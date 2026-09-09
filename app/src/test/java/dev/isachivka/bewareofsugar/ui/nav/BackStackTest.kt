package dev.isachivka.bewareofsugar.ui.nav

import dev.isachivka.bewareofsugar.ui.module.ModuleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `the app opens on the launcher`() {
        assertEquals(Screen.Home, BackStack.Initial.current)
        assertFalse("back at the root must exit the app, not be swallowed", BackStack.Initial.canPop)
    }

    @Test
    fun `going somewhere and coming back returns you where you were`() {
        val stack = BackStack.Initial.push(Screen.Updates)

        assertEquals(Screen.Updates, stack.current)
        assertTrue(stack.canPop)
        assertEquals(Screen.Home, stack.pop().current)
    }

    /** Home to Updates to Token, and back twice, which is the deepest the app currently goes. */
    @Test
    fun `back unwinds the whole journey one screen at a time`() {
        val stack = BackStack.Initial.push(Screen.Updates).push(Screen.Token)

        assertEquals(listOf(Screen.Home, Screen.Updates, Screen.Token), stack.entries)
        assertEquals(Screen.Updates, stack.pop().current)
        assertEquals(Screen.Home, stack.pop().pop().current)
    }

    @Test
    fun `tapping the same destination twice costs one press of back, not two`() {
        val once = BackStack.Initial.push(Screen.Updates)
        val twice = once.push(Screen.Updates)

        assertEquals(once, twice)
        assertEquals(Screen.Home, twice.pop().current)
    }

    /**
     * The one that would have been a real bug. Reaching a screen that is already behind you must
     * return to it, not stack a second copy - otherwise back sends the owner *forwards* through a
     * screen they just left.
     */
    @Test
    fun `going back to a screen already behind you returns to it`() {
        val stack = BackStack.Initial
            .push(Screen.Updates)
            .push(Screen.Token)
            .push(Screen.Updates)

        assertEquals(listOf(Screen.Home, Screen.Updates), stack.entries)
        assertEquals(Screen.Home, stack.pop().current)
    }

    @Test
    fun `no journey can grow the stack past the destinations it visited`() {
        val visited = setOf(Screen.Home, Screen.Updates, Screen.Token)

        val stack = BackStack.Initial
            .push(Screen.Updates)
            .push(Screen.Token)
            .push(Screen.Updates)
            .push(Screen.Token)
            .push(Screen.Home)
            .push(Screen.Updates)

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
        val all = listOf(Screen.Home, Screen.Updates, Screen.Token, Screen.ModulesSettings) +
            // Through screenFor, because that is what a tap uses, and the round trip has to agree with
            // the tap or a rotation would move the owner to a different screen. It returns null for a
            // module with no screen - which ModuleRegistryTest proves cannot happen - so this filters
            // rather than asserts, and that assertion lives where the registry does.
            ModuleRegistry.all.mapNotNull(Screen.Companion::screenFor)

        all.forEach { screen ->
            assertEquals(screen, Screen.fromRoute(screen.route))
        }
        assertEquals("routes must be distinct", all.size, all.map { it.route }.toSet().size)
    }

    /**
     * Every module restores to its own screen.
     *
     * **This used to guard against restoring onto a placeholder**, which was the subtle failure while
     * placeholders existed: the tile would open the real screen and a rotation would put the owner on
     * a "not built yet" card for a module that works. REQ-0012 deleted the placeholder, so there is
     * nothing to land on wrongly — what survives is that a saved route comes back as the same screen
     * the tap produced.
     */
    @Test
    fun `every module restores to the screen its tile opens`() {
        assertEquals("the guard needs something to guard", 2, ModuleRegistry.all.size)
        ModuleRegistry.all.forEach { module ->
            val restored = Screen.fromRoute("module/${module.id}")

            assertNotNull("a module must resolve to a screen", restored)
            assertEquals(
                "a rotation would move the owner to a different screen than the tap did",
                Screen.screenFor(module),
                restored,
            )
        }
    }

    @Test
    fun `an unrecognised route is dropped rather than crashing`() {
        // What this stands in for: the owner updates the app - which this app does to itself -
        // while it is in the background, and the saved state names a screen the new build has
        // never heard of.
        assertEquals(null, Screen.fromRoute("modules-settings"))
    }

    /**
     * The same version-skew case, one level down: the route is shaped like a module and names one
     * this build no longer has. Dropped rather than restored as an empty screen, which is what
     * carrying the id instead of the module would have forced.
     */
    @Test
    fun `a route naming a module this build does not have is dropped`() {
        assertEquals(null, Screen.fromRoute("module/nas"))
        assertEquals(null, Screen.fromRoute("module/"))
    }

    @Test
    fun `two modules are two different destinations`() {
        val first = Screen.screenFor(ModuleRegistry.all[0])!!
        val second = Screen.screenFor(ModuleRegistry.all[1])!!

        // Otherwise push() would treat the second tap as "you are already here", and the owner would
        // tap the second tile and stay on the first.
        val stack = BackStack.Initial.push(first).push(second)

        assertEquals(listOf(Screen.Home, first, second), stack.entries)
    }

    @Test
    fun `a saved stack of nothing recognisable restores to the launcher`() {
        val restored = BackStack(
            listOf("nonsense", "also-nonsense").mapNotNull(Screen::fromRoute)
                .ifEmpty { listOf(Screen.Home) },
        )

        assertEquals(BackStack.Initial, restored)
    }
}
