package dev.isachivka.bewareofsugar.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Every state the two screens can be in, rendered on a device.
 *
 * **What this proves and what it does not.** `StatusPresentationTest` already asserts which tone
 * each of the thirteen states gets; it is a pure function and needs no device. What a pure function
 * cannot tell you is whether the screen actually *draws* in that state — whether a card with no
 * second line lays out, whether a spinner in place of an icon fits, whether a download card with no
 * asset size crashes on a null. That is what this is for, and it is why it asserts presence rather
 * than colour: colour is already covered, and pinning pixels here would fail on every intentional
 * restyle.
 *
 * One `setContent` and a state that is driven through every value, rather than eighteen tests: the
 * screens are the same composable each time, and eighteen activity launches would cost minutes to
 * prove the same thing.
 */
@RunWith(AndroidJUnit4::class)
class UpdateStatusCardUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun release(tag: String, sizeBytes: Long = 25_373_199L) = Release(
        tag = tag,
        version = AppVersion.parse(tag),
        title = tag,
        notes = "## What's Changed\n* feat: something",
        draft = false,
        prerelease = false,
        assets = listOf(ReleaseAsset(1L, "beware-of-sugar-0.6.0.apk", sizeBytes)),
    )

    private val everyStatus = listOf(
        UpdateStatus.Idle,
        UpdateStatus.Checking,
        UpdateStatus.UpToDate(1_700_000_000L),
        UpdateStatus.Available(release("v0.6.0"), 1_700_000_000L),
        UpdateStatus.NoToken,
        UpdateStatus.TokenUnreadable,
        UpdateStatus.TokenExpired,
        UpdateStatus.TokenNotAccepted,
        UpdateStatus.NoRepoAccess,
        UpdateStatus.InsufficientPermission,
        UpdateStatus.RateLimited(1_700_003_600L),
        UpdateStatus.RateLimited(null),
        UpdateStatus.Offline,
        UpdateStatus.GitHubUnavailable(503),
        UpdateStatus.UnreadableRelease,
    )

    @Test
    fun everyCheckStateDrawsAStatusCard() {
        var status by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
        compose.setContent {
            AppTheme {
                UpdateScreen(status = status, onCheckNow = {}, onOpenToken = {}, onBack = {})
            }
        }

        everyStatus.forEach { value ->
            status = value
            compose.waitForIdle()
            compose.onNodeWithTag(TAG_UPDATE_STATUS).assertIsDisplayed()
            // Check now stays reachable in every state, including the failures. REQ-0003's rule
            // that nothing is a dead end is only true if the way out is always on screen.
            compose.onNodeWithTag(TAG_CHECK_NOW).assertExists()
            compose.onNodeWithTag(TAG_OPEN_TOKEN).assertExists()
        }
    }

    @Test
    fun everyDownloadStateDrawsSomethingToDo() {
        var download by mutableStateOf<DownloadState>(DownloadState.Idle)
        compose.setContent {
            AppTheme {
                UpdateScreen(
                    status = UpdateStatus.Available(release("v0.6.0"), 1_700_000_000L),
                    onCheckNow = {},
                    onOpenToken = {},
                    onBack = {},
                    download = download,
                )
            }
        }

        // The design draws three of these. The other two happen anyway.
        val states = listOf(
            DownloadState.Idle to TAG_DOWNLOAD,
            DownloadState.Running(2L * 1024 * 1024, 24L * 1024 * 1024) to TAG_DOWNLOAD_CANCEL,
            DownloadState.ReadyToInstall(File("/tmp/x.apk")) to TAG_INSTALL,
            DownloadState.ReadyToInstall(File("/tmp/x.apk"), launched = true) to TAG_INSTALL,
            DownloadState.NotAllowedToInstall to TAG_ALLOW_INSTALLS,
            DownloadState.Failed(DownloadFailure.Interrupted) to TAG_DOWNLOAD,
            DownloadState.Failed(DownloadFailure.Truncated(10, 5)) to TAG_DOWNLOAD,
            DownloadState.Failed(DownloadFailure.NotEnoughSpace(10, 5)) to TAG_DOWNLOAD,
            DownloadState.Failed(DownloadFailure.NoAsset) to TAG_DOWNLOAD,
            DownloadState.Failed(DownloadFailure.Rejected(TokenValidationResult.NotAccepted)) to TAG_DOWNLOAD,
        )

        states.forEach { (value, expectedAction) ->
            download = value
            compose.waitForIdle()
            // Every one of them offers a way forward. A download card with nothing to press is the
            // dead end REQ-0003 exists to avoid.
            compose.onNodeWithTag(expectedAction).assertIsDisplayed()
        }
    }

    /** A release with no `.apk` attached must not take the size chip or the card down with it. */
    @Test
    fun aReleaseWithNoAssetStillRenders() {
        compose.setContent {
            AppTheme {
                UpdateScreen(
                    status = UpdateStatus.Available(
                        release("v0.6.0").copy(assets = emptyList()),
                        1_700_000_000L,
                    ),
                    onCheckNow = {},
                    onOpenToken = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(TAG_UPDATE_STATUS).assertIsDisplayed()
        compose.onNodeWithTag(TAG_RELEASE_NOTES).assertIsDisplayed()
        compose.onNodeWithTag(TAG_RELEASE_SIZE).assertDoesNotExist()
        // No size to quote, so no "This update is X" line - and the button is still there.
        compose.onNodeWithTag(TAG_DOWNLOAD_SIZE).assertDoesNotExist()
        compose.onNodeWithTag(TAG_DOWNLOAD).assertIsDisplayed()
    }

    /** Every token state, for the same reason: the screen has to lay out in all of them. */
    @Test
    fun everyTokenStateDrawsTheTokenScreen() {
        var state by mutableStateOf(TokenScreenState())
        compose.setContent {
            AppTheme {
                TokenScreen(
                    state = state,
                    onInputChange = {},
                    onSave = {},
                    onReplace = {},
                    onRemove = {},
                    onBack = {},
                )
            }
        }

        val everyTokenState = listOf(
            TokenScreenState(),
            TokenScreenState(input = "xxxx"),
            TokenScreenState(input = "xxxx", status = TokenStatus.Validating),
            TokenScreenState(status = TokenStatus.Saved(1_700_000_000L)),
            TokenScreenState(status = TokenStatus.Saved(1_700_000_000L), replacing = true),
            TokenScreenState(status = TokenStatus.NotAccepted),
            TokenScreenState(status = TokenStatus.NoRepoAccess),
            TokenScreenState(status = TokenStatus.InsufficientPermission),
            TokenScreenState(status = TokenStatus.RateLimited(1_700_003_600L)),
            TokenScreenState(status = TokenStatus.RateLimited(null)),
            TokenScreenState(status = TokenStatus.Offline),
            TokenScreenState(status = TokenStatus.GitHubUnavailable(503)),
            TokenScreenState(status = TokenStatus.Unreadable(permanent = true, reason = "the key was invalidated", sinceEpochSeconds = 1_700_000_000L)),
            TokenScreenState(status = TokenStatus.NotStored),
        )

        everyTokenState.forEach { value ->
            state = value
            compose.waitForIdle()
            // The way out and the reason the screen is dark are on screen in every state.
            compose.onNodeWithTag(TAG_BACK).assertIsDisplayed()
            compose.onNodeWithTag(TAG_SCREENSHOTS_BLOCKED).assertIsDisplayed()
        }
    }
}
