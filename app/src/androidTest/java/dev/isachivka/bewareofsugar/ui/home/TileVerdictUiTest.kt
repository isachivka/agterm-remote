package dev.isachivka.bewareofsugar.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.bewareofsugar.reachability.AnswerMeaning
import dev.isachivka.bewareofsugar.reachability.Reachability
import dev.isachivka.bewareofsugar.reachability.tileVerdictOf
import dev.isachivka.bewareofsugar.ui.module.ModuleRegistry
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import dev.isachivka.bewareofsugar.update.UpdateStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tile's words, which are the half a screenshot cannot check.
 *
 * REQ-0006 requires the count in the subtitle and the count — **never the colour** — in the content
 * description. A screen reader that says "green" has told the owner nothing they can act on, and on a
 * launcher there is nothing to compare a hue against anyway.
 */
@RunWith(AndroidJUnit4::class)
class TileVerdictUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val up = Reachability.Answered(200, 40, AnswerMeaning.Reachable)
    private val down = Reachability.ConnectionTimedOut

    private fun show(results: List<Reachability>) {
        compose.setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        modules = ModuleRegistry.all,
                        installedVersion = "v0.7.0 (700)",
                        updateStatus = UpdateStatus.Idle,
                        onOpenUpdates = {}, onOpenModule = {}, onOpenSettings = {},
                        verdict = tileVerdictOf(results),
                    )
                }
            }
        }
    }

    @Test
    fun theSubtitleCarriesTheCountInWords() {
        show(List(8) { up } + List(2) { down })

        // Unmerged: the tile merges its descendants so it announces itself once, which absorbs the
        // subtitle's own node in the merged tree. That merge is the accessibility behaviour we want,
        // so the test reads around it rather than the tile giving it up.
        compose.onNodeWithTag(TAG_REACHABILITY_SUBTITLE, useUnmergedTree = true).assertTextEquals("8 of 10 available")
    }

    @Test
    fun nothingCheckedSaysSoRatherThanCountingZero() {
        // "0 of 10 available" before anything was asked would be the fixed-green lie with the
        // colours reversed.
        show(List(10) { Reachability.NotChecked })

        // Unmerged: the tile merges its descendants so it announces itself once, which absorbs the
        // subtitle's own node in the merged tree. That merge is the accessibility behaviour we want,
        // so the test reads around it rather than the tile giving it up.
        compose.onNodeWithTag(TAG_REACHABILITY_SUBTITLE, useUnmergedTree = true).assertTextEquals("Not checked yet")
    }

    @Test
    fun theContentDescriptionStatesTheCountAndNeverTheColour() {
        show(List(3) { up } + List(7) { down })

        compose.onNodeWithContentDescription("Reachability. 3 of 10 services available.").assertExists()
    }

    @Test
    fun theContentDescriptionSaysNotCheckedRatherThanZero() {
        show(List(10) { Reachability.NotChecked })

        compose.onNodeWithContentDescription("Reachability. Not checked yet.").assertExists()
    }
}
