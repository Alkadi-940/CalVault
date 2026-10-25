package dev.calvault.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest

class VaultTest {
    @get:Rule val temp = TemporaryFolder()
    private class FakeDevice : DeviceKey {
        private val key = Crypto.random(32)
        override fun seal(plaintext: ByteArray, context: ByteArray) = Crypto.seal(key, plaintext, context)
        override fun open(ciphertext: ByteArray, context: ByteArray) = Crypto.open(key, ciphertext, context)
    }
    private fun initialized(name: String = "vault"): Pair<Vault, String> {
        val recovery = Crypto.recoverySecret()
        val vault = Vault(temp.newFolder(name), FakeDevice())
        vault.create("001234".toCharArray(), recovery)
        return vault to recovery
    }
    @Test fun importExportSeekRenameDeleteAndRelock() {
        val (vault, _) = initialized()
        val bytes = ByteArray(3 * 1024 * 1024 + 133) { (it * 31).toByte() }
        val item = vault.importFile("München-private.jpg", "image/jpeg", "Trips", bytes.inputStream())
        vault.openMedia(item.id).use { channel ->
            channel.position(1024 * 1024 + 111L)
            val sample = ByteBuffer.allocate(231)
            while (sample.hasRemaining()) assertTrue(channel.read(sample) > 0)
            assertArrayEquals(bytes.copyOfRange(1024 * 1024 + 111, 1024 * 1024 + 342), sample.array())
        }
        val exported = ByteArrayOutputStream()
        vault.export(item.id, exported)
        assertArrayEquals(bytes, exported.toByteArray())
        vault.rename(item.id, "renamed.jpg", "Other")
        vault.lock()
        assertThrows(Exception::class.java) { vault.items() }
        assertThrows(Exception::class.java) { vault.unlock("123456".toCharArray()) }
        vault.unlock("001234".toCharArray())
        assertEquals("Other", vault.items().single().folder)
        assertEquals("renamed.jpg", vault.items().single().name)
        vault.delete(setOf(item.id))
        assertTrue(vault.items().isEmpty())
    }
    @Test fun backupRestoresWithoutOriginalDeviceKeyAndCanRecoverPin() {
        val (vault, recovery) = initialized()
        val bytes = ByteArray(2 * 1024 * 1024 + 1) { (it % 251).toByte() }
        vault.importFile("secret.mp4", "video/mp4", "", bytes.inputStream())
        vault.importFile("empty.txt", "text/plain", "", byteArrayOf().inputStream())
        val backup = ByteArrayOutputStream()
        vault.writeBackup(backup)
        vault.verifyBackup(backup.toByteArray().inputStream())
        val restored = Vault(temp.newFolder("second-device"), FakeDevice())
        restored.restoreBackup(backup.toByteArray().inputStream(), recovery, "998877".toCharArray())
        assertEquals(2, restored.items().size)
        val out = ByteArrayOutputStream()
        restored.export(restored.items().first().id, out)
        assertArrayEquals(bytes, out.toByteArray())
        restored.lock()
        restored.recover(recovery, "112233".toCharArray())
        restored.lock(); restored.unlock("112233".toCharArray())
        assertEquals(2, restored.items().size)
        assertThrows(Exception::class.java) { restored.restoreBackup(backup.toByteArray().inputStream(), recovery, "000000".toCharArray()) }
    }
    @Test fun failedImportKeepsCatalogAndCleansStaging() {
        val (vault, _) = initialized()
        val broken = object : InputStream() { override fun read(): Int = throw IOException("Source revoked") }
        assertThrows(Exception::class.java) { vault.importFile("original.txt", "text/plain", "", broken) }
        assertTrue(vault.items().isEmpty())
        val root = temp.root.resolve("vault")
        assertTrue(root.resolve("objects").listFiles().orEmpty().isEmpty())
        assertFalse(root.walk().filter { it.isFile }.any { String(it.readBytes()).contains("original.txt") })
    }
    @Test fun corruptOrWrongKeyBackupNeverCommits() {
        val (vault, _) = initialized()
        vault.importFile("private.txt", "text/plain", "", "secret".byteInputStream())
        val out = ByteArrayOutputStream(); vault.writeBackup(out)
        val bytes = out.toByteArray()
        val target = Vault(temp.newFolder("failed"), FakeDevice())
        assertThrows(Exception::class.java) { target.restoreBackup(bytes.inputStream(), Crypto.recoverySecret(), "123456".toCharArray()) }
        assertFalse(target.exists)
        val secret = Crypto.recoverySecret()
        val second = Vault(temp.newFolder("source2"), FakeDevice())
        second.create("123456".toCharArray(), secret)
        val good = ByteArrayOutputStream(); second.writeBackup(good)
        val damaged = good.toByteArray().dropLast(1).toByteArray()
        assertThrows(Exception::class.java) { target.restoreBackup(damaged.inputStream(), secret, "123456".toCharArray()) }
        assertFalse(target.exists)
    }
    @Test fun lockedMediaHandleCannotContinueReading() {
        val (vault, _) = initialized()
        val item = vault.importFile("test.bin", "application/octet-stream", "", ByteArray(1000).inputStream())
        val channel = vault.openMedia(item.id)
        vault.lock()
        assertThrows(Exception::class.java) { channel.read(ByteBuffer.allocate(1)) }
        channel.close()
    }
    @Test fun backgroundLockDuringPinDerivationCannotPublishSession() {
        val root = temp.newFolder("race")
        val device = FakeDevice()
        val blocking = object : DeviceKey {
            var callback: (() -> Unit)? = null
            override fun seal(plaintext: ByteArray, context: ByteArray) = device.seal(plaintext, context)
            override fun open(ciphertext: ByteArray, context: ByteArray): ByteArray {
                return device.open(ciphertext, context).also { callback?.invoke() }
            }
        }
        val vault = Vault(root, blocking)
        vault.create("123456".toCharArray(), Crypto.recoverySecret())
        blocking.callback = { vault.lock() }
        assertThrows(Exception::class.java) { vault.unlock("123456".toCharArray()) }
        assertFalse(vault.unlocked)
    }
    @Test fun unidentifiedPartialIsPreservedByNonDestructiveRepair() {
        val (vault, _) = initialized()
        val orphan = temp.root.resolve("vault/objects/abandoned.part")
        orphan.parentFile.mkdirs(); orphan.writeBytes(ByteArray(20))
        vault.lock(); vault.unlock("001234".toCharArray())
        assertTrue(orphan.exists())
    }
    @Test fun tamperedBlobFailsAuthenticationAndLostDeviceKeyCanRecover() {
        val (vault, recovery) = initialized()
        val item = vault.importFile("private-marker.txt", "text/plain", "private-folder", "known-sensitive-plaintext".byteInputStream())
        val root = temp.root.resolve("vault")
        root.walkTopDown().filter { it.isFile }.forEach {
            val raw = String(it.readBytes(), Charsets.ISO_8859_1)
            assertFalse(raw.contains("private-marker")); assertFalse(raw.contains("private-folder")); assertFalse(raw.contains("known-sensitive-plaintext"))
        }
        vault.lock()
        val replacement = Vault(root, FakeDevice())
        assertThrows(Exception::class.java) { replacement.unlock("001234".toCharArray()) }
        replacement.recover(recovery, "445566".toCharArray())
        val file = root.resolve("objects/${item.id}.cvf")
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        assertThrows(Exception::class.java) { replacement.export(item.id, ByteArrayOutputStream()) }
    }
    @Test fun originalRemovalRequiresUnchangedSourceAndSurvivingVaultCopy() {
        val (vault, _) = initialized()
        val item = vault.importFile("original.txt", "text/plain", "", "original".byteInputStream())
        vault.verifyOriginal(item.id, "original".byteInputStream())
        assertThrows(Exception::class.java) { vault.verifyOriginal(item.id, "modified".byteInputStream()) }
        vault.delete(setOf(item.id))
        assertThrows(Exception::class.java) { vault.verifyOriginal(item.id, "original".byteInputStream()) }
    }
    @Test fun cancelledSetupDoesNotInitializeVaultAfterPinDerivation() {
        val device = FakeDevice()
        lateinit var vault: Vault
        val blocking = object : DeviceKey {
            override fun seal(plaintext: ByteArray, context: ByteArray): ByteArray {
                return device.seal(plaintext, context).also { vault.lock() }
            }
            override fun open(ciphertext: ByteArray, context: ByteArray) = device.open(ciphertext, context)
        }
        vault = Vault(temp.newFolder("cancelled"), blocking)
        assertThrows(Exception::class.java) { vault.create("123456".toCharArray(), Crypto.recoverySecret()) }
        assertFalse(vault.exists)
        assertFalse(vault.unlocked)
    }
}
