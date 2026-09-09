package dev.isachivka.agtermremote.pairing

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.cert.X509Certificate

/**
 * The gate, and the thing it is really asserting: **what did not happen**.
 *
 * A test that only checks the returned value would pass for a gate that enrolled first and threw the
 * answer away — which is the failure exactly, because enrolling spends a one-time token, pins this
 * phone on the Mac, and writes the profile here. So every case below counts the calls.
 */
class EnrolGateTest {

    private val certificate: X509Certificate =
        HeldCertificate.Builder().commonName("this phone").build().certificate

    private var enrolments = 0

    private fun enrol(seen: X509Certificate): EnrollResult {
        enrolments++
        return EnrollResult.Paired(seen, "AAAA BBBB")
    }

    // --- The gate closed ------------------------------------------------------------------------

    /**
     * **The case the gate exists for.** The enrolment would have succeeded: it presents no client
     * certificate, so a key that cannot sign is invisible to it. The screen would then have said
     * Paired, shown a fingerprint, and never reached the API again.
     */
    @Test
    fun `a key that cannot sign is refused before anything is opened`() {
        val result = EnrolGate.run(
            identity = { certificate },
            signing = { SigningState.Unusable },
            enrol = ::enrol,
        )

        assertEquals(EnrollResult.Refused(PairingOutcome.KEY_CANNOT_SIGN), result)
        assertEquals("the enrolment must not have been reached at all", 0, enrolments)
    }

    /**
     * `Absent` cannot follow a successful mint, and it is refused anyway rather than let through by a
     * check written as "not Unusable". The gate asks whether the key WILL SIGN, not whether it has
     * been seen to fail.
     */
    @Test
    fun `any state that is not ready is refused`() {
        // Listed rather than reflected: `sealedSubclasses` needs kotlin-reflect, which is not on this
        // module's test classpath, and a list a person has to extend is the same guarantee here -
        // adding a state without adding it below leaves it untested, and adding it below is one line.
        listOf(SigningState.Unusable, SigningState.Absent)
            .forEach { state ->
                enrolments = 0
                val result = EnrolGate.run({ certificate }, { state }, ::enrol)

                assertTrue("$state must be refused, was $result", result is EnrollResult.Refused)
                assertEquals("$state reached the enrolment", 0, enrolments)
            }
    }

    /** A keystore that will not mint is this phone's problem, and the sentence says so. */
    @Test
    fun `an identity that will not mint is refused before anything is opened`() {
        val result = EnrolGate.run(
            identity = { throw GeneralSecurityException("no key for you") },
            signing = { error("the signing state must not be asked about a key that does not exist") },
            enrol = ::enrol,
        )

        assertEquals(EnrollResult.Refused(PairingOutcome.NO_IDENTITY), result)
        assertEquals(0, enrolments)
    }

    // --- The gate open --------------------------------------------------------------------------

    @Test
    fun `a key that will sign enrols, exactly once, with that certificate`() {
        var seen: X509Certificate? = null

        val result = EnrolGate.run(
            identity = { certificate },
            signing = { SigningState.Ready },
            enrol = { it.also { c -> seen = c }; enrol(it) },
        )

        assertEquals(1, enrolments)
        assertEquals("the certificate the enrolment sends must be the one just read", certificate, seen)
        assertTrue(result is EnrollResult.Paired)
    }

    /**
     * **The identity is read before the signing state, and the order is load-bearing.**
     *
     * On a phone with no key at all, `certificate()` mints one and `signingState()` then reports on
     * the key that now exists. Asked the other way round, the first pairing on a new phone would be
     * refused for a key that had not been created yet.
     */
    @Test
    fun `the identity is minted before the signing state is asked`() {
        val order = mutableListOf<String>()

        EnrolGate.run(
            identity = { order += "identity"; certificate },
            signing = { order += "signing"; SigningState.Ready },
            enrol = { order += "enrol"; enrol(it) },
        )

        assertEquals(listOf("identity", "signing", "enrol"), order)
    }
}
