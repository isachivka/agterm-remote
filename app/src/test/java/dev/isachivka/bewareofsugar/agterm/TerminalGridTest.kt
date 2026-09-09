package dev.isachivka.bewareofsugar.agterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid property, which is the one the owner asked for by name.
 *
 * *"Tables and other elements must still work"* is not a statement about fonts, it is a statement
 * about **columns landing in the same place on every row**. That is held by two things: a single
 * monospace layout pass with no wrapping — which is [TerminalBox]'s doing and is argued there — and
 * tabs being expanded to fixed stops before the text renderer ever sees them, which is here.
 */
class TerminalGridTest {

    /**
     * **The planted positive.** A tab-aligned table, of the shape a terminal program actually emits.
     *
     * If tabs reached the renderer, this would lay out against whatever stops that renderer chose and
     * the columns would drift — with no error, no exception, and every individual line still looking
     * correct. So the test asserts the column *positions*, not merely that something changed.
     */
    @Test
    fun `a tab-aligned table keeps its columns in the same place on every row`() {
        val table = "NAME\tSTATUS\tPORT\nagterm\tup\t8443\nbuild\tdown\t9000"

        val expanded = expandTabs(table)

        val columns = expanded.lines().map { line ->
            listOf(0, line.indexOf("STATUS").takeIf { it >= 0 } ?: -1)
        }
        // The real assertion: every row's second and third fields start at one x, across all rows.
        val starts = expanded.lines().map { line -> Regex("\\S+").findAll(line).map { it.range.first }.toList() }
        assertEquals("three fields per row", listOf(3, 3, 3), starts.map { it.size })
        assertEquals("second column must align", 1, starts.map { it[1] }.distinct().size)
        assertEquals("third column must align", 1, starts.map { it[2] }.distinct().size)
        assertTrue("no tab may survive into the renderer", !expanded.contains('\t'))
        assertTrue(columns.isNotEmpty())
    }

    @Test
    fun `a tab advances to the next eight-column stop, not by a fixed width`() {
        assertEquals("a       b", expandTabs("a\tb"))
        assertEquals("abcdefg z", expandTabs("abcdefg\tz"))
        // Exactly on a stop still advances a full one - a zero-width tab would merge two fields.
        assertEquals("abcdefgh        x", expandTabs("abcdefgh\tx"))
    }

    /** Stops are measured from the start of each row, not from the start of the text. */
    @Test
    fun `stops restart on every line`() {
        assertEquals("ab      c\nd       e", expandTabs("ab\tc\nd\te"))
    }

    /**
     * Text with no tabs is returned unchanged, and identically.
     *
     * Not an optimisation being asserted for its own sake: this is the overwhelmingly common case —
     * terminal output is mostly space-aligned already — and rebuilding every screen at 0.5 Hz to
     * change nothing would be work done on the owner's battery for no result.
     */
    @Test
    fun `text without tabs is passed straight through`() {
        val plain = "no tabs here\njust spaces   and words"
        assertSame(plain, expandTabs(plain))
    }

    /**
     * **A field wider than a stop pushes its row to the NEXT stop, and that is correct.**
     *
     * Found by this test's first version, whose table had a twelve-character name in one row: its
     * columns landed at 16 and 24 while the other rows sat at 8 and 16, and the assertion failed. The
     * expansion was right and the test data was wrong — a real terminal does exactly this, because it
     * is what tabs mean.
     *
     * Recorded rather than quietly corrected, because the tempting "fix" is to pad to the widest field
     * instead of to the stop. That would align this table and would no longer be what the owner's
     * terminal shows — the app would be re-formatting their output, which is the line this whole box
     * exists not to cross.
     */
    @Test
    fun `a field wider than a stop pushes to the next one, as a terminal does`() {
        val wide = expandTabs("bridge-thing\tdown")

        assertEquals("bridge-thing    down", wide)
    }

    @Test
    fun `the stop is the terminal convention rather than an invented number`() {
        assertEquals(8, TAB_STOP)
    }
}
