package dev.calvault

import androidx.test.platform.app.InstrumentationRegistry
import dev.calvault.core.Vault
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Run against the synthetic three-file v0.1.0 device fixture before fresh-install tests. */
@RunWith(AndroidJUnit4::class)
class UpgradeTest {
    @Test fun preservesExistingVaultAndCiphertext() {
        assumeTrue("Requires the retained v0.1.0 synthetic fixture", InstrumentationRegistry.getArguments().getString("legacyFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "vault")
        val vault = Vault(root, AndroidDeviceKey(), ::syncVaultDirectory)
        val oldBlobs = File(root, "objects").listFiles().orEmpty().filter { it.extension == "cvf" }.associate { it.name to it.readBytes() }
        assertEquals(3, oldBlobs.size)
        vault.unlock("246810".toCharArray())
        assertEquals(3, vault.items().size)
        assertTrue(File(root, "catalog.v1.snapshot").isFile)
        oldBlobs.forEach { (name, bytes) -> assertArrayEquals(bytes, File(root, "objects/$name").readBytes()) }
        val image = vault.items().first { it.mime == "image/png" }
        val output = ByteArrayOutputStream()
        vault.export(image.id, output)
        assertTrue(output.size() > 100)
        assertTrue("Personal" in vault.folders())
        vault.lock()
    }
}
