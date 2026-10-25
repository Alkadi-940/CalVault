package dev.calvault.core

import java.io.*
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private data class StoredItem(val item: VaultItem, val wrappedKey: ByteArray, val digest: ByteArray, val revision: Long = 0)
private data class Catalog(val records: List<StoredItem>, val folders: List<String>, val revision: Long = 0, val formatVersion: Int = 3, val deletedFolders: List<TrashedFolder> = emptyList())
private data class Envelope(
    val id: String, val pinSalt: ByteArray, val deviceWrapped: ByteArray,
    val recoverySalt: ByteArray, val recoveryWrapped: ByteArray,
)
private class Session(val envelope: Envelope, val master: ByteArray, var records: List<StoredItem>, var folders: List<String> = listOf("Private"), var revision: Long = 0, var deletedFolders: List<TrashedFolder> = emptyList()) {
    val active = AtomicBoolean(true)
    fun check() = check(active.get()) { "Vault locked. Unlock to continue." }
}

/** Encrypted catalog is the commit point. Blobs are immutable and promoted before catalog commits. */
class Vault(private val root: File, private val device: DeviceKey, private val syncDirectory: (File) -> Unit = {}) {
    private val header get() = File(root, "envelope.bin")
    private val catalog get() = File(root, "catalog.enc")
    private val navigation get() = File(root, "navigation.enc")
    private val objects get() = File(root, "objects").also { it.mkdirs() }
    @Volatile private var session: Session? = null
    @Volatile private var restoring: Session? = null
    private val generation = AtomicLong()
    private val sessionGuard = Any()
    private val navigationGuard = Any()
    @Volatile var lastRepair = RepairReport()
        private set
    val exists: Boolean get() = header.isFile
    val unlocked: Boolean get() = session?.active?.get() == true
    private fun current(): Session = checkNotNull(session) { "Unlock your vault first" }.also { it.check() }
    @Synchronized fun items(): List<VaultItem> = current().records.filter { it.item.deletedAt == 0L }.map { it.item.copy(missing = !objectFile(it.item.id).isFile) }
    @Synchronized fun trash(): List<VaultItem> = current().records.filter { it.item.deletedAt > 0 }.map { it.item.copy(missing = !objectFile(it.item.id).isFile) }
    @Synchronized fun folders(): List<String> = current().folders.toList()
    @Synchronized fun trashFolders(): List<TrashedFolder> = current().deletedFolders.toList()
    val identity: String get() = readEnvelope().id

    @Synchronized fun createFolder(name: String, parent: String? = null) {
        val s = current()
        require(parent == null || parent in s.folders) { "Parent folder no longer exists" }
        val path = FolderPath.child(parent, name)
        require(s.folders.none { it.equals(path, true) }) { "A folder with this name already exists here" }
        commit(s, s.records, s.folders + path)
    }
    @Synchronized fun renameFolder(old: String, name: String) {
        val s = current()
        require(old in s.folders)
        val replacement = FolderPath.child(FolderPath.parent(old), name)
        require(s.folders.none { !FolderPath.within(it, old) && it.equals(replacement, true) }) { "A folder with this name already exists here" }
        val folders = s.folders.map { FolderPath.renamed(it, old, replacement) }.also { it.forEach(FolderPath::validate) }
        val records = s.records.map { if (FolderPath.within(it.item.folder, old) && it.item.deletedAt == 0L) it.copy(item = it.item.copy(folder = FolderPath.renamed(it.item.folder, old, replacement)), revision = s.revision + 1) else it }
        records.filter { it.revision == s.revision + 1 }.forEach { writeRecovery(s, it) }
        commit(s, records, folders)
    }
    @Synchronized fun deleteFolder(name: String) {
        val s = current()
        require(name in s.folders) { "Folder no longer exists" }
        val now = System.currentTimeMillis().coerceAtLeast(1)
        val subtree = s.folders.filter { FolderPath.within(it, name) }
        val deleted = TrashedFolder(UUID.randomUUID().toString(), name, now, subtree)
        val records = s.records.map { if (FolderPath.within(it.item.folder, name) && it.item.deletedAt == 0L) it.copy(item = it.item.copy(deletedAt = now, trashFolderId = deleted.id), revision = s.revision + 1) else it }
        records.filter { it.item.trashFolderId == deleted.id }.forEach { writeRecovery(s, it) }
        commit(s, records, s.folders - subtree.toSet(), s.deletedFolders + deleted)
    }
    @Synchronized fun restoreFolder(id: String) {
        val s = current()
        val folder = s.deletedFolders.firstOrNull { it.id == id } ?: error("Folder is no longer in the recycle bin")
        val records = s.records.map { if (it.item.trashFolderId == folder.id) it.copy(item = it.item.copy(deletedAt = 0, trashFolderId = ""), revision = s.revision + 1) else it }
        records.filter { it.revision == s.revision + 1 }.forEach { writeRecovery(s, it) }
        commit(s, records, (s.folders + folder.paths).distinct(), s.deletedFolders.filterNot { it.id == id })
    }
    @Synchronized fun permanentlyDeleteFolder(id: String) {
        val s = current()
        val folder = s.deletedFolders.firstOrNull { it.id == id } ?: return
        removeObjects(s, s.records.filter { it.item.trashFolderId == folder.id }.map { it.item.id }.toSet())
        atomicWrite(File(objects, "$id.foldergone"), byteArrayOf())
        commit(s, s.records, s.folders, s.deletedFolders.filterNot { it.id == id })
    }

    /** Small encrypted navigation record; independent of the potentially long-running import monitor. */
    fun saveNavigation(json: String) = synchronized(navigationGuard) {
        require(json.toByteArray().size <= MAX_NAVIGATION)
        val s = current()
        val key = subkey(s, "navigation")
        val encrypted = try { Crypto.seal(key, json.toByteArray(), aad(s.envelope.id, "navigation")) } finally { key.fill(0) }
        s.check()
        atomicWrite(navigation, encrypted)
    }
    fun loadNavigation(): String = synchronized(navigationGuard) {
        if (!navigation.isFile) return@synchronized ""
        val s = current()
        val key = subkey(s, "navigation")
        val plain = try { Crypto.open(key, navigation.readBounded(MAX_NAVIGATION + 28), aad(s.envelope.id, "navigation")) } finally { key.fill(0) }
        try { s.check(); String(plain, Charsets.UTF_8) } finally { plain.fill(0) }
    }

    fun lock() = synchronized(sessionGuard) {
        generation.incrementAndGet()
        val previous = session
        session = null
        restoring?.active?.set(false)
        previous?.active?.set(false)
        previous?.master?.fill(0)
    }
    private fun publish(next: Session, ticket: Long) = synchronized(sessionGuard) {
        if (generation.get() != ticket || !next.active.get()) {
            next.active.set(false); next.master.fill(0)
            error("Vault locked. Unlock to continue.")
        }
        session = next
    }

    @Synchronized fun create(pin: CharArray, recovery: String) {
        val ticket = generation.get()
        check(!exists) { "A vault already exists" }
        root.mkdirs()
        val master = Crypto.random(32)
        try {
            val id = UUID.randomUUID().toString()
            val secret = Crypto.parseRecovery(recovery)
            val recoverySalt = Crypto.random(16)
            val recoveryKey = try { Crypto.derive(secret, recoverySalt, "recovery/$id") } finally { secret.fill(0) }
            val recoveryWrapped = try { Crypto.seal(recoveryKey, master, aad(id, "recovery")) } finally { recoveryKey.fill(0) }
            val base = Envelope(id, ByteArray(0), ByteArray(0), recoverySalt, recoveryWrapped)
            val envelope = withPin(base, master, pin)
            val next = Session(envelope, master.copyOf(), emptyList())
            try {
                check(generation.get() == ticket) { "Vault locked. Unlock to continue." }
                commit(next, emptyList())
                atomicWrite(header, encodeEnvelope(envelope))
                publish(next, ticket)
            } catch (e: Exception) { next.master.fill(0); throw e }
        } finally { master.fill(0); pin.fill('\u0000') }
    }

    @Synchronized fun unlock(pin: CharArray, automaticRepair: Boolean = true) {
        lock()
        val ticket = generation.get()
        val envelope = readEnvelope()
        val inner = device.open(envelope.deviceWrapped, aad(envelope.id, "device"))
        val key = try { Crypto.pinKey(pin, envelope.pinSalt) } finally { pin.fill('\u0000') }
        val master = try { Crypto.open(key, inner, aad(envelope.id, "pin")) }
        finally { key.fill(0); inner.fill(0) }
        try {
            val next = loadSession(envelope, master, automaticRepair)
            publish(next, ticket)
        } catch (e: Exception) { master.fill(0); throw e }
    }

    @Synchronized fun recover(recovery: String, newPin: CharArray) {
        lock()
        val ticket = generation.get()
        val previous = readEnvelope()
        val master = recoverMaster(previous, recovery)
        try {
            val loaded = loadSession(previous, master.copyOf(), true)
            val replacement = withPin(previous, master, newPin)
            atomicWrite(header, encodeEnvelope(replacement))
            val next = Session(replacement, master.copyOf(), loaded.records, loaded.folders, loaded.revision, loaded.deletedFolders)
            loaded.master.fill(0)
            publish(next, ticket)
        } finally { master.fill(0); newPin.fill('\u0000') }
    }

    @Synchronized fun changePin(newPin: CharArray) {
        val ticket = generation.get()
        val old = current()
        val next = try { withPin(old.envelope, old.master, newPin) } finally { newPin.fill('\u0000') }
        old.check()
        atomicWrite(header, encodeEnvelope(next))
        publish(Session(next, old.master.copyOf(), old.records, old.folders, old.revision, old.deletedFolders), ticket)
        old.active.set(false)
        old.master.fill(0)
    }

    @Synchronized fun importFile(
        name: String, mime: String, folder: String, input: InputStream,
        mode: StorageMode = StorageMode.ENCRYPTED, source: SourceLocation = SourceLocation(),
        progress: (Long) -> Unit = {},
    ): VaultItem {
        val s = current()
        require(s.records.size < MAX_ITEMS) { "Vault item limit reached" }
        validName(name); validFolder(folder)
        require(mime.length <= 200)
        require(listOf(source.uri, source.relativePath, source.collection, source.treeUri, source.parentHint).all { it.length <= MAX_LOCATION }) {
            "Source location is too long to store safely. The original was retained."
        }
        val id = UUID.randomUUID().toString()
        val staging = File(objects, "$id.part")
        val target = objectFile(id)
        val key = Crypto.random(32)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            FileOutputStream(staging).use { file ->
                // Keep the descriptor open long enough to sync after the final authentication tag.
                val nonClosing = object : FilterOutputStream(file) {
                    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                    override fun close() { flush() }
                }
                StreamCipher(key).encrypt(nonClosing, aad(s.envelope.id, id)).use { encrypted ->
                    val buffer = ByteArray(BUFFER)
                    try {
                        while (true) {
                            s.check()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            encrypted.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            size = Math.addExact(size, count.toLong())
                            require(size <= MAX_FILE_SIZE) { "File exceeds the supported size" }
                            progress(size)
                        }
                    } finally { buffer.fill(0) }
                }
                file.fd.sync()
            }
            val hash = digest.digest()
            staging.inputStream().use { verifyObject(s, key, id, it, size, hash) }
            s.check()
            // Both modes retain the same authenticated encryption, as explicitly chosen by the owner.
            val actualFolder = folder.ifBlank { DEFAULT_FOLDER }
            val item = VaultItem(id, name, mime, actualFolder, size, System.currentTimeMillis(), mode = mode, source = source,
                sourceState = if (source.uri.isBlank()) SourceState.UNKNOWN else SourceState.PENDING_REMOVAL)
            val wrappingKey = subkey(s, "object-keys")
            val wrapped = try { Crypto.seal(wrappingKey, key, aad(s.envelope.id, "$id/key")) } finally { wrappingKey.fill(0) }
            val stored = StoredItem(item, wrapped, hash, s.revision + 1)
            val records = s.records + stored
            writeRecovery(s, stored)
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(objects)
            commit(s, records, (s.folders + actualFolder).distinct())
            syncDirectory(root)
            return item
        } catch (e: Exception) {
            staging.delete()
            // If the catalog commit failed, preserve the ciphertext for authenticated recovery.
            throw e
        } finally { key.fill(0) }
    }

    @Synchronized fun rename(id: String, name: String, folder: String) {
        validName(name); validFolder(folder)
        val s = current()
        require(s.records.any { it.item.id == id }) { "File is no longer available" }
        val actualFolder = folder.ifBlank { DEFAULT_FOLDER }
        val records = s.records.map { if (it.item.id == id) it.copy(item = it.item.copy(name = name, folder = actualFolder), revision = s.revision + 1) else it }
        writeRecovery(s, records.first { it.item.id == id })
        commit(s, records, (s.folders + actualFolder).distinct())
    }

    @Synchronized fun delete(ids: Set<String>) {
        val s = current()
        val now = System.currentTimeMillis().coerceAtLeast(1)
        val records = s.records.map { if (it.item.id in ids && it.item.deletedAt == 0L) it.copy(item = it.item.copy(deletedAt = now, trashFolderId = ""), revision = s.revision + 1) else it }
        records.filter { it.item.id in ids }.forEach { writeRecovery(s, it) }
        commit(s, records)
    }
    @Synchronized fun restoreTrash(ids: Set<String>) {
        val s = current()
        val records = s.records.map { if (it.item.id in ids) it.copy(item = it.item.copy(deletedAt = 0, trashFolderId = ""), revision = s.revision + 1) else it }
        records.filter { it.item.id in ids }.forEach { writeRecovery(s, it) }
        commit(s, records, (s.folders + records.filter { it.item.id in ids }.map { it.item.folder }).distinct())
    }
    @Synchronized fun permanentlyDelete(ids: Set<String>) {
        val s = current()
        require(s.records.filter { it.item.id in ids }.all { it.item.deletedAt > 0 }) { "Move files to the recycle bin first" }
        removeObjects(s, ids)
    }
    /** Called only after writing and verifying the unhidden destination. */
    @Synchronized fun finishUnhide(ids: Set<String>) { removeObjects(current(), ids) }
    private fun removeObjects(s: Session, ids: Set<String>) {
        var failures = 0
        for (stored in s.records.filter { it.item.id in ids }) {
            s.check()
            atomicWrite(File(objects, "${stored.item.id}.gone"), byteArrayOf())
            val ownedCopies = objects.listFiles().orEmpty().filter { it.name.startsWith("${stored.item.id}.") && it.extension != "gone" }
            val deleted = ownedCopies.map { !it.exists() || it.delete() }.all { it }
            if (deleted) {
                commit(s, s.records.filterNot { it.item.id == stored.item.id })
            } else failures++
        }
        check(failures == 0) { "$failures file(s) could not be removed. Try again." }
    }
    @Synchronized fun markOriginalRemoved(id: String) {
        val s = current()
        val records = s.records.map { if (it.item.id == id) it.copy(item = it.item.copy(sourceState = SourceState.REMOVED), revision = s.revision + 1) else it }
        writeRecovery(s, records.first { it.item.id == id })
        commit(s, records)
    }

    @Synchronized fun export(id: String, output: OutputStream) {
        val s = current()
        val record = record(s, id)
        val key = objectKey(s, record)
        try {
            objectFile(id).inputStream().use { raw ->
                StreamCipher(key).decrypt(raw, aad(s.envelope.id, id)).use { plain ->
                    val hash = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    copy(plain, output, s) { buffer, count -> hash.update(buffer, 0, count); size += count }
                    require(size == record.item.size && MessageDigest.isEqual(hash.digest(), record.digest)) { "File verification failed" }
                }
            }
        } finally { key.fill(0) }
    }

    /** Recheck both copies immediately before an explicitly requested source-provider deletion. */
    @Synchronized fun verifyOriginal(id: String, original: InputStream) {
        val s = current()
        val stored = record(s, id)
        require(stored.item.deletedAt == 0L) { "Restore this file from the recycle bin first" }
        val key = objectKey(s, stored)
        try { objectFile(id).inputStream().use { verifyObject(s, key, id, it, stored.item.size, stored.digest) } }
        finally { key.fill(0) }
        val hash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        copy(original, null, s) { buffer, count ->
            hash.update(buffer, 0, count); size += count
            require(size <= stored.item.size) { "Original changed. Import it again before removing it." }
        }
        require(size == stored.item.size && MessageDigest.isEqual(hash.digest(), stored.digest)) {
            "Original changed. Import it again before removing it."
        }
        syncDirectory(objects)
        syncDirectory(root)
        s.check()
    }

    @Synchronized fun verifyDestination(id: String, input: InputStream) {
        val s = current()
        val stored = record(s, id)
        val hash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        copy(input, null, s) { buffer, count -> hash.update(buffer, 0, count); size += count; require(size <= stored.item.size) { "Destination size mismatch" } }
        require(size == stored.item.size && MessageDigest.isEqual(hash.digest(), stored.digest)) { "Destination verification failed. The vault file was retained." }
    }

    @Synchronized fun openMedia(id: String): SeekableByteChannel {
        val s = current()
        val record = record(s, id)
        val key = objectKey(s, record)
        val file = Files.newByteChannel(objectFile(id).toPath())
        val plain = try { StreamCipher(key).seekable(file, aad(s.envelope.id, id)) }
        catch (e: Exception) { file.close(); throw e } finally { key.fill(0) }
        return object : SeekableByteChannel {
            override fun read(dst: java.nio.ByteBuffer): Int { s.check(); return plain.read(dst) }
            override fun write(src: java.nio.ByteBuffer): Int = throw UnsupportedOperationException()
            override fun position(): Long { s.check(); return plain.position() }
            override fun position(newPosition: Long): SeekableByteChannel { s.check(); plain.position(newPosition); return this }
            override fun size(): Long { s.check(); return plain.size() }
            override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()
            override fun isOpen(): Boolean = s.active.get() && plain.isOpen
            override fun close() = plain.close()
        }
    }

    @Synchronized fun writeBackup(output: OutputStream) {
        val s = current()
        val salt = Crypto.random(16)
        val portable = encodeEnvelope(s.envelope.copy(pinSalt = ByteArray(0), deviceWrapped = ByteArray(0)))
        val publicHeader = encode { writeInt(BACKUP_MAGIC); blob(portable); blob(salt) }
        val raw = DataOutputStream(output)
        raw.blob(publicHeader)
        val key = Crypto.derive(s.master, salt, "backup/${s.envelope.id}")
        try {
            DataOutputStream(StreamCipher(key).encrypt(raw, publicHeader)).use { payload ->
                payload.blob(catalog.readBounded(MAX_CATALOG))
                payload.blob(if (navigation.isFile) navigation.readBounded(MAX_NAVIGATION + 28) else byteArrayOf())
                payload.writeInt(s.records.size)
                s.records.forEach { record ->
                    s.check()
                    payload.writeUTF(record.item.id)
                    val file = objectFile(record.item.id)
                    payload.writeLong(file.length())
                    file.inputStream().use { copy(it, payload, s) }
                }
            }
        } finally { key.fill(0) }
    }

    @Synchronized fun verifyBackup(input: InputStream) {
        val s = current()
        val backup = readBackupHeader(input)
        require(backup.envelope.id == s.envelope.id) { "Backup belongs to a different vault" }
        consumeBackup(backup, input, s.master, s, null)
    }

    @Synchronized fun restoreBackup(input: InputStream, recovery: String, pin: CharArray) {
        val ticket = generation.get()
        check(!exists) { "Restore requires a new installation without an existing vault" }
        val backup = readBackupHeader(input)
        val master = recoverMaster(backup.envelope, recovery)
        val staging = File(root.parentFile, "restore-${UUID.randomUUID()}")
        staging.mkdirs()
        val tempSession = Session(backup.envelope, master, emptyList())
        restoring = tempSession
        // The caller may cancel restoration through its input stream; no live vault is modified.
        try {
            val restored = consumeBackup(backup, input, master, tempSession, staging)
            val encryptedCatalog = restored.catalog
            val envelope = withPin(backup.envelope, master, pin)
            tempSession.check()
            root.mkdirs()
            val children = staging.listFiles().orEmpty()
            children.forEach { Files.move(it.toPath(), File(objects, it.name).toPath(), StandardCopyOption.REPLACE_EXISTING) }
            atomicWrite(catalog, encryptedCatalog)
            if (restored.navigation.isNotEmpty()) atomicWrite(navigation, restored.navigation)
            val loaded = readCatalog(envelope, master, encryptedCatalog)
            val next = Session(envelope, master.copyOf(), loaded.records, loaded.folders, loaded.revision, loaded.deletedFolders)
            next.records.forEach { writeRecovery(next, it) }
            // Header is the commit point: all recovery metadata is ready before initialization.
            atomicWrite(header, encodeEnvelope(envelope))
            publish(next, ticket)
        } finally {
            tempSession.active.set(false)
            restoring = null
            master.fill(0); pin.fill('\u0000')
            staging.listFiles().orEmpty().forEach { it.delete() }
            staging.delete()
        }
    }

    private data class BackupHeader(val public: ByteArray, val envelope: Envelope, val salt: ByteArray, val version: Int)
    private data class BackupContents(val catalog: ByteArray, val navigation: ByteArray)
    private fun readBackupHeader(input: InputStream): BackupHeader {
        val public = DataInputStream(input).blob(2048)
        return decode(public) {
            val magic = readInt()
            require(magic == BACKUP_MAGIC || magic == LEGACY_BACKUP_MAGIC) { "Unsupported backup format" }
            val envelope = decodeEnvelope(blob(1024))
            require(envelope.pinSalt.isEmpty() && envelope.deviceWrapped.isEmpty()) { "Invalid portable envelope" }
            val salt = blob(16).also { require(it.size == 16) }
            BackupHeader(public, envelope, salt, if (magic == BACKUP_MAGIC) 2 else 1)
        }
    }

    private fun consumeBackup(backup: BackupHeader, input: InputStream, master: ByteArray, s: Session, destination: File?): BackupContents {
        val key = Crypto.derive(master, backup.salt, "backup/${backup.envelope.id}")
        try {
            DataInputStream(StreamCipher(key).decrypt(input, backup.public)).use { payload ->
                val encryptedCatalog = payload.blob(MAX_CATALOG)
                val records = readCatalog(backup.envelope, master, encryptedCatalog).records
                val nav = if (backup.version == 2) payload.blob(MAX_NAVIGATION + 28) else byteArrayOf()
                if (nav.isNotEmpty()) {
                    val navKey = Crypto.derive(master, backup.envelope.id.toByteArray(), "navigation")
                    try { Crypto.open(navKey, nav, aad(backup.envelope.id, "navigation")).fill(0) } finally { navKey.fill(0) }
                }
                val count = payload.readInt()
                require(count == records.size) { "Backup manifest mismatch" }
                val backupSession = Session(backup.envelope, master, records)
                records.forEach { record ->
                    s.check()
                    require(payload.readUTF() == record.item.id) { "Backup object mismatch" }
                    val size = payload.readLong()
                    // Tink streaming overhead is small; cap malicious lengths before reading or allocating.
                    require(size >= 0 && size <= record.item.size + record.item.size / 100 + 4096) { "Invalid backup object size" }
                    val limited = LimitedInput(payload, size)
                    val objectKey = objectKey(backupSession, record)
                    try {
                        if (destination == null) {
                            verifyObject(s, objectKey, record.item.id, limited, record.item.size, record.digest, backup.envelope.id)
                        } else {
                            val file = File(destination, "${record.item.id}.cvf")
                            FileOutputStream(file).use { out -> copy(limited, out, s); out.fd.sync() }
                            file.inputStream().use { verifyObject(s, objectKey, record.item.id, it, record.item.size, record.digest, backup.envelope.id) }
                        }
                        require(limited.remaining == 0L) { "Truncated backup object" }
                    } finally { objectKey.fill(0) }
                }
                require(payload.read() == -1) { "Unexpected backup data" }
                s.check()
                return BackupContents(encryptedCatalog, nav)
            }
        } finally { key.fill(0) }
    }

    /** Restores missing/damaged objects from this vault's backup without replacing healthy files. */
    @Synchronized fun retrieveFromBackup(input: InputStream, recovery: String): Int {
        val s = current()
        val backup = readBackupHeader(input)
        require(backup.envelope.id == s.envelope.id) { "Choose a backup from this vault" }
        val recoveredMaster = recoverMaster(backup.envelope, recovery)
        val staging = File(root.parentFile, "retrieve-${UUID.randomUUID()}").also { it.mkdirs() }
        try {
            require(MessageDigest.isEqual(recoveredMaster, s.master)) { "Backup key does not match this vault" }
            val payload = consumeBackup(backup, input, recoveredMaster, s, staging)
            val backupCatalog = readCatalog(backup.envelope, recoveredMaster, payload.catalog)
            var retrieved = 0
            var records = s.records
            for (stored in backupCatalog.records) {
                s.check()
                val id = stored.item.id
                if (File(objects, "$id.gone").exists()) continue
                val current = records.firstOrNull { it.item.id == id }
                val healthy = current != null && runCatching {
                    val key = objectKey(s, current)
                    try { objectFile(id).inputStream().use { verifyObject(s, key, id, it, current.item.size, current.digest) } } finally { key.fill(0) }
                }.isSuccess
                if (healthy) continue
                val target = objectFile(id)
                if (target.exists()) Files.copy(target.toPath(), File(objects, "$id.damaged-${UUID.randomUUID()}").toPath())
                Files.copy(File(staging, "$id.cvf").toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                val recovered = (current ?: stored).copy(revision = s.revision + 1)
                writeRecovery(s, recovered)
                records = records.filterNot { it.item.id == id } + recovered
                retrieved++
            }
            commit(s, records, (s.folders + records.filter { it.item.deletedAt == 0L }.map { it.item.folder }).distinct())
            return retrieved
        } finally {
            recoveredMaster.fill(0)
            // Only this operation's encrypted staging copies are cleaned up, never original vault objects.
            staging.listFiles().orEmpty().forEach { it.delete() }
            staging.delete()
        }
    }

    private fun withPin(envelope: Envelope, master: ByteArray, pin: CharArray): Envelope {
        val salt = Crypto.random(16)
        val key = Crypto.pinKey(pin, salt)
        val inner = try { Crypto.seal(key, master, aad(envelope.id, "pin")) } finally { key.fill(0) }
        val outer = try { device.seal(inner, aad(envelope.id, "device")) } finally { inner.fill(0) }
        return envelope.copy(pinSalt = salt, deviceWrapped = outer)
    }

    private fun recoverMaster(envelope: Envelope, recovery: String): ByteArray {
        val secret = Crypto.parseRecovery(recovery)
        val key = try { Crypto.derive(secret, envelope.recoverySalt, "recovery/${envelope.id}") } finally { secret.fill(0) }
        return try { Crypto.open(key, envelope.recoveryWrapped, aad(envelope.id, "recovery")) }
        finally { key.fill(0) }
    }
    private fun subkey(s: Session, purpose: String) = Crypto.derive(s.master, s.envelope.id.toByteArray(), purpose)
    private fun record(s: Session, id: String) = s.records.firstOrNull { it.item.id == id } ?: error("File is no longer available")
    private fun objectKey(s: Session, record: StoredItem): ByteArray {
        val key = subkey(s, "object-keys")
        return try { Crypto.open(key, record.wrappedKey, aad(s.envelope.id, "${record.item.id}/key")) }
        finally { key.fill(0) }
    }
    private fun objectFile(id: String): File { validId(id); return File(objects, "$id.cvf") }
    private fun commit(s: Session, records: List<StoredItem>, folders: List<String> = s.folders, deletedFolders: List<TrashedFolder> = s.deletedFolders) {
        val expanded = FolderPath.expand(folders)
        saveCatalog(s, records, expanded, deletedFolders)
        s.records = records
        s.folders = expanded
        s.deletedFolders = deletedFolders
        s.revision++
    }
    private fun saveCatalog(s: Session, records: List<StoredItem>, folders: List<String> = s.folders, deletedFolders: List<TrashedFolder> = s.deletedFolders) {
        s.check()
        require(records.size <= MAX_ITEMS && folders.size <= MAX_ITEMS && deletedFolders.size <= MAX_ITEMS) { "Vault catalog limit reached" }
        val plain = encode {
            writeInt(3); writeLong(s.revision + 1)
            writeInt(folders.size); folders.forEach { writeUTF(it) }
            writeInt(deletedFolders.size); deletedFolders.forEach { writeUTF(it.id); writeUTF(it.name); writeLong(it.deletedAt); writeInt(it.paths.size); it.paths.forEach(::writeUTF) }
            writeInt(records.size); records.forEach { writeRecord(it) }
        }
        require(plain.size < MAX_CATALOG - 28) { "Vault catalog is full" }
        val key = subkey(s, "metadata")
        val encrypted = try { Crypto.seal(key, plain, aad(s.envelope.id, "catalog")) }
        finally { key.fill(0); plain.fill(0) }
        s.check()
        if (catalog.isFile) {
            val old = catalog.readBounded(MAX_CATALOG)
            val oldFormat = runCatching { readCatalog(s.envelope, s.master, old) }.getOrNull()
            if (oldFormat != null) {
                atomicWrite(File(root, "catalog.previous"), old)
                val legacySnapshot = File(root, "catalog.v1.snapshot")
                if (oldFormat.formatVersion == 1 && !legacySnapshot.exists()) atomicWrite(legacySnapshot, old)
            } else {
                // Preserve the original encrypted bytes for recovery rather than discarding a damaged catalog.
                atomicWrite(File(root, "catalog.repair-source-${UUID.randomUUID()}.enc"), old)
            }
        }
        atomicWrite(catalog, encrypted)
    }
    private fun DataOutputStream.writeRecord(stored: StoredItem) {
        val item = stored.item
        writeUTF(item.id); writeUTF(item.name); writeUTF(item.mime); writeUTF(item.folder)
        writeLong(item.size); writeLong(item.created); blob(stored.wrappedKey); blob(stored.digest)
        writeByte(item.mode.ordinal); writeLong(item.deletedAt)
        writeUTF(item.source.uri); writeUTF(item.source.relativePath); writeUTF(item.source.collection)
        writeUTF(item.source.treeUri); writeUTF(item.source.parentHint)
        writeByte(item.sourceState.ordinal); writeUTF(item.trashFolderId); writeLong(stored.revision)
    }
    private fun DataInputStream.readRecord(version: Int): StoredItem {
        val id = readUTF().also(::validId)
        val name = readUTF().also(::validName)
        val mime = readUTF().also { require(it.length <= 200) }
        val folder = readUTF().also(::validFolder).ifBlank { DEFAULT_FOLDER }
        val size = readLong().also { require(it in 0..MAX_FILE_SIZE) }
        val created = readLong()
        val wrapped = blob(128).also { require(it.size == 60) }
        val hash = blob(32).also { require(it.size == 32) }
        if (version == 1) return StoredItem(VaultItem(id, name, mime, folder, size, created), wrapped, hash)
        val mode = StorageMode.entries.getOrNull(readUnsignedByte()) ?: error("Unknown storage mode")
        val deletedAt = readLong().also { require(it >= 0) }
        fun sourceString() = readUTF().also { require(it.length <= MAX_LOCATION) }
        val source = SourceLocation(sourceString(), sourceString(), sourceString(), sourceString(), sourceString())
        val state = SourceState.entries.getOrNull(readUnsignedByte()) ?: error("Unknown source state")
        val trashFolder = readUTF().also { if (it.isNotEmpty()) validId(it) }
        val revision = readLong().also { require(it in 0 until Long.MAX_VALUE) }
        return StoredItem(VaultItem(id, name, mime, folder, size, created, mode, deletedAt, source, state, trashFolder), wrapped, hash, revision)
    }
    private fun readCatalog(envelope: Envelope, master: ByteArray, encrypted: ByteArray): Catalog {
        val key = Crypto.derive(master, envelope.id.toByteArray(), "metadata")
        val plain = try { Crypto.open(key, encrypted, aad(envelope.id, "catalog")) } finally { key.fill(0) }
        return try {
            decode(plain) {
                val version = readInt()
                require(version in 1..3) { "Unsupported catalog version" }
                val revision = if (version >= 2) readLong().also { require(it in 0 until Long.MAX_VALUE) } else 0
                val folders = if (version >= 2) {
                    val count = readInt().also { require(it in 0..MAX_ITEMS) }
                    List(count) { readUTF().also(::validFolderName) }.also { require(it.distinct().size == it.size) }
                } else emptyList()
                val deletedFolders = if (version >= 2) {
                    val count = readInt().also { require(it in 0..MAX_ITEMS) }
                    List(count) {
                        val id = readUTF().also(::validId)
                        val path = readUTF().also(::validFolderName)
                        val deleted = readLong().also { require(it > 0) }
                        val paths = if (version >= 3) List(readInt().also { require(it in 1..MAX_ITEMS) }) { readUTF().also(::validFolderName) } else listOf(path)
                        require(path in paths && paths.distinct().size == paths.size && paths.all { FolderPath.within(it, path) })
                        TrashedFolder(id, path, deleted, paths)
                    }
                } else emptyList()
                val count = readInt()
                require(count in 0..MAX_ITEMS) { "Invalid catalog count" }
                val ids = mutableSetOf<String>()
                val records = List(count) {
                    readRecord(version).also { require(ids.add(it.item.id)) { "Duplicate object ID" } }
                }
                Catalog(records, if (version == 1) (records.map { it.item.folder } + DEFAULT_FOLDER).distinct() else folders, revision, version, deletedFolders)
            }
        } finally { plain.fill(0) }
    }
    private fun writeRecovery(s: Session, stored: StoredItem) {
        s.check()
        val plain = encode { writeInt(3); writeRecord(stored) }
        val key = subkey(s, "recovery-records")
        val encrypted = try { Crypto.seal(key, plain, aad(s.envelope.id, "${stored.item.id}/recovery")) }
        finally { key.fill(0); plain.fill(0) }
        s.check()
        atomicWrite(File(objects, "${stored.item.id}.meta"), encrypted)
    }
    private fun readRecovery(s: Session, id: String): StoredItem {
        validId(id)
        val key = subkey(s, "recovery-records")
        val plain = try { Crypto.open(key, File(objects, "$id.meta").readBounded(128 * 1024), aad(s.envelope.id, "$id/recovery")) }
        finally { key.fill(0) }
        return try { decode(plain) { val version = readInt(); require(version in 2..3); readRecord(version).also { require(it.item.id == id) } } }
        finally { plain.fill(0) }
    }
    private fun loadSession(envelope: Envelope, master: ByteArray, automaticRepair: Boolean): Session {
        try {
            var fromFallback = false
            val loaded = try { readCatalog(envelope, master, catalog.readBounded(MAX_CATALOG)) }
            catch (e: Exception) {
                if (!automaticRepair || e.message == "Unsupported catalog version") throw e
                fromFallback = true
                runCatching { readCatalog(envelope, master, File(root, "catalog.previous").readBounded(MAX_CATALOG)) }.getOrNull()
            }
            val s = Session(envelope, master, loaded?.records.orEmpty(), loaded?.folders.orEmpty(), loaded?.revision ?: 0, loaded?.deletedFolders.orEmpty())
            val result = reconcile(s, automaticRepair)
            check(loaded != null || result.recovered > 0) { "No usable recovery records. Restore your encrypted backup." }
            if (fromFallback || loaded?.formatVersion != 3 || result.recovered > 0 || s.records != loaded.records || s.folders != loaded.folders || s.deletedFolders != loaded.deletedFolders) commit(s, s.records, s.folders)
            lastRepair = result
            return s
        } catch (e: Exception) { master.fill(0); throw e }
    }
    @Synchronized fun repairStorage(): RepairReport {
        val s = current()
        val report = reconcile(s, true)
        commit(s, s.records, s.folders)
        lastRepair = report
        return report
    }
    /** Repair only reindexes verified data. It never deletes blobs, partial files, or recovery records. */
    private fun reconcile(s: Session, automaticRepair: Boolean): RepairReport {
        var recovered = 0
        var damaged = 0
        val records = s.records.associateBy { it.item.id }.toMutableMap()
        val files = objects.listFiles().orEmpty()
        val tombstones = files.filter { it.extension == "gone" }.map { it.nameWithoutExtension }.toSet()
        s.deletedFolders = s.deletedFolders.filterNot { File(objects, "${it.id}.foldergone").exists() }
        // Suppress completed explicit deletions in a fallback catalog without deleting any files.
        records.entries.removeAll { (id, _) -> id in tombstones && !objectFile(id).exists() && !File(objects, "$id.meta").exists() }
        if (automaticRepair) for (file in files.filter { it.extension == "meta" }) {
            s.check()
            val id = file.nameWithoutExtension
            if (id in tombstones) continue
            try {
                val candidate = readRecovery(s, id)
                val known = records[id]
                val part = File(objects, "$id.part")
                val blob = objectFile(id)
                if (known != null && known.revision >= candidate.revision && blob.exists()) continue
                val readable = if (blob.exists()) blob else part.takeIf { it.isFile } ?: continue
                val key = objectKey(s, candidate)
                try { readable.inputStream().use { verifyObject(s, key, id, it, candidate.item.size, candidate.digest) } }
                finally { key.fill(0) }
                if (readable == part) Files.copy(part.toPath(), blob.toPath())
                records[id] = candidate
                s.revision = maxOf(s.revision, candidate.revision)
                recovered++
            } catch (e: Exception) { s.check(); damaged++ }
        }
        s.records = records.values.toList()
        s.folders = FolderPath.expand(s.folders + s.records.filter { it.item.deletedAt == 0L }.map { it.item.folder })
        for (stored in s.records) {
            if (stored.item.id !in tombstones && !File(objects, "${stored.item.id}.meta").exists()) runCatching { writeRecovery(s, stored) }.onFailure { s.check(); damaged++ }
        }
        return RepairReport(recovered, s.records.count { !objectFile(it.item.id).isFile }, damaged)
    }
    private fun readEnvelope() = decodeEnvelope(header.readBounded(1024))
    private fun encodeEnvelope(e: Envelope): ByteArray = encode {
        writeInt(ENVELOPE_MAGIC); writeUTF(e.id); blob(e.pinSalt); blob(e.deviceWrapped)
        blob(e.recoverySalt); blob(e.recoveryWrapped)
    }
    private fun decodeEnvelope(bytes: ByteArray): Envelope = decode(bytes) {
        require(readInt() == ENVELOPE_MAGIC) { "Unsupported vault format" }
        val id = readUTF().also(::validId)
        val salt = blob(16).also { require(it.size == 16 || it.isEmpty()) }
        val outer = blob(256)
        val recoverySalt = blob(16).also { require(it.size == 16) }
        val recoveryWrapped = blob(128).also { require(it.size == 60) }
        Envelope(id, salt, outer, recoverySalt, recoveryWrapped)
    }

    private fun verifyObject(s: Session, key: ByteArray, id: String, input: InputStream, expectedSize: Long, expectedHash: ByteArray, vaultId: String = s.envelope.id) {
        val hash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        StreamCipher(key).decrypt(object : FilterInputStream(input) { override fun close() {} }, aad(vaultId, id)).use { plain ->
            copy(plain, null, s) { buffer, count ->
                hash.update(buffer, 0, count); size += count
                require(size <= expectedSize) { "File size mismatch" }
            }
        }
        require(size == expectedSize && MessageDigest.isEqual(hash.digest(), expectedHash)) { "File verification failed" }
    }
    private fun copy(input: InputStream, output: OutputStream?, s: Session, consume: (ByteArray, Int) -> Unit = { _, _ -> }) {
        val buffer = ByteArray(BUFFER)
        try {
            while (true) {
                s.check()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output?.write(buffer, 0, count)
                consume(buffer, count)
            }
        } finally { buffer.fill(0) }
    }
    private class LimitedInput(input: InputStream, var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining == 0L) return -1
            val value = super.read()
            if (value < 0) throw EOFException("Truncated backup")
            remaining--; return value
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining == 0L) return -1
            val count = `in`.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("Truncated backup")
            remaining -= count; return count
        }
        override fun close() {}
    }
    companion object {
        private const val MAX_CATALOG = 32 * 1024 * 1024
        private const val MAX_ITEMS = 10000
        private const val MAX_FILE_SIZE = 16L * 1024 * 1024 * 1024 * 1024
        private const val BUFFER = 64 * 1024
        private const val MAX_NAVIGATION = 2 * 1024 * 1024
        private const val MAX_LOCATION = 4096
        private const val ENVELOPE_MAGIC = 0x43564C31
        private const val BACKUP_MAGIC = 0x43564232
        private const val LEGACY_BACKUP_MAGIC = 0x43564231
        private const val DEFAULT_FOLDER = "Private"
        private fun aad(id: String, purpose: String) = Crypto.context("CalVault-v1", id, purpose)
        private fun validId(id: String) { require(id.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))) { "Invalid object ID" } }
        private fun validName(name: String) { require(name.isNotBlank() && name.length <= 240 && name.none { it == '\u0000' || it == '/' || it == '\\' }) { "Use a filename of 1–240 characters without slashes" } }
        private fun validFolder(folder: String) { if (folder.isNotBlank()) FolderPath.validate(folder) else require(folder.isEmpty()) { "Enter a folder name" } }
        private fun validFolderName(folder: String) = FolderPath.validate(folder)
    }
}
