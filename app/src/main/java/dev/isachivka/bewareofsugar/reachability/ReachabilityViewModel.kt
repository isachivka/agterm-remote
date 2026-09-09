package dev.isachivka.bewareofsugar.reachability

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras

/**
 * Keeps the results alive across navigation, and deliberately does not run anything.
 *
 * REQ-0005 wrote down *"there is no ViewModel, because an Activity-scoped one would keep checking
 * after the owner walked away"*. What that rule was protecting was **nothing runs while the app is
 * not on screen** — the two were the same thing only because the check started on a screen. REQ-0006
 * needs the answer before the screen is opened, so the two separate:
 *
 * - **Where results live** — here, so moving between the launcher and the module neither loses them
 *   nor restarts them, and so they die with the process.
 * - **What drives the work** — the Activity's lifecycle, in `MainActivity`. Not [viewModelScope].
 *
 * **[viewModelScope] is the wrong scope and that is not a style preference.** A `ViewModel` outlives
 * `onStop`, so a check launched from `viewModelScope` would keep running with the app off screen,
 * which is precisely what REQ-0005 forbade. There is no `init` block here for the same reason. If
 * either ever appears, `LaunchCheckInstrumentedTest` goes red.
 */
class ReachabilityViewModel(
    val results: ReachabilityResults,
) : ViewModel() {

    companion object {
        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                ReachabilityViewModel(ReachabilityResults()) as T
        }
    }
}
