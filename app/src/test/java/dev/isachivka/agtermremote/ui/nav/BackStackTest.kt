package dev.isachivka.agtermremote.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Back, as a unit test.
 *
 * The reason the back stack is a plain value with no Android in it is sitting right here: these are
 * the cases that actually go wrong, and every one of them is answered in milliseconds on the JVM
 * instead of thirty seconds on an emulator. What is left for the device is that the composables are
 * wired to it, which is one test rather than eight.
 */
class BackStackTest {

    @Test
    fun `the app opens on the terminal`() {
        assertEquals(Screen.Agterm, BackStack.Initial.current)
        assertFalse("back at the root must exit the app, not be swallowed", BackStack.Initial.canPop)
    }

    @Test
    fun `going somewhere and coming back returns you where you were`() {
        val stack = BackStack.Initial.push(Screen.Settings)

        assertEquals(Screen.Settings, stack.current)
        assertTrue(stack.canPop)
        assertEquals(Screen.Agterm, stack.pop().current)
    }

    @Test
    fun `tapping the same destination twice costs one press of back, not two`() {
        val once = BackStack.Initial.push(Screen.Settings)
        val twice = once.push(Screen.Settings)

        assertEquals(once, twice)
        assertEquals(Screen.Agterm, twice.pop().current)
    }

    /**
     * The one that would have been a real bug. Reaching a screen that is already behind you must
     * return to it, not stack a second copy - otherwise back sends the owner *forwards* through a
     * screen they just left.
     *
     * With two destinations the only journey that can do it is the round trip, and it is written out
     * rather than skipped: the rule is what keeps the stack bounded, and a third destination would
     * arrive to a property that was already being asserted.
     */
    @Test
    fun `going back to a screen already behind you returns to it`() {
        val stack = BackStack.Initial
            .push(Screen.Settings)
            .push(Screen.Agterm)

        assertEquals(listOf(Screen.Agterm), stack.entries)
        assertFalse("the round trip must land back at the root, not one above it", stack.canPop)
    }

    @Test
    fun `no journey can grow the stack past the destinations it visited`() {
        val visited = setOf(Screen.Agterm, Screen.Settings)

        val stack = BackStack.Initial
            .push(Screen.Settings)
            .push(Screen.Agterm)
            .push(Screen.Settings)
            .push(Screen.Agterm)
            .push(Screen.Settings)

        // Bounded by what was visited, not by a number written here - which is what makes "back
        // always terminates" a property rather than an observation about this particular journey.
        assertTrue("stack grew to ${stack.entries}", stack.entries.size <= visited.size)
    }

    @Test
    fun `back at the root is a no-op rather than an empty stack`() {
        assertEquals(BackStack.Initial, BackStack.Initial.pop())
    }

    @Test
    fun `a back stack always has somewhere to be`() {
        assertThrows(IllegalArgumentException::class.java) { BackStack(emptyList()) }
    }

    // -- What rememberSaveable writes down ------------------------------------------------------

    @Test
    fun `every route survives a round trip`() {
        // Enumerated by hand rather than reflectively: a new destination whose route does not
        // round-trip should fail here, and a reflective list would only ever test what exists.
        val all = listOf(Screen.Agterm, Screen.Settings)

        all.forEach { screen ->
            assertEquals(screen, Screen.fromRoute(screen.route))
        }
        assertEquals("routes must be distinct", all.size, all.map { it.route }.toSet().size)
    }

    @Test
    fun `an unrecognised route is dropped rather than crashing`() {
        // What this stands in for: Play updates the app while it is in the background, and the saved
        // state names a screen the new build has never heard of.
        assertEquals(null, Screen.fromRoute("modules"))
        assertEquals(null, Screen.fromRoute("module/agterm"))
        assertEquals(null, Screen.fromRoute(""))
    }

    @Test
    fun `a saved stack of nothing recognisable restores to the terminal`() {
        val restored = BackStack(
            listOf("nonsense", "also-nonsense").mapNotNull(Screen::fromRoute)
                .ifEmpty { listOf(Screen.Agterm) },
        )

        assertEquals(BackStack.Initial, restored)
    }
}
