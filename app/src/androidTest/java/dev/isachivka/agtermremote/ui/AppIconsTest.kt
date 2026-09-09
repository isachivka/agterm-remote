package dev.isachivka.agtermremote.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.agtermremote.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bundled glyphs are all there, and they all load.
 *
 * Rendering the grid is the second of those assertions: a `.xml` with malformed `pathData` or a bad
 * viewport throws while inflating, so a drawable that did not survive conversion cannot reach the
 * screen. What that does *not* cover is whether each glyph is the right shape, the right way up and
 * the right size — the actual risk in converting 27 paths by machine — and no assertion covers it
 * either. `DesignScreenshotTest` takes the picture that does.
 */
@RunWith(AndroidJUnit4::class)
class AppIconsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyGlyphTheDesignUsesIsBundled() {
        // 26 from design/v0's markup and its MODULES array, once the device chrome (signal, wifi,
        // battery) and the dropped "What will live here" checkbox are removed - plus network_check,
        // which REQ-0005's module needed and the design never drew, plus the ones design/v1's two
        // terminal screens added. A count is weak on its own; it is here so that deleting a drawable
        // and its AppIcons entry together still fails rather than passing quietly.
        // 35 since REQ-0013 added the Claude mark. The bump is the deliberate act this count exists to
        // force: adding a glyph fails here until somebody says how many there should be.
        // 36 since REQ-0029 replaced the overpull's green label with a checkmark - the first glyph in
        // this set that was drawn here rather than converted from Material Symbols.
        // 38 since REQ-0032's pane pair, which are drawn here too but from a different source again:
        // measured off a screenshot of agterm's own toolbar, so the phone shows the owner the picture
        // his laptop already shows him.
        val expected = 38
        assertEquals(expected, AppIcons.all.size)
        assertEquals("glyph names must be unique", expected, AppIcons.all.map { it.first }.toSet().size)
        // Two names pointing at one drawable is the copy-paste mistake this catches, and it would
        // otherwise show up as the wrong icon on one screen and nowhere else.
        assertEquals("drawables must be distinct", expected, AppIcons.all.map { it.second }.toSet().size)
    }

    @Test
    fun everyGlyphInflates() {
        compose.setContent { AppTheme { AppIconGrid() } }
        compose.waitForIdle()
    }
}
