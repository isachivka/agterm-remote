package dev.isachivka.agtermremote.agterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The recalibration reporting itself — REQ-0033.
 *
 * The owner reached for the long press on 2026-08-25 with a stuck fit and could not tell it had done
 * anything: *"анимацию надо сделать чтоб я понял когда сработало, ну или там нотификацию, хоть
 * что-то."*
 *
 * **The trap this is written around:** a recalibration that lands on the same column count changes
 * nothing on screen. So feedback keyed on the terminal reflowing would report success only in the
 * cases he could already see, and stay silent in exactly the case he was in.
 */
class RecalibrateNoticeTest {

    @Test
    fun `a finished recalibration is reported with the count the laptop measured`() {
        val notice = noticeFor(LinkNote.None, TypingState.Closed, recalibrated = 45)

        assertEquals(Notice.Measured(45), notice)
    }

    /**
     * **Zero is a report, not an absence.** A laptop that answered zero columns has still answered,
     * and swallowing it would put the owner back where he started — pressing something that says
     * nothing. Null is the only "nothing happened".
     */
    @Test
    fun `a count of zero is still a report`() {
        assertEquals(Notice.Measured(0), noticeFor(LinkNote.None, TypingState.Closed, recalibrated = 0))
        assertNull(noticeFor(LinkNote.None, TypingState.Closed, recalibrated = null))
    }

    /**
     * A link in doubt still wins. A measurement reported over a socket that is gone is a number about
     * a window nobody can currently see, and the connection is the precondition for it meaning
     * anything — the same argument that already puts the connection above a keystroke report.
     */
    @Test
    fun `a connection in doubt beats the measurement`() {
        assertEquals(
            Notice.Reconnecting,
            noticeFor(LinkNote.Reconnecting, TypingState.Closed, recalibrated = 45),
        )
    }

    /**
     * And it beats a keystroke report, because it is the newer event and the one he deliberately
     * asked for — a typing note describes something from before he reached for the escape hatch.
     */
    @Test
    fun `the measurement beats a stale typing report`() {
        assertEquals(
            Notice.Measured(45),
            noticeFor(LinkNote.None, TypingState.Sent, recalibrated = 45),
        )
    }

    /** It takes itself away like the others; only Reconnecting stays until it resolves. */
    @Test
    fun `the measurement note expires`() {
        assertEquals(true, expires(Notice.Measured(45)))
    }

    /**
     * Two recalibrations landing on the same count are the same note, and a different count is a
     * different one — so the surface actually changes when the answer does.
     */
    @Test
    fun `the note is the count`() {
        assertEquals(Notice.Measured(45), Notice.Measured(45))
        assertEquals(false, Notice.Measured(45) == Notice.Measured(59))
    }
}
