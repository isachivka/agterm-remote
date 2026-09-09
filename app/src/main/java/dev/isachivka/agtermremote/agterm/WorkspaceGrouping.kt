package dev.isachivka.agtermremote.agterm

/**
 * One workspace's heading and the sessions under it, in the order agterm gave them.
 *
 * [name] is for reading and may be empty; [id] is what this group IS. They are deliberately not the
 * same field — see [groupByWorkspace].
 */
data class WorkspaceGroup(
    val id: String,
    val name: String,
    val sessions: List<BridgeSession>,
)

/**
 * Turns the listing into the hierarchy agterm already holds.
 *
 * ### What the owner asked for
 *
 * *"у листа есть иерархия и я ее хочу видеть"* — agterm's tree is one level, workspaces holding
 * sessions, and the phone was rendering it flat. The bridge had been walking that tree all along and
 * throwing the structure away.
 *
 * **Seeing it is the whole request.** No collapsing, no reordering, no drag and drop: those are
 * separate decisions and they are the owner's to make, not ours to bundle in.
 *
 * ### Grouped by IDENTITY, never by name and never by adjacency
 *
 * Grouping by name merges two workspaces that happen to share one, and the owner then sees a session
 * under a workspace it is not in — a wrong answer presented confidently.
 *
 * Grouping by adjacency — starting a new group whenever the workspace changes between neighbours — is
 * subtler and worse. It is correct exactly while the list arrives grouped, which is a property nobody
 * is holding and nobody would notice breaking. So the id decides membership, and a workspace whose
 * sessions arrive split across the list is still one group.
 *
 * ### Order is agterm's, at both levels
 *
 * Workspaces appear in the order their first session appears; sessions keep their order within a
 * workspace. Nothing here sorts anything into what we might think is nicer — the structure on the
 * screen is the structure on the laptop.
 *
 * ### Nothing is ever dropped
 *
 * A session whose workspace has no name still appears, under its own identity, with a label the app
 * supplies. **A hierarchy that silently swallows a session is worse than a flat list**, so this
 * function's output holds exactly the sessions it was given — asserted by its tests rather than
 * intended.
 *
 * ### Not remembered
 *
 * This is a pure function of one listing. The grouping is a report of what agterm holds on THIS reply,
 * recomputed when the next one arrives — the same rule as every other piece of state here. If the tree
 * changes shape, the next listing says so and the screen follows.
 */
fun groupByWorkspace(
    sessions: List<BridgeSession>,
    /**
     * agterm's own workspace list, when the bridge published one.
     *
     * **This is what makes an EMPTY workspace visible**, and that is the whole reason it exists. A
     * workspace with no sessions produces no heading when the grouping is derived from the sessions
     * alone, so the owner would create one and be shown nothing.
     *
     * It also fixes the order. Deriving it put workspaces in the order their first session happened to
     * appear in, which is right only while that order and agterm's agree — nobody was holding that.
     *
     * Null means a bridge too old to say, and the derivation below is the fallback. **Not defaulted to
     * an empty list**: empty would mean a laptop with no workspaces, which agterm does not permit, and
     * treating an old bridge as one would wipe the list. See [SessionListing.workspaces].
     */
    workspaces: List<BridgeWorkspace>? = null,
): List<WorkspaceGroup> {
    val order = LinkedHashMap<String, MutableList<BridgeSession>>()
    val names = HashMap<String, String>()

    // Seeded first, so agterm's order wins and a workspace holding nothing still gets a heading. A
    // session whose workspace is somehow absent from this list still lands below, under its own
    // identity - nothing is ever dropped, which was true before this and stays true.
    workspaces?.forEach { space ->
        order.getOrPut(space.id) { mutableListOf() }
        if (names[space.id].isNullOrEmpty()) names[space.id] = space.name
    }

    for (session in sessions) {
        order.getOrPut(session.workspaceId) { mutableListOf() }.add(session)
        // The first non-empty name wins, so a group whose sessions disagree still gets the name that
        // was actually sent rather than an empty heading. They should not disagree; if they ever do,
        // showing something readable beats showing nothing.
        if (names[session.workspaceId].isNullOrEmpty()) {
            names[session.workspaceId] = session.workspace
        }
    }

    return order.map { (id, grouped) ->
        WorkspaceGroup(id = id, name = names[id].orEmpty(), sessions = grouped)
    }
}
