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
        /** The terminal. It is what the app is for, so it is what the app opens on. */
        val Initial = BackStack(listOf(Screen.Agterm))

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
