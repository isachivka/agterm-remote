package dev.isachivka.bewareofsugar.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a completed check means, branch by branch. */
class UpdateCheckTest {

    private val now = 1_700_000_000L
    private val installed = AppVersion(0, 3, 0)

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ) = Release(
        tag = tag,
        version = AppVersion.parse(tag),
        title = tag,
        notes = "notes for $tag",
        draft = draft,
        prerelease = prerelease,
        assets = emptyList(),
    )

    private fun evaluate(
        releases: List<Release>,
        installedVersion: AppVersion? = installed,
    ) = UpdateCheck.evaluate(ApiResult.Success(releases), installedVersion, now, hasWorkedBefore = true)

    private fun evaluate(reason: TokenValidationResult, hasWorkedBefore: Boolean = true) =
        UpdateCheck.evaluate(ApiResult.Failure(reason), installed, now, hasWorkedBefore)

    /**
     * The screen quotes a size before the owner taps, and the download then fetches an asset. Both
     * ask this, so they cannot mean different files.
     */
    @Test
    fun `the update asset is the apk`() {
        val withAssets = release("v0.4.0").copy(
            assets = listOf(
                ReleaseAsset(1L, "mapping.txt", 12_000L),
                ReleaseAsset(2L, "beware-of-sugar-0.4.0.apk", 25_372_783L),
                ReleaseAsset(3L, "checksums.txt", 400L),
            ),
        )

        assertEquals(2L, withAssets.updateAsset?.id)
        assertEquals(25_372_783L, withAssets.updateAsset?.sizeBytes)
    }

    /**
     * The one that matters, and the reason this is chosen by name rather than by size. Picking the
     * biggest file is right only while exactly one file is attached; the day a larger symbols
     * archive or bundle appears beside the .apk, "biggest" hands a non-.apk to the installer and the
     * owner gets an error they can do nothing about.
     */
    @Test
    fun `a larger non-apk attached beside the apk does not win`() {
        val withSymbols = release("v0.4.0").copy(
            assets = listOf(
                ReleaseAsset(1L, "beware-of-sugar-0.4.0.apk", 25_372_783L),
                ReleaseAsset(2L, "native-debug-symbols.zip", 90_000_000L),
                ReleaseAsset(3L, "mapping.txt", 12_000L),
            ),
        )

        assertEquals("the .apk, not the bigger zip", 1L, withSymbols.updateAsset?.id)
    }

    @Test
    fun `the extension is matched whatever its case`() {
        val shouty = release("v0.4.0").copy(
            assets = listOf(
                ReleaseAsset(1L, "SOMETHING-ELSE.ZIP", 90_000_000L),
                ReleaseAsset(2L, "BEWARE-OF-SUGAR-0.4.0.APK", 25_372_783L),
            ),
        )

        assertEquals(2L, shouty.updateAsset?.id)
    }

    @Test
    fun `between two apks the bigger one wins`() {
        val two = release("v0.4.0").copy(
            assets = listOf(
                ReleaseAsset(1L, "beware-of-sugar-0.4.0-arm.apk", 20_000_000L),
                ReleaseAsset(2L, "beware-of-sugar-0.4.0-universal.apk", 25_372_783L),
            ),
        )

        assertEquals(2L, two.updateAsset?.id)
    }

    @Test
    fun `with nothing named apk the biggest file is the least bad guess`() {
        // A release built by some process this one does not know about. Android will refuse an
        // uninstallable file and nothing has been replaced, which beats refusing to try at all.
        val odd = release("v0.4.0").copy(
            assets = listOf(
                ReleaseAsset(1L, "app-release", 25_372_783L),
                ReleaseAsset(2L, "notes.txt", 400L),
            ),
        )

        assertEquals(1L, odd.updateAsset?.id)
    }

    @Test
    fun `a release with nothing attached has no update asset`() {
        assertEquals(null, release("v0.4.0").updateAsset)
    }

    @Test
    fun `a newer release is offered`() {
        val status = evaluate(listOf(release("v0.4.0")))
        assertTrue(status is UpdateStatus.Available)
        assertEquals("v0.4.0", (status as UpdateStatus.Available).release.tag)
    }

    @Test
    fun `the installed version is not an update`() {
        assertEquals(UpdateStatus.UpToDate(now), evaluate(listOf(release("v0.3.0"))))
    }

    @Test
    fun `an older release is never offered as a downgrade`() {
        // Somebody re-publishing an old release must not send the owner backwards - Android would
        // refuse the install at the final tap anyway.
        assertEquals(UpdateStatus.UpToDate(now), evaluate(listOf(release("v0.2.0"))))
    }

    @Test
    fun `a draft is not a release anyone can install`() {
        assertEquals(UpdateStatus.UpToDate(now), evaluate(listOf(release("v0.4.0", draft = true))))
    }

    @Test
    fun `a pre-release is not offered`() {
        assertEquals(UpdateStatus.UpToDate(now), evaluate(listOf(release("v0.4.0", prerelease = true))))
    }

    @Test
    fun `a draft at the head does not hide the newest stable release behind it`() {
        val status = evaluate(listOf(release("v0.5.0", draft = true), release("v0.4.0")))
        assertEquals("v0.4.0", (status as UpdateStatus.Available).release.tag)
    }

    @Test
    fun `the newest is chosen by version, not by list position`() {
        val status = evaluate(listOf(release("v0.4.0"), release("v0.10.0"), release("v0.9.0")))
        assertEquals("v0.10.0", (status as UpdateStatus.Available).release.tag)
    }

    @Test
    fun `an unreadable tag is said out loud rather than silently ignored`() {
        assertEquals(UpdateStatus.UnreadableRelease, evaluate(listOf(release("nightly"))))
    }

    @Test
    fun `a readable older tag wins over an unreadable newer one`() {
        val status = evaluate(listOf(release("nightly"), release("v0.4.0")))
        assertEquals("v0.4.0", (status as UpdateStatus.Available).release.tag)
    }

    @Test
    fun `no releases at all is up to date`() {
        assertEquals(UpdateStatus.UpToDate(now), evaluate(emptyList()))
    }

    @Test
    fun `an unreadable installed version offers nothing rather than everything`() {
        // If we cannot say what is installed, we cannot say anything is newer than it.
        assertEquals(UpdateStatus.UpToDate(now), evaluate(listOf(release("v9.9.9")), installedVersion = null))
    }

    /**
     * The distinction iteration 1 could not make. Same 401, different meaning, and the difference is
     * whether GitHub ever accepted this token.
     */
    @Test
    fun `a 401 for a token that used to work is expired, not wrong`() {
        assertEquals(
            UpdateStatus.TokenExpired,
            evaluate(TokenValidationResult.NotAccepted, hasWorkedBefore = true),
        )
    }

    @Test
    fun `a 401 for a token with no history says what the token screen says`() {
        assertEquals(
            UpdateStatus.TokenNotAccepted,
            evaluate(TokenValidationResult.NotAccepted, hasWorkedBefore = false),
        )
    }

    @Test
    fun `every failure keeps its own meaning`() {
        assertEquals(UpdateStatus.Offline, evaluate(TokenValidationResult.Offline))
        assertEquals(UpdateStatus.NoRepoAccess, evaluate(TokenValidationResult.NoRepoAccess))
        assertEquals(
            UpdateStatus.InsufficientPermission,
            evaluate(TokenValidationResult.InsufficientPermission),
        )
        assertEquals(
            UpdateStatus.RateLimited(now + 600),
            evaluate(TokenValidationResult.RateLimited(now + 600)),
        )
        assertEquals(
            UpdateStatus.GitHubUnavailable(503),
            evaluate(TokenValidationResult.GitHubUnavailable(503)),
        )
    }
}
