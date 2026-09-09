package dev.isachivka.bewareofsugar.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The update screen, state by state. */
@RunWith(AndroidJUnit4::class)
class UpdateScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private var checks = 0

    private fun release(
        tag: String,
        notes: String = "* feat: something",
        assets: List<ReleaseAsset> = listOf(ReleaseAsset(1L, "beware-of-sugar.apk", 25_372_783L)),
    ) = Release(
        tag = tag,
        version = AppVersion.parse(tag),
        title = tag,
        notes = notes,
        draft = false,
        prerelease = false,
        assets = assets,
    )

    /** Held here so a test can walk through several states in one composition. */
    private lateinit var setStatus: (UpdateStatus) -> Unit

    private var downloads = 0

    /** Held so a test can walk several states through one composition. */
    private lateinit var setDownload: (DownloadState) -> Unit

    private fun show(status: UpdateStatus, download: DownloadState = DownloadState.Idle) {
        compose.setContent {
            var currentStatus by remember { mutableStateOf(status) }
            var currentDownload by remember { mutableStateOf(download) }
            setStatus = { currentStatus = it }
            setDownload = { currentDownload = it }
            AppTheme {
                UpdateScreen(
                    status = currentStatus,
                    onCheckNow = { checks++ },
                    onOpenToken = {},
                    onBack = {},
                    download = currentDownload,
                    onDownload = { downloads++ },
                )
            }
        }
    }

    @Test
    fun anAvailableUpdateNamesTheVersionAndShowsWhatChanged() {
        show(UpdateStatus.Available(release("v0.4.0"), 1_700_000_000L))

        // Anchored to the status line: the version also appears as the notes' title, and an
        // unanchored match would be ambiguous about which one it found.
        compose.onNodeWithTag(TAG_UPDATE_STATUS).assertTextContains("v0.4.0", substring = true)
        compose.onNodeWithTag(TAG_RELEASE_NOTES).assertIsDisplayed()
        compose.onNodeWithText("feat: something", substring = true).assertIsDisplayed()
    }

    /**
     * Notes are shown as plain text by decision, not by accident: a Markdown renderer is a
     * dependency this feature has not earned. What that looks like is asserted rather than assumed -
     * the hashes and the link syntax are on screen exactly as GitHub sent them.
     */
    @Test
    fun markdownNotesAreShownRawAndLegibly() {
        val notes = "## What's Changed\n* feat: updates by [@isachivka](https://github.com/isachivka)"
        show(UpdateStatus.Available(release("v0.4.0", notes), 1_700_000_000L))

        compose.onNodeWithText("## What's Changed", substring = true).assertIsDisplayed()
        compose.onNodeWithText("[@isachivka](https://github.com/isachivka)", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun aReleaseWithNoNotesSaysSoRatherThanShowingNothing() {
        show(UpdateStatus.Available(release("v0.4.0", notes = "   "), 1_700_000_000L))
        compose.onNodeWithText("without notes", substring = true).assertIsDisplayed()
    }

    @Test
    fun beingUpToDateSaysSoQuietly() {
        show(UpdateStatus.UpToDate(1_700_000_000L))

        compose.onNodeWithTag(TAG_UPDATE_STATUS).assertIsDisplayed()
        compose.onNodeWithText("newest release", substring = true).assertIsDisplayed()
        // No notes section when there is nothing to report.
        compose.onNodeWithTag(TAG_RELEASE_NOTES).assertDoesNotExist()
    }

    @Test
    fun anExpiredTokenSaysExpiredRatherThanWrong() {
        show(UpdateStatus.TokenExpired)

        compose.onNodeWithText("expired or been revoked", substring = true).assertIsDisplayed()
        // And the way to fix it is right there.
        compose.onNodeWithTag(TAG_OPEN_TOKEN).assertIsDisplayed()
    }

    @Test
    fun everyFailureIsALineAndNeverADialog() {
        show(UpdateStatus.Idle)
        // A failed check is not an error screen: REQ-0003 wants this ignorable.
        listOf(
            UpdateStatus.Offline,
            UpdateStatus.NoToken,
            UpdateStatus.TokenUnreadable,
            UpdateStatus.NoRepoAccess,
            UpdateStatus.InsufficientPermission,
            UpdateStatus.RateLimited(1_700_000_000L),
            UpdateStatus.GitHubUnavailable(503),
            UpdateStatus.UnreadableRelease,
        ).forEach { status ->
            compose.runOnUiThread { setStatus(status) }
            compose.onNodeWithTag(TAG_UPDATE_STATUS).assertIsDisplayed()
            // Still offering a way to try again, and still no dialog to dismiss.
            compose.onNodeWithTag(TAG_CHECK_NOW).assertIsEnabled()
        }
    }

    /**
     * The size is on screen *before* the tap. Two dozen megabytes arriving unannounced on mobile
     * data is the surprise this is here to prevent, so showing it only during the download would be
     * showing it too late.
     */
    @Test
    fun theSizeIsShownBeforeTheOwnerCommitsToDownloading() {
        show(UpdateStatus.Available(release("v0.4.0"), 1_700_000_000L))

        compose.onNodeWithTag(TAG_DOWNLOAD_SIZE).assertTextContains("24.2 MB", substring = true)
        // And it is next to the button, not hidden behind it.
        compose.onNodeWithTag(TAG_DOWNLOAD).assertIsDisplayed()
    }

    /**
     * The size on screen is the `.apk`'s, not that of whatever file happens to be largest — the same
     * choice the download makes, so the number quoted is the number that arrives.
     */
    @Test
    fun theSizeQuotedIsTheApksEvenWhenSomethingBiggerIsAttached() {
        val withSymbols = release(
            "v0.4.0",
            assets = listOf(
                ReleaseAsset(1L, "beware-of-sugar-0.4.0.apk", 25_372_783L),
                ReleaseAsset(2L, "native-debug-symbols.zip", 90_000_000L),
            ),
        )
        show(UpdateStatus.Available(withSymbols, 1_700_000_000L))

        compose.onNodeWithTag(TAG_DOWNLOAD_SIZE).assertTextContains("24.2 MB", substring = true)
    }

    @Test
    fun aReleaseWithNothingAttachedQuotesNoSize() {
        show(UpdateStatus.Available(release("v0.4.0", assets = emptyList()), 1_700_000_000L))

        // Better to say nothing than to promise a 0 B download.
        compose.onNodeWithTag(TAG_DOWNLOAD_SIZE).assertDoesNotExist()
    }

    @Test
    fun theSizeMakesWayForProgressOnceDownloadingStarts() {
        show(
            UpdateStatus.Available(release("v0.4.0"), 1_700_000_000L),
            DownloadState.Running(bytesWritten = 1024L * 1024, totalBytes = 25_372_783L),
        )

        compose.onNodeWithTag(TAG_DOWNLOAD_SIZE).assertDoesNotExist()
        compose.onNodeWithTag(TAG_DOWNLOAD_PROGRESS).assertTextContains("24.2 MB", substring = true)
    }

    @Test
    fun anUpdateOffersToDownloadIt() {
        show(UpdateStatus.Available(release("v0.4.0"), 1_700_000_000L))

        compose.onNodeWithTag(TAG_DOWNLOAD).assertIsDisplayed().performClick()

        assertEquals(1, downloads)
    }

    /** Progress is bytes, not a spinner: the numbers on screen are what was written to disk. */
    @Test
    fun downloadProgressShowsRealBytes() {
        show(
            UpdateStatus.Available(release("v0.4.0"), 1_700_000_000L),
            DownloadState.Running(bytesWritten = 2L * 1024 * 1024, totalBytes = 8L * 1024 * 1024),
        )

        compose.onNodeWithTag(TAG_DOWNLOAD_PROGRESS).assertTextContains("2.0 MB", substring = true)
        compose.onNodeWithTag(TAG_DOWNLOAD_PROGRESS).assertTextContains("8.0 MB", substring = true)
        compose.onNodeWithTag(TAG_DOWNLOAD_CANCEL).assertIsDisplayed()
    }

    @Test
    fun aFinishedDownloadOffersToInstallAndSaysAndroidWillAsk() {
        show(
            UpdateStatus.Available(release("v0.4.0"), 1_700_000_000L),
            DownloadState.ReadyToInstall(java.io.File("update.apk")),
        )

        compose.onNodeWithTag(TAG_INSTALL).assertIsDisplayed()
        // Android's confirmation is the platform's, and saying so up front stops it reading as a
        // second, unexplained prompt.
        compose.onNodeWithText("Android will ask", substring = true).assertIsDisplayed()
    }

    /**
     * Declining Android's prompt is a normal outcome, and Android tells the app nothing about it. So
     * afterwards the screen must read as a standing offer rather than as a failure.
     */
    @Test
    fun afterTheInstallerHasBeenLaunchedItIsAStandingOfferNotAnError() {
        show(
            UpdateStatus.Available(release("v0.4.0"), 1_700_000_000L),
            DownloadState.ReadyToInstall(java.io.File("update.apk"), launched = true),
        )

        compose.onNodeWithTag(TAG_DOWNLOAD_STATUS).assertTextContains("ready whenever you are", substring = true)
        compose.onNodeWithTag(TAG_INSTALL).assertIsDisplayed()
    }

    @Test
    fun everyDownloadFailureExplainsItselfAndOffersAnotherGo() {
        show(UpdateStatus.Available(release("v0.4.0"), 1_700_000_000L))

        listOf(
            DownloadFailure.Interrupted to "stopped before it finished",
            DownloadFailure.Truncated(100L, 50L) to "didn\'t arrive intact",
            DownloadFailure.NotEnoughSpace(1024L * 1024 * 50, 1024L) to "Not enough space",
            DownloadFailure.NoAsset to "no .apk attached",
        ).forEach { (failure, expected) ->
            compose.runOnUiThread { setDownload(DownloadState.Failed(failure)) }

            compose.onNodeWithTag(TAG_DOWNLOAD_STATUS).assertTextContains(expected, substring = true)
            // Nothing here is a dead end: every one of them offers another go.
            compose.onNodeWithTag(TAG_DOWNLOAD).assertIsDisplayed()
        }
    }

    @Test
    fun withoutPermissionToInstallItOffersTheOneSettingsScreenThatFixesIt() {
        show(
            UpdateStatus.Available(release("v0.4.0"), 1_700_000_000L),
            DownloadState.NotAllowedToInstall,
        )

        compose.onNodeWithTag(TAG_ALLOW_INSTALLS).assertIsDisplayed()
        compose.onNodeWithText("permission", substring = true).assertIsDisplayed()
    }

    @Test
    fun checkNowIsAlwaysAvailableExceptWhileChecking() {
        show(UpdateStatus.Checking)
        compose.onNodeWithTag(TAG_CHECK_NOW).assertIsNotEnabled()
    }

    @Test
    fun pressingCheckAsks() {
        show(UpdateStatus.UpToDate(1_700_000_000L))

        compose.onNodeWithTag(TAG_CHECK_NOW).performClick()

        assertEquals(1, checks)
    }
}
