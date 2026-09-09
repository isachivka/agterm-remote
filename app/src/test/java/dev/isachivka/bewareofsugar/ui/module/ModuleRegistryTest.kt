package dev.isachivka.bewareofsugar.ui.module

import dev.isachivka.bewareofsugar.ui.nav.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The registry, and the property that replaced the launcher's count.
 *
 * ### What this file used to be about
 *
 * The count. `tileCount` was the one number on the launcher that could be wrong without looking
 * wrong — the design shipped "1 of 10" while drawing nine tiles — so five tests pinned its arithmetic
 * against hidden modules and live flags.
 *
 * All of that is gone. REQ-0012 deleted the eight illustrative modules at the owner's request, so
 * every tile is real, the subtitle stopped counting, and `tileCount` lost its only consumer.
 *
 * ### What replaced it, and why it is a better guard than the count was
 *
 * `Screen.screenFor` used to send anything unrecognised to a placeholder, which meant a module that
 * should have had a screen and did not would land on "not built yet" **silently**. With the
 * placeholders deleted it returns null instead, and the test below turns that into a failure.
 *
 * So the thing being defended has moved from *is the count right* to *does every module go
 * somewhere*, which is the question that was underneath it all along.
 */
class ModuleRegistryTest {

    @Test
    fun `the registry is exactly the modules that exist`() {
        assertEquals(listOf("reachability", "agterm"), ModuleRegistry.all.map { it.id })
    }

    /**
     * **Every module resolves to a screen, and this is the mechanism rather than a habit.**
     *
     * Adding an entry without a destination fails here. Before REQ-0012 it could not: the `else`
     * branch caught it and drew a placeholder, so the mistake shipped looking like a design.
     */
    @Test
    fun `every module in the registry resolves to a screen`() {
        ModuleRegistry.all.forEach { module ->
            assertNotNull(
                "module '${module.id}' is on the launcher with no screen behind it",
                Screen.screenFor(module),
            )
        }
    }

    /**
     * The control for the test above, and it exists because that one passes for free if `screenFor`
     * ever stopped returning null at all — a fallback quietly reintroduced would leave it green while
     * guarding nothing, which is exactly how the placeholder hid this class of mistake before.
     */
    @Test
    fun `a module with no screen resolves to nothing`() {
        val invented = Module("nas", ModuleRegistry.all.first().nameRes, ModuleRegistry.all.first().iconRes)

        assertNull("screenFor must have no fallback, or the test above guards nothing", Screen.screenFor(invented))
    }

    @Test
    fun `ids are unique, because the launcher keys its tiles by them`() {
        assertEquals(ModuleRegistry.all.size, ModuleRegistry.all.map { it.id }.toSet().size)
    }

    @Test
    fun `every module has its own icon`() {
        // Two modules sharing an icon is the copy-paste mistake, and it looks like a rendering bug
        // rather than a data one.
        assertEquals(ModuleRegistry.all.size, ModuleRegistry.all.map { it.iconRes }.toSet().size)
    }

    /**
     * Every module carries its own tagline now.
     *
     * It used to be null for the eight shapes, which had no sentence to say because they did nothing.
     * A real tile with no tagline would draw an empty line under its name.
     */
    @Test
    fun `every module has a tagline`() {
        ModuleRegistry.all.forEach { module ->
            assertNotNull("module '${module.id}' would draw a blank line under its name", module.taglineRes)
        }
    }

    @Test
    fun `an unknown id is not a module`() {
        assertNull(ModuleRegistry.byId("nas"))
    }
}
