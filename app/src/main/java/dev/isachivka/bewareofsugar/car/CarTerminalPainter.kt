package dev.isachivka.bewareofsugar.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.fonts.FontStyle
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.content.ContextCompat
import dev.isachivka.bewareofsugar.R
import dev.isachivka.bewareofsugar.agterm.Pane
import dev.isachivka.bewareofsugar.agterm.Sgr
import dev.isachivka.bewareofsugar.agterm.SgrRun
import dev.isachivka.bewareofsugar.agterm.expandTabs
import dev.isachivka.bewareofsugar.agterm.terminalTypeface

/**
 * Everything the painter draws, as a value. The screen builds one from the holder's state and the
 * draft; the painter draws it and nothing else.
 *
 * [lines] are the screen text as the bridge sent it - raw escapes when [styled], plain otherwise.
 * [status] is one sentence for the bottom line: the session's name most of the time, and what became
 * of the last send when that is the more useful thing to know.
 */
data class CarFrame(
    val lines: List<String> = emptyList(),
    val styled: Boolean = false,
    val draft: CarDraft = CarDraft(),
    val status: String = "",
    val stale: Boolean = false,
    /** Which pane is on screen, for the pane button's glyph. */
    val paneShown: Pane = Pane.Left,
    /** Whether the session has a second pane at all; without one the pane button is not drawn. */
    val splitPane: Boolean = false,
    /** The laptop's fit, as the bridge reports it: on, off, or unknown. Decides the fit glyph. */
    val fitEnabled: Boolean? = null,
)

/**
 * Paints the terminal onto the rectangle the host lends.
 *
 * ### The same grid as the phone, drawn by hand
 *
 * The phone lays the whole screen out as one `Text` so column *n* lands at the same x on every row;
 * that is what "tables still work" means (TerminalBox). Here there is no Compose - the host hands over
 * a raw `Surface` - so the same property is held the same way a terminal emulator holds it: every
 * character at `x = column * cellWidth`, one monospace family, no wrapping. Panning is a viewport
 * over the grid, never a reflow.
 *
 * The family is the phone's: JetBrains Mono with its real bold and italic faces and JuliaMono behind
 * it for symbols, through the same `terminalTypeface` chain, so the four faces here are derived from
 * one typeface and share one advance width - a bold run does not push the columns after it.
 *
 * ### What the host tells us, and what we do not assume
 *
 * The surface's size and density come from `onSurfaceAvailable`; the part of it not covered by the
 * host's own controls comes from `onVisibleAreaChanged`, and it changes when the map goes side by
 * side. The painter draws inside that rectangle and prints its columns×rows in the corner, which is
 * what REQ-0044 §7 asks the owner to read off the screen on the first run.
 *
 * ### Drawn on the main thread, on every change
 *
 * A frame arrives at most every poll, 2 Hz, and a pan every touch. The text is at most 120 lines.
 * Nothing here needs a render thread, and a render thread would be a place for a second bug to live.
 */
class CarTerminalPainter(
    private val context: Context,
    /** The key labels, from resources; a test hands in names of its own. */
    private val labelOf: (CarKey) -> String = { context.getString(it.label) },
    /** A press on one of the painted buttons. Resolved by [CarKeyRow] from the host's `onClick`. */
    private val onButton: (CarButton) -> Unit = {},
    /** The rectangle changed - a surface arrived, or the map came alongside - so [geometry] did too. */
    private val onGeometryChanged: () -> Unit = {},
) : SurfaceCallback {

    var frame: CarFrame = CarFrame()
        set(value) {
            field = value
            plain = null
            repaint()
        }

    private var container: SurfaceContainer? = null
    private var visible: Rect? = null

    /** Where the owner panned to; `null` follows the bottom, where the prompt is. */
    private var viewport: CarViewport? = null
    private var carryX = 0f
    private var carryY = 0f

    /** The lines without escapes, computed once per frame: what the grid arithmetic measures. */
    private var plain: List<String>? = null

    /** Where the buttons were last drawn, in the visible area's own pixels, for [onClick]. */
    private var boxes: List<CarButtonBox> = emptyList()

    private val typeface: Typeface by lazy { terminalTypeface(context) ?: Typeface.MONOSPACE }
    private val boldFace: Typeface by lazy { Typeface.create(typeface, FontStyle.FONT_WEIGHT_BOLD, false) }
    private val italicFace: Typeface by lazy { Typeface.create(typeface, FontStyle.FONT_WEIGHT_NORMAL, true) }
    private val boldItalicFace: Typeface by lazy { Typeface.create(typeface, FontStyle.FONT_WEIGHT_BOLD, true) }

    private fun faceOf(bold: Boolean, italic: Boolean): Typeface = when {
        bold && italic -> boldItalicFace
        bold -> boldFace
        italic -> italicFace
        else -> typeface
    }

    /** Leaving pan mode: back to the live view. */
    fun followBottom() {
        viewport = null
        carryX = 0f
        carryY = 0f
        repaint()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        container = surfaceContainer
        repaint()
        onGeometryChanged()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = Rect(visibleArea)
        repaint()
        onGeometryChanged()
    }

    /** The numbers a fit request carries for the rectangle as it is now; null before a surface. */
    fun geometry(): CarGeometry? {
        val c = container ?: return null
        val m = metrics(c.dpi)
        return CarTerminalLayout.geometry(area(c).width(), m.cellWidth, m.cellWidth, c.dpi)
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        container = null
    }

    /**
     * A tap on the surface, in the surface's pixels. The buttons were laid out in the visible area's
     * pixels, so the host's own insets are taken off first. A tap on the terminal itself does nothing;
     * there is no cursor to place.
     */
    override fun onClick(x: Float, y: Float) {
        val c = container ?: return
        val a = area(c)
        val hit = CarKeyRow.hit(boxes, x - a.left, y - a.top) ?: return
        onButton(hit)
    }

    /**
     * The host's pan: a distance in pixels since the last touch position, positive when the finger
     * moved left or up - so a positive x moves the window right over the text, which is how a person
     * drags a map to see more of it.
     */
    override fun onScroll(distanceX: Float, distanceY: Float) {
        val c = container ?: return
        val m = metrics(c.dpi)
        carryX += distanceX
        carryY += distanceY
        val dColumns = (carryX / m.cellWidth).toInt()
        val dLines = (carryY / m.lineHeight).toInt()
        if (dColumns == 0 && dLines == 0) return
        carryX -= dColumns * m.cellWidth
        carryY -= dLines * m.lineHeight
        val grid = grid(area(c), m)
        val lines = plainLines()
        val start = viewport ?: CarTerminalLayout.bottom(lines.size, grid)
        viewport = CarTerminalLayout.panned(start, dLines, dColumns, lines.size, widest(lines), grid)
        repaint()
    }

    private fun repaint() {
        val c = container ?: return
        val surface = c.surface ?: return
        if (!surface.isValid) return
        val canvas = try {
            surface.lockCanvas(null)
        } catch (e: IllegalArgumentException) {
            return
        }
        try {
            canvas.drawColor(GROUND)
            val a = area(c)
            canvas.save()
            canvas.translate(a.left.toFloat(), a.top.toFloat())
            paint(canvas, a.width(), a.height(), c.dpi)
            canvas.restore()
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    /**
     * Draws the frame into a [width]×[height] rectangle at the top-left of [canvas]. The part with no
     * surface in it, so `CarTerminalPainterTest` can hand it a bitmap.
     */
    fun paint(canvas: Canvas, width: Int, height: Int, dpi: Int) {
        val m = metrics(dpi)
        canvas.drawColor(GROUND)
        val pad = m.cellWidth
        val grid = CarTerminalLayout.grid(
            width = (width - 2 * pad).toInt(),
            height = (height - 2 * pad - m.buttonRow).toInt(),
            cellWidth = m.cellWidth,
            lineHeight = m.lineHeight,
        )
        val lines = plainLines()
        val v = viewport ?: CarTerminalLayout.bottom(lines.size, grid)

        // The terminal.
        val x0 = pad
        for (row in 0 until grid.rows) {
            val i = v.firstLine + row
            val baseline = pad + row * m.lineHeight + m.textSize
            if (frame.styled) {
                val raw = frame.lines.getOrNull(i) ?: continue
                drawStyled(canvas, Sgr.parse(raw), x0, baseline, v.firstColumn, grid.columns, m)
            } else {
                val line = lines.getOrNull(i) ?: continue
                canvas.drawText(cut(line, v.firstColumn, grid.columns), x0, baseline, m.text)
            }
        }

        // The buttons, in the band with the draft - where the owner asked for them. See CarKeyRow.
        val rowTop = pad + grid.rows * m.lineHeight
        boxes = CarKeyRow.layout(
            left = x0,
            top = rowTop + m.lineHeight * 0.25f,
            right = width - pad,
            height = m.lineHeight * 1.5f,
            cellWidth = m.cellWidth,
            labelOf = labelOf,
            showPane = frame.splitPane,
        )
        for (box in boxes) {
            canvas.drawRoundRect(box.left, box.top, box.right, box.bottom, m.textSize * 0.4f, m.textSize * 0.4f, m.chip)
            val glyph = glyphOf(box.button)
            if (glyph == null) {
                val label = CarKeyRow.label(box.button, labelOf)
                val x = box.left + CarKeyRow.PADDING_CELLS * m.cellWidth
                val baseline = box.top + (box.bottom - box.top + m.textSize) / 2f - m.textSize * 0.1f
                canvas.drawText(label, x, baseline, m.text)
            } else {
                // The same Material glyphs the phone uses, tinted like the accent, one line tall.
                val size = m.textSize * 1.15f
                val cx = (box.left + box.right) / 2f
                val cy = (box.top + box.bottom) / 2f
                glyph.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
                // The fit glyph is dim while the laptop's window is its own; lit once it is the car's.
                glyph.setTint(if (box.button == CarButton.Fit && frame.fitEnabled != true) DIM else ACCENT)
                glyph.draw(canvas)
            }
        }

        // The band: the owner's words, and never the laptop's.
        val bandTop = rowTop + m.buttonRow
        canvas.drawRect(x0, bandTop, width - pad, bandTop + m.hairline, m.rule)
        val draftText = when {
            frame.draft.listening && frame.draft.text.isEmpty() -> context.getString(R.string.car_listening)
            frame.draft.text.isEmpty() -> context.getString(R.string.car_draft_hint)
            else -> frame.draft.text
        }
        val draftPaint = if (frame.draft.text.isEmpty()) m.dim else m.accent
        val prefix = if (frame.draft.listening) "\u25cf " else "> "
        canvas.drawText(prefix + cut(draftText, 0, grid.columns - 2), x0, bandTop + m.lineHeight * 0.3f + m.textSize, draftPaint)

        // The status line, with the grid the host gave us at its end - for the first-run script.
        val status = (if (frame.stale) "${context.getString(R.string.car_stale)} \u00b7 ${frame.status}" else frame.status) +
            " \u00b7 ${grid.columns}\u00d7${grid.rows}"
        canvas.drawText(cut(status, 0, grid.columns), x0, bandTop + m.lineHeight * 1.3f + m.textSize, if (frame.stale) m.warn else m.dim)
    }

    private fun drawStyled(canvas: Canvas, runs: List<SgrRun>, x0: Float, baseline: Float, first: Int, columns: Int, m: Metrics) {
        var column = 0
        for (run in runs) {
            val start = column
            val end = column + run.text.length
            column = end
            val from = maxOf(start, first)
            val to = minOf(end, first + columns)
            if (to <= from) continue
            val segment = run.text.substring(from - start, to - start)
            val x = x0 + (from - first) * m.cellWidth
            val style = run.style
            var fg = style.foreground ?: FOREGROUND
            var bg = style.background
            if (style.inverse) {
                val swapped = bg ?: GROUND
                bg = fg
                fg = swapped
            }
            if (bg != null) {
                m.fill.color = bg
                canvas.drawRect(x, baseline - m.textSize, x + segment.length * m.cellWidth, baseline + m.lineHeight - m.textSize, m.fill)
            }
            m.styledText.color = if (style.faint) fg and 0x80FFFFFF.toInt() else fg
            m.styledText.typeface = faceOf(style.bold, style.italic)
            m.styledText.isUnderlineText = style.underline
            canvas.drawText(segment, x, baseline, m.styledText)
        }
    }

    private fun glyphOf(button: CarButton): android.graphics.drawable.Drawable? = when (button) {
        is CarButton.Key -> null
        CarButton.Claude -> ContextCompat.getDrawable(context, R.drawable.ic_claude)
        CarButton.Pane -> ContextCompat.getDrawable(
            context,
            if (frame.paneShown == Pane.Right) R.drawable.ic_pane_right else R.drawable.ic_pane_left,
        )
        CarButton.Fit -> ContextCompat.getDrawable(
            context,
            if (frame.fitEnabled == true) R.drawable.ic_fit_width_on else R.drawable.ic_fit_width_off,
        )
        CarButton.Mic -> ContextCompat.getDrawable(context, R.drawable.ic_mic)
        CarButton.Keyboard -> ContextCompat.getDrawable(context, R.drawable.ic_keyboard)
        CarButton.Sessions -> ContextCompat.getDrawable(context, R.drawable.ic_view_list)
    }

    private fun plainLines(): List<String> = plain ?: run {
        val computed = if (frame.styled) {
            frame.lines.map { line -> Sgr.parse(line).joinToString("") { it.text } }
        } else {
            frame.lines.map { expandTabs(it) }
        }
        plain = computed
        computed
    }

    private fun widest(lines: List<String>): Int = lines.maxOfOrNull { it.length } ?: 0

    private fun area(c: SurfaceContainer): Rect = visible ?: Rect(0, 0, c.width, c.height)

    private fun grid(area: Rect, m: Metrics): CarGrid =
        CarTerminalLayout.grid(
            width = (area.width() - 2 * m.cellWidth).toInt(),
            height = (area.height() - 2 * m.cellWidth).toInt(),
            cellWidth = m.cellWidth,
            lineHeight = m.lineHeight,
        )

    private fun cut(line: String, first: Int, columns: Int): String =
        if (first >= line.length || columns <= 0) "" else line.substring(first, minOf(line.length, first + columns))

    private class Metrics(val textSize: Float, typeface: Typeface) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; this.textSize = this@Metrics.textSize; color = FOREGROUND }
        val styledText = Paint(text)
        val dim = Paint(text).apply { color = DIM }
        val accent = Paint(text).apply { color = ACCENT }
        val warn = Paint(text).apply { color = WARN }
        val fill = Paint()
        val rule = Paint().apply { color = DIM }
        val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CHIP }
        val cellWidth: Float = text.measureText("M")
        val lineHeight: Float = textSize * LINE_HEIGHT
        val hairline: Float = maxOf(1f, textSize / 16f)
        /** The button row: a chip one and a half lines tall with a quarter line above and below. */
        val buttonRow: Float = lineHeight * 2f
    }

    private fun metrics(dpi: Int): Metrics = Metrics(CAR_FONT_SP * dpi / 160f, typeface)

    companion object {
        /**
         * One size; the rectangle decides the grid. It started at 22, larger than the phone's 16
         * because the screen is at arm's length; the owner saw it on the head unit on 2026-09-05 and
         * asked for one and a half times smaller: *"очень крупный текст, можно в полтора раза меньше"*.
         */
        const val CAR_FONT_SP = 15f
        const val LINE_HEIGHT = 1.25f
        val GROUND = Color.rgb(0x10, 0x12, 0x14)
        val FOREGROUND = Color.rgb(0xE6, 0xE6, 0xE6)
        val DIM = Color.rgb(0x8A, 0x8F, 0x98)
        val CHIP = Color.rgb(0x2A, 0x2E, 0x33)
        val ACCENT = Color.rgb(0x7F, 0xD1, 0x8C)
        val WARN = Color.rgb(0xF0, 0xB4, 0x5A)
    }
}
