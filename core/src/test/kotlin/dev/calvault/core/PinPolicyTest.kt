package dev.calvault.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class PinPolicyTest {
    @get:Rule val temp = TemporaryFolder()
    private class Device : DeviceKey {
        private val key = Crypto.random(32)
        override fun seal(plaintext: ByteArray, context: ByteArray) = Crypto.seal(key, plaintext, context)
        override fun open(ciphertext: ByteArray, context: ByteArray) = Crypto.open(key, ciphertext, context)
    }
    @Test fun boundaryPinsWorkForCreateChangeRecoveryAndRestore() {
        val recovery = Crypto.recoverySecret()
        val vault = Vault(temp.newFolder("vault"), Device())
        vault.create("123".toCharArray(), recovery)
        val item = vault.importFile("note.txt", "text/plain", "Private", "unchanged".byteInputStream())
        vault.lock(); vault.unlock("123".toCharArray())
        vault.changePin("987654321".toCharArray()); vault.lock(); vault.unlock("987654321".toCharArray())
        val backup = ByteArrayOutputStream(); vault.writeBackup(backup)
        val restored = Vault(temp.newFolder("restored"), Device())
        restored.restoreBackup(backup.toByteArray().inputStream(), recovery, "456".toCharArray())
        restored.lock(); restored.unlock("456".toCharArray())
        restored.recover(recovery, "123456789".toCharArray()); restored.lock(); restored.unlock("123456789".toCharArray())
        val output = ByteArrayOutputStream(); restored.export(item.id, output)
        assertEquals("unchanged", output.toString("UTF-8"))
    }
    @Test fun invalidNewPinsLeaveExistingAccessUnchanged() {
        val vault = Vault(temp.newFolder("vault"), Device())
        vault.create("246810".toCharArray(), Crypto.recoverySecret())
        listOf("", "12", "1234567890", "123456789012", "12a", "١٢٣").forEach { pin ->
            assertThrows(IllegalArgumentException::class.java) { vault.changePin(pin.toCharArray()) }
            assertTrue(vault.unlocked)
        }
        vault.lock(); vault.unlock("246810".toCharArray())
        val invalid = Vault(temp.newFolder("invalid"), Device())
        assertThrows(IllegalArgumentException::class.java) { invalid.create("12".toCharArray(), Crypto.recoverySecret()) }
        assertFalse(invalid.exists)
    }
}
