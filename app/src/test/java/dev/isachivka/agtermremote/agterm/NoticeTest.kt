package dev.isachivka.agtermremote.agterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One surface, two sources, and the precedence between them.
 *
 * The owner asked for the typing report to move onto the floating card, and it is admitted by the rule
 * that already governs that surface: it carries what the app OBSERVED. What is still forbidden is
 * anything about what an agent is doing — and there is no way to say that here, which is the point of
 * the type being closed.
 */
class NoticeTest {

    /**
     * **A connection note beats a typing note, always.**
     *
     * If the link is being waited out, a report about a keystroke is stale at best — and at worst it
     * says *sent* about a socket that is gone. Stated as a function so it is a rule that can be read,
     * rather than whichever composable happens to draw first.
     */
    @Test
    fun `the connection wins over a keystroke report`() {
        for (typing in listOf(TypingState.Sent, TypingState.NoChange, TypingState.Failed)) {
            assertEquals(Notice.Reconnecting, noticeFor(LinkNote.Reconnecting, typing))
            assertEquals(Notice.Reconnected, noticeFor(LinkNote.Reconnected, typing))
        }
    }

    @Test
    fun `with the link quiet the keystroke report is shown`() {
        assertEquals(Notice.Sent, noticeFor(LinkNote.None, TypingState.Sent))
        assertEquals(Notice.NoChange, noticeFor(LinkNote.None, TypingState.NoChange))
        assertEquals(Notice.Failed, noticeFor(LinkNote.None, TypingState.Failed))
    }

    /**
     * **A failure the owner caused outranks a measurement that succeeded — REQ-0035.**
     *
     * The order used to be the other way and it did not matter, because a recalibration and a mutation
     * could not be outstanding on the same screen: mutations belonged to the list. The pane toggle put
     * them one tap apart — it sits beside the fit control in the same header — so which wins is now a
     * thing the owner can see rather than a thing nobody could reach.
     *
     * A measurement is a report that something he asked for HAPPENED. A mutation note is a report that
     * something he asked for did NOT. Reversing these would show him `45 columns` while the pane he
     * just pressed for silently did not open.
     */
    @Test
    fun `a failed pane beats a measurement that landed`() {
        assertEquals(
            Notice.PaneFailed,
            noticeFor(LinkNote.None, TypingState.Closed, MutationNote.PaneFailed, recalibrated = 45),
        )
    }

    /** With nothing failed, the measurement still shows — the reorder must not have hidden it. */
    @Test
    fun `a measurement still shows when nothing failed`() {
        assertEquals(
            Notice.Measured(45),
            noticeFor(LinkNote.None, TypingState.Closed, MutationNote.None, recalibrated = 45),
        )
    }

    /**
     * And a failed pane beats an outstanding keystroke, which is the collision the precedence comment
     * in `Notice.kt` said could not happen and then predicted would.
     */
    @Test
    fun `a failed pane beats a keystroke report on the same screen`() {
        assertEquals(
            Notice.PaneFailed,
            noticeFor(LinkNote.None, TypingState.Sent, MutationNote.PaneFailed),
        )
    }

    /** The link still beats it, like everything else below the link. */
    @Test
    fun `the connection wins over a failed pane too`() {
        assertEquals(
            Notice.Reconnecting,
            noticeFor(LinkNote.Reconnecting, TypingState.Closed, MutationNote.PaneFailed),
        )
    }

    /** Composing and closed are not reports about anything, so the surface stays empty. */
    @Test
    fun `nothing to report shows nothing`() {
        assertNull(noticeFor(LinkNote.None, TypingState.Closed))
        assertNull(noticeFor(LinkNote.None, TypingState.Composing()))
        assertNull(noticeFor(LinkNote.None, TypingState.Composing(TypingState.Notice.LineBreaks)))
    }

    /**
     * **Only "reconnecting" persists**, because it is the only one describing something still
     * happening. A card that vanished mid-retry would leave the owner watching a frozen screen with no
     * account of it — which is the state this whole surface was added to end.
     */
    @Test
    fun `everything except an ongoing reconnect takes itself away`() {
        assertFalse(expires(Notice.Reconnecting))
        for (notice in listOf(Notice.Reconnected, Notice.Sent, Notice.NoChange, Notice.Failed)) {
            assertTrue("$notice should not sit there for ever", expires(notice))
        }
    }

    /**
     * Dismissing a report puts the owner back where they were before they sent, with the bar still
     * open. A state that is not a report is untouched.
     */
    @Test
    fun `dismissing a report returns to composing and touches nothing else`() {
        for (state in listOf(TypingState.Sent, TypingState.NoChange, TypingState.Failed)) {
            assertEquals(TypingState.Composing(), Typing.dismissReport(state))
        }
        val noticed = TypingState.Composing(TypingState.Notice.NotTypable)
        assertEquals(noticed, Typing.dismissReport(noticed))
        assertEquals(TypingState.Closed, Typing.dismissReport(TypingState.Closed))
    }

    /**
     * **No note carries what was typed**, and this is the assertion that survives the move. An earlier
     * version of the status line named the outstanding keystroke, which printed the owner's typing back
     * at them — including a password in the one case the terminal had deliberately hidden it.
     *
     * `Notice` was a closed set of objects with no fields, so there was nowhere for a payload to go.
     * This asserted that rather than trusting it, "because there is nowhere to put it is exactly the
     * kind of property a later `data class` quietly removes".
     *
     * ### That later data class arrived — REQ-0033 — and this guard would have missed it
     *
     * [Notice.Measured] carries a column count. The old version of this test enumerated five notices by
     * hand, so a sixth simply went unchecked: **the guard would have kept passing while no longer
     * covering the thing it was written for**, which is worse than failing.
     *
     * ### So it is narrowed to the rule rather than deleted
     *
     * The rule was never "no fields". It is **no owner content on this surface** — the defect was a
     * note that named the outstanding keystroke and printed the owner's own typing back at them, a
     * password among it.
     *
     * A `String` is the shape that carries typing. An `Int` measured by the laptop about its own
     * window is not, and it is already on screen a few dp above this surface. So: any field is
     * refused unless it is a primitive number, which cannot hold text however it is assigned.
     *
     * **And the list is now every notice**, checked against the exhaustive `when` in `NoticeCard`:
     * that one has no `else`, so a new notice cannot ship without a branch there, and the reviewer
     * adding it meets this list on the way past. Reflection over `sealedSubclasses` would be tidier
     * and needs `kotlin-reflect`, which is not on this test's classpath.
     */
    /**
     * **Every notice, and the list is HAND-WRITTEN AND LOAD-BEARING.** Read the next paragraph before
     * trusting it.
     *
     * Neither runtime enumeration is available here, checked rather than assumed:
     *
     *  - `Notice::class.sealedSubclasses` needs **kotlin-reflect**, which is not a dependency of this
     *    module;
     *  - `Notice::class.java.permittedSubclasses` needs the `PermittedSubclasses` attribute, which
     *    Kotlin emits only when targeting **JVM 17+**. `app/build.gradle.kts` says in as many words:
     *    *"The build runs on JDK 21 and compiles to JVM 11 bytecode."*
     *
     * So the set cannot be derived and must be transcribed. **[mustBeListed] is what stops that being
     * a hope**: an exhaustive `when` with no `else`, so a tenth notice does not compile until somebody
     * stands in this file. What it cannot force is that they also add it to the list below — but they
     * cannot get here without being sent here, which is the difference between a guard and a wish.
     *
     * The assertion this replaced was worse than nothing: it checked that nine literals were nine
     * distinct classes. A tenth notice missing from both lists would have passed it, which is the exact
     * failure the payload guard was being narrowed to fix, wearing a different hat.
     */
    private val everyNotice = listOf(
        Notice.Reconnecting, Notice.Reconnected, Notice.Sent, Notice.NoChange, Notice.Failed,
        Notice.CreateFailed, Notice.RenameFailed, Notice.DeleteFailed, Notice.PaneFailed,
        Notice.FitRefused, Notice.NeedsFit,
        Notice.Measured(45),
    )

    /**
     * **The compiler's half of the guard above.** No `else`, used as an expression, so exhaustiveness
     * is enforced: adding a notice fails the build HERE, in the file that owns the list it must join.
     *
     * It asserts nothing at runtime and is never called. That is not dead code — it is the check,
     * performed at compile time.
     */
    @Suppress("unused")
    private fun Notice.mustBeListed(): Unit = when (this) {
        Notice.Reconnecting -> Unit
        Notice.Reconnected -> Unit
        Notice.Sent -> Unit
        Notice.NoChange -> Unit
        Notice.Failed -> Unit
        Notice.CreateFailed -> Unit
        Notice.RenameFailed -> Unit
        Notice.DeleteFailed -> Unit
        Notice.PaneFailed -> Unit
        Notice.FitRefused -> Unit
        Notice.NeedsFit -> Unit
        is Notice.Measured -> Unit
    }

    @Test
    fun `no notice can carry the owner's own words`() {
        val allowed = setOf(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)

        for (notice in everyNotice) {
            // Instance fields only. INSTANCE and the Compose compiler's stable marker are static and
            // belong to the object rather than to anything it carries.
            val fields = notice.javaClass.declaredFields
                .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            val carriers = fields.filterNot { it.type in allowed }
            // Built before the assertion rather than inside it. A string template holding a lambda
            // holding another string is legal Kotlin and unreadable, and it is what broke this file.
            val described = carriers.joinToString { field -> field.name + ": " + field.type.simpleName }

            assertTrue(
                notice.javaClass.simpleName + " carries " + described +
                    " - a note on this surface may hold a number the laptop measured and nothing " +
                    "that could be the owner's own typing",
                carriers.isEmpty(),
            )
        }
    }

    /** No duplicate transcribed into the list, which would hide a missing one behind a right count. */
    @Test
    fun `the transcribed list has no repeats`() {
        assertEquals(everyNotice.size, everyNotice.map { it.javaClass }.toSet().size)
    }
}
