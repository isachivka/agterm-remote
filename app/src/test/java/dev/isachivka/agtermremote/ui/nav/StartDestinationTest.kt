package dev.isachivka.agtermremote.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the app opens, as a unit test.
 *
 * The port made the app open on the terminal unconditionally, which is right for the only owner it
 * had - one who was already paired. It is wrong for every new one: a phone with no laptop opens on a
 * terminal that cannot connect, and the thing it is being asked to do is two taps away behind a
 * settings button.
 *
 * A pure function of one fact, so it is answered here in milliseconds rather than on an emulator.
 * What a device is still needed for is that the function is actually consulted, which is one test in
 * `NavigationInstrumentedTest` rather than four here.
 */
class StartDestinationTest {

    @Test
    fun `an unpaired phone starts at pairing`() {
        assertEquals(Start.Pairing, startDestination(hasPairedLaptop = false))
    }

    @Test
    fun `a paired phone starts at the terminal`() {
        assertEquals(Start.Terminal, startDestination(hasPairedLaptop = true))
    }

    /**
     * **The two are not the same place**, which is the whole content of the two tests above and is
     * worth saying once on its own: a `startDestination` that returned one constant would satisfy
     * either of them in isolation.
     */
    @Test
    fun `the two answers differ`() {
        assertNotEquals(startDestination(hasPairedLaptop = false), startDestination(hasPairedLaptop = true))
    }

    /**
     * Nothing else can be the start destination.
     *
     * The guarantee is a COMPILE-TIME one and this test is where it is spent: `Start` is sealed, the
     * `when` below has no `else`, so a third variant added to `Start` stops this file compiling until
     * somebody decides where it opens. A runtime assertion could not say as much - `sealedSubclasses`
     * needs kotlin-reflect, which is not on this module's test classpath, and the JVM target here is
     * 11, below the 17 at which Kotlin emits a `PermittedSubclasses` attribute a plain `Class` could
     * be asked about.
     *
     * The list is written out rather than derived for the same reason: deriving it from the type
     * would make this test agree with whatever the type says, which is not a check.
     */
    @Test
    fun `nothing else can be the start destination`() {
        val every = listOf(Start.Pairing, Start.Terminal)

        assertEquals("both answers of startDestination must appear here", every.toSet(), setOf(startDestination(false), startDestination(true)))

        every.forEach { start ->
            // No `else`. A third `Start` breaks this line rather than quietly inheriting a root.
            val root = when (start) {
                Start.Pairing -> BackStack.initialFor(Start.Pairing)
                Start.Terminal -> BackStack.initialFor(Start.Terminal)
            }
            assertEquals(root, BackStack.initialFor(start))
        }
    }

    // --- What each one actually opens on ------------------------------------------------------------

    @Test
    fun `starting at the terminal puts nothing behind it`() {
        val stack = BackStack.initialFor(Start.Terminal)

        assertEquals(Screen.Agterm, stack.current)
        assertFalse("back on the terminal must exit the app, as it always has", stack.canPop)
    }

    /**
     * Pairing opens with the terminal underneath it, and that entry is not decoration.
     *
     * Pairing has no screen of its own yet - it is the whole of the settings page, and it gets a
     * screen when the scanner is built. What matters now is that back from it goes somewhere: with a
     * single-entry stack, back at the root returns the stack unchanged, so a new owner who does not
     * want to pair this minute would be held on the pairing screen with no way out but the task
     * switcher.
     */
    @Test
    fun `starting at pairing leaves the terminal to go back to`() {
        val stack = BackStack.initialFor(Start.Pairing)

        assertEquals(Screen.Settings, stack.current)
        assertTrue("an owner who does not want to pair now must be able to leave", stack.canPop)
        assertEquals(Screen.Agterm, stack.pop().current)
    }
}
