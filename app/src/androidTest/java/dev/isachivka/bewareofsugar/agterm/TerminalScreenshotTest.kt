package dev.isachivka.bewareofsugar.agterm

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The terminal screen and its input bar, photographed.
 *
 * **Because this is the third time a feature has been right in the code and wrong on the screen.**
 * Unit tests hold the behaviour; nothing held the layout, and the owner found all five of the last
 * defects with their eyes. These are for looking at.
 */
class TerminalScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val sessions = listOf(
        BridgeSession("4C9B5C9B-C77F-4913-8BEA-9DF7513AC8BA", "W-main", "main", "agterm", "", true),
        BridgeSession("E0AECF7B-C459-4434-80A0-102444ECE9C5", "W-main", "main", "bridge", "", false),
    )

    private val screen = buildString {
        appendLine("$ ls -la")
        appendLine("total 48")
        appendLine("drwxr-xr-x  8 is  staff   256 Jul 29 22:20 .")
        appendLine("-rw-r--r--  1 is  staff  1024 Jul 29 22:19 notes.md")
        appendLine("$ hello")
        appendLine("zsh: command not found: hello")
        append("$ ")
    }

    private fun capture(name: String, typing: TypingState, fitted: Boolean = false, draft: String = "") {
        compose.setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
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
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screenshots",
        ).apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("no screenshot written to ${file.absolutePath}", file.length() > 0)
    }

    @Test fun barClosed() = capture("terminal-1-bar-closed", TypingState.Closed)
    @Test fun barClosedFitted() = capture("terminal-2-fitted", TypingState.Closed, fitted = true)
    @Test fun barComposing() = capture("terminal-3-composing", TypingState.Composing(), draft = "git status")
    @Test fun barSent() = capture("terminal-4-sent", TypingState.Sent)
    @Test fun barNoChange() = capture("terminal-5-no-change", TypingState.NoChange)
}
