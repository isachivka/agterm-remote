package dev.isachivka.bewareofsugar.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The painter, handed a bitmap instead of the host's surface.
 *
 * What is checked is what a screenshot in the car would show: the ground where nothing is drawn,
 * ink where the first line is, and a red where the text said red. Not pixel-exact - fonts differ
 * between devices - but each assertion fails on the defect it names: a painter that draws nothing,
 * or that drops the colours it was asked for.
 */
@RunWith(AndroidJUnit4::class)
class CarTerminalPainterTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun paint(frame: CarFrame, width: Int = 800, height: Int = 400): Bitmap {
        val painter = CarTerminalPainter(context)
        painter.frame = frame
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        painter.paint(Canvas(bitmap), width, height, dpi = 160)
        return bitmap
    }

    @Test
    fun theGroundIsPaintedWhereNothingIsDrawn() {
        val bitmap = paint(CarFrame())
        assertEquals(CarTerminalPainter.GROUND, bitmap.getPixel(2, 2))
    }

    @Test
    fun aLineOfTextLeavesInk() {
        val bitmap = paint(CarFrame(lines = listOf("MMMMMMMMMMMMMMMMMMMM")))
        // Ink somewhere in the first text row: the row starts one cell in and one cell down, and a cell is
        // about nine pixels at 15sp and 160 dpi.
        assertTrue(inkIn(bitmap, left = 10, top = 8, right = 200, bottom = 40))
    }

    @Test
    fun styledRedIsRed() {
        val bitmap = paint(CarFrame(lines = listOf("[31mMMMMMMMMMMMMMMMMMMMM[0m"), styled = true))
        val red = pixels(bitmap, left = 10, top = 8, right = 200, bottom = 40)
            .filter { it != CarTerminalPainter.GROUND }
            .count { Color.red(it) > 120 && Color.green(it) < 100 && Color.blue(it) < 100 }
        assertNotEquals(0, red)
    }

    @Test
    fun theDraftBandIsDrawnBelowTheGrid() {
        val empty = paint(CarFrame())
        val withDraft = paint(CarFrame(draft = CarDraft(text = "MMMMMMMMMMMM")))
        // Same frame otherwise, so any difference is the band's text.
        var differs = 0
        for (y in 0 until empty.height step 2) for (x in 0 until empty.width step 2) {
            if (empty.getPixel(x, y) != withDraft.getPixel(x, y)) differs++
        }
        assertTrue(differs > 0)
    }

    private fun inkIn(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Boolean =
        pixels(bitmap, left, top, right, bottom).any { it != CarTerminalPainter.GROUND }

    private fun pixels(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): List<Int> {
        val out = ArrayList<Int>()
        for (y in top until bottom) for (x in left until right) out.add(bitmap.getPixel(x, y))
        return out
    }
}
