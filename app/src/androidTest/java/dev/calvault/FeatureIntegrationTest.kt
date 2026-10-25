package dev.calvault

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.calvault.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FeatureIntegrationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
    private fun done() { rule.waitUntil(60000) { !model.ui.value.busy }; rule.waitForIdle() }
    private fun unlock() { rule.runOnIdle { model.unlock("246810") }; done(); assertTrue(model.ui.value.message.orEmpty(), model.vault.unlocked) }

    @Test fun movesMultipleDocumentsUnhidesAndKeepsReadOnlyOriginalHonest() {
        unlock()
        rule.runOnIdle { model.updateSettings(model.settings.value.copy(lockOnBackground = false)); model.createFolder("Moves") }; done()
        val resolver = rule.activity.contentResolver
        fun uri(name: String) = DocumentsContract.buildDocumentUri("dev.calvault.test.documents", "root/$name")
        val a = uri("move-a.txt"); val b = uri("move-b.txt"); val c = uri("readonly-c.txt")
        val tree = DocumentsContract.buildTreeDocumentUri("dev.calvault.test.documents", "root")
        rule.runOnIdle { rule.activity.startActivity(android.content.Intent().setClassName("dev.calvault.test", "dev.calvault.FixtureSetupActivity")) }
        rule.waitUntil(15000) { runCatching { resolver.openInputStream(c)!!.close() }.isSuccess }
        SourceFiles(rule.activity).retain(tree)
        rule.runOnIdle { model.preparePicker("Moves", StorageMode.HIDDEN); model.selectedImports(listOf(a, b, c)) }; done()
        val items = model.vault.items().filter { it.folder == "Moves" }
        assertEquals(3, items.size)
        assertTrue(items.all { it.mode == StorageMode.HIDDEN })
        assertEquals(2, items.count { it.sourceState == SourceState.REMOVED })
        assertEquals(SourceState.PENDING_REMOVAL, items.single { it.name == "readonly-c.txt" }.sourceState)
        assertTrue(runCatching { resolver.openInputStream(a)!!.close() }.isFailure)
        assertTrue(runCatching { resolver.openInputStream(c)!!.close() }.isSuccess)
        val source = SourceFiles(rule.activity)
        val first = items.single { it.name == "move-a.txt" }
        val target = source.documentDestination(tree, first)
        source.writeUnhidden(model.vault, first, target)
        resolver.openInputStream(target.uri)!!.use { assertEquals("Private synthetic move-a.txt", it.reader().readText()) }
        model.vault.finishUnhide(setOf(first.id))
        assertFalse(model.vault.items().any { it.id == first.id })
        val second = items.single { it.name == "move-b.txt" }
        // Original tree grant was recorded; no guessed raw path or broad permission is used.
        val original = source.originalDestination(model.vault, second)!!
        source.writeUnhidden(model.vault, second, original)
        model.vault.finishUnhide(setOf(second.id))
        resolver.openInputStream(original.uri)!!.use { assertEquals("Private synthetic move-b.txt", it.reader().readText()) }
        rule.runOnIdle { model.refresh() }; done()
    }

    @Test fun binPlaylistsRotationLockPreferencesAndBookmarks() {
        unlock()
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.mp4").use { it.readBytes() }
        val videos = (1..3).map { model.vault.importFile("Playlist $it.mp4", "video/mp4", "Playlist", bytes.inputStream()) }
        rule.runOnIdle { model.refresh() }; done()
        rule.runOnIdle { model.updateSettings(model.settings.value.copy(lockOnBackground = false, seekForwardSeconds = 3, seekBackSeconds = 2, videoShuffle = true)); model.view(videos[0]) }
        rule.waitUntil(30000) { model.playback.flow.value.ready }
        rule.runOnIdle { assertEquals(3, model.playback.order.toSet().size); model.playback.seek(1000) }
        rule.onNodeWithContentDescription("Video surface").performTouchInput { doubleClick(Offset(width * .8f, height * .25f)) }
        rule.runOnIdle { assertTrue(model.playback.position in 3900..4200) }
        rule.onNodeWithContentDescription("Video surface").performTouchInput { doubleClick(Offset(width * .2f, height * .25f)) }
        rule.runOnIdle { assertTrue(model.playback.position in 1900..2200) }
        val seen = mutableSetOf<String>()
        repeat(3) { rule.runOnIdle { seen += model.playback.player!!.currentMediaItem!!.mediaId; model.playback.next() } }
        assertEquals(3, seen.size)
        rule.runOnIdle { model.updateSettings(model.settings.value.copy(videoReplay = true)); assertEquals(Player.REPEAT_MODE_ONE, model.playback.player!!.repeatMode); model.playback.seek(3500); rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(30000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        rule.runOnIdle { assertTrue(model.vault.unlocked); assertTrue(model.playback.position >= 3400) }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(model.vault.unlocked)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.runOnIdle { model.saveBookmarkNow(); model.lock(); model.unlock("246810") }; done()
        assertEquals(Screen.VIEWER, model.ui.value.screen)
        assertEquals("Playlist", model.ui.value.folder)
        assertTrue(model.ui.value.videoPosition >= 3400)
        rule.runOnIdle { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; model.back(); model.delete(setOf(videos[0].id)) }; done()
        assertEquals(1, model.vault.trash().size)
        rule.runOnIdle { model.openTrashFolder(null) }
        rule.onNodeWithContentDescription("Restore ${videos[0].name}").performScrollTo().performClick(); done()
        assertEquals(0, model.vault.trash().size)
        rule.runOnIdle { model.deleteFolder("Playlist") }; done()
        assertEquals(3, model.vault.trash().size)
        rule.runOnIdle { model.restoreTrash(model.vault.trashFolders().map { "folder:${it.id}" }.toSet()) }; done()
        assertEquals(3, model.vault.items().count { it.folder == "Playlist" })
        rule.runOnIdle { model.delete(setOf(videos[0].id)); }; done()
        rule.runOnIdle { model.openTrashFolder(null) }
        rule.onNodeWithContentDescription("Permanently delete ${videos[0].name}").performScrollTo().performClick()
        rule.onNodeWithText("Delete permanently").performClick(); done()
        assertEquals(0, model.vault.trash().size)
        rule.runOnIdle { model.updateSettings(model.settings.value.copy(lockOnBackground = true)); model.openFolder("Playlist"); model.setSearch("Playlist"); model.select(videos[1].id); model.saveBookmarkNow() }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertFalse(model.vault.unlocked)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        unlock()
        assertEquals("Playlist", model.ui.value.folder)
        assertEquals(setOf(videos[1].id), model.ui.value.selected)
    }

    @Test fun galleryMoveUsesAndroidConsentAndUnhidesToOriginalAlbum() {
        unlock()
        rule.runOnIdle { model.updateSettings(model.settings.value.copy(lockOnBackground = false)); model.preparePicker("Personal", StorageMode.ENCRYPTED) }
        val resolver = rule.activity.contentResolver
        val marker = DocumentsContract.buildDocumentUri("dev.calvault.test.documents", "root/media-uri.txt")
        val prior = runCatching { resolver.openInputStream(marker)!!.use { it.reader().readText() } }.getOrNull()
        rule.runOnIdle { rule.activity.startActivity(android.content.Intent().setClassName("dev.calvault.test", "dev.calvault.FixtureSetupActivity").putExtra("gallery", true)) }
        rule.waitUntil(15000) { runCatching { resolver.openInputStream(marker)!!.use { it.reader().readText() } != prior }.getOrDefault(false) }
        val media = resolver.openInputStream(marker)!!.use { Uri.parse(it.reader().readText()) }
        rule.runOnIdle { model.selectedImports(listOf(media)) }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var approved = false
        try { rule.waitUntil(30000) {
            val root = automation.rootInActiveWindow
            val node = listOf("Delete", "Allow").flatMap { root?.findAccessibilityNodeInfosByText(it).orEmpty() }.firstOrNull { candidate -> listOf("delete", "allow").any { candidate.text?.toString()?.equals(it, true) == true } && candidate.isEnabled }
            if (node != null) { approved = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK); if (!approved) approved = node.parent?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true }
            approved
        } } catch (e: Throwable) { throw AssertionError("Gallery consent not shown: ${model.ui.value.imports}; ${model.ui.value.message}", e) }
        done()
        if (!model.vault.unlocked) unlock()
        rule.waitUntil(20000) { !model.ui.value.busy && model.ui.value.items.any { it.source.uri == media.toString() && it.sourceState == SourceState.REMOVED } }
        val item = model.vault.items().single { it.source.uri == media.toString() }
        assertTrue(item.source.relativePath.endsWith("Pictures/CalVaultQA/"))
        assertTrue(runCatching { resolver.openInputStream(media)!!.close() }.isFailure)
        rule.runOnIdle { model.requestUnhide(setOf(item.id), true) }; done()
        assertFalse(model.vault.items().any { it.id == item.id })
    }
}
