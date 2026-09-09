package dev.isachivka.bewareofsugar.agterm

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.bewareofsugar.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rotating the phone must not throw away the connection or the owner's place in the output.
 *
 * ### Why this is an instrumentation test and not a unit test
 *
 * The defect it guards against is invisible to anything that does not actually destroy and recreate an
 * activity. Before the ViewModel existed, the code looked entirely correct: state was in `remember`,
 * the effect was keyed properly, and every unit test passed. **The failure only appeared on a device**
 * — the window manager logged `finishDrawing of relaunch` and focus went to `BiometricPrompt`.
 *
 * "Compiles and is wired" was the least predictive signal of this milestone. So this recreates the
 * activity for real and asks the platform what survived.
 */
@RunWith(AndroidJUnit4::class)
class RotationKeepsThePlaceTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun modelOf(activity: MainActivity): AgtermViewModel =
        ViewModelProvider(
            activity,
            viewModelFactory { initializer { AgtermViewModel(activity.applicationContext) } },
        )[AgtermViewModel::class.java]

    /**
     * **The property**: the same ViewModel instance is handed back after recreation.
     *
     * That is what makes the connection, the fetched list and the scroll position survive — all three
     * hang off this object, so identity across recreation is the single thing worth asserting.
     */
    @Test
    fun theViewModelSurvivesActivityRecreation() {
        val before = modelOf(rule.activity)
        assertNotNull(before)

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()

        val after = modelOf(rule.activity)
        assertSame(
            "recreation produced a different AgtermViewModel, so the connection and the reading " +
                "position were thrown away - which is the whole defect this guards",
            before,
            after,
        )
    }

    /**
     * And the part the owner actually feels: the scroll position is still where they left it.
     *
     * Asserted on the ViewModel's own ScrollState rather than through the UI, because the terminal box
     * is only composed when a session is open and this test does not have a paired laptop. The
     * ScrollState object is the thing threaded into `TerminalBox`, so its survival is the property.
     */
    @Test
    fun theReadingPositionSurvivesRecreation() {
        val before = modelOf(rule.activity)
        // A position no default could produce by accident.
        before.terminalHorizontal.dispatchRawDelta(-137f)
        val position = before.terminalHorizontal.value

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()

        val after = modelOf(rule.activity)
        assertSame(before.terminalHorizontal, after.terminalHorizontal)
        assertEquals(
            "the horizontal scroll position was lost across recreation, so panning right and " +
                "rotating drops the owner back to column zero",
            position,
            after.terminalHorizontal.value,
        )
    }

    /**
     * **The test that binds this to OUR wiring rather than to Android's.**
     *
     * Everything above would pass even if `AgtermHost` still kept its state in `remember`: those tests
     * ask a ViewModel they created themselves to survive recreation, and ViewModels surviving
     * recreation is a platform guarantee rather than anything this codebase does. They would have been
     * green on the broken code.
     *
     * So this one opens the Terminal screen and then asks for the ViewModel with a factory that
     * **throws if it is invoked**. If `AgtermHost` created the ViewModel, it is already in the
     * activity's store and the factory is never called. If `AgtermHost` reverted to `remember`, there
     * is nothing in the store, the factory runs, and this fails.
     */
    @Test
    fun theHostItselfPutsTheStateInTheViewModel() {
        rule.onNodeWithText("Terminal").performClick()
        rule.waitForIdle()

        val neverCall = viewModelFactory {
            initializer<AgtermViewModel> {
                throw AssertionError(
                    "no AgtermViewModel in the activity's store after opening Terminal, so " +
                        "AgtermHost is holding its state somewhere that does not survive rotation",
                )
            }
        }
        val model = ViewModelProvider(rule.activity, neverCall)[AgtermViewModel::class.java]
        assertNotNull(model)
    }

    /**
     * The negative control: recreation really is happening.
     *
     * Without this, both assertions above would also pass if `recreate()` silently did nothing — a
     * test that cannot distinguish "state survived" from "nothing was destroyed" is not a test. The
     * Activity instance must differ even though the ViewModel does not.
     */
    @Test
    fun recreationActuallyDestroysTheActivity() {
        val before = rule.activity

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()

        val after = rule.activity
        assert(before !== after) {
            "the activity instance is unchanged, so recreate() did nothing and the other tests in " +
                "this class prove nothing"
        }
    }
}
