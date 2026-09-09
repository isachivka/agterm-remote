package dev.isachivka.bewareofsugar.update

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole flow on a device: check, download, and the handoff, against a server standing in for
 * GitHub.
 *
 * **What this proves and what it cannot.** Everything up to the moment Android's installer opens is
 * exercised here on real storage with real sockets. The install itself is not: Android's
 * confirmation is a person tapping a button, and no test on any machine can stand in for that. The
 * emulator also has no StrongBox and installs debug-signed builds over debug-signed builds, which is
 * not the path the owner walks. Those three are what `docs/qa/update-on-device.md` exists for.
 */
@RunWith(AndroidJUnit4::class)
class UpdateFlowInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var downloadDir: File

    private val apkBytes = ByteArray(96 * 1024) { (it % 251).toByte() }
    private val assetId = 490736218L
    private val token = GitHubToken("not-a-real-token")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloadDir = File(context.filesDir, "updates").apply { mkdirs() }
        downloadDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        server.close()
        downloadDir.listFiles()?.forEach { it.delete() }
    }

    private fun downloader() = ReleaseDownloader(
        downloadDir = downloadDir,
        baseUrl = server.url("/"),
        repoSlug = "isachivka/beware-of-sugar",
        usableSpaceBytes = { UpdateInstaller.allocatableBytes(context, downloadDir) },
    )

    private val asset = ReleaseAsset(assetId, "beware-of-sugar-0.4.0.apk", apkBytes.size.toLong())

    private fun releasesJson(tag: String) = """
        [{"tag_name":"$tag","name":"$tag","body":"## What's Changed\n* feat: something\n",
          "draft":false,"prerelease":false,
          "assets":[{"id":$assetId,"name":"beware-of-sugar-0.4.0.apk","size":${apkBytes.size}}]}]
    """.trimIndent()

    private class Store(private val token: GitHubToken) : TokenStore {
        var checkedAt = 0L
        var foundTag: String? = null
        override suspend fun read(): StoredToken = StoredToken.Present(token, 1_699_000_000L)
        override suspend fun save(token: GitHubToken, validatedAtEpochSeconds: Long) = true
        override suspend fun clear() = Unit
        override suspend fun lastCheckedAt(): Long = checkedAt
        override suspend fun recordChecked(atEpochSeconds: Long) { checkedAt = atEpochSeconds }
        override suspend fun lastFoundTag(): String? = foundTag
        override suspend fun recordFound(tag: String?) { foundTag = tag }
    }

    private fun <T> awaitValue(
        flow: kotlinx.coroutines.flow.StateFlow<T>,
        timeoutMillis: Long = 15_000,
        predicate: (T) -> Boolean,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val value = flow.value
            if (predicate(value)) return value
            Thread.sleep(25)
        }
        throw AssertionError("timed out after ${timeoutMillis}ms; last value was ${flow.value}")
    }

    /** Check to downloaded file, driven through the ViewModel exactly as the screen drives it. */
    @Test
    fun checkThenDownloadEndsWithAnInstallableFile() {
        server.enqueue(MockResponse.Builder().code(200).body(releasesJson("v9.9.9")).build())
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(apkBytes)).build())

        val viewModel = UpdateViewModel(
            store = Store(token),
            api = GitHubApi(baseUrl = server.url("/"), repoSlug = "isachivka/beware-of-sugar"),
            downloader = downloader(),
            installed = AppVersion(0, 3, 0),
            nowEpochSeconds = { 1_700_000_000L },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        // The real API hops to Dispatchers.IO, so the state settles a moment after the call
        // returns. Waited for rather than assumed - the alternative is a test that passes because
        // it looked at the right moment.
        val status = awaitValue(viewModel.status) { it is UpdateStatus.Available }
        assertEquals("v9.9.9", (status as UpdateStatus.Available).release.tag)

        viewModel.startDownload(canInstall = true)

        val download = awaitValue(viewModel.download) { it is DownloadState.ReadyToInstall }
        val apk = (download as DownloadState.ReadyToInstall).apk
        assertTrue(apk.readBytes().contentEquals(apkBytes))

        // And Android would take it: the intent resolves to a real activity on this device.
        val intent = UpdateInstaller.installIntent(context, apk)
        assertNotNull(
            "no activity on this device would handle the install intent",
            context.packageManager.resolveActivity(intent, 0),
        )
        assertEquals(Intent.ACTION_VIEW, intent.action)
    }

    /**
     * The failure the owner is most likely to meet: signal lost mid-download. On a real filesystem,
     * because "nothing was left behind" is a claim about a real directory.
     */
    @Test
    fun anInterruptedDownloadLeavesTheDirectoryClean() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(Buffer().write(apkBytes))
                .onResponseBody(SocketEffect.CloseSocket(closeSocket = true))
                .build(),
        )

        val outcome = downloader().download(token, asset)

        assertEquals(DownloadOutcome.Failed(DownloadFailure.Interrupted), outcome)
        assertEquals(
            "nothing may be left for the installer to find",
            0,
            downloadDir.listFiles()?.size ?: 0,
        )
    }

    @Test
    fun aTruncatedAssetIsNeverOfferedToTheInstaller() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(apkBytes)).build())

        // GitHub says the asset is bigger than what arrives.
        val outcome = downloader().download(token, asset.copy(sizeBytes = apkBytes.size * 2L))

        assertTrue(outcome is DownloadOutcome.Failed)
        assertEquals(0, downloadDir.listFiles()?.size ?: 0)
    }

    @Test
    fun aTokenThatExpiredBetweenCheckAndDownloadIsSaidPlainly() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())

        val outcome = downloader().download(token, asset)

        assertEquals(
            DownloadOutcome.Failed(DownloadFailure.Rejected(TokenValidationResult.NotAccepted)),
            outcome,
        )
        assertEquals(0, downloadDir.listFiles()?.size ?: 0)
    }

    /**
     * A second run after a failure must not build on what the first one left, on a real filesystem
     * rather than a temporary folder.
     */
    @Test
    fun aRetryAfterAFailureProducesAWholeFile() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(Buffer().write(apkBytes))
                .onResponseBody(SocketEffect.CloseSocket(closeSocket = true))
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(apkBytes)).build())

        val downloader = downloader()
        assertTrue(downloader.download(token, asset) is DownloadOutcome.Failed)
        val second = downloader.download(token, asset)

        val apk = (second as DownloadOutcome.Ready).apk
        assertTrue("the retry must produce the whole file, not a doubled one", apk.readBytes().contentEquals(apkBytes))
        assertFalse(File(downloadDir, "update-$assetId.apk.part").exists())
    }
}
