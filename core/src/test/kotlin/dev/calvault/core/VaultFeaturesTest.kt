package dev.calvault.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class VaultFeaturesTest {
    @get:Rule val temp = TemporaryFolder()
    private class Device : DeviceKey {
        val key = Crypto.random(32)
        override fun seal(plaintext: ByteArray, context: ByteArray) = Crypto.seal(key, plaintext, context)
        override fun open(ciphertext: ByteArray, context: ByteArray) = Crypto.open(key, ciphertext, context)
    }
    private data class Fixture(val vault: Vault, val root: File, val recovery: String)
    private fun create(name: String = "vault"): Fixture {
        val root = temp.newFolder(name); val secret = Crypto.recoverySecret()
        return Fixture(Vault(root, Device()).apply { create("123456".toCharArray(), secret) }, root, secret)
    }
    @Test fun emptyFoldersAndFolderRenameSurviveLockAndPinChange() {
        val (vault) = create()
        vault.createFolder("Family")
        val item = vault.importFile("notes.txt", "text/plain", "Family", "safe".byteInputStream())
        vault.renameFolder("Family", "Memories")
        vault.changePin("654321".toCharArray()); vault.lock(); vault.unlock("654321".toCharArray())
        assertTrue("Memories" in vault.folders()); assertEquals("Memories", vault.items().single { it.id == item.id }.folder)
        vault.createFolder("Empty"); vault.lock(); vault.unlock("654321".toCharArray()); assertTrue("Empty" in vault.folders())
    }
    @Test fun recycleBinRestoresSelectedAndDeletesAllRecoveryCopiesOnlyOnExplicitDelete() {
        val (vault, root) = create()
        val item = vault.importFile("private.txt", "text/plain", "Private", "private".byteInputStream())
        vault.delete(setOf(item.id))
        assertTrue(vault.items().isEmpty()); assertEquals(item.id, vault.trash().single().id)
        assertTrue(root.resolve("objects/${item.id}.cvf").exists())
        vault.restoreTrash(setOf(item.id)); assertEquals(item.id, vault.items().single().id)
        vault.delete(setOf(item.id))
        root.resolve("objects/${item.id}.part").writeBytes(root.resolve("objects/${item.id}.cvf").readBytes())
        vault.permanentlyDelete(setOf(item.id)); vault.lock(); vault.unlock("123456".toCharArray())
        assertTrue(vault.items().isEmpty()); assertTrue(vault.trash().isEmpty())
        assertEquals(listOf("${item.id}.gone"), root.resolve("objects").listFiles().orEmpty().filter { it.name.startsWith(item.id) }.map { it.name })
    }
    @Test fun folderRestoreDoesNotRestoreItemsBinnedBeforeTheFolder() {
        val (vault) = create()
        vault.createFolder("Albums")
        val previous = vault.importFile("already-binned.txt", "text/plain", "Albums", "old".byteInputStream())
        val current = vault.importFile("in-folder.txt", "text/plain", "Albums", "new".byteInputStream())
        vault.delete(setOf(previous.id)); vault.deleteFolder("Albums")
        val folder = vault.trashFolders().single()
        assertFalse("Albums" in vault.folders()); vault.restoreFolder(folder.id)
        assertTrue("Albums" in vault.folders()); assertEquals(current.id, vault.items().single().id)
        assertEquals(previous.id, vault.trash().single().id)
    }
    @Test fun emptyFolderCanBeRestoredAndPermanentlyRemoved() {
        val (vault) = create()
        vault.createFolder("Empty"); vault.deleteFolder("Empty")
        vault.lock(); vault.unlock("123456".toCharArray())
        vault.restoreFolder(vault.trashFolders().single().id); assertTrue("Empty" in vault.folders())
        vault.deleteFolder("Empty"); vault.permanentlyDeleteFolder(vault.trashFolders().single().id)
        assertTrue(vault.trashFolders().isEmpty())
    }
    @Test fun bothModesEncryptContentsAndSourceMetadata() {
        val (vault, root) = create()
        val marker = "known private content 776655"
        for (mode in StorageMode.entries) {
            val item = vault.importFile("secret-$mode.txt", "text/plain", "Private", marker.byteInputStream(), mode,
                SourceLocation("content://provider/private-source-445566", "Pictures/private-778899/", "images:external_primary"))
            assertEquals(SourceState.PENDING_REMOVAL, item.sourceState)
            val out = ByteArrayOutputStream(); vault.export(item.id, out); assertEquals(marker, out.toString("UTF-8"))
            vault.markOriginalRemoved(item.id)
        }
        root.walk().filter { it.isFile }.forEach {
            val data = String(it.readBytes(), Charsets.ISO_8859_1)
            assertFalse(data.contains(marker)); assertFalse(data.contains("private-source-445566")); assertFalse(data.contains("private-778899"))
        }
        vault.lock(); vault.unlock("123456".toCharArray()); assertTrue(vault.items().all { it.sourceState == SourceState.REMOVED })
    }
    @Test fun oversizedSourceCannotCreateAnUnreadableCatalog() {
        val (vault, root) = create()
        assertThrows(IllegalArgumentException::class.java) {
            vault.importFile("source.txt", "text/plain", "Private", "data".byteInputStream(), source = SourceLocation(uri = "x".repeat(4097)))
        }
        assertTrue(vault.items().isEmpty())
        assertTrue(root.resolve("objects").listFiles().orEmpty().isEmpty())
        vault.lock(); vault.unlock("123456".toCharArray())
        assertTrue(vault.items().isEmpty())
    }
    @Test fun unhideRequiresDestinationVerificationAndThenRemovesVaultCopy() {
        val (vault, root) = create()
        val item = vault.importFile("document.txt", "text/plain", "Private", "data".byteInputStream())
        assertThrows(Exception::class.java) { vault.verifyDestination(item.id, "wrong".byteInputStream()) }
        assertTrue(vault.items().isNotEmpty())
        val destination = ByteArrayOutputStream(); vault.export(item.id, destination)
        vault.verifyDestination(item.id, destination.toByteArray().inputStream()); vault.finishUnhide(setOf(item.id))
        assertTrue(vault.items().isEmpty()); assertFalse(root.resolve("objects/${item.id}.cvf").exists())
    }
    @Test fun deletedCatalogIsRetrievedFromIndependentlyAuthenticatedRecords() {
        val (vault, root) = create()
        val item = vault.importFile("retrieved.txt", "text/plain", "Memories", "original".byteInputStream())
        vault.lock(); root.resolve("catalog.enc").delete(); root.resolve("catalog.previous").delete()
        vault.unlock("123456".toCharArray())
        assertEquals(item.id, vault.items().single().id); assertEquals(1, vault.lastRepair.recovered)
        val out = ByteArrayOutputStream(); vault.export(item.id, out); assertEquals("original", out.toString("UTF-8"))
    }
    @Test fun missingObjectsDoNotLockOutHealthyFiles() {
        val (vault, root) = create()
        val missing = vault.importFile("missing.txt", "text/plain", "Private", "missing".byteInputStream())
        val safe = vault.importFile("safe.txt", "text/plain", "Private", "safe".byteInputStream())
        root.resolve("objects/${missing.id}.cvf").delete(); vault.lock(); vault.unlock("123456".toCharArray())
        assertTrue(vault.items().first { it.id == missing.id }.missing)
        val out = ByteArrayOutputStream(); vault.export(safe.id, out); assertEquals("safe", out.toString("UTF-8"))
    }
    @Test fun explicitDeletionIsNotResurrectedByFallbackRepair() {
        val (vault, root) = create()
        val item = vault.importFile("gone.txt", "text/plain", "Private", "gone".byteInputStream())
        vault.delete(setOf(item.id)); vault.permanentlyDelete(setOf(item.id)); vault.lock()
        root.resolve("catalog.enc").writeBytes(ByteArray(100))
        vault.unlock("123456".toCharArray())
        assertTrue(vault.items().isEmpty()); assertTrue(vault.trash().isEmpty())
        val archive = ByteArrayOutputStream(); vault.writeBackup(archive); vault.verifyBackup(archive.toByteArray().inputStream())
    }
    @Test fun backupPreservesFoldersBinModesAndEncryptedNavigation() {
        val (vault, root, key) = create()
        vault.createFolder("Empty")
        val hidden = vault.importFile("hidden.txt", "text/plain", "Private", "hidden".byteInputStream(), StorageMode.HIDDEN)
        vault.delete(setOf(hidden.id)); vault.deleteFolder("Empty")
        val bookmark = "{\"screen\":\"VIEWER\",\"private-query\":\"private-name-224466\",\"position\":12345}"
        vault.saveNavigation(bookmark)
        assertFalse(String(root.resolve("navigation.enc").readBytes()).contains("private-name-224466"))
        val out = ByteArrayOutputStream(); vault.writeBackup(out)
        val target = Vault(temp.newFolder("restored"), Device())
        target.restoreBackup(out.toByteArray().inputStream(), key, "456789".toCharArray())
        assertEquals(bookmark, target.loadNavigation()); assertEquals(StorageMode.HIDDEN, target.trash().single().mode)
        assertEquals("Empty", target.trashFolders().single().name)
    }
    @Test fun retrievalFromBackupRepairsDamagedFileAndKeepsHealthyOne() {
        val (vault, root, recovery) = create()
        val item = vault.importFile("repair.txt", "text/plain", "Private", "healthy bytes".byteInputStream())
        val other = vault.importFile("other.txt", "text/plain", "Private", "other".byteInputStream())
        val backup = ByteArrayOutputStream(); vault.writeBackup(backup)
        root.resolve("objects/${item.id}.cvf").writeBytes(ByteArray(100))
        assertEquals(1, vault.retrieveFromBackup(backup.toByteArray().inputStream(), recovery))
        val out = ByteArrayOutputStream(); vault.export(item.id, out); assertEquals("healthy bytes", out.toString("UTF-8"))
        assertEquals(other.id, vault.items().first { it.name == "other.txt" }.id)
    }
    @Test fun shuffledCycleHasNoRepeatedItemsAndStartsWithCurrent() {
        val ids = listOf("a", "b", "c", "d", "e")
        repeat(100) { seed ->
            val order = PlaylistOrder.cycle(ids, "c", true, seed.toLong())
            assertEquals("c", order.first()); assertEquals(ids.toSet(), order.toSet()); assertEquals(ids.size, order.size)
        }
        assertEquals(ids, PlaylistOrder.cycle(ids, "c", false, 1))
    }
}
