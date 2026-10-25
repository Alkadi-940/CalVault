package dev.calvault

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.calvault.core.Crypto
import dev.calvault.core.FolderPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SubfolderFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
    private fun done() { rule.waitUntil(60000) { !model.ui.value.busy }; rule.waitForIdle() }
    private fun create(name: String) {
        val parent = model.ui.value.folder
        if (parent == null) rule.onNodeWithText("New folder").performScrollTo().performClick()
        else rule.onNodeWithContentDescription("New folder").performClick()
        rule.onNodeWithText("Folder name").performTextInput(name)
        rule.onNodeWithText("Create", useUnmergedTree = true).performClick()
        done()
        assertTrue(model.ui.value.message.orEmpty(), FolderPath.child(parent, name) in model.ui.value.folders)
    }
    @Test fun createNavigateRenameRecycleRestoreAndResumeSubfolders() {
        rule.runOnIdle { if (model.vault.exists) model.unlock("246810") else model.create("246810", Crypto.recoverySecret()) }; done()
        assertTrue(model.vault.unlocked)
        rule.runOnIdle { model.openFolder(null) }
        val root = "Nested QA ${SystemClock.uptimeMillis()}"
        create(root)
        rule.onNodeWithText(root).performScrollTo().performClick()
        create("Trips")
        rule.onNodeWithText("Trips").performScrollTo().performClick()
        create("Empty child"); create("2026")
        rule.onNodeWithText("2026").performScrollTo().performClick()
        val leaf = FolderPath.child(FolderPath.child(root, "Trips"), "2026")
        assertEquals(leaf, model.ui.value.folder)
        val item = model.vault.importFile("nested-note.txt", "text/plain", leaf, "Nested synthetic content".byteInputStream())
        rule.runOnIdle { model.refresh() }; done()
        rule.onNodeWithText("nested-note.txt").assertIsDisplayed()
        rule.onNodeWithText("Photos").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back to folders").performClick()
        assertEquals(FolderPath.parent(leaf), model.ui.value.folder)
        rule.onNodeWithText("My vault").performClick()
        rule.onNodeWithText("Trips").assertDoesNotExist()
        rule.onNodeWithText("2026").assertDoesNotExist()
        rule.onNodeWithContentDescription("Actions for folder $root").performScrollTo().performClick()
        rule.onNodeWithText("Rename folder").performClick()
        val renamed = "$root renamed"
        rule.onNodeWithText("Folder name").performTextReplacement(renamed)
        rule.onNodeWithText("Save").performClick(); done()
        val renamedLeaf = FolderPath.renamed(leaf, root, renamed)
        assertEquals(renamedLeaf, model.ui.value.items.single { it.id == item.id }.folder)
        rule.onNodeWithContentDescription("Actions for folder $renamed").performScrollTo().performClick()
        rule.onNodeWithText("Move folder to recycle bin").performClick()
        rule.onNodeWithText("Move to bin").performClick(); done()
        assertEquals(4, model.ui.value.trashFolders.single { it.name == renamed }.paths.size)
        rule.onNodeWithContentDescription("Recycle bin").performClick()
        rule.onNodeWithContentDescription("Restore folder $renamed").performScrollTo().performClick(); done()
        assertTrue(FolderPath.child(FolderPath.child(renamed, "Trips"), "Empty child") in model.ui.value.folders)
        rule.runOnIdle { model.openFolder(renamedLeaf); model.lock(); model.unlock("246810") }; done()
        assertEquals(renamedLeaf, model.ui.value.folder)
        rule.onNodeWithText("nested-note.txt").assertIsDisplayed()
        screenshot("subfolder-portrait")
        rule.runOnIdle { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(30000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        assertTrue(model.vault.unlocked)
        rule.onNodeWithText("nested-note.txt").performScrollTo().assertIsDisplayed()
        screenshot("subfolder-landscape")
        rule.runOnIdle { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(rule.activity.getExternalFilesDir(null), "qa-subfolders").also { it.mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
