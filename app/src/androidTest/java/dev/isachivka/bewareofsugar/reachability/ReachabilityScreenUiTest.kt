package dev.isachivka.bewareofsugar.reachability

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.isachivka.bewareofsugar.R
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen, and the sentences on it.
 *
 * Rendered against `AppTheme` rather than a bare `MaterialTheme`, because this screen reads
 * `AppTheme.colors` and a test under baseline Material 3 would be exercising a different screen and
 * would die the moment anyone looked at it.
 */
@RunWith(AndroidJUnit4::class)
class ReachabilityScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun show(state: ReachabilityUiState, onOpen: (HomeService) -> Unit = {}) {
        compose.setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReachabilityScreen(
                        state = state,
                        onCheckNow = {},
                        onOpen = onOpen,
                        onBack = {},
                    )
                }
            }
        }
    }

    // -- The copy the ruling made normative ---------------------------------------------------------

    /**
     * **The obligation the ruling created, tested where it can actually fail.**
     *
     * Android cannot tell an expired certificate from an unknown chain, so this state is loud about a
     * cause that may not be tampering. `ReachabilityPresentationTest` asserts the sentence exists;
     * only this can assert what it says. A tone test would pass happily on copy that still told the
     * owner they were being intercepted.
     *
     * Three things have to be true of it: it names interception, it names expiry, and it sends the
     * owner to the one check that separates them.
     */
    @Test
    fun theCertificateSentenceNamesBothCausesAndSendsTheOwnerToTheDate() {
        val detail = resources.getString(R.string.reach_cert_unverified_detail).lowercase()

        assertTrue("does not name interception: $detail", detail.contains("intercept"))
        assertTrue("does not name expiry: $detail", detail.contains("expired"))
        assertTrue("does not admit the app cannot tell: $detail", detail.contains("cannot tell"))
        assertTrue("does not say what to check: $detail", detail.contains("date"))
    }

    /**
     * And it must not be phrased as an accusation. "Something is intercepting this connection" as a
     * flat statement would be the app claiming knowledge it does not have.
     */
    @Test
    fun theCertificateSentenceDoesNotAssertInterception() {
        val detail = resources.getString(R.string.reach_cert_unverified_detail).lowercase()

        assertTrue("reads as a verdict rather than as two possibilities", detail.contains("either"))
    }

    /**
     * The summary may describe the failures it observed and may not claim the services share
     * infrastructure — over IPv6 they are ten different addresses and the app cannot know which
     * family the phone used.
     */
    @Test
    fun theSummaryDescribesFailuresRatherThanAssertingOneIngress() {
        val sameLayer = resources.getString(R.string.reach_summary_same_layer, 10).lowercase()

        assertTrue("does not describe the pattern", sameLayer.contains("same way"))
        listOf("ingress", "one address", "same server", "one door", "your router is").forEach {
            assertEquals("claims shared infrastructure: $sameLayer", false, sameLayer.contains(it))
        }
    }

    // -- The screen ---------------------------------------------------------------------------------

    @Test
    fun everyServiceGetsARowAndTheNamesAreTheOwnersOwn() {
        show(ReachabilityUiState.initial())

        ServiceRegistry.remote.forEach { service ->
            compose.onNodeWithTag(serviceRowTag(service.id)).assertExists()
        }
        compose.onNodeWithText("Navidrome").assertExists()
    }

    @Test
    fun anAuthChallengeReadsAsReachable() {
        show(ReachabilityUiState.of(mapOf("torrent" to Probe(Reachability.Answered(401, 120, AnswerMeaning.NeedsAuth), AddressFamily.IPv4))))

        // Iteration 4 suppressed the sentence that used to explain this. The rule still shows: the
        // verdict says Reachable and the facts line says 401, which together are the explanation.
        compose.onNodeWithText(resources.getString(R.string.reach_up)).assertIsDisplayed()
        compose.onNodeWithTag(factsTag("torrent"), useUnmergedTree = true)
            .assertTextEquals("401 · IPv4 · 120 ms")
    }

    /** Latency is on screen where there is one, because "up but four seconds" is a real answer. */
    @Test
    fun latencyIsShownBesideTheVerdict() {
        show(ReachabilityUiState.of(mapOf("immich" to Probe(Reachability.Answered(200, 4_120, AnswerMeaning.Reachable), AddressFamily.IPv6))))

        compose.onNodeWithText(resources.getString(R.string.reach_latency, 4_120), substring = true)
            .assertExists()
    }

    @Test
    fun theSummaryIsOnScreenAboveTheRows() {
        show(ReachabilityUiState.of(ServiceRegistry.remote.associate { it.id to Probe(Reachability.ConnectionTimedOut, AddressFamily.IPv4) }))

        compose.onNodeWithTag(TAG_SUMMARY).assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.reach_summary_same_layer, 10)).assertExists()
    }

    /**
     * The other half of the estate is named, not omitted. A list that silently drops ten services
     * reads as "these are all my services".
     */
    @Test
    fun theServicesWithNoRemoteAddressAreNamed() {
        show(ReachabilityUiState.initial())

        compose.onNodeWithTag(TAG_NOT_CHECKABLE).assertExists()
        compose.onNodeWithText("Plex", substring = true).assertExists()
    }

    /**
     * The two fields this iteration exists for, in one line, in the order a reader scans them.
     * Rendered only where they exist - a row with nothing to say shows nothing rather than an empty
     * separator.
     */
    @Test
    fun aSettledRowCarriesTheStatusTheFamilyAndTheLatency() {
        show(ReachabilityUiState.of(
            mapOf("immich" to Probe(Reachability.Answered(200, 128, AnswerMeaning.Reachable), AddressFamily.IPv6)),
        ))

        compose.onNodeWithTag(factsTag("immich"), useUnmergedTree = true)
            .assertTextEquals("200 · IPv6 · 128 ms")
    }

    @Test
    fun aRowWithNothingToSayShowsNoFactsLine() {
        // A name that would not resolve opened no connection: no status, no family, no round trip.
        show(ReachabilityUiState.of(mapOf("frigate" to Probe(Reachability.NameNotResolved))))

        compose.onNodeWithTag(factsTag("frigate"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun openHandsBackTheServiceItBelongsTo() {
        var opened: HomeService? = null
        show(ReachabilityUiState.initial()) { opened = it }

        // Ninth of ten, so it is off screen until scrolled to - and a click on a node outside the
        // viewport lands nowhere and fails silently, which is how the first version of this test
        // reported "opened nothing" instead of a missing scroll.
        compose.onNodeWithTag(openTag("navidrome")).performScrollTo().performClick()

        assertEquals("navidrome", opened?.id)
    }
}
