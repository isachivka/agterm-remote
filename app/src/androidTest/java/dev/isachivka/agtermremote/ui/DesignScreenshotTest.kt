package dev.isachivka.agtermremote.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.isachivka.agtermremote.ui.settings.SettingsScreen
import dev.isachivka.agtermremote.ui.theme.AppTheme
import dev.isachivka.agtermremote.ui.theme.ColourRoles
import dev.isachivka.agtermremote.ui.theme.TypeScale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Turns every "look at this" composable into a PNG.
 *
 * A screenshot of a preview beats a paragraph asserting it looks right, and this is how that gets
 * produced without Android Studio: render, capture, write, `adb pull`. What each image is for is
 * documented on the composable it captures.
 *
 * The PNGs are written to the external cache and pulled off the device for the pull request. They
 * are not committed — they regenerate in seconds, and a binary that changes whenever a colour does
 * is noise in every future diff.
 *
 * These assert only that a file was produced. They are not screenshot *comparison* tests: pinning
 * pixels would fail on every intentional change and on a different emulator skin, which is how a
 * team learns to re-baseline without looking.
 */
@RunWith(AndroidJUnit4::class)
class DesignScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            AppTheme {
                // The same Surface MainActivity puts at its root. Without it the captured bitmap is
                // transparent wherever a screen does not paint its own background, which comes out
                // white and makes a dark app look like a light one in the very image meant to show
                // how it looks.
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        compose.waitForIdle()

        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        // filesDir and not externalCacheDir: the latter returns null on an emulator with no
        // external volume mounted for the package, and File(null, "screenshots") is a *relative*
        // path that lands in the process working directory. That is how the first version of this
        // wrote three files nobody could find and still passed.
        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "screenshots",
        ).apply { mkdirs() }

        val file = File(directory, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertTrue("no screenshot written to ${file.absolutePath}", file.length() > 0)
    }

    @Test
    fun theIconGrid() = capture("icons") { AppIconGrid() }

    @Test
    fun theSettings() = capture("settings") {
        SettingsScreen(onBack = {})
    }

    @Test
    fun theColourRoles() = capture("colour-roles") { ColourRoles() }

    @Test
    fun theTypeScale() = capture("type-scale") { TypeScale() }
}
