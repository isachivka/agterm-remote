package dev.isachivka.agtermremote.agterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is exercised on the exact shapes a `zmx history --vt` dump carried on 2026-09-05, after the
 * bridge reduced it to text and SGR: the `\e[0m` before every run, palette colours as `38;5;N`, RGB as
 * `38;2;r;g;b`, and bold opened and closed on the same row.
 */
class SgrTest {

    private fun runs(text: String) = Sgr.parse(text)

    @Test
    fun `plain text is one unstyled run`() {
        val r = runs("hello")
        assertEquals(1, r.size)
        assertEquals("hello", r[0].text)
        assertEquals(SgrStyle(), r[0].style)
    }

    @Test
    fun `a palette colour is resolved to rgb`() {
        val r = runs("\u001B[0m\u001B[38;5;4m(main)\u001B[0m ok")
        assertEquals(listOf("(main)", " ok"), r.map { it.text })
        assertEquals(Sgr.PALETTE[4], r[0].style.foreground)
        assertNull(r[1].style.foreground)
    }

    @Test
    fun `a truecolor foreground carries its exact rgb`() {
        val r = runs("\u001B[38;2;177;185;249mpath\u001B[0m")
        assertEquals(0xFFB1B9F9.toInt(), r[0].style.foreground)
    }

    @Test
    fun `bold opens and a reset closes it`() {
        val r = runs("a \u001B[1mb\u001B[0m c")
        assertEquals(listOf("a ", "b", " c"), r.map { it.text })
        assertTrue(r[1].style.bold)
        assertEquals(SgrStyle(), r[2].style)
    }

    @Test
    fun `attributes stack and cancel individually`() {
        val r = runs("\u001B[1m\u001B[3m\u001B[4mx\u001B[22my\u001B[23m\u001B[24mz")
        assertEquals(SgrStyle(bold = true, italic = true, underline = true), r[0].style)
        assertEquals(SgrStyle(italic = true, underline = true), r[1].style)
        assertEquals(SgrStyle(), r[2].style)
    }

    @Test
    fun `the 256 colour cube and greys are computed`() {
        assertEquals(0xFF000000.toInt(), Sgr.colour(16))
        assertEquals(0xFF0000FF.toInt(), Sgr.colour(21))
        assertEquals(0xFFFFFFFF.toInt(), Sgr.colour(231))
        assertEquals(0xFF080808.toInt(), Sgr.colour(232))
        assertEquals(0xFFEEEEEE.toInt(), Sgr.colour(255))
    }

    @Test
    fun `background inverse and faint are carried`() {
        val r = runs("\u001B[48;5;1m\u001B[7m\u001B[2mx")
        assertEquals(Sgr.PALETTE[1], r[0].style.background)
        assertTrue(r[0].style.inverse)
        assertTrue(r[0].style.faint)
    }

    @Test
    fun `an unknown parameter is skipped without losing the rest`() {
        val r = runs("\u001B[99;1mx")
        assertTrue(r[0].style.bold)
    }

    @Test
    fun `a bare reset between identical runs does not split the text`() {
        // The dump writes `\e[0m` before every styled run; two adjacent plain pieces stay one run so the
        // AnnotatedString does not fragment into hundreds of spans.
        val r = runs("a\u001B[0mb")
        assertEquals(listOf("ab"), r.map { it.text })
    }

    @Test
    fun `tabs are expanded against the visible column not the byte offset`() {
        val r = runs("\u001B[1mab\u001B[0m\tc")
        assertEquals("ab", r[0].text)
        assertEquals("      c", r[1].text)
    }

    @Test
    fun `newlines pass through inside runs`() {
        val r = runs("\u001B[1ma\nb")
        assertEquals("a\nb", r[0].text)
    }
}
