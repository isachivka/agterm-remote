package dev.isachivka.bewareofsugar.update

/**
 * Everything the token screen can be.
 *
 * The plan enumerates twelve states; they are two axes rather than twelve classes, because the field
 * contents and the outcome of the last attempt vary independently. The mapping:
 *
 *  1. No token          — [status] NoToken, [input] empty
 *  2. Typing            — [status] NoToken, [input] non-blank ([canSave] true)
 *  3. Validating        — [status] Validating
 *  4. Saved             — [status] Saved
 *  5. Not accepted      — [status] NotAccepted
 *  6. No repo access    — [status] NoRepoAccess
 *  7. Not enough rights — [status] InsufficientPermission
 *  8. Rate-limited      — [status] RateLimited
 *  9. Offline           — [status] Offline
 * 10. GitHub is down    — [status] GitHubUnavailable
 * 11. Unreadable        — [status] Unreadable
 * 12. Removed           — [status] NoToken, [input] empty, arrived at from Saved
 * 13. Not stored        — [status] NotStored: GitHub accepted it, the phone would not keep it
 *
 * [toString] is overridden because [input] holds the token while it is being typed, and a data
 * class would print it into any log line or crash trace that dumped the state.
 */
data class TokenScreenState(
    val input: String = "",
    val status: TokenStatus = TokenStatus.NoToken,
    /** The owner tapped Replace over a saved token: show the field, but keep the stored one until a new one validates. */
    val replacing: Boolean = false,
) {
    /** Nothing to validate until something has been typed, and not twice at once. */
    val canSave: Boolean
        get() = GitHubToken.fromInput(input) != null && status != TokenStatus.Validating

    val isSaved: Boolean
        get() = status is TokenStatus.Saved

    /** Hidden only while a saved token is sitting there unchallenged. */
    val showsField: Boolean
        get() = !isSaved || replacing

    override fun toString(): String = "TokenScreenState(input=${input.length} chars, status=$status)"
}

sealed interface TokenStatus {

    data object NoToken : TokenStatus

    data object Validating : TokenStatus

    data class Saved(val validatedAtEpochSeconds: Long) : TokenStatus

    /**
     * A stored token that would not decrypt — and is still stored, REQ-0047. The screen says why and
     * since when; [permanent] picks between "paste a new one" and "it may read again later".
     */
    data class Unreadable(val permanent: Boolean, val reason: String, val sinceEpochSeconds: Long) : TokenStatus

    /**
     * The token is good and the phone would not store it.
     *
     * Its own state rather than a shrug, because the owner's next move depends entirely on knowing
     * the token was not the problem — otherwise they go and mint a replacement for a fault that had
     * nothing to do with it. What they typed is kept so Save can simply be pressed again.
     */
    data object NotStored : TokenStatus

    data object NotAccepted : TokenStatus

    data object NoRepoAccess : TokenStatus

    data object InsufficientPermission : TokenStatus

    data class RateLimited(val resetAtEpochSeconds: Long?) : TokenStatus

    data object Offline : TokenStatus

    data class GitHubUnavailable(val statusCode: Int) : TokenStatus
}

/**
 * The API's answer, as a screen state.
 *
 * A rejected token is never stored, and — this is the part that matters on a train — [Offline] and
 * [GitHubUnavailable] say nothing about the token, so neither one may cause a stored one to be
 * dropped.
 */
fun TokenValidationResult.toStatus(validatedAtEpochSeconds: Long): TokenStatus = when (this) {
    TokenValidationResult.Valid -> TokenStatus.Saved(validatedAtEpochSeconds)
    TokenValidationResult.NotAccepted -> TokenStatus.NotAccepted
    TokenValidationResult.NoRepoAccess -> TokenStatus.NoRepoAccess
    TokenValidationResult.InsufficientPermission -> TokenStatus.InsufficientPermission
    is TokenValidationResult.RateLimited -> TokenStatus.RateLimited(resetAtEpochSeconds)
    TokenValidationResult.Offline -> TokenStatus.Offline
    is TokenValidationResult.GitHubUnavailable -> TokenStatus.GitHubUnavailable(statusCode)
}
