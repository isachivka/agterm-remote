package dev.isachivka.agtermremote.agterm

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.isachivka.agtermremote.R
import dev.isachivka.agtermremote.ui.AppIcons
import dev.isachivka.agtermremote.ui.theme.AppTheme

/**
 * The input bar: a draft, the keys a touch keyboard does not have, and an honest status line.
 *
 * ### It is not part of the terminal, and that is the point
 *
 * The draft is drawn HERE and never inside [TerminalBox]. What the owner sees as terminal content is
 * only ever what the laptop sent back — see [TypingState] for why a local echo could not be made
 * correct even if it were wanted.
 *
 * ### Closed by default
 *
 * The owner is sending real commands to a real machine, so the bar is somewhere they went
 * deliberately. An input that is always focused is one a pocket can type into.
 *
 * ### The keys, because a touch keyboard has none of them
 *
 * Enter, Escape, Tab and ^C are not on a phone's keyboard and are most of what makes a terminal
 * usable. They are chips rather than a hidden gesture: discoverable, and hard to hit by accident. Each
 * sends one named key from the bridge's closed set — this screen cannot compose a sequence, and
 * neither can the layer beneath it.
 *
 * ### One row of them, not three
 *
 * The bar carried **fifteen** cells in three rows for a month, and after two weeks of daily use the
 * owner reported that eight of them had never once been pressed - not once, in two weeks: Ctrl-A,
 * Ctrl-E, Backspace and the arrows. Three rows of keys, more than half of them dead,
 * standing on the axis a phone has least of — and the terminal above is `weight(1f)`, so every one of
 * those rows was taken directly out of the output the owner is trying to read.
 *
 * They are **folded, not deleted**. [KEYS_UNDER_THE_FOLD] is one tap away behind the ellipsis, which is
 * the owner's own idea: hide some of the keys behind an ellipsis that lifts the panel when it is
 * tapped. A key nobody presses is worth a tap; a key that is gone is a bug report.
 *
 * The arithmetic, on the owner's phone — 448dp wide, so 424dp inside this bar's padding:
 *
 * | | rows | height | terminal lines it costs | one key |
 * |---|---|---|---|---|
 * | Before the fold | 3 | 148dp | 7.4 | 78.4dp |
 * | Folded | 1 | 44dp | 2.2 | 47.75dp |
 * | **Now, folded (the normal state)** | **1** | **44dp** | **2.2** | **65.7dp** |
 * | Now, unfolded | 3 | 148dp | 7.4 | 80dp |
 *
 * A key row is 44dp — [KEY_VERTICAL_PADDING_DP] twice around a 20sp label — plus the 8dp this Column
 * puts between its children. A terminal line is `TerminalBox`'s 20sp `LINE_HEIGHT_SP`. **The normal
 * state gives the terminal back 104dp, which is 5.2 lines of the owner's session.**
 *
 * **The last column is what the overpull changed.** The fold bought those lines by putting eight
 * cells in a row and shrinking every key 39%, and that shrink was the one thing it shipped
 * unverified. Moving `PgUp` and `PgDn` off the bar — they have a gesture now, see [TerminalPull] —
 * leaves six cells and **65.7dp a key, back above Material's 48dp minimum**, with the height saving
 * untouched.
 *
 * ### There is no close button
 *
 * Android dismisses its own keyboard, by gesture and by the back control, on every device. Ours was a
 * row of height spent duplicating the platform, on the axis a phone has least of.
 */
@Composable
fun TypingBar(
    state: TypingState,
    onDraftChange: (String) -> Unit,
    onSendText: (String) -> Unit,
    onSendKey: (String) -> Unit,
    /**
     * Types a command and then presses Return — the Claude button.
     *
     * Separate from [onSendText] because the two acts are separate calls, ordered, with the Return
     * sent only if the text landed. Defaulted so a preview or a test that does not care reads as it
     * did before.
     */
    onRunMacro: (String) -> Unit = {},
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * What is in the field. Beside [state] rather than inside it, so a key going out or a
     * report coming back leaves the owner's text exactly where it was.
     */
    draft: String = "",
) {
    if (state is TypingState.Closed) return

    // **A card, and the terminal ends where it begins.** The design gives the bar a raised container
    // with 28dp top corners so the output above it has a visible edge rather than running under a row
    // of controls. Appearance only: nothing about what this bar DOES changed with it - see the note
    // on singleLine below.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, BarShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // **A refusal about the text says so HERE, above the field, with the text still in it.**
        //
        // This used to be a full-screen error that replaced the terminal and the session
        // list and listed our internal key names, for a person who had pasted a message out of a chat
        // app. A no about what they sent is not the connection breaking, so it costs one line beside
        // the thing they can edit and nothing else.
        (state as? TypingState.Composing)?.notice?.let { notice ->
            Text(
                text = stringResource(
                    when (notice) {
                        TypingState.Notice.LineBreaks -> R.string.agterm_typing_notice_line_breaks
                        TypingState.Notice.NotTypable -> R.string.agterm_typing_notice_not_typable
                        TypingState.Notice.FileRefused -> R.string.agterm_typing_notice_file_refused
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp).testTag(TAG_TYPING_NOTICE),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // **The pill holds the field AND the attach control**, which is what the design changes
            // about this row: the picker moves inside the input rather than sitting between the field
            // and Send. The border goes amber when there is something to send.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, InputShape)
                    .border(
                        width = 1.dp,
                        color = if (draft.isEmpty()) {
                            MaterialTheme.colorScheme.outlineVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        shape = InputShape,
                    )
                    .padding(start = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f).testTag(TAG_TYPING_FIELD),
                // **Still single line, and this is the one line of this file nobody may change
                // casually.** PR #84 put a multiline field here and the owner reverted the lot, on
                // the grounds that the field had brought a pile of bugs with it. The design
                // describes how the bar LOOKS; it does not reopen what it does.
                singleLine = true,
                // A terminal is not prose. Autocorrect turning `cd` into `CD`, or capitalising the
                // first word of every command, would silently send something other than what was
                // typed - which is the same rule as refusing control characters rather than
                // stripping them.
                //
                // **KeyboardType.Text, and NOT Ascii.** Ascii was here and it took the owner's own
                // language away from them: Gboard hides the language switch on an ASCII field, so a
                // Russian speaker could not type Russian into their own terminal. Reported by them,
                // 2026-07-30.
                //
                // Restricting the IME bought nothing. Nothing downstream wants ASCII - `keys.Text`
                // accepts any valid UTF-8 that is not a control character, and the wire is UTF-8 - so
                // the restriction was a guess about what a terminal "should" take, applied to the one
                // layer that decides which keyboards a person is allowed to use.
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { onSendText(draft) }),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                placeholder = { Text(stringResource(R.string.agterm_typing_placeholder)) },
                // The pill draws the container now, so the field draws none of its own. Every
                // decoration is transparent rather than removed, because a TextField with no
                // container still owns its focus and cursor behaviour and that is what is wanted.
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                ),
            )
            // **The picker is inside the pill now, and it still does not send.** It puts the path the
            // bridge chose into the draft; the owner presses Enter. Choosing a file must never execute
            // a command, which is the same reasoning that keeps this bar shut until they open it.
            IconButton(
                onClick = onPickFile,
                modifier = Modifier.size(40.dp).testTag(TAG_TYPING_PICK_FILE),
            ) {
                Icon(
                    painter = painterResource(AppIcons.AttachFile),
                    contentDescription = stringResource(R.string.agterm_typing_pick_file),
                    tint = AppTheme.colors.onSurfaceSubtle,
                    modifier = Modifier.size(20.dp),
                )
            }
            }
            // **Send is a square outside the pill, and it is dead when there is nothing to send.** It
            // was a text button that looked like a link; the design makes it the one filled control on
            // the screen, which is what a send button should be on a phone.
            val canSend = draft.isNotEmpty()
            Surface(
                onClick = { onSendText(draft) },
                enabled = canSend,
                shape = SendShape,
                color = if (canSend) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (canSend) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    AppTheme.colors.onSurfaceSubtle.copy(alpha = 0.6f)
                },
                modifier = Modifier.size(56.dp).testTag(TAG_TYPING_SEND),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(AppIcons.ArrowUpward),
                        contentDescription = stringResource(R.string.agterm_typing_send),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        // **The fold is remembered here and nowhere else, and `remember` is deliberate.**
        //
        // A rotation with the fold open shuts it. **That is the state the bar opens in anyway**, so
        // the loss lands exactly where the design already was — the whole subtree leaves composition
        // on `TypingState.Closed`, so the bar opens folded every time regardless. The folded row is the
        // one the owner uses; opening in whatever state they left it hours ago would make the bar's
        // height unpredictable at the moment they are reaching for it.
        //
        // (An earlier comment here said the saveable variant was BANNED by a source scan. That scan
        // enforced a rule the owner never set, and it is gone. `remember` stays for the
        // reason above, not because anything forbids the alternative.)
        //
        // Not hoisted into AgtermViewModel beside `closedWorkspaces`, which is the other fold state
        // in this feature. That one survives leaving a session and coming back BY DESIGN; this one
        // must not, for the reason above.
        var unfolded by remember { mutableStateOf(false) }

        // **The hidden rows come out ABOVE the always-visible one, and that ordering is the point.**
        //
        // The bar grows upward into the terminal rather than downward under the thumb, so Esc, Enter
        // and ^C do not move when the fold opens. A control that relocates the buttons around it is
        // how a person presses Enter and gets an arrow key.
        if (unfolded) {
            KEYS_UNDER_THE_FOLD.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp),
                ) {
                    row.forEach { cell -> KeyCellButton(cell, onSendKey, onRunMacro, Modifier.weight(1f)) }
                }
            }
        }

        // **One row, six cells: five keys and the fold.**
        //
        // This was a horizontally scrolling row once, which put ^C at the cut edge and everything
        // after it off screen - useless for precisely the keys a person reaches for in a hurry. A key
        // you have to go looking for is not a key. Then it was three fixed rows of five, which put
        // every key on screen and cost the terminal seven lines to do it. Then it was one row of
        // eight, which bought those lines back by making every key 39% narrower.
        //
        // **Six is the first version of this row that is short AND wide**, and it took giving two of
        // the keys a gesture instead - see TerminalPull. 65.7dp a cell.
        //
        // Fixed cells rather than a wrapping flow, because a flow's height changes with the widest
        // label and would move the field under the owner's thumb when a label is translated.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KEY_GAP_DP.dp),
        ) {
            KEYS_ON_THE_BAR.forEach { cell ->
                KeyCellButton(cell, onSendKey, onRunMacro, Modifier.weight(1f))
            }
            // **An ellipsis, in the owner's own word for it.** Drawn as a key rather than as an icon
            // beside the field, because what it reveals is keys and it should read as one of them.
            // It is filled rather than outlined while open, so the row says which state it is in
            // without a second glyph that would have to be guessed at.
            val description = stringResource(
                if (unfolded) R.string.agterm_keys_fewer else R.string.agterm_keys_more,
            )
            Surface(
                onClick = { unfolded = !unfolded },
                shape = KeyShape,
                color = if (unfolded) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (unfolded) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                ),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = description }
                    .testTag(TAG_TYPING_FOLD),
            ) {
                Box(
                    modifier = Modifier.padding(vertical = KEY_VERTICAL_PADDING_DP.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }

        // **No arrow-key note here.** It shipped above the keyboard, the owner read it, and they
        // ruled it off the screen: it cost a block of height on the axis a phone has least of, for a
        // sentence that is only useful once. The limit itself has not changed and is documented where
        // it will be maintained - the `keys` package comment, and TerminalBox's neighbours. Putting
        // standing documentation in front of someone every time they type is how a UI gets tall.
    }
}

/**
 * One cell, drawn the same whether it is on the bar or under the fold.
 *
 * Extracted when the fold split the grid in two — the alternative was the same twenty lines written
 * twice, which is how the folded keys end up looking like a different app's buttons after the next
 * design change touches only one copy.
 */
@Composable
private fun KeyCellButton(
    cell: KeyCell,
    onSendKey: (String) -> Unit,
    onRunMacro: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tag = when (cell) {
        is KeyCell.Key -> "$TAG_TYPING_KEY${cell.name}"
        is KeyCell.Macro -> TAG_TYPING_MACRO
    }
    Surface(
        onClick = {
            when (cell) {
                is KeyCell.Key -> onSendKey(cell.name)
                is KeyCell.Macro -> onRunMacro(cell.command)
            }
        },
        shape = KeyShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.testTag(tag),
    ) {
        Box(
            modifier = Modifier.padding(vertical = KEY_VERTICAL_PADDING_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (cell) {
                is KeyCell.Key -> Text(
                    text = stringResource(cell.label),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    maxLines = 1,
                )
                // **Tint UNSPECIFIED, and that is load-bearing.** `Icon` tints with
                // LocalContentColor by default, which here is the key label colour - it would
                // silently repaint Anthropic's #D97757 the same grey as the letters beside it. The
                // owner asked for the logo to look like the logo, in its own orange rather than
                // repainted. The colour lives in the drawable; see its header.
                is KeyCell.Macro -> Icon(
                    painter = painterResource(cell.icon),
                    contentDescription = stringResource(cell.description),
                    tint = Color.Unspecified,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// **The status line used to be here, and it is gone rather than duplicated.**
//
// The owner: *"notifications still in the keyboard block, but on design it is in special place over
// terminal with x button to close"*. It now lives on the one notes surface with the connection notes -
// see Notice, where the precedence between the two sources is decided.
//
// It is DELETED here rather than left as a second place that can also say it. Two places reporting the
// same thing is how they start disagreeing, and the disagreement would be about whether a keystroke
// reached the owner's laptop.

/** The bar's own corners, in one place: the card, the input pill, Send, and one key. */
private val BarShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val InputShape = RoundedCornerShape(26.dp)
private val SendShape = RoundedCornerShape(18.dp)
private val KeyShape = RoundedCornerShape(14.dp)

/**
 * One cell of the key bar, and the reason it is a sealed type rather than a pair.
 *
 * ### A macro is not a key, and the type says so
 *
 * This was `Pair<String?, Int>`, where the string is **a name the bridge's key allowlist must
 * accept**. `internal/keys` holds a closed map and `keys.Key` refuses anything outside it by design.
 *
 * One cell types a command and presses Return. Giving it a name like `"claude"` would
 * put a string in that position which the bridge will refuse — and the refusal would arrive as a
 * keystroke that silently does nothing, with the reason four layers away in a Go error nobody holding
 * the phone can read. Two cases cannot be crossed, and the `when` that renders them is exhaustive, so
 * a third kind of cell cannot ship with no rendering behind it.
 *
 * The `String?` that used to mean *"or nothing"* is gone with the empty cell it existed for.
 */
internal sealed interface KeyCell {

    /** A key from the bridge's closed allowlist. [name] must be one `keys.Key` accepts. */
    data class Key(val name: String, @param:StringRes val label: Int) : KeyCell

    /**
     * Types [command] and then presses Return — two acts, in that order, never a newline in the text.
     *
     * [description] says what pressing it DOES rather than what it looks like: a screen reader should
     * announce "start Claude", not the name of a shape.
     */
    data class Macro(
        val command: String,
        @param:DrawableRes val icon: Int,
        @param:StringRes val description: Int,
    ) : KeyCell
}

/**
 * **The five that stay on screen, in the order a hand reaches for them.**
 *
 * These are the cells the owner did NOT name when they reported, after two weeks of daily use, which
 * buttons they had never pressed — less `pageup` and `pagedown`, which left the bar when the overpull
 * gesture replaced them. Nothing is removed; everything is in [KEYS_UNDER_THE_FOLD].
 *
 * Five, because the fold's own cell makes six, and six is what makes each key **65.7dp** wide instead
 * of the 47.75dp that eight across bought. That number is the whole reason this row got shorter: it
 * was the one thing flagged as unverified and the one thing a thumb would find first.
 */
internal val KEYS_ON_THE_BAR: List<KeyCell> = listOf(
    KeyCell.Key("escape", R.string.agterm_key_escape),
    KeyCell.Key(ENTER_KEY, R.string.agterm_key_enter),
    KeyCell.Key("tab", R.string.agterm_key_tab),
    KeyCell.Key("interrupt", R.string.agterm_key_interrupt),
    // The Claude button, asked for by name: it types the alias and presses Return. It is
    // pressed once per session rather than once per minute, and it stays on the bar anyway: it is the
    // control that STARTS the thing this app exists to talk to, and a person opening the bar on a
    // fresh session should not have to go looking for it.
    //
    // A Macro rather than a Key, because `claude_yolo` is not a name the bridge's key allowlist has
    // or should have. See KeyCell.
    KeyCell.Macro(CLAUDE_COMMAND, AppIcons.Claude, R.string.agterm_macro_claude),
)

/**
 * **The ten behind the ellipsis. Hidden — not deleted, and the difference is the whole rule.**
 *
 * Reported by the owner on 2026-08-21, from two weeks of using the thing daily: he had not once
 * pressed Ctrl-A, Ctrl-E, Backspace or the arrows. Seven of these are theirs by name. The eighth is
 * `killline` — ^U — and that one is a judgement rather than a report: it is the last member of the
 * line-editing family whose other three they named, and a line-editing key on a bar whose owner does
 * no line editing is a cell spent on nothing. **If they press it, it comes back to the front row;
 * that is a label change, not a release.**
 *
 * ### Why a fold and not a deletion
 *
 * `Ctrl-D` was taken off this bar AND out of the bridge's allowlist in one go on 2026-07-31, on the
 * same kind of evidence — the owner did not know what it was for. That was a release away from being
 * undone. **The arrows are how a Claude Code menu is answered**, which is a thing the owner may not
 * have done from the phone yet rather than a thing they will never do; two weeks of use is evidence
 * about frequency and not about need. So: one tap, and the bridge's allowlist is untouched.
 *
 * **Ten cells over two rows of five**, not one row of ten: ten across would be 37dp a cell and `PgUp`
 * needs 33.7 of it, which is the squeeze this whole change exists to undo. Five across is 80dp, the
 * widest key the bar has ever had — and these are the keys reached for least often, so they are the
 * ones that can afford the space.
 *
 * Unfolded the bar is three key rows, which is what it was before the fold. Folded — the state it
 * opens in, every time — it is one.
 */
internal val KEYS_UNDER_THE_FOLD: List<List<KeyCell>> = listOf(
    listOf(
        KeyCell.Key("left", R.string.agterm_key_left),
        KeyCell.Key("up", R.string.agterm_key_up),
        KeyCell.Key("down", R.string.agterm_key_down),
        KeyCell.Key("right", R.string.agterm_key_right),
        KeyCell.Key("backspace", R.string.agterm_key_backspace),
    ),
    // **PgUp and PgDn joined this row on 2026-08-22, and they are the only two here that were not
    // folded for being unused.** They were folded because they got a better control: an overpull of
    // the terminal itself now sends them - see TerminalPull. The buttons stay because the gesture is
    // new and unproven on a real thumb, and taking away the working control on the same day as
    // offering an untested one is how a person ends up with neither.
    listOf(
        KeyCell.Key("pageup", R.string.agterm_key_pageup),
        KeyCell.Key("pagedown", R.string.agterm_key_pagedown),
        KeyCell.Key("linestart", R.string.agterm_key_linestart),
        // **^E replaced Ctrl-D here on 2026-07-31**, at the owner's word. It is the twin of the ^A
        // beside it - the two ends of a line.
        KeyCell.Key("lineend", R.string.agterm_key_lineend),
        KeyCell.Key("killline", R.string.agterm_key_killline),
    ),
)

/**
 * **Every cell the bar can show, folded or not.** The union, and the thing the invariants are asserted
 * against.
 *
 * It stays a `List<List<KeyCell>>` and it stays named `KEY_ROWS` because that is what the guards in
 * `KeyRowsTest` read: every key on it is one the bridge allows, no key appears twice, exactly one
 * macro. **A key moving behind the fold must not fall out of those checks** — hiding is a layout
 * decision, and the moment it starts deciding what the bridge is asked for, it has become a deletion
 * wearing a fold's clothes.
 *
 * Still a subset of what the bridge accepts, not the whole set: `home`, `end`, `delete` and `suspend`
 * are known to the allowlist and offered by nothing. Adding one is adding a line above and a string;
 * the bridge usually already knows it.
 */
internal val KEY_ROWS: List<List<KeyCell>> = listOf(KEYS_ON_THE_BAR) + KEYS_UNDER_THE_FOLD

/**
 * A key cell's vertical padding, and the reason it is a named constant now.
 *
 * A row's height is this twice plus a 20sp label — 44dp — and the fold's whole claim is arithmetic
 * over that number: three rows to one gives the terminal 104dp, which is 5.2 of `TerminalBox`'s 20sp
 * lines. A literal `12.dp` in two places is a claim nobody can grep for.
 */
private const val KEY_VERTICAL_PADDING_DP = 12

/**
 * The gap between key cells. **6dp rather than the 8dp everything else in this bar uses.**
 *
 * It arrived when the bar was eight cells across and 2dp a cell was the difference between a label
 * with room around it and one touching its own border. **The squeeze is gone and the 6dp stays**, for
 * the opposite reason: with six cells it is no longer rescuing anything, it is simply 1.7dp of extra
 * key — 65.7dp against 64.0 — on the axis a thumb is worst at. Spending it on the gap instead would
 * be spending it on nothing anyone touches.
 */
private const val KEY_GAP_DP = 6

const val TAG_TYPING_FIELD = "agterm_typing_field"
const val TAG_TYPING_NOTICE = "agterm_typing_notice"
const val TAG_TYPING_SEND = "agterm_typing_send"
const val TAG_TYPING_CLOSE = "agterm_typing_close"
const val TAG_TYPING_KEY = "agterm_typing_key_"

/**
 * The Claude button.
 *
 * It replaces `TAG_TYPING_KEY_RESERVED`, which named the empty Spacer in this cell. Checked before
 * removing rather than after: `grep` found exactly one use of that tag, its own `testTag` call, so no
 * assertion went quiet with it.
 *
 * Its own tag rather than `$TAG_TYPING_KEY$name`, because it is not a key and has no key name — which
 * is the whole point of [KeyCell].
 */
const val TAG_TYPING_MACRO = "agterm_typing_macro"
/**
 * The ellipsis — the cell that brings [KEYS_UNDER_THE_FOLD] out and puts them back.
 *
 * Its own tag rather than a key name, for the same reason the macro has one: it presses nothing on the
 * owner's laptop. It is the only control on this bar that changes the bar's own height.
 */
const val TAG_TYPING_FOLD = "agterm_typing_fold"
const val TAG_TYPING_OPEN = "agterm_typing_open"
const val TAG_TYPING_PICK_FILE = "agterm_typing_pick_file"
