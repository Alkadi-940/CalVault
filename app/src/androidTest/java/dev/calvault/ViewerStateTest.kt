package dev.calvault

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class ViewerStateTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
    private fun unlock() {
        rule.runOnIdle { model.unlock("246810") }
        rule.waitUntil(60000) { !model.ui.value.busy && model.vault.unlocked }
    }

    @Test fun photoSlideshowRotationAndAppearance() {
        unlock()
        val pictures = (1..3).map { n ->
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(listOf(0xff789b89.toInt(), 0xff8a98b7.toInt(), 0xffc49f82.toInt())[n - 1]) }
            val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            model.vault.importFile("Slideshow $n.png", "image/png", "Slideshow", png.inputStream())
        }
        rule.runOnIdle { model.refresh() }
        rule.waitUntil(10000) { !model.ui.value.busy }
        rule.runOnIdle { model.updateSettings(model.settings.value.copy(slideshowShuffle = true, slideshowSeconds = 1, slideTransition = SlideTransition.SLIDE)); model.view(pictures[0]) }
        val seen = mutableSetOf<String>()
        repeat(3) { rule.runOnIdle { seen += model.ui.value.viewing!!.id; model.nextPhoto() } }
        assertEquals(3, seen.size)
        val initial = model.ui.value.viewing!!.id
        rule.onNodeWithContentDescription("Start slideshow").performClick()
        rule.waitUntil(15000) { model.ui.value.viewing!!.id != initial }
        rule.onNodeWithContentDescription("Pause slideshow").performClick()
        rule.runOnIdle { model.imageTransform(model.ui.value.viewing!!.id, 2f, .25f, -.1f); rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(30000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        assertTrue(model.vault.unlocked)
        assertEquals(2f, model.ui.value.imageScale)
        screenshot("slideshow-landscape")
        rule.runOnIdle { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; model.openFolder(null); model.setSearch(""); model.updateSettings(model.settings.value.copy(theme = ThemeStyle.AMOLED, accent = 0xff65d6c7.toInt())) }
        rule.waitUntil(30000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        screenshot("folders-amoled")
        rule.runOnIdle { model.openSettings("Video") }; screenshot("video-settings")
        rule.runOnIdle { model.settingsSection("Images") }; screenshot("image-settings")
        rule.runOnIdle { model.settingsSection("Storage") }; screenshot("storage-settings")
        rule.onAllNodesWithText("Fingerprint unlock").assertCountEquals(0)
        rule.runOnIdle { model.settingsSection("Security") }; screenshot("security-settings")
        rule.runOnIdle { model.settingsSection("Retrieval") }; screenshot("retrieval-settings")
        rule.runOnIdle { model.settingsSection("Appearance") }; screenshot("appearance-settings")
        rule.runOnIdle { model.settingsSection("Migration") }; screenshot("migration")
        rule.runOnIdle { model.updateSettings(model.settings.value.copy(theme = ThemeStyle.LIGHT)); model.openFolder("Slideshow"); model.setSearch("") }; screenshot("folder-light")
    }

    @Test fun prepareRestartBookmark() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("restartPhase") == "prepare")
        unlock()
        val item = model.vault.items().first { it.mime.startsWith("video/") }
        rule.runOnIdle { model.view(item) }
        rule.waitUntil(30000) { model.playback.flow.value.ready }
        rule.runOnIdle { model.playback.pause(); model.playback.seek(4200); model.saveBookmarkNow() }
        rule.runOnIdle { assertTrue(model.playback.position >= 4100) }
    }

    @Test fun returnedPickersRestoreEncryptedContextAfterModelRecreation() {
        unlock()
        rule.runOnIdle { model.updateSettings(model.settings.value.copy(lockOnBackground = false)); model.createFolder("Picker resume") }
        rule.waitUntil(10000) { !model.ui.value.busy }
        val resolver = rule.activity.contentResolver
        val source = android.provider.DocumentsContract.buildDocumentUri("dev.calvault.test.documents", "root/readonly-c.txt")
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri("dev.calvault.test.documents", "root")
        rule.runOnIdle { rule.activity.startActivity(android.content.Intent().setClassName("dev.calvault.test", "dev.calvault.FixtureSetupActivity")) }
        rule.waitUntil(15000) { runCatching { resolver.openInputStream(source)!!.close() }.isSuccess }
        rule.runOnIdle { model.preparePicker("Picker resume", dev.calvault.core.StorageMode.HIDDEN); model.lock() }
        val recreated = VaultViewModel(rule.activity.application)
        rule.runOnIdle { recreated.selectedImports(listOf(source)); recreated.unlock("246810") }
        rule.waitUntil(60000) { !recreated.ui.value.busy && recreated.vault.unlocked }
        val item = recreated.vault.items().last { it.folder == "Picker resume" }
        assertEquals(dev.calvault.core.StorageMode.HIDDEN, item.mode)
        assertEquals(dev.calvault.core.SourceState.PENDING_REMOVAL, item.sourceState)
        rule.runOnIdle { recreated.requestUnhide(setOf(item.id), false); recreated.treeLaunched(); recreated.lock() }
        val next = VaultViewModel(rule.activity.application)
        rule.runOnIdle { next.selectedTree(tree); next.unlock("246810") }
        rule.waitUntil(60000) { !next.ui.value.busy && next.vault.unlocked }
        assertFalse(next.ui.value.message.orEmpty(), next.vault.items().any { it.id == item.id })
        rule.runOnIdle { next.lock() }
    }

    @Test fun verifyRestartBookmark() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("restartPhase") == "verify")
        assertFalse(model.vault.unlocked)
        assertNull(model.ui.value.viewing)
        unlock()
        assertEquals(Screen.VIEWER, model.ui.value.screen)
        assertTrue(model.ui.value.viewing!!.mime.startsWith("video/"))
        assertTrue("Expected saved video position, got ${model.ui.value.videoPosition}", model.ui.value.videoPosition >= 4100)
        rule.waitUntil(30000) { model.playback.flow.value.ready }
        screenshot("video-player")
        rule.runOnIdle { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(30000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        screenshot("video-landscape")
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        rule.runOnIdle { rule.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        try {
            // Allow WindowManager's secure-layer update to reach the compositor before native capture.
            if (name.startsWith("video-")) android.os.SystemClock.sleep(400)
            rule.waitForIdle()
            val image = if (name.startsWith("video-")) InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!! else rule.onRoot().captureToImage().asAndroidBitmap()
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "qa-v020").also { it.mkdirs() }
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { rule.runOnIdle { rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
    }
}
