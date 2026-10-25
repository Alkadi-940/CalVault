package dev.calvault.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Independently writes the documented v0.1 catalog/archive layout, not the new codec. */
class LegacyCompatibilityTest {
    @get:Rule val temp = TemporaryFolder()
    private class Device : DeviceKey {
        val key = Crypto.random(32)
        override fun seal(plaintext: ByteArray, context: ByteArray) = Crypto.seal(key, plaintext, context)
        override fun open(ciphertext: ByteArray, context: ByteArray) = Crypto.open(key, ciphertext, context)
    }
    @Test fun originalCatalogMigratesWithoutReencryptingOrLosingTheOriginalSnapshot() {
        val data = legacyFixture()
        data.vault.lock(); data.vault.unlock("123456".toCharArray())
        assertEquals("Family", data.vault.items().single().folder)
        assertEquals(SourceState.UNKNOWN, data.vault.items().single().sourceState)
        val out = ByteArrayOutputStream(); data.vault.export(data.itemId, out)
        assertEquals("legacy private content", out.toString("UTF-8"))
        assertArrayEquals(data.oldCatalog, data.root.resolve("catalog.v1.snapshot").readBytes())
        assertArrayEquals(data.oldObject, data.root.resolve("objects/${data.itemId}.cvf").readBytes())
        data.vault.createFolder("New empty folder"); data.vault.lock(); data.vault.unlock("123456".toCharArray())
        assertTrue("New empty folder" in data.vault.folders())
    }
    @Test fun originalBackupRestoresWithNewDeviceKeyAndPin() {
        val data = legacyFixture()
        val target = Vault(temp.newFolder("target"), Device())
        target.restoreBackup(data.archive.inputStream(), data.recovery, "112233".toCharArray())
        val out = ByteArrayOutputStream(); target.export(data.itemId, out)
        assertEquals("legacy private content", out.toString("UTF-8"))
        target.lock(); target.unlock("112233".toCharArray())
        assertTrue("Family" in target.folders()); assertEquals(1, target.items().size)
    }
    private data class Legacy(val vault: Vault, val root: java.io.File, val recovery: String, val itemId: String, val oldCatalog: ByteArray, val oldObject: ByteArray, val archive: ByteArray)
    private fun legacyFixture(): Legacy {
        val root = temp.newFolder("legacy-${UUID.randomUUID()}")
        val recovery = Crypto.recoverySecret()
        val vault = Vault(root, Device()).apply { create("123456".toCharArray(), recovery) }
        data class Header(val id: String, val recoverySalt: ByteArray, val wrapped: ByteArray)
        val header = decode(root.resolve("envelope.bin").readBytes()) {
            require(readInt() == 0x43564C31)
            val id = readUTF(); blob(16); blob(256)
            Header(id, blob(16), blob(128))
        }
        fun context(purpose: String) = Crypto.context("CalVault-v1", header.id, purpose)
        val recoveryBytes = Crypto.parseRecovery(recovery)
        val recoveryKey = Crypto.derive(recoveryBytes, header.recoverySalt, "recovery/${header.id}")
        val master = Crypto.open(recoveryKey, header.wrapped, context("recovery"))
        val id = UUID.randomUUID().toString()
        val plain = "legacy private content".toByteArray()
        val fileKey = Crypto.random(32)
        val encryptedFile = ByteArrayOutputStream().also { StreamCipher(fileKey).encrypt(it, context(id)).use { stream -> stream.write(plain) } }.toByteArray()
        root.resolve("objects").mkdirs(); root.resolve("objects/$id.cvf").writeBytes(encryptedFile)
        val wrappingKey = Crypto.derive(master, header.id.toByteArray(), "object-keys")
        val wrappedFileKey = Crypto.seal(wrappingKey, fileKey, context("$id/key"))
        val oldPlainCatalog = encode {
            writeInt(1); writeInt(1)
            writeUTF(id); writeUTF("legacy.txt"); writeUTF("text/plain"); writeUTF("Family")
            writeLong(plain.size.toLong()); writeLong(1700000000000)
            blob(wrappedFileKey); blob(Crypto.digest(plain))
        }
        val metadataKey = Crypto.derive(master, header.id.toByteArray(), "metadata")
        val oldCatalog = Crypto.seal(metadataKey, oldPlainCatalog, context("catalog"))
        root.resolve("catalog.enc").writeBytes(oldCatalog)
        val portable = encode { writeInt(0x43564C31); writeUTF(header.id); blob(byteArrayOf()); blob(byteArrayOf()); blob(header.recoverySalt); blob(header.wrapped) }
        val salt = Crypto.random(16)
        val public = encode { writeInt(0x43564231); blob(portable); blob(salt) }
        val archiveKey = Crypto.derive(master, salt, "backup/${header.id}")
        val archive = ByteArrayOutputStream()
        java.io.DataOutputStream(archive).blob(public)
        java.io.DataOutputStream(StreamCipher(archiveKey).encrypt(archive, public)).use {
            it.blob(oldCatalog); it.writeInt(1); it.writeUTF(id); it.writeLong(encryptedFile.size.toLong()); it.write(encryptedFile)
        }
        listOf(recoveryBytes, recoveryKey, master, fileKey, wrappingKey, metadataKey, archiveKey, oldPlainCatalog).forEach { it.fill(0) }
        return Legacy(vault, root, recovery, id, oldCatalog, encryptedFile, archive.toByteArray())
    }
}
