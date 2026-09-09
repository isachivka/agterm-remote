package dev.isachivka.bewareofsugar.car

/** Columns and rows the car's rectangle holds, after the band and the status line took theirs. */
data class CarGrid(val columns: Int, val rows: Int)

/** Where the window over the screen text starts: a line index from the top, a column from the left. */
data class CarViewport(val firstLine: Int, val firstColumn: Int)

/**
 * What the car tells the laptop when it asks for a fit: the box in dp and one character in
 * thousandths of a dp - the same two numbers the phone sends, measured here from the surface the host
 * lent rather than from a Compose layout. The bridge keys its cache on them, so the car's geometry
 * gets entries of its own beside the phone's.
 */
data class CarGeometry(val boxWidthDp: Int, val characterWidthMilliDp: Int)

/**
 * The arithmetic between the rectangle the host hands over and the text the owner reads.
 *
 * ### The same trade the phone made
 *
 * The laptop's terminal is wide and the car's rectangle beside a map is not. REQ-0009 settled this for
 * the phone: keep the grid, never reflow, and let the viewport move over it — a column that has moved
 * is recoverable by panning and a column that has been reflowed away is not. The car has a better pan
 * than a thumb: the host's own pan mode, which is what [panned] serves.
 *
 * ### No fit
 *
 * The phone asks the laptop for a column count that fits it. The car does not ask, and adopts whatever
 * fit is in force — a second screen asking the laptop to resize would fight the phone for one window.
 * So the car's columns are what its rectangle holds, and wide lines are read by panning.
 */
object CarTerminalLayout {

    /** The draft band and the status line, under the terminal, each one row tall. */
    const val RESERVED_ROWS = 2

    fun grid(width: Int, height: Int, cellWidth: Float, lineHeight: Float): CarGrid {
        val columns = if (cellWidth <= 0f) 0 else (width / cellWidth).toInt()
        val rows = if (lineHeight <= 0f) 0 else (height / lineHeight).toInt() - RESERVED_ROWS
        return CarGrid(columns.coerceAtLeast(0), rows.coerceAtLeast(0))
    }

    /** The live view: a terminal is read from its bottom, where the prompt is. */
    fun bottom(lineCount: Int, grid: CarGrid): CarViewport =
        CarViewport(firstLine = (lineCount - grid.rows).coerceAtLeast(0), firstColumn = 0)

    /** The rows on screen: each line cut from [CarViewport.firstColumn] for as many columns as fit. */
    fun window(lines: List<String>, grid: CarGrid, v: CarViewport): List<String> =
        (v.firstLine until v.firstLine + grid.rows).map { i ->
            val line = lines.getOrNull(i).orEmpty()
            if (v.firstColumn >= line.length) "" else line.substring(v.firstColumn, minOf(line.length, v.firstColumn + grid.columns))
        }

    /**
     * The fit geometry of a rectangle [areaWidthPx] wide with [padPx] either side, drawn in cells
     * [cellWidthPx] wide at [dpi]. Null before there is a surface to measure, so a fit cannot be asked
     * for with zeros - the bridge would refuse it, and a refusal from the car reads as the laptop's.
     */
    fun geometry(areaWidthPx: Int, padPx: Float, cellWidthPx: Float, dpi: Int): CarGeometry? {
        if (dpi <= 0 || cellWidthPx <= 0f) return null
        val scale = dpi / 160f
        val box = ((areaWidthPx - 2 * padPx) / scale).toInt()
        val cell = Math.round(cellWidthPx / scale * 1000)
        return if (box <= 0 || cell <= 0) null else CarGeometry(box, cell)
    }

    /**
     * Moves the viewport by whole cells and clamps it to the text: never past the last line, never
     * past the widest line, never negative. When everything fits, nothing moves.
     */
    fun panned(v: CarViewport, dLines: Int, dColumns: Int, lineCount: Int, widest: Int, grid: CarGrid): CarViewport {
        val maxLine = (lineCount - grid.rows).coerceAtLeast(0)
        val maxColumn = (widest - grid.columns).coerceAtLeast(0)
        return CarViewport(
            firstLine = (v.firstLine + dLines).coerceIn(0, maxLine),
            firstColumn = (v.firstColumn + dColumns).coerceIn(0, maxColumn),
        )
    }
}
