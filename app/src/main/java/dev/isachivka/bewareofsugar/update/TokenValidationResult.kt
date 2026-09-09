package dev.isachivka.bewareofsugar.update

/**
 * What the API said about a token.
 *
 * Shared with iteration 2 on purpose: the release check makes the same call against the same repo,
 * so "the token stopped working, and here is how" should have one answer in this app, not two.
 */
/**
 * What a call to GitHub produced: what was asked for, or the reason it could not be had.
 *
 * The failure side is [TokenValidationResult] rather than a second failure vocabulary, so every
 * caller classifies "GitHub said no" the same way and the copy cannot diverge between the token
 * screen and the update check.
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val reason: TokenValidationResult) : ApiResult<Nothing>
}

/**
 * Split from [GitHubApi] so the screen's logic can be tested on the JVM without a socket,
 * and so iteration 2 can reuse the same seam.
 */
interface TokenValidator {
    suspend fun validate(token: GitHubToken): TokenValidationResult
}

/**
 * Where releases come from. The same seam as [TokenValidator] and for the same reason: the update
 * check is testable without a socket, and both are implemented by the one [GitHubApi].
 */
interface ReleaseSource {
    suspend fun releases(token: GitHubToken, perPage: Int = DEFAULT_RELEASES_PER_PAGE): ApiResult<List<Release>>
}

/**
 * Enough to see past a draft or a pre-release sitting at the head of the list, and small enough that
 * the launch check stays one cheap call.
 */
const val DEFAULT_RELEASES_PER_PAGE = 10

sealed interface TokenValidationResult {

    /** 200: this token can list this repository's releases, which is the permission the updater needs. */
    data object Valid : TokenValidationResult

    /**
     * 401. GitHub says "Bad credentials" for a mistyped token and for an expired or revoked one
     * alike, so at entry these cannot be told apart and the copy covers both. Iteration 2 can tell
     * them apart, because by then the token has a record of having worked.
     */
    data object NotAccepted : TokenValidationResult

    /** 404: a live token that cannot see this repository. GitHub hides private repos behind 404. */
    data object NoRepoAccess : TokenValidationResult

    /** 403 that is not a rate limit: the token can see the repo but lacks Contents: Read-only. */
    data object InsufficientPermission : TokenValidationResult

    /** @param resetAtEpochSeconds when the limit lifts, when GitHub said; null when it did not. */
    data class RateLimited(val resetAtEpochSeconds: Long?) : TokenValidationResult

    /** The request never got an answer: no network, DNS failure, timeout. Says nothing about the token. */
    data object Offline : TokenValidationResult

    /** GitHub answered with something we did not ask about — a 5xx, or anything unaccounted for. */
    data class GitHubUnavailable(val statusCode: Int) : TokenValidationResult
}
