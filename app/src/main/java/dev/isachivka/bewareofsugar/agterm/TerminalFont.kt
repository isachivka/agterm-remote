package dev.isachivka.bewareofsugar.agterm

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily
import android.graphics.fonts.FontStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.isachivka.bewareofsugar.R

/**
 * The font agterm draws with, bundled so the phone shows the owner's terminal as it actually is.
 *
 * ### Which font — JetBrains Mono, since 2026-09-05
 *
 * agterm draws its grid with **JetBrains Mono**, and this was established from the code rather than
 * from a settings screen. agterm sets no `font-family` anywhere: not in `~/.config/agterm/ghostty.conf`,
 * not in its bundled `ghostty-defaults.conf`, not in the `ghostty-settings.conf` its Settings window
 * generates (which carries only `font-size` and `theme`), and the global `~/.config/ghostty/config` —
 * which does say `font-family = Menlo` — is loaded only behind an opt-in toggle that is off. With no
 * family configured, libghostty's `SharedGridSet` adds the JetBrains Mono variable face it embeds as
 * regular, bold (`wght` 700) and italic, and that is the whole collection. The agterm binary carries
 * the font's own name table — "JetBrains Mono", eight UTF-16 hits — and not one byte of "Menlo".
 *
 * **Menlo was bundled from 2026-07-29 to 2026-09-05** on the owner's instruction to see everything
 * exactly as agterm shows it; agterm was never showing Menlo. The owner ruled the swap on 2026-09-05
 * after seeing the proof. The reason to prefer it was never taste: JetBrains Mono is OFL and
 * redistributable, Menlo is Apple's and was not.
 *
 * ### Four faces, and why bold is the point
 *
 * Regular, Bold, Italic and Bold Italic, **JetBrains Mono 2.304** (`res/raw/jetbrainsmono_ofl.txt`).
 * A styled screen ([Sgr]) asks for `FontWeight.Bold` and `FontStyle.Italic` spans; with one face the
 * platform fakes both by smearing and slanting, and with these four `Typeface.create(family, weight,
 * italic)` picks the real one. All four advance **600/1000 = 0.6000 em**, so a bold word is exactly
 * as wide as the plain one beside it and the grid holds through a status line.
 *
 * ### The fallback is unchanged — JuliaMono, REQ-0015
 *
 * JetBrains Mono lacks some of what Claude draws — its chrome ⏺ ⏵ ⎿ ※ ✅ ⏸ was measured against
 * Menlo in `docs/qa/req-0015-glyph-measurement.md`, and the design is the same for any primary: a
 * **monospaced** symbols font sits ahead of the platform chain, consulted only for codepoints the
 * primary lacks, so nothing the primary draws can move. JuliaMono advances 0.6000 em for every glyph
 * it has, which against JetBrains Mono's 0.6000 is not a rounding question any more: the two are the
 * same width at every size. (Against Menlo's 0.60205 it was a 0.34% gap that whole-pixel grid
 * fitting happened to hide at 16sp.)
 *
 * ### The system chain stays on the end, deliberately
 *
 * `CustomFallbackBuilder` keeps the platform's own fallback after our two families, and that is a
 * trade the owner ruled on rather than a default left in place. Characters neither bundled font has —
 * CJK, most emoji — render as real glyphs at platform widths, up to 1.245 em, which shifts the rest of
 * that row. That row was never going to line up: the laptop reserves two columns for a wide character
 * (the bridge's own `isWide`), so content wins over a row of boxes. There is no API to turn the chain
 * off — `setSystemFallback("")`, `setSystemFallback("sans-serif")` and omitting the call all produce
 * the same typeface on Android 17, measured.
 *
 * ### The fit key
 *
 * [characterWidthMilliDp] is the bridge's width-fit cache key, measured from `"X"` in this family. A
 * new primary can move it, and the bridge then treats the owner's calibration as unmeasured and
 * measures again on the next Fit — one calibration, not a broken one. At 16sp on the owner's phone
 * Menlo and JetBrains Mono both grid-fit to the same whole pixel, so it is not expected to move at all;
 * `TerminalFontRendersTest` reports the integer either way.
 */
val TerminalFontFamily: FontFamily
    @Composable get() = terminalFontFamily(LocalContext.current)

/** The same family off the composition, for a measurement that runs where there is no composable. */
fun terminalFontFamily(context: Context): FontFamily = terminalFaces(context).regular

/**
 * The four faces as four Compose families, one per weight-and-slant, all over the same fallback chain.
 *
 * **Why four families and not one family with four fonts.** A Compose `FontFamily` wrapped around a
 * platform `Typeface` draws that typeface and nothing else: the `fontWeight` and `fontStyle` a span
 * asks for are not consulted, which is why the first styled build showed colour and no bold at all,
 * not even a synthesised one — measured on the owner's phone, 2026-09-05. The platform side can answer
 * a weight (`Typeface.create(family, 700, italic)` picks the real face, as `TerminalFontRendersTest`
 * proves), so the answer is asked for here, once per face, and each styled run names the family it
 * wants rather than a weight nobody will read. See [SgrText].
 */
fun terminalFaces(context: Context): TerminalFaces {
    cached?.let { return it }
    return synchronized(lock) {
        cached ?: buildTerminalFaces(context.applicationContext).also { cached = it }
    }
}

/** One Compose family per face. All four share the primary's advance and the JuliaMono fallback. */
data class TerminalFaces(
    val regular: FontFamily,
    val bold: FontFamily,
    val italic: FontFamily,
    val boldItalic: FontFamily,
) {
    fun of(bold: Boolean, italic: Boolean): FontFamily = when {
        bold && italic -> boldItalic
        bold -> this.bold
        italic -> this.italic
        else -> regular
    }
}

/**
 * The width of one terminal cell in thousandths of a dp — the bridge's width-fit cache key.
 *
 * Measured from `"X"`, a character the primary font has, so the fallback is never consulted and the
 * integer depends on the primary face alone.
 */
fun characterWidthMilliDp(measurer: TextMeasurer, density: Density, family: FontFamily): Int {
    val width = measurer.measure(
        text = "X",
        style = TextStyle(fontFamily = family, fontSize = FitToPhone.TERMINAL_FONT_SIZE_SP.sp),
    ).size.width
    // Thousandths of a dp, so the wire carries an integer and no float rounding creeps in.
    return (with(density) { width.toDp().value.toDouble() } * 1000).toInt()
}

/**
 * The platform typeface [TerminalFontFamily] wraps: JetBrains Mono in four faces, then JuliaMono,
 * then the system chain. Null when a bundled file cannot be read or parsed, so the caller can fall
 * back rather than crash.
 *
 * Exposed so `TerminalFontRendersTest` can measure the chain with `Paint`, which is where the
 * advance-width facts above were established.
 */
fun terminalTypeface(context: Context): Typeface? = try {
    val primary = PlatformFontFamily.Builder(face(context, R.font.jetbrainsmono_regular, FontStyle.FONT_WEIGHT_NORMAL, false))
        .addFont(face(context, R.font.jetbrainsmono_bold, FontStyle.FONT_WEIGHT_BOLD, false))
        .addFont(face(context, R.font.jetbrainsmono_italic, FontStyle.FONT_WEIGHT_NORMAL, true))
        .addFont(face(context, R.font.jetbrainsmono_bolditalic, FontStyle.FONT_WEIGHT_BOLD, true))
        .build()
    val symbols = PlatformFontFamily.Builder(
        PlatformFont.Builder(context.resources, R.font.juliamono_regular).build(),
    ).build()
    Typeface.CustomFallbackBuilder(primary)
        .addCustomFallback(symbols)
        // No setSystemFallback call, and that is not an omission: the platform chain stays on the
        // end by REQ-0015 Decision 3, and passing "" or "sans-serif" here was measured to produce
        // exactly the same typeface anyway.
        .build()
} catch (unparseable: Exception) {
    // Broad on purpose: Font.Builder throws IOException on a file it cannot read and
    // IllegalArgumentException on one it cannot parse, and neither is worth a crash here.
    null
}

/**
 * One face with its weight and slant declared, so the family can answer a bold or italic request
 * with the real face rather than a synthesised one.
 */
private fun face(context: Context, resource: Int, weight: Int, italic: Boolean): PlatformFont =
    PlatformFont.Builder(context.resources, resource)
        .setWeight(weight)
        .setSlant(if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT)
        .build()

@Volatile
private var cached: TerminalFaces? = null
private val lock = Any()

private fun buildTerminalFaces(context: Context): TerminalFaces {
    val chain = terminalTypeface(context)
        ?: return TerminalFaces(
            regular = FontFamily(Font(R.font.jetbrainsmono_regular)),
            bold = FontFamily(Font(R.font.jetbrainsmono_bold)),
            italic = FontFamily(Font(R.font.jetbrainsmono_italic)),
            boldItalic = FontFamily(Font(R.font.jetbrainsmono_bolditalic)),
        )
    // Derived from the ONE chained typeface, so every face keeps JuliaMono and the platform behind it.
    fun face(weight: Int, italic: Boolean): FontFamily = FontFamily(Typeface.create(chain, weight, italic))
    return TerminalFaces(
        regular = face(FontStyle.FONT_WEIGHT_NORMAL, false),
        bold = face(FontStyle.FONT_WEIGHT_BOLD, false),
        italic = face(FontStyle.FONT_WEIGHT_NORMAL, true),
        boldItalic = face(FontStyle.FONT_WEIGHT_BOLD, true),
    )
}
