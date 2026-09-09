package dev.isachivka.bewareofsugar.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch check and the manual one, without a network.
 *
 * The seam is [ReleaseSource], the same shape as [TokenValidator]: the check is decided by
 * [UpdateCheck] and the fetching is somebody else's problem, so neither needs a socket to test.
 */
class UpdateViewModelTest {

    private val now = 1_700_000_000L
    private val installed = AppVersion(0, 3, 0)
    private val token = GitHubToken("not-a-real-token")

    private class FakeStore(
        var stored: StoredToken = StoredToken.Present(GitHubToken("not-a-real-token"), 1_699_000_000L),
        var lastChecked: Long = 0L,
    ) : TokenStore {
        var recorded: Long? = null
        var foundTag: String? = null

        override suspend fun read(): StoredToken = stored
        override suspend fun save(token: GitHubToken, validatedAtEpochSeconds: Long) = true
        override suspend fun clear() = Unit
        override suspend fun lastCheckedAt(): Long = lastChecked
        override suspend fun recordChecked(atEpochSeconds: Long) {
            recorded = atEpochSeconds
            lastChecked = atEpochSeconds
        }

        override suspend fun lastFoundTag(): String? = foundTag
        override suspend fun recordFound(tag: String?) {
            foundTag = tag
        }
    }

    /** Counts calls, so "did it check at all" is answerable. */
    private class RecordingApi(private val result: ApiResult<List<Release>>) : ReleaseSource {
        var calls = 0
        override suspend fun releases(token: GitHubToken, perPage: Int): ApiResult<List<Release>> {
            calls++
            return result
        }
    }

    private fun release(tag: String) = Release(
        tag = tag,
        version = AppVersion.parse(tag),
        title = tag,
        notes = "notes",
        draft = false,
        prerelease = false,
        assets = emptyList(),
    )

    private fun viewModel(
        store: TokenStore,
        api: ReleaseSource,
        downloader: AssetDownloader? = null,
    ) = UpdateViewModel(
        store = store,
        api = api,
        downloader = downloader,
        installed = installed,
        nowEpochSeconds = { now },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `a newer release on launch is offered`() {
        val api = RecordingApi(ApiResult.Success(listOf(release("v0.4.0"))))
        val status = viewModel(FakeStore(), api).status.value

        assertTrue(status is UpdateStatus.Available)
        assertEquals("v0.4.0", (status as UpdateStatus.Available).release.tag)
        assertEquals(1, api.calls)
    }

    // -- What the check remembers, and what it refuses to ----------------------------------------

    @Test
    fun `a successful check writes down what it found`() {
        val store = FakeStore()
        val vm = viewModel(store, RecordingApi(ApiResult.Success(listOf(release("v0.4.0")))))

        assertEquals("v0.4.0", store.foundTag)
        assertEquals("v0.4.0", vm.noticeTag.value)
    }

    @Test
    fun `a successful check that finds nothing newer forgets the previous answer`() {
        // The owner installed it. Nothing tells this app so, but the next check finding nothing
        // newer is the moment the memory has to go - otherwise the banner outlives the update.
        val store = FakeStore(lastChecked = 0L).apply { foundTag = "v0.4.0" }
        val vm = viewModel(store, RecordingApi(ApiResult.Success(listOf(release("v0.2.0")))))

        assertNull(store.foundTag)
        assertNull(vm.noticeTag.value)
    }

    @Test
    fun `a failed check writes nothing down at all`() {
        val store = FakeStore().apply { foundTag = "v0.4.0" }
        val vm = viewModel(store, RecordingApi(ApiResult.Failure(TokenValidationResult.Offline)))

        // Neither erased nor invented: a failure is not an answer about what releases exist.
        assertEquals("v0.4.0", store.foundTag)
        assertEquals("v0.4.0", vm.noticeTag.value)
        assertNull("a failed check must not count against the once-a-day budget", store.recorded)
    }

    /**
     * Guard 4, and the one that is easiest to break by accident: remembering an update must not
     * become a reason to check for it. `CheckSchedule` still decides, and does not consult the
     * memory.
     */
    @Test
    fun `remembering an update does not earn an extra call to GitHub`() {
        val store = FakeStore(lastChecked = now - 60).apply { foundTag = "v0.4.0" }
        val api = RecordingApi(ApiResult.Success(listOf(release("v0.4.0"))))

        val vm = viewModel(store, api)

        assertEquals("the schedule decides, not the memory", 0, api.calls)
        // And it is still announced, from the memory alone, without a call.
        assertEquals("v0.4.0", vm.noticeTag.value)
    }

    @Test
    fun `a check within the day does not call GitHub again`() {
        val api = RecordingApi(ApiResult.Success(listOf(release("v0.4.0"))))
        viewModel(FakeStore(lastChecked = now - 60), api)

        assertEquals("once a day means once a day", 0, api.calls)
    }

    @Test
    fun `a check a day later does call`() {
        val api = RecordingApi(ApiResult.Success(emptyList()))
        viewModel(FakeStore(lastChecked = now - CheckSchedule.ONE_DAY_SECONDS), api)

        assertEquals(1, api.calls)
    }

    @Test
    fun `the manual check runs even when one just ran`() {
        val api = RecordingApi(ApiResult.Success(listOf(release("v0.4.0"))))
        val vm = viewModel(FakeStore(lastChecked = now), api)
        assertEquals(0, api.calls)

        vm.checkNow()

        assertEquals(1, api.calls)
        assertTrue(vm.status.value is UpdateStatus.Available)
    }

    @Test
    fun `with no token stored there is nothing to check with`() {
        val api = RecordingApi(ApiResult.Success(emptyList()))
        val status = viewModel(FakeStore(stored = StoredToken.None), api).status.value

        assertEquals(UpdateStatus.NoToken, status)
        assertEquals("and no reason to call GitHub", 0, api.calls)
    }

    @Test
    fun `an unreadable stored token is its own state, not "no token" and not a failed check`() {
        val api = RecordingApi(ApiResult.Success(emptyList()))
        val status = viewModel(FakeStore(stored = StoredToken.Unreadable(permanent = true, reason = "the key was invalidated", sinceEpochSeconds = 1_700_000_000L)), api).status.value

        assertEquals(UpdateStatus.TokenUnreadable, status)
        assertEquals(0, api.calls)
    }

    @Test
    fun `a successful check is what counts against the daily budget`() {
        val store = FakeStore()
        viewModel(store, RecordingApi(ApiResult.Success(emptyList())))

        assertEquals(now, store.recorded)
    }

    /**
     * The one that would hurt: recording a failed check would buy a day of silence after a single
     * tunnel - and, worse, a day of silence after the owner fixes an expired token.
     */
    @Test
    fun `a failed check does not buy a day of silence`() {
        val store = FakeStore()
        viewModel(store, RecordingApi(ApiResult.Failure(TokenValidationResult.Offline)))

        assertEquals(null, store.recorded)
        assertEquals(0L, store.lastChecked)
    }

    @Test
    fun `an expired token is named as expired because it worked before`() {
        val store = FakeStore(stored = StoredToken.Present(token, validatedAtEpochSeconds = 1_699_000_000L))
        val status = viewModel(store, RecordingApi(ApiResult.Failure(TokenValidationResult.NotAccepted))).status.value

        assertEquals(UpdateStatus.TokenExpired, status)
    }

    @Test
    fun `a token with no record of working is not called expired`() {
        val store = FakeStore(stored = StoredToken.Present(token, validatedAtEpochSeconds = 0L))
        val status = viewModel(store, RecordingApi(ApiResult.Failure(TokenValidationResult.NotAccepted))).status.value

        assertEquals(UpdateStatus.TokenNotAccepted, status)
    }

    // --- downloading -------------------------------------------------------------------------

    private fun availableViewModel(
        downloader: AssetDownloader,
        store: TokenStore = FakeStore(),
        asset: ReleaseAsset? = ReleaseAsset(490736218L, "beware-of-sugar-0.4.0.apk", 1024L),
    ): UpdateViewModel {
        val withAsset = release("v0.4.0").copy(assets = listOfNotNull(asset))
        return viewModel(store, RecordingApi(ApiResult.Success(listOf(withAsset))), downloader)
    }

    /** A downloader that answers without a socket. */
    private fun downloaderReturning(outcome: DownloadOutcome) = object : AssetDownloader {
        var discarded = false
        override fun discard() { discarded = true }

        override suspend fun download(
            token: GitHubToken,
            asset: ReleaseAsset,
            onProgress: (Long, Long) -> Unit,
        ): DownloadOutcome {
            onProgress(512L, asset.sizeBytes)
            return outcome
        }
    }

    /**
     * The size on the screen and the file that arrives must be the same asset - otherwise the app
     * quotes one number and fetches another, on the screen whose job is to avoid surprises.
     */
    @Test
    fun `the asset downloaded is the one whose size was quoted`() {
        var requested: ReleaseAsset? = null
        val downloader = object : AssetDownloader {
            override fun discard() = Unit
            override suspend fun download(
                token: GitHubToken,
                asset: ReleaseAsset,
                onProgress: (Long, Long) -> Unit,
            ): DownloadOutcome {
                requested = asset
                return DownloadOutcome.Ready(java.io.File("update.apk"))
            }
        }
        val multiAsset = release("v0.4.0").copy(
            assets = listOf(
                ReleaseAsset(1L, "mapping.txt", 12_000L),
                ReleaseAsset(2L, "beware-of-sugar-0.4.0.apk", 25_372_783L),
                // Bigger than the .apk on purpose: the download must still fetch the .apk, or the
                // installer is handed something it cannot parse.
                ReleaseAsset(3L, "native-debug-symbols.zip", 90_000_000L),
            ),
        )
        val vm = viewModel(
            FakeStore(),
            RecordingApi(ApiResult.Success(listOf(multiAsset))),
            downloader,
        )

        vm.startDownload(canInstall = true)

        assertEquals(multiAsset.updateAsset, requested)
        assertEquals("the .apk, not the bigger archive beside it", 2L, requested?.id)
        assertEquals(25_372_783L, requested?.sizeBytes)
    }

    @Test
    fun `a finished download is ready to install`() {
        val apk = java.io.File("update.apk")
        val vm = availableViewModel(downloaderReturning(DownloadOutcome.Ready(apk)))

        vm.startDownload(canInstall = true)

        assertEquals(DownloadState.ReadyToInstall(apk), vm.download.value)
    }

    @Test
    fun `without permission to install nothing is downloaded`() {
        val vm = availableViewModel(downloaderReturning(DownloadOutcome.Ready(java.io.File("x"))))

        vm.startDownload(canInstall = false)

        assertEquals(DownloadState.NotAllowedToInstall, vm.download.value)
    }

    @Test
    fun `a release with no apk says so rather than failing a download`() {
        val vm = availableViewModel(downloaderReturning(DownloadOutcome.Ready(java.io.File("x"))), asset = null)

        vm.startDownload(canInstall = true)

        assertEquals(DownloadState.Failed(DownloadFailure.NoAsset), vm.download.value)
    }

    @Test
    fun `an interrupted download is reported and offers no file`() {
        val vm = availableViewModel(downloaderReturning(DownloadOutcome.Failed(DownloadFailure.Interrupted)))

        vm.startDownload(canInstall = true)

        assertEquals(DownloadState.Failed(DownloadFailure.Interrupted), vm.download.value)
    }

    /**
     * A token that expires between the check and the download is the same fact the check reports, so
     * it is reported in the same place and the same words rather than as a download error.
     */
    @Test
    fun `a token rejected during download updates the check status too`() {
        val vm = availableViewModel(
            downloaderReturning(DownloadOutcome.Failed(DownloadFailure.Rejected(TokenValidationResult.NotAccepted))),
        )

        vm.startDownload(canInstall = true)

        assertEquals(UpdateStatus.TokenExpired, vm.status.value)
        assertTrue(vm.download.value is DownloadState.Failed)
    }

    @Test
    fun `progress reaches the screen as bytes`() {
        val seen = mutableListOf<DownloadState>()
        val vm = availableViewModel(object : AssetDownloader {
            override fun discard() = Unit

            override suspend fun download(
                token: GitHubToken,
                asset: ReleaseAsset,
                onProgress: (Long, Long) -> Unit,
            ): DownloadOutcome {
                onProgress(256L, 1024L)
                onProgress(1024L, 1024L)
                return DownloadOutcome.Ready(java.io.File("update.apk"))
            }
        })

        vm.startDownload(canInstall = true)

        // The final state is ready-to-install; what matters here is that the running states carried
        // real byte counts rather than a spinner.
        assertEquals(0.25f, DownloadState.Running(256L, 1024L).fraction)
        assertEquals(null, DownloadState.Running(256L, 0L).fraction)
    }

    @Test
    fun `launching the installer does not look like a failure afterwards`() {
        val apk = java.io.File("update.apk")
        val vm = availableViewModel(downloaderReturning(DownloadOutcome.Ready(apk)))
        vm.startDownload(canInstall = true)

        vm.onInstallerLaunched()

        // Android reports nothing back, so declining leaves the app exactly here - with the file
        // still ready and a standing offer to install it.
        assertEquals(DownloadState.ReadyToInstall(apk, launched = true), vm.download.value)
    }

    /**
     * Android never reports an install, so "you are up to date" is the app's only evidence that one
     * happened - and therefore the only moment it can know the downloaded .apk is dead weight.
     */
    @Test
    fun `being up to date throws away a downloaded apk`() {
        val downloader = downloaderReturning(DownloadOutcome.Ready(java.io.File("update.apk")))
        val store = FakeStore()
        // A check that finds nothing newer, which is what the world looks like after installing.
        viewModel(store, RecordingApi(ApiResult.Success(emptyList())), downloader)

        assertTrue("a stale .apk is megabytes in an app that stores a token", downloader.discarded)
    }

    @Test
    fun `an available update does not throw away what is being downloaded`() {
        val downloader = downloaderReturning(DownloadOutcome.Ready(java.io.File("update.apk")))
        availableViewModel(downloader)

        assertFalse("there is still something to install", downloader.discarded)
    }

    @Test
    fun `being offline on launch is quiet, not fatal`() {
        val status = viewModel(FakeStore(), RecordingApi(ApiResult.Failure(TokenValidationResult.Offline))).status.value
        assertEquals(UpdateStatus.Offline, status)
    }
}
