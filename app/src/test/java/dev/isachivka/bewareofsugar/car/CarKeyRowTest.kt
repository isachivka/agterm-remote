package dev.isachivka.bewareofsugar.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The button row: where each button lands and which one a tap means.
 *
 * Every press in the car resolves through [CarKeyRow.hit]; a wrong rectangle here is Enter pressed
 * when ^C was meant, on a real machine, from a car. So the arithmetic is tested to the pixel.
 */
class CarKeyRowTest {

    private val names = mapOf("enter" to "Enter", "interrupt" to "^C", "escape" to "Esc", "tab" to "Tab", "backspace" to "⌫")
    private fun labelOf(key: CarKey): String = names.getValue(key.wire)

    @Test
    fun `the keys come first, in the phone's order, then the glyph buttons`() {
        val boxes = CarKeyRow.layout(left = 0f, top = 0f, right = 10_000f, height = 20f, cellWidth = 10f, labelOf = ::labelOf)
        assertEquals(CarKeyRow.ORDER, boxes.map { it.button })
        assertEquals(CAR_KEYS.map { it.wire }, boxes.take(CAR_KEYS.size).map { (it.button as CarButton.Key).key.wire })
        assertEquals(
            listOf(CarButton.Claude, CarButton.Pane, CarButton.Fit, CarButton.Mic, CarButton.Keyboard, CarButton.Sessions),
            boxes.drop(CAR_KEYS.size).map { it.button },
        )
    }

    /** No second pane, no pane button: absent, not disabled, so the row closes up around the gap. */
    @Test
    fun `a session without a second pane has no pane button`() {
        val boxes = CarKeyRow.layout(left = 0f, top = 0f, right = 10_000f, height = 20f, cellWidth = 10f, labelOf = ::labelOf, showPane = false)
        assertTrue(boxes.none { it.button == CarButton.Pane })
        val claude = boxes.first { it.button == CarButton.Claude }
        val fit = boxes.first { it.button == CarButton.Fit }
        assertEquals(claude.right + 10f, fit.left, 0f)
    }

    @Test
    fun `a button is its label plus a cell of padding either side, and a cell of gap follows`() {
        val boxes = CarKeyRow.layout(left = 10f, top = 5f, right = 10_000f, height = 20f, cellWidth = 10f, labelOf = ::labelOf)
        // "Enter" is 5 characters: 7 cells wide, from 10 to 80.
        assertEquals(10f, boxes[0].left, 0f)
        assertEquals(80f, boxes[0].right, 0f)
        // "^C" is 2: 4 cells, starting after a 1-cell gap at 90.
        assertEquals(90f, boxes[1].left, 0f)
        assertEquals(130f, boxes[1].right, 0f)
        assertEquals(5f, boxes[1].top, 0f)
        assertEquals(25f, boxes[1].bottom, 0f)
    }

    @Test
    fun `a tap inside a box names its button, and a tap in the gap names nothing`() {
        val boxes = CarKeyRow.layout(left = 0f, top = 0f, right = 10_000f, height = 20f, cellWidth = 10f, labelOf = ::labelOf)
        assertEquals(CarButton.Key(CAR_KEYS[0]), CarKeyRow.hit(boxes, 35f, 10f))
        assertEquals(CarButton.Key(CAR_KEYS[1]), CarKeyRow.hit(boxes, 100f, 19f))
        // Enter ends at 70 and ^C starts at 80: the gap between them names nothing.
        assertNull(CarKeyRow.hit(boxes, 75f, 10f))
        assertNull(CarKeyRow.hit(boxes, 35f, 20f))
    }

    /** A narrow rectangle drops buttons from the end rather than drawing one half off the screen. */
    @Test
    fun `buttons that do not fit are dropped from the end`() {
        val boxes = CarKeyRow.layout(left = 0f, top = 0f, right = 150f, height = 20f, cellWidth = 10f, labelOf = ::labelOf)
        assertEquals(2, boxes.size)
        assertTrue(boxes.all { it.right <= 150f })
    }
}
