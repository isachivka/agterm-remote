package dev.isachivka.bewareofsugar.pairing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import dev.isachivka.bewareofsugar.R
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat

/**
 * The pairing section, wired to the platform.
 *
 * ### There is a camera in this application now, and what it cost
 *
 * The paragraph that used to be here said there was none, and gave a measurement: seventeen artifacts
 * without `camera-view` and thirty-nine with it. Those were **gross** counts including things this app
 * already ships, and the argument they supported was overtaken by a plainer fact — **the route that
 * needed no permission did not work.** On 2026-08-09 the owner could not pair by camera at all, and
 * the pairing was carried by the file route.
 *
 * REQ-0019 re-measured against the real classpath: `camera-compose` is 21 new artifacts, against
 * `camera-view`'s 37 with Guava and AppCompat inside the difference. The decoder is unchanged and
 * costs nothing — see [PairingCodeFrames].
 *
 * ### Two ways in, neither privileged
 *
 * **Scanning is what the owner asked for**: *"чтобы оно сканилось как нормальные человеческие qr в
 * камере"*. [PairingViewfinder] reads frames continuously; the permission is requested when it opens
 * and nowhere else.
 *
 * **The file route stays, and reaching it never asks for anything.** `PairingCode`'s argument is
 * unchanged — a dark room, a cracked lens or a camera that will not focus must never block pairing —
 * and it is now load-bearing rather than considerate: it is the only route that has ever completed a
 * pairing in this project, including the one the owner is using today.
 */
@Composable
fun PairingHost(
    sequence: PairingSequence,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Read here rather than inside the lambda: resolving a resource off LocalContext bypasses the
    // composition's own configuration, so it can hand back a string for the wrong locale after a
    // configuration change. `stringResource` is the one that recomposes.
    val shareSheetTitle = stringResource(R.string.pairing_half_send)
    var state by remember { mutableStateOf(sequence.initial()) }

    val chooseImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            state = decodeInto(sequence, context, uri) ?: PairingState.NotAPairingCode
        }
    }

    // Whether the viewfinder is on screen. `remember` rather than `rememberSaveable`, deliberately:
    // if the process is recreated the owner comes back to the pairing screen with the scanner CLOSED.
    // Nothing is lost, because nothing has been decoded yet - and a scanner that reopened itself
    // after a recreation would be a camera switching on without anybody asking it to.
    var scanning by remember { mutableStateOf(false) }

    // Whether THIS app has asked and been told no. It cannot be read from the platform, it is not a
    // cache of the grant, and it resets with the process - the cost of that is one extra dialog after
    // a restart, which is the harmless direction.
    var refusedAlready by remember { mutableStateOf(false) }

    // Asked when the viewfinder is opened, NEVER at launch, and never anywhere on the way to the file
    // route. Refusal is not an error: `scanning` stays false, the screen re-reads CameraAccess on the
    // next composition and explains itself with the file route still beneath it.
    val askForCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanning = granted
        refusedAlready = !granted
    }

    if (scanning) {
        PairingViewfinder(
            onDecoded = { profile ->
                // Leaving this composition is what releases the camera - PairingViewfinder unbinds
                // its use cases on dispose. An earlier version of this comment claimed the release
                // happened here and it was FALSE: binding to the activity's lifecycle kept the camera
                // open until the app was backgrounded, measured at +26s on 2026-08-09. A false
                // comment about a lifecycle is worse than none, because the next person reads it
                // instead of the dumpsys output.
                scanning = false
                state = sequence.onProfile(profile)
            },
            onCancel = { scanning = false },
            modifier = modifier,
        )
        return
    }

    PairingSection(
        state = state,
        // Read here rather than remembered: a permission revoked in Settings while this app sat in
        // the background must be observed the next time it matters, and a cached `true` is how an
        // app tells the owner something that stopped being true.
        camera = CameraAccess.of(
            hasCamera = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
            permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
            refusedAlready = refusedAlready,
        ),
        // Every image type, because the owner's camera app decides the format and a filter that
        // guessed wrong would hide the photograph they just took.
        onChoose = { runCatching { chooseImage.launch(arrayOf("image/*")) } },
        // The permission is requested HERE, on a deliberate tap, and nowhere else in this file. A
        // permission already held opens the viewfinder with no dialog; one revoked in Settings comes
        // back through this same path, which is why revocation needs no special case of its own.
        onScan = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                scanning = true
            } else {
                runCatching { askForCamera.launch(Manifest.permission.CAMERA) }
            }
        },
        onConfirm = { state = sequence.onFingerprintConfirmed(it) },
        onReject = { state = sequence.onFingerprintRejected() },
        onUnpair = { state = sequence.onUnpair() },
        // The share sheet, not a file the owner has to go and find. runCatching because a device with
        // nothing able to receive a PEM would otherwise crash on a button press, and "nothing happened"
        // is a better outcome than a stack trace on the pairing screen.
        onSendCertificate = {
            runCatching {
                context.startActivity(
                    Intent.createChooser(
                        CertificateHandover.shareIntent(context),
                        shareSheetTitle,
                    ),
                )
            }
        },
        onHandoverDone = { state = sequence.onHandoverDone(it) },
        // The one destructive action on this screen, and it happens here because the owner pressed it.
        onReplaceIdentity = { state = sequence.onReplaceIdentity() },
        modifier = modifier,
    )
}

/**
 * Reads an image and runs it through the decoder.
 *
 * Returns null when the image cannot be read at all, which the caller reports as *not a pairing code*
 * — the same answer as an image that read fine and contained something else. The distinctions
 * available here are not ones the owner can act on.
 */
private fun decodeInto(sequence: PairingSequence, context: Context, uri: Uri): PairingState? {
    val bitmap = loadBitmap(context, uri) ?: return null
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val state = sequence.onImage(pixels, bitmap.width, bitmap.height)
    bitmap.recycle()
    return state
}

private fun loadBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder returns a hardware bitmap by default, whose pixels cannot be read back at all.
        // getPixels on one throws, so the decode would fail on every photograph with no clue why.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }
}.getOrElse {
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}
