package dev.isachivka.bewareofsugar.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.isachivka.bewareofsugar.R
import dev.isachivka.bewareofsugar.ui.theme.AppTheme

/**
 * Pairing, as a section of Settings.
 *
 * **The placement is provisional and REQ-0009 §4 says why.** Pairing configures a feature that does
 * not exist yet, so its eventual home is beside the terminal — the way the token screen hangs off
 * Updates rather than sitting on the launcher. It is here because there is no parent to hang it from
 * today and this is reachable and cheap to move.
 *
 * The composable is stateless and takes [PairingState] as a value, so every state is reachable from a
 * preview and from a test without a camera, a laptop, or a paired phone.
 */
@Composable
fun PairingSection(
    state: PairingState,
    camera: CameraAccess,
    onScan: () -> Unit,
    onChoose: () -> Unit,
    onConfirm: (PairingState.Comparing) -> Unit,
    onReject: () -> Unit,
    onUnpair: () -> Unit,
    onSendCertificate: () -> Unit,
    onHandoverDone: (PairingState.PhoneHalfOutstanding) -> Unit,
    onReplaceIdentity: () -> Unit,
    modifier: Modifier = Modifier,
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
        // nothing - the house rule since REQ-0005.
        when (state) {
            PairingState.NotPaired -> Unpaired(onScan, onChoose, R.string.pairing_none_body, camera)
            PairingState.NotAPairingCode -> Unpaired(onScan, onChoose, R.string.pairing_not_a_code, camera)
            PairingState.NoScreenLock -> NoScreenLock()
            is PairingState.Comparing -> Comparing(state, onConfirm, onReject)
            is PairingState.PhoneHalfOutstanding ->
                PhoneHalfOutstanding(state, onSendCertificate, onHandoverDone, onUnpair)
            is PairingState.Paired -> Paired(state, onUnpair)
            is PairingState.IdentityCannotSign -> IdentityCannotSign(state, onReplaceIdentity)
            is PairingState.IdentityMadeTheOldWay -> IdentityMadeTheOldWay(state, onReplaceIdentity)
        }
    }
}

/**
 * The key works today and was built the way that stops working.
 *
 * **A different screen from [IdentityCannotSign] on purpose.** That one reports something already
 * broken; this asks the owner to give up something that is currently fine, which is a harder thing to
 * ask and needs the reason and the price in the same breath. The copy says both: the key will stop
 * working on its own, and replacing it means the laptop stops recognising this phone until they pair
 * again.
 *
 * The fingerprint shown is the one the laptop pins TODAY, so they can see exactly what they are
 * trading away before they press anything.
 */
@Composable
private fun IdentityMadeTheOldWay(
    state: PairingState.IdentityMadeTheOldWay,
    onReplaceIdentity: () -> Unit,
) {
    Text(
        text = stringResource(R.string.pairing_identity_old_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(R.string.pairing_identity_old_body),
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.onSurfaceSubtle,
    )
    Fingerprint(state.phoneFingerprint)
    Button(onClick = onReplaceIdentity, modifier = Modifier.testTag(TAG_REPLACE_IDENTITY)) {
        Text(stringResource(R.string.pairing_identity_old_action))
    }
}

/**
 * This phone's key will not sign, so nothing it does can reach the laptop.
 *
 * **The button is the point, and so is its being a button.** Replacing the key is the only remedy, and
 * it costs the pairing: the laptop pins the certificate about to be destroyed, and no message from the
 * phone can tell it otherwise. Until 2026-07-29 the app did this by itself on the way into this
 * screen — the owner's Pixel cut a new identity at 18:28:50 and every handshake after it was refused,
 * with nothing on screen connecting the two. A cost the owner cannot see must not be paid on their
 * behalf.
 *
 * The fingerprint shown is the OLD one, still on the laptop, so they can confirm this is the pairing
 * they think it is before ending it.
 */
@Composable
private fun IdentityCannotSign(
    state: PairingState.IdentityCannotSign,
    onReplaceIdentity: () -> Unit,
) {
    Text(
        text = stringResource(R.string.pairing_identity_dead_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(R.string.pairing_identity_dead_body),
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.onSurfaceSubtle,
    )
    Fingerprint(state.phoneFingerprint)
    Button(onClick = onReplaceIdentity, modifier = Modifier.testTag(TAG_REPLACE_IDENTITY)) {
        Text(stringResource(R.string.pairing_identity_dead_action))
    }
}

/**
 * Two ways in, in the order the owner will reach for them.
 *
 * **Scan first**, as a filled button: they are standing at the laptop with the code on screen, and
 * pointing the phone at it is the whole gesture. Choosing a file is a `TextButton` beneath — still
 * present, because a dark room or a camera that will not focus must never block pairing, but no
 * longer the thing being suggested.
 */
@Composable
private fun Unpaired(onScan: () -> Unit, onChoose: () -> Unit, body: Int, camera: CameraAccess) {
    Text(
        text = stringResource(R.string.pairing_none_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(body),
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.onSurfaceSubtle,
    )
    // Present whenever the hardware is. The tap is what asks for the permission - hiding the button
    // until the permission was held made the viewfinder unreachable on every phone that had never
    // granted it, because the button was the only thing that asked. See CameraAccess.
    if (camera.offersScanning) {
        Button(onClick = onScan, modifier = Modifier.testTag(TAG_SCAN)) {
            Text(stringResource(R.string.pairing_scan))
        }
    }
    // Says what the camera was for and stops. No "open Settings" button: the owner refused something
    // on purpose, and a screen that immediately asks again in a different shape is the app arguing
    // with them. The next deliberate tap on scanning is where it asks - which is also how a
    // permission revoked in Settings comes back, with no special case for it.
    if (camera.explainsItself) {
        Text(
            text = stringResource(R.string.pairing_camera_refused),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.onSurfaceSubtle,
            modifier = Modifier.testTag(TAG_CAMERA_REFUSED),
        )
    }
    // ALWAYS, in every state above. This is the route that has actually completed a pairing, and
    // reaching it never asks for a permission. Its label stops saying "instead" when it is the only
    // way in, because a lone button offering an alternative to nothing reads as a bug.
    TextButton(onClick = onChoose, modifier = Modifier.testTag(TAG_CHOOSE_IMAGE)) {
        Text(
            stringResource(
                if (camera.offersScanning) R.string.pairing_choose_image else R.string.pairing_choose_image_only,
            ),
        )
    }
}

/**
 * The one step that depends on a human.
 *
 * Nothing has been stored at this point. Both certificates are public, so the channel never needed to
 * be private — it needed to be authentic, and this comparison is where that comes from.
 *
 * **There is no continue-anyway.** The reject action is not a cancel: it is the owner reporting that
 * the code came from somewhere other than the laptop in front of them, and offering an override would
 * make the comparison advisory.
 */
@Composable
private fun Comparing(
    state: PairingState.Comparing,
    onConfirm: (PairingState.Comparing) -> Unit,
    onReject: () -> Unit,
) {
    Text(
        text = stringResource(R.string.pairing_compare_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    // ABOVE the fingerprint, deliberately. The fingerprint is identical in every QR this laptop
    // generates - bridgecert qr reads the existing identity rather than minting one - so a code built
    // for the WRONG HOST shows a matching fingerprint and everything looks correct until nothing
    // connects. The address is the only field in the payload that discriminates, which makes it the
    // thing the owner is actually here to check. It goes first because it is the one that can be
    // wrong.
    Label(R.string.pairing_compare_address_label)
    Address(PairingAddress.of(state.profile))
    Label(R.string.pairing_compare_fingerprint_label)
    Fingerprint(state.laptopFingerprint)
    Text(
        text = stringResource(R.string.pairing_compare_body),
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.onSurfaceSubtle,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onConfirm(state) }, modifier = Modifier.testTag(TAG_CONFIRM)) {
            Text(stringResource(R.string.pairing_confirm))
        }
        TextButton(onClick = onReject, modifier = Modifier.testTag(TAG_REJECT)) {
            Text(stringResource(R.string.pairing_reject))
        }
    }
}

/**
 * No secure lock screen, so no key that requires the owner present.
 *
 * Names the remedy rather than the API. "InvalidAlgorithmParameterException" is true and useless; the
 * owner needs to know they must set a PIN, pattern or password, and that the app cannot do it for
 * them. There is no button, because the fix is in Settings and deep-linking there from an unpaired
 * state is a promise this screen cannot keep across manufacturers.
 */
@Composable
private fun NoScreenLock() {
    Text(
        text = stringResource(R.string.pairing_no_lock_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(R.string.pairing_no_lock_body),
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.onSurfaceSubtle,
    )
}

/**
 * The laptop is stored and the laptop does not know about this phone yet.
 *
 * ### Written for what the owner is physically doing
 *
 * They are at the laptop. Its screen has the pairing PNG open in Preview and its terminal has the
 * bridge fingerprint printed. They have just photographed that screen with the phone in their hand and
 * confirmed the fingerprints match. **They have not put the phone down.**
 *
 * So this screen is the second half of the same sitting, in the order they will do it:
 *
 *  1. **Send** — hands the certificate to the laptop through the share sheet, which on a Mac means
 *     AirDrop and takes seconds while both devices are in front of them.
 *  2. **The command to run**, shown here rather than left to memory, because they are about to switch
 *     to a terminal and typing it from a phone screen beats recalling it.
 *  3. **This phone's fingerprint**, so that when `bridgecert pin` prints one, there is something to
 *     compare it against — the same check in the other direction, and the only defence against
 *     pinning the wrong file out of a Downloads folder.
 *  4. **Done** — the owner's assertion, because it is the only kind available.
 */
@Composable
private fun PhoneHalfOutstanding(
    state: PairingState.PhoneHalfOutstanding,
    onSendCertificate: () -> Unit,
    onHandoverDone: (PairingState.PhoneHalfOutstanding) -> Unit,
    onUnpair: () -> Unit,
) {
    Text(
        text = stringResource(R.string.pairing_half_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(R.string.pairing_half_body),
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.onSurfaceSubtle,
    )
    Button(onClick = onSendCertificate, modifier = Modifier.testTag(TAG_SEND_CERTIFICATE)) {
        Text(stringResource(R.string.pairing_half_send))
    }
    Text(
        text = stringResource(R.string.pairing_half_command),
        style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        ),
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(R.string.pairing_half_compare),
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.onSurfaceSubtle,
    )
    Fingerprint(state.phoneFingerprint)
    Button(
        onClick = { onHandoverDone(state) },
        modifier = Modifier.testTag(TAG_HANDOVER_DONE),
    ) {
        Text(stringResource(R.string.pairing_half_done))
    }
    TextButton(onClick = onUnpair, modifier = Modifier.testTag(TAG_UNPAIR)) {
        Text(stringResource(R.string.pairing_unpair))
    }
}

@Composable
private fun Paired(state: PairingState.Paired, onUnpair: () -> Unit) {
    Text(
        text = stringResource(R.string.pairing_paired_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Fingerprint(state.laptopFingerprint)
    Text(
        text = stringResource(R.string.pairing_paired_body),
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.onSurfaceSubtle,
    )
    TextButton(onClick = onUnpair, modifier = Modifier.testTag(TAG_UNPAIR)) {
        Text(stringResource(R.string.pairing_unpair))
    }
}

/**
 * Two rows of eight groups, monospace, matching what `bridgecert` prints on the laptop.
 *
 * The layout is not cosmetic. The owner is comparing 64 hex characters against another screen, and
 * the grouping is what makes that possible without losing their place — two devices showing the same
 * bytes in different groupings is a comparison people get wrong, or abandon.
 *
 * The whole block carries one content description rather than each row carrying its own, so a screen
 * reader announces a fingerprint rather than two unrelated strings of hex.
 */
/**
 * Says which of the two values below it is which.
 *
 * Two blocks of machine-looking text stacked on a phone are one thing to a person in a hurry, and the
 * whole point of this screen is that they are not the same thing: one of them cannot fail.
 */
@Composable
private fun Label(text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.labelMedium,
        color = AppTheme.colors.onSurfaceSubtle,
    )
}

/**
 * The address the phone is about to trust, as one line the owner reads off against the laptop.
 *
 * Monospace and `softWrap`, never `TextOverflow.Ellipsis`. **A truncated host is the one shape this
 * screen may not produce**: `example.invalid` and `agterm.example.invalid` are one label apart and one
 * is a suffix of the other, so an ellipsis in the wrong place turns two different destinations into
 * the same string — which is exactly the mistake this line exists to catch. It wraps instead.
 *
 * The string comes from [PairingAddress] and is not built here, because the bridge face will render
 * the same value and two renderings of one address is a comparison the owner performs correctly and
 * still gets wrong.
 */
@Composable
private fun Address(address: String) {
    Text(
        text = address,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        softWrap = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_ADDRESS),
    )
}

@Composable
private fun Fingerprint(rows: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_FINGERPRINT)
            .semantics {
                contentDescription = rows.joinToString(" ")
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rows.forEach { row ->
            Text(
                text = row,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

const val TAG_SCAN = "pairing_scan"
const val TAG_CHOOSE_IMAGE = "pairing_choose_image"
const val TAG_CONFIRM = "pairing_confirm"
const val TAG_REJECT = "pairing_reject"
const val TAG_UNPAIR = "pairing_unpair"
const val TAG_FINGERPRINT = "pairing_fingerprint"
const val TAG_ADDRESS = "pairing_address"
const val TAG_CAMERA_REFUSED = "pairing_camera_refused"
const val TAG_VIEWFINDER = "pairing_viewfinder"
const val TAG_VIEWFINDER_TORCH = "pairing_viewfinder_torch"
const val TAG_VIEWFINDER_CANCEL = "pairing_viewfinder_cancel"
const val TAG_SEND_CERTIFICATE = "pairing_send_certificate"
const val TAG_HANDOVER_DONE = "pairing_handover_done"
const val TAG_REPLACE_IDENTITY = "pairing_replace_identity"
