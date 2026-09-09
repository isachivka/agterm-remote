package dev.isachivka.agtermremote.agterm

/**
 * Where the session list was, so returning to it puts them back rather than at the top.
 *
 * *"если я открываю какую-то сессию а потом возвращаюсь к списку сессий то scroll был там где я его
 * оставил"*.
 *
 * ### Why an ANCHOR and not an index
 *
 * A `LazyListState` remembers a first-visible index and an offset, and restoring those into a list
 * that has changed underneath puts the owner somewhere **plausible and wrong** — which is worse than
 * the top, because the top is obviously the top and a wrong position looks like a right one. The list
 * genuinely does change: it is regrouped from every listing, sessions come and go on the laptop while
 * the owner is away, and workspaces appear with them.
 *
 * So what is remembered is **the key of the row that was at the top of the viewport**, plus how far it
 * was scrolled past. On return the key is looked up in the list as it is NOW:
 *
 *  - **found** — they are put back on the same row, wherever it has moved to. A session appearing or
 *    vanishing above them changes the index and changes nothing about what they see.
 *  - **gone** — the top. Deliberately not "the nearest surviving neighbour": that is a guess dressed
 *    as a restore, and the row they were reading no longer exists to be restored to. The top is
 *    honest and recognisable.
 *
 * ### Keys are what LazyColumn holds, not what the sessions are
 *
 * The list is grouped, so it holds workspace headings as well as sessions. The anchor is a key from
 * that same sequence — `AgtermScreen` builds both from [listKeys] — so an index derived here is an
 * index into the real list rather than into a session count that would be short by one per workspace.
 *
 * ### And collapsing must not fight any of it
 *
 * Groups fold shut now, which changes the rows on screen without anything having changed on the
 * laptop. Two things follow, and both are here rather than in the screen:
 *
 *  - **[listKeys] takes the closed set, and is called twice with different arguments.** The rows on
 *    screen are `listKeys(groups, closed)`; the LISTING is `listKeys(groups, emptySet())`, which by
 *    construction does not move when the owner collapses something. That is what the restore effect
 *    is keyed on, so a collapse cannot re-run it and yank the list under their thumb. There is
 *    deliberately no second function and no default argument: two constructions of "the keys, in
 *    order" agree until the day one of them changes, and a default is a way to call the wrong one by
 *    saying nothing.
 *  - **[restore] takes the groups and the closed set**, not a list of keys, so there is no way to
 *    restore a position without accounting for what is folded away.
 */
object ListPosition {

    /** What was at the top of the viewport, and how far past it the list was scrolled. */
    data class Anchor(val key: String, val offset: Int)

    /** Where to put the list back: an index into the same sequence [listKeys] produces. */
    data class Target(val index: Int, val offset: Int)

    /**
     * The keys LazyColumn holds, in order, for a listing with [closed] folded shut.
     *
     * **One place, called by the screen to build the list, by [restore] to find a row in it, and with
     * an empty set to name the listing itself.** A workspace's heading is always present — folding a
     * group hides its rows, never the group.
     */
    fun listKeys(groups: List<WorkspaceGroup>, closed: Set<String>): List<String> = buildList {
        for (group in groups) {
            add(headingKey(group.id))
            if (group.id in closed) continue
            for (session in group.sessions) add(session.id)
        }
    }

    /** The key of a workspace heading row. Prefixed so it can never collide with a session id. */
    fun headingKey(workspaceId: String): String = "workspace-$workspaceId"

    /**
     * Where to scroll to, or null for the top.
     *
     * Null covers three cases that are all the same to the owner: nothing was remembered, the row they
     * were on is gone, and the list is empty. **A row that is merely folded away is not one of them** —
     * see [fold].
     */
    fun restore(anchor: Anchor?, groups: List<WorkspaceGroup>, closed: Set<String>): Target? {
        val visible = fold(anchor, groups, closed) ?: return null
        val index = listKeys(groups, closed).indexOf(visible.key)
        if (index < 0) return null
        return Target(index = index, offset = visible.offset)
    }

    /**
     * An anchor pointing at a row that is folded away, moved to the group that now holds it.
     *
     * **The heading is where the row went.** Neither of the two obvious answers is right: restoring to
     * the top is a jump the owner did not ask for, and the nearest surviving neighbour is a guess
     * dressed as a restore — the objection this file already makes about restoring by index. A group's
     * heading is not a guess about where the row might be; it is the row that now contains it.
     *
     * The offset is dropped. It was measured against a session row and a heading is a different row of
     * a different height, so carrying it would scroll partway through the heading — a number that is
     * arithmetically faithful and visually wrong.
     *
     * Anything else passes through untouched, including an anchor on a heading, which is visible
     * whether its group is open or shut.
     */
    /**
     * The anchor, re-pointed for a row the owner has just deleted.
     *
     * ### Why a deleted row needs its own answer
     *
     * [restore] returns null — the top — when the remembered key is not in the list. That is the right
     * answer for a row that vanished on the laptop while the owner was away: it is gone, they were not
     * watching, and the top is honest.
     *
     * **A row they deleted themselves is a different situation.** They are looking at it, the group
     * around it is still there, and jumping to the top of the list is a lurch they did not ask for on
     * the one gesture where they are most sure of what they meant. [fold] already answers the shape of
     * this question for a folded-away row — *the heading is where the row went* — and the same answer
     * is right here.
     *
     * ### The two cases differ, and the difference is not an oversight
     *
     *  - **A deleted session** → the heading of the group that held it. The group survives, so there is
     *    a real row to name. The offset is dropped for [fold]'s reason: it was measured against a
     *    session row and a heading is a different height, so carrying it scrolls partway through the
     *    heading.
     *  - **A deleted workspace** → null, the top. Its heading went with it and so did every row under
     *    it, so there is nothing left to fold to. The nearest surviving neighbour is exactly the guess
     *    dressed as a restore that this file refuses everywhere else.
     *
     * Applied where the delete is dispatched, against the grouping as it was **before** the refresh —
     * afterwards the deleted row is gone from the listing and nothing can say which group held it.
     */
    fun afterDelete(anchor: Anchor?, deleted: RenameTarget, groups: List<WorkspaceGroup>): Anchor? {
        if (anchor == null) return null
        return when (deleted) {
            is RenameTarget.Session -> {
                if (anchor.key != deleted.id) return anchor
                val group = groups.firstOrNull { g -> g.sessions.any { it.id == deleted.id } }
                    ?: return null
                Anchor(headingKey(group.id), offset = 0)
            }
            is RenameTarget.Workspace -> {
                val group = groups.firstOrNull { it.id == deleted.id } ?: return anchor
                val inside = anchor.key == headingKey(group.id) ||
                    group.sessions.any { it.id == anchor.key }
                if (inside) null else anchor
            }
        }
    }

    fun fold(anchor: Anchor?, groups: List<WorkspaceGroup>, closed: Set<String>): Anchor? {
        if (anchor == null) return null
        for (group in groups) {
            if (group.id !in closed) continue
            if (group.sessions.any { it.id == anchor.key }) {
                return Anchor(headingKey(group.id), offset = 0)
            }
        }
        return anchor
    }
}
