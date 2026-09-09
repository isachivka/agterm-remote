package dev.isachivka.bewareofsugar.update.play

/**
 * What Play had to say about an update — REQ-0050.
 *
 * The same rule the GitHub path follows in [dev.isachivka.bewareofsugar.update.UpdateStatus]: every
 * state here is quiet. A check that could not reach Play is a line on a screen the owner chose to
 * open, never a dialog over the greeting.
 */
sealed interface PlayUpdateStatus {

    /** Nothing has been asked yet in this session, and nothing is being claimed. */
    data object Idle : PlayUpdateStatus

    data object Checking : PlayUpdateStatus

    /** @param checkedAtEpochSeconds when this answer was obtained, so the screen can say when. */
    data class UpToDate(val checkedAtEpochSeconds: Long) : PlayUpdateStatus

    /**
     * Play has a newer build on the track this install came from.
     *
     * @param versionCode what Play is offering. Shown rather than a version *name*, because that is
     * all Play reports — and the two are related by [versionCodeOf], which is arithmetic the owner
     * should not have to do in their head. The screen turns it back into a name.
     * @param immediateAllowed whether Play will run the blocking flow for this release. When false
     * the update is real but this app cannot force it, and the screen sends the owner to the Store
     * rather than pretending a button will work.
     */
    data class Available(
        val versionCode: Int,
        val checkedAtEpochSeconds: Long,
        val immediateAllowed: Boolean,
    ) : PlayUpdateStatus

    /**
     * An update this app started earlier is still being applied by Play.
     *
     * Its own state rather than folded into [Available], because the correct action differs: there
     * is nothing to start, only something to resume, and offering "Update" here starts a second
     * flow over the first.
     */
    data class InProgress(val checkedAtEpochSeconds: Long) : PlayUpdateStatus

    /**
     * This copy did not come from Play, so Play has nothing to say about it.
     *
     * Not a failure and not an error — the app is working exactly as installed. It is the state that
     * replaced the GitHub updater's whole screen for such a build, and the copy on it has to explain
     * that updates now arrive by a route this install is not on.
     */
    data object NotFromPlay : PlayUpdateStatus

    /**
     * Play answered, and its answer was that it does not know.
     *
     * `UpdateAvailability.UNKNOWN` is what Play returns when it cannot determine the state — a
     * signed-out Store, a build it has no record of. Distinct from [Failed], which is Play not
     * answering at all, because the remedies differ and neither is "try again immediately".
     */
    data class Unknown(val checkedAtEpochSeconds: Long) : PlayUpdateStatus

    /**
     * The request to Play failed.
     *
     * @param errorCode Play's own code where it gave one. Shown, because the owner reporting a
     * number is worth more than the owner reporting "it said it did not work".
     */
    data class Failed(val errorCode: Int?) : PlayUpdateStatus
}

/**
 * The three facts this app acts on, lifted out of Play's `AppUpdateInfo`.
 *
 * This type exists so the decision below is testable. `AppUpdateInfo` is a final class from a
 * Play library with no public constructor, so a mapping written directly against it could only be
 * exercised on a device with a real update waiting — which is to say, never. Same separation the
 * GitHub path uses between `GitHubApi` and `UpdateCheck.evaluate`.
 */
data class PlayUpdateInfo(
    /** Play's `UpdateAvailability` constant. See [PlayUpdateCheck] for the ones that matter. */
    val availability: Int,
    /** Meaningless unless [availability] says an update exists. */
    val availableVersionCode: Int,
    val immediateAllowed: Boolean,
)

/** Turns what Play said into what the screen shows. */
object PlayUpdateCheck {

    // Play's own constants, restated rather than imported.
    //
    // Importing UpdateAvailability here would drag the Play library onto the unit-test classpath,
    // where it is a stub that throws - the same trap org.json is in, and the reason this project
    // keeps a real json implementation for tests. These four values are part of Play's published
    // API and do not move; if they ever did, PlayUpdates would stop compiling against the real
    // constants, which is the check that matters.
    const val UNKNOWN = 0
    const val UPDATE_NOT_AVAILABLE = 1
    const val UPDATE_AVAILABLE = 2
    const val DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS = 3

    fun evaluate(info: PlayUpdateInfo, nowEpochSeconds: Long): PlayUpdateStatus =
        when (info.availability) {
            UPDATE_AVAILABLE -> PlayUpdateStatus.Available(
                versionCode = info.availableVersionCode,
                checkedAtEpochSeconds = nowEpochSeconds,
                immediateAllowed = info.immediateAllowed,
            )

            UPDATE_NOT_AVAILABLE -> PlayUpdateStatus.UpToDate(nowEpochSeconds)

            DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> PlayUpdateStatus.InProgress(nowEpochSeconds)

            // UNKNOWN and anything Play adds later. A value this code has never seen is not
            // evidence that the app is up to date, and reporting it as such is the one wrong answer
            // here: it is the answer that tells the owner to stop looking.
            else -> PlayUpdateStatus.Unknown(nowEpochSeconds)
        }
}
