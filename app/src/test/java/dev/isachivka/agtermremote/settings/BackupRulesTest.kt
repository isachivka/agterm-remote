package dev.isachivka.agtermremote.settings

import dev.isachivka.agtermremote.pairing.PairedLaptop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * **What this app must never hand to a cloud backup or a device transfer.**
 *
 * ### Why this file was written twice
 *
 * `app/build.gradle.kts`, `PairedLaptop.DIRECTORY` and `data_extraction_rules.xml` all name
 * `BackupRulesTest` as the thing that fails when an exclusion is removed. It was not in the tree: it
 * went with the updater whose token it was first written for, and three comments went on promising a
 * guard that no longer existed. A test nothing can find is worth exactly as much as a test that does
 * not run, which is the failure this repository has been bitten by six times.
 *
 * ### The three paths, and what restoring each one costs
 *
 *  * `datastore` — the update token. It is encrypted under a Keystore key that never leaves the
 *    device, so a restored copy could not be read anyway; excluding it is the second reason, not the
 *    first.
 *  * `pairing` — the laptop's certificate and address. The phone's own private key is in the
 *    hardware keystore and **cannot** be backed up, so a restored profile is an app that looks
 *    paired, shows a fingerprint and cannot complete a handshake. A broken state wearing a working
 *    one's clothes is worse than an unpaired one, which at least says what to do.
 *  * `asked-for-camera` — one empty file recording that this app has shown the camera dialog *on this
 *    device*. Restored, it makes `CameraAccess` read `Blocked` on a handset that was never asked:
 *    the owner is told they refused a camera, given a button to a toggle that is already on, and
 *    shown the real permission dialog over the top of both.
 *
 * Reads the shipped resources directly, like `WindowBackgroundTest`: the claim is about what is in
 * the `.apk`, and making it needs no device. `src/main/res` is declared as a unit-test input in
 * `app/build.gradle.kts` for exactly that reason.
 */
class BackupRulesTest {

    /** The unit-test working directory is the module directory; the second path allows the root. */
    private fun resource(name: String): File = listOf(
        File("src/main/res/xml/$name"),
        File("app/src/main/res/xml/$name"),
    ).firstOrNull { it.exists() } ?: error("cannot find $name from ${File(".").absolutePath}")

    private val required = listOf("datastore", PairedLaptop.DIRECTORY, AskedForCamera.FILE)

    /** Every `path` attribute under the given element, so a rule in the wrong section is not counted. */
    private fun excluded(file: File, section: String?): List<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val scope = section?.let {
            val nodes = document.getElementsByTagName(it)
            assertTrue("no <$it> in ${file.name}", nodes.length == 1)
            nodes.item(0)
        } ?: document.documentElement
        val children = scope.childNodes
        return (0 until children.length)
            .map { children.item(it) }
            .filter { it.nodeName == "exclude" }
            .mapNotNull { it.attributes.getNamedItem("path")?.nodeValue }
    }

    /**
     * **The file that actually applies.** `android:dataExtractionRules` is used from API 31 and
     * minSdk here is 34, so this is the one the platform reads on every device this app can install
     * on — and both of its sections have to say the same thing. Backing something up to the cloud and
     * handing it to a new handset are two different acts with one set of consequences.
     */
    @Test
    fun `nothing private is backed up or transferred`() {
        val file = resource("data_extraction_rules.xml")

        listOf("cloud-backup", "device-transfer").forEach { section ->
            assertEquals(
                "$section in data_extraction_rules.xml no longer excludes what it must; a restored " +
                    "profile arrives with a pairing it has no key for",
                required,
                excluded(file, section),
            )
        }
    }

    /**
     * And the legacy file is kept in step, though nothing this app can install on reads it.
     * `android:fullBackupContent` is consulted up to API 30 only. It is here so that a reader
     * comparing the two does not find one of them silently disagreeing with the other.
     */
    @Test
    fun `the legacy backup rules say the same thing`() {
        assertEquals(required, excluded(resource("backup_rules.xml"), section = null))
    }
}
