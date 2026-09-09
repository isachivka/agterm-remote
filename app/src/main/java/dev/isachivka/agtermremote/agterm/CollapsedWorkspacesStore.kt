package dev.isachivka.agtermremote.agterm

import java.io.File

/**
 * The folded workspaces, as a file of ids — REQ-0031.
 *
 * The owner: *"постоянно сбрасывается на - все открыто"* — the collapsed session list kept reverting
 * to everything expanded, and he wanted it remembered. He was first told the phone was forbidden from
 * storing this and answered *"я не ставил таких требований, можешь хранить такие данные на телефоне"*.
 * He was right, and on 2026-09-06 he withdrew the whole family of rules that answer came from — see
 * REQ-0046. This file used to describe itself as "the only door in the package that writes to disk";
 * there is no such rule and no such test any more. [DraftStore] writes beside it.
 *
 * ### Only ids fit through
 *
 * Both directions pass every entry through [looksLikeAnId], which admits **only a UUID**. That is
 * still worth having on its own merits: a hand-edited or corrupt file cannot inject anything but ids,
 * and the file is self-evidently a list of workspaces and nothing else.
 *
 * ### Failure is silence, deliberately
 *
 * A missing file is the ordinary first run. A write that fails costs the owner a fold state, and
 * crashing a session list to report it would be a far worse trade than forgetting which groups were
 * shut. Both directions degrade to "everything expanded", which is exactly the behaviour this replaces.
 */
class CollapsedWorkspacesStore internal constructor(private val file: File) {

    /**
     * What the app uses. **The `File` stays inside this class on purpose.**
     *
     * `AgtermViewModel` constructed the path itself in the first draft, which would have made it a
     * second file in this package touching `java.io.File` — and therefore a second exemption in
     * `NothingPersistedTest`. Two doors is not the arrangement that was argued for. One class knows
     * where the file is, and everything else knows only that ids go in and ids come out.
     *
     * **A directory path rather than a `Context`, and that is not fussiness.** The guard forbids this
     * file from containing the vocabulary of content, and `Context` has the word inside it — so
     * accepting one here would have failed the check on a false positive, and the tempting repair
     * would have been to loosen the check. A `String` is all this needs, so the door gets narrower
     * instead of the guard getting blunter.
     */
    constructor(directory: String) : this(File(directory, COLLAPSED_FILE))

    /** The ids folded shut when this was last written, or none if there is nothing readable. */
    fun read(): Set<String> = try {
        if (!file.exists()) {
            emptySet()
        } else {
            idsOnly(String(file.readBytes()).split('\n').map { it.trim() }).toSet()
        }
    } catch (unreadable: Exception) {
        // Broad on purpose, and the same reasoning as the font chain's: a corrupt or unreadable file
        // is survivable as "nothing was folded", and is not worth a crash on the way into a screen.
        emptySet()
    }

    /**
     * Records the ids folded shut. **Anything that is not an id is dropped rather than written.**
     *
     * Silently, and that is the right severity: this is a defence against a future caller handing the
     * door something it must not carry, not a validation of user input. Nothing the owner can do
     * produces a non-id here.
     */
    fun write(ids: Set<String>) {
        try {
            file.writeBytes(idsOnly(ids).joinToString("\n").toByteArray())
        } catch (unwritable: Exception) {
            // See the class comment: forgetting a fold beats crashing a list.
        }
    }

    private companion object {
        /**
         * A workspace id and nothing else — the same UUID shape the bridge validates before it will
         * address a workspace at all.
         *
         * **This regex is the whole safety argument of this file.** Widening it is not a refactor; it
         * is the moment this door stops being able to carry only ids.
         */
        val ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        fun looksLikeAnId(candidate: String): Boolean = ID.matches(candidate)

        /**
         * **The single filter both directions go through, and the reason it is a named function.**
         *
         * It was written inline in each direction, and a control caught what that cost: deleting the
         * filter from the WRITE left an identical one in the READ, so a guard looking for the filter
         * anywhere in the file still found it and passed. The detector was matching the wrong copy.
         *
         * Now the write is one line, `writeBytes(idsOnly(...))`, and `NothingPersistedTest` asserts
         * that THAT line names this function. Removing it from the write is now visible on the line
         * the guard reads.
         */
        fun idsOnly(values: Iterable<String>): List<String> =
            values.filter { looksLikeAnId(it) }.sorted()

        /**
         * The file, under the app's own private storage.
         *
         * No extension: it is a list of ids, one per line, and calling it `.json` would invite
         * somebody to put something structured in it.
         */
        const val COLLAPSED_FILE = "collapsed-workspaces"
    }
}
