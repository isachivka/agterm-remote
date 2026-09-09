package dev.isachivka.bewareofsugar.agterm

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Folding a workspace, and the memory it must not fight.
 *
 * The owner asked for this — *"в меню сессий сделай чтобы разделы можно было 'сворачивать'"* — and the
 * risk is not that folding fails to fold. It is that folding changes the rows on screen, the restore
 * is keyed on the rows on screen, and the list therefore jumps under their thumb every time they press
 * a header. That is what [collapsingDoesNotMoveTheListUnderTheirThumb] is for; the rest is the feature.
 *
 * **Every name in this file is invented.** Ids and counts are what is asserted.
 */
class WorkspaceCollapseTest {

    @get:Rule val compose = createComposeRule()

    private val groupIds = listOf("W-one", "W-two", "W-three")
    private val perGroup = 6

    /** Three workspaces of six, with one session waiting on the owner in the LAST group. */
    private val sessions = groupIds.flatMapIndexed { g, workspace ->
        (0 until perGroup).map { i ->
            BridgeSession(
                id = "S$g-$i",
                workspaceId = workspace,
                workspace = "group $g",
                name = "session $g-$i",
                title = "doing something",
                active = false,
                status = if (workspace == "W-three" && i == 0) SessionStatus.NeedsYou else SessionStatus.Running,
            )
        }
    }

    /** The last anchor the screen reported, which is how it remembers where the owner is. */
    private var anchor: ListPosition.Anchor? = null

    private fun show() {
        compose.setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var closed by remember { mutableStateOf(emptySet<String>()) }
                    AgtermScreen(
                        state = AgtermUiState.Sessions(sessions),
                        onRefresh = {}, onOpen = {}, onCloseSession = {},
                        onFitToPhone = { _, _, _ -> }, onRestoreWindow = {},
                        fit = FitState(enabled = false, columns = 0),
                        typing = TypingState.Closed,
                        onOpenTyping = {}, onCloseTyping = {}, onDraftChange = {},
                        onSendText = {}, onSendKey = {}, onPickFile = {},
                        onDisconnect = {}, onPair = {}, onBack = {},
                        onListAnchor = { anchor = it },
                        closedWorkspaces = closed,
                        onClosedWorkspaces = { closed = it },
                        laptop = "10.0.0.1",
                    )
                }
            }
        }
    }

    private fun headers() = compose.onAllNodesWithTag(TAG_WORKSPACE_HEADING)
    private fun dots() = compose.onAllNodesWithTag(TAG_STATUS_DOT)

    @Test
    fun foldingAGroupHidesExactlyItsOwnRows() {
        show()
        headers().assertCountEquals(3)
        val before = dots().fetchSemanticsNodes().size

        headers()[0].performClick()
        compose.waitForIdle()

        // Still three groups - folding shuts a workspace, it never removes one.
        headers().assertCountEquals(3)
        assertEquals(
            "folding the first group should hide its rows and nobody else's",
            before - perGroup,
            dots().fetchSemanticsNodes().size,
        )
    }

    /** A folded group still counts what is inside it. Folding hides rows, never facts. */
    @Test
    fun aFoldedGroupStillCountsItsSessions() {
        show()
        headers()[0].performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag(TAG_GROUP_COUNT).assertCountEquals(3)
        compose.onAllNodesWithTag(TAG_GROUP_COUNT)[0]
            .assertContentDescriptionEqualsCount(perGroup)
    }

    /**
     * **The dot appears only when the group is shut AND something inside is waiting.** With the group
     * open the rows say so themselves, and a dot that duplicates what is on screen stops meaning
     * anything; on a group where nothing is blocked it would be a light that is always on.
     */
    @Test
    fun theAttentionDotIsShownOnlyByAFoldedGroupThatIsWaitingOnThem() {
        show()
        compose.onAllNodesWithTag(TAG_GROUP_ATTENTION).assertCountEquals(0)

        // Fold everything. Only the third group holds a session that is waiting.
        compose.onNodeWithTag(TAG_COLLAPSE_ALL).performClick()
        compose.waitForIdle()

        headers().assertCountEquals(3)
        compose.onAllNodesWithTag(TAG_GROUP_ATTENTION).assertCountEquals(1)
    }

    @Test
    fun theHeaderControlFoldsEverythingAndOpensItAgain() {
        show()
        compose.onNodeWithTag(TAG_COLLAPSE_ALL).performClick()
        compose.waitForIdle()
        dots().assertCountEquals(0)

        compose.onNodeWithTag(TAG_COLLAPSE_ALL).performClick()
        compose.waitForIdle()
        dots().assertCountEquals(groupIds.size * perGroup)
    }

    /**
     * **The one that is not about folding.**
     *
     * `SessionList` restores the owner's position in a `LaunchedEffect`, and folding changes the rows
     * that effect would be keyed on. Keyed wrongly, every press of a header re-runs the restore and
     * scrolls the list — a control fighting the memory beside it. So the key is the LISTING, which
     * folding cannot change, and this is what says so from outside the code.
     *
     * The anchor is the screen's own record of the row at the top. It is asserted before and after
     * folding a group **below** the viewport: nothing above the owner moved, so nothing about where
     * they are looking may move either.
     */
    @Test
    fun collapsingDoesNotMoveTheListUnderTheirThumb() {
        show()
        // Down into the second group, far enough that the first group is off the top.
        compose.onNodeWithTag(TAG_LIST).performScrollToIndex(9)
        compose.waitForIdle()
        val before = anchor
        assertEquals("the fixture must have scrolled somewhere to be a test at all", true, before != null)

        // The last group's header, which is below where they are looking.
        headers()[2].performClick()
        compose.waitForIdle()

        assertEquals("folding a group below them moved the list", before, anchor)
    }
}

/** Reads the count a badge announces, which is the only place its number is said in words. */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertContentDescriptionEqualsCount(count: Int) {
    // Explicit receiver: bare `assert` resolves to Kotlin's own, which takes a Boolean.
    this.assert(hasContentDescription(count.toString(), substring = true))
}
