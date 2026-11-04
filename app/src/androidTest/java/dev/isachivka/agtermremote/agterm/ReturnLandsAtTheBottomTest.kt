package dev.isachivka.agtermremote.agterm

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import dev.isachivka.agtermremote.debug.ComposeHostActivity
import dev.isachivka.agtermremote.ui.theme.AppTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A held pane that comes back - after a heal, or after the phone was away - lands at its bottom.
 *
 * The owner, 2026-11-04: in zmx mode, after the connection came back, the terminal sat at the top
 * of five hundred rows. The mechanism, reproduced by [aHealWithoutTheCounterStaysAtTheTop]: a heal
 * calls `watch` for the session already on screen, `watch` blanks the screen (offset clamps to
 * zero), the id does not change so the opening landing is not armed, and if the scroll memory has
 * the owner as reading, the new screen is left at offset zero.
 */
class ReturnLandsAtTheBottomTest {

    @get:Rule val compose = createAndroidComposeRule<ComposeHostActivity>()

    private val sessions = listOf(
        BridgeSession("4C9B5C9B-C77F-4913-8BEA-9DF7513AC8BA", "W-main", "main", "agterm", "", true),
    )
    private val scroll = ScrollState(0)
    private var state by mutableStateOf<AgtermUiState>(AgtermUiState.Sessions(sessions))
    private var opened by mutableIntStateOf(0)

    private fun tall(tag: String) = (1..500).joinToString("\n") { "$tag line $it" }

    private fun watching(screen: String) =
        AgtermUiState.Sessions(sessions, watching = sessions.first(), screen = screen)

    private fun settle(ms: Long = 1200) {
        compose.waitForIdle()
        Thread.sleep(ms)
        compose.waitForIdle()
    }

    private fun atBottom() = TerminalScroll.isAtBottom(scroll.value, scroll.maxValue)

    /** Opens the session the way `watch` does, then scrolls to the top as a reader would. */
    private fun openThenReadFromTheTop() {
        compose.setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgtermScreen(
                        state = state,
                        onRefresh = {}, onOpen = {}, onCloseSession = {},
                        onFitToPhone = { _, _, _ -> }, onRestoreWindow = {},
                        fit = FitState(enabled = false, columns = 0),
                        typing = TypingState.Closed,
                        draft = "",
                        onOpenTyping = {}, onCloseTyping = {}, onDraftChange = {}, onSendText = {}, onSendKey = {},
                        onPickFile = {},
                        onDisconnect = {}, onPair = {}, onOpenSettings = {},
                        terminalVertical = scroll,
                        opened = opened,
                    )
                }
            }
        }
        settle(300)
        opened++
        state = watching("")
        settle(300)
        state = watching(tall("A"))
        settle()
        assertTrue("opening a session lands at its bottom", atBottom())
        assertTrue(scroll.maxValue > 0)
        runBlocking { scroll.scrollTo(0) }
        settle(300)
        assertEquals(0, scroll.value)
    }

    /** A heal, as `watch` performs it for the session already on screen. */
    private fun heal(bumpOpened: Boolean) {
        if (bumpOpened) opened++
        state = watching("")
        settle(300)
        state = watching(tall("B"))
        settle()
    }

    @Test
    fun aHealWithoutTheCounterStaysAtTheTop() {
        openThenReadFromTheTop()
        heal(bumpOpened = false)
        assertEquals("the control: without the counter the blanked screen leaves them at zero", 0, scroll.value)
    }

    @Test
    fun aHealLandsAtTheBottom() {
        openThenReadFromTheTop()
        heal(bumpOpened = true)
        assertTrue("value=${scroll.value} max=${scroll.maxValue}", atBottom())
    }

    @Test
    fun comingBackToTheAppLandsAtTheBottom() {
        openThenReadFromTheTop()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        state = watching(tall("B"))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()
        assertTrue("value=${scroll.value} max=${scroll.maxValue}", atBottom())
    }

    @Test
    fun comingBackToAnUnchangedScreenLandsAtTheBottomAndDisarms() {
        openThenReadFromTheTop()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()
        assertTrue("value=${scroll.value} max=${scroll.maxValue}", atBottom())

        // Reading again after the return: the next screen must not yank them back down.
        runBlocking { scroll.scrollTo(0) }
        settle(300)
        state = watching(tall("C"))
        settle()
        assertEquals("a landing left armed by the return fired on a later poll", 0, scroll.value)
    }
}
