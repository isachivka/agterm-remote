package dev.isachivka.agtermremote.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.agtermremote.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every bundled glyph inflates on a real device.
 *
 * A `.xml` with malformed `pathData` or a bad viewport throws while inflating, so a drawable that did
 * not survive conversion cannot reach the screen. Rendering the grid is what proves that, and it is
 * the only assertion here worth a device.
 *
 * **The count that used to be in this file has moved to the JVM, and become a different question.**
 * It pinned `AppIcons.all.size` at 38, which is a ratchet against adding a glyph carelessly and no
 * defence at all against removing a screen: twenty-two glyphs outlived their last caller and the
 * number never moved. `AppIconsTest` on the JVM now asks whether anything still DRAWS each one, which
 * is a fact about the call sites rather than about this list, and it answers in milliseconds.
 *
 * What neither covers is whether each glyph is the right shape, the right way up and the right size
 * — the actual risk in converting paths by machine. `DesignScreenshotTest` takes the picture that
 * does.
 */
@RunWith(AndroidJUnit4::class)
class AppIconsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyGlyphInflates() {
        compose.setContent { AppTheme { AppIconGrid() } }
        compose.waitForIdle()
    }
}
