package dev.calvault

import android.graphics.Bitmap
import android.graphics.Color
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.calvault.core.Crypto
import dev.calvault.core.Vault
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CalVaultFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(rule.activity)[VaultViewModel::class.java]

    @Test fun setupImportViewLockRecoverAndBackup() {
        rule.onNodeWithText("Create my vault").assertIsDisplayed()
        screenshot("welcome")
        rule.onNodeWithText("Create my vault").performClick()
        rule.onNodeWithText("Create PIN").performTextInput("135790")
        rule.onNodeWithText("Confirm PIN").performTextInput("135790")
        rule.onNodeWithText("Continue").performScrollTo().performClick()
        val keyNode = rule.onNode(SemanticsMatcher("Recovery key groups") {
            val text = it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { value -> value.text }.orEmpty()
            text.contains(Regex("[0-9A-F]{8}  [0-9A-F]{8}  [0-9A-F]{8}"))
        }).fetchSemanticsNode()
        val secret = keyNode.config[SemanticsProperties.Text].joinToString(" ") { it.text }.trim().replace(Regex("\\s+"), "-")
        assertEquals(32, Crypto.parseRecovery(secret).size)
        val groups = secret.split('-')
        rule.onNodeWithText("Confirm group 3").performScrollTo().performTextInput(groups[2])
        rule.onNodeWithText("Confirm group 8").performScrollTo().performTextInput(groups[7])
        rule.onNode(isToggleable()).performScrollTo().performClick()
        rule.onNodeWithText("Create encrypted vault").performScrollTo().performClick()
        rule.waitUntil(60000) { model.ui.value.screen == Screen.VAULT && !model.ui.value.busy }
        rule.waitUntil(10000) { runCatching { rule.onNodeWithText("My vault").assertIsDisplayed() }.isSuccess }
        rule.onNodeWithText("My vault").assertIsDisplayed()

        val photo = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(109, 157, 138)) }
        val png = ByteArrayOutputStream().also { photo.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        photo.recycle()
        val image = model.vault.importFile("A quiet afternoon.png", "image/png", "Personal", png.inputStream())
        model.vault.importFile("Travel notes.txt", "text/plain", "Documents", "Synthetic private test content".byteInputStream())
        val video = InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.mp4").use {
            model.vault.importFile("Weekend by the sea.mp4", "video/mp4", "Personal", it)
        }
        rule.runOnIdle { model.refresh() }
        rule.waitUntil(30000) { !model.ui.value.busy && model.ui.value.items.size >= 2 }
        screenshot("vault")
        verifyVideoPlayback(video.id)
        rule.onNodeWithText("All").assertDoesNotExist()
        rule.onNodeWithText("New folder").performScrollTo().performClick()
        rule.onNodeWithText("Folder name").performTextInput("Empty folder")
        rule.onNodeWithText("Create", useUnmergedTree = true).performClick()
        rule.waitUntil(10000) { !model.ui.value.busy && "Empty folder" in model.ui.value.folders }
        rule.onNodeWithText("Personal").performScrollTo().performClick()
        rule.onNodeWithText("Photos").assertIsDisplayed()
        screenshot("folder")
        rule.onNodeWithText("A quiet afternoon.png").performScrollTo().performClick()
        rule.waitUntil(30000) { rule.onAllNodesWithContentDescription("A quiet afternoon.png").fetchSemanticsNodes().isNotEmpty() }
        screenshot("photo-viewer")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.runOnIdle { model.openSettings() }
        screenshot("security")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Lock vault").performClick()
        rule.onNodeWithText("Calculator").assertIsDisplayed()
        rule.runOnIdle { rule.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(30000) { rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        rule.onNodeWithContentDescription("Equals", useUnmergedTree = true).assertHeightIsAtLeast(48.dp)
        screenshot("calculator-landscape")
        rule.runOnIdle { rule.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        rule.waitUntil(30000) { rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        screenshot("calculator")
        // Standard equals calculates even when digits are the PIN.
        "135790".forEach { rule.onNodeWithContentDescription(it.toString(), useUnmergedTree = true).performClick() }
        rule.onNodeWithContentDescription("Equals", useUnmergedTree = true).performClick()
        assertFalse(model.vault.unlocked)
        rule.onNodeWithContentDescription("Equals", useUnmergedTree = true).performTouchInput { longClick() }
        rule.waitUntil(60000) { model.ui.value.screen == Screen.VAULT && !model.ui.value.busy }
        assertEquals("A quiet afternoon.png", model.vault.items().first { it.id == image.id }.name)
        assertTrue(rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)

        val backup = ByteArrayOutputStream()
        model.vault.writeBackup(backup)
        model.vault.verifyBackup(backup.toByteArray().inputStream())
        val restored = Vault(File(rule.activity.cacheDir, "restore-verification"), AndroidDeviceKey("calvault.test.restored"))
        restored.restoreBackup(backup.toByteArray().inputStream(), secret, "000111".toCharArray())
        assertEquals(model.vault.items().size, restored.items().size)
        val restoredImage = ByteArrayOutputStream()
        restored.export(image.id, restoredImage)
        assertArrayEquals(png, restoredImage.toByteArray())
        restored.lock()
        rule.runOnIdle { model.lock(); model.recover(secret, "246810") }
        rule.waitUntil(60000) { model.ui.value.screen == Screen.VAULT && !model.ui.value.busy }
        assertTrue(model.vault.items().isNotEmpty())
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertFalse(model.vault.unlocked)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        rule.onNodeWithText("Calculator").assertIsDisplayed()
    }

    private fun verifyVideoPlayback(id: String) {
        val prepared = CountDownLatch(1)
        val sought = CountDownLatch(1)
        var seeking = false
        lateinit var player: ExoPlayer
        rule.runOnIdle {
            player = ExoPlayer.Builder(rule.activity).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            if (seeking) sought.countDown() else prepared.countDown()
                        }
                    }
                })
                setMediaSource(ProgressiveMediaSource.Factory(VaultDataSource.Factory(model.vault, id))
                    .createMediaSource(MediaItem.fromUri("vault://$id".toUri())))
                prepare()
            }
        }
        try {
            assertTrue("Encrypted video never reached READY", prepared.await(30, TimeUnit.SECONDS))
            rule.runOnIdle {
                assertTrue(player.duration >= 7900)
                seeking = true
                player.seekTo(6500)
            }
            assertTrue("Encrypted seek never reached READY", sought.await(30, TimeUnit.SECONDS))
            rule.runOnIdle { assertTrue(player.currentPosition >= 6400) }
        } finally { rule.runOnIdle { player.release() } }
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        // Synthetic-fixture screenshots only. Production code always keeps FLAG_SECURE set.
        rule.runOnIdle { rule.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        try {
            rule.waitForIdle()
            val image = rule.onRoot().captureToImage().asAndroidBitmap()
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "qa")
            directory.mkdirs()
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { rule.runOnIdle { rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
    }
}
