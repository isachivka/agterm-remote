package dev.isachivka.bewareofsugar.update

import org.junit.Assert.assertTrue
import dev.isachivka.bewareofsugar.pairing.PairedLaptop
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * A regression guard on the backup rules, because "the token is not backed up" is a claim made in
 * the commit history and in REQ-0003, and the only thing enforcing it is two lines of XML that look
 * like boilerplate and would survive a tidy-up unnoticed.
 *
 * This reads the resource files directly rather than through the Android framework: the assertion
 * is about what ships in the .apk, and it needs no device to make.
 */
class BackupRulesTest {

    private fun resource(name: String): File = listOf(
        // Unit tests run with the module directory as the working directory; the second path is
        // there so running from the repository root works too.
        File("src/main/res/xml/$name"),
        File("app/src/main/res/xml/$name"),
    ).firstOrNull { it.exists() } ?: error("cannot find $name from ${File(".").absolutePath}")

    private fun excludedPaths(file: File, section: String): List<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val roots = if (section.isEmpty()) {
            listOf(document.documentElement)
        } else {
            document.getElementsByTagName(section).let { nodes ->
                (0 until nodes.length).map { nodes.item(it) as Element }
            }
        }
        return roots.flatMap { root ->
            val excludes = root.getElementsByTagName("exclude")
            (0 until excludes.length)
                .map { excludes.item(it) as Element }
                .filter { it.getAttribute("domain") == "file" }
                .map { it.getAttribute("path") }
        }
    }

    /** The token's file is `<filesDir>/datastore/github_token.preferences_pb`. */
    private fun covers(paths: List<String>): Boolean {
        val tokenFile = "datastore/${DataStoreTokenStore.DATASTORE_NAME}.preferences_pb"
        return paths.any { it == tokenFile || it == "datastore" || it == "datastore/" }
    }

    @Test
    fun `the token is excluded from cloud backup`() {
        val paths = excludedPaths(resource("data_extraction_rules.xml"), "cloud-backup")
        assertTrue("cloud-backup excludes: $paths", covers(paths))
    }

    @Test
    fun `the token is excluded from device transfer`() {
        val paths = excludedPaths(resource("data_extraction_rules.xml"), "device-transfer")
        assertTrue("device-transfer excludes: $paths", covers(paths))
    }

    /**
     * The exclusion has to be the whole directory, not the token's file by name.
     *
     * REQ-0004 put module visibility in a second DataStore file beside the token's, and its privacy
     * was entirely a side effect of this rule being directory-wide. **That feature was deleted on
     * 2026-07-31 and the argument is stronger without it**, because the file it protected is still
     * there, orphaned and unread — see the test below.
     *
     * Narrowing this to `datastore/github_token.preferences_pb` would look like a tightening — more
     * specific, same protection for the thing that matters — and would quietly start backing up
     * anything else in the directory, including files nobody remembers writing.
     *
     * The directory form is also what protects the token during a write: DataStore writes through a
     * temporary file beside the real one, and a backup running mid-write would otherwise catch it.
     */
    @Test
    fun `the exclusion covers the whole datastore directory, not one file in it`() {
        listOf("cloud-backup", "device-transfer").forEach { section ->
            val paths = excludedPaths(resource("data_extraction_rules.xml"), section)

            assertTrue(
                "$section must exclude the datastore directory itself, but excludes: $paths",
                paths.any { it == "datastore" || it == "datastore/" },
            )
        }
    }

    /**
     * The same claim for the orphaned store by name, so the reason it is private is written down.
     *
     * **`module_visibility.preferences_pb` is still on the owner's phone and nothing reads it.** The
     * feature that wrote it was deleted on 2026-07-31; no migration was written, deliberately, because
     * shipping a code path whose only job is to delete a file we will never read again is a storage
     * write on a device we get one install at a time, to save a few hundred bytes.
     *
     * So this test keeps its subject. A file that exists and is excluded needs the exclusion exactly
     * as much as one that is being written — arguably more, since nothing else in the app now mentions
     * it and a future reader would have no other reason to know it is there.
     */
    @Test
    fun `the orphaned module visibility file is excluded too, because it lives in that directory`() {
        val moduleFile = "datastore/module_visibility.preferences_pb"

        listOf("cloud-backup", "device-transfer").forEach { section ->
            val paths = excludedPaths(resource("data_extraction_rules.xml"), section)

            assertTrue(
                "$section does not cover $moduleFile; excludes: $paths",
                paths.any { it == "datastore" || it == "datastore/" || it == moduleFile },
            )
        }
    }

    @Test
    fun `the legacy backup rules agree with them`() {
        // Dead config below API 31 and minSdk is 34, but a reader comparing the two files should
        // not find them contradicting each other.
        val paths = excludedPaths(resource("backup_rules.xml"), "")
        assertTrue("full-backup-content excludes: $paths", covers(paths))
    }

    /**
     * REQ-0009's pairing, guarded the same way the token is.
     *
     * The exclusion exists for a reason the token's does not have: the phone's private key is in the
     * hardware keystore and **cannot** be backed up at all. So a restored profile would arrive on a
     * new handset with no key to go with it - an app that shows a fingerprint, looks paired, and
     * cannot complete a handshake. A broken state wearing a working one's clothes is worse than an
     * unpaired one, which at least tells the owner what to do.
     *
     * Asserted against `PairedLaptop.DIRECTORY` rather than against the string, so renaming the
     * directory without updating the XML fails here instead of silently starting to back the pairing
     * up.
     */
    @Test
    fun `the pairing is excluded from both cloud backup and device transfer`() {
        listOf("cloud-backup", "device-transfer").forEach { section ->
            val paths = excludedPaths(resource("data_extraction_rules.xml"), section)
            assertTrue(
                "$section must exclude ${PairedLaptop.DIRECTORY}, but excludes: $paths",
                paths.any { it == PairedLaptop.DIRECTORY || it == "${PairedLaptop.DIRECTORY}/" },
            )
        }
    }

    /**
     * `backup_rules.xml` is only consulted up to API 30 and minSdk is 34, so it decides nothing on any
     * device this app installs on. It is kept in step anyway: a reader comparing the two files should
     * not find one of them silently disagreeing with the other.
     */
    @Test
    fun `the legacy rules agree about the pairing`() {
        val paths = excludedPaths(resource("backup_rules.xml"), "full-backup-content")
        assertTrue(
            "backup_rules.xml must exclude ${PairedLaptop.DIRECTORY}, but excludes: $paths",
            paths.any { it == PairedLaptop.DIRECTORY || it == "${PairedLaptop.DIRECTORY}/" },
        )
    }
}
