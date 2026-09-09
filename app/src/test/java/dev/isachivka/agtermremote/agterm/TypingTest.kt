package dev.isachivka.agtermremote.agterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The input bar's promises, as checks.
 *
 * Two of them are the whole reason this is a state machine rather than a boolean: the draft never
 * becomes terminal content, and *sent* is never allowed to look like *acknowledged*.
 */
class TypingTest {

    /**
     * **Typing has to be entered deliberately.**
     *
     * The owner is sending real commands to a real machine. An input that is open by default is one a
     * pocket can type into.
     */
    @Test
    fun `the bar starts closed`() {
        assertEquals(TypingState.Closed, Typing.close())
        assertTrue(!Typing.isOpen(TypingState.Closed))
        assertTrue(Typing.isOpen(Typing.open()))
    }

    /** Opening gives a clean composing state. The draft is the sessions' business — REQ-0046. */
    @Test
    fun `opening the bar is composing with nothing to report`() {
        assertEquals(TypingState.Composing(), Typing.open())
    }

    /**
     * **The gap between sent and acknowledged is a state, not silence.**
     *
     * An unacknowledged keystroke and a keystroke that produced no output look identical on screen,
     * and that ambiguity is how failures hide. Sent says which keystroke is outstanding.
     */
    @Test
    fun `a sent keystroke is outstanding and carries nothing`() {
        assertEquals(TypingState.Sent, Typing.sending())
    }

    /** A screen that moved is an acknowledgement the owner can see, and the bar returns to composing. */
    @Test
    fun `a screen that changed resolves the outstanding keystroke`() {
        val settled = Typing.onScreenSettled(Typing.sending(), changed = true)

        assertEquals(TypingState.Composing(), settled)
    }

    /**
     * **The honest end of the ambiguity, and the assertion that matters most here.**
     *
     * A screen that did not move must NOT be reported as a failure — the bridge accepted the
     * keystroke, and a password prompt, a running command or a key `vim` swallowed all produce
     * exactly this. It must also not be reported as success, and it must not be silence.
     */
    @Test
    fun `a screen that did not change is reported as no change, not as failure`() {
        val settled = Typing.onScreenSettled(Typing.sending(), changed = false)

        assertEquals(TypingState.NoChange, settled)
        assertTrue("a keystroke the bridge accepted must not read as failed", settled !is TypingState.Failed)
    }

    /** Not reaching the laptop is a different fact from reaching it and showing nothing. */
    @Test
    fun `failing to send is its own state`() {
        assertEquals(TypingState.Failed, Typing.failed())
        assertTrue(Typing.failed() !is TypingState.NoChange)
    }

    /**
     * **A poll must never disturb the owner while they are composing.** A notice they have not read
     * yet stays; a closed bar stays closed.
     */
    @Test
    fun `a poll landing while composing changes nothing`() {
        val composing = TypingState.Composing(TypingState.Notice.LineBreaks)

        assertEquals(composing, Typing.onScreenSettled(composing, changed = true))
        assertEquals(composing, Typing.onScreenSettled(composing, changed = false))
        assertEquals(TypingState.Closed, Typing.onScreenSettled(TypingState.Closed, changed = true))
    }

    /** Sending is outstanding until the screen answers, and then it is composing again. */
    @Test
    fun `a send resolves back to composing when the screen moves`() {
        val sent = Typing.sending()

        assertTrue(sent !is TypingState.Composing)
        assertEquals(TypingState.Composing(), Typing.onScreenSettled(sent, changed = true))
    }

    /** Editing only means something while composing; a keystroke in flight is not a text field. */
    @Test
    fun `editing does nothing to a state that is not composing`() {
        for (state in listOf(TypingState.Closed, Typing.sending(), TypingState.Failed)) {
            assertEquals(state, Typing.edited(state))
        }
        assertEquals(TypingState.Composing(), Typing.edited(TypingState.Composing()))
    }

    /**
     * **A picked file's path is APPENDED, and one space joins it.**
     *
     * The kind of one-liner that is wrong by a space for a month. `cat ` then a pick must read
     * `cat /tmp/...`, and so must `cat` then a pick. Whitespace the owner typed is theirs and is not
     * trimmed - only the join is decided here.
     */
    @Test
    fun `a path joins the draft with exactly one space`() {
        assertEquals("/tmp/x/a.txt", appendPath("", "/tmp/x/a.txt"))
        assertEquals("cat /tmp/x/a.txt", appendPath("cat", "/tmp/x/a.txt"))
        assertEquals("cat /tmp/x/a.txt", appendPath("cat ", "/tmp/x/a.txt"))
        assertEquals("diff a.txt /tmp/x/b.txt", appendPath("diff a.txt", "/tmp/x/b.txt"))
    }

    /**
     * **Several picked files land as several paths, one space between each - REQ-0048.**
     *
     * `AgtermSessions.sendFiles` appends each path as it lands, so a batch is [appendPath] applied
     * once per file to the draft the previous one left. The owner picks three photographs and wants
     * three paths in the box, in the order he picked them: `cat` then three picks reads `cat /a /b /c`,
     * and a trailing space he typed is used once rather than doubled.
     */
    @Test
    fun `several paths join the draft in order with one space each`() {
        assertEquals("/tmp/x/a.txt /tmp/x/b.txt", listOf("/tmp/x/a.txt", "/tmp/x/b.txt").fold("", ::appendPath))
        assertEquals("cat /a /b /c", listOf("/a", "/b", "/c").fold("cat", ::appendPath))
        assertEquals("cat /a /b", listOf("/a", "/b").fold("cat ", ::appendPath))
    }

    /**
     * **The draft is never terminal content.**
     *
     * The check is structural rather than a screenshot: no state in this machine carries the terminal
     * text, so nothing in it can be rendered into the box. If a field is ever added that holds screen
     * output, this fails and the reason is above it.
     */
    @Test
    fun `no typing state carries terminal content`() {
        // Java reflection rather than Kotlin's `sealedSubclasses`, and the reason is worth meeting
        // rather than working around.
        //
        // `sealedSubclasses` needs kotlin-reflect on the run-time classpath. That is one small,
        // well-behaved, entirely reputable dependency, added to a test, where it could not possibly
        // ship to the owner's phone. Every one of those clauses is true and the answer is still no.
        //
        // **The oldest mechanism in this project is `bridge/go.sum` not existing.** It holds "zero
        // third-party runtime dependencies" by failing loudly in the diff the moment one appears,
        // without anyone remembering to look. A rule like that survives on the cases where it was
        // inconvenient, not on the cases where it was easy — and "it is only for a test" is exactly
        // the shape of the first exception, after which the rule is a preference.
        //
        // The nested classes ARE the sealed subclasses, so this enumerates the same set with the
        // reflection already on the classpath. The cost of holding the line here was four words.
        val fields = TypingState::class.java.declaredClasses.flatMap { sub ->
            sub.declaredFields
                // An enum's constants are CASES, not payloads - they are the same three words every
                // time, chosen from a closed set, and they cannot carry anything the owner typed.
                // Its synthetic $VALUES/$ENTRIES are the compiler's. What a case must never grow is a
                // field of its own, which is asserted separately below rather than filtered away.
                .filterNot { it.isEnumConstant || it.isSynthetic }
                .map { "${sub.simpleName}.${it.name}" }
        }.filterNot { it.contains("INSTANCE") || it.contains("stable") }

        assertEquals(
            "a typing state gained a field. Only Composing may hold anything: the draft the owner is " +
                "looking at, and which NOTICE sits above it. Sent, NoChange and Failed used to carry " +
                "what was typed, which printed passwords into the status line",
            listOf("Composing.notice"),
            fields.sorted(),
        )

        // **`notice` is the one exception, and this is its edge.** REQ-0017 Decision 3, granted by
        // the owner on 2026-08-09: a send that did not happen leaves the text where it was, in
        // Composing and nowhere else - never in a notice, never in a log, never on disk, and still
        // cleared on success. The two are separate fields precisely so a message cannot be assembled
        // out of what they typed, which is the echo Sent exists to prevent wearing a different hat.
        //
        // So the notice must stay a CASE with no payload. The moment it can hold a string it can hold
        // their draft, and this whole test is decoration.
        assertTrue(
            "Notice stopped being an enum; it can now carry text, and the first thing it will carry " +
                "is what the owner typed",
            TypingState.Notice::class.java.isEnum,
        )
        assertEquals(
            "a Notice case gained a field of its own",
            emptyList<String>(),
            TypingState.Notice::class.java.declaredFields
                .filterNot { it.isEnumConstant || it.isSynthetic }
                .map { it.name },
        )
        // The control: the walk really found the subclasses. An empty list satisfies nothing above,
        // which is how a reflection test rots into a comment.
        assertTrue("the reflection walk found no states at all", fields.isNotEmpty())
    }

    /**
     * **A refused send is composing again, with a notice.** REQ-0017. The draft itself is put back by
     * `AgtermSessions`, which still holds the text — see `AgtermSessionsTest`.
     */
    @Test
    fun `a refused send returns to composing with a notice`() {
        val state = Typing.refused("line one\nline two")

        assertEquals(TypingState.Composing(TypingState.Notice.LineBreaks), state)
    }

    /**
     * And the notice is chosen from **the text itself**, never from the bridge's message.
     *
     * The bridge answers with byte offsets and the names of every key in its allowlist. That sentence
     * is for the log. Parsing it to decide what to show would make our copy depend on the wording of
     * an error string, which is the kind of coupling that breaks silently in a language nobody reads.
     */
    @Test
    fun `the notice names line breaks only when there are line breaks`() {
        assertEquals(
            TypingState.Notice.LineBreaks,
            (Typing.refused("one\ntwo") as TypingState.Composing).notice,
        )
        assertEquals(
            TypingState.Notice.NotTypable,
            (Typing.refused("a tab\tin it") as TypingState.Composing).notice,
        )
    }

    /** A notice describes one send; the next send starts clean. */
    @Test
    fun `a notice does not survive the next send`() {
        val refused = Typing.refused("one\ntwo")
        assertTrue("precondition: there is a notice to survive", (refused as TypingState.Composing).notice != null)

        assertEquals(TypingState.Sent, Typing.sending())
        assertEquals(TypingState.Composing(), Typing.onScreenSettled(Typing.sending(), changed = true))
    }

    /**
     * **The picker path loses drafts too, and it did so before any of this.** REQ-0017.
     *
     * `sendFile` reads the draft, sends the file, and on failure sets `Failed` — so a file the laptop
     * refused cost the owner whatever they were composing, for a send that never happened. The same
     * defect as the one they reported, reached by a different button.
     *
     * Its own notice, because *"will not type that"* would be a sentence about typing on a path where
     * nothing was typed. A notice that is nearly true is how copy stops being trusted.
     */
    @Test
    fun `a refused file returns to composing with a notice about the file`() {
        assertEquals(TypingState.Composing(TypingState.Notice.FileRefused), Typing.refusedFile())
    }

    /** Editing the text puts the notice away: it described a send, and this is a different one. */
    @Test
    fun `editing clears the notice`() {
        val refused = Typing.refused("one\ntwo")

        assertEquals(TypingState.Composing(), Typing.edited(refused))
    }
}
