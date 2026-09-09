package dev.isachivka.bewareofsugar.car

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest is the contract with the Android Auto host, and nothing in the code can see it.
 *
 * So it is read as a file — the pattern BackupRulesTest set — and the manifest is declared a test
 * input in build.gradle.kts, so this cannot report success by not running. Each assertion is one
 * line the host reads: drop any of them and the app is simply absent from the car, with no error.
 */
class CarManifestTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `the car service is declared, exported, in the navigation category`() {
        assertTrue(manifest.contains("android:name=\".car.TerminalCarAppService\""))
        assertTrue(manifest.contains("androidx.car.app.CarAppService"))
        assertTrue(manifest.contains("androidx.car.app.category.NAVIGATION"))
    }

    @Test
    fun `the three car permissions and the microphone are declared`() {
        assertTrue(manifest.contains("androidx.car.app.NAVIGATION_TEMPLATES"))
        assertTrue(manifest.contains("androidx.car.app.MAP_TEMPLATES"))
        // Without this one the host refuses the surface with an exception that kills the app. Found on
        // the head unit, not in the documentation - REQ-0044 §3.
        assertTrue(manifest.contains("androidx.car.app.ACCESS_SURFACE"))
        assertTrue(manifest.contains("android.permission.RECORD_AUDIO"))
    }

    @Test
    fun `the host descriptor and the minimum car api level are declared`() {
        assertTrue(manifest.contains("com.google.android.gms.car.application"))
        assertTrue(manifest.contains("androidx.car.app.minCarApiLevel"))
    }

    /**
     * The spike allowed any host. A validator that admits every caller lets any app on the phone bind
     * the exported service, read the terminal and type into it. The manifest cannot say which validator
     * the code builds, but the spike's own name for the switch must not survive anywhere in it.
     */
    @Test
    fun `nothing in the manifest allows every host`() {
        assertFalse(manifest.contains("ALLOW_ALL_HOSTS"))
    }
}
