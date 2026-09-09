package dev.isachivka.bewareofsugar.update

import android.content.Context

/**
 * Which route this copy of the app arrived by — REQ-0050.
 *
 * Android records two different things about an install and they are easy to confuse. WHO SIGNED it
 * decides whether a new build can replace this one; WHO INSTALLED it decides whether Android Auto
 * will run it. Since 0.33.0 both channels are signed with the same key, so the first question has
 * one answer everywhere and this file is entirely about the second.
 *
 * The consequence is sharp enough to be worth stating where the enum is: installing a GitHub `.apk`
 * over a Play install changes the installer from Play to this app, and the car stops listing the
 * app until it is installed from Play again. Nothing about the app breaks, and nothing says why.
 */
enum class InstallChannel {
    /** Installed by the Play Store. In-app updates work, and so does Android Auto. */
    PLAY,

    /**
     * Installed by anything else — the app's own updater, a browser, `adb`, a file manager.
     *
     * Play's update API answers nothing useful for such a copy: it reports on the track an install
     * came from, and this one came from no track.
     */
    ELSEWHERE,
}

/** The Play Store's package name, which is what "installed from Play" means to the platform. */
const val PLAY_STORE_PACKAGE = "com.android.vending"

/**
 * The rule itself, as a pure function of the one string it depends on, so it can be tested without
 * a device. [installChannel] is the part that has to touch a `Context`.
 */
fun installChannelOf(installingPackageName: String?): InstallChannel =
    if (installingPackageName == PLAY_STORE_PACKAGE) InstallChannel.PLAY else InstallChannel.ELSEWHERE

/**
 * Reads it off the platform.
 *
 * `installingPackageName` and not `initiatingPackageName`: the initiator is whoever *started* the
 * install — a browser handing a file to the package installer names itself there — while the
 * installer is the package that actually performed it. Play is the installer for a Play install,
 * and that is the fact the car reads.
 *
 * A failure here is reported as [InstallChannel.ELSEWHERE] rather than thrown. The honest reading of
 * "the platform will not tell me where this came from" is "not demonstrably from Play", and the
 * screen that follows from it says so; an update screen that crashes is worse than one that is
 * cautious.
 */
fun Context.installChannel(): InstallChannel = runCatching {
    installChannelOf(packageManager.getInstallSourceInfo(packageName).installingPackageName)
}.getOrDefault(InstallChannel.ELSEWHERE)
