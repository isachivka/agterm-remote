package dev.isachivka.bewareofsugar.update.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Turning Play's `versionCode` back into a version name — REQ-0050.
 *
 * This is arithmetic that fails by producing a wrong-but-plausible string, which is the reason it is
 * a function rather than a format call, and the reason these cases are the same worked examples
 * `VersionCodeTest` pins in the other direction. The two cannot be made to share code — `buildSrc`
 * is build logic and is not on the app's classpath — so they are made to share numbers.
 */
class VersionCodeNameTest {

    @Test
    fun `the releases this app has actually cut`() {
        assertEquals("0.32.0", versionNameOfCode(3200))
        assertEquals("0.33.0", versionNameOfCode(3300))
        assertEquals("0.3.0", versionNameOfCode(300))
    }

    @Test
    fun `each component lands in its own column`() {
        assertEquals("1.2.3", versionNameOfCode(10203))
        assertEquals("0.0.1", versionNameOfCode(1))
        assertEquals("0.1.0", versionNameOfCode(100))
        assertEquals("1.0.0", versionNameOfCode(10000))
    }

    @Test
    fun `the highest components the scheme allows`() {
        assertEquals("0.99.99", versionNameOfCode(9999))
        assertEquals("12.99.99", versionNameOfCode(129999))
    }

    @Test
    fun `zero is a version name and not a refusal`() {
        // 0.0.0 was never released, but it is a code this scheme could produce, and refusing it
        // would mean the null branch covers two different things.
        assertEquals("0.0.0", versionNameOfCode(0))
    }

    @Test
    fun `a negative code has no name`() {
        assertNull(versionNameOfCode(-1))
    }

    @Test
    fun `every non-negative code has a name`() {
        // This test replaced two that asserted the opposite, and they failed - which is how the
        // unreachable guard in versionNameOfCode was found. 199 is 0.1.99 and 20000 is 2.0.0; the
        // components cannot breach their ceiling because the arithmetic imposes it on the way out.
        assertEquals("0.1.99", versionNameOfCode(199))
        assertEquals("2.0.0", versionNameOfCode(20_000))
        assertEquals("214748.36.47", versionNameOfCode(Int.MAX_VALUE))
    }

    @Test
    fun `it is the exact inverse of the scheme buildSrc uses`() {
        // major * 10000 + minor * 100 + patch, restated here because it cannot be imported.
        for (major in 0..3) {
            for (minor in listOf(0, 1, 9, 33, 99)) {
                for (patch in listOf(0, 1, 7, 99)) {
                    val code = major * 10_000 + minor * 100 + patch
                    assertEquals("$major.$minor.$patch", versionNameOfCode(code))
                }
            }
        }
    }
}
