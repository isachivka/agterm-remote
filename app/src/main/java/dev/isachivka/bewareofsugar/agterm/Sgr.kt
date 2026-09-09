package dev.isachivka.bewareofsugar.agterm

/**
 * SGR — the `ESC [ … m` sequences that carry colour and weight — parsed into runs the terminal box can
 * draw.
 *
 * ### What arrives
 *
 * When the owner turns on the styled screen, the bridge reads the pane through its zmx daemon and hands
 * back text that ghostty's own formatter emitted: `\e[0m` before every styled run, palette colours as
 * `38;5;N`, RGB as `38;2;r;g;b`, bold and friends as their one-digit codes. Every other escape has been
 * removed on the laptop (see the bridge's `internal/styled`), so this parser meets SGR and text and
 * nothing else — an unknown sequence here is a bug on the other end, and it is skipped rather than
 * shown.
 *
 * ### What this is not
 *
 * Not a terminal emulator. There is no cursor, no grid, no scrolling region; the text is drawn in
 * reading order exactly as a plain screen is, with colours attached. REQ-0010 owns anything beyond that.
 *
 * ### Palette
 *
 * zmx does not hand the formatter a palette, so the sixteen base colours come back as indices and are
 * resolved HERE, from [PALETTE] — a dark-theme default rather than agterm's actual theme, which the phone
 * cannot see. The 6×6×6 cube and the grey ramp are arithmetic and match every terminal.
 */
object Sgr {

    /** The sixteen base colours, ARGB. xterm's defaults, brightened for a dark background. */
    val PALETTE: IntArray = intArrayOf(
        0xFF000000.toInt(), 0xFFCC0000.toInt(), 0xFF4E9A06.toInt(), 0xFFC4A000.toInt(),
        0xFF3465A4.toInt(), 0xFF75507B.toInt(), 0xFF06989A.toInt(), 0xFFD3D7CF.toInt(),
        0xFF555753.toInt(), 0xFFEF2929.toInt(), 0xFF8AE234.toInt(), 0xFFFCE94F.toInt(),
        0xFF729FCF.toInt(), 0xFFAD7FA8.toInt(), 0xFF34E2E2.toInt(), 0xFFEEEEEC.toInt(),
    )

    /** Resolves a 256-colour index to ARGB: the base sixteen, then the cube, then the greys. */
    fun colour(index: Int): Int {
        val i = index.coerceIn(0, 255)
        if (i < 16) return PALETTE[i]
        if (i < 232) {
            val n = i - 16
            val r = level(n / 36)
            val g = level(n / 6 % 6)
            val b = level(n % 6)
            return argb(r, g, b)
        }
        val grey = 8 + (i - 232) * 10
        return argb(grey, grey, grey)
    }

    private fun level(step: Int): Int = if (step == 0) 0 else 55 + step * 40

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /**
     * Splits [text] into runs of one style each. Adjacent pieces with the same style are one run, so a
     * dump that resets before every word does not become a span per word. Tabs are expanded against
     * the visible column of the row, across runs, so a styled prefix does not shift what follows it.
     */
    fun parse(text: String, tabStop: Int = TAB_STOP): List<SgrRun> {
        val runs = ArrayList<SgrRun>()
        val current = StringBuilder()
        var style = SgrStyle()
        var column = 0

        fun flush() {
            if (current.isEmpty()) return
            val last = runs.lastOrNull()
            if (last != null && last.style == style) {
                runs[runs.size - 1] = SgrRun(last.text + current, style)
            } else {
                runs.add(SgrRun(current.toString(), style))
            }
            current.setLength(0)
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == ESC && i + 1 < text.length && text[i + 1] == '[' -> {
                    val end = text.indexOf('m', i + 2)
                    if (end < 0) break
                    flush()
                    style = apply(style, text.substring(i + 2, end))
                    i = end + 1
                }
                ch == '\t' -> {
                    do {
                        current.append(' ')
                        column++
                    } while (column % tabStop != 0)
                    i++
                }
                ch == '\n' -> {
                    current.append(ch)
                    column = 0
                    i++
                }
                else -> {
                    current.append(ch)
                    column++
                    i++
                }
            }
        }
        flush()
        return runs
    }

    /** Applies one SGR parameter list to [style]. Unknown codes are skipped; malformed ones stop the list. */
    internal fun apply(style: SgrStyle, params: String): SgrStyle {
        if (params.isEmpty()) return SgrStyle()
        val codes = params.split(';').map { it.toIntOrNull() ?: return style }
        var s = style
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> s = SgrStyle()
                1 -> s = s.copy(bold = true)
                2 -> s = s.copy(faint = true)
                3 -> s = s.copy(italic = true)
                4 -> s = s.copy(underline = true)
                7 -> s = s.copy(inverse = true)
                22 -> s = s.copy(bold = false, faint = false)
                23 -> s = s.copy(italic = false)
                24 -> s = s.copy(underline = false)
                27 -> s = s.copy(inverse = false)
                in 30..37 -> s = s.copy(foreground = PALETTE[code - 30])
                39 -> s = s.copy(foreground = null)
                in 40..47 -> s = s.copy(background = PALETTE[code - 40])
                49 -> s = s.copy(background = null)
                in 90..97 -> s = s.copy(foreground = PALETTE[code - 90 + 8])
                in 100..107 -> s = s.copy(background = PALETTE[code - 100 + 8])
                38, 48 -> {
                    val (colour, consumed) = extended(codes, i + 1) ?: return s
                    s = if (code == 38) s.copy(foreground = colour) else s.copy(background = colour)
                    i += consumed
                }
                else -> {}
            }
            i++
        }
        return s
    }

    /** Reads the colour after a 38 or 48: `5;N` or `2;r;g;b`. Null when the list ends early. */
    private fun extended(codes: List<Int>, at: Int): Pair<Int, Int>? = when (codes.getOrNull(at)) {
        5 -> codes.getOrNull(at + 1)?.let { colour(it) to 2 }
        2 -> if (at + 3 < codes.size) argb(codes[at + 1], codes[at + 2], codes[at + 3]) to 4 else null
        else -> null
    }

    private const val ESC = '\u001B'
    private const val TAB_STOP = 8
}

/** One style, as SGR describes it. Colours are ARGB; null means the terminal's default. */
data class SgrStyle(
    val foreground: Int? = null,
    val background: Int? = null,
    val bold: Boolean = false,
    val faint: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
)

/** A stretch of text drawn in one style. */
data class SgrRun(val text: String, val style: SgrStyle)
