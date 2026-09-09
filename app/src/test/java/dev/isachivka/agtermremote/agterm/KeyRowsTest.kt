package dev.isachivka.agtermremote.agterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key bar's cells, and the guard over what a cell may be.
 *
 * The bar used to be `List<List<Pair<String?, Int>>>`, where the string is **a name the bridge's key
 * allowlist must accept**. Adding a cell that types a command rather than pressing a key was the
 * moment that shape became dangerous: a macro given a name like `"claude"` sits in the same position
 * as a real key, compiles, renders, and is refused four layers away by `keys.Key` — surfacing as a
 * button that silently does nothing.
 */
class KeyRowsTest {

    /**
     * **The bridge's closed set, as the phone believes it.**
     *
     * Nineteen names, copied from `bridge/internal/keys/keys.go`. This is a second copy and no type
     * can join a Kotlin list to a Go map, so the honest version of "keep them in sync" is a test that
     * fails when they diverge — the same arrangement as `MaxLabelRunes` and the bridge's 64-rune cap.
     *
     * If this list is wrong, the failure it produces is the one worth having: a key on the bar that
     * the laptop refuses.
     */
    private val bridgeKeys = setOf(
        "enter", "tab", "backspace", "escape",
        "up", "down", "left", "right",
        "home", "end", "pageup", "pagedown", "delete",
        "interrupt", "suspend", "clear", "killline", "linestart", "lineend",
    )

    /**
     * **Every Key on the bar is a key the bridge will accept.**
     *
     * This is the assertion that would have caught a macro smuggled in as a key, and it is why the
     * cell type is sealed rather than a pair.
     */
    @Test
    fun `every key on the bar is one the bridge allows`() {
        val keys = KEY_ROWS.flatten().filterIsInstance<KeyCell.Key>()

        assertTrue("the guard needs something to guard", keys.isNotEmpty())
        keys.forEach { key ->
            assertTrue(
                "'${key.name}' is on the key bar but not in the bridge's allowlist - it would be " +
                    "refused by keys.Key and read as a button that does nothing",
                key.name in bridgeKeys,
            )
        }
    }

    /**
     * **The macro is NOT a key**, which is the other half of the same rule. If it were, the test above
     * would demand `claude_yolo` be in the bridge's key allowlist — and it must never be there.
     */
    @Test
    fun `the Claude cell is a macro and its command is not a key name`() {
        val macros = KEY_ROWS.flatten().filterIsInstance<KeyCell.Macro>()

        assertEquals("exactly one macro cell", 1, macros.size)
        assertEquals(CLAUDE_COMMAND, macros.single().command)
        assertTrue(
            "the command must never be in the key allowlist; it is text, not a key",
            CLAUDE_COMMAND !in bridgeKeys,
        )
    }

    /**
     * **One row on the bar, one row behind the fold, eight cells each.**
     *
     * Seven keys plus the fold's own cell make the visible eight; the fold is drawn by `TypingBar`
     * rather than living in the list, because it presses nothing and is not a [KeyCell].
     *
     * The count is the feature. Three rows of five became one row of eight, which is what gives the
     * terminal back 104dp — 5.2 of its own lines. A row quietly growing back here is that measurement
     * quietly becoming false.
     */
    @Test
    fun `one row on the bar and two behind the fold`() {
        assertEquals("the bar is five keys, and the fold's cell makes six", 5, KEYS_ON_THE_BAR.size)
        assertEquals("the fold holds two rows", 2, KEYS_UNDER_THE_FOLD.size)
        KEYS_UNDER_THE_FOLD.forEach { row ->
            assertEquals("a folded row is five cells - 80dp each, the widest keys we draw", 5, row.size)
        }
        assertEquals("KEY_ROWS is the union and nothing else", 3, KEY_ROWS.size)
    }

    /**
     * **Six cells is the whole point of the fold, so the count is asserted rather than left to
     * drift.**
     *
     * The visible row is [KEYS_ON_THE_BAR] plus one cell for the fold, drawn by `TypingBar`. At the
     * owner's 424dp of usable bar width and a 6dp gap, six cells is 65.7dp each; seven would be 55.4
     * and eight 47.75, which is what shipped and what a thumb was never verified against.
     *
     * A key added back to the front row is therefore not a small change — it is that number moving.
     */
    @Test
    fun `the visible row is six cells wide`() {
        val visibleCells = KEYS_ON_THE_BAR.size + 1
        val usableDp = 424.0
        val gapDp = 6.0

        val cellDp = (usableDp - (visibleCells - 1) * gapDp) / visibleCells

        assertEquals(6, visibleCells)
        assertEquals("a key must stay above Material's 48dp minimum", 65.67, cellDp, 0.01)
    }

    /**
     * **Every key that was on the bar before the fold is still on it. This is the test that matters.**
     *
     * The owner's instruction, given while the fold was being written: *"HIDE, do not delete. Every
     * key the owner named goes under the '…', not out of KEY_ROWS and not out of the bridge
     * allowlist. Ctrl-D was deleted from both once; this must not repeat."*
     *
     * `Ctrl-D` is the precedent. It came off this bar and out of `internal/keys` in one commit on
     * 2026-07-31 because the owner said they did not know what it was for — and undoing that is a
     * release, while undoing a fold is a tap. The fourteen names below are what the bar carried on the
     * commit before this one, written out rather than derived, so that a key dropped from
     * [KEYS_ON_THE_BAR] and forgotten in [KEYS_UNDER_THE_FOLD] fails here instead of shipping.
     */
    @Test
    fun `the fold hid keys and deleted none`() {
        val before = setOf(
            "escape", "left", "up", "down", "right",
            ENTER_KEY, "tab", "backspace", "interrupt", "lineend",
            "pageup", "pagedown", "linestart", "killline",
        )

        val now = KEY_ROWS.flatten().filterIsInstance<KeyCell.Key>().map { it.name }.toSet()

        assertEquals("a key left the bar rather than going under the fold", before, now)
    }

    /**
     * **Nothing the owner named as never-pressed is still taking a cell on the bar**, and nothing they
     * did not name was folded away without a reason written beside it.
     *
     * Their report, 2026-08-21, after two weeks of daily use: not once had they pressed Ctrl-A,
     * Ctrl-E, Backspace or the arrows. `killline` is the eighth and is a judgement
     * call, not their word — it is asserted here so that the judgement is visible in a test rather
     * than buried in a layout list.
     */
    @Test
    fun `the folded keys are the ones the owner never pressed`() {
        val folded = KEYS_UNDER_THE_FOLD.flatten().filterIsInstance<KeyCell.Key>().map { it.name }

        assertEquals(
            setOf(
                // Named by the owner as never pressed.
                "left", "up", "down", "right", "backspace", "linestart", "lineend",
                // Ours: the fourth member of the line-editing family whose other three they named.
                "killline",
                // **Not folded for being unused - folded because they got a gesture.** an
                // overpull of the terminal sends these two now. The buttons stay because the gesture
                // has never been tried by a thumb, and withdrawing the working control on the same
                // day as offering an untested one leaves the owner with neither.
                "pageup", "pagedown",
            ),
            folded.toSet(),
        )
    }

    /** No cell appears twice — the copy-paste mistake that looks like a rendering bug. */
    @Test
    fun `no key is on the bar twice`() {
        val names = KEY_ROWS.flatten().filterIsInstance<KeyCell.Key>().map { it.name }

        assertEquals(names.size, names.toSet().size)
    }
}
