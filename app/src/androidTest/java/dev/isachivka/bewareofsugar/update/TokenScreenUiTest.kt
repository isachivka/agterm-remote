package dev.isachivka.bewareofsugar.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.isachivka.bewareofsugar.BuildConfig
import dev.isachivka.bewareofsugar.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen, state by state. [TokenScreen] takes its state as a parameter, so each state can be
 * rendered directly rather than driven into existence through the API.
 *
 * The state is held here and updated by the callbacks, so typing genuinely changes what the field
 * holds — with no-op callbacks the masking test below would pass without proving anything.
 */
@RunWith(AndroidJUnit4::class)
class TokenScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val typed = "not-a-real-token"

    private fun show(initial: TokenScreenState = TokenScreenState()) {
        compose.setContent {
            var state by remember { mutableStateOf(initial) }
            AppTheme {
                TokenScreen(
                    state = state,
                    onInputChange = { state = state.copy(input = it) },
                    onSave = {},
                    onReplace = {},
                    onRemove = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun firstRunExplainsWhyItIsAskingAndNamesTheRepository() {
        show()

        compose.onNodeWithTag(TAG_INTRO).assertIsDisplayed()
        // The sentence the owner reads months later, carrying the slug from its single source.
        compose.onNodeWithText(BuildConfig.GITHUB_REPO, substring = true).assertIsDisplayed()
        compose.onNodeWithTag(TAG_FIELD).assertIsDisplayed()
    }

    @Test
    fun nothingCanBeSavedUntilSomethingIsTyped() {
        show()
        compose.onNodeWithTag(TAG_SAVE).assertIsNotEnabled()
    }

    @Test
    fun whitespaceAloneCannotBeSaved() {
        show(TokenScreenState(input = "   "))
        compose.onNodeWithTag(TAG_SAVE).assertIsNotEnabled()
    }

    @Test
    fun typingEnablesSaving() {
        show()
        compose.onNodeWithTag(TAG_FIELD).performTextInput(typed)
        compose.onNodeWithTag(TAG_SAVE).assertIsEnabled()
    }

    /**
     * The token is not in what the screen displays, and not in the `EditableText` an accessibility
     * service reads out. The field is also marked as a password node.
     */
    @Test
    fun theTypedTokenIsNotDisplayedAndNotInEditableText() {
        show()
        compose.onNodeWithTag(TAG_FIELD).performTextInput(typed)
        // It really did land in the field: Save only enables for non-blank input.
        compose.onNodeWithTag(TAG_SAVE).assertIsEnabled()

        val exposedIn = whereTextAppears(typed)
        assertTrue("the token appears in: $exposedIn", exposedIn.isEmpty())

        compose.onNodeWithTag(TAG_FIELD)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }

    /**
     * The boundary, asserted rather than assumed: while the field is on screen, Compose keeps the
     * untransformed value in `InputText`, because the IME and autofill need it. This test exists so
     * that fact stays visible and cannot quietly change - if a future Compose masks it too, this
     * fails and the KDoc on [TokenScreen] gets less pessimistic.
     */
    @Test
    fun theRawInputTextIsTheKnownExceptionToThat() {
        show()
        compose.onNodeWithTag(TAG_FIELD).performTextInput(typed)

        val inputText = compose.onNodeWithTag(TAG_FIELD)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.InputText)
            ?.text

        assertEquals("if this changes, so should TokenScreen's KDoc", typed, inputText)
    }

    @Test
    fun aSavedTokenIsNeverDisplayedBack() {
        show(TokenScreenState(status = TokenStatus.Saved(1_700_000_000L)))

        compose.onNodeWithTag(TAG_SAVED).assertIsDisplayed()
        compose.onNodeWithTag(TAG_REMOVE).assertIsDisplayed()
        // No field at all while a saved token sits there unchallenged.
        compose.onNodeWithTag(TAG_FIELD).assertDoesNotExist()
    }

    @Test
    fun aRejectedTokenSaysSoWithoutLosingWhatWasTyped() {
        show(TokenScreenState(input = typed, status = TokenStatus.NotAccepted))

        compose.onNodeWithTag(TAG_STATUS).assertIsDisplayed()
        // The wording has to cover an expired token as well as a typo, because at this point they
        // are the same 401.
        compose.onNodeWithText("expired", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(TAG_FIELD).assertIsDisplayed()
        compose.onNodeWithTag(TAG_SAVE).assertIsEnabled()
    }

    @Test
    fun aRateLimitSaysWhenToComeBack() {
        show(TokenScreenState(status = TokenStatus.RateLimited(1_700_000_000L)))
        compose.onNodeWithText("rate-limiting", substring = true).assertIsDisplayed()
    }

    @Test
    fun beingOfflineIsItsOwnLineAndNotAVerdictOnTheToken() {
        show(TokenScreenState(input = typed, status = TokenStatus.Offline))
        compose.onNodeWithText("Can't reach GitHub", substring = true).assertIsDisplayed()
    }

    /**
     * The first clause of this copy is the load-bearing one: told only that saving failed, the owner
     * would go and mint a replacement token for a problem that was never the token's.
     */
    @Test
    fun aTokenThePhoneWillNotStoreSaysTheTokenWasGood() {
        show(TokenScreenState(input = typed, status = TokenStatus.NotStored))

        compose.onNodeWithTag(TAG_STATUS).assertIsDisplayed()
        compose.onNodeWithText("That token is good", substring = true).assertIsDisplayed()
        // And it is still in the field, so Save can simply be pressed again.
        compose.onNodeWithTag(TAG_FIELD).assertIsDisplayed()
        compose.onNodeWithTag(TAG_SAVE).assertIsEnabled()
    }

    @Test
    fun anUnreadableStoredTokenAsksForItAgain() {
        show(TokenScreenState(status = TokenStatus.Unreadable(permanent = true, reason = "the key was invalidated", sinceEpochSeconds = 1_700_000_000L)))

        compose.onNodeWithTag(TAG_STATUS).assertIsDisplayed()
        compose.onNodeWithTag(TAG_FIELD).assertIsDisplayed()
    }

    @Test
    fun theProgressLineShowsWhileChecking() {
        show(TokenScreenState(input = typed, status = TokenStatus.Validating))

        compose.onNodeWithText("Checking", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(TAG_SAVE).assertIsNotEnabled()
    }

    /**
     * Where the text appears, if anywhere: what a node displays, and what an editable field reports
     * as its contents. Named rather than boolean, so a failure says which one it was.
     */
    private fun whereTextAppears(text: String): List<String> {
        // Deliberately not hasText(), which matches EditableText as well and would conflate the two.
        val displayed = compose
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes()
            .filter { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.contains(text) }
            }
            .map { "displayed text" }
        val editable = compose
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .fetchSemanticsNodes()
            .filter { it.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains(text) == true }
            .map { "EditableText semantics" }
        return displayed + editable
    }
}
