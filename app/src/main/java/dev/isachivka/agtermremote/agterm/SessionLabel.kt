package dev.isachivka.agtermremote.agterm

/**
 * What the owner may type when they name a workspace or a session.
 *
 * ### Why the phone checks at all, when the bridge already does
 *
 * **The bridge's check is the one that matters and this one is not a substitute for it.** A phone is
 * not a trust boundary; the bridge validates because a caller that has got past pairing is still a
 * caller. This exists for a different reason: so the owner learns their name is too long *before* a
 * round trip to their laptop, with the dialog still open and their text still in it.
 *
 * The two must agree, or the phone accepts something the laptop refuses and the owner watches a
 * rename silently fail. `SessionLabelTest` pins them to the same boundaries — which is the honest
 * version of "keep them in sync", since one is Kotlin and one is Go and no type can join them.
 *
 * ### The name is never put in a message
 *
 * Same rule as everywhere else here: the copy names the SHAPE of the problem — too long, empty,
 * contains a control character — never the value. It is in front of the owner in the text field
 * already.
 */
enum class LabelProblem {
    /** Nothing, or only whitespace. A name that renders as nothing is not a name. */
    Empty,

    /** Longer than [MaxLabelRunes]. */
    TooLong,

    /**
     * Holds a character a terminal would act on rather than draw.
     *
     * Unreachable from an ordinary soft keyboard, and checked anyway: paste exists, and the bridge
     * refuses these, so a phone that let one through would produce a failure with no explanation.
     */
    ControlCharacter,
}

/**
 * The cap, in **code points**, matching `keys.maxLabelRunes` in the bridge.
 *
 * Code points rather than UTF-16 units, because [String.length] would give a name written in an
 * alphabet outside the BMP a smaller allowance than an English one — and the owner writes Russian
 * workspace names, so a length that quietly means something different per script is not academic.
 */
const val MaxLabelRunes = 64

/** The name as it will be sent: trimmed, exactly as the bridge trims it before storing. */
fun cleanLabel(raw: String): String = raw.trim()

/**
 * What is wrong with this name, or null when nothing is.
 *
 * Checked against the TRIMMED value, in the same order as the bridge, so the two cannot disagree
 * about a name that is wrong in more than one way.
 */
fun labelProblem(raw: String): LabelProblem? {
    val label = cleanLabel(raw)
    if (label.isEmpty()) return LabelProblem.Empty
    if (label.codePointCount(0, label.length) > MaxLabelRunes) return LabelProblem.TooLong
    var i = 0
    while (i < label.length) {
        val code = label.codePointAt(i)
        if (isControlCode(code)) return LabelProblem.ControlCharacter
        i += Character.charCount(code)
    }
    return null
}

/**
 * C0, DEL, and C1 — the same three ranges as `keys.isControl` in the bridge.
 *
 * C1 (0x80–0x9f) is included for the reason it is there: those are control codes that look like
 * ordinary high characters, so they are the ones an implementation forgets.
 */
private fun isControlCode(code: Int): Boolean =
    code < 0x20 || code == 0x7f || (code in 0x80..0x9f)
