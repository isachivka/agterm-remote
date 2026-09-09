package dev.isachivka.bewareofsugar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen shows the version it was actually built as.
 *
 * The label is asserted against [BuildConfig], not against a literal, because a literal here would
 * pass while the app displayed something else entirely — which is the one failure this requirement
 * exists to prevent.
 */
class VersionLabelTest {

    @Test
    fun `label shows the built versionName`() {
        assertTrue(
            "label was \"${versionLabel()}\", expected it to contain ${BuildConfig.VERSION_NAME}",
            versionLabel().contains(BuildConfig.VERSION_NAME),
        )
    }

    @Test
    fun `label shows the built versionCode`() {
        assertTrue(
            "label was \"${versionLabel()}\", expected it to contain ${BuildConfig.VERSION_CODE}",
            versionLabel().contains(BuildConfig.VERSION_CODE.toString()),
        )
    }

    /**
     * Checked on the real build output rather than on the pure function.
     *
     * `VersionCodeTest` in buildSrc proves the arithmetic. This proves the arithmetic was actually
     * *applied* to this .apk — that the Gradle wiring is live and the two values agree. Those are
     * different failures and only this one is visible from inside the app.
     */
    @Test
    fun `the built versionCode is derived from the built versionName`() {
        val (major, minor, patch) = BuildConfig.VERSION_NAME.split(".").map(String::toInt)
        assertEquals(major * 10000 + minor * 100 + patch, BuildConfig.VERSION_CODE)
    }
}
