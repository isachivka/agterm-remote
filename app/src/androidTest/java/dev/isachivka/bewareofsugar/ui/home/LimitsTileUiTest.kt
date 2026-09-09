package dev.isachivka.bewareofsugar.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.bewareofsugar.limits.LimitWindow
import dev.isachivka.bewareofsugar.limits.LimitsSnapshot
import dev.isachivka.bewareofsugar.limits.LimitsState
import dev.isachivka.bewareofsugar.limits.LimitsTile
import dev.isachivka.bewareofsugar.limits.ProviderError
import dev.isachivka.bewareofsugar.limits.ProviderLimits
import dev.isachivka.bewareofsugar.limits.TAG_LIMITS_FOOTER
import dev.isachivka.bewareofsugar.limits.TAG_LIMITS_TILE
import dev.isachivka.bewareofsugar.limits.WindowKind
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The limits tile's words — REQ-0045 — and its one gesture. Each state is rendered from a fixture with
 * no bridge behind it; the sentence, not the colour, is what these assert.
 */
@RunWith(AndroidJUnit4::class)
class LimitsTileUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-09-06T10:00:00Z")
    private val snapshot = LimitsSnapshot(
        fetchedAt = now.minusSeconds(12 * 60),
        claude = ProviderLimits.Windows(
            listOf(LimitWindow(WindowKind.FiveHour, 89, now.plusSeconds(2 * 3600 + 13 * 60))),
        ),
        codex = ProviderLimits.Failed(ProviderError.Expired),
    )

    /**
     * Unmerged: the tile is one clickable, so it merges its descendants and announces itself once,
     * which absorbs the footer's own node in the merged tree. That merge is the accessibility
     * behaviour we want - `TileVerdictUiTest` reads around it the same way rather than the tile
     * giving it up.
     */
    private fun footer() = compose.onNodeWithTag(TAG_LIMITS_FOOTER, useUnmergedTree = true)

    private fun show(state: LimitsState, onForce: () -> Unit = {}) {
        compose.setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LimitsTile(state = state, now = now, onForceRefresh = onForce)
                }
            }
        }
    }

    @Test
    fun aHeldSnapshotShowsTheWindowTheErrorAndTheAge() {
        show(LimitsState.Held(snapshot))
        compose.onNodeWithText("5h · 89% left").assertExists()
        compose.onNodeWithText("resets in 2h 13m").assertExists()
        compose.onNodeWithText("Open Codex once").assertExists()
        footer().assertTextEquals("updated 12 min ago")
    }

    @Test
    fun anUnreachableLaptopKeepsTheNumbersAndSaysSo() {
        show(LimitsState.Unreachable(snapshot))
        compose.onNodeWithText("5h · 89% left").assertExists()
        footer().assertTextContains("Laptop unreachable", substring = true)
    }

    @Test
    fun anOldBridgeIsNamedAsSuch() {
        show(LimitsState.BridgeTooOld)
        footer().assertTextEquals("Update the bridge on the Mac")
    }

    @Test
    fun notPairedSaysWhereToGo() {
        show(LimitsState.NotPaired)
        footer().assertTextEquals("Pair a laptop in Settings")
    }

    @Test
    fun aLongPressAsksOnceWhileIdle() {
        var presses = 0
        show(LimitsState.Held(snapshot)) { presses++ }
        compose.onNodeWithTag(TAG_LIMITS_TILE).performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(1, presses)
    }

    @Test
    fun aLongPressDuringAnUpdateAsksNothing() {
        var presses = 0
        show(LimitsState.Fetching(snapshot)) { presses++ }
        compose.onNodeWithTag(TAG_LIMITS_TILE).performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(0, presses)
    }
}
