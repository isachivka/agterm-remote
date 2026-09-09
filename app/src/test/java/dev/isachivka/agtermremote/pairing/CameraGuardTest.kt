package dev.isachivka.agtermremote.pairing

import androidx.camera.core.CameraUnavailableException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening a camera fails on somebody else's device, and this screen may not crash when it does.
 *
 * The wiring - that the failure reaches [PairingUi.NeedsCamera] and the owner sees the paste field -
 * is proven by running the app on an emulator with no back camera; see `docs/pairing.md`. What is
 * proven here is the **policy**: which throws are turned into a report, and which one is not.
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
}
