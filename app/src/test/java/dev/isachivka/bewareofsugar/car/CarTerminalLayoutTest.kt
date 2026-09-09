package dev.isachivka.bewareofsugar.car

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The grid the car's rectangle gives, and the window the viewport cuts out of the screen text.
 *
 * The host hands over a rectangle in pixels and a density; the owner gets columns and rows. Every
 * number in between is here, so a car of any width gets the same arithmetic the tests saw.
 */
class CarTerminalLayoutTest {

    private val grid = CarGrid(columns = 80, rows = 18)

    @Test
    fun `the grid floors, and reserves rows for the draft band and the status line`() {
        // 1000 / 12.5 = 80 columns; 600 / 30 = 20 rows, two of them reserved.
        assertEquals(grid, CarTerminalLayout.grid(width = 1000, height = 600, cellWidth = 12.5f, lineHeight = 30f))
    }

    @Test
    fun `a rectangle too small for anything gives an empty grid rather than throwing`() {
        assertEquals(CarGrid(0, 0), CarTerminalLayout.grid(width = 0, height = 0, cellWidth = 12.5f, lineHeight = 30f))
        assertEquals(CarGrid(3, 0), CarTerminalLayout.grid(width = 40, height = 59, cellWidth = 12.5f, lineHeight = 30f))
    }

    /** The live view: a terminal is read from its bottom, where the prompt is. */
    @Test
    fun `the bottom viewport shows the last rows`() {
        assertEquals(CarViewport(firstLine = 82, firstColumn = 0), CarTerminalLayout.bottom(lineCount = 100, grid = grid))
        assertEquals(CarViewport(firstLine = 0, firstColumn = 0), CarTerminalLayout.bottom(lineCount = 5, grid = grid))
    }

    @Test
    fun `the window cuts each line from the first column for as many columns as fit`() {
        val lines = listOf("0123456789", "abcdefghij", "short")
        val small = CarGrid(columns = 4, rows = 2)
        assertEquals(listOf("2345", "cdef"), CarTerminalLayout.window(lines, small, CarViewport(firstLine = 0, firstColumn = 2)))
        assertEquals(listOf("cdef", "ort"), CarTerminalLayout.window(lines, small, CarViewport(firstLine = 1, firstColumn = 2)))
        assertEquals(listOf("", ""), CarTerminalLayout.window(lines, small, CarViewport(firstLine = 1, firstColumn = 20)))
    }

    @Test
    fun `panning clamps to the text and never goes negative`() {
        val v = CarViewport(firstLine = 82, firstColumn = 0)
        val up = CarTerminalLayout.panned(v, dLines = -100, dColumns = -5, lineCount = 100, widest = 200, grid = grid)
        assertEquals(CarViewport(0, 0), up)
        val far = CarTerminalLayout.panned(v, dLines = 100, dColumns = 500, lineCount = 100, widest = 200, grid = grid)
        assertEquals(CarViewport(firstLine = 82, firstColumn = 120), far)
    }

    @Test
    fun `panning is a no-op when everything fits`() {
        val v = CarViewport(0, 0)
        assertEquals(v, CarTerminalLayout.panned(v, dLines = 3, dColumns = 3, lineCount = 10, widest = 40, grid = grid))
    }
}
