package dev.isachivka.bewareofsugar.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two numbers the car sends the laptop when it asks for a fit.
 *
 * They are the phone's two numbers, measured from a surface instead of a layout, and the bridge
 * keys its cache on them. A wrong scale here is a window fitted to the wrong width and a cache
 * entry under a key nothing will ever ask for again - silently, which is how this feature burned
 * the phone twice (REQ-0042).
 */
class CarGeometryTest {

    @Test
    fun `at 160 dpi a pixel is a dp`() {
        // 1190 wide, 9 px of padding either side, 9 px cells: 1172 dp and 9000 milli-dp.
        assertEquals(CarGeometry(1172, 9000), CarTerminalLayout.geometry(areaWidthPx = 1190, padPx = 9f, cellWidthPx = 9f, dpi = 160))
    }

    @Test
    fun `at 320 dpi every pixel is half a dp`() {
        assertEquals(CarGeometry(586, 4500), CarTerminalLayout.geometry(areaWidthPx = 1190, padPx = 9f, cellWidthPx = 9f, dpi = 320))
    }

    @Test
    fun `no surface, no numbers`() {
        assertNull(CarTerminalLayout.geometry(areaWidthPx = 0, padPx = 9f, cellWidthPx = 9f, dpi = 160))
        assertNull(CarTerminalLayout.geometry(areaWidthPx = 1190, padPx = 9f, cellWidthPx = 0f, dpi = 160))
        assertNull(CarTerminalLayout.geometry(areaWidthPx = 1190, padPx = 9f, cellWidthPx = 9f, dpi = 0))
    }
}
