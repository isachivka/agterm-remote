package dev.isachivka.bewareofsugar.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the download line shows.
 *
 * This exists because `docs/qa/update-on-device.md` quotes these strings to the owner as what they
 * should expect to see. A document that predicts the screen is only useful while it is right, so the
 * prediction is pinned here rather than left to drift.
 */
class FormatBytesTest {

    /** The real v0.3.0 asset: `beware-of-sugar-0.3.0.apk`, 25,372,783 bytes. */
    @Test
    fun `the released apk reads as about twenty four megabytes`() {
        assertEquals("24.2 MB", formatBytes(25_372_783L))
    }

    @Test
    fun `megabytes carry one decimal, which is enough to see it moving`() {
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("4.2 MB", formatBytes((4.2 * 1024 * 1024).toLong()))
    }

    @Test
    fun `smaller sizes do not pretend to be megabytes`() {
        assertEquals("512 KB", formatBytes(512L * 1024))
        assertEquals("999 B", formatBytes(999L))
        assertEquals("0 B", formatBytes(0L))
    }

    @Test
    fun `the formatting does not depend on the phone's locale`() {
        // A decimal comma would still be legible, but the QA script quotes a decimal point, and a
        // document that predicts the screen has to be right about it.
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("24.2 MB", formatBytes(25_372_783L))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
