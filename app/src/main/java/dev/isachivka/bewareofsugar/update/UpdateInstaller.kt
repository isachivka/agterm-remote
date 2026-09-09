package dev.isachivka.bewareofsugar.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.isachivka.bewareofsugar.BuildConfig
import java.io.File
import java.io.IOException

/**
 * Hands the `.apk` to Android's installer.
 *
 * **Android decides, not this app.** Silent installation has not been possible since Oreo, so the
 * confirmation the owner sees is the platform's own screen. That is not a gap in this feature and
 * there is nothing here that tries to route around it.
 *
 * ## Why `ACTION_VIEW` and not the `PackageInstaller` session API
 *
 * A fair question, because `PackageInstaller` *can* report a result and this cannot: with a session
 * you get `STATUS_FAILURE_ABORTED` when the owner declines, and a reason when an install fails.
 * `ACTION_VIEW` returns nothing at all.
 *
 * The trade, plainly. A session means streaming the whole `.apk` into it — a second copy of several
 * megabytes on a device that may have refused the first one for space — plus a `PendingIntent` and a
 * receiver to catch the callback, on the single path that must not break. What the app would do with
 * that callback is change one line of copy: the screen already treats declining and
 * confirming-then-changing-your-mind identically, because both leave a downloaded file and a
 * standing offer to install it, and neither is a failure. Paying a whole extra mechanism for a
 * distinction with no consequence is not a good trade.
 *
 * The other thing a callback would buy — knowing when to delete the downloaded `.apk` — is had
 * without one: the next successful check reports the app as up to date, and that *is* the signal
 * that the install happened, so [ReleaseDownloader.discard] runs then.
 *
 * What would change this: needing to tell the owner *why* an install failed, or to act on a failure
 * rather than let them retry. Neither is true today. If either becomes true, `PackageInstaller` is
 * the right answer and it is its own change, not a footnote in this one.
 *
 * The file is in app-internal storage and reaches the installer through a `FileProvider` grant that
 * lasts for the one intent. It is never written to external storage: an `.apk` anywhere
 * world-readable is one any other app on the phone can swap between the download finishing and the
 * installer opening it — and the owner would then be confirming an install of something else,
 * having read the right version number a second earlier.
 */
object UpdateInstaller {

    /** Where downloads live: `filesDir/updates`, matching `res/xml/file_paths.xml`. */
    fun downloadDir(context: Context): File = File(context.filesDir, "updates")

    /**
     * How many bytes could actually be written here.
     *
     * `File.usableSpace` is the obvious answer and the wrong one on Android: it ignores the space
     * the system could reclaim by clearing other apps' caches, so it reports "not enough room" for
     * an update that would in fact fit. `getAllocatableBytes` is the platform's own answer to that
     * question, and it is what Android lint points at.
     */
    fun allocatableBytes(context: Context, dir: File): Long {
        val storage = context.getSystemService(StorageManager::class.java)
        return try {
            storage.getAllocatableBytes(storage.getUuidForPath(dir))
        } catch (e: IOException) {
            // The volume could not be interrogated. Rather than blocking the download on a number we
            // could not read, let it start - the write itself will fail honestly if space runs out,
            // and that path is already handled as an interruption.
            Long.MAX_VALUE
        }
    }

    /**
     * Whether the owner has allowed this app to ask. Without it the installer intent opens a screen
     * that goes nowhere, so it is checked before offering to install rather than after.
     */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Sends the owner to the one Android settings screen that can grant the above. */
    fun allowUnknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData("package:${context.packageName}".toUri())

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            // The grant is per-intent and read-only: the installer can read this one file, for this
            // one launch, and nothing else in the directory.
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    /** Must match the authority in AndroidManifest.xml. */
    val AUTHORITY: String = "${BuildConfig.APPLICATION_ID}.updates"
}

private fun String.toUri(): Uri = Uri.parse(this)
