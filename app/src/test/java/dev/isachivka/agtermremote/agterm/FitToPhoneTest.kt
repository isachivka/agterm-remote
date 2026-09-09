package dev.isachivka.agtermremote.agterm

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is left after the arithmetic was deleted.
 *
 * ### The test that used to be here asserted a column count, and that is the point
 *
 * `columnsThatFit` computed one from a screen width, a padding constant and `CHARACTER_WIDTH_DP =
 * 10.84` — a figure from Menlo's advance ratio never checked against the owner's phone. The bridge
 * cached against its result, so **every layout change we shipped silently discarded their
 * calibration**, and three attempts at this feature failed on it.
 *
 * The phone now sends two MEASUREMENTS and the laptop answers with the count its terminal really
 * rendered. There is no formula here to test, which is why this file is short: the interesting
 * assertions moved to `internal/resize`, where the number is measured rather than derived, and the
 * agreement between the code and the owner's own hand figures is checked against a real agterm.
 *
 * What remains is the record-keeping contract: the margin the phone reports must be the margin the box
 * actually draws, or the bridge stores the wrong reason beside a fit.
 */
class FitToPhoneTest {

    /**
     * **The arithmetic must stay deleted.** A formula that still compiles is a formula someone will
     * use, and finding 5e is a design that survived being replaced because nobody grepped for it.
     *
     * This reads the source rather than the API, because a re-added helper would compile and this
     * would otherwise pass by knowing nothing about it.
     */
    @Test
    fun `no column arithmetic has crept back into the phone`() {
        // **Code lines only.** The doc comment names what was deleted and why, deliberately - that
        // prose is how the next reader learns not to re-add it, so a guard that banned the words
        // would forbid the explanation along with the thing.
        val source = sourceOf("FitToPhone.kt")
            .lines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") || it.trimStart().startsWith("//") }
            .joinToString(separator = "\n")

        for (gone in listOf("CHARACTER_WIDTH_DP", "columnsThatFit", "fun request", "10.84")) {
            assertTrue(
                "$gone is back in FitToPhone. The phone measures and the laptop answers; it does not " +
                    "compute a column count. See finding 5e.",
                !source.contains(gone),
            )
        }
    }

    /**
     * The margin is no longer arithmetic — it is reported to the bridge so a human can see why a cache
     * key changed. It must match the box that draws, or that record names the wrong cause.
     */
    @Test
    fun `the margin reported to the bridge matches the box that draws`() {
        val source = sourceOf("TerminalBox.kt")

        assertTrue(
            "TerminalBox's horizontal padding is not what the bridge is told the fit was measured under",
            source.contains("HORIZONTAL_PADDING_DP = ${FitToPhone.TERMINAL_HORIZONTAL_PADDING_DP}"),
        )
        assertTrue(
            "TerminalBox no longer draws at ${FitToPhone.TERMINAL_FONT_SIZE_SP}sp",
            source.contains("FONT_SIZE_SP = ${FitToPhone.TERMINAL_FONT_SIZE_SP}"),
        )
        assertTrue(
            "the constants this reads for have been renamed, so it is guarding nothing",
            source.contains("HORIZONTAL_PADDING_DP") && source.contains("VERTICAL_PADDING_DP"),
        )
    }

    private fun sourceOf(name: String): String = listOf(
        java.io.File("src/main/java/dev/isachivka/agtermremote/agterm/$name"),
        java.io.File("app/src/main/java/dev/isachivka/agtermremote/agterm/$name"),
    ).first { it.isFile }.readText()
}
