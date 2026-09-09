package dev.isachivka.agtermremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **The version bump has three parts and all three fail silently.**
 *
 * release-please rewrites `appVersionName` in `app/build.gradle.kts`, and it does so only when three
 * separate things are true: the line carries a `// x-release-please-version` marker, the file is
 * listed under `extra-files` in `release-please-config.json`, and the version already there matches
 * the one `.release-please-manifest.json` believes is current.
 *
 * **Break any of them and nothing fails.** The release job is green, the tag is cut, the changelog
 * is written, and the `.aab` carries the version before it. Nobody finds out until a phone reports a
 * version that does not exist, or refuses an update because `versionCode` did not move - Android
 * will not install an `.apk` whose code is not greater than the installed one, so a missed bump ends
 * the update at the final tap with no explanation.
 *
 * This module arrived from a private repository carrying that repository's version, `0.34.0`, into a
 * tree whose manifest says `0.1.0`. Nothing anywhere would have said so.
 *
 * Read as FILES rather than through BuildConfig, because two of the three facts are not in the
 * built artefact at all. Both files are declared as task inputs in `app/build.gradle.kts`; without
 * that, editing one leaves this task UP-TO-DATE and the test reports success by not running.
 */
class ReleasePleaseTest {

    // The unit-test working directory is the module directory, so the repository root is one up.
    private val moduleDir = File(".").absoluteFile.normalize()
    private val root = moduleDir.parentFile

    private val buildFile = File(moduleDir, "build.gradle.kts")
    private val config = File(root, "release-please-config.json")
    private val manifest = File(root, ".release-please-manifest.json")

    @Test
    fun `the version line carries the marker release-please looks for`() {
        val line = buildFile.readLines().singleOrNull { it.startsWith("val appVersionName") }
            ?: error("no single `val appVersionName` line in ${buildFile.path}")

        assertTrue(
            "the version line must end with the release-please marker, and it reads: $line",
            line.trim().endsWith("// x-release-please-version"),
        )
    }

    @Test
    fun `the build file is listed among release-please's extra files`() {
        assertTrue(
            "release-please-config.json must list app/build.gradle.kts under extra-files, " +
                "or the marker above is read by nothing",
            config.readText().contains("\"app/build.gradle.kts\""),
        )
    }

    @Test
    fun `the version in the build file is the one release-please believes is current`() {
        val declared = Regex("""val appVersionName = "([^"]+)"""")
            .find(buildFile.readText())?.groupValues?.get(1)
            ?: error("no `val appVersionName = \"...\"` in ${buildFile.path}")
        val current = Regex(""""\.":\s*"([^"]+)"""")
            .find(manifest.readText())?.groupValues?.get(1)
            ?: error("no root entry in ${manifest.path}")

        // release-please replaces the OLD version by matching it, so a build file that disagrees
        // with the manifest is one release-please cannot rewrite - and it says nothing when it
        // cannot.
        assertEquals(
            "app/build.gradle.kts and .release-please-manifest.json name different versions",
            current,
            declared,
        )
    }
}
