package dev.isachivka.agtermremote.agterm

import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.res.ResourcesCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.isachivka.agtermremote.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled font is really the one being drawn with.
 *
 * **A font resource that fails to parse does not throw — it falls back**, and the fallback is another
 * monospace face that looks close enough to pass a glance. The owner asked to see their terminal *as
 * it actually is*, so "close enough" is precisely the failure this has to catch, and it is the same
 * shape as everything else in this milestone: something that reports success while describing
 * something other than what it names.
 *
 * The discriminator is the advance width. JetBrains Mono is 600/1000 = **0.6000 em**; the platform
 * monospace is 1229/2048 = 0.60010 em. A 0.02% difference is far too small to see and still large
 * enough to measure at 2048px over a hundred characters, which is why it is worth asserting.
 *
 * Until 2026-09-05 the primary was Menlo (0.60205 em), and several of the numbers below were first
 * measured against it; the design they assert is unchanged, only the face is.
 */
class TerminalFontRendersTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** JetBrains Mono, every face: 600/1000, read off the files with fontTools before bundling. */
    private val primaryAdvanceEm = 600.0 / 1000.0

    private val faces = listOf(
        R.font.jetbrainsmono_regular to "Regular",
        R.font.jetbrainsmono_bold to "Bold",
        R.font.jetbrainsmono_italic to "Italic",
        R.font.jetbrainsmono_bolditalic to "Bold Italic",
    )

    @Test
    fun everyBundledFaceLoadsAtAll() {
        for ((resource, name) in faces) {
            val typeface = ResourcesCompat.getFont(context, resource)
            assertNotNull("JetBrains Mono $name did not load; a broken file falls back silently", typeface)
        }
    }

    @Test
    fun everyBundledFaceHasJetBrainsMonosAdvanceWidth() {
        // 2048px, one unit per em-unit, and hinting off.
        //
        // At 200px this measured exactly 0.600 for BOTH a bundled font and the platform's — not
        // because a fallback was in use, but because glyph advances are grid-fitted to whole pixels.
        // The discriminator was destroyed by the measurement rather than by the thing measured. At
        // 2048px the advances are 1228.8 and 1229 pixels, which a hundred characters keep apart.
        for ((resource, name) in faces) {
            val advance = advanceEmOf(ResourcesCompat.getFont(context, resource)!!, "M")
            assertEquals(
                "$name advances $advance em; JetBrains Mono is $primaryAdvanceEm and a fallback would not match",
                primaryAdvanceEm,
                advance,
                0.0005,
            )
        }
    }

    /**
     * **Bold is the reason there are four faces.** A styled screen asks for `FontWeight.Bold`; with one
     * face the platform smears the regular one, with a real bold face it uses that — and a bold word
     * must be exactly as wide as the plain word beside it, or a status line drifts by a cell.
     */
    @Test
    fun theFamilyAnswersBoldAndItalicWithRealFacesOfTheSameWidth() {
        val family = terminalTypeface(context)!!
        val regular = Typeface.create(family, 400, false)
        val bold = Typeface.create(family, 700, false)
        val italic = Typeface.create(family, 400, true)

        assertTrue("bold resolved to the regular face; the family has no bold to give", bold != regular)
        assertTrue("italic resolved to the regular face; the family has no italic to give", italic != regular)
        for ((t, name) in listOf(regular to "regular", bold to "bold", italic to "italic")) {
            assertEquals("$name is not one cell wide", primaryAdvanceEm, advanceEmOf(t, "M"), 0.0005)
        }
    }

    /**
     * **The Compose side of the same fact.** Each of the four families the box hands to [SgrText]
     * must measure a bold `M` as the bold file does and as wide as the regular one, through the
     * resolver Compose actually draws with — a family that quietly resolved to the regular face is
     * the bug the owner photographed on 2026-09-05.
     */
    @Test
    fun theFourComposeFamiliesDrawFourFacesOfOneWidth() {
        val density = Density(context)
        val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
        val faces = terminalFaces(context)
        val widths = listOf(faces.regular, faces.bold, faces.italic, faces.boldItalic)
            .map { characterWidthMilliDp(measurer, density, it) }
        assertEquals("the four faces do not share a cell: $widths milli-dp", 1, widths.toSet().size)

        fun typefaceOf(family: FontFamily): Typeface =
            createFontFamilyResolver(context).resolve(family).value as Typeface
        assertTrue("bold family resolved to the regular typeface", typefaceOf(faces.bold) != typefaceOf(faces.regular))
        assertTrue("italic family resolved to the regular typeface", typefaceOf(faces.italic) != typefaceOf(faces.regular))
        assertTrue(typefaceOf(faces.bold).isBold)
        assertTrue(typefaceOf(faces.italic).isItalic)
        assertTrue(typefaceOf(faces.boldItalic).isBold && typefaceOf(faces.boldItalic).isItalic)
    }

    /**
     * And the negative half: the platform's own monospace must measure DIFFERENTLY.
     *
     * Without this, a test asserting "the advance is 0.600" would also pass if every font on the
     * device happened to measure 0.600 — it would be checking the number rather than the font. This
     * proves the measurement can tell the two apart at all.
     */
    @Test
    fun theBundledFontIsDistinguishableFromThePlatformMonospace() {
        val bundled = advanceEmOf(ResourcesCompat.getFont(context, R.font.jetbrainsmono_regular)!!, "M")
        val platform = advanceEmOf(Typeface.MONOSPACE, "M")

        assertTrue(
            "bundled ($bundled) and platform monospace ($platform) measure the same, so this " +
                "measurement cannot detect a fallback and proves nothing",
            bundled != platform,
        )
    }

    /**
     * A glyph the terminal actually needs. Box drawing is what makes a table look like a table, and it
     * is the first thing the owner would notice rendering as a substitute glyph from some other family.
     */
    @Test
    fun theBundledFontCoversBoxDrawingAndBlocks() {
        val typeface = ResourcesCompat.getFont(context, R.font.jetbrainsmono_regular)!!
        val paint = Paint().apply { this.typeface = typeface; textSize = 200f }

        for (ch in listOf("─", "│", "┌", "┐", "└", "┘", "█", "▀", "▄")) {
            assertTrue("no glyph for $ch in the bundled font", paint.hasGlyph(ch))
        }
    }

    // ---- REQ-0015: the six the primary does not have, and the chain that supplies them ----

    /**
     * The six codepoints Claude draws that the primary lacks, measured over 40 sessions of real output
     * against Menlo — `docs/qa/req-0015-glyph-measurement.md` — and re-checked against JetBrains Mono
     * 2.304's cmap with fontTools on 2026-09-05: it has none of the six either.
     */
    private val theSixThePrimaryLacks = listOf(
        "⏺" to "U+23FA, the bullet on every tool call, 96 occurrences",
        "⏵" to "U+23F5, the ⏵⏵ the owner photographed, 64",
        "⎿" to "U+23BF, the corner on every tool result, 31",
        "※" to "U+203B, recap lines, 23",
        "✅" to "U+2705, 4",
        "⏸" to "U+23F8, manual mode, 2",
    )

    /** JuliaMono's advance, and the reason it was chosen: 1200/2000 for every glyph it has. */
    private val juliaMonoAdvanceEm = 1200.0 / 2000.0

    private fun advanceEmOf(typeface: Typeface, text: String): Double {
        val size = 2048f
        val paint = Paint().apply {
            this.typeface = typeface
            textSize = size
            hinting = Paint.HINTING_OFF
            isLinearText = true
        }
        return (paint.measureText(text.repeat(100)) / 100f / size).toDouble()
    }

    /**
     * **The bug, stated as a test.** Every one of the six draws a glyph instead of a box.
     */
    @Test
    fun theChainCoversEverythingClaudeDrawsThatThePrimaryLacks() {
        val typeface = terminalTypeface(context)!!
        val paint = Paint().apply { this.typeface = typeface; textSize = 200f }

        for ((ch, what) in theSixThePrimaryLacks) {
            assertTrue("no glyph for $ch ($what) - this is the box the owner reported", paint.hasGlyph(ch))
        }
    }

    /**
     * **The negative control, and without it the test above proves nothing about our chain.**
     *
     * A `Typeface` loaded through `ResourcesCompat` is built with `createFromFamiliesWithDefault`, so
     * it already carries the platform's fallback chain. Only ⏵ was ever a box; the rest were coming
     * from NotoColorEmoji at 1.2451 em, the CJK faces at 1.0 em, and Roboto at 0.6064 em.
     *
     * So the control is the fact that actually justifies bundling: **the platform cannot produce a
     * one-cell glyph for any of the six.** Either it has nothing (⏵) or it has something the wrong
     * width. If a future Android shipped a monospaced ⏺, this fails and the bundling is worth
     * re-arguing.
     */
    @Test
    fun withoutOurFallbackNotOneOfTheSixIsOneCellWide() {
        val todaysPath = ResourcesCompat.getFont(context, R.font.jetbrainsmono_regular)!!
        val paint = Paint().apply { this.typeface = todaysPath; textSize = 200f }

        for ((ch, what) in theSixThePrimaryLacks) {
            if (!paint.hasGlyph(ch)) continue // ⏵: in none of the phone's 208 fonts.
            val advance = advanceEmOf(todaysPath, ch)
            assertTrue(
                "the platform now draws $ch ($what) at $advance em, one cell wide, so bundling a " +
                    "fallback for it is no longer justified by this measurement",
                kotlin.math.abs(advance - primaryAdvanceEm) > 0.002,
            )
        }
    }

    /**
     * **The invariant the whole design rests on: a fallback must not move a character the primary has.**
     *
     * The fallback is consulted only for codepoints the primary lacks, so this cannot happen — and
     * by construction is not evidence, which is what this measures.
     */
    @Test
    fun theChainDoesNotMoveThePrimarysOwnAdvance() {
        val chained = advanceEmOf(terminalTypeface(context)!!, "M")
        val alone = advanceEmOf(ResourcesCompat.getFont(context, R.font.jetbrainsmono_regular)!!, "M")

        assertEquals("JetBrains Mono's advance is 600/1000, and the chain must not change it", primaryAdvanceEm, chained, 0.0005)
        assertEquals(
            "the chain measures $chained em and the primary alone $alone em; the fallback has moved " +
                "a character the primary already draws",
            alone,
            chained,
            0.0000001,
        )
    }

    /** Box drawing is the primary's, and a chain that reordered would draw tables from the fallback. */
    @Test
    fun boxDrawingStillComesFromThePrimary() {
        val chained = advanceEmOf(terminalTypeface(context)!!, "─")
        assertEquals("box drawing no longer measures the primary's advance", primaryAdvanceEm, chained, 0.0005)
    }

    /**
     * **A symbol wider than a cell shifts a monospace row, which is worse than a box.**
     *
     * JuliaMono is monospaced at 0.6000 em, the same as JetBrains Mono, so each of the six must
     * measure exactly one cell — and in particular must not measure the platform's, where ⏺ is
     * 1.2451 em (2.07 cells, in colour) and ⎿ is 1.0 em.
     */
    @Test
    fun theSixDrawOneCellWideFromTheMonospacedFallback() {
        val typeface = terminalTypeface(context)!!

        for ((ch, what) in theSixThePrimaryLacks) {
            val advance = advanceEmOf(typeface, ch)
            assertEquals(
                "$ch ($what) measures $advance em; JuliaMono is $juliaMonoAdvanceEm and the platform's " +
                    "emoji font is 1.2451 - a glyph from the wrong family shifts the rest of the row",
                juliaMonoAdvanceEm,
                advance,
                0.002,
            )
            assertTrue(
                "$ch measures $advance em, wider than the primary's cell of $primaryAdvanceEm",
                advance <= primaryAdvanceEm + 0.0005,
            )
        }
    }

    /**
     * **The one that actually settles it: at the size the terminal draws, the six are the same
     * number of pixels as the primary's own characters.**
     *
     * Measured at [FitToPhone.TERMINAL_FONT_SIZE_SP], where the phone renders, with no tolerance.
     * With Menlo this relied on whole-pixel grid fitting hiding a 0.34% gap; with JetBrains Mono the
     * two fonts share an advance to begin with, so it holds at every size and density.
     */
    @Test
    fun atTheTerminalsOwnSizeTheSixAreExactlyOneCell() {
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            FitToPhone.TERMINAL_FONT_SIZE_SP.toFloat(),
            context.resources.displayMetrics,
        )
        val paint = Paint().apply { typeface = terminalTypeface(context)!!; textSize = px }
        val cell = paint.measureText("M".repeat(100))

        for ((ch, what) in theSixThePrimaryLacks) {
            assertEquals(
                "$ch ($what) advances ${paint.measureText(ch.repeat(100)) / 100} px against the " +
                    "primary's ${cell / 100} at ${px}px; the fallback and the primary no longer share a cell",
                cell,
                paint.measureText(ch.repeat(100)),
                0f,
            )
        }
    }

    /**
     * **The trade of REQ-0015 Decision 3, asserted rather than described.**
     *
     * Characters neither bundled font has still reach the platform and draw a real glyph, at
     * platform widths — 1.0 em for CJK, 1.2451 em for emoji. That is content beating grid, and it is
     * a choice; a test is what stops it being quietly reversed by someone tidying the builder.
     *
     * Measured while writing this: **the chain cannot be turned off through this API anyway.**
     * `setSystemFallback("")`, `setSystemFallback("sans-serif")` and omitting the call entirely all
     * produce the same result on Android 17 — 中 and 🚀 still render.
     */
    @Test
    fun charactersNeitherFontHasStillReachThePlatform() {
        val paint = Paint().apply { typeface = terminalTypeface(context)!!; textSize = 200f }

        for (ch in listOf("中", "🚀")) {
            assertTrue("$ch is a box; the platform chain is no longer behind the two bundled fonts", paint.hasGlyph(ch))
        }
    }

    /**
     * **The fit key is the primary's alone.**
     *
     * `character_width_milli_dp` is the width-fit cache key in `bridge/internal/resize`. It is
     * measured from `"X"` — a character the primary has — so the fallback is never consulted for it
     * and the chain cannot move the integer. (The primary itself changing, as it did on 2026-09-05,
     * may move it; the bridge then re-measures on the next Fit, which is the designed recovery.)
     *
     * **The same integer, not close to it.** A tolerance here would let the key drift by one and take
     * the owner's calibration with it, which is the defect this milestone has already paid for three
     * times.
     *
     * It calls the production function rather than repeating its arithmetic: a copied formula agrees
     * with the original until the day it matters.
     */
    @Test
    fun theFitMeasurementIsTheSameInteger() {
        val density = Density(context)
        val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)

        val withChain = characterWidthMilliDp(measurer, density, terminalFontFamily(context))
        val alone = characterWidthMilliDp(measurer, density, FontFamily(Font(R.font.jetbrainsmono_regular)))

        assertEquals(
            "the fit key moved from $alone to $withChain milli-dp; the bridge caches the owner's " +
                "calibration against this integer and a changed key silently discards it",
            alone,
            withChain,
        )
    }
}
