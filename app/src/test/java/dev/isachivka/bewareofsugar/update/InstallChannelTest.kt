package dev.isachivka.bewareofsugar.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which route an install came by — REQ-0050.
 *
 * One string decides it, and the consequence of deciding it wrongly is not an error message: it is
 * an update screen that offers the owner a button which turns Android Auto off.
 */
class InstallChannelTest {

    @Test
    fun `the play store is the play channel`() {
        assertEquals(InstallChannel.PLAY, installChannelOf("com.android.vending"))
    }

    @Test
    fun `this app installing its own apk is not the play channel`() {
        // REQ-0003's updater names itself here. This is the exact case the whole enum exists for:
        // the app is signed identically and works perfectly, and the car will not list it.
        assertEquals(InstallChannel.ELSEWHERE, installChannelOf("dev.isachivka.bewareofsugar"))
    }

    @Test
    fun `a browser download is not the play channel`() {
        assertEquals(InstallChannel.ELSEWHERE, installChannelOf("com.android.chrome"))
    }

    @Test
    fun `adb leaves no installer at all`() {
        // `adb install` records null, and so does a sideload by some paths. Null must not be read
        // as "probably Play" by any accident of string comparison.
        assertEquals(InstallChannel.ELSEWHERE, installChannelOf(null))
    }

    @Test
    fun `a package that merely looks like the store is not the store`() {
        // A near-miss must fail closed. Nothing here does prefix or contains matching, and this test
        // is what stops someone "helpfully" making it lenient later.
        assertEquals(InstallChannel.ELSEWHERE, installChannelOf("com.android.vending.fake"))
        assertEquals(InstallChannel.ELSEWHERE, installChannelOf("vending"))
        assertEquals(InstallChannel.ELSEWHERE, installChannelOf(""))
    }

    @Test
    fun `the constant is the package the platform actually reports`() {
        assertEquals("com.android.vending", PLAY_STORE_PACKAGE)
    }
}
