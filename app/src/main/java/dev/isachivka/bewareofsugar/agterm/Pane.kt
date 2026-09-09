package dev.isachivka.bewareofsugar.agterm

/**
 * Which half of a split session the phone is addressing — REQ-0032.
 *
 * ### Why this exists as a type
 *
 * The bridge holds the same closed set (`agterm.Pane`), refuses anything outside it by name, and will
 * not send a request without one. This is the phone's end of that: a value that cannot be a typo, and
 * cannot be absent.
 *
 * ### The defect underneath it
 *
 * agterm resolves an absent pane **differently for the two commands** — measured over its control
 * socket on 2026-08-25: a read gets the on-screen pane, a keystroke gets primary. So while the phone
 * named no pane, a split session showed one half and typed into the other, silently, into the terminal
 * the owner was not looking at.
 *
 * **The fix was never "send a pane". It is that one value answers both questions**, which is why
 * [BridgeConnection.screen] and [BridgeConnection.type] both take this as a REQUIRED argument with no
 * default: the compiler will not let a caller leave one of them to guess.
 *
 * ### `left` and `right` are agterm's own words
 *
 * Not a vocabulary this app invented and has to translate: `--pane` accepts them, the tree's surfaces
 * carry `kind: left|right`, and the bridge passes them through untouched. [wire] exists so the string
 * on the socket is written once rather than at each call.
 */
enum class Pane(val wire: String) {

    /**
     * agterm's `primary`. **Every session has this one**, which is why it is what an unnamed pane means
     * on both sides, and what the phone falls back to when a split disappears.
     */
    Left("left"),

    /** agterm's `split`. Exists only while a second pane does — see [BridgeSession.splitPane]. */
    Right("right"),
    ;

    /** The other one. A toggle has exactly two states and this is the whole of its logic. */
    fun other(): Pane = if (this == Left) Right else Left
}
