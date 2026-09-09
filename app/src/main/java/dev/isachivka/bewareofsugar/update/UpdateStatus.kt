package dev.isachivka.bewareofsugar.update

/**
 * What the update check has to say.
 *
 * Every one of these is quiet. REQ-0003 asks for an updater that is ignorable, and one that opens
 * with a dialog because GitHub was briefly unreachable is not — so a failed check is a line on a
 * screen the owner chose to open, never something that interrupts the greeting.
 */
sealed interface UpdateStatus {

    /** Nothing has been checked yet in this session, and nothing is being claimed. */
    data object Idle : UpdateStatus

    data object Checking : UpdateStatus

    /** @param checkedAtEpochSeconds when this answer was obtained, so the screen can say when. */
    data class UpToDate(val checkedAtEpochSeconds: Long) : UpdateStatus

    data class Available(val release: Release, val checkedAtEpochSeconds: Long) : UpdateStatus

    /** No token stored, so there is nothing to check with. Not a failure — an unstarted setup. */
    data object NoToken : UpdateStatus

    /**
     * A token IS stored and this phone could not decrypt it — REQ-0047. Its own state because it
     * used to be shown as [NoToken], and *"No token yet"* over a token the owner pasted yesterday is
     * the sentence that sent him to mint a new one three times. The token screen says why.
     */
    data object TokenUnreadable : UpdateStatus

    /**
     * A token that used to work and does not now.
     *
     * This is the state iteration 1 could describe but not detect. `lastValidatedAt` records that
     * GitHub accepted this token once, so a 401 today means it expired or was revoked rather than
     * that it was mistyped — and REQ-0003 is explicit that this is a normal state, not an error.
     */
    data object TokenExpired : UpdateStatus

    /** A 401 for a token with no record of ever having worked. Says the same thing the token screen does. */
    data object TokenNotAccepted : UpdateStatus

    data object NoRepoAccess : UpdateStatus

    data object InsufficientPermission : UpdateStatus

    data class RateLimited(val resetAtEpochSeconds: Long?) : UpdateStatus

    data object Offline : UpdateStatus

    data class GitHubUnavailable(val statusCode: Int) : UpdateStatus

    /** The check worked and the newest release's tag is not a version this app can compare. */
    data object UnreadableRelease : UpdateStatus
}

/**
 * Decides what a completed check means.
 *
 * Separated from anything that does IO so every branch is a plain unit test.
 */
object UpdateCheck {

    /**
     * @param installed what is running now, from `BuildConfig.VERSION_NAME`.
     * @param hasWorkedBefore whether GitHub has ever accepted this token — the difference between
     * "expired" and "wrong".
     */
    fun evaluate(
        result: ApiResult<List<Release>>,
        installed: AppVersion?,
        nowEpochSeconds: Long,
        hasWorkedBefore: Boolean,
    ): UpdateStatus = when (result) {
        is ApiResult.Failure -> failure(result.reason, hasWorkedBefore)
        is ApiResult.Success -> success(result.value, installed, nowEpochSeconds)
    }

    private fun failure(reason: TokenValidationResult, hasWorkedBefore: Boolean): UpdateStatus =
        when (reason) {
            TokenValidationResult.NotAccepted ->
                if (hasWorkedBefore) UpdateStatus.TokenExpired else UpdateStatus.TokenNotAccepted
            TokenValidationResult.NoRepoAccess -> UpdateStatus.NoRepoAccess
            TokenValidationResult.InsufficientPermission -> UpdateStatus.InsufficientPermission
            is TokenValidationResult.RateLimited -> UpdateStatus.RateLimited(reason.resetAtEpochSeconds)
            TokenValidationResult.Offline -> UpdateStatus.Offline
            is TokenValidationResult.GitHubUnavailable -> UpdateStatus.GitHubUnavailable(reason.statusCode)
            // Valid is not a failure and cannot arrive here; mapped rather than thrown, because a
            // crash on launch is never the right answer to an impossible branch.
            TokenValidationResult.Valid -> UpdateStatus.UpToDate(0L)
        }

    private fun success(
        releases: List<Release>,
        installed: AppVersion?,
        nowEpochSeconds: Long,
    ): UpdateStatus {
        // Drafts are not published and pre-releases are a channel REQ-0003 puts out of scope, so
        // neither is ever offered - and neither may hide a stable release sitting behind it.
        val candidates = releases.filterNot { it.draft || it.prerelease }
        if (candidates.isEmpty()) return UpdateStatus.UpToDate(nowEpochSeconds)

        val newest = candidates.mapNotNull { release ->
            release.version?.let { release to it }
        }.maxByOrNull { (_, version) -> version }
            ?: return UpdateStatus.UnreadableRelease

        val (release, version) = newest
        // Strictly greater. An equal tag is the version already running, and an older one is
        // somebody re-publishing history - offering either would be a downgrade Android would
        // refuse at the final tap anyway.
        return if (installed != null && version > installed) {
            UpdateStatus.Available(release, nowEpochSeconds)
        } else {
            UpdateStatus.UpToDate(nowEpochSeconds)
        }
    }
}
