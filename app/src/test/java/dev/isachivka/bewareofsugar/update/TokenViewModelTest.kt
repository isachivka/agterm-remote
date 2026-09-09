package dev.isachivka.bewareofsugar.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen's behaviour, without a screen. Everything runs on an unconfined scope, so each call has
 * finished by the time it returns.
 */
class TokenViewModelTest {

    private val token = "not-a-real-token"
    private val now = 1_700_000_000L

    private class FakeStore(var stored: StoredToken = StoredToken.None) : TokenStore {
        var saved: GitHubToken? = null
        var cleared = false
        /** When false, the phone would not store it - blocker 3's path. */
        var storageWorks = true

        override suspend fun read(): StoredToken = stored

        override suspend fun save(token: GitHubToken, validatedAtEpochSeconds: Long): Boolean {
            if (!storageWorks) return false
            saved = token
            stored = StoredToken.Present(token, validatedAtEpochSeconds)
            return true
        }

        override suspend fun clear() {
            cleared = true
            stored = StoredToken.None
            // The real store clears the whole preferences file, so the remembered tag goes with the
            // token. A fake that kept it would let a guard pass here and fail on a phone.
            foundTag = null
        }

        var foundTag: String? = null

        override suspend fun lastFoundTag(): String? = foundTag
        override suspend fun recordFound(tag: String?) {
            foundTag = tag
        }

        var lastChecked = 0L

        override suspend fun lastCheckedAt(): Long = lastChecked

        override suspend fun recordChecked(atEpochSeconds: Long) {
            lastChecked = atEpochSeconds
        }
    }

    private class FakeValidator(var result: TokenValidationResult) : TokenValidator {
        var calls = 0
        var lastToken: GitHubToken? = null

        override suspend fun validate(token: GitHubToken): TokenValidationResult {
            calls++
            lastToken = token
            return result
        }
    }

    private fun viewModel(
        store: TokenStore = FakeStore(),
        validator: TokenValidator = FakeValidator(TokenValidationResult.Valid),
    ) = TokenViewModel(store, validator, { now }, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `with nothing stored it starts empty`() {
        val state = viewModel().state.value
        assertEquals(TokenStatus.NoToken, state.status)
        assertFalse(state.canSave)
        assertTrue(state.showsField)
    }

    @Test
    fun `a stored token comes back as saved, and the field is not shown`() {
        val store = FakeStore(StoredToken.Present(GitHubToken(token), now))
        val state = viewModel(store = store).state.value
        assertEquals(TokenStatus.Saved(now), state.status)
        assertFalse(state.showsField)
        assertEquals("", state.input)
    }

    @Test
    fun `a stored token that will not decrypt asks for it again`() {
        val store = FakeStore(StoredToken.Unreadable(permanent = true, reason = "the key was invalidated", sinceEpochSeconds = 1_700_000_000L))
        assertEquals(TokenStatus.Unreadable(permanent = true, reason = "the key was invalidated", sinceEpochSeconds = 1_700_000_000L), viewModel(store = store).state.value.status)
    }

    @Test
    fun `whitespace alone cannot be saved`() {
        val vm = viewModel()
        vm.onInputChange("   \n")
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun `a valid token is stored trimmed, and the field is cleared`() {
        val store = FakeStore()
        val validator = FakeValidator(TokenValidationResult.Valid)
        val vm = viewModel(store, validator)

        vm.onInputChange("  $token\n")
        assertTrue(vm.state.value.canSave)
        vm.onSave()

        assertEquals(GitHubToken(token), validator.lastToken)
        assertEquals(GitHubToken(token), store.saved)
        assertEquals(TokenStatus.Saved(now), vm.state.value.status)
        assertEquals("", vm.state.value.input)
    }

    @Test
    fun `a rejected token is not stored and what was typed is kept`() {
        val store = FakeStore()
        val vm = viewModel(store, FakeValidator(TokenValidationResult.NotAccepted))

        vm.onInputChange(token)
        vm.onSave()

        assertNull("a token GitHub refused must not reach the store", store.saved)
        assertEquals(TokenStatus.NotAccepted, vm.state.value.status)
        assertEquals("so a typo can be fixed rather than retyped", token, vm.state.value.input)
    }

    @Test
    fun `being offline is not a verdict on the token`() {
        val store = FakeStore(StoredToken.Present(GitHubToken(token), now))
        val vm = viewModel(store, FakeValidator(TokenValidationResult.Offline))

        vm.onReplace()
        vm.onInputChange("a-different-token")
        vm.onSave()

        assertEquals(TokenStatus.Offline, vm.state.value.status)
        assertNull(store.saved)
        assertTrue("the working token must survive a train tunnel", store.stored is StoredToken.Present)
    }

    @Test
    fun `a 503 does not cost the owner a working token either`() {
        val store = FakeStore(StoredToken.Present(GitHubToken(token), now))
        val vm = viewModel(store, FakeValidator(TokenValidationResult.GitHubUnavailable(503)))

        vm.onReplace()
        vm.onInputChange("a-different-token")
        vm.onSave()

        assertEquals(TokenStatus.GitHubUnavailable(503), vm.state.value.status)
        assertTrue(store.stored is StoredToken.Present)
    }

    @Test
    fun `a rate limit carries its reset time through to the screen`() {
        val vm = viewModel(validator = FakeValidator(TokenValidationResult.RateLimited(now + 600)))
        vm.onInputChange(token)
        vm.onSave()
        assertEquals(TokenStatus.RateLimited(now + 600), vm.state.value.status)
    }

    @Test
    fun `each failure maps to its own state`() {
        val cases = mapOf(
            TokenValidationResult.NotAccepted to TokenStatus.NotAccepted,
            TokenValidationResult.NoRepoAccess to TokenStatus.NoRepoAccess,
            TokenValidationResult.InsufficientPermission to TokenStatus.InsufficientPermission,
            TokenValidationResult.Offline to TokenStatus.Offline,
        )
        cases.forEach { (result, expected) ->
            val vm = viewModel(validator = FakeValidator(result))
            vm.onInputChange(token)
            vm.onSave()
            assertEquals(expected, vm.state.value.status)
        }
    }

    @Test
    fun `replacing shows the field again without dropping the stored token`() {
        val store = FakeStore(StoredToken.Present(GitHubToken(token), now))
        val vm = viewModel(store = store)

        vm.onReplace()

        assertTrue(vm.state.value.showsField)
        assertFalse(store.cleared)
        assertTrue(store.stored is StoredToken.Present)
    }

    @Test
    fun `removing clears the store and returns to the first-run screen`() = runBlocking {
        val store = FakeStore(StoredToken.Present(GitHubToken(token), now))
        val vm = viewModel(store = store)

        vm.onRemove()

        assertTrue(store.cleared)
        assertEquals(TokenScreenState(), vm.state.value)
    }

    @Test
    fun `once saved, tapping save again does not call the API with an empty field`() {
        // Note what this does and does not prove: on an unconfined scope the first save has fully
        // completed by the time onSave returns, so this covers the cleared-field guard. The
        // "already validating" guard is not exercised here - there is no window to hit.
        val validator = FakeValidator(TokenValidationResult.Valid)
        val vm = viewModel(validator = validator)
        vm.onInputChange(token)

        vm.onSave()
        vm.onSave()

        assertEquals(1, validator.calls)
    }

    /**
     * GitHub said yes and the phone would not keep it. The owner has to be told, and what they typed
     * has to still be there - otherwise they paste it again, or worse, go and mint a new token for a
     * problem that was never the token's.
     */
    @Test
    fun `a token the phone will not store is reported, and what was typed is kept`() {
        val store = FakeStore().apply { storageWorks = false }
        val vm = viewModel(store, FakeValidator(TokenValidationResult.Valid))

        vm.onInputChange(token)
        vm.onSave()

        assertEquals(TokenStatus.NotStored, vm.state.value.status)
        assertEquals("the good token must still be in the field", token, vm.state.value.input)
        assertTrue("so Save can simply be pressed again", vm.state.value.canSave)
        assertNull(store.saved)
    }

    @Test
    fun `a failed store does not claim the token was saved`() {
        val store = FakeStore().apply { storageWorks = false }
        val vm = viewModel(store, FakeValidator(TokenValidationResult.Valid))

        vm.onInputChange(token)
        vm.onSave()

        assertFalse(vm.state.value.isSaved)
        assertTrue(vm.state.value.showsField)
    }

    @Test
    fun `the state does not print the token`() {
        val vm = viewModel()
        vm.onInputChange(token)
        assertFalse(vm.state.value.toString().contains(token))
        assertNotNull(vm.state.value.toString())
    }
}
