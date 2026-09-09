package dev.isachivka.bewareofsugar.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The answers to a refused camera, and the deadlock that hid the question.
 */
class CameraAccessTest {

    /**
     * **The regression test, and the one worth more than the rest of this file.**
     *
     * A fresh install holds no permission. The first version of this type hid the scan affordance in
     * exactly that state, while the affordance was the only thing that requested the permission — so
     * the viewfinder was unreachable on every phone that had never granted it, which is every phone.
     * An emulator run found it on 2026-08-09; no JVM test could have, because the old assertions
     * described the behaviour rather than questioning it.
     */
    @Test
    fun `on a fresh install with no permission held, the scan affordance is present`() {
        val fresh = CameraAccess.of(hasCamera = true, permissionGranted = false, refusedAlready = false)

        assertTrue("the viewfinder is unreachable on a phone that has never granted the permission", fresh.offersScanning)
        assertTrue("nothing would ever request the permission", fresh.asksOnTap)
        assertEquals(CameraAccess.Askable, fresh)
    }

    /** Nothing has been refused, so there is nothing to explain. */
    @Test
    fun `a fresh install explains no refusal that has not happened`() {
        assertFalse(CameraAccess.of(hasCamera = true, permissionGranted = false).explainsItself)
    }

    @Test
    fun `a granted permission opens the viewfinder without asking again`() {
        val ready = CameraAccess.of(hasCamera = true, permissionGranted = true)

        assertEquals(CameraAccess.Ready, ready)
        assertTrue(ready.offersScanning)
        assertFalse("a held permission must not raise a dialog", ready.asksOnTap)
        assertFalse(ready.explainsItself)
    }

    /**
     * Refused keeps the button. A permission refused today may be granted tomorrow, and the tap is
     * still the only thing that asks — but the refusal is explained so the button is not silently
     * doing nothing.
     */
    @Test
    fun `a refusal keeps the affordance and explains itself`() {
        val refused = CameraAccess.of(hasCamera = true, permissionGranted = false, refusedAlready = true)

        assertEquals(CameraAccess.Refused, refused)
        assertTrue("a refusal must not remove the only route to the viewfinder", refused.offersScanning)
        assertTrue(refused.asksOnTap)
        assertTrue(refused.explainsItself)
    }

    /**
     * **No camera outranks everything**, including a permission the device happens to hold: it is a
     * declaration, not a capability, and sending that owner to Settings for hardware they do not have
     * is the screen that makes someone distrust the rest of the app.
     */
    @Test
    fun `no camera hides the affordance whatever the permission says`() {
        for (granted in listOf(true, false)) {
            for (refused in listOf(true, false)) {
                val access = CameraAccess.of(hasCamera = false, permissionGranted = granted, refusedAlready = refused)
                assertEquals(CameraAccess.Absent, access)
                assertFalse(access.offersScanning)
                assertFalse(access.explainsItself)
                assertFalse(access.asksOnTap)
            }
        }
    }

    /** Exactly one state hides the button, so a fifth state cannot quietly become a second deadlock. */
    @Test
    fun `Absent is the only state that removes the scan affordance`() {
        assertEquals(
            listOf(CameraAccess.Absent),
            CameraAccess.entries.filterNot { it.offersScanning },
        )
    }
}
