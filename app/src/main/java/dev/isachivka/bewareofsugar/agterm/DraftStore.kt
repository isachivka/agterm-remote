package dev.isachivka.bewareofsugar.agterm

import java.io.File

/**
 * The owner's unsent drafts, one file per session, in the app's private storage — REQ-0046.
 *
 * *"потерять промт введённый сюда очень страшно"*: a prompt typed on a phone is minutes of work, and
 * the process being killed in a pocket used to cost it. So a draft is written shortly after every
 * edit and read back when the session is opened again, until the owner sends it or erases it.
 *
 * ### What it is keyed by, and why the key is checked
 *
 * The file name is the session's UUID and nothing else, so the directory cannot be made to hold a
 * path: an id that is not shaped like a UUID is refused rather than sanitised, the same rule the
 * bridge applies to a file's basename. Nothing here knows what a session is called.
 *
 * ### Failure is silence, deliberately
 *
 * A missing file is a session with no draft. A write that fails costs one draft the next time the
 * process dies, and crashing the terminal to report it would be the worse trade. Writes go through a
 * temporary file and a rename, so a process killed mid-write leaves the previous draft rather than
 * half of the new one.
 */
class DraftStore internal constructor(private val directory: File) {

    constructor(filesDir: String) : this(File(filesDir, DIRECTORY))

    fun read(sessionId: String): String {
        val file = fileFor(sessionId) ?: return ""
        return try {
            if (file.exists()) String(file.readBytes(), Charsets.UTF_8) else ""
        } catch (unreadable: Exception) {
            ""
        }
    }

    /** An empty draft removes the file: nothing to restore is nothing on disk. */
    fun write(sessionId: String, draft: String) {
        val file = fileFor(sessionId) ?: return
        try {
            if (draft.isEmpty()) {
                file.delete()
                return
            }
            directory.mkdirs()
            val staging = File(directory, file.name + ".tmp")
            staging.writeBytes(draft.toByteArray(Charsets.UTF_8))
            if (!staging.renameTo(file)) {
                file.writeBytes(draft.toByteArray(Charsets.UTF_8))
                staging.delete()
            }
        } catch (unwritable: Exception) {
            // See the class comment: losing one draft beats crashing the terminal.
        }
    }

    private fun fileFor(sessionId: String): File? =
        if (ID.matches(sessionId)) File(directory, sessionId.lowercase()) else null

    private companion object {
        val ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        const val DIRECTORY = "drafts"
    }
}
