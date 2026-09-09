package dev.isachivka.bewareofsugar.agterm

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/** Turns [Sgr] runs into the one thing Compose's `Text` draws with colour: an [AnnotatedString]. */
object SgrText {

    /**
     * [default] is the colour a plain screen is drawn in and [ground] is what it is drawn on; they
     * stand in for a terminal's default foreground and background, which is what `inverse` swaps and
     * what an unset colour means. A run with no attributes gets no span at all, so a plain screen
     * that happened to arrive styled costs the same as before.
     */
    fun annotate(text: String, default: Color, ground: Color, faces: TerminalFaces? = null): AnnotatedString =
        buildAnnotatedString {
            for (run in Sgr.parse(text)) {
                val span = span(run.style, default, ground, faces)
                if (span == null) append(run.text) else withStyle(span) { append(run.text) }
            }
        }

    /**
     * [faces] is what actually makes bold bold: a family wrapped from a platform typeface ignores
     * `fontWeight` and `fontStyle`, so every styled run names the family for its weight and slant
     * (see [terminalFaces]). The weight and style are still set for any consumer that does read them.
     */
    private fun span(s: SgrStyle, default: Color, ground: Color, faces: TerminalFaces?): SpanStyle? {
        if (s == SgrStyle()) return null
        var fg = s.foreground?.let { Color(it) } ?: default
        var bg = s.background?.let { Color(it) }
        if (s.inverse) {
            val swapped = bg ?: ground
            bg = fg
            fg = swapped
        }
        if (s.faint) fg = fg.copy(alpha = FAINT_ALPHA)
        return SpanStyle(
            color = fg,
            background = bg ?: Color.Unspecified,
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            fontFamily = faces?.of(bold = s.bold, italic = s.italic),
            textDecoration = if (s.underline) TextDecoration.Underline else null,
        )
    }

    private const val FAINT_ALPHA = 0.6f
}
