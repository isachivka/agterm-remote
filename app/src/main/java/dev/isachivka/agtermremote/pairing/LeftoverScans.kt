package dev.isachivka.agtermremote.pairing

import java.io.File

/**
 * Removes the photographs the old still-camera route left behind.
 *
 * ### Deleting the writer does not delete what it wrote
 *
 * The scan route once asked the system camera to photograph the laptop's screen into
 * `files/pairing/incoming/scan.jpg`. **A phone that ran that build may be holding that photograph
 * right now** — a picture containing the laptop's address and certificate — and removing the code
 * that produced it changes nothing about the file. Someone had to notice, and this is the noticing.
 *
 * The old route deleted the image after a successful decode, so the copies that survive are precisely
 * the ones from scans that **failed** — which, on the owner's phone, is all of them.
 *
 * ### It runs every start, and that is the simple design rather than the lazy one
 *
 * A one-shot flag would need somewhere to live, and that somewhere would be a second thing to get
 * wrong for a directory that will not exist on any install after this one. Deleting nothing costs a
 * `File.exists()` call.
 *
 * ### It cannot fail, because there is nothing here worth failing over
 *
 * Absent is the expected case. Unreadable, a permission error, a file replaced by something odd — all
 * of them mean the same thing: this app is starting, and a leftover photograph is not a reason to
 * stop it. Every path returns quietly, and none of them logs the path or its contents.
 */
object LeftoverScans {

    /** Where the retired route wrote. Duplicated from the deleted `ScanWithCamera.DIRECTORY` on purpose:
     *  this outlives that file, and a constant imported from a deletion is a deletion that cannot happen. */
    private const val DIRECTORY = "pairing/incoming"

    /**
     * Removes the directory and anything in it. Safe to call on every start, on any phone, forever.
     *
     * Takes the files directory rather than a `Context` so it is exercised by an ordinary JVM test
     * against real directories — including the ones that cannot be read.
     */
    fun remove(filesDir: File) {
        runCatching {
            val directory = File(filesDir, DIRECTORY)
            if (!directory.exists()) return
            directory.deleteRecursively()
        }
    }
}
