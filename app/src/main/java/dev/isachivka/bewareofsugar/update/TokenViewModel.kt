package dev.isachivka.bewareofsugar.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds the token while it is being typed, and nothing else holds it.
 *
 * This is the reason the screen has a ViewModel at all: `rememberSaveable` would put the in-progress
 * token into `savedInstanceState`, which the system serialises to disk and includes in bug reports.
 * Living here means it survives a rotation and dies with the process, which is exactly right.
 *
 * @param scope a seam for tests, which supply their own instead of the Main-dispatcher-backed
 * [viewModelScope] that does not exist on the JVM.
 */
class TokenViewModel(
    private val store: TokenStore,
    private val validator: TokenValidator,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(TokenScreenState())
    val state: StateFlow<TokenScreenState> = _state.asStateFlow()

    private var validation: Job? = null

    init {
        this.scope.launch { restore() }
    }

    private suspend fun restore() {
        val status = when (val stored = store.read()) {
            is StoredToken.Present -> TokenStatus.Saved(stored.validatedAtEpochSeconds)
            is StoredToken.Unreadable -> TokenStatus.Unreadable(stored.permanent, stored.reason, stored.sinceEpochSeconds)
            StoredToken.None -> TokenStatus.NoToken
        }
        _state.update { it.copy(status = status) }
    }

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value) }
    }

    /** Shows the field again over an already-saved token. The stored one stays until a new one validates. */
    fun onReplace() {
        _state.update { it.copy(input = "", replacing = true) }
    }

    fun onSave() {
        val token = GitHubToken.fromInput(_state.value.input) ?: return
        if (_state.value.status == TokenStatus.Validating) return

        validation?.cancel()
        validation = scope.launch {
            _state.update { it.copy(status = TokenStatus.Validating) }

            when (val result = validator.validate(token)) {
                TokenValidationResult.Valid -> {
                    val now = nowEpochSeconds()
                    if (store.save(token, now)) {
                        // The field is cleared the moment the token is stored: it is never displayed
                        // back, not even masked, and not even its last four characters.
                        _state.value = TokenScreenState(input = "", status = TokenStatus.Saved(now))
                    } else {
                        // A validated token must never be dropped in silence. What they typed stays
                        // in the field so Save can be pressed again without pasting it a second time.
                        _state.update { it.copy(status = TokenStatus.NotStored) }
                    }
                }

                else -> {
                    // Nothing is stored, and nothing already stored is touched - being offline or
                    // catching a 503 says nothing about a token that has been working.
                    _state.update { it.copy(status = result.toStatus(nowEpochSeconds())) }
                }
            }
        }
    }

    fun onRemove() {
        validation?.cancel()
        scope.launch {
            store.clear()
            _state.value = TokenScreenState()
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TokenViewModel(
                    store = tokenStore(context.applicationContext),
                    validator = GitHubApi(),
                )
            }
        }
    }
}
