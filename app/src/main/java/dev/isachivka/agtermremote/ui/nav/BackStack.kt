package dev.isachivka.agtermremote.ui.nav

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * Where the owner is, and how they got there.
 *
 * What this replaces is one `Screen` value plus a `BackHandler` per screen that hardcoded its
 * parent, which is only ever right while every screen has exactly one way in. Back is a fact about
 * the journey rather than about a screen, so it is stored as one.
 *
 * A plain immutable value with no Android in it, so the interesting behaviour below is a unit test
 * rather than something that needs an emulator to answer.
 *
 * [entries] is never mutated in place, which is what makes [Immutable] a true statement about it
 * rather than a hint.
 */
@Immutable
data class BackStack(val entries: List<Screen>) {

    init {
        require(entries.isNotEmpty()) { "a back stack always has somewhere to be" }
    }

    val current: Screen get() = entries.last()

    /** False at the root, so system back exits the app instead of being quietly swallowed. */
    val canPop: Boolean get() = entries.size > 1

    /**
     * Go to [screen].
     *
     * Two things this does that appending would not, and both are bugs otherwise:
     *
     * 1. **Going where you already are does nothing.** Otherwise a double tap costs two presses of
     *    back to undo, which reads as the app ignoring the first one.
     * 2. **Going somewhere already behind you returns to it** rather than stacking a second copy.
     *    Without it a cycle through the destinations would put back on a screen the owner just left,
     *    sending them forwards to go back. It also keeps the stack bounded by the number of
     *    destinations, which is what makes "back always terminates" true by construction rather
     *    than by hoping.
     */
    fun push(screen: Screen): BackStack {
        val existing = entries.indexOf(screen)
        return when {
            existing == entries.lastIndex -> this
            existing >= 0 -> BackStack(entries.subList(0, existing + 1))
            else -> BackStack(entries + screen)
        }
    }

    /** At the root this returns itself; the caller decides what back means there. */
    fun pop(): BackStack = if (canPop) BackStack(entries.dropLast(1)) else this

    companion object {

        /**
         * The stack the app opens with, given where [startDestination] says it should open.
         *
         * There is no unconditional `Initial` any more, and its absence is the point: a constant root
         * is what made the app open on a terminal that cannot connect for anyone who has not paired
         * yet. Building the root from a [Start] means the question "where does this open" has exactly
         * one answer and it is a function of a fact about the device.
         *
         * **[Start.Pairing] puts the terminal underneath rather than opening on a single entry**, and
         * that entry is load-bearing. `pop()` at the root returns the stack unchanged - correct, so
         * that system back exits the app rather than being swallowed - which on a one-entry pairing
         * stack would leave an owner who does not want to pair this minute with no way off the screen
         * but the task switcher. With the terminal behind it, back does the ordinary thing.
         *
         * Pairing resolves to [Screen.Settings] because that is where pairing is: the settings page is
         * the pairing page with one switch on it. It gets a destination of its own when the scanner
         * screen is built, and this is the single line that has to change then.
         */
        fun initialFor(start: Start): BackStack = when (start) {
            Start.Terminal -> BackStack(listOf(Screen.Agterm))
            Start.Pairing -> BackStack(listOf(Screen.Agterm, Screen.Settings))
        }

        /**
         * Survives rotation, and survives the process being killed in the background.
         *
         * Routes and not indices: an index would silently point at a different screen the moment
         * the destination list changes, and the failure would be a restored app on the wrong screen
         * rather than anything that looks like a bug in this file.
         *
         * An unrecognised route is dropped rather than crashing, because it means the saved state
         * came from a different version of the app — which is what happens when Play updates the
         * app while it is in the background.
         */
        // Named StateSaver rather than Saver so it does not shadow the type it is an instance of.
        val StateSaver: Saver<BackStack, Any> = listSaver<BackStack, String>(
            save = { stack -> stack.entries.map { it.route } },
            restore = { routes ->
                BackStack(routes.mapNotNull(Screen::fromRoute).ifEmpty { listOf(Screen.Agterm) })
            },
        )
    }
}
