package dev.isachivka.bewareofsugar.update

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Every answer GitHub can give, mapped to the state the owner sees.
 *
 * Against a real socket rather than a stubbed client, so the assertions about what we actually send —
 * the Authorization header, the API version, the path — mean something.
 *
 * The token here is deliberately not shaped like a real one.
 */
class GitHubApiTest {

    private lateinit var server: MockWebServer
    private lateinit var validator: GitHubApi

    private val token = GitHubToken("not-a-real-token")
    private val fixedNow = 1_700_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        validator = GitHubApi(
            client = GitHubApi.defaultClient(),
            baseUrl = server.url("/"),
            repoSlug = "isachivka/beware-of-sugar",
            nowEpochSeconds = { fixedNow },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun enqueue(code: Int, headers: Map<String, String> = emptyMap(), body: String = "[]") {
        val builder = MockResponse.Builder().code(code).body(body)
        headers.forEach { (name, value) -> builder.setHeader(name, value) }
        server.enqueue(builder.build())
    }

    private fun validate(): TokenValidationResult = runBlocking { validator.validate(token) }

    @Test
    fun `a 200 means the token can list this repository's releases`() {
        enqueue(200, body = """[{"tag_name":"v0.2.0"}]""")
        assertEquals(TokenValidationResult.Valid, validate())
    }

    @Test
    fun `an empty release list is still proof of access`() {
        enqueue(200, body = "[]")
        assertEquals(TokenValidationResult.Valid, validate())
    }

    @Test
    fun `the request carries the token, the media type and the API version`() {
        enqueue(200)
        validate()

        val recorded = server.takeRequest()
        assertEquals("Bearer not-a-real-token", recorded.headers["Authorization"])
        assertEquals("application/vnd.github+json", recorded.headers["Accept"])
        assertEquals("2022-11-28", recorded.headers["X-GitHub-Api-Version"])
        assertTrue(recorded.headers["User-Agent"].orEmpty().startsWith("BewareOfSugar/"))
    }

    @Test
    fun `the token never appears in the URL`() {
        enqueue(200)
        validate()

        val target = server.takeRequest().target
        assertEquals("/repos/isachivka/beware-of-sugar/releases?per_page=1", target)
        assertTrue(!target.contains(token.value))
    }

    @Test
    fun `a 401 is not accepted`() {
        enqueue(401, body = """{"message":"Bad credentials"}""")
        assertEquals(TokenValidationResult.NotAccepted, validate())
    }

    @Test
    fun `a 404 means the token cannot see this repository`() {
        enqueue(404, body = """{"message":"Not Found"}""")
        assertEquals(TokenValidationResult.NoRepoAccess, validate())
    }

    /**
     * The one that would hurt most to get backwards. A permission failure and a rate limit are both
     * 403; only the headers separate them. Mapping this to "rate limited" would tell the owner to
     * wait for a problem that waiting never fixes.
     */
    @Test
    fun `a 403 with budget left is a permission problem, not a rate limit`() {
        enqueue(
            403,
            headers = mapOf("x-ratelimit-remaining" to "4999", "x-ratelimit-reset" to "${fixedNow + 3600}"),
            body = """{"message":"Resource not accessible by personal access token"}""",
        )
        assertEquals(TokenValidationResult.InsufficientPermission, validate())
    }

    @Test
    fun `a 403 with no budget left is a rate limit`() {
        enqueue(
            403,
            headers = mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "${fixedNow + 600}"),
            body = """{"message":"API rate limit exceeded"}""",
        )
        assertEquals(TokenValidationResult.RateLimited(fixedNow + 600), validate())
    }

    @Test
    fun `a 429 is a rate limit whatever else it carries`() {
        enqueue(429, headers = mapOf("retry-after" to "120"))
        assertEquals(TokenValidationResult.RateLimited(fixedNow + 120), validate())
    }

    @Test
    fun `a secondary limit uses retry-after as a delta from now`() {
        enqueue(403, headers = mapOf("retry-after" to "60", "x-ratelimit-remaining" to "4998"))
        assertEquals(TokenValidationResult.RateLimited(fixedNow + 60), validate())
    }

    @Test
    fun `a rate limit with no reset header still reports a rate limit`() {
        enqueue(403, headers = mapOf("x-ratelimit-remaining" to "0"))
        assertEquals(TokenValidationResult.RateLimited(null), validate())
    }

    @Test
    fun `a 500 is GitHub's problem, not the token's`() {
        enqueue(500)
        assertEquals(TokenValidationResult.GitHubUnavailable(500), validate())
    }

    @Test
    fun `a 503 is GitHub's problem too`() {
        enqueue(503)
        assertEquals(TokenValidationResult.GitHubUnavailable(503), validate())
    }

    @Test
    fun `an unreachable server is offline, and says nothing about the token`() {
        // Shut down before calling: the port refuses the connection, which is what no network looks
        // like to the client. Deliberately not a socket policy, so this test does not depend on
        // MockWebServer's fault-injection API.
        val deadUrl = server.url("/")
        server.close()
        val offline = GitHubApi(
            client = GitHubApi.defaultClient(),
            baseUrl = deadUrl,
            repoSlug = "isachivka/beware-of-sugar",
            nowEpochSeconds = { fixedNow },
        )

        assertEquals(TokenValidationResult.Offline, runBlocking { offline.validate(token) })
    }

    @Test
    fun `releases returns what GitHub sent, parsed`() {
        enqueue(200, body = """[{"tag_name":"v0.4.0","name":"n","body":"notes","assets":[{"id":7,"name":"a.apk","size":9}]}]""")

        val result = runBlocking { validator.releases(token) }

        assertTrue(result is ApiResult.Success)
        val releases = (result as ApiResult.Success).value
        assertEquals("v0.4.0", releases.single().tag)
        assertEquals(7L, releases.single().assets.single().id)
    }

    @Test
    fun `releases asks for enough to see past a draft`() {
        enqueue(200)
        runBlocking { validator.releases(token) }
        assertEquals(
            "/repos/isachivka/beware-of-sugar/releases?per_page=10",
            server.takeRequest().target,
        )
    }

    @Test
    fun `a failed release fetch carries the same classified reason as validation`() {
        // The point of one code path: the update check and the token screen cannot disagree about
        // what a 401 means.
        enqueue(401, body = """{"message":"Bad credentials"}""")

        val result = runBlocking { validator.releases(token) }

        assertEquals(ApiResult.Failure(TokenValidationResult.NotAccepted), result)
    }

    @Test
    fun `a rate limited release fetch carries its reset time through`() {
        enqueue(403, headers = mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "${fixedNow + 600}"))

        val result = runBlocking { validator.releases(token) }

        assertEquals(ApiResult.Failure(TokenValidationResult.RateLimited(fixedNow + 600)), result)
    }

    @Test
    fun `a 200 carrying unreadable json is no releases rather than a failure`() {
        // GitHub answered and the token is fine; the payload is the problem, and an empty list is
        // what "nothing to offer" looks like.
        enqueue(200, body = "not json")

        val result = runBlocking { validator.releases(token) }

        assertEquals(ApiResult.Success(emptyList<Release>()), result)
    }

    @Test
    fun `an offline release fetch is a failure, not an empty list`() {
        val deadUrl = server.url("/")
        server.close()
        val offline = GitHubApi(
            client = GitHubApi.defaultClient(),
            baseUrl = deadUrl,
            repoSlug = "isachivka/beware-of-sugar",
            nowEpochSeconds = { fixedNow },
        )

        assertEquals(
            ApiResult.Failure(TokenValidationResult.Offline),
            runBlocking { offline.releases(token) },
        )
    }

    @Test
    fun `the API base URL is the real one by default`() {
        assertEquals("https://api.github.com/".toHttpUrl(), GitHubApi.GITHUB_API.toHttpUrl())
    }
}
