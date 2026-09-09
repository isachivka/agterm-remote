package dev.isachivka.bewareofsugar.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.isachivka.bewareofsugar.reachability.AnswerMeaning
import dev.isachivka.bewareofsugar.reachability.Reachability
import dev.isachivka.bewareofsugar.reachability.ReachabilityScreen
import dev.isachivka.bewareofsugar.reachability.tileVerdictOf
import dev.isachivka.bewareofsugar.reachability.ReachabilityUiState
import dev.isachivka.bewareofsugar.ui.home.HomeScreen
import dev.isachivka.bewareofsugar.ui.module.ModuleRegistry
import dev.isachivka.bewareofsugar.ui.module.SettingsScreen
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import dev.isachivka.bewareofsugar.ui.theme.ColourRoles
import dev.isachivka.bewareofsugar.ui.theme.TypeScale
import dev.isachivka.bewareofsugar.update.AppVersion
import dev.isachivka.bewareofsugar.update.DownloadState
import dev.isachivka.bewareofsugar.update.Release
import dev.isachivka.bewareofsugar.update.ReleaseAsset
import dev.isachivka.bewareofsugar.update.UpdateScreen
import dev.isachivka.bewareofsugar.update.UpdateStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Turns every "look at this" composable into a PNG.
 *
 * PLAN-0004 says a screenshot of a preview beats a paragraph asserting it looks right, and this is
 * how that gets produced without Android Studio: render, capture, write, `adb pull`. What each image
 * is for is documented on the composable it captures.
 *
 * **There is deliberately no token-screen capture.** `TokenScreen` sets `FLAG_SECURE` while it is on
 * screen, and a secure window cannot be read back — `captureToImage` fails on it in exactly the way
 * Android refuses the owner's own screenshot. That is the feature working, so the only honest way to
 * show that screen is to look at it.
 *
 * The PNGs are written to the external cache and pulled off the device for the pull request. They
 * are not committed — they regenerate in seconds, and a binary that changes whenever a colour does
 * is noise in every future diff.
 *
 * These assert only that a file was produced. They are not screenshot *comparison* tests: pinning
 * pixels would fail on every intentional change and on a different emulator skin, which is how a
 * team learns to re-baseline without looking.
 */
@RunWith(AndroidJUnit4::class)
class DesignScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            AppTheme {
                // The same Surface MainActivity puts at its root. Without it the captured bitmap is
                // transparent wherever a screen does not paint its own background, which comes out
                // white and makes a dark app look like a light one in the very image meant to show
                // how it looks.
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        compose.waitForIdle()

        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        // filesDir and not externalCacheDir: the latter returns null on an emulator with no
        // external volume mounted for the package, and File(null, "screenshots") is a *relative*
        // path that lands in the process working directory. That is how the first version of this
        // wrote three files nobody could find and still passed.
        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "screenshots",
        ).apply { mkdirs() }

        val file = File(directory, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertTrue("no screenshot written to ${file.absolutePath}", file.length() > 0)
    }

    @Test
    fun theIconGrid() = capture("icons") { AppIconGrid() }

    /**
     * The launcher tile in all four verdicts.
     *
     * Two questions only a picture answers. Is amber legible as "neither fine nor broken" when the
     * same amber already means "update available" two tiles above it? And is the subtitle readable
     * at tile size - because if it is not, the answer is to say so, not to shrink the text until it
     * fits.
     */
    private fun launcherWith(results: List<Reachability>) = @Composable {
        HomeScreen(
            modules = ModuleRegistry.all,
            installedVersion = "v0.7.0 (700)",
            updateStatus = UpdateStatus.Idle,
            onOpenUpdates = {}, onOpenModule = {}, onOpenSettings = {},
            verdict = tileVerdictOf(results),
        )
    }

    private val up = Reachability.Answered(200, 40, AnswerMeaning.Reachable)
    private val down = Reachability.ConnectionTimedOut

    @Test
    fun theTileNotChecked() =
        capture("tile-not-checked") { launcherWith(List(10) { Reachability.NotChecked })() }

    @Test
    fun theTileNoneAvailable() =
        capture("tile-none") { launcherWith(List(10) { down })() }

    @Test
    fun theTileSomeAvailable() =
        capture("tile-some") { launcherWith(List(4) { up } + List(6) { down })() }

    @Test
    fun theTileMostAvailable() =
        capture("tile-most") { launcherWith(List(8) { up } + List(2) { down })() }

    /**
     * REQ-0005's screen with a deliberate spread of verdicts on it.
     *
     * The states that are hard to read are next to each other on purpose - reachable beside an auth
     * challenge beside the router answering beside a certificate that could not be verified - because
     * the question a screenshot can answer and a unit test cannot is whether they are still
     * distinguishable at a glance when they are neighbours.
     */
    @Test
    fun theReachabilityScreen() = capture("reachability") {
        ReachabilityScreen(
            state = ReachabilityUiState.preview(),
            onCheckNow = {},
            onOpen = {},
            onBack = {},
        )
    }

    /** And with nothing checked, which is what the owner sees for the first second every time. */
    @Test
    fun theReachabilityScreenBeforeAnyCheck() = capture("reachability-idle") {
        ReachabilityScreen(
            state = ReachabilityUiState.initial(),
            onCheckNow = {},
            onOpen = {},
            onBack = {},
        )
    }

    @Test
    fun theUpdatesScreenWithAnUpdate() = capture("updates-available") {
        UpdateScreen(
            status = UpdateStatus.Available(
                Release(
                    tag = "v0.6.0",
                    version = AppVersion.parse("v0.6.0"),
                    title = "v0.6.0",
                    notes = "## What's Changed\n* feat: the Updates and token screens, restyled\n* fix: the launcher's shimmer sweeps the width the design draws",
                    draft = false,
                    prerelease = false,
                    assets = listOf(ReleaseAsset(1L, "beware-of-sugar-0.6.0.apk", 25_373_199L)),
                ),
                1_700_000_000L,
            ),
            onCheckNow = {},
            onOpenToken = {},
            onBack = {},
            tokenValidatedAtEpochSeconds = 1_700_000_000L,
        )
    }

    @Test
    fun theUpdatesScreenWithNoToken() = capture("updates-no-token") {
        UpdateScreen(status = UpdateStatus.NoToken, onCheckNow = {}, onOpenToken = {}, onBack = {})
    }

    @Test
    fun theUpdatesScreenWhileDownloading() = capture("updates-downloading") {
        UpdateScreen(
            status = UpdateStatus.Available(
                Release(
                    tag = "v0.6.0",
                    version = AppVersion.parse("v0.6.0"),
                    title = "v0.6.0",
                    notes = "## What's Changed\n* feat: the Updates and token screens, restyled",
                    draft = false,
                    prerelease = false,
                    assets = listOf(ReleaseAsset(1L, "beware-of-sugar-0.6.0.apk", 25_373_199L)),
                ),
                1_700_000_000L,
            ),
            onCheckNow = {},
            onOpenToken = {},
            onBack = {},
            download = DownloadState.Running(9_800_000L, 25_373_199L),
        )
    }

    @Test
    fun theLauncherWithAnUpdateWaiting() = capture("launcher-update") {
        HomeScreen(
            modules = ModuleRegistry.all,
            installedVersion = "v0.5.0 (500)",
            updateStatus = UpdateStatus.Idle,
            onOpenUpdates = {},
            onOpenModule = {},
            onOpenSettings = {},
            noticeTag = "v0.6.0",
        )
    }

    @Test
    fun theSettings() = capture("settings") {
        SettingsScreen(onBack = {})
    }

    /**
     * An empty launcher.
     *
     * **This used to be "every module hidden"**, which PLAN-0004 wanted looked at rather than reasoned
     * about. Hiding is gone — the launcher draws the whole registry now — so the case it was guarding
     * cannot arise from anything the owner does.
     *
     * It is kept rather than deleted because the LAYOUT question survives the feature: a grid with no
     * tiles still has to draw its header, its footer and its spacing without collapsing, and a
     * registry that is empty for some other reason would land here.
     */
    @Test
    fun theLauncherWithNoModules() = capture("launcher-empty") {
        HomeScreen(
            modules = emptyList(),
            installedVersion = "v0.5.0 (500)",
            updateStatus = UpdateStatus.UpToDate(1_700_000_000L),
            onOpenUpdates = {},
            onOpenModule = {},
            onOpenSettings = {},
        )
    }

    @Test
    fun theColourRoles() = capture("colour-roles") { ColourRoles() }

    @Test
    fun theTypeScale() = capture("type-scale") { TypeScale() }
}
