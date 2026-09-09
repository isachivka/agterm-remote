package dev.isachivka.bewareofsugar.agterm

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.isachivka.bewareofsugar.debug.ComposeHostActivity
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The terminal screen with a REAL keyboard up, in the REAL activity.
 *
 * ### Why this exists and the plain Compose rule does not answer it
 *
 * `createComposeRule` renders into a throwaway host, so there is no IME and the activity's
 * `windowSoftInputMode` never applies. Screenshots from it showed the layout and could say nothing at
 * all about the thing the owner actually complained about — a gap that only appears with the keyboard
 * up. An instrument that cannot see the part of the subject that matters.
 *
 * `ComposeHostActivity` is a bare activity in the debug manifest carrying the SAME
 * `windowSoftInputMode` as MainActivity, so the property under test really applies. MainActivity
 * itself cannot be used: it sets its own content in `onCreate` and the rule refuses to set content
 * over it. `UiAutomation.takeScreenshot` captures the whole display rather than the Compose root,
 * which is the only way the keyboard is in the picture.
 */
class TerminalWithKeyboardTest {

    @get:Rule val compose = createAndroidComposeRule<ComposeHostActivity>()

    private val sessions = listOf(
        BridgeSession("4C9B5C9B-C77F-4913-8BEA-9DF7513AC8BA", "W-main", "main", "agterm", "", true),
    )

    private val screen = buildString {
        appendLine("$ ls -la")
        appendLine("total 48")
        appendLine("drwxr-xr-x  8 is  staff   256 Jul 29 22:20 .")
        appendLine("$ hello")
        appendLine("zsh: command not found: hello")
        append("$ ")
    }

    private var typing by mutableStateOf<TypingState>(TypingState.Closed)
    private var draft by mutableStateOf("")

    private fun show(next: TypingState, text: String = "") {
        typing = next
        draft = text
        compose.waitForIdle()
    }

    private fun content() {
        compose.setContent {
            AppTheme {
                // safeDrawingPadding as MainActivity applies it at the root - without it the status
                // bar draws over the header and the capture shows an overlap this app does not have.
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    AgtermScreen(
                            state = AgtermUiState.Sessions(sessions, watching = sessions.first(), screen = screen),
                            onRefresh = {}, onOpen = {}, onCloseSession = {},
                            onFitToPhone = { _, _, _ -> }, onRestoreWindow = {},
                        fit = FitState(enabled = false, columns = 0),
                        typing = typing,
                        draft = draft,
                        onOpenTyping = {}, onCloseTyping = {}, onDraftChange = {}, onSendText = {}, onSendKey = {},
                        onPickFile = {},
                        onDisconnect = {}, onPair = {}, onBack = {},
                    )
                }
            }
        }
    }

    private fun grab(name: String) {
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = File(compose.activity.filesDir, "screenshots").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("no screenshot written", file.length() > 0)
    }

    @Test
    fun keyboardClosedThenOpen() {
        content()
        show(TypingState.Closed)
        Thread.sleep(1200)
        grab("ime-1-closed-bar-shut")

        show(TypingState.Composing())
        Thread.sleep(1200)
        grab("ime-2-bar-open-no-keyboard")

        // Tapping the field is what raises the IME - the same gesture the owner makes.
        compose.onNodeWithTag(TAG_TYPING_FIELD).performClick()
        Thread.sleep(2500)
        grab("ime-3-keyboard-up")

        show(TypingState.Composing(), text = "git status")
        Thread.sleep(2000)
        grab("ime-4-keyboard-up-with-draft")
    }
}
