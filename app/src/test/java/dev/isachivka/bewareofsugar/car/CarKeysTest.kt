package dev.isachivka.bewareofsugar.car

import dev.isachivka.bewareofsugar.agterm.KEY_ROWS
import dev.isachivka.bewareofsugar.agterm.KeyCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keys the car carries: the phone's four and Backspace, by the bridge's names.
 */
class CarKeysTest {

    @Test
    fun `the keys are the bridge's names, in the owner's order`() {
        assertEquals(listOf("enter", "interrupt", "escape", "tab", "backspace"), CAR_KEYS.map { it.wire })
    }

    /**
     * Every wire name here is one the phone already sends. The bridge's key set is closed, and the
     * phone's bar is the only list of it this app keeps; a car key not on that bar would be a name the
     * bridge may refuse, discovered in a car.
     */
    @Test
    fun `every car key is a key the phone's bar already sends`() {
        val onTheBar = KEY_ROWS.flatten().filterIsInstance<KeyCell.Key>().map { it.name }.toSet()
        CAR_KEYS.forEach { key -> assertTrue(key.wire, key.wire in onTheBar) }
    }
}
