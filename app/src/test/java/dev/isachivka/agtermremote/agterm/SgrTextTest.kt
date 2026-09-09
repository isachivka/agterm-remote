package dev.isachivka.agtermremote.agterm

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SgrTextTest {

    private val default = Color(0xFFCCCCCC)
    private val ground = Color(0xFF111111)

    @Test
    fun `the text of the annotated string is the runs joined`() {
        val a = SgrText.annotate("a\u001B[1mb\u001B[0mc", default, ground)
        assertEquals("abc", a.text)
    }

    @Test
    fun `a plain run gets no span`() {
        val a = SgrText.annotate("plain", default, ground)
        assertTrue(a.spanStyles.isEmpty())
    }

    @Test
    fun `colour weight slant and underline become span styles`() {
        val a = SgrText.annotate("\u001B[1;3;4;38;2;10;20;30mx", default, ground)
        val span = a.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals(1, span.end)
        assertEquals(Color(0xFF0A141E), span.item.color)
        assertEquals(FontWeight.Bold, span.item.fontWeight)
        assertEquals(FontStyle.Italic, span.item.fontStyle)
        assertEquals(TextDecoration.Underline, span.item.textDecoration)
    }

    @Test
    fun `inverse swaps the colours against the defaults`() {
        val a = SgrText.annotate("\u001B[7mx", default, ground)
        val span = a.spanStyles.single()
        assertEquals(ground, span.item.color)
        assertEquals(default, span.item.background)
    }

    /**
     * A family wrapped from a platform `Typeface` draws one face whatever the span asks for, which is
     * why bold never showed on the phone. The fix is to pick the face by name: each span carries the
     * family for its weight and slant, and the box supplies four families built from the same chain.
     */
    @Test
    fun `each span names the family for its weight and slant`() {
        val faces = TerminalFaces(
            regular = FontFamily.Monospace,
            bold = FontFamily.Serif,
            italic = FontFamily.Cursive,
            boldItalic = FontFamily.SansSerif,
        )
        val a = SgrText.annotate("a[1mb[0m[3mc[0m[1;3md", default, ground, faces)
        val byText = a.spanStyles.associate { a.text.substring(it.start, it.end) to it.item.fontFamily }
        assertEquals(FontFamily.Serif, byText["b"])
        assertEquals(FontFamily.Cursive, byText["c"])
        assertEquals(FontFamily.SansSerif, byText["d"])
        assertTrue("a plain run must not carry a family", a.spanStyles.none { a.text.substring(it.start, it.end) == "a" })
    }

    @Test
    fun `a coloured but upright regular run names the regular family`() {
        val faces = TerminalFaces(FontFamily.Monospace, FontFamily.Serif, FontFamily.Cursive, FontFamily.SansSerif)
        val a = SgrText.annotate("[38;5;1mx", default, ground, faces)
        assertEquals(FontFamily.Monospace, a.spanStyles.single().item.fontFamily)
    }

    @Test
    fun `faint dims the foreground`() {
        val a = SgrText.annotate("\u001B[2mx", default, ground)
        assertTrue(a.spanStyles.single().item.color.alpha < 1f)
    }
}
