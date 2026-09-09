package dev.isachivka.bewareofsugar.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.isachivka.bewareofsugar.R
import dev.isachivka.bewareofsugar.agterm.AgtermUiState
import dev.isachivka.bewareofsugar.agterm.BridgeSession
import dev.isachivka.bewareofsugar.agterm.copyFor
import dev.isachivka.bewareofsugar.wire.WireFailure
import kotlinx.coroutines.launch

/**
 * The first screen in the car: the sessions, or the one sentence about why there are none.
 *
 * ### Refreshed on resume, invalidated on change, and only on change
 *
 * The list is fetched when this screen comes to the top - the first time, and again when the owner
 * comes back from a session. The host is asked to redraw only when what it would draw has changed:
 * a template sent for no reason is at best a flicker and at worst a step against the task quota,
 * which is five and then a closed app. So each state is reduced to a small key and compared.
 *
 * ### One sentence for a failure, and it is the phone's sentence
 *
 * Not paired, unreachable, refused: the copy is `copyFor`, the same words the phone shows, because a
 * cause this app cannot observe must not be asserted in a car any more than on a phone. The car
 * cannot pair; that is said by showing the not-paired sentence and nothing else.
 */
class SessionsCarScreen(
    carContext: CarContext,
    private val holder: CarSessionsHolder,
) : Screen(carContext) {

    private var shown: Any? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = holder.start()
        })
        lifecycleScope.launch {
            holder.sessions.state.collect { state ->
                val key = keyOf(state)
                if (key != shown) {
                    shown = key
                    invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val state = holder.sessions.state.value
        shown = keyOf(state)
        return when (state) {
            AgtermUiState.Loading, AgtermUiState.Disconnected -> CarTemplates.loading(carContext)
            AgtermUiState.NotPaired -> CarTemplates.message(carContext, carContext.getString(copyFor(WireFailure.NotPaired)))
            is AgtermUiState.Failed -> CarTemplates.message(carContext, carContext.getString(copyFor(state.failure)))
            is AgtermUiState.Refused ->
                CarTemplates.message(carContext, carContext.getString(R.string.car_refused_listing, state.reason))
            is AgtermUiState.Sessions -> {
                val sections = carSections(state, rowLimit(), carContext.getString(R.string.agterm_workspace_unnamed))
                if (sections.isEmpty()) {
                    CarTemplates.message(carContext, carContext.getString(R.string.car_no_sessions))
                } else {
                    CarTemplates.sessions(carContext, sections, ::open)
                }
            }
        }
    }

    private fun open(session: BridgeSession) {
        holder.open(session)
        screenManager.push(TerminalCarScreen(carContext, holder, session))
    }

    /**
     * The host's own limit, asked at runtime. Six is the library's documented floor for a list, and it
     * is what a host too old to be asked gets.
     */
    private fun rowLimit(): Int = try {
        carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    } catch (e: RuntimeException) {
        FALLBACK_ROW_LIMIT
    }

    private fun keyOf(state: AgtermUiState): Any = when (state) {
        is AgtermUiState.Sessions ->
            state.sessions.map { listOf(it.id, it.workspaceId, it.name, it.title, it.status) } to state.workspaces
        else -> state
    }

    private companion object {
        const val FALLBACK_ROW_LIMIT = 6
    }
}
