package dev.isachivka.bewareofsugar.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The download, including the ways it ends badly.
 *
 * Two of these are the reason this class exists. A partial file must never become something the
 * installer is offered, and a retry must never append to one — both are proven by killing a transfer
 * rather than by reasoning about the code that handles it.
 */
class ReleaseDownloaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloadDir: File

    private val token = GitHubToken("not-a-real-token")
    private val payload = ByteArray(64 * 1024) { (it % 251).toByte() }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloadDir = folder.newFolder("updates")
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun downloader(
        usableSpace: Long = Long.MAX_VALUE,
        dir: File = downloadDir,
    ) = ReleaseDownloader(
        downloadDir = dir,
        client = GitHubApi.defaultClient(),
        baseUrl = server.url("/"),
        repoSlug = "isachivka/beware-of-sugar",
        usableSpaceBytes = { usableSpace },
        nowEpochSeconds = { 1_700_000_000L },
    )

    private fun asset(size: Long = payload.size.toLong()) =
        ReleaseAsset(id = 490736218L, name = "beware-of-sugar-0.3.0.apk", sizeBytes = size)

    private fun partialFile() = File(downloadDir, "update-490736218.apk.part")

    @Test
    fun `a whole asset lands on disk with its bytes intact`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(payload)).build())

        val outcome = downloader().download(token, asset())

        assertTrue(outcome is DownloadOutcome.Ready)
        val apk = (outcome as DownloadOutcome.Ready).apk
        assertTrue(apk.exists())
        assertTrue("the bytes must be the bytes", apk.readBytes().contentEquals(payload))
        assertFalse("no .part may survive a success", partialFile().exists())
    }

    @Test
    fun `progress is measured in bytes actually written`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(payload)).build())
        val seen = mutableListOf<Long>()

        downloader().download(token, asset()) { written, total ->
            seen += written
            assertEquals(payload.size.toLong(), total)
        }

        assertTrue("progress must be reported at all", seen.isNotEmpty())
        assertEquals("and must end at the whole file", payload.size.toLong(), seen.last())
        assertEquals("and never go backwards", seen.sorted(), seen)
    }

    @Test
    fun `the request asks for the bytes and carries the token`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(payload)).build())

        downloader().download(token, asset())

        val recorded = server.takeRequest()
        assertEquals("/repos/isachivka/beware-of-sugar/releases/assets/490736218", recorded.target)
        // Without this GitHub sends the JSON description of the asset instead of the asset, and the
        // "download" succeeds while producing something the installer cannot read.
        assertEquals("application/octet-stream", recorded.headers["Accept"])
        assertEquals("Bearer not-a-real-token", recorded.headers["Authorization"])
    }

    /**
     * The redirect that must not leak the token.
     *
     * GitHub answers the asset endpoint with a 302 to its own downloads host. OkHttp drops
     * `Authorization` when a redirect crosses hosts, which is what keeps the token from reaching a
     * third party — and what stops the far end rejecting a request that carries both its own
     * query-string auth and ours. Asserted, because the whole design leans on it.
     */
    @Test
    fun `the token is not forwarded when the download redirects to another host`() = runBlocking {
        val storage = MockWebServer()
        storage.start()
        try {
            storage.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(payload)).build())
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .headers(Headers.headersOf("Location", storage.url("/signed-url").toString()))
                    .build(),
            )

            val outcome = downloader().download(token, asset())

            assertTrue("the redirect must still produce the file", outcome is DownloadOutcome.Ready)
            server.takeRequest()
            val redirected = storage.takeRequest()
            assertNull(
                "the token must not reach the storage host",
                redirected.headers["Authorization"],
            )
        } finally {
            storage.close()
        }
    }

    /**
     * The failure everybody skips. The socket dies with the file half written; what must not happen
     * is a partial `.apk` being offered to the installer, which rejects it with an error the owner
     * can do nothing about.
     */
    @Test
    fun `a download killed mid-flight leaves nothing behind`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(okio.Buffer().write(payload))
                .onResponseBody(SocketEffect.CloseSocket(closeSocket = true))
                .build(),
        )

        val outcome = downloader().download(token, asset())

        assertEquals(DownloadOutcome.Failed(DownloadFailure.Interrupted), outcome)
        assertFalse("no partial may survive", partialFile().exists())
        assertFalse("and nothing may be offered to the installer", File(downloadDir, "update-490736218.apk").exists())
    }

    @Test
    fun `cancelling mid-download leaves nothing behind`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(okio.Buffer().write(payload))
                // Slow enough that the cancellation lands mid-transfer rather than after it.
                .throttleBody(1024, 200, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build(),
        )

        val job = launch(Dispatchers.IO) {
            downloader().download(token, asset())
        }
        // Let some bytes arrive, then walk away, as the owner leaving the screen would.
        while (!partialFile().exists() && job.isActive) Thread.sleep(10)
        job.cancelAndJoin()

        assertFalse("a cancelled download must clean up after itself", partialFile().exists())
        assertFalse(File(downloadDir, "update-490736218.apk").exists())
    }

    /**
     * The one that turns a failed download into a corrupt install: a retry that appends to what the
     * last attempt left behind produces a file of exactly the right size and entirely wrong content.
     */
    @Test
    fun `a retry starts clean rather than resuming onto a stale partial`() = runBlocking {
        // What a previous, failed attempt left behind.
        partialFile().writeBytes(ByteArray(4096) { 0x7F })
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(payload)).build())

        val outcome = downloader().download(token, asset())

        val apk = (outcome as DownloadOutcome.Ready).apk
        assertEquals("no stale bytes may be prepended", payload.size, apk.readBytes().size)
        assertTrue(apk.readBytes().contentEquals(payload))
        // And no Range header was sent: this is a fresh transfer, not a resumption.
        assertNull(server.takeRequest().headers["Range"])
    }

    /**
     * The size GitHub published against the bytes that arrived. A short `.apk` reaches the installer
     * as an unparseable package and a useless error - exactly the dead end REQ-0003 is written
     * against.
     */
    @Test
    fun `an asset that arrives short of its published size is refused`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(payload)).build())

        // GitHub says the asset is twice what actually arrives.
        val outcome = downloader().download(token, asset(size = payload.size * 2L))

        assertEquals(
            DownloadOutcome.Failed(DownloadFailure.Truncated(payload.size * 2L, payload.size.toLong())),
            outcome,
        )
        assertFalse(partialFile().exists())
        assertFalse(File(downloadDir, "update-490736218.apk").exists())
    }

    @Test
    fun `no space is reported before anything is downloaded`() = runBlocking {
        val outcome = downloader(usableSpace = 1024L).download(token, asset(size = 50L * 1024 * 1024))

        assertEquals(
            DownloadOutcome.Failed(DownloadFailure.NotEnoughSpace(50L * 1024 * 1024, 1024L)),
            outcome,
        )
        assertEquals("and GitHub is not troubled for a download that cannot land", 0, server.requestCount)
    }

    @Test
    fun `a token that stopped working mid-feature is classified, not called a network error`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())

        val outcome = downloader().download(token, asset())

        assertEquals(
            DownloadOutcome.Failed(DownloadFailure.Rejected(TokenValidationResult.NotAccepted)),
            outcome,
        )
        assertFalse(partialFile().exists())
    }

    @Test
    fun `a rate limit during download keeps its reset time`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .headers(Headers.headersOf("x-ratelimit-remaining", "0", "x-ratelimit-reset", "1700000600"))
                .build(),
        )

        val outcome = downloader().download(token, asset())

        assertEquals(
            DownloadOutcome.Failed(DownloadFailure.Rejected(TokenValidationResult.RateLimited(1_700_000_600L))),
            outcome,
        )
    }

    @Test
    fun `an unreachable server is an interruption, not a silent success`() = runBlocking {
        val deadUrl = server.url("/")
        server.close()
        val offline = ReleaseDownloader(
            downloadDir = downloadDir,
            client = GitHubApi.defaultClient(),
            baseUrl = deadUrl,
            repoSlug = "isachivka/beware-of-sugar",
            usableSpaceBytes = { Long.MAX_VALUE },
            nowEpochSeconds = { 1_700_000_000L },
        )

        assertEquals(
            DownloadOutcome.Failed(DownloadFailure.Interrupted),
            offline.download(token, asset()),
        )
    }

    @Test
    fun `discarding clears everything the downloader left`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(payload)).build())
        val downloader = downloader()
        downloader.download(token, asset())

        downloader.discard()

        assertEquals(0, downloadDir.listFiles()?.size ?: 0)
    }
}
