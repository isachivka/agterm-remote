package dev.isachivka.agtermremote.pairing

import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
// A suspend extension ON THE COMPANION (ProcessCameraProviderExtKt), so it needs its own import and
// is not reachable through the class. Confirmed by javap against the downloaded .aar rather than
// guessed: the class itself offers only the ListenableFuture form.
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
// androidx.lifecycle.compose, not androidx.compose.ui.platform: the latter is deprecated and moved
// here. lifecycle-runtime-compose is already on this app's classpath, so the correct one costs no
// new dependency - checked against the resolved classpath rather than assumed.
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.isachivka.agtermremote.R
import dev.isachivka.agtermremote.ui.theme.AppTheme
import java.util.concurrent.Executors

/**
 * A live viewfinder that reads the code off the Mac's screen.
 *
 * ### It reads a symbol and hands over TEXT
 *
 * [onText] is given the string a QR symbol carried, and nothing here decides whether that string is a
 * pairing code. That decision belongs to [EnrollCodec], which is the decoder the shared vectors pin,
 * and it is reached through the same callback the paste field uses. **The scanner has no path of its
 * own into the pairing.** It had one until this screen was rewritten, and the path it had ran a base64
 * decoder that trimmed its input and never checked padding.
 *
 * ### The lifecycle is the library's, deliberately
 *
 * `bindToLifecycle` ties the camera to this screen's `LifecycleOwner`, so **the camera is released
 * when the app goes to the background** without any code here noticing — and, more importantly,
 * without any code here forgetting. A hand-managed `open`/`close` pair is one early return away from
 * holding the device's camera while the owner is in another app, which is the kind of bug whose
 * report is *"my camera stopped working"* two days later and never gets traced back here.
 *
 * `CameraXViewfinder` owns the `SurfaceRequest`, so there is no `AndroidView`, no `SurfaceProvider` of
 * ours, and no rotation or teardown code to get wrong.
 *
 * ### What it may not do
 *
 * **Nothing here tells the owner to move closer based on how big the code looks.** The probe on
 * 2026-08-09 found a cliff between 9 and 7 pixels per module and *also* found that, under blur, a
 * code filling 20% of the frame decoded better than one filling 30% — a result it could not explain.
 * Guidance is therefore driven by **the decoder not succeeding for a while**, which is a fact, rather
 * than by apparent size, which is the assumption the measurement contradicted.
 *
 * ### Nothing in here may crash this screen
 *
 * Opening a camera is the part of this file that fails on somebody else's device rather than in our
 * code, and **the screen already owns the state to fall into**: [PairingUi.NeedsCamera], with the
 * paste field under it, which needs no camera and no permission. Two shapes are real and neither is
 * exotic:
 *
 *  - a device with **no back camera**. `FEATURE_CAMERA_ANY` is satisfied by a front camera, so such a
 *    phone passes the check the host makes and then throws on `DEFAULT_BACK_CAMERA`. That one is a
 *    throw, and [cameraOrUnavailable] catches it.
 *  - a camera **held by another application**. **This one does not throw at all**, and assuming it did
 *    was wrong: `bindToLifecycle` succeeds, and the camera service's refusal arrives later and
 *    elsewhere, as a `CameraState` carrying an error. Nothing was watching that, so the outcome was
 *    not the paste field - it was a viewfinder that stayed black forever with no way out, which is
 *    worse than the crash the guard was built for.
 *
 * So there are two mechanisms because there are two shapes, and only one of them is an exception, and
 * **they are reported through two different callbacks because they mean different things.** A throw is
 * a fact about the device: [onUnavailable], and Try again keeps the owner on the paste field. A state
 * error is a fact about this moment: [onStalled], which shows the paste field and leaves the scanner
 * available, because most of these errors are published by a library that is retrying and about to
 * succeed. Latching on one of those would take a working screen away from somebody a moment from
 * finishing.
 *
 * The state is observed below and the policy is [cameraIsUnusable]; both are unit-tested.
 *
 * **No end-to-end run has produced a camera-state error, and staging one was tried and failed** -
 * which turns out to argue the opposite of what it looks like. A second client opened from inside this
 * process was evicted and CameraX logged `Camera open completed ... errorCode=null`: Android gives the
 * camera to the foreground application. **That eviction is the same event that hands the OTHER side a
 * momentary in-use state**, so the case that could not be staged here is the case a person meets -
 * resuming this app right after a camera application, or the moment one is slow to let go. It is not
 * rare; it is just not stageable from the side that wins. Recorded as a gap in `docs/pairing.md`.
 *
 * ### What is proven about this file, and what is not
 *
 * The frame seam is covered off-device by `QrCodeTest`. Everything else here — the permission, the
 * binding, the surface, the analyzer's delivery — was run end to end on the emulator's virtual scene
 * against a real bridge, which is recorded in `docs/pairing.md` along with **the one criterion still
 * outstanding: this has never met a real lens.**
 */
@Composable
fun PairingViewfinder(
    onText: (String) -> Unit,
    onUnavailable: () -> Unit,
    onStalled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var struggling by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }

    // The callback can change between recompositions; the analyzer below is created once and would
    // otherwise capture the first one forever.
    val decoded by rememberUpdatedState(onText)

    // One thread, ours, shut down on the way out. Analysis must not run on the main thread: a decode
    // is tens of milliseconds and the viewfinder is the thing that would stutter.
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    // Held so the effect below can be told apart from "not ready yet": a provider that never
    // arrives leaves this null forever, and nothing must go on waiting for it in silence.
    val unavailable by rememberUpdatedState(onUnavailable)

    val provider by produceState<ProcessCameraProvider?>(null, context) {
        value = cameraOrUnavailable({ unavailable() }) { ProcessCameraProvider.awaitInstance(context) }
    }

    val preview = remember {
        Preview.Builder().build().apply {
            setSurfaceProvider { request -> surfaceRequest = request }
        }
    }

    val analysis = remember {
        ImageAnalysis.Builder()
            // Keep only the latest: a decode that falls behind must drop frames rather than queue
            // them. A queue here would show the owner a viewfinder reading a scene they have already
            // moved away from, and would keep "recognising" a code after they lowered the phone.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            // ASKED FOR EXPLICITLY, because the default is 640x480 and this code cannot fit in it.
            // A pairing payload is ~550 characters, which is an 89-module symbol; the probe on
            // 2026-08-09 put the decode cliff between 7 and 9 pixels per module, so the code needs
            // 623-801 px across. In a 640-wide frame that is impossible EDGE TO EDGE WITH NO QUIET
            // ZONE - it would have failed on the owner's phone at every distance. Measured on an
            // emulator: preview was handed 1280x960 while analysis took the 640x480 default, with
            // 1280x960 sitting in the candidate list the whole time.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 960),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .build()
    }

    // Binding and UNBINDING are one effect, so the camera cannot outlive the screen that opened it.
    //
    // The previous version bound inside a LaunchedEffect and never unbound: bindToLifecycle ties the
    // camera to the ACTIVITY, so leaving the viewfinder left it open - measured still held at +26
    // seconds on 2026-08-09, released only when the app was backgrounded. Removing a composable does
    // not unbind anything, and nothing in the type system says so.
    DisposableEffect(provider, lifecycleOwner) {
        val bound = provider
        var found = false
        var firstFrameAt = 0L
        analysis.setAnalyzer(executor) { image ->
            // EVERY path closes the frame, including the throwing one. An unclosed ImageProxy stalls
            // the pipeline after a handful of frames and the viewfinder simply freezes - with no
            // error, which is the worst shape a bug can take on this screen.
            try {
                if (!found) {
                    val now = System.nanoTime()
                    if (firstFrameAt == 0L) firstFrameAt = now
                    val text = readText(image)
                    if (text != null) {
                        // Once. The analyzer runs on its own thread and the caller's reaction is a
                        // state change on another, so without this a second frame carrying the same
                        // symbol would start a second enrolment - with a one-time token that the
                        // first one has already spent.
                        found = true
                        struggling = false
                        decoded(text)
                    } else {
                        // Driven by the decoder failing for a while, never by how large the code
                        // looks. See the note above: apparent size is the assumption the measurement
                        // contradicted.
                        struggling = now - firstFrameAt > STRUGGLING_AFTER_NANOS
                    }
                }
            } finally {
                image.close()
            }
        }

        if (bound != null) {
            // The unbind is inside the guard too. It is the call that would throw on a provider left
            // in a bad state by whatever failed last time, and a throw here is as fatal as one from
            // the bind - it happens in a DisposableEffect, on the main thread, with no catch above it.
            camera = cameraOrUnavailable({ unavailable() }) {
                bound.unbindAll()
                bound.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }

        onDispose {
            // Guarded for the same reason the bind is: this runs on the way out of a screen, on the
            // main thread, with nothing above it to catch anything. A provider left in a bad state by
            // whatever failed a moment ago must not turn leaving the screen into a crash - and there
            // is nothing useful to report here, because the screen is already going.
            cameraOrUnavailable({}) {
                // The torch first: unbinding extinguishes it anyway, but a torch left burning in
                // somebody's pocket is the one failure here with a physical cost.
                camera?.cameraControl?.enableTorch(false)
                analysis.clearAnalyzer()
                bound?.unbind(preview, analysis)
            }
            camera = null
        }
    }

    // **The camera opened, or it did not, and the second answer arrives here rather than as a throw.**
    //
    // A camera another application is holding lets `bindToLifecycle` succeed and then never delivers
    // a frame: the refusal comes from the camera service, asynchronously, as a state carrying an
    // error. Watching it is the difference between the paste field and a black rectangle that never
    // resolves.
    //
    // Keyed on `camera`, so a rebind gets a fresh observer and the old one is removed. The LiveData is
    // observed with the screen's own lifecycle owner as well, so nothing here outlives the screen even
    // if the dispose is missed.
    val stalled by rememberUpdatedState(onStalled)
    val watched = camera
    DisposableEffect(watched, lifecycleOwner) {
        val state = watched?.cameraInfo?.cameraState
        val observer = Observer<CameraState> { value ->
            // `stalled`, never `unavailable`: this camera opened, so it is not a camera the phone
            // does not have. See PairingFlow.cameraStalled.
            if (cameraIsUnusable(value)) stalled()
        }
        state?.observe(lifecycleOwner, observer)
        onDispose { state?.removeObserver(observer) }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .testTag(TAG_VIEWFINDER),
        ) {
            surfaceRequest?.let { request ->
                CameraXViewfinder(surfaceRequest = request, modifier = Modifier.fillMaxWidth())
            }
        }
        Text(
            text = stringResource(
                if (struggling) R.string.pairing_viewfinder_struggling else R.string.pairing_viewfinder_body,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.onSurfaceSubtle,
        )
        // Manual only, and absent rather than broken where there is no flash unit. Nothing switches
        // this on by itself: a torch that lights automatically while a phone is pointed at a person
        // is a worse surprise than a dark viewfinder, and the dark room already has an answer that
        // needs no camera at all - the paste field, on the same screen, below this.
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            TextButton(
                onClick = {
                    torchOn = !torchOn
                    camera?.cameraControl?.enableTorch(torchOn)
                },
                modifier = Modifier.testTag(TAG_VIEWFINDER_TORCH),
            ) {
                Text(
                    stringResource(
                        if (torchOn) R.string.pairing_viewfinder_torch_off else R.string.pairing_viewfinder_torch_on,
                    ),
                )
            }
        }
    }
}

/** Six seconds of frames with nothing in them is a person who needs telling something. */
private const val STRUGGLING_AFTER_NANOS = 6_000_000_000L

/**
 * One frame's luminance plane, copied out and handed to the symbol reader.
 *
 * The buffer belongs to the camera and is recycled the moment the frame is closed, so it is copied
 * rather than retained. `PlanarYUVLuminanceSource` reads it synchronously, but the copy is what makes
 * that guarantee ours rather than an assumption about somebody else's code.
 */
private fun readText(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val luma = ByteArray(buffer.remaining())
    buffer.get(luma)
    return QrCode.textIn(
        luma = luma,
        // NOT image.width. The hardware pads each row out to a stride, and reading it as though it
        // did not shears the image silently - see QrCode.
        rowStride = plane.rowStride,
        width = image.width,
        height = image.height,
    )
}

/**
 * A CameraX call that can fail on somebody else's device, made to report rather than to crash.
 *
 * ### Why the catch is this wide
 *
 * Because the set of things that can go wrong opening a camera is not ours and is not enumerable. A
 * device with no back camera raises `IllegalArgumentException`; one whose camera is held raises
 * `CameraUnavailableException`, which is a checked exception; a provider in a bad state raises
 * `IllegalStateException`; and vendors add their own. **Enumerating the ones we have seen would
 * rebuild the exact defect this project keeps meeting** - a check that describes the failures somebody
 * happened to meet rather than the property needed. The property is simple: if the camera will not
 * open, this phone pairs by pasting the code, which is a route that always exists.
 *
 * The breadth is only safe because the consequence is a screen, not a deletion. Nothing here is
 * destroyed, nothing is stored, and the owner is left in front of the field they can use.
 *
 * **`CancellationException` is re-thrown**, and that is not a detail. This runs inside `produceState`,
 * so swallowing a cancellation would mean a composable that has left the screen going on believing it
 * is alive - a coroutine leaked once per visit to a screen somebody may bounce in and out of.
 */
internal inline fun <T> cameraOrUnavailable(onUnavailable: () -> Unit, block: () -> T): T? = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    onUnavailable()
    null
}

/**
 * Whether a camera state means this phone is not going to read a code with it.
 *
 * ### Any error, and not only the ones CameraX calls critical
 *
 * `StateError` carries a type — recoverable or critical — and a camera **held by another
 * application** is recoverable: CameraX will open it if that application lets go. Routing only the
 * critical ones would therefore leave the commonest case exactly where it was, which is a viewfinder
 * that stays black until something outside this app changes.
 *
 * So any error at all sends the owner to the paste field, and the cost of that is the safe direction:
 * a route that always works, needs no permission, and is on the same screen. Nothing is destroyed and
 * nothing is stored; re-opening the screen tries the camera again.
 *
 * A state with no error is not interesting whatever its type. `PENDING_OPEN` on its own is CameraX
 * waiting, which is what it does before every successful open.
 */
internal fun cameraIsUnusable(state: CameraState): Boolean = state.error != null
