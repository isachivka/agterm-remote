package dev.isachivka.bewareofsugar.settings

import java.io.File

/**
 * Whether the owner wants the terminal read WITH its colours — through the zmx daemon behind each
 * pane rather than agterm's plain `session.text`.
 *
 * Off until turned on. A file rather than DataStore because one boolean does not need a schema, and a
 * corrupt or unreadable file is survivable as "plain", which is what the owner had before they found
 * the switch.
 *
 * In the `settings` package because it is a setting; the `agterm` package keeps its own files
 * (`CollapsedWorkspacesStore`, `DraftStore`) for the things it owns.
 *
 * The setting lives on the phone only. The bridge is asked on every poll and keeps nothing, so
 * flipping it takes effect on the next reply.
 */
class StyledScreenStore internal constructor(private val file: File) {

    constructor(directory: String) : this(File(directory, STYLED_FILE))

    fun read(): Boolean = try {
        file.exists() && String(file.readBytes()).trim() == ON
    } catch (unreadable: Exception) {
        false
    }

    fun write(on: Boolean) {
        try {
            file.writeBytes((if (on) ON else OFF).toByteArray())
        } catch (unwritable: Exception) {
            // Forgetting a preference beats crashing the settings screen.
        }
    }

    private companion object {
        const val STYLED_FILE = "styled-screen"
        const val ON = "on"
        const val OFF = "off"
    }
}
