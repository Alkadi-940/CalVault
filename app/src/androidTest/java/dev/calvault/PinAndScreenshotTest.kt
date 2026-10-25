package dev.calvault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PinAndScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(rule.activity)[VaultViewModel::class.java]
    private fun done() { rule.waitUntil(60000) { !model.ui.value.busy }; rule.waitForIdle() }
    private fun unlock(pin: String) {
        pin.forEach { rule.onNodeWithContentDescription(it.toString(), useUnmergedTree = true).performClick() }
        rule.onNodeWithContentDescription("Equals", useUnmergedTree = true).performTouchInput { longClick() }
        done(); assertTrue(model.ui.value.message.orEmpty(), model.vault.unlocked)
    }
    @Test fun shortAndNineDigitPinsUnlockAndNativeScreenshotsAreAllowed() {
        val theme = model.settings.value.theme
        rule.runOnIdle { model.unlock("246810") }; done()
        assertTrue(model.vault.unlocked)
        rule.runOnIdle { model.openSettings("Security") }
        rule.onNodeWithText("New PIN").performScrollTo().performTextInput("12")
        rule.onNodeWithText("Confirm new PIN").performScrollTo().performTextInput("12")
        rule.onNodeWithText("Update PIN").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("New PIN").performTextReplacement("123")
        rule.onNodeWithText("Confirm new PIN").performTextReplacement("123")
        rule.onNodeWithText("Update PIN").performScrollTo().assertIsEnabled().performClick(); done()
        rule.runOnIdle { model.lock() }; unlock("123")
        rule.onNodeWithText("New PIN").performScrollTo().performTextInput("987654321")
        rule.onNodeWithText("Confirm new PIN").performScrollTo().performTextInput("987654321")
        rule.onNodeWithText("Update PIN").performScrollTo().performClick(); done()
        rule.runOnIdle { model.lock() }; unlock("987654321")
        rule.runOnIdle { model.updateSettings(model.settings.value.copy(theme = ThemeStyle.LIGHT)); model.openFolder(null) }
        rule.waitForIdle()
        assertEquals(0, rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        android.os.SystemClock.sleep(400)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        assertTrue("App content was blacked out", (2..8).any { x -> (2..8).any { y -> bitmap.getPixel(bitmap.width * x / 10, bitmap.height * y / 10) and 0x00FFFFFF != 0 } })
        val directory = File(rule.activity.getExternalFilesDir(null), "qa-v022").also { it.mkdirs() }
        File(directory, "screenshots-allowed.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.runOnIdle { model.changePin("246810"); model.updateSettings(model.settings.value.copy(theme = theme)) }; done()
    }
}
