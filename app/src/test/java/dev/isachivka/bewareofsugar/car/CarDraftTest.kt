package dev.isachivka.bewareofsugar.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The draft as a value: what the microphone and the keyboard put into it, and what Enter takes out.
 *
 * Deterministic and free of Android, because every decision about what gets typed into the laptop is
 * made here, and a decision made in a Screen callback is one nobody can test without a car.
 */
class CarDraftTest {

    @Test
    fun `enter on an empty draft is the key alone`() {
        val plan = CarDrafting.enter(CarDraft())
        assertNull(plan.text)
        assertFalse(plan.paste)
    }

    @Test
    fun `enter with text sends the text, not as a paste`() {
        val plan = CarDrafting.enter(CarDraft(text = "ls -la"))
        assertEquals("ls -la", plan.text)
        assertFalse(plan.paste)
    }

    /** The phone's rule, REQ-0017: a line break is a paste, never a Return the owner did not press. */
    @Test
    fun `a draft with a line break goes as a paste`() {
        val plan = CarDrafting.enter(CarDraft(text = "one\ntwo"))
        assertEquals("one\ntwo", plan.text)
        assertTrue(plan.paste)
    }

    @Test
    fun `what was heard replaces the draft, trimmed, and listening ends`() {
        val d = CarDrafting.heard(CarDraft(text = "old", listening = true), "  git status ")
        assertEquals(CarDraft(text = "git status", listening = false), d)
    }

    @Test
    fun `a partial result shows while still listening`() {
        val d = CarDrafting.partial(CarDrafting.startListening(CarDraft()), "git st")
        assertEquals("git st", d.text)
        assertTrue(d.listening)
    }

    /** A recogniser that gives up mid-sentence leaves what it had, rather than blanking the band. */
    @Test
    fun `a failed recognition keeps the partial text`() {
        val d = CarDrafting.voiceFailed(CarDraft(text = "git st", listening = true))
        assertEquals(CarDraft(text = "git st", listening = false), d)
    }

    @Test
    fun `the keyboard replaces the draft`() {
        assertEquals(CarDraft(text = "pwd"), CarDrafting.typed(CarDraft(text = "ls"), "pwd"))
    }

    @Test
    fun `a sent draft is empty`() {
        assertEquals(CarDraft(), CarDrafting.sent(CarDraft(text = "ls", listening = true)))
    }

    /** REQ-0017 Decision 3, the phone's narrow exception: a send that did not happen leaves the text. */
    @Test
    fun `a refused send puts the text back`() {
        assertEquals(CarDraft(text = "rm -rf x"), CarDrafting.refused(CarDraft(), "rm -rf x"))
    }
}
