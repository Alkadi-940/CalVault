package dev.calvault.core

import java.io.*
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class VaultItem(
    val id: String, val name: String, val mime: String, val folder: String,
    val size: Long, val created: Long,
)
private data class StoredItem(val item: VaultItem, val wrappedKey: ByteArray, val digest: ByteArray)
private data class Envelope(
    val id: String, val pinSalt: ByteArray, val deviceWrapped: ByteArray,
    val recoverySalt: ByteArray, val recoveryWrapped: ByteArray,
)
private class Session(val envelope: Envelope, val master: ByteArray, var records: List<StoredItem>) {
    val active = AtomicBoolean(true)
    fun check() = check(active.get()) { "Vault locked. Unlock to continue." }
}

/** Encrypted catalog is the commit point. Blobs are immutable and promoted before catalog commits. */
class Vault(private val root: File, private val device: DeviceKey) {
    private val header get() = File(root, "envelope.bin")
    private val catalog get() = File(root, "catalog.enc")
    private val objects get() = File(root, "objects").also { it.mkdirs() }
    @Volatile private var session: Session? = null
    @Volatile private var restoring: Session? = null
    private val generation = AtomicLong()
    private val sessionGuard = Any()
    val exists: Boolean get() = header.isFile
    val unlocked: Boolean get() = session?.active?.get() == true
    private fun current(): Session = checkNotNull(session) { "Unlock your vault first" }.also { it.check() }
    @Synchronized fun items(): List<VaultItem> = current().records.map { it.item }

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
                saveCatalog(next, emptyList())
                atomicWrite(header, encodeEnvelope(envelope))
                publish(next, ticket)
            } catch (e: Exception) { next.master.fill(0); throw e }
        } finally { master.fill(0); pin.fill('\u0000') }
    }

    @Synchronized fun unlock(pin: CharArray) {
        lock()
        val ticket = generation.get()
        val envelope = readEnvelope()
        val inner = device.open(envelope.deviceWrapped, aad(envelope.id, "device"))
        val key = try { Crypto.pinKey(pin, envelope.pinSalt) } finally { pin.fill('\u0000') }
        val master = try { Crypto.open(key, inner, aad(envelope.id, "pin")) }
        finally { key.fill(0); inner.fill(0) }
        try {
            val records = readCatalog(envelope, master, catalog.readBounded(MAX_CATALOG))
            val next = Session(envelope, master, records)
            reconcile(next)
            publish(next, ticket)
        } catch (e: Exception) { master.fill(0); throw e }
    }

    @Synchronized fun recover(recovery: String, newPin: CharArray) {
        lock()
        val ticket = generation.get()
        val previous = readEnvelope()
        val master = recoverMaster(previous, recovery)
        try {
            val records = readCatalog(previous, master, catalog.readBounded(MAX_CATALOG))
            val replacement = withPin(previous, master, newPin)
            atomicWrite(header, encodeEnvelope(replacement))
            val next = Session(replacement, master.copyOf(), records)
            reconcile(next)
            publish(next, ticket)
        } finally { master.fill(0); newPin.fill('\u0000') }
    }

    @Synchronized fun changePin(newPin: CharArray) {
        val ticket = generation.get()
        val old = current()
        val next = try { withPin(old.envelope, old.master, newPin) } finally { newPin.fill('\u0000') }
        old.check()
        atomicWrite(header, encodeEnvelope(next))
        publish(Session(next, old.master.copyOf(), old.records), ticket)
        old.active.set(false)
        old.master.fill(0)
    }

    @Synchronized fun importFile(
        name: String, mime: String, folder: String, input: InputStream,
        progress: (Long) -> Unit = {},
    ): VaultItem {
        val s = current()
        require(s.records.size < MAX_ITEMS) { "Vault item limit reached" }
        validName(name); validFolder(folder)
        require(mime.length <= 200)
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
            val item = VaultItem(id, name, mime, folder, size, System.currentTimeMillis())
            val wrappingKey = subkey(s, "object-keys")
            val wrapped = try { Crypto.seal(wrappingKey, key, aad(s.envelope.id, "$id/key")) } finally { wrappingKey.fill(0) }
            val records = s.records + StoredItem(item, wrapped, hash)
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            saveCatalog(s, records)
            s.records = records
            return item
        } catch (e: Exception) {
            staging.delete()
            // If the catalog commit failed, leave an orphan ciphertext; unlock reconciliation removes it.
            throw e
        } finally { key.fill(0) }
    }

    @Synchronized fun rename(id: String, name: String, folder: String) {
        validName(name); validFolder(folder)
        val s = current()
        require(s.records.any { it.item.id == id }) { "File is no longer available" }
        val records = s.records.map { if (it.item.id == id) it.copy(item = it.item.copy(name = name, folder = folder)) else it }
        saveCatalog(s, records)
        s.records = records
    }

    @Synchronized fun delete(ids: Set<String>) {
        val s = current()
        val records = s.records.filterNot { it.item.id in ids }
        saveCatalog(s, records)
        s.records = records
        ids.forEach { objectFile(it).delete() }
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
        s.check()
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
            val encryptedCatalog = consumeBackup(backup, input, master, tempSession, staging)
            val envelope = withPin(backup.envelope, master, pin)
            tempSession.check()
            root.mkdirs()
            val children = staging.listFiles().orEmpty()
            children.forEach { Files.move(it.toPath(), File(objects, it.name).toPath(), StandardCopyOption.REPLACE_EXISTING) }
            atomicWrite(catalog, encryptedCatalog)
            // Header is the commit point: a failed restore cannot appear as an initialized vault.
            atomicWrite(header, encodeEnvelope(envelope))
            publish(Session(envelope, master.copyOf(), readCatalog(envelope, master, encryptedCatalog)), ticket)
        } finally {
            tempSession.active.set(false)
            restoring = null
            master.fill(0); pin.fill('\u0000')
            staging.listFiles().orEmpty().forEach { it.delete() }
            staging.delete()
        }
    }

    private data class BackupHeader(val public: ByteArray, val envelope: Envelope, val salt: ByteArray)
    private fun readBackupHeader(input: InputStream): BackupHeader {
        val public = DataInputStream(input).blob(2048)
        return decode(public) {
            require(readInt() == BACKUP_MAGIC) { "Unsupported backup format" }
            val envelope = decodeEnvelope(blob(1024))
            require(envelope.pinSalt.isEmpty() && envelope.deviceWrapped.isEmpty()) { "Invalid portable envelope" }
            val salt = blob(16).also { require(it.size == 16) }
            BackupHeader(public, envelope, salt)
        }
    }

    private fun consumeBackup(backup: BackupHeader, input: InputStream, master: ByteArray, s: Session, destination: File?): ByteArray {
        val key = Crypto.derive(master, backup.salt, "backup/${backup.envelope.id}")
        try {
            DataInputStream(StreamCipher(key).decrypt(input, backup.public)).use { payload ->
                val encryptedCatalog = payload.blob(MAX_CATALOG)
                val records = readCatalog(backup.envelope, master, encryptedCatalog)
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
                return encryptedCatalog
            }
        } finally { key.fill(0) }
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
    private fun saveCatalog(s: Session, records: List<StoredItem>) {
        s.check()
        val plain = encode {
            writeInt(1); writeInt(records.size)
            records.forEach {
                writeUTF(it.item.id); writeUTF(it.item.name); writeUTF(it.item.mime); writeUTF(it.item.folder)
                writeLong(it.item.size); writeLong(it.item.created); blob(it.wrappedKey); blob(it.digest)
            }
        }
        require(plain.size < MAX_CATALOG - 28) { "Vault catalog is full" }
        val key = subkey(s, "metadata")
        val encrypted = try { Crypto.seal(key, plain, aad(s.envelope.id, "catalog")) }
        finally { key.fill(0); plain.fill(0) }
        s.check()
        atomicWrite(catalog, encrypted)
    }
    private fun readCatalog(envelope: Envelope, master: ByteArray, encrypted: ByteArray): List<StoredItem> {
        val key = Crypto.derive(master, envelope.id.toByteArray(), "metadata")
        val plain = try { Crypto.open(key, encrypted, aad(envelope.id, "catalog")) } finally { key.fill(0) }
        return try {
            decode(plain) {
                require(readInt() == 1) { "Unsupported catalog version" }
                val count = readInt()
                require(count in 0..MAX_ITEMS) { "Invalid catalog count" }
                val ids = mutableSetOf<String>()
                List(count) {
                    val id = readUTF().also { validId(it); require(ids.add(it)) }
                    val name = readUTF().also(::validName)
                    val mime = readUTF().also { require(it.length <= 200) }
                    val folder = readUTF().also(::validFolder)
                    val size = readLong().also { require(it in 0..MAX_FILE_SIZE) }
                    val created = readLong()
                    val wrapped = blob(128).also { require(it.size == 60) }
                    val hash = blob(32).also { require(it.size == 32) }
                    StoredItem(VaultItem(id, name, mime, folder, size, created), wrapped, hash)
                }
            }
        } finally { plain.fill(0) }
    }
    private fun reconcile(s: Session) {
        val expected = s.records.map { "${it.item.id}.cvf" }.toSet()
        require(expected.all { File(objects, it).isFile }) { "Some vault files are missing. Restore a verified backup." }
        objects.listFiles().orEmpty().filter { it.name !in expected }.forEach { it.delete() }
        File(root, "catalog.enc.tmp").delete()
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
        private const val ENVELOPE_MAGIC = 0x43564C31
        private const val BACKUP_MAGIC = 0x43564231
        private fun aad(id: String, purpose: String) = Crypto.context("CalVault-v1", id, purpose)
        private fun validId(id: String) { require(id.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))) { "Invalid object ID" } }
        private fun validName(name: String) { require(name.isNotBlank() && name.length <= 240 && name.none { it == '\u0000' || it == '/' || it == '\\' }) { "Use a filename of 1–240 characters without slashes" } }
        private fun validFolder(folder: String) { require(folder.length <= 120 && folder.none { it.isISOControl() }) { "Folder name is too long" } }
    }
}
