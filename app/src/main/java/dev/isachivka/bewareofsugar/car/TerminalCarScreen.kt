package dev.isachivka.bewareofsugar.car

import android.Manifest
import androidx.activity.OnBackPressedCallback
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.isachivka.bewareofsugar.R
import dev.isachivka.bewareofsugar.agterm.AgtermUiState
import dev.isachivka.bewareofsugar.agterm.BridgeSession
import dev.isachivka.bewareofsugar.agterm.CLAUDE_COMMAND
import dev.isachivka.bewareofsugar.agterm.ENTER_KEY
import dev.isachivka.bewareofsugar.agterm.FitState
import dev.isachivka.bewareofsugar.agterm.MutationNote
import dev.isachivka.bewareofsugar.agterm.Pane
import dev.isachivka.bewareofsugar.agterm.TypingState
import dev.isachivka.bewareofsugar.agterm.copyFor
import dev.isachivka.bewareofsugar.wire.WireFailure
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The session on the car's surface, with the keys above it.
 *
 * ### The template never changes; the surface does
 *
 * One navigation template is sent when the screen opens, and it is the same template for as long as
 * the screen lives: one way-back action the host may fade, and pan. Everything else - the text, the
 * draft, the status, and the buttons themselves - is painted onto the surface by [CarTerminalPainter]
 * and costs nothing against the host's template quota. A tap on a painted button comes back through
 * the surface and is resolved by [CarKeyRow].
 *
 * ### Enter, with and without a draft
 *
 * Enter on an empty draft is the key alone, which is most presses. Enter with a draft is the text and
 * then the key, in one coroutine so they cannot cross on the wire - the holder's `runMacro` is exactly
 * that pair, and it is used rather than copied. A draft with a line break is a paste and goes alone;
 * the bridge wraps it in the paste markers and the owner presses Enter again if they mean it. The
 * car's keyboard is one line and the recogniser never produces a break, so that path is a rule kept
 * for honesty rather than a thing that happens.
 *
 * ### A refusal puts the words back
 *
 * When the laptop refuses what was typed, the holder comes back to `Composing` with the text and a
 * notice. The car reads that and returns the text to the band - REQ-0017's narrow exception - and says
 * so on the status line. It is the only moment the draft is filled from the holder's state.
 */
class TerminalCarScreen(
    carContext: CarContext,
    private val holder: CarSessionsHolder,
    private val session: BridgeSession,
) : Screen(carContext) {

    private val painter = CarTerminalPainter(carContext, onButton = ::press, onGeometryChanged = ::refit)
    private val voice = CarVoice(carContext)
    private var draft = CarDraft()
    private var voiceNote: String? = null

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(painter)
        lifecycleScope.launch {
            val s = holder.sessions
            combine(s.state, s.typing, s.paneShown, s.fit, s.mutation) { state, typing, pane, fit, note ->
                restoreRefused(typing)
                frameFrom(state, typing, pane, fit, note)
            }.collect { painter.frame = it }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                voice.destroy()
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
            }
        })
        carContext.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
    }

    override fun onGetTemplate(): Template = CarTemplates.terminal(
        carContext,
        CarTerminalActions(
            onSessions = ::leave,
            onPanMode = { inPan -> if (!inPan) painter.followBottom() },
        ),
    )

    /** A painted button, resolved by the painter from a tap on the surface. */
    private fun press(button: CarButton) {
        when (button) {
            is CarButton.Key -> press(button.key)
            // The phone's macro: the owner's own alias and Enter, nothing else known about it.
            CarButton.Claude -> holder.sessions.runMacro(CLAUDE_COMMAND)
            CarButton.Pane -> holder.sessions.togglePane()
            CarButton.Fit -> fit()
            CarButton.Mic -> listen()
            CarButton.Keyboard -> screenManager.push(DraftCarScreen(carContext, draft.text, ::typed))
            CarButton.Sessions -> leave()
        }
    }

    private fun press(key: CarKey) {
        // A key press is a new act; whatever the microphone last said about itself is over.
        voiceNote = null
        if (key.wire != ENTER_KEY) {
            holder.sessions.type(key = key.wire)
            return
        }
        val plan = CarDrafting.enter(draft)
        when {
            plan.text == null -> holder.sessions.type(key = ENTER_KEY)
            plan.paste -> holder.sessions.type(text = plan.text)
            else -> holder.sessions.runMacro(plan.text)
        }
        setDraft(CarDrafting.sent(draft))
    }

    private fun typed(text: String) = setDraft(CarDrafting.typed(draft, text))

    /**
     * The car's fit: the same two numbers the phone sends, measured from the surface. On means give
     * the window back; off means ask. Never a calibration from here - that takes a long press the
     * surface does not report, and a dozen resizes of a window nobody is watching are the thing
     * REQ-0041 refused to start by surprise.
     */
    private fun fit() {
        val g = painter.geometry() ?: return
        if (holder.sessions.fit.value.enabled == true) {
            holder.sessions.restoreWindow()
        } else {
            holder.sessions.fitToPhone(g.boxWidthDp, g.characterWidthMilliDp, recalibrate = false)
        }
    }

    /**
     * The rectangle changed - the map came alongside, or went away - so a fit the laptop already has
     * for this geometry is applied, and nothing is measured. The phone's path on a session switch.
     */
    private fun refit() {
        val g = painter.geometry() ?: return
        holder.sessions.refit(g.boxWidthDp, g.characterWidthMilliDp)
    }

    private fun listen() {
        if (!voice.isAvailable()) {
            note(R.string.car_voice_unavailable)
            return
        }
        if (voice.hasPermission()) {
            startListening()
            return
        }
        carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
            if (granted.contains(Manifest.permission.RECORD_AUDIO)) startListening() else note(R.string.car_voice_denied)
        }
    }

    private fun startListening() {
        voiceNote = null
        setDraft(CarDrafting.startListening(draft))
        voice.listen(
            onPartial = { heard -> setDraft(CarDrafting.partial(draft, heard)) },
            onResult = { text -> setDraft(CarDrafting.heard(draft, text)) },
            onError = {
                note(R.string.car_voice_failed)
                setDraft(CarDrafting.voiceFailed(draft))
            },
        )
    }

    private fun leave() {
        holder.leave()
        screenManager.pop()
    }

    private fun note(text: Int) {
        voiceNote = carContext.getString(text)
        repaint()
    }

    private fun setDraft(next: CarDraft) {
        draft = next
        repaint()
    }

    private fun repaint() {
        val s = holder.sessions
        painter.frame = frameFrom(s.state.value, s.typing.value, s.paneShown.value, s.fit.value, s.mutation.value)
    }

    private fun restoreRefused(typing: TypingState) {
        // The refused text comes back into the session's draft (REQ-0046 moved it out of Composing),
        // and the car's own draft picks it up from there when it has nothing of its own.
        val restored = holder.sessions.draft.value
        if (typing is TypingState.Composing && typing.notice != null && restored.isNotEmpty() && draft.text.isEmpty()) {
            draft = CarDrafting.refused(draft, restored)
        }
    }

    private fun frameFrom(state: AgtermUiState, typing: TypingState, pane: Pane, fit: FitState, note: MutationNote): CarFrame {
        val label = CarTemplates.sessionLabel(carContext, session)
        val fitWord = when {
            note == MutationNote.FitRefused -> carContext.getString(R.string.car_fit_refused)
            note == MutationNote.NeedsFit -> carContext.getString(R.string.car_fit_needs)
            fit.enabled == true -> carContext.getString(R.string.car_fit_columns, fit.columns)
            else -> null
        }
        val status = voiceNote ?: when (typing) {
            TypingState.Sent -> carContext.getString(R.string.car_sending)
            TypingState.NoChange -> carContext.getString(R.string.car_sent_no_change)
            TypingState.Failed -> carContext.getString(R.string.car_not_sent)
            is TypingState.Composing -> if (typing.notice != null) carContext.getString(R.string.car_refused) else label
            TypingState.Closed -> label
        } + (fitWord?.let { " \u00b7 $it" } ?: "")
        return when (state) {
            is AgtermUiState.Sessions -> CarFrame(
                lines = if (state.screen.isEmpty()) emptyList() else state.screen.split('\n'),
                styled = state.screenStyled,
                draft = draft,
                status = status,
                stale = state.stale,
                paneShown = pane,
                splitPane = state.watching?.splitPane ?: session.splitPane,
                fitEnabled = fit.enabled,
            )
            is AgtermUiState.Failed -> CarFrame(draft = draft, status = carContext.getString(copyFor(state.failure)), stale = true)
            AgtermUiState.NotPaired -> CarFrame(draft = draft, status = carContext.getString(copyFor(WireFailure.NotPaired)), stale = true)
            is AgtermUiState.Refused -> CarFrame(draft = draft, status = carContext.getString(R.string.car_refused_listing, state.reason), stale = true)
            AgtermUiState.Loading, AgtermUiState.Disconnected -> CarFrame(draft = draft, status = status)
        }
    }
}
