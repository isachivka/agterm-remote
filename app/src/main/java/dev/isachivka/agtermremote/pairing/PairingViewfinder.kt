package dev.isachivka.agtermremote.pairing

import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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

    val provider by produceState<ProcessCameraProvider?>(null, context) {
        value = ProcessCameraProvider.awaitInstance(context)
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
            bound.unbindAll()
            camera = bound.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }

        onDispose {
            // The torch first: unbinding extinguishes it anyway, but a torch left burning in
            // somebody's pocket is the one failure here with a physical cost.
            camera?.cameraControl?.enableTorch(false)
            analysis.clearAnalyzer()
            bound?.unbind(preview, analysis)
            camera = null
        }
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
