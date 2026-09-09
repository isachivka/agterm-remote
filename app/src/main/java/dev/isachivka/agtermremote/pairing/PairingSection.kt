package dev.isachivka.agtermremote.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.isachivka.agtermremote.R
import dev.isachivka.agtermremote.ui.theme.AppTheme

/**
 * The pairing screen: five states, and one way into the pairing from either route.
 *
 * ### Stateless, and the [viewfinder] slot is why
 *
 * It takes [PairingUi] as a value and the camera as a **slot**, so every state renders in a test and
 * in a preview without a camera, a Mac, or a paired phone. The host fills the slot with
 * [PairingViewfinder]; a test fills it with a button.
 *
 * ### One callback, both routes
 *
 * [onCode] is what the Pair button calls, and it is what is handed to the [viewfinder] slot. **The
 * scanner does not get its own path into the pairing** — the text a symbol carried and the text a
 * person pasted arrive at the same place, are read by the same decoder, and fail the same way. That is
 * a property of this file, and `PairingScreenTest` drives both routes through it and compares what
 * came out.
 *
 * ### The paste field is always there
 *
 * Not only when the camera is refused. A scanner that will not read leaves the owner with no way
 * forward, and the payload is base64 text precisely so that it can be sent through any channel they
 * already have and pasted in. Reaching it needs no permission at all.
 */
@Composable
fun PairingSection(
    state: PairingUi,
    onCode: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    viewfinder: @Composable (onText: (String) -> Unit) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Exhaustive, so a new state has to decide what it looks like rather than silently render
        // nothing, which is the house rule.
        when (state) {
            PairingUi.Scanning -> {
                Title(R.string.pairing_scan_title)
                viewfinder(onCode)
                Paste(onCode)
            }
            PairingUi.NeedsCamera -> {
                Title(R.string.pairing_no_camera_title)
                Body(R.string.pairing_no_camera_body)
                Paste(onCode)
            }
            PairingUi.Working -> Working()
            is PairingUi.Failed -> Failed(state, onRetry)
            is PairingUi.Paired -> Paired(state)
        }
    }
}

/**
 * The route that needs nothing but a way to get the text onto the phone.
 *
 * The field holds its own value in a plain `remember`, **never `rememberSaveable`**: the payload
 * carries a live enrolment token for as long as the window is open, and `rememberSaveable` writes what
 * it holds into `savedInstanceState`, which the system persists to disk and which turns up in bug
 * reports. Losing a half-typed code to a rotation is the cheaper of the two.
 *
 * Autocorrect and capitalisation are off. Base64 is case-sensitive and a keyboard that helpfully
 * capitalises the first letter produces a code that will not decode, with nothing on screen to explain
 * why.
 */
@Composable
private fun Paste(onCode: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    Text(
        text = stringResource(R.string.pairing_paste_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Body(R.string.pairing_paste_body)
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = false,
        maxLines = 4,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_PASTE_FIELD),
    )
    Button(
        onClick = { onCode(text) },
        // A pairing attempt with nothing in the field would be a refusal the owner caused by pressing
        // an enabled button, which reads as the app being broken rather than as nothing having been
        // entered.
        enabled = text.isNotBlank(),
        modifier = Modifier.testTag(TAG_PAIR),
    ) {
        Text(stringResource(R.string.pairing_pair))
    }
}

/**
 * The phone is talking to the Mac and nothing has been stored yet.
 *
 * No cancel button. The exchange is one line out and one line in with a two-second deadline at the far
 * end, so a cancel would race the answer — and cancelling after the Mac has already pinned this phone
 * would leave the two disagreeing about whether they are paired, which is the one state this design
 * has no way to detect or repair.
 */
@Composable
private fun Working() {
    Title(R.string.pairing_working_title)
    Body(R.string.pairing_working_body)
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag(TAG_WORKING))
}

/**
 * It did not work, and the sentence is the content.
 *
 * **The button is not decoration.** A failure the owner cannot act on is the defect this project keeps
 * meeting; Try again is what makes the sentence's advice reachable, and it returns to whichever route
 * was available — the viewfinder, or the paste field on a phone with no camera to offer.
 */
@Composable
private fun Failed(state: PairingUi.Failed, onRetry: () -> Unit) {
    Title(R.string.pairing_failed_title)
    Text(
        text = state.reason,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(TAG_FAILURE_REASON),
    )
    Button(onClick = onRetry, modifier = Modifier.testTag(TAG_TRY_AGAIN)) {
        Text(stringResource(R.string.pairing_try_again))
    }
}

/**
 * Paired, and the fingerprint is a receipt rather than a task.
 *
 * It is what the bridge stored for this phone, so a person who wants to check this screen against the
 * Mac's menu can. **Nothing waits on them doing it** — the design this replaces made a human
 * comparison the authenticity mechanism, and enrolment moved that into the handshake.
 */
@Composable
private fun Paired(state: PairingUi.Paired) {
    Title(R.string.pairing_paired_title)
    Body(R.string.pairing_paired_body)
    Fingerprint(Fingerprint.rows(state.fingerprint))
}

@Composable
private fun Title(text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun Body(text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.onSurfaceSubtle,
    )
}

/**
 * Two rows of eight groups, monospace, matching what the Mac prints.
 *
 * The layout is not cosmetic. Somebody comparing 64 hex characters against another screen needs the
 * grouping to keep their place, and two devices showing the same bytes in different groupings is a
 * comparison people get wrong, or abandon.
 *
 * The whole block carries one content description rather than each row carrying its own, so a screen
 * reader announces a fingerprint rather than two unrelated strings of hex.
 */
@Composable
private fun Fingerprint(rows: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_FINGERPRINT)
            .semantics { contentDescription = rows.joinToString(" ") },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rows.forEach { row ->
            Text(
                text = row,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Spelled with a hyphen because the plan spells it so, and a test types it. */
const val TAG_PASTE_FIELD = "paste-field"
const val TAG_PAIR = "pairing_pair"
const val TAG_TRY_AGAIN = "pairing_try_again"
const val TAG_FAILURE_REASON = "pairing_failure_reason"
const val TAG_WORKING = "pairing_working"
const val TAG_FINGERPRINT = "pairing_fingerprint"
const val TAG_VIEWFINDER = "pairing_viewfinder"
const val TAG_VIEWFINDER_TORCH = "pairing_viewfinder_torch"
