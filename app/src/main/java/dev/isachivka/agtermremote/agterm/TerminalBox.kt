package dev.isachivka.agtermremote.agterm

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.isachivka.agtermremote.R
import dev.isachivka.agtermremote.ui.AppIcons
import kotlin.math.roundToInt

/**
 * The owner's terminal, on a phone, without becoming something else.
 *
 * ### The spec, in the owner's words
 *
 * *"A terminal should be a terminal"*, agterm is the transport, and **this must not turn terminal
 * output into a chat UI**. Also: *not a big wide terminal on a phone* — it must be **scaled to a large
 * font size**, and **tables and other elements must still work**.
 *
 * ### The tension, which is the actual design problem
 *
 * Those two pull against each other and no layout satisfies both by cleverness. The arithmetic:
 * the bridge's own bound describes a **276-column** terminal, and the owner's phone gives this box
 * 440dp after [HORIZONTAL_PADDING_DP] either side. [TerminalFontFamily] at [FONT_SIZE_SP]sp measures
 * **9.78dp per character on that phone** — 22px at density 2.25, which is Menlo's 0.60205 em advance
 * grid-fitted to a whole pixel. That is **45 columns visible** against 276 produced: a full screen of
 * output is six phone-widths across.
 *
 * (Two corrections live in this paragraph rather than being deleted, because the numbers it used to
 * carry are still quoted elsewhere. It said **10.84dp and 37 columns**, which was Menlo at **18**sp;
 * [FONT_SIZE_SP] has been 16 since the step-down, where the font file alone gives 9.63dp and the
 * phone actually renders 9.78. It also said `FitToPhoneTest` derives the column count — that test
 * exists to keep the arithmetic **deleted**; the count is no longer computed on the phone at all.
 * The laptop answers with the columns its terminal really rendered — see [FitToPhone].
 *
 * The other history is worth keeping: this said 39 until 2026-07-29 and **35 until 2026-07-30**. The
 * 35 was the owner's complaint — horizontal padding was 12dp, 24dp gone before a character is drawn,
 * subtracted and then floored, so the phone asked the laptop for a width that left the gap on the
 * right they photographed. At 4dp the padding no longer costs a column.)
 *
 * So one of four things has to give, and three of them are refusals:
 *
 * | | |
 * |---|---|
 * | Shrink the font until 276 columns fit | ~1.5dp per character. Unreadable, and it is the one thing the owner ruled out by name. |
 * | Reflow lines to the screen width | **Destroys every table**, which is the other thing they ruled out by name. A wrapped table is not a smaller table, it is noise. |
 * | Reformat output into cards or bubbles | This is the chat UI. It is the thing this box exists not to be. |
 * | **Keep the grid and let the viewport move over it** | **Chosen.** Costs horizontal panning on wide output. |
 *
 * **What was traded: reading wide output now requires panning sideways.** That is a real cost and it
 * is paid on purpose, because the alternatives each destroy something the owner asked for, and a
 * column that has moved is recoverable by scrolling while a column that has been reflowed away is not.
 *
 * ### Why the whole block scrolls as one, and not each line
 *
 * **This is the detail the feature lives or dies on.** The text is laid out as a *single* [Text] with
 * `softWrap = false` inside *one* horizontally scrollable container, so every line shares one layout
 * pass, one font, and one horizontal offset. Column *n* is therefore at the same x on every row, and
 * stays there while panning — which is what "tables still work" actually means.
 *
 * Per-line scrolling, or per-line `Text` composables, would each be a reasonable-looking refactor and
 * would silently break exactly that: rows would drift out of alignment and a table would come apart
 * while every individual line still looked correct.
 *
 * ### What this is not
 *
 * No cursor, no selection model, no zoom, no anchoring, no scrollback virtualisation. The transport
 * cannot supply the first two — `session.text` returns plain text by design — and the rest belong to
 * a terminal emulator, which is REQ-0010 and not this. PLAN-0009 names this box staying plain as the
 * acceptance criterion, because the temptation to start that milestone early lands here.
 *
 * **Colour is the one exception, and it is opt-in.** Since 2026-09-05 the owner can ask, in Settings,
 * for each pane to be read through its zmx daemon instead of `session.text`; the bridge then sends
 * SGR, and [styled] hands the text to [Sgr] before it is drawn. Off, this box is exactly what it was.
 * `docs/qa/why-the-terminal-has-no-colour.md` records the other route, inside agterm itself.
 */
@Composable
fun TerminalBox(
    text: String,
    modifier: Modifier = Modifier,
    /**
     * [text] carries SGR sequences — the bridge read it through zmx — and is parsed by [Sgr] before it
     * is drawn. False for a plain read, which is every read until the owner turns the styled screen
     * on in Settings, and the fallback the bridge takes when a pane has no daemon.
     */
    styled: Boolean = false,
    // Hoisted so the reading position can outlive a rotation - see AgtermViewModel. Defaulted so a
    // preview or a test that does not care about configuration change reads exactly as before.
    vertical: ScrollState = rememberScrollState(),
    horizontal: ScrollState = rememberScrollState(),
    // **The VIEWPORT the text is clipped to, in dp — the thing that cannot grow with content.** Not a
    // screen width minus a padding constant, which is how our own edits used to move the number while
    // the truth stayed put; and not the laid-out node, which is how it came to report the width of the
    // text instead of the width of the phone. Zero means "we do not know", never "nothing fits".
    onMeasured: (Double) -> Unit = {},
    /**
     * Sends one key when the terminal is overpulled past the threshold — `pageup` or `pagedown`, and
     * nothing else. REQ-0029, and see [TerminalPull] for the direction, which is settled.
     *
     * Defaulted to nothing so a preview or a test that does not care reads as it did before, and so
     * this box still has exactly one job when nobody wires it up.
     */
    onPageKey: (String) -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // **Horizontal and vertical are NOT the same number, and that is the point.** Horizontal
            // padding is measured in COLUMNS: every 9.78dp of it is a character of the owner's
            // terminal thrown away before anything is drawn, on the screen whose whole complaint is
            // that wide output does not fit. Vertical padding costs nothing they can see, and text
            // hard against the top and bottom edges reads as a rendering fault.
            //
            // **OUTSIDE the measurement below, and outside the scrollers.** Taking `maxWidth` as the
            // usable width is only true if nothing eats into it afterwards. The cost, named before it
            // shipped: the 4dp gutter no longer scrolls away with the content, it stays at the edges.
            .padding(horizontal = HORIZONTAL_PADDING_DP.dp, vertical = VERTICAL_PADDING_DP.dp),
    ) {
        // **The viewport, measured as a CONSTRAINT rather than as a laid-out node.**
        //
        // This is the defect of 2026-07-30, and of attempts one through three wearing other clothes.
        // `onSizeChanged` used to sit at the end of the chain below `horizontalScroll`, so it observed
        // the node the scroller measures with unbounded width — the CONTENT. The phone told the laptop
        // its box was 1706dp, the laptop widened the owner's window to the 159 columns that implies,
        // the text got wider, and the next press reported wider still. Every log line said success.
        //
        // `maxWidth` here is what the PARENT permits, decided before this subtree is measured. Content
        // cannot enlarge it — not by where this call sits, but by the direction data flows. That is
        // why this is a `BoxWithConstraints` and not the old modifier moved three lines up: moving it
        // up fixes today and leaves a refactor free to move it back down.
        // **clipToBounds because the overpull moves the content out of this box**, and without it the
        // terminal would draw over the input bar below while a pull is held. A draw modifier only: it
        // does not touch the constraints `maxWidth` is read from, which is the measurement this whole
        // composable exists to get right.
        BoxWithConstraints(modifier = Modifier.clipToBounds()) {
            // **An unbounded width means a scroller is above us, and the only honest answer then is
            // that we do not know.** Zero is not a fallback width: `AgtermScreen` reads `<= 0` as
            // unmeasured, disables the control and puts the reason on the owner's screen. So the
            // refactor that reintroduces this bug produces a visibly dead control the owner reports in
            // a sentence, instead of a laptop-width resize that six days of logs call success.
            //
            // `TerminalBoxMeasurementTest.insideAScrollerItRefusesToGuess` measures this composable
            // inside a `horizontalScroll` and asserts the zero. Without that test this paragraph is a
            // comment, and a comment is a claim whose expiry nobody notices.
            val usable = if (constraints.hasBoundedWidth) maxWidth.value.toDouble() else 0.0
            LaunchedEffect(usable) { onMeasured(usable) }

            // **The overpull, REQ-0029.** All of the deciding is in TerminalPull, which is a pure
            // object with unit tests; what is here is the three things only a composition can do -
            // collect the deltas the scrollers refused, move the content, and draw the icon.
            val thresholdPx = with(LocalDensity.current) {
                TerminalPull.THRESHOLD_TRAVEL_DP.dp.toPx()
            }
            val pull = remember { mutableStateOf(0f) }
            val overpull = remember(thresholdPx, onPageKey) {
                object : NestedScrollConnection {
                    // **The unwinding half, and its absence was a shipped bug.** A reversal gives the
                    // child somewhere to scroll again, so it consumes the whole delta and onPostScroll
                    // below is handed zero - the pull could climb and never come down, and the
                    // terminal stayed displaced while the content scrolled underneath it. While a pull
                    // is standing, a delta pushing back at it is spent here FIRST and only the
                    // remainder goes down.
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (source != NestedScrollSource.UserInput) return Offset.Zero
                        val unwound = TerminalPull.unwind(pull.value, available.y)
                        if (unwound.consumed == 0f) return Offset.Zero
                        pull.value = unwound.pull
                        return Offset(0f, unwound.consumed)
                    }

                    // **onPostScroll, so this only ever sees what the terminal could NOT use.** A
                    // view with somewhere to scroll consumes the whole delta and nothing arrives
                    // here, which is what makes the gesture arm itself at the limit with no test for
                    // being at the limit written anywhere.
                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        // Only a finger. A fling's leftover would otherwise accumulate after the
                        // hand had gone and fire on the NEXT lift, which is a key the owner did not
                        // ask for arriving a gesture late.
                        if (source != NestedScrollSource.UserInput || available.y == 0f) {
                            return Offset.Zero
                        }
                        pull.value = TerminalPull.accumulate(pull.value, available.y)
                        // Claimed, so the platform's own edge glow does not also draw on it.
                        return Offset(0f, available.y)
                    }

                    // The finger has left the glass: this is where a pull becomes a keystroke or
                    // becomes nothing.
                    override suspend fun onPreFling(available: Velocity): Velocity {
                        val key = TerminalPull.released(pull.value, thresholdPx)
                        if (key != null) {
                            pull.value = 0f
                            onPageKey(key)
                            // Eaten: the fling that would have followed belongs to a gesture that
                            // has just been spent on something else.
                            return available
                        }
                        // Short of the threshold. **Settles rather than snaps** - the owner asked for
                        // the terminal to come back, and a jump to zero reads as a glitch.
                        val from = pull.value
                        if (from != 0f) {
                            animate(initialValue = from, targetValue = 0f) { value, _ ->
                                pull.value = value
                            }
                        }
                        return Velocity.Zero
                    }
                }
            }
            val progress = TerminalPull.progress(pull.value, thresholdPx)
            if (progress > 0f) {
                // **The words, not a chevron** - the owner: *"вместо иконочки со стрелочкой просто
                // будем рисовать pgup pgdn чтобы было предельно понятно что происходит"*.
                //
                // A chevron has to be interpreted, and it can mean three different things: the key
                // that will fire, the way the content is about to move, or the way the finger went.
                // Three reports in a row turned on which one it meant. A label cannot be read two
                // ways.
                //
                // **The SAME string resources the folded keys use.** Not new ones and not a literal,
                // so the cell behind the ellipsis and the indicator over the terminal are physically
                // incapable of disagreeing about what this key is called, in any language.
                //
                // One value decides the label AND which end it sits at - see TerminalPull.pulling,
                // which exists because these were separate expressions over the same sign and drifted.
                val says = TerminalPull.pulling(pull.value)
                PullIndicator(
                    label = stringResource(
                        if (says.key == TerminalPull.PAGE_UP) {
                            R.string.agterm_key_pageup
                        } else {
                            R.string.agterm_key_pagedown
                        },
                    ),
                    // **A boolean, and it never meets the ramp.** Passed separately from `alpha` so
                    // there is no expression anywhere in which a fraction could reach the checkmark.
                    armed = TerminalPull.armed(pull.value, thresholdPx),
                    alpha = progress,
                    modifier = Modifier
                        .align(if (says.atTop) Alignment.TopCenter else Alignment.BottomCenter)
                        .padding(PULL_LABEL_PADDING_DP.dp)
                        .testTag(TAG_TERMINAL_PULL),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // The content follows the finger, at TerminalPull's resistance. This is what the
                    // owner meant by the terminal travelling further than it should.
                    .offset { IntOffset(0, TerminalPull.travel(pull.value).roundToInt()) }
                    // ABOVE the scrollers in the chain, so it is their parent and sees what they
                    // leave. Below them it would see nothing at all.
                    .nestedScroll(overpull)
                    // Both on the same container, so the grid moves as one piece under a viewport
                    // rather than as rows that can drift apart.
                    .verticalScroll(vertical)
                    .horizontalScroll(horizontal),
            ) {
                // **The whole feature, and deliberately nothing more.** The owner asked to copy text
                // out of a session; they also said what they wanted built — the text becomes
                // available to the finger and Android's own machinery does the rest. So: no handles
                // of ours, no magnifier, no floating menu, no copy button. All of that exists in the
                // platform, behaves the way every other app behaves, and is what their thumb already
                // knows. REQ-0026.
                //
                // **Why this wraps the Text and not the scrolling Box.** The handles belong to the
                // text, not to the viewport: a container around the scrollers would put selection
                // around a window onto the text and leave the handles behind when it scrolls.
                //
                // **And it does not cost the scroll.** Read out of foundation 1.11.4 itself — the
                // touch path in `SelectionGesturesKt` is built on `awaitLongPressOrCancellation`,
                // `getLongPressTimeoutMillis` and `withTimeoutOrNull`, so selection's drag begins
                // only after a long press has fired. A plain drag crosses touch slop long before
                // that and belongs to `verticalScroll`/`horizontalScroll`, exactly as it does today.
                // Nothing here claims long-press either: the terminal had no long-press of its own.
                // The default colour the plain screen has always used, and the ground the box sits
                // on; the styled path reads both so an SGR-less run and an inverse run land on the
                // same two colours the plain path draws with.
                val ink = MaterialTheme.colorScheme.onSurfaceVariant
                val ground = MaterialTheme.colorScheme.background
                // The four faces, so a bold run is drawn with the bold file rather than asked for by
                // a weight the wrapped family would ignore - see terminalFaces.
                val faces = terminalFaces(LocalContext.current)
                val drawn: AnnotatedString = remember(text, styled, ink, ground, faces) {
                    if (styled) SgrText.annotate(text, ink, ground, faces) else AnnotatedString(expandTabs(text))
                }
                SelectionContainer {
                    Text(
                        text = drawn,
                        style = TextStyle(
                            fontFamily = TerminalFontFamily,
                            fontSize = FONT_SIZE_SP.sp,
                            // Tight rather than the default multiplier: vertical space is the scarcer
                            // axis once the font is this large, and a terminal's rows are its own
                            // unit of meaning.
                            lineHeight = LINE_HEIGHT_SP.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // The two together are what keep the grid: no wrapping, and no ellipsis
                        // standing in for characters that are merely off-screen.
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                        modifier = Modifier.testTag(TAG_TERMINAL_TEXT),
                    )
                }
            }
        }
    }
}

/**
 * The overpull's indicator: a label that fades, and a checkmark that does not.
 *
 * ### Two channels, and they must not leak into each other
 *
 * [alpha] is the ramp and answers *how far*. [armed] is a boolean and answers *now*. The owner ruled
 * out a colour for the second — *"давай не цвет, давай перед текстом добавим иконку галочки"* — and
 * ruled on how it must behave: *"она или полностью видна, или полностью не видна."*
 *
 * **So the checkmark is not drawn at all unless armed, and its tint carries no alpha.** Not "drawn at
 * alpha 1", which would be the same picture by a route that a later edit could quietly turn into a
 * fraction. There is no expression in this function where [alpha] and the icon meet.
 *
 * The trap this shape avoids is on the way BACK. At the instant the checkmark appears, progress is 1
 * and the ramp happens to be at full opacity too, so an icon wrongly wired to [alpha] looks perfect —
 * and reveals itself only when he pulls below the threshold and the tick fades out with the label
 * instead of vanishing.
 *
 * ### The checkmark must not move the label, and the label stays centred
 *
 * *"он у тебя сейчас центрован, вот это надо сохранить."* Both halves of that, and the obvious fix
 * satisfies one while quietly breaking the other: reserving a permanent slot for the icon means
 * nothing moves, and the label then sits half an icon right of centre for ever.
 *
 * **So this reports the LABEL's size as its own**, and places the tick at a negative x — beside the
 * text, outside the bounds anything else measures. The parent centres a box the size of the label, in
 * both states, so adding or removing the tick cannot move it by a pixel. A `Row` could not do this: a
 * row's width is its children's, which is precisely the thing that must not happen here.
 *
 * **`internal` rather than private so the layout claim can be ASSERTED rather than described.** The
 * centring is the entire request and it is the kind of claim that reads as obviously true in review and
 * is wrong on a device; `TerminalPullIndicatorTest` composes this directly and compares the label's
 * bounds with the tick and without it.
 */
@Composable
internal fun PullIndicator(label: String, armed: Boolean, alpha: Float, modifier: Modifier = Modifier) {
    Layout(
        modifier = modifier,
        content = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
            // **Composed only when armed**, which is the strongest form of "fully visible or fully
            // invisible": there is no instance of it to be given a fraction.
            if (armed) {
                Icon(
                    painter = painterResource(AppIcons.Check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(CHECK_DP.dp),
                )
            }
        },
    ) { measurables, constraints ->
        val text = measurables[0].measure(constraints)
        // Unbounded, because the tick is placed outside this layout and must not be squeezed by a
        // width that was measured for the label.
        val tick = measurables.getOrNull(1)?.measure(Constraints())
        val gap = CHECK_GAP_DP.dp.roundToPx()

        // **The reported size is the label's alone.** This one line is the whole centring guarantee.
        layout(text.width, text.height) {
            text.place(0, 0)
            tick?.place(-(tick.width + gap), (text.height - tick.height) / 2)
        }
    }
}

/**
 * Turns tabs into spaces on eight-column stops.
 *
 * **A tab is not a fixed number of characters, and that is the problem.** The renderer advances to
 * wherever it thinks the next stop is, which is not necessarily where the program that produced the
 * output thought — so a tab-aligned table lays out correctly in the terminal and comes apart here,
 * with no error and nothing to point at. Expanding them against the same eight-column rule terminals
 * use makes the column arithmetic ours rather than the text renderer's.
 *
 * Counted per line, because a stop is measured from the start of its own row.
 */
internal fun expandTabs(text: String, stop: Int = TAB_STOP): String {
    if (!text.contains('\t')) return text
    return text.lineSequence().joinToString("\n") { line ->
        val out = StringBuilder(line.length)
        for (ch in line) {
            if (ch == '\t') {
                do out.append(' ') while (out.length % stop != 0)
            } else {
                out.append(ch)
            }
        }
        out.toString()
    }
}

/**
 * Large on purpose: the owner asked for it, and it is what makes the panning trade necessary.
 *
 * **16 since 2026-07-31**, 18 before that, at their request - one step down, no more. It is a real
 * change to the width feature and not a cosmetic tweak: a narrower character means more columns fit
 * the same box, so the bridge's cache key carries the measured character width and a font change
 * calibrates once instead of silently reusing a number that was true of a different font.
 */
private const val FONT_SIZE_SP = 16
/** Follows the font down by the same step, so the rows stay as tight relative to the text as before. */
private const val LINE_HEIGHT_SP = 20
/**
 * Horizontal inset, in dp, and **it is measured in characters**.
 *
 * Four either side costs 8dp — three quarters of one column. Twelve either side cost 24dp, which is
 * 2.2 columns, and back when the phone still computed a column count itself — subtracting this and
 * then flooring — the owner lost two whole characters and saw the gap on the right of their screen.
 *
 * It is now subtracted by the LAYOUT rather than by arithmetic: this padding sits outside the node
 * whose constraint is measured, so the width reported upward is already net of it.
 *
 * `FitToPhoneTest` fails if this and [FitToPhone.TERMINAL_HORIZONTAL_PADDING_DP] stop agreeing. That
 * guard exists because these two drifting apart is what produced the wrong 39 that stood for months.
 */
private const val HORIZONTAL_PADDING_DP = 4

/**
 * Vertical inset, in dp. Deliberately still 12, and deliberately a different constant.
 *
 * Vertical space is not measured in columns, so this buys legibility for nothing. Collapsing the two
 * back into one number would quietly cost the owner two characters again, in whichever direction the
 * survivor happened to be set.
 */
private const val VERTICAL_PADDING_DP = 12
internal const val TAB_STOP = 8

/**
 * How far the overpull's label sits from the edge it is pinned to.
 *
 * It replaced a 28dp icon size when the chevron became words. The label needs clearance rather than a
 * box: pinned hard against the edge it reads as part of the terminal's own output.
 */
private const val PULL_LABEL_PADDING_DP = 12

/** The checkmark's size, a little under the label's cap height so it reads as a mark and not a button. */
private const val CHECK_DP = 16

/** The space between the tick and the word. Layout-free: it is part of the tick's negative offset. */
private const val CHECK_GAP_DP = 4

/**
 * The overpull indicator, REQ-0029.
 *
 * Present only while a pull is in progress, so an instrumented test can assert that an ordinary scroll
 * never brings it into existence — which is the claim that the gesture cannot fire by accident.
 */
internal const val TAG_TERMINAL_PULL = "agterm_terminal_pull"
