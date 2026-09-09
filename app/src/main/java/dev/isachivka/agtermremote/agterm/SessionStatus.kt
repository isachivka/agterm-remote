package dev.isachivka.agtermremote.agterm

/**
 * What a session is doing, as agterm's own agent hooks report it.
 *
 * ### Four states, and the fourth is the one that is not said
 *
 * agterm's enum is `idle · active · completed · blocked`, and **`idle` never appears on the wire** —
 * its encoder omits the first case. So absence is a state rather than a gap, which is why [fromWire]
 * takes a nullable string and has somewhere sensible to put it.
 *
 * The values were established from agterm's own binary rather than from a reply: the enum sits in its
 * string table beside `statusPane`, and again in the shell-integration hook block that writes it. A
 * live sample of 32 sessions contained no `blocked` at all — **and a sample that lacks a value is not
 * evidence the value does not exist**, which is the mistake that broke the fit on 2026-07-31.
 *
 * That also settles what these MEAN. The agent's hooks set them, so [NeedsYou] is *this session is
 * waiting on the person*, observed on the laptop. It is not a diagnosis this app is making about
 * somebody's session, which it has no way to make — the same rule as [copyFor].
 */
enum class SessionStatus {
    /** The agent is working. */
    Running,

    /** It is waiting on the owner. The only state that raises a group's attention dot. */
    NeedsYou,

    /** It finished. */
    Done,

    /** Nothing to say — no status, or one this app does not recognise. */
    Idle,
}

/**
 * The wire value, closed at this boundary.
 *
 * **Anything unrecognised is [SessionStatus.Idle]**, and that is a decision rather than a fallback: an
 * unknown state is not one of the other three, and rendering it as one would put a confident wrong
 * glyph on a row. Idle draws a plain dot, which is the honest picture of "nothing to say".
 *
 * The bridge already normalises to the same closed set, and this is not redundant. That end
 * guarantees only a known value leaves the laptop; this end guarantees an unknown one cannot reach a
 * `when`. Two decoders is two chances to drift, so both are tested against the same strings.
 */
fun statusFromWire(value: String?): SessionStatus = when (value) {
    "active" -> SessionStatus.Running
    "blocked" -> SessionStatus.NeedsYou
    "completed" -> SessionStatus.Done
    else -> SessionStatus.Idle
}

/**
 * Whether a group has something inside it that wants the owner.
 *
 * Drawn on a **closed** group, where the rows that would say so themselves are not on screen. It is
 * deliberately only [SessionStatus.NeedsYou]: a group full of running sessions is not asking for
 * anything, and a dot that lit up for those would be a dot the owner learns to ignore.
 *
 * A closed group still counts its sessions and still reports this — collapsing hides rows, never
 * facts.
 */
fun wantsAttention(group: WorkspaceGroup): Boolean =
    group.sessions.any { it.status == SessionStatus.NeedsYou }

/**
 * Whether the dot is actually DRAWN on a group, which is a narrower question than [wantsAttention].
 *
 * **Only while the group is folded.** With it open, the rows say so themselves, and a dot that
 * duplicates what is already on screen is a dot that stops meaning anything.
 *
 * This is a pure function rather than a condition inside the header composable, and deliberately so:
 * it is the rule, and a rule that lives in a `when` inside a `@Composable` can only be checked by
 * running a UI test on a device. Here it is checked by the same suite as everything else.
 */
fun showsAttention(group: WorkspaceGroup, closed: Set<String>): Boolean =
    group.id in closed && wantsAttention(group)
