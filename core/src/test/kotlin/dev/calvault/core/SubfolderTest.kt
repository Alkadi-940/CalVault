package dev.calvault.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class SubfolderTest {
    @get:Rule val temp = TemporaryFolder()
    private class Device : DeviceKey {
        private val key = Crypto.random(32)
        override fun seal(plaintext: ByteArray, context: ByteArray) = Crypto.seal(key, plaintext, context)
        override fun open(ciphertext: ByteArray, context: ByteArray) = Crypto.open(key, ciphertext, context)
    }
    private fun create(name: String = "vault") = Vault(temp.newFolder(name), Device()).apply { create("123456".toCharArray(), recovery) }
    private val recovery = Crypto.recoverySecret()

    @Test fun sameNamesInDifferentParentsAndDepthLimits() {
        val vault = create()
        vault.createFolder("A"); vault.createFolder("B")
        vault.createFolder("Photos", "A"); vault.createFolder("Photos", "B")
        assertTrue(FolderPath.child("A", "Photos") in vault.folders())
        assertThrows(IllegalArgumentException::class.java) { vault.createFolder("photos", "A") }
        assertThrows(IllegalArgumentException::class.java) { vault.createFolder("Missing", "No parent") }
        var path = "A"
        repeat(31) { path = FolderPath.child(path, "Level $it"); vault.createFolder(FolderPath.name(path), FolderPath.parent(path)) }
        assertThrows(IllegalArgumentException::class.java) { vault.createFolder("Too deep", path) }
    }
    @Test fun parentRenameMovesActiveDescendantsAndEmptyFoldersButNotOldTrash() {
        val vault = create()
        vault.createFolder("A"); vault.createFolder("Child", "A")
        val child = FolderPath.child("A", "Child")
        vault.createFolder("Empty", child)
        val old = vault.importFile("old.txt", "text/plain", child, "old".byteInputStream())
        vault.delete(setOf(old.id))
        val live = vault.importFile("live.txt", "text/plain", child, "live".byteInputStream())
        vault.renameFolder("A", "Renamed")
        assertEquals(FolderPath.child("Renamed", "Child"), vault.items().single().folder)
        assertEquals(child, vault.trash().single().folder)
        assertTrue(FolderPath.child(FolderPath.child("Renamed", "Child"), "Empty") in vault.folders())
        vault.lock(); vault.unlock("123456".toCharArray())
        val out = ByteArrayOutputStream(); vault.export(live.id, out); assertEquals("live", out.toString("UTF-8"))
    }
    @Test fun binAndBackupRestoreEntireSubtreeIncludingEmptyDescendants() {
        val vault = create()
        vault.createFolder("A"); vault.createFolder("Child", "A"); vault.createFolder("Empty", FolderPath.child("A", "Child"))
        val paths = vault.folders().filter { FolderPath.within(it, "A") }
        val item = vault.importFile("note.txt", "text/plain", FolderPath.child("A", "Child"), "safe".byteInputStream())
        vault.createFolder("AB")
        vault.deleteFolder("A")
        assertTrue("AB" in vault.folders()); assertFalse(vault.folders().any { FolderPath.within(it, "A") })
        assertEquals(paths, vault.trashFolders().single().paths)
        val bytes = ByteArrayOutputStream(); vault.writeBackup(bytes)
        val restored = Vault(temp.newFolder("restored"), Device())
        restored.restoreBackup(bytes.toByteArray().inputStream(), recovery, "654321".toCharArray())
        restored.restoreFolder(restored.trashFolders().single().id)
        assertTrue(restored.folders().containsAll(paths)); assertEquals(item.id, restored.items().single().id)
        restored.lock(); restored.unlock("654321".toCharArray()); assertTrue(restored.folders().containsAll(paths))
    }
    @Test fun restoringAChildFileRecreatesAncestorsAndPermanentDeletionStaysInSubtree() {
        val vault = create()
        vault.createFolder("A"); vault.createFolder("Child", "A"); vault.createFolder("Other")
        val child = FolderPath.child("A", "Child")
        val restored = vault.importFile("restore.txt", "text/plain", child, "restore".byteInputStream())
        val removed = vault.importFile("remove.txt", "text/plain", child, "remove".byteInputStream())
        val safe = vault.importFile("safe.txt", "text/plain", "Other", "safe".byteInputStream())
        vault.deleteFolder("A"); vault.restoreTrash(setOf(restored.id))
        assertTrue(vault.folders().containsAll(listOf("A", child)))
        vault.permanentlyDeleteFolder(vault.trashFolders().single().id)
        vault.lock(); vault.unlock("123456".toCharArray())
        assertEquals(setOf(restored.id, safe.id), vault.items().map { it.id }.toSet())
        assertFalse(vault.trash().any { it.id == removed.id })
    }
    @Test fun recoveryRecordsRecreateHierarchyAndSupportLongNestedMetadata() {
        val vault = create()
        var path: String? = null
        repeat(32) { vault.createFolder("x".repeat(120), path); path = FolderPath.child(path, "x".repeat(120)) }
        val item = vault.importFile("nested.txt", "text/plain", path!!, "data".byteInputStream(), source = SourceLocation("u".repeat(4096), "p".repeat(4096), "c".repeat(4096), "t".repeat(4096), "h".repeat(4096)))
        vault.lock()
        temp.root.resolve("vault/catalog.enc").delete(); temp.root.resolve("vault/catalog.previous").delete()
        vault.unlock("123456".toCharArray())
        assertEquals(item.folder, vault.items().single().folder)
        assertEquals(32, vault.folders().size)
    }
}
