package dev.isachivka.bewareofsugar.car

/**
 * The owner's words, waiting to be sent — the car's draft.
 *
 * ### It is not terminal content, and it is never drawn as such
 *
 * What the car shows as terminal is only ever what the laptop sent back. The draft lives here and is
 * drawn in its own band under the terminal — [TypingState] argues why an echo could not be made
 * correct, and the car inherits the rule unchanged.
 *
 * ### Two sources, one field
 *
 * The microphone and the car's keyboard both write [text]. [listening] is true while the recogniser is
 * still speaking for the owner, so the band can say so; a partial result is shown as it arrives and a
 * final one replaces it. Nothing here is kept anywhere but memory, and nothing here is kept after a
 * send — `TypingState.Sent` carries no payload for a reason that applies twice over in a car.
 */
data class CarDraft(val text: String = "", val listening: Boolean = false)

/**
 * What Enter does with the draft: the text to type first, or nothing, and whether it is a paste.
 *
 * [text] `null` is the key alone — Enter on an empty draft is just Enter, which is most presses.
 */
data class EnterPlan(val text: String?, val paste: Boolean)

/** The transitions, as functions of a value, so every one of them is a line in `CarDraftTest`. */
object CarDrafting {

    fun startListening(d: CarDraft): CarDraft = d.copy(listening = true)

    /** A partial result: shown at once, and still being listened to. */
    fun partial(d: CarDraft, heard: String): CarDraft = d.copy(text = heard, listening = true)

    /** The final result replaces whatever was there, trimmed; a recogniser pads with spaces. */
    fun heard(d: CarDraft, text: String): CarDraft = CarDraft(text = text.trim(), listening = false)

    /** A recogniser that gave up keeps what it had rather than blanking the band. */
    fun voiceFailed(d: CarDraft): CarDraft = d.copy(listening = false)

    fun typed(d: CarDraft, text: String): CarDraft = CarDraft(text = text)

    /**
     * **A draft with a line break is a PASTE, and everything else is typed** — the phone's rule from
     * REQ-0017, decided from the text itself and never from a guess about what runs at the far end.
     */
    fun enter(d: CarDraft): EnterPlan =
        if (d.text.isEmpty()) EnterPlan(text = null, paste = false)
        else EnterPlan(text = d.text, paste = d.text.contains('\n'))

    fun sent(d: CarDraft): CarDraft = CarDraft()

    /**
     * REQ-0017 Decision 3, the narrow exception: a send that did not happen leaves the text where it
     * was. Back into the band, never into a notice, never into a log.
     */
    fun refused(d: CarDraft, text: String): CarDraft = CarDraft(text = text)
}
