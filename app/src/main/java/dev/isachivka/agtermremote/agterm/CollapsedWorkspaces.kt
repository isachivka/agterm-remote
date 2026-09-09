package dev.isachivka.agtermremote.agterm

/**
 * Which workspaces the owner has folded shut.
 *
 * Asked for on 2026-07-30: the sections of the session list fold shut, the way they do in agterm
 * itself.
 *
 * ### CLOSED, never open, and that is the load-bearing half
 *
 * The set names what is shut. An id nobody has shut is open — so a workspace that appears on the
 * laptop while the owner had everything collapsed **arrives visible**, rather than hidden behind a
 * state that was never a decision about it. Holding the open ones instead would mean every new
 * workspace is born invisible and stays that way until somebody notices it is missing, which is the
 * failure `groupByWorkspace` already refuses in its own form: nothing is ever dropped.
 *
 * ### By workspace ID
 *
 * Never by name — two workspaces can share one — and never by index, because the list is rebuilt from
 * every listing and an index means a different group by the next poll. Same reasoning as
 * [groupByWorkspace] and [ListPosition], and the third place in this feature where identity beats
 * position.
 *
 * ### Persisted, by id
 *
 * **This said "Not persisted", and the owner overruled it:** he had set no such requirement, and
 * this data may live on the phone. The set kept reverting to everything-expanded whenever Android
 * reclaimed the app, and no rule ever forbade storing it — the rule in question is about session
 * names and screen content, and an id is neither.
 *
 * It still lives in the ViewModel while the app runs; what changed is that the ViewModel now seeds it
 * from disk and writes it back. See [CollapsedWorkspacesStore], which is the only door in this package
 * that writes anything, and which admits nothing but a UUID.
 */
object CollapsedWorkspaces {

    /** Folds one group shut, or opens it again. */
    fun toggle(closed: Set<String>, id: String): Set<String> =
        if (id in closed) closed - id else closed + id

    /**
     * Whether everything CURRENTLY LISTED is shut.
     *
     * Computed against the ids on screen rather than against the whole set, so the header control
     * means "everything you can see" rather than "everything this set happens to remember" — the set
     * can still hold a workspace that closed on the laptop days ago.
     *
     * An empty list is not all-closed. There is nothing to open, and an icon offering to expand
     * nothing is a control that does nothing.
     */
    fun allClosed(closed: Set<String>, listed: List<String>): Boolean =
        listed.isNotEmpty() && listed.all { it in closed }

    /**
     * What the header control does, given what is on screen.
     *
     * **One function, so the icon and the action cannot disagree.** They are two readings of the same
     * question — *is everything already shut?* — and asking it twice in two places is how a button
     * ends up showing "expand" while doing "collapse" for one frame after a listing changes.
     *
     * Collapsing names exactly the listed ids, so ids for workspaces that no longer exist are dropped
     * rather than accumulating for the life of the process.
     */
    fun press(closed: Set<String>, listed: List<String>): Set<String> =
        if (allClosed(closed, listed)) emptySet() else listed.toSet()
}
