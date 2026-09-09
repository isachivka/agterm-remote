package dev.isachivka.bewareofsugar.pairing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The leftover photograph, and the three ways this could go wrong on somebody's phone.
 *
 * It runs on every start, on every device, so the bar is not "it deletes the file" — it is "it can
 * never be the reason the app does not open". Absent, present and unreadable are all ordinary.
 */
class LeftoverScansTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `it removes the photograph and the directory that held it`() {
        val incoming = File(temp.root, "pairing/incoming").apply { mkdirs() }
        File(incoming, "scan.jpg").writeText("a photograph of a pairing code")

        LeftoverScans.remove(temp.root)

        assertFalse("the photograph survived", File(incoming, "scan.jpg").exists())
        assertFalse("the directory survived", incoming.exists())
    }

    /** The common case on every install after this one, and it must be silent. */
    @Test
    fun `an absent directory is not an error`() {
        LeftoverScans.remove(temp.root)

        assertFalse(File(temp.root, "pairing/incoming").exists())
    }

    /** Called on every start, so calling it twice — or a hundred times — has to be the same as once. */
    @Test
    fun `it is idempotent`() {
        File(temp.root, "pairing/incoming").apply { mkdirs() }
        File(temp.root, "pairing/incoming/scan.jpg").writeText("x")

        repeat(3) { LeftoverScans.remove(temp.root) }

        assertFalse(File(temp.root, "pairing/incoming").exists())
    }

    /**
     * **The one that decides whether this can brick a launch.** A directory the process cannot read
     * makes `deleteRecursively` fail, and that failure must go nowhere: the app is starting, and a
     * photograph nobody can delete is not a reason to stop it.
     */
    @Test
    fun `an unreadable directory is swallowed rather than thrown`() {
        val incoming = File(temp.root, "pairing/incoming").apply { mkdirs() }
        File(incoming, "scan.jpg").writeText("x")
        incoming.setReadable(false, false)
        incoming.setExecutable(false, false)

        try {
            LeftoverScans.remove(temp.root)
        } finally {
            // So the rule can clean up regardless of what the assertion did.
            incoming.setReadable(true, true)
            incoming.setExecutable(true, true)
        }
    }

    /** The directory beside it carries THIS phone's certificate for sharing. It is not ours to remove. */
    @Test
    fun `the outgoing directory is left alone`() {
        val outgoing = File(temp.root, "pairing/outgoing").apply { mkdirs() }
        val certificate = File(outgoing, "phone-cert.pem").apply { writeText("-----BEGIN CERTIFICATE-----") }
        File(temp.root, "pairing/incoming").mkdirs()

        LeftoverScans.remove(temp.root)

        assertTrue("this phone's shareable certificate was deleted", certificate.exists())
        assertTrue(outgoing.exists())
    }
}
