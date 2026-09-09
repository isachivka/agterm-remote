package dev.isachivka.bewareofsugar.car

/** One button in the row the painter draws under the terminal. */
sealed interface CarButton {
    data class Key(val key: CarKey) : CarButton
    /** Types the owner's Claude alias and Enter - the phone's macro, REQ-0013. */
    data object Claude : CarButton
    /** The other pane of a split session; drawn only when the session has one. */
    data object Pane : CarButton
    /** Ask the laptop to fit its window to this rectangle, or give the window back. */
    data object Fit : CarButton
    data object Mic : CarButton
    data object Keyboard : CarButton
    data object Sessions : CarButton
}

/** A button and where it is, in the painter's own pixels. */
data class CarButtonBox(val button: CarButton, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/**
 * The row of buttons, laid out and hit-tested without a canvas.
 *
 * ### Ours, not the host's — the owner's decision on the head unit
 *
 * The first build put the four keys on the host's action strip and the microphone, keyboard and list
 * on its map strip. On the head unit the strips faded after a few seconds without a touch, the owner
 * could not find Enter, and asked the question that ended that design: *"Зачем мы вообще кнопки
 * вынесли куда-то наружу терминала? Почему не рисовать кнопки примерно там, где находится поле
 * ввода, чтобы экономить место?"*
 *
 * So the buttons are drawn by [CarTerminalPainter] in the band with the draft, and a tap on the
 * surface — which the host reports through `SurfaceCallback.onClick` — is resolved here. What that
 * buys: no cap of four, nothing fades, the keys sit where the typing lands, and the rows the strips
 * took go back to the terminal. What it costs: the host no longer decides which of these is fit for
 * driving; the keyboard button opens the host's own keyboard template, which the host still refuses
 * while moving, so that rule is kept where it was.
 *
 * Widths are in cells so the row lines up with the grid: a label of n characters gets n + 2 cells.
 */
object CarKeyRow {

    const val PADDING_CELLS = 1
    const val GAP_CELLS = 1

    /** The buttons in order: the keys, then the ones that are not keys, glyphs all. */
    val ORDER: List<CarButton> =
        CAR_KEYS.map { CarButton.Key(it) } + listOf(
            CarButton.Claude, CarButton.Pane, CarButton.Fit, CarButton.Mic, CarButton.Keyboard, CarButton.Sessions,
        )

    /**
     * The label the painter draws for a key; [labelOf] supplies the names from resources. The three
     * that are not keys are drawn as glyphs - *"mic abc list? что это?"* - and their label here only
     * sets the chip's width: three cells, a square for the glyph.
     */
    fun label(button: CarButton, labelOf: (CarKey) -> String): String = when (button) {
        is CarButton.Key -> labelOf(button.key)
        else -> "   "
    }

    /**
     * Lays the row out from [left] to at most [right], starting at [top] with [height]. Buttons that
     * would not fit are dropped from the end rather than squeezed: a button half off the screen is a
     * button that cannot be pressed.
     *
     * [showPane] is whether the session has a second pane. Without one the pane button is ABSENT,
     * not disabled - the rule the phone's key bar set when it left its spare cell empty.
     */
    fun layout(
        left: Float,
        top: Float,
        right: Float,
        height: Float,
        cellWidth: Float,
        labelOf: (CarKey) -> String,
        showPane: Boolean = true,
    ): List<CarButtonBox> {
        val out = ArrayList<CarButtonBox>()
        var x = left
        for (button in ORDER) {
            if (button == CarButton.Pane && !showPane) continue
            val width = (label(button, labelOf).length + 2 * PADDING_CELLS) * cellWidth
            if (x + width > right) break
            out.add(CarButtonBox(button, x, top, x + width, top + height))
            x += width + GAP_CELLS * cellWidth
        }
        return out
    }

    fun hit(boxes: List<CarButtonBox>, x: Float, y: Float): CarButton? =
        boxes.firstOrNull { it.contains(x, y) }?.button
}
