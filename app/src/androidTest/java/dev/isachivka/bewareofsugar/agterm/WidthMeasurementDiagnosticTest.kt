package dev.isachivka.bewareofsugar.agterm

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import dev.isachivka.bewareofsugar.debug.ComposeHostActivity
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test

/**
 * Where the extra column comes from. **A measurement, not a fix.**
 *
 * The owner reports one column too many: every line overflows the box by a character and the phone
 * pans. Two candidates, and they need different repairs — the reported box width may include the
 * padding it claims to exclude, or the measured character may read a fraction under the truth so the
 * floor lets one more through. This prints both so the answer is read rather than guessed.
 */
class WidthMeasurementDiagnosticTest {

    @get:Rule val compose = createAndroidComposeRule<ComposeHostActivity>()

    @Volatile private var reportedBoxDp = 0.0

    @Test
    fun whatTheTwoMeasurementsActuallyAre() {
        compose.setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    val measurer = rememberTextMeasurer()

                    // Read outside the remember blocks: TerminalFontFamily builds the fallback chain
                    // from the composition's Context and is a composable read.
                    val family = TerminalFontFamily

                    val oneChar = remember {
                        measurer.measure(
                            "X",
                            TextStyle(fontFamily = family, fontSize = FitToPhone.TERMINAL_FONT_SIZE_SP.sp),
                        ).size.width
                    }
                    // A hundred characters, so per-character rounding is a hundredth of what it is
                    // for one. If these two disagree, the single-character measure is the suspect.
                    val hundredChars = remember {
                        measurer.measure(
                            "X".repeat(100),
                            TextStyle(fontFamily = family, fontSize = FitToPhone.TERMINAL_FONT_SIZE_SP.sp),
                        ).size.width
                    }

                    with(density) {
                        Log.i(TAG, "char from 1: ${oneChar}px = ${oneChar.toDp().value}dp")
                        Log.i(TAG, "char from 100: ${hundredChars}px total = ${hundredChars / 100.0}px each " +
                            "= ${(hundredChars / 100.0).toInt().toDp().value}dp (approx)")
                        Log.i(TAG, "density=${density.density}")
                    }

                    TerminalBox(text = "hello", onMeasured = { reportedBoxDp = it })
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(1500)

        val screenDp = compose.activity.resources.configuration.screenWidthDp
        Log.i(TAG, "screen=${screenDp}dp  padding=${FitToPhone.TERMINAL_HORIZONTAL_PADDING_DP}dp each side")
        Log.i(TAG, "content width should be ${screenDp - 2 * FitToPhone.TERMINAL_HORIZONTAL_PADDING_DP}dp")
        Log.i(TAG, "TerminalBox REPORTED ${reportedBoxDp}dp")
    }

    private companion object { const val TAG = "WIDTH-DIAG" }
}
