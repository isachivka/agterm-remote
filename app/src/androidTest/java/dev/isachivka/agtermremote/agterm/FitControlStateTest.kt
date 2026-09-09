package dev.isachivka.agtermremote.agterm

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import dev.isachivka.agtermremote.debug.ComposeHostActivity
import dev.isachivka.agtermremote.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test

/**
 * **Which term of the fit control's enable condition is failing, and whether the owner can see why.**
 *
 * They pressed and nothing happened, and reported no explanation on screen. Two questions, and the
 * second is the more important: a diagnostic nobody sees is worse than no diagnostic, because it is
 * believed to be working.
 */
class FitControlStateTest {

    @get:Rule val compose = createAndroidComposeRule<ComposeHostActivity>()

    private val sessions = listOf(BridgeSession("S1", "W-main", "main", "agterm", "", true))

    // The bridge has answered, and it said OFF. That is the state the owner is in before they ever
    // press anything, and it is when the control must be usable.
    private var fitState = FitState(enabled = false, columns = 0)

    @Test
    fun whatTheFitControlActuallyShows() {
        compose.setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgtermScreen(
                        state = AgtermUiState.Sessions(sessions, watching = sessions.first(), screen = "$ ls\nfile"),
                        onRefresh = {}, onOpen = {}, onCloseSession = {},
                        onFitToPhone = { _, _, _ -> }, onRestoreWindow = {},
                        fit = fitState,
                        typing = TypingState.Closed,
                        onOpenTyping = {}, onCloseTyping = {}, onDraftChange = {}, onSendText = {}, onSendKey = {},
                        onPickFile = {}, onDisconnect = {}, onPair = {}, onBack = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(1500)

        val toggle = compose.onNodeWithTag(TAG_FIT_TO_PHONE).fetchSemanticsNode()
        val enabled = !toggle.config.contains(SemanticsProperties.Disabled)
        Log.i(TAG, "fit control enabled = $enabled")

        val reasons = compose.onAllNodes(hasTestTag(TAG_FIT_DISABLED_REASON), useUnmergedTree = true)
            .fetchSemanticsNodes()
        Log.i(TAG, "reason nodes on screen = ${reasons.size}")
        reasons.forEach { n ->
            Log.i(TAG, "reason text = ${n.config.getOrElse(SemanticsProperties.Text) { emptyList() }}")
            Log.i(TAG, "reason bounds = ${n.boundsInWindow}")
        }

        val box = compose.onAllNodes(hasTestTag(TAG_BOX), useUnmergedTree = true).fetchSemanticsNodes()
        Log.i(TAG, "terminal box nodes = ${box.size}, width px = ${box.firstOrNull()?.size?.width}")

        // **The deadlock, broken.** Once the bridge has answered - even with OFF - the control must be
        // live, because off is exactly when the owner needs to press it.
        if (!enabled) {
            throw AssertionError("the control is disabled with the bridge having answered")
        }
    }

    private companion object { const val TAG = "FIT-DIAG" }
}
