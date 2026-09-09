package dev.isachivka.bewareofsugar.car

import dev.isachivka.bewareofsugar.agterm.AgtermUiState
import dev.isachivka.bewareofsugar.agterm.BridgeSession
import dev.isachivka.bewareofsugar.agterm.groupByWorkspace

/** One workspace's heading and the sessions under it, as the car's list shows them. */
data class CarSection(val title: String, val sessions: List<BridgeSession>)

/**
 * The phone's grouping, cut to the host's row limit.
 *
 * **The cap is applied here and not left to the host, because the host does not truncate — it
 * refuses.** A list over `ConstraintManager.getContentLimit` is an error shown to the owner and a closed
 * app. The limit is only known at runtime, so it arrives as a parameter and the cut is a thing a test
 * can watch: from the tail, in listing order, and a section it empties is dropped rather than shown as
 * a heading over nothing.
 *
 * Grouping is by workspace **id**, through the same function the phone uses; two workspaces sharing
 * a name stay two sections. [unnamed] is the car's label for a workspace the bridge sent without one.
 */
fun carSections(state: AgtermUiState.Sessions, limit: Int, unnamed: String): List<CarSection> {
    var left = limit.coerceAtLeast(0)
    val out = ArrayList<CarSection>()
    for (group in groupByWorkspace(state.sessions, state.workspaces)) {
        if (left == 0) break
        val kept = group.sessions.take(left)
        if (kept.isEmpty()) continue
        left -= kept.size
        out.add(CarSection(title = group.name.ifBlank { unnamed }, sessions = kept))
    }
    return out
}
