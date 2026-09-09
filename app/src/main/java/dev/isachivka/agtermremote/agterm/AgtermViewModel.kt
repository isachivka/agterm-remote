package dev.isachivka.agtermremote.agterm

import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.isachivka.agtermremote.pairing.PairedLaptop
import dev.isachivka.agtermremote.pairing.PhoneIdentity
import dev.isachivka.agtermremote.settings.StyledScreenStore

/**
 * Holds the connection and the reading position across configuration change.
 *
 * ### What this is for
 *
 * Rotating the phone destroys and recreates the activity. Before this existed, everything the Terminal
 * screen held lived in `remember`, so a rotation **re-fetched the session list, lost the owner's place
 * in the output, and could raise an unlock prompt** — measured on the device, not inferred: the window
 * manager logged `finishDrawing of relaunch` and focus went straight to `BiometricPrompt`.
 *
 * That is the prerequisite of the landscape layout option. Landscape is worth more than any font-size
 * change — **40.4% of the owner's real output needs panning at 39 columns, 22.4% at 89** — but a
 * gesture that costs a round trip and an unlock every time is a fix that annoys in a new way. This
 * makes rotating free, and it is worth having whichever layout option is eventually chosen.
 *
 * ### Why a ViewModel and not `rememberSaveable`
 *
 * A `ViewModel` survives configuration change **in memory**, which is all a scroll offset or a
 * connection needs. What should outlive the process is written to disk by the thing that owns it:
 * the collapsed set through [CollapsedWorkspacesStore] (REQ-0031) and the drafts through
 * [DraftStore] (REQ-0046).
 *
 * Earlier versions of this comment argued from a rule that nothing about the terminal may be
 * persisted. The owner never set that rule and withdrew it on 2026-09-06 — see REQ-0046. What is and
 * is not written is decided per feature, on what the owner wants back after the process dies.
 *
 * The scroll offsets stay in memory for their own reason: nobody has asked for a reading position to
 * survive being killed in a pocket, and an offset into a buffer that has since changed would restore
 * them somewhere plausible and wrong.
 *
 * Two things are READ from disk here: [laptopAddress], out of the pairing this phone already stores,
 * and the collapsed set. Both here rather than during composition, so neither is a file read per
 * frame.
 *
 * ### No Activity is held, and now nothing wants one
 *
 * This class used to carry an `authenticator` slot, refilled every composition, because the unlock
 * prompt needed an `Activity` and holding one here would leak it across every rotation. The key is no
 * longer gated on a recent unlock (2026-07-29), so there is no prompt, no slot, and no Activity to be
 * careful about.
 */
class AgtermViewModel(context: Context) : ViewModel() {

    /**
     * The application context, which is safe to hold for the ViewModel's life.
     *
     * An activity or a composition-local context would be a leak; the connection only needs the
     * paired profile and the keystore, neither of which is activity-scoped.
     */
    private val appContext = context.applicationContext

    val sessions = AgtermSessions(
        connect = {
            val profile = PairedLaptop(appContext).read()
            profile?.let { BridgeConnection.open(it, PhoneIdentity.keyManager()) }
        },
        keyState = { PhoneIdentity.signingState() },
        scope = viewModelScope,
        // Read from disk on every poll — one small file at 2 Hz — so the switch in Settings takes
        // effect on the next reply without this ViewModel and that screen sharing any state.
        styled = { StyledScreenStore(appContext.filesDir.path).read() },
        // REQ-0046: drafts survive the process, one file per session under filesDir/drafts.
        drafts = DraftStore(appContext.filesDir.path),
    )

    /**
     * The terminal's scroll position, surviving rotation.
     *
     * Both axes, because the horizontal one is the whole point: after panning right to read a wide
     * line, a rotation used to drop the owner back to column zero — on the very gesture that gives
     * them more columns to read.
     */
    val terminalVertical = ScrollState(0)

    /**
     * Where the session LIST was, remembered as an anchor rather than as an index.
     *
     * Hoisted here for the same reason the terminal's scroll is: this survives the screen being
     * recomposed, a session being opened and closed, and a rotation. **One answer to "where does
     * scroll position live" rather than two that diverge.**
     *
     * An anchor and not a `LazyListState`, because restoring a raw index into a list that has changed
     * puts the owner somewhere plausible and wrong - see ListPosition, where that argument lives.
     */
    var sessionListAnchor: ListPosition.Anchor? = null

    /**
     * Which workspaces the owner has folded shut, by id.
     *
     * Hoisted for the same reason as the anchor: opening a session and coming back finds the same
     * groups open, and so does a rotation.
     *
     * **Compose state rather than a plain `var`**, unlike [sessionListAnchor] beside it, and the
     * difference is not stylistic: the anchor is written by the screen and read once inside an
     * effect, while this is read during composition and has to recompose the list when it changes. A
     * plain field here would fold a group in memory and leave it drawn open.
     *
     * ### It is persisted — REQ-0031
     *
     * This said *"never persisted"* once, citing a rule the owner then rejected: *"я не ставил таких
     * требований, можешь хранить такие данные на телефоне"*. It is written through
     * [CollapsedWorkspacesStore] and read back on construction.
     *
     * ### Why it needed persisting rather than re-scoping
     *
     * Diagnosed before it was built, because two faults look identical from a phone. This ViewModel is
     * **Activity-scoped** — `MainActivity` switches screens with a `when`, not a `NavHost` — so it
     * outlives leaving the terminal and coming back; `release()` drops the connection and touches
     * nothing here; and `CollapsedWorkspaces` has no path that prunes the set on a poll. Navigation and
     * rotation are therefore ruled out **by construction**, and what remains is the Activity being
     * destroyed for real: the owner leaving the app, or Android reclaiming it from their pocket.
     *
     * ### A custom setter, so one assignment cannot land in memory and not on disk
     *
     * `by mutableStateOf` gives no hook to write through, and a separate `save()` the caller must
     * remember is the shape where a new call site forgets. One property, both effects.
     */
    var closedWorkspaces: Set<String>
        get() = closedState.value
        set(value) {
            closedState.value = value
            // Synchronous, and small enough to be honest about: a handful of 36-byte ids written when
            // a finger folds a group. Moving it off the main thread would buy microseconds and cost
            // an ordering question between two taps.
            collapsed.write(value)
        }

    private val collapsed = CollapsedWorkspacesStore(appContext.filesDir.path)

    /**
     * Seeded from disk at construction, which is what makes the list come back folded.
     *
     * Reading here rather than in a composable for the same reason [laptopAddress] does: it touches
     * disk, and a value fetched during composition is a file read on every frame.
     */
    private val closedState = mutableStateOf(collapsed.read())

    /**
     * The address this phone dials, for the connection row.
     *
     * **Read once, and it is not the laptop's name** — the pairing payload has no name in it. Read
     * here rather than in the composable because it touches disk, and a value fetched during
     * composition is a file read on every frame.
     */
    val laptopAddress: String = PairedLaptop(appContext).read()?.host.orEmpty()

    val terminalHorizontal = ScrollState(0)

    /**
     * Releases the connection when the screen is genuinely finished with.
     *
     * Called by the composable rather than only from [onCleared], because leaving the Terminal screen
     * inside a surviving activity should still drop the connection — an open mTLS session to the
     * owner's laptop is not a thing to keep alive on the chance they come back.
     */
    fun release() {
        sessions.release()
    }

    override fun onCleared() {
        sessions.release()
    }
}
