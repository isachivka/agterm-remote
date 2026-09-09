package dev.isachivka.bewareofsugar.update.play

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.isachivka.bewareofsugar.update.installChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Play update check — REQ-0050.
 *
 * Checks on launch and whenever the owner asks, the same shape [dev.isachivka.bewareofsugar.update.UpdateViewModel]
 * has for the GitHub path. It carries none of that path's machinery: there is no token, because Play
 * already knows who the tester is; no downloader, because Play downloads; and no installer, because
 * Play installs. That absence is the point of the whole requirement — every one of those pieces
 * existed to work around not being on Play.
 *
 * There is also no once-a-day budget. `CheckSchedule` exists because the GitHub check spends a rate
 * limit on a private repository's API; asking the Play service on the device costs nothing worth
 * rationing, and a stale answer on an update screen is the failure it was written to avoid.
 */
class PlayUpdateViewModel(
    private val source: PlayUpdateSource,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _status = MutableStateFlow<PlayUpdateStatus>(PlayUpdateStatus.Idle)
    val status: StateFlow<PlayUpdateStatus> = _status.asStateFlow()

    private var running: Job? = null

    init {
        checkNow()
    }

    /** The owner pressed Check, or the app just started. Both are the same request. */
    fun checkNow() {
        if (running?.isActive == true) return
        running = scope.launch {
            _status.value = PlayUpdateStatus.Checking
            _status.value = source.check(nowEpochSeconds())
        }
    }

    /**
     * Hands the update to Play.
     *
     * Nothing is set here on success. Play takes the screen, and if it finishes the app is restarted
     * on the new version — so there is no "updating" state for this app to draw, and inventing one
     * would mean drawing a screen nobody can ever see. A refusal, though, is this app's to report:
     * [PlayUpdateStatus.Unknown] rather than silence, because a button that does nothing at all is
     * the thing the owner will retry forever.
     */
    fun startUpdate(activity: Activity) = startUpdate { source.startImmediate(activity) }

    /**
     * The half of [startUpdate] that has a decision in it.
     *
     * Split from the platform overload above because an `Activity` cannot be constructed off a
     * device — android.jar is stubs that throw — so a single method taking one would put the only
     * branch worth testing behind the only thing a unit test cannot make. What is left above is a
     * one-line delegation with nothing to get wrong.
     *
     * @param start launches Play's flow and reports whether Play accepted it.
     */
    internal fun startUpdate(start: () -> Boolean) {
        if (!start()) {
            _status.value = PlayUpdateStatus.Unknown(nowEpochSeconds())
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = context.applicationContext
                PlayUpdateViewModel(
                    source = PlayUpdates(application, application.installChannel()),
                )
            }
        }
    }
}
