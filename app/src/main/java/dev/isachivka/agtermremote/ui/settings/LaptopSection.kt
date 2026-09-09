package dev.isachivka.agtermremote.ui.settings

import android.Manifest
import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.isachivka.agtermremote.R
import dev.isachivka.agtermremote.pairing.Fingerprint
import dev.isachivka.agtermremote.pairing.PairedLaptop
import dev.isachivka.agtermremote.pairing.PairingHost
import dev.isachivka.agtermremote.settings.AskedForCamera
import dev.isachivka.agtermremote.settings.CameraAccess
import dev.isachivka.agtermremote.settings.LaptopSettings
import dev.isachivka.agtermremote.settings.cameraAccess
import dev.isachivka.agtermremote.ui.theme.AppTheme

/**
 * The **Laptop** section: whichever of the two things this phone currently needs.
 *
 * A paired phone gets the address it dials, the fingerprint it pinned and the way out. A phone with
 * no laptop gets the scanner, which is what task 23 built, and above it the one sentence a phone with
 * a permanently refused camera has never been told.
 *
 * ### It does not switch away from the receipt
 *
 * Pairing finishes inside [PairingHost] and leaves **Paired** on screen with the phone's own
 * fingerprint on it — the receipt from the Mac. This does not swap that for the panel below the
 * moment it happens, and the restraint is deliberate: the receipt is the one thing a person may want
 * to compare against the Mac's menu, and taking it away at the instant it appears would make it
 * unreadable. The panel is what they find the next time they open this screen.
 */
@Composable
fun LaptopSection(
    settings: LaptopSettings,
    store: PairedLaptop,
    modifier: Modifier = Modifier,
) {
    val laptop = settings.laptop
    if (laptop == null) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CameraBlockedNote()
            PairingHost(store = store)
        }
        return
    }

    Panel(modifier = modifier) {
        Title(R.string.settings_address_title)
        Body(R.string.settings_address_body)
        OutlinedTextField(
            value = settings.address,
            onValueChange = settings::edit,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            // Base64 is not the only case-sensitive thing on this screen: a host is displayed as the
            // bytes the payload carried, and a keyboard that capitalises the first letter produces an
            // address that differs from the one on the Mac by a byte nobody can see.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth().testTag(TAG_ADDRESS),
        )
        Button(
            onClick = { settings.save() },
            modifier = Modifier.testTag(TAG_ADDRESS_SAVE),
        ) {
            Text(stringResource(R.string.settings_address_save))
        }
        settings.note?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(TAG_NOTE),
            )
        }

        Title(R.string.settings_fingerprint_title)
        Body(R.string.settings_fingerprint_body)
        FingerprintRows(Fingerprint.rows(Fingerprint.of(laptop.bridgeCertificate)))

        Destructive(
            settings = settings,
            what = LaptopSettings.Destruction.Unpair,
            label = R.string.settings_unpair,
            tag = TAG_UNPAIR,
        )
    }
}

/**
 * The **This phone** section, which exists only while this phone's key is in the one state that has
 * no other way out.
 *
 * See `LaptopSettings.keyIsUnusable`: the verdict decides whether this is on screen and never what is
 * deleted. Rendering nothing when the key is fine is not a cosmetic choice — a destructive control
 * that is always present is one somebody eventually presses.
 */
@Composable
fun PhoneKeySection(settings: LaptopSettings, modifier: Modifier = Modifier) {
    Panel(modifier = modifier) {
        Title(R.string.settings_key_unusable_title)
        Body(R.string.settings_key_unusable_body)
        Destructive(
            settings = settings,
            what = LaptopSettings.Destruction.ReplaceKey,
            label = R.string.settings_key_replace,
            tag = TAG_REPLACE_KEY,
        )
    }
}

/**
 * One irreversible act, behind one confirmation that says what will be lost.
 *
 * **The confirmation replaces the button rather than floating over it.** A dialog would leave the
 * trigger underneath it in the view hierarchy, so *the thing that starts this* and *the thing that
 * finishes it* would be on screen at the same time — which is a second press away from an accident,
 * and which no test could tell apart. Here the two states are exclusive by construction.
 *
 * Cancel is first in the row and is the ordinary button; the destructive one carries the error
 * colour and is second. Somebody dismissing a screen by reflex hits the harmless one.
 */
@Composable
private fun Destructive(
    settings: LaptopSettings,
    what: LaptopSettings.Destruction,
    label: Int,
    tag: String,
) {
    if (settings.pending != what) {
        // Only when nothing is pending: two confirmations on one screen would be two questions with
        // one answer between them.
        if (settings.pending == null) {
            Button(
                onClick = { settings.ask(what) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.testTag(tag),
            ) {
                Text(stringResource(label))
            }
        }
        return
    }

    Text(
        text = what.warning,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(TAG_WARNING),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = settings::dismiss, modifier = Modifier.testTag(TAG_CANCEL)) {
            Text(stringResource(R.string.settings_cancel))
        }
        Button(
            onClick = settings::confirm,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.testTag(TAG_CONFIRM),
        ) {
            Text(stringResource(label))
        }
    }
}

/**
 * What a phone whose owner refused the camera for good is owed, and it is two things: what happened,
 * and where it can be undone.
 *
 * Rendered only in [CameraAccess.Blocked]. Every other state already has a screen — the viewfinder,
 * the system dialog, or the *no camera to read the code* copy on a device that has none — and a note
 * about permissions on top of any of them would be noise on the screen somebody is trying to use.
 */
@Composable
private fun CameraBlockedNote() {
    val context = LocalContext.current
    val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>()
        .firstOrNull()

    val access = cameraAccess(
        hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED,
        everAsked = AskedForCamera(context).wasAsked(),
        // No activity means nothing could have shown the dialog, so nothing can claim it was refused
        // for good. `true` reads as "asking is still on the table", which is the safe direction: it
        // withholds a sentence rather than inventing one.
        wouldAskAgain = activity == null ||
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA),
    )
    if (access != CameraAccess.Blocked) return

    Panel(modifier = Modifier.testTag(TAG_CAMERA_BLOCKED)) {
        Title(R.string.settings_camera_blocked_title)
        Body(R.string.settings_camera_blocked_body)
        Button(
            onClick = {
                // The app's own page in the system settings, which is where the toggle is. Not the
                // global privacy screen: it lists every app and leaves the owner to find this one.
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", context.packageName, null))
                        // Reached from a composable that may not be an activity context, and a
                        // non-activity context cannot start an activity without its own task.
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            modifier = Modifier.testTag(TAG_CAMERA_SETTINGS),
        ) {
            Text(stringResource(R.string.settings_camera_blocked_open))
        }
    }
}

/** The same card the pairing section is drawn on, so the two read as one screen. */
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

/**
 * Two rows of eight groups, monospace, matching what the Mac prints — and matching what the pairing
 * receipt showed, because it is the same fingerprint in the same grouping. Somebody comparing 64 hex
 * characters against another screen needs the grouping to keep their place.
 */
@Composable
private fun FingerprintRows(rows: List<String>) {
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
            )
        }
    }
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

const val TAG_ADDRESS = "settings_address"
const val TAG_ADDRESS_SAVE = "settings_address_save"
const val TAG_NOTE = "settings_note"
const val TAG_FINGERPRINT = "settings_fingerprint"
const val TAG_UNPAIR = "settings_unpair"
const val TAG_REPLACE_KEY = "settings_replace_key"
const val TAG_WARNING = "settings_warning"
const val TAG_CONFIRM = "settings_confirm"
const val TAG_CANCEL = "settings_cancel"
const val TAG_CAMERA_BLOCKED = "settings_camera_blocked"
const val TAG_CAMERA_SETTINGS = "settings_camera_open"
