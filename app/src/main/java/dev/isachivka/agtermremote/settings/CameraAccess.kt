package dev.isachivka.agtermremote.settings

import android.content.Context
import java.io.File

/**
 * Whether this app can open the camera, and — when it cannot — whether asking again would do anything.
 *
 * ### Why the last state exists at all
 *
 * A phone whose owner has refused the camera twice is in a state Android will not move out of on its
 * own: `requestPermissions` returns immediately with a refusal and no dialog is shown. Until this
 * screen existed the app said **No camera to read the code** and offered the paste field, which is
 * true and useless — it does not say what happened, and there is nothing on the screen that leads
 * anywhere. The owner has to know, unprompted, that the answer is in the system settings.
 *
 * So [Blocked] is a state of its own, with copy that says what happened and a button that opens the
 * screen where it can be undone.
 */
enum class CameraAccess {

    /** Nothing to say. The viewfinder opens. */
    Granted,

    /** No camera on this device at all. Not a permission problem and must not be described as one. */
    NoCamera,

    /**
     * Not granted, and asking would put the system dialog in front of the owner.
     *
     * Covers both *never asked* and *refused once*. They are the same situation from here: the app
     * asks on arrival at the pairing screen, and there is deliberately no second button that asks
     * again — a screen that immediately re-asks in a different shape is the app arguing with somebody
     * who said no on purpose.
     */
    Askable,

    /**
     * Refused for good. **Android will not ask again**, so the app cannot fix this and must say so.
     */
    Blocked,
}

/**
 * The verdict, as a pure function of the four facts the platform can be asked for.
 *
 * A function rather than something that reads the platform itself, because the interesting part is
 * the *combination* and a rule that can only be exercised by holding a phone is a rule nothing
 * asserts. `CameraAccessTest` covers every row.
 *
 * @param hasCamera `PackageManager.FEATURE_CAMERA_ANY`.
 * @param granted `checkSelfPermission` came back granted.
 * @param everAsked this app has put the dialog in front of the owner at least once — see
 *   [AskedForCamera]. **Without it the first run and a permanent refusal are indistinguishable**,
 *   because `shouldShowRequestPermissionRationale` is false for both.
 * @param wouldAskAgain `shouldShowRequestPermissionRationale`, which is Android's way of saying the
 *   dialog is still on the table.
 */
fun cameraAccess(
    hasCamera: Boolean,
    granted: Boolean,
    everAsked: Boolean,
    wouldAskAgain: Boolean,
): CameraAccess = when {
    // Asked first, and deliberately: a device with no camera cannot have a permission problem, and a
    // granted permission on such a device (an app restored from another phone, a manual grant) is
    // still no camera. Saying "turn the camera on in settings" to somebody who has none is the same
    // class of wrong sentence as blaming the laptop for a phone's key.
    !hasCamera -> CameraAccess.NoCamera
    granted -> CameraAccess.Granted
    everAsked && !wouldAskAgain -> CameraAccess.Blocked
    else -> CameraAccess.Askable
}

/**
 * That this app has asked for the camera at least once, on disk.
 *
 * One byte of history, and it exists because the platform does not keep it. Both *never asked* and
 * *refused for good* report `shouldShowRequestPermissionRationale == false`, so without this the app
 * would either tell a first-run owner that they had blocked the camera, or never tell the blocked one
 * anything. It records that a question was asked — not an answer, not a verdict about anything, and
 * nothing that expires.
 *
 * A file rather than DataStore for the same reason as [StyledScreenStore]: one fact does not need a
 * schema, and an unreadable file is survivable as *not asked yet*, which only costs the copy.
 */
class AskedForCamera(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, FILE))

    fun wasAsked(): Boolean = try {
        file.isFile
    } catch (unreadable: SecurityException) {
        false
    }

    /** Called at the moment the dialog is launched, not when the answer comes back. */
    fun record() {
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(ByteArray(0))
        } catch (unwritable: Exception) {
            // Forgetting that we asked costs one sentence of copy. Crashing the pairing screen costs
            // the pairing.
        }
    }

    private companion object {
        const val FILE = "asked-for-camera"
    }
}
