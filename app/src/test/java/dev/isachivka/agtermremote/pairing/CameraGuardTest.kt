package dev.isachivka.agtermremote.pairing

import androidx.camera.core.CameraState
import androidx.camera.core.CameraUnavailableException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening a camera fails on somebody else's device in two different shapes, and this screen may not
 * crash on one or sit black forever on the other.
 *
 * The wiring for the first — a throw reaching [PairingUi.NeedsCamera], with the owner in front of the
 * paste field — is proven by running the app on an emulator with no back camera; see
 * `docs/pairing.md`.
 *
 * **The second has no such run.** Staging a camera another application is holding was attempted and
 * did not work: Android gives the camera to the foreground application, so a second client opened from
 * inside the test was evicted and CameraX opened the camera anyway. What is proven here is therefore
 * the **policy** for both — which throws are turned into a report, which one is not, and which camera
 * states mean this phone is not going to read a code — and not that this path has ever carried one.
 */
class CameraGuardTest {

    private var reported = 0

    private fun <T> guarded(block: () -> T): T? = cameraOrUnavailable({ reported++ }, block)

    /**
     * The two shapes that actually happen, and a third nobody has enumerated.
     *
     * `IllegalArgumentException` is a device with no camera matching the back selector — and
     * `FEATURE_CAMERA_ANY`, which is what the screen checks before getting here, is satisfied by a
     * front camera alone. `CameraUnavailableException` is a camera another application is holding, and
     * it is **checked**, so a `catch (e: RuntimeException)` would not have caught it at all.
     */
    @Test
    fun `a camera that will not open is reported rather than thrown`() {
        val failures = listOf(
            IllegalArgumentException("No available camera can be found"),
            CameraUnavailableException(CameraUnavailableException.CAMERA_IN_USE),
            IllegalStateException("provider in a bad state"),
            RuntimeException("something a vendor added"),
        )

        failures.forEach { failure ->
            reported = 0

            val got = guarded<String> { throw failure }

            assertNull("$failure produced a value", got)
            assertEquals("$failure was not reported", 1, reported)
        }
    }

    /** And a call that works is not disturbed by the guard around it. */
    @Test
    fun `a camera that opens is handed back untouched`() {
        val got = guarded { "a camera" }

        assertEquals("a camera", got)
        assertEquals("nothing failed, so nothing may be reported", 0, reported)
    }

    /**
     * **A cancellation is not a camera failure and must not be swallowed.**
     *
     * The provider is acquired inside `produceState`, so this runs in a coroutine that is cancelled
     * when the composable leaves. Catching that would leave a composable that is gone believing it is
     * alive — a coroutine leaked once per visit to a screen the owner can bounce in and out of.
     */
    @Test
    fun `a cancellation propagates rather than being reported as an unavailable camera`() {
        var thrown: Throwable? = null

        try {
            guarded<String> { throw CancellationException("the screen went away") }
        } catch (e: CancellationException) {
            thrown = e
        }

        assertTrue("a cancellation must propagate", thrown is CancellationException)
        assertEquals("a cancellation is not a camera that will not open", 0, reported)
    }

    // --- The shape that is not a throw at all ---------------------------------------------------

    /**
     * **A camera another application is holding does not throw.** `bindToLifecycle` succeeds and the
     * refusal arrives later, from the camera service, as a state carrying an error — so the exception
     * guard never sees it and, until this state was observed, the viewfinder simply stayed black.
     *
     * `ERROR_CAMERA_IN_USE` is classified RECOVERABLE, which is why "route only the critical ones"
     * would leave the commonest case exactly where it was.
     */
    @Test
    fun `a camera held by something else is a state error rather than a throw`() {
        val inUse = CameraState.create(
            CameraState.Type.PENDING_OPEN,
            CameraState.StateError.create(CameraState.ERROR_CAMERA_IN_USE),
        )

        assertTrue("a camera held by another app must reach the paste field", cameraIsUnusable(inUse))
    }

    /** Every error this library can report, whatever its type. There is no black rectangle for any of them. */
    @Test
    fun `every camera error sends the owner to the paste field`() {
        val codes = listOf(
            CameraState.ERROR_CAMERA_IN_USE,
            CameraState.ERROR_MAX_CAMERAS_IN_USE,
            CameraState.ERROR_OTHER_RECOVERABLE_ERROR,
            CameraState.ERROR_STREAM_CONFIG,
            CameraState.ERROR_CAMERA_DISABLED,
            CameraState.ERROR_CAMERA_FATAL_ERROR,
            CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED,
            CameraState.ERROR_CAMERA_REMOVED,
        )

        codes.forEach { code ->
            CameraState.Type.entries.forEach { type ->
                val state = CameraState.create(type, CameraState.StateError.create(code))

                assertTrue("error $code in state $type left the viewfinder black", cameraIsUnusable(state))
            }
        }
    }

    /**
     * And a state with no error is not one, whatever its type. **`PENDING_OPEN` on its own is CameraX
     * waiting**, which is what it does before every successful open — treating it as a failure would
     * send every owner to the paste field on the way to a camera that was about to work.
     */
    @Test
    fun `a state carrying no error is never treated as a failure`() {
        CameraState.Type.entries.forEach { type ->
            assertTrue(
                "$type with no error was treated as a camera that will not open",
                !cameraIsUnusable(CameraState.create(type)),
            )
        }
    }
}
