package dev.isachivka.bewareofsugar.update

/**
 * A GitHub personal access token.
 *
 * The point of the wrapper is [toString]. A raw `String` prints itself into any log line, exception
 * message or `data class` dump that happens to interpolate it, and the token is the one value in
 * this app that must never appear in any of those. Wrapping it means an accidental
 * `"...$token..."` prints `GitHubToken(len=93)` instead of the secret — see `TokenRedactionTest`.
 *
 * The value is held in an ordinary immutable `String`, which cannot be zeroed and therefore lives in
 * the heap until it is collected. That is a considered trade-off, not an oversight; see the note in
 * `DataStoreTokenStore` for why a `CharArray` is not an improvement here.
 */
@JvmInline
value class GitHubToken(val value: String) {

    override fun toString(): String = "GitHubToken(len=${value.length})"

    companion object {
        /**
         * The token as typed, trimmed, or null when what was typed is only whitespace.
         *
         * Trimming happens here and nowhere else, so there is a single answer to "was this
         * trimmed before it was used": a pasted token almost always carries a trailing newline,
         * and that is the normal case rather than the edge case.
         */
        fun fromInput(raw: String): GitHubToken? = raw.trim().takeIf { it.isNotEmpty() }?.let(::GitHubToken)
    }
}
