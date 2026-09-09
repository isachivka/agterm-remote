package dev.isachivka.bewareofsugar.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's background colour exists twice, and this is what keeps the two copies honest.
 *
 * The framework paints the window before any Kotlin runs, so the first frame of a cold start comes
 * from `res/values/colors.xml`; every frame after it comes from `DesignBackground`. If those two
 * drift apart the result is a flash of the old colour on every launch — which never appears in a
 * screenshot, never fails a UI test, and is the first thing the owner sees every single time.
 *
 * Reads the resource file directly, like `BackupRulesTest`: the claim is about what ships in the
 * `.apk`, and making it needs no device.
 */
class WindowBackgroundTest {

    private fun colorsXml(): File = listOf(
        // Unit tests run with the module directory as the working directory; the second path is
        // there so running from the repository root works too.
        File("src/main/res/values/colors.xml"),
        File("app/src/main/res/values/colors.xml"),
    ).firstOrNull { it.exists() } ?: error("cannot find colors.xml from ${File(".").absolutePath}")

    private fun colorNamed(name: String): Color {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(colorsXml())
        val nodes = document.getElementsByTagName("color")
        val literal = (0 until nodes.length)
            .map { nodes.item(it) }
            .firstOrNull { it.attributes.getNamedItem("name")?.nodeValue == name }
            ?.textContent
            ?.trim()
            ?: error("no <color name=\"$name\"> in colors.xml")

        // Compared as a packed value rather than through toArgb(), which would drag a colour-space
        // conversion into a test that is only asking whether two literals are the same literal.
        return Color(literal.removePrefix("#").toLong(radix = 16))
    }

    @Test
    fun `the window background is the same colour Compose draws`() {
        assertEquals(
            "res/values/colors.xml and ui/theme/Color.kt disagree, so a cold start will flash",
            DesignBackground,
            colorNamed("window_background"),
        )
    }
}
