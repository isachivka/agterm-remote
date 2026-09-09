package dev.isachivka.bewareofsugar.ui.theme

import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.bewareofsugar.MainActivity
import dev.isachivka.bewareofsugar.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the system actually paints before Compose draws.
 *
 * `WindowBackgroundTest` compares two literals, which is the drift this pins. This one asks the
 * platform, against a real launched Activity, which is the only context whose theme is the one the
 * manifest names — an application context with no theme explicitly set falls back to a *platform*
 * default and answers a question nobody asked.
 *
 * It is read from `window.decorView`, because that drawable is literally the thing on screen between
 * the launcher icon being tapped and the first Compose frame. Run against the theme this replaced it
 * fails, which is what makes it evidence rather than a restatement of the fix.
 */
@RunWith(AndroidJUnit4::class)
class WindowBackgroundInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theWindowBackgroundIsTheDesignBackground() {
        val activity = compose.activity
        val expected = activity.getColor(R.color.window_background)

        // A ColorDrawable and not, say, a layer list: the platform's own themes put layered
        // drawables here, so "it is a flat colour" is half of what is being asserted.
        val background = activity.window.decorView.background
        val actual = (background as? ColorDrawable)?.color

        assertEquals("decorView background was $background", expected, actual)
    }
}
