package dev.isachivka.agtermremote.agterm

/**
 * What the phone can honestly say about the connection, as one state.
 *
 * ### The surface is real; half of what the mock puts on it is not
 *
 * design/v1 floats notes over the top of the terminal, and draws two: *"Reconnected to
 * MacBook-Pro-Igor"* and *"Waiting on your answer — three install checks and a yes/no on the agterm
 * request"*.
 *
 * The first is real. [Reconnect] already knows a transient failure is being waited out and knows when
 * an attempt succeeded after one, and today that is invisible to the owner: the screen freezes, and
 * then either it comes back or a full-screen failure arrives with no account of the gap.
 *
 * **The second is a fabrication.** It is the agent's state, and nothing in this app can observe
 * whether some Claude in some session is waiting on anybody. `SessionsPresentation` has held that rule
 * since REQ-0005 — *the copy must not assert a cause the app cannot observe* — and a new surface does
 * not get a new rule. So this type is connection state and nothing else, and there is no way to
 * express a note about something the phone did not see.
 *
 * ### One state, not a queue
 *
 * The mock draws a column of cards because a mock can. These two are mutually exclusive — a connection
 * is being waited out or it is not — so a list would be a mechanism with one possible occupant, plus
 * room for the second occupant somebody would eventually invent. Room to hold a fabricated note is how
 * a fabricated note gets written.
 *
 * ### Neither note names the laptop
 *
 * The mock says `MacBook-Pro-Igor`. The phone holds no name for it — see the `laptop` parameter on
 * `AgtermScreen`, which carries an address because an address is what there is.
 */
sealed interface LinkNote {

    /** Nothing to say. The ordinary state, and the one the owner should be in almost always. */
    data object None : LinkNote

    /**
     * A transient failure was caught and the phone is waiting it out.
     *
     * Observed: the connection was refused, reset, or reached a router with nothing behind it, and
     * [Reconnect.isTransient] said waiting is likely to help. It does NOT say why, because from here
     * a restarting bridge, a sleeping laptop and a lost network are the same silence.
     */
    data object Reconnecting : LinkNote

    /**
     * An attempt succeeded after at least one transient failure.
     *
     * Two observations, not one: something failed, and then something worked. Shown only when the
     * first actually happened — a clean first connection says nothing, because there was nothing to
     * come back from.
     */
    data object Reconnected : LinkNote
}
