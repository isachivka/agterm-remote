package dev.isachivka.agtermremote.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.cert.X509Certificate

/**
 * The pairing sequence, as the five states the screen renders.
 *
 * ### Why this is a plain class and not a ViewModel
 *
 * It holds one value and one suspending call. A ViewModel would add a lifecycle to something that has
 * none, and would put the thing under test behind a factory. What it does hold is Compose state, so
 * the screen recomposes when it changes, and an instrumented test can read [state] directly and wait
 * on it.
 *
 * ### The enrolment is injected, and only the enrolment
 *
 * Everything above it — reading the code, choosing the sentence, deciding which state to return to —
 * is the real thing in every test. The seam is one lambda because a fake reaching any further up would
 * be a test of the fake.
 */
class PairingFlow(private val enrol: suspend (EnrollPayload) -> EnrollResult) {

    /** What the screen is showing. Scanning until something says the camera is not available. */
    var state: PairingUi by mutableStateOf(PairingUi.Scanning)
        private set

    /**
     * Where **Try again** goes back to, which is not always where pairing started.
     *
     * A phone whose owner refused the camera must not be sent to a viewfinder that will never open.
     */
    private var fallback: PairingUi = PairingUi.Scanning

    /** No camera, or the owner said no. The paste field is the route, and it always works. */
    fun cameraUnavailable() {
        fallback = PairingUi.NeedsCamera
        state = PairingUi.NeedsCamera
    }

    /**
     * A code arrived — from the lens, or from the paste field. **There is one of these, on purpose.**
     *
     * The decode happens first and refuses without opening anything: this text was put in front of a
     * camera, or on a clipboard, by anyone. Only a payload this build understands reaches the network.
     */
    suspend fun onCode(text: String) {
        val decoded = EnrollCodec.readText(text)
        PairingOutcome.of(decoded)?.let { state = it; return }

        state = PairingUi.Working
        state = PairingOutcome.of(enrol((decoded as EnrollDecode.Read).payload))
    }

    /** Back to whichever route this phone actually has. */
    fun retry() {
        state = fallback
    }
}

/**
 * The pairing screen, wired to the platform.
 *
 * ### The permission is asked once, when this screen opens
 *
 * This screen exists to pair, the camera is the way it is meant to be done, and the spec's onboarding
 * is *camera permission → viewfinder → (fallback: paste the code)*. So the request happens here, on
 * arrival, and nowhere else in the application.
 *
 * **A refusal is a supported state, not an error**, and there is deliberately no button that asks
 * again: the owner refused on purpose, and a screen that immediately asks in a different shape is the
 * app arguing with them. The paste field is under the refusal and needs no permission at all. A
 * permission granted later in Settings is picked up the next time this screen is opened, because the
 * check below is read rather than remembered.
 *
 * ### The store is written by the enrolment and by nothing here
 *
 * [PairedLaptop] is handed to [Enrollment], which writes it only after the reply has checked out. This
 * file stores nothing, so a pairing that fails halfway leaves exactly what was there before.
 */
@Composable
fun PairingHost(
    store: PairedLaptop,
    modifier: Modifier = Modifier,
    enrol: suspend (EnrollPayload) -> EnrollResult = { payload -> defaultEnrol(payload, store) },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember(store) { PairingFlow(enrol) }

    // Read rather than remembered: a permission revoked in Settings while this app sat in the
    // background must be observed the next time it matters, and a cached `true` is how an app tells
    // the owner something that stopped being true.
    val hasCamera = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var permitted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val askForCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        if (!granted) flow.cameraUnavailable()
    }

    // Once per arrival at this screen. `Unit` rather than a changing key: asking again on every
    // recomposition would put a system dialog in front of somebody who is typing into the paste field.
    LaunchedEffect(Unit) {
        when {
            !hasCamera -> flow.cameraUnavailable()
            permitted -> Unit
            else -> runCatching { askForCamera.launch(Manifest.permission.CAMERA) }
                // A device with no activity able to show the dialog is a device with no camera route.
                .onFailure { flow.cameraUnavailable() }
        }
    }

    PairingSection(
        state = flow.state,
        onCode = { text -> scope.launch { flow.onCode(text) } },
        onRetry = flow::retry,
        modifier = modifier,
        // The same callback the Pair button is given. The scanner has no path of its own.
        viewfinder = { onText -> if (permitted) PairingViewfinder(onText = onText) },
    )
}

/**
 * One enrolment, off the main thread, with this phone's identity.
 *
 * The identity is generated on first use and **never replaced here**. Replacing a key is what took the
 * owner's pairing down on 2026-07-29, and the verdict that would trigger it — `SigningState.Unusable`
 * — is deliberately broad, which is right for a sentence on a screen and was catastrophic as a
 * deletion. A key that cannot sign therefore pairs and then cannot reach the API; that is a real gap,
 * it is recorded in `docs/pairing.md`, and closing it belongs with the Settings screen that owns
 * unpairing.
 *
 * `GeneralSecurityException` is caught because the alternative is a crash on a button press. It is
 * reported through the same arm as an unreachable Mac would not be — a key that will not mint is this
 * phone's problem, and the sentence says so.
 */
private suspend fun defaultEnrol(payload: EnrollPayload, store: PairedLaptop): EnrollResult =
    withContext(Dispatchers.IO) {
        val identity: X509Certificate = try {
            PhoneIdentity.certificate()
        } catch (e: GeneralSecurityException) {
            return@withContext EnrollResult.Refused(NO_IDENTITY)
        }
        Enrollment.enroll(payload, identity, deviceName(), store)
    }

/**
 * What the Mac's menu will show beside this phone's fingerprint.
 *
 * The model, which is what the owner would call this phone when they see it in a list. **Not
 * `Settings.Global.DEVICE_NAME`**, which is a name a person chose and often their own — this string
 * leaves the device and is stored on the Mac.
 */
private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

/** The key would not mint. Nothing about the Mac is wrong, so the sentence must not blame it. */
private const val NO_IDENTITY =
    "This phone could not make the key that proves who it is. Try again, and if it keeps failing, " +
        "restart the phone."
