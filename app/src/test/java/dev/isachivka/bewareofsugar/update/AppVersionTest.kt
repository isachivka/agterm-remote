package dev.isachivka.bewareofsugar.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version comparison, which is where an updater quietly goes wrong: a release tag is `v0.3.0` and
 * `BuildConfig.VERSION_NAME` is `0.3.0`, and everything downstream depends on those meeting.
 */
class AppVersionTest {

    @Test
    fun `a release tag and a versionName parse to the same thing`() {
        assertEquals(AppVersion.parse("0.3.0"), AppVersion.parse("v0.3.0"))
    }

    @Test
    fun `components are read in order`() {
        val version = AppVersion.parse("v1.2.3")
        assertEquals(AppVersion(1, 2, 3), version)
    }

    @Test
    fun `comparison is by component, not by string`() {
        // The one a string comparison gets wrong: "0.10.0" sorts before "0.9.0" alphabetically.
        assertTrue(AppVersion.parse("v0.10.0")!! > AppVersion.parse("v0.9.0")!!)
        assertTrue(AppVersion.parse("v1.0.0")!! > AppVersion.parse("v0.99.99")!!)
        assertTrue(AppVersion.parse("v0.3.1")!! > AppVersion.parse("v0.3.0")!!)
    }

    @Test
    fun `the same version is not newer than itself`() {
        assertEquals(0, AppVersion.parse("v0.3.0")!!.compareTo(AppVersion.parse("0.3.0")!!))
    }

    @Test
    fun `a pre-release is not a version this app offers`() {
        // Update channels are out of scope for REQ-0003, and treating -rc1 as stable is how somebody
        // ends up on one without choosing to.
        assertNull(AppVersion.parse("v1.0.0-rc1"))
        assertNull(AppVersion.parse("v1.0.0-beta"))
        assertNull(AppVersion.parse("1.0.0+build7"))
    }

    @Test
    fun `a malformed tag is not a version`() {
        listOf("", "   ", "v", "1.2", "1.2.3.4", "latest", "v1.2.x", "release-1.2.3", "-1.2.3")
            .forEach { assertNull("\"$it\" must not parse", AppVersion.parse(it)) }
    }

    @Test
    fun `a component at the versionCode ceiling is refused`() {
        // buildSrc refuses to build these because the versionCode would collide with a later
        // release; an .apk with a colliding code is one Android will not install, so offering it
        // would fail at the final tap on the owner's phone instead of here.
        assertNull(AppVersion.parse("v1.100.0"))
        assertNull(AppVersion.parse("v1.0.100"))
        // The last values that are still fine.
        assertEquals(AppVersion(1, 99, 99), AppVersion.parse("v1.99.99"))
    }

    @Test
    fun `surrounding whitespace does not stop a tag parsing`() {
        assertEquals(AppVersion(0, 3, 0), AppVersion.parse("  v0.3.0\n"))
    }

    @Test
    fun `a large major version still parses`() {
        assertEquals(AppVersion(2026, 1, 2), AppVersion.parse("v2026.1.2"))
    }
}
