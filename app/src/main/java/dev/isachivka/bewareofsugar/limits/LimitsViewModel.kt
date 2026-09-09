package dev.isachivka.bewareofsugar.limits

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras

/**
 * Keeps the limits alive across navigation and runs nothing — the `ReachabilityViewModel` shape.
 *
 * No `viewModelScope`, no `init`: what drives the request is the Activity's lifecycle and the owner's
 * thumb, and both live in `MainActivity`. A ViewModel outlives `onStop`, so anything launched from
 * here would keep asking the laptop with the app off screen.
 */
class LimitsViewModel(val results: LimitsResults) : ViewModel() {

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                // The application context: a ViewModel outlives the Activity that built it.
                val app = context.applicationContext
                return LimitsViewModel(LimitsResults(LimitsResults.overBridge(LimitsResults.connectPaired(app)))) as T
            }
        }
    }
}
