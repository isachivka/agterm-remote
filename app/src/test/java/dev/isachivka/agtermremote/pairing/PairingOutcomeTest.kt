package dev.isachivka.agtermremote.pairing

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Every way pairing can end, and what the screen says about it.
 *
 * The screen itself needs a device; this does not, and it is where the mapping lives precisely so
 * that it can be exhaustive. A `when` with no `else` over both sealed types means a new outcome stops
 * this file compiling rather than quietly inheriting somebody's `Failed("")`.
 *
 * **The property that matters is not the wording, it is that there is a next step.** A failure with
 * nothing to do about it is the shape this project keeps meeting: a screen saying the laptop did not
 * answer, about a laptop that answered, with no action on it.
 */
class PairingOutcomeTest {

    private val payload = EnrollPayload(
        host = "mac.example.invalid",
        port = 8443,
        fingerprint = ByteArray(32),
        token = ByteArray(32),
        expiryUnix = 0,
    )

    // --- Enrolment ------------------------------------------------------------------------------

    @Test
    fun `a paired result carries the fingerprint the bridge stored`() {
        val result = EnrollResult.Paired(
            HeldCertificate.Builder().commonName("a bridge").build().certificate,
            "AAAA BBBB CCCC DDDD",
        )

        assertEquals(PairingUi.Paired("AAAA BBBB CCCC DDDD"), PairingOutcome.of(result))
    }

    @Test
    fun `a refusal shows the sentence the enrolment wrote, never one invented here`() {
        assertEquals(
            PairingUi.Failed(Enrollment.REFUSED),
            PairingOutcome.of(EnrollResult.Refused(Enrollment.REFUSED)),
        )
        assertEquals(
            PairingUi.Failed(Enrollment.MISMATCHED),
            PairingOutcome.of(EnrollResult.Refused(Enrollment.MISMATCHED)),
        )
    }

    /**
     * **The wrong machine and an absent one are different sentences**, because the remedies differ:
     * one is a new code, the other is a look at what is on that address.
     */
    @Test
    fun `the wrong laptop and an unreachable one do not share a sentence`() {
        val wrong = PairingOutcome.of(EnrollResult.NotTheLaptopInTheCode) as PairingUi.Failed
        val absent = PairingOutcome.of(EnrollResult.Unreachable(IOException("no route"))) as PairingUi.Failed

        assertTrue("the two must not read the same", wrong.reason != absent.reason)
    }

    /**
     * **The exception is never shown.** `Unreachable` carries a `Throwable` whose message can hold the
     * address the phone tried to reach, and this screen is the one a person photographs for a bug
     * report.
     */
    @Test
    fun `an unreachable laptop does not put the cause on the screen`() {
        val cause = IllegalArgumentException("mac.example.invalid:8443 refused the connection")

        val failed = PairingOutcome.of(EnrollResult.Unreachable(cause)) as PairingUi.Failed

        assertTrue(
            "the screen must not carry the cause: ${failed.reason}",
            !failed.reason.contains("mac.example.invalid") && !failed.reason.contains(cause.message!!),
        )
    }

    // --- Reading the code -----------------------------------------------------------------------

    /** A code this build understands is not an outcome at all: the caller goes on to enrol with it. */
    @Test
    fun `a readable code produces no screen of its own`() {
        assertNull(PairingOutcome.of(EnrollDecode.Read(payload)))
    }

    /**
     * **"This Mac is newer than this app" and "that is not a pairing code" are different sentences**,
     * with different remedies: update the phone, or scan again. Collapsing them costs the owner of a
     * newer Mac an afternoon looking for a broken camera.
     */
    @Test
    fun `an unsupported version says the app is behind, and a refusal says it is not a code`() {
        val newer = PairingOutcome.of(EnrollDecode.UnsupportedVersion(9)) as PairingUi.Failed
        val rubbish = PairingOutcome.of(
            EnrollDecode.NotAPairingCode(EnrollRefusal.NotStandardBase64),
        ) as PairingUi.Failed

        assertTrue("the two must not read the same", newer.reason != rubbish.reason)
    }

    /**
     * **Nothing on a screen may branch on a refusal kind.** It is the wire contract's field, not the
     * copy's: every value means the same thing to somebody holding a phone.
     */
    @Test
    fun `every refusal kind produces the same sentence`() {
        val sentences = EnrollRefusal.entries
            .map { (PairingOutcome.of(EnrollDecode.NotAPairingCode(it)) as PairingUi.Failed).reason }
            .toSet()

        assertEquals("a screen that branches on a refusal kind is telling the owner about the wire", 1, sentences.size)
    }

    // --- The property the whole type exists for -------------------------------------------------

    /**
     * **Every failure names something the owner can do**, and this is the assertion that keeps it true
     * as arms are added. A sentence that only reports is the defect this project has met repeatedly.
     *
     * The check is deliberately crude - the reason has to end in a full stop and contain an imperative
     * from a short list - because the alternative is checking the exact wording, which pins the copy
     * rather than the property.
     */
    @Test
    fun `every failure says what to do next`() {
        val everyFailure = listOf(
            PairingOutcome.of(EnrollResult.Refused(Enrollment.REFUSED)),
            PairingOutcome.of(EnrollResult.Refused(Enrollment.MISMATCHED)),
            PairingOutcome.of(EnrollResult.Refused(Enrollment.WRONG_CERTIFICATE)),
            PairingOutcome.of(EnrollResult.NotTheLaptopInTheCode),
            PairingOutcome.of(EnrollResult.Unreachable(IOException("x"))),
            PairingOutcome.of(EnrollDecode.UnsupportedVersion(9)),
        ) + EnrollRefusal.entries.map { PairingOutcome.of(EnrollDecode.NotAPairingCode(it)) }

        everyFailure.filterIsInstance<PairingUi.Failed>().forEach { failed ->
            assertTrue("an empty reason is a screen with nothing on it", failed.reason.isNotBlank())
            assertTrue("a reason must be a sentence: ${failed.reason}", failed.reason.trim().endsWith("."))
            assertTrue(
                "nothing to do about it: ${failed.reason}",
                IMPERATIVES.any { failed.reason.contains(it) },
            )
        }
        assertEquals("every arm above must map to a failure", everyFailure.size, everyFailure.filterIsInstance<PairingUi.Failed>().size)
    }

    private companion object {
        /** Verbs that name an action. Short on purpose: this pins the property, not the copy. */
        val IMPERATIVES = listOf("Open ", "Scan ", "Update ", "Check ", "Try ", "Point ", "Paste ")
    }
}
