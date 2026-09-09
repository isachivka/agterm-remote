package dev.isachivka.bewareofsugar.update.play

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallException
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.ktx.requestAppUpdateInfo
import dev.isachivka.bewareofsugar.update.InstallChannel

/**
 * The only place this app touches Play's update library — REQ-0050.
 *
 * Deliberately thin, and deliberately untested by anything that runs off a device. Everything that
 * decides *what the owner is told* lives in [PlayUpdateCheck], which is a pure function over
 * [PlayUpdateInfo] and has real tests. What is left here is the part no test could honestly cover:
 * a call into a Play service that answers about the track an install came from.
 *
 * The same split as `GitHubApi` and `UpdateCheck.evaluate` on the other path.
 */
interface PlayUpdateSource {
    suspend fun check(nowEpochSeconds: Long): PlayUpdateStatus

    /**
     * Hands the update to Play, which downloads, installs and restarts the app.
     *
     * @return false when there is nothing to start — no check has succeeded, Play offered no
     * update, or it refused the immediate flow for this release. The caller shows what it already
     * knows rather than a spinner over nothing.
     */
    fun startImmediate(activity: Activity): Boolean
}

/**
 * Play's answer, for a copy of the app that came from Play.
 *
 * @param channel where this install came from. Passed in rather than read here so the caller owns
 * the decision and the tests can make it. On [InstallChannel.ELSEWHERE] no call is made at all:
 * Play's API reports on the track an install came from, and this one came from no track — asking
 * anyway would turn a knowable fact into an error message.
 */
class PlayUpdates(
    context: Context,
    private val channel: InstallChannel,
    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(context.applicationContext),
) : PlayUpdateSource {

    /**
     * The last thing Play said, kept because starting the flow needs the object and not a summary.
     *
     * `AppUpdateInfo` is a one-shot token: Play's own guidance is to request it again rather than
     * hold one indefinitely, so this is only ever the answer to the check the owner just made, and
     * [startImmediate] fails honestly rather than staleley when there is none.
     */
    private var lastInfo: AppUpdateInfo? = null

    override suspend fun check(nowEpochSeconds: Long): PlayUpdateStatus {
        if (channel != InstallChannel.PLAY) return PlayUpdateStatus.NotFromPlay

        return try {
            val info = manager.requestAppUpdateInfo()
            lastInfo = info
            PlayUpdateCheck.evaluate(
                PlayUpdateInfo(
                    availability = info.updateAvailability(),
                    availableVersionCode = info.availableVersionCode(),
                    immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
                ),
                nowEpochSeconds,
            )
        } catch (e: InstallException) {
            lastInfo = null
            PlayUpdateStatus.Failed(e.errorCode)
        } catch (e: Exception) {
            // Play's library throws its own types for its own failures and plain exceptions for the
            // rest - a missing or disabled Store, mostly. Neither is worth crashing an update screen
            // over, and the owner gets the same sentence either way.
            lastInfo = null
            PlayUpdateStatus.Failed(null)
        }
    }

    override fun startImmediate(activity: Activity): Boolean {
        val info = lastInfo ?: return false
        if (!info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) return false

        return runCatching {
            manager.startUpdateFlowForResult(
                info,
                activity,
                AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                IMMEDIATE_UPDATE_REQUEST,
            )
            true
        }.getOrDefault(false)
    }

    companion object {
        /**
         * IMMEDIATE, not FLEXIBLE, and the owner chose it: *"зафорсить"*.
         *
         * FLEXIBLE downloads in the background and then has to persuade the owner to restart, which
         * adds a "downloaded but not applied" state to an app that has none and would have to draw
         * one. IMMEDIATE hands the screen to Play until the new version is running.
         *
         * Either way Play performs the install, so `installingPackageName` stays
         * `com.android.vending` and Android Auto keeps listing the app. That is the whole reason
         * this path exists rather than a button that opens the Store.
         */
        const val IMMEDIATE_UPDATE_REQUEST = 0x0501
    }
}

/**
 * Opens this app's page in the Play Store — REQ-0050.
 *
 * The fallback for the two states where this app cannot do the update itself: Play has one but
 * refuses the immediate flow, and the copy did not come from Play at all. `market://` reaches the
 * Store app directly; the https form is what a device without it can still follow.
 *
 * Note what this is NOT: a substitute for the in-app flow. Sending the owner to the Store is a
 * handoff, and a handoff is exactly what the old updater was criticised for. It is here because the
 * alternative in those two states is a button that does nothing.
 */
fun playStoreIntent(packageName: String): Intent =
    Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Where [playStoreIntent] cannot be resolved, because no Play Store app is installed. */
fun playStoreWebIntent(packageName: String): Intent =
    Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri())
