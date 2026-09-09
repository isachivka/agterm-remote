package dev.isachivka.agtermremote.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The camera verdict, which exists because two very different situations look identical to the
 * platform.
 *
 * `shouldShowRequestPermissionRationale` is false on a first run *and* after a permanent refusal.
 * Reading it alone gives an app two choices, both wrong: accuse a new owner of having blocked the
 * camera, or never tell the blocked one anything — which is what shipped until now.
 */
class CameraAccessTest {

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a phone with no camera is not a permission problem`() {
        // Including the case where the permission is somehow granted: an app restored onto a device
        // with no camera still has no camera, and "turn it on in settings" would send somebody to a
        // screen with nothing on it.
        assertEquals(
            CameraAccess.NoCamera,
            cameraAccess(hasCamera = false, granted = true, everAsked = true, wouldAskAgain = false),
        )
        assertEquals(
            CameraAccess.NoCamera,
            cameraAccess(hasCamera = false, granted = false, everAsked = true, wouldAskAgain = false),
        )
    }

    @Test
    fun `a granted camera has nothing to say`() {
        assertEquals(
            CameraAccess.Granted,
            cameraAccess(hasCamera = true, granted = true, everAsked = true, wouldAskAgain = false),
        )
    }

    /**
     * **The row this whole type exists for.** Both of these report `wouldAskAgain = false`, and they
     * are the opposite situations: one has never seen a dialog, the other will never see one again.
     */
    @Test
    fun `a first run is not a refusal, and a refusal is not a first run`() {
        assertEquals(
            CameraAccess.Askable,
            cameraAccess(hasCamera = true, granted = false, everAsked = false, wouldAskAgain = false),
        )
        assertEquals(
            CameraAccess.Blocked,
            cameraAccess(hasCamera = true, granted = false, everAsked = true, wouldAskAgain = false),
        )
    }

    /**
     * Refused once is still askable: Android is still willing to show the dialog, and the screen has
     * nothing to explain that the dialog would not.
     */
    @Test
    fun `a camera refused once is left alone`() {
        assertEquals(
            CameraAccess.Askable,
            cameraAccess(hasCamera = true, granted = false, everAsked = true, wouldAskAgain = true),
        )
    }

    @Test
    fun `asking is recorded and survives being read by another instance`() {
        val file = File(folder.root, "asked")
        assertFalse(AskedForCamera(file).wasAsked())

        AskedForCamera(file).record()

        assertTrue(AskedForCamera(file).wasAsked())
    }

    /**
     * An unwritable store costs one sentence of copy and must never cost the pairing screen — the
     * record is taken on the way into a permission dialog, which is the worst place in the app to
     * throw. A directory in the file's place is the cheapest thing that cannot be written.
     */
    @Test
    fun `a store it cannot write does not throw and reads as not asked`() {
        val store = AskedForCamera(folder.newFolder("occupied"))

        store.record()

        assertFalse(store.wasAsked())
    }
}
