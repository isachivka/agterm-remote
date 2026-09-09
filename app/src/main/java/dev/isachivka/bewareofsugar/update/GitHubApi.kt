package dev.isachivka.bewareofsugar.update

import dev.isachivka.bewareofsugar.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The one path to GitHub in this app.
 *
 * There is a single call here - `GET /repos/{owner}/{repo}/releases` - and both things the app needs
 * are built on it: proving a token works, and finding the newest release. That is deliberate. Two
 * code paths to the same endpoint would mean two status-to-state mappings drifting apart, and the
 * failure they would disagree about is the one that matters most: what an expired token looks like.
 *
 * The endpoint is the point. A
 * fine-grained token needs **Contents: read** to list releases or download a release asset, but only
 * **Metadata: read** to fetch the repository itself — so `GET /user` returns 200 for a token with
 * access to nothing, and `GET /repos/{owner}/{repo}` returns 200 for a token that can never download
 * an update. This is the weakest call that proves the permission the updater actually depends on,
 * and it is the same call iteration 2 will make to find the newest release.
 */
class GitHubApi(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: HttpUrl = GITHUB_API.toHttpUrl(),
    private val repoSlug: String = BuildConfig.GITHUB_REPO,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : TokenValidator, ReleaseSource {

    /**
     * Proves the token works for this repository, and discards the payload.
     *
     * Kept as its own name because that is what the token screen is asking, but it is the same call
     * as [releases] - so a token that validates is by definition a token that can read releases.
     */
    override suspend fun validate(token: GitHubToken): TokenValidationResult =
        // One release is enough to prove access; the update check asks for more because it has to
        // look past drafts and pre-releases.
        when (val result = releases(token, perPage = 1)) {
            is ApiResult.Success -> TokenValidationResult.Valid
            is ApiResult.Failure -> result.reason
        }

    /**
     * The newest releases, newest first.
     *
     * @param perPage how many to ask for. The check needs only the newest usable one, but a draft or
     * a pre-release at the head of the list would otherwise hide the newest stable release behind
     * it, so it asks for a few and filters.
     */
    override suspend fun releases(token: GitHubToken, perPage: Int): ApiResult<List<Release>> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(
                baseUrl.newBuilder()
                    .addPathSegments("repos/$repoSlug/releases")
                    .addQueryParameter("per_page", perPage.toString())
                    .build(),
            )
            // The token travels in a header and never in the URL or a query parameter, so it cannot
            // reach an exception message, a redirect target or anything that logs a URL.
            .header("Authorization", "Bearer ${token.value}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "BewareOfSugar/${BuildConfig.VERSION_NAME}")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (val classified = classify(response)) {
                    TokenValidationResult.Valid ->
                        // The body is read here, inside use{}, while the response is still open.
                        ApiResult.Success(ReleaseParser.parseReleases(response.body.string()))
                    else -> ApiResult.Failure(classified)
                }
            }
        } catch (e: IOException) {
            // No answer at all: airplane mode, no signal, DNS, timeout. This says nothing about the
            // token, so nothing stored is touched and nothing is marked bad.
            ApiResult.Failure(TokenValidationResult.Offline)
        }
    }

    private fun classify(response: Response): TokenValidationResult =
        classifyResponse(response, nowEpochSeconds)

    companion object {
        const val GITHUB_API = "https://api.github.com/"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // No logging interceptor, here or later: it would print the Authorization header.
            .build()

        private const val TIMEOUT_SECONDS = 15L
    }
}

/**
 * One place where an HTTP response becomes a state the owner can be told about.
 *
 * Top-level rather than a method, because the downloader classifies the same way: a 401 during a
 * download and a 401 during a check are the same fact about the same token, and two copies of this
 * decision would eventually disagree about which.
 */
internal fun classifyResponse(response: Response, nowEpochSeconds: () -> Long): TokenValidationResult = when {
    response.isSuccessful -> TokenValidationResult.Valid

    response.code == 401 -> TokenValidationResult.NotAccepted

    response.code == 404 -> TokenValidationResult.NoRepoAccess

    // Order matters, and this is the whole reason these two are written as one ordered block:
    // a permission failure and a rate limit are both 403, and only the headers separate them.
    // Telling the owner to wait until 14:32 for a permission problem sends them to wait for
    // something that waiting will never fix.
    response.code == 429 || (response.code == 403 && isRateLimited(response)) ->
        TokenValidationResult.RateLimited(resetAt(response, nowEpochSeconds))

    response.code == 403 -> TokenValidationResult.InsufficientPermission

    else -> TokenValidationResult.GitHubUnavailable(response.code)
}

/**
 * A rate-limited 403 carries `x-ratelimit-remaining: 0`, or a `retry-after` for a secondary
 * limit. A permission-denied 403 carries a full budget — GitHub also puts
 * `Resource not accessible by personal access token` in its body, which corroborates this but is
 * deliberately not what the decision keys on: parsing a prose message for control flow would
 * break the day GitHub rewords it, and iteration 1 has no JSON parser by design.
 */
private fun isRateLimited(response: Response): Boolean =
    response.header("x-ratelimit-remaining")?.toLongOrNull() == 0L ||
        response.header("retry-after") != null

private fun resetAt(response: Response, nowEpochSeconds: () -> Long): Long? {
    response.header("x-ratelimit-reset")?.toLongOrNull()?.let { return it }
    response.header("retry-after")?.toLongOrNull()?.let { return nowEpochSeconds() + it }
    return null
}
