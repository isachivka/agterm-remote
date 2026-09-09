package dev.isachivka.agtermremote.pairing

import dev.isachivka.agtermremote.wire.BridgeUrl
import dev.isachivka.agtermremote.wire.ByteStream
import dev.isachivka.agtermremote.wire.TlsDriver
import dev.isachivka.agtermremote.wire.WebSocketStream
import dev.isachivka.agtermremote.wire.asByteStream
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * What one enrolment did.
 *
 * ### Four answers, and why "unreachable" is not allowed to absorb two of them
 *
 * The house rule this project keeps returning to is that saying *the laptop is not answering* about a
 * laptop that answered sends the owner to the wrong device. Two of the four arms exist because of it:
 *
 *  - [NotTheLaptopInTheCode] is a machine that answered with the wrong certificate. The remedy is a
 *    fresh code, or a hard look at what is on that address.
 *  - [Refused] is the bridge itself saying no. The remedy is a fresh code too, but for a different
 *    reason, and the laptop is fine.
 *
 * Collapsing either into [Unreachable] would be the same defect twice.
 */
sealed interface EnrollResult {

    /**
     * Paired, and the two things the next screen needs.
     *
     * [bridgeCertificate] is the DER the phone has just pinned — the bytes behind the fingerprint the
     * code carried. [fingerprint] is **this phone's**, as the bridge stored it: the receipt, and the
     * string the Mac's menu will show, so a human who wants to compare the two screens can.
     */
    data class Paired(val bridgeCertificate: X509Certificate, val fingerprint: String) : EnrollResult

    /**
     * The bridge said no.
     *
     * [reason] is **this app's own sentence and is never parsed out of the reply.** The bridge answers
     * every failure with the same forty bytes and no cause, deliberately, so that an anonymous caller
     * learns nothing from guessing. Inventing a cause from a reply that carries none would be a
     * diagnosis the phone cannot make.
     */
    data class Refused(val reason: String) : EnrollResult

    /**
     * Something answered on that address and it is not the laptop the code names.
     *
     * **Nothing was sent.** The fingerprint is checked during the handshake, so the one-time token and
     * this phone's certificate never left the device.
     */
    data object NotTheLaptopInTheCode : EnrollResult

    /** Nothing answered — including an address the phone will not dial at all. */
    data class Unreachable(val cause: Throwable) : EnrollResult
}

/**
 * The one exchange that happens before this phone is anybody.
 *
 * ```
 * WebSocketStream   the HTTP upgrade the bridge's front door expects
 *   └─ TlsDriver    TLS 1.3, ALPN agterm/enroll-1, NO client certificate,
 *   │               the server judged by FingerprintTrust against the 32 bytes off the code
 *        └─ here    one JSON line out, one line in, and the connection closes
 * ```
 *
 * ### Everything below rests on the handshake, and the handshake rests on 32 bytes
 *
 * The phone arrives holding a digest that came off the owner's own screen. That is enough to
 * recognise the laptop and not enough to pin it, which is what the reply is for: it carries the DER,
 * and from the next connection onwards the phone compares bytes.
 *
 * The order matters and is the security argument for a token on a screen at all: **the token is sent
 * only after the laptop has proved it is the one in the code.** A man in the middle fails at the
 * handshake, before any secret exists on the wire.
 *
 * ### One line in, one line out
 *
 * The far end reads at most 64 KiB up to a newline, writes one line, and closes. A second request on
 * the same connection is discarded, and there is a two-second deadline on the whole exchange after
 * the handshake completes. Nothing here retries inside a connection; a phone that got it wrong scans
 * again.
 *
 * ### What a refusal is allowed to say
 *
 * Nothing. Every failure comes back as the same forty bytes with no cause, and that is deliberate on
 * the far side — the owner's log gets the diagnosis, the anonymous caller does not. So this file
 * never reads `error` out of a reply, and [EnrollResult.Refused] carries a sentence written here.
 */
object Enrollment {

    /**
     * The sentence shown for a bridge that said no.
     *
     * One value, because the bridge distinguishes nothing and the phone must not pretend it does. The
     * causes it covers — an expired window, a token already spent, five wrong attempts, a panel that
     * was closed — all have the same next step.
     */
    const val REFUSED = "That pairing code did not work. Open a new one on your Mac and scan it."

    /**
     * Which is also what a spent or expired code produces, and deliberately the same sentence.
     *
     * The two arrive by different routes - a bridge that answered `ok:false`, and a bridge whose shut
     * window means the handshake never completed - and the phone cannot tell them apart even in
     * principle: the far end is silent about the first by design and says nothing at all about the
     * second. One remedy, one sentence, and no invented distinction between them.
     */

    /** The bridge pinned something that is not this phone, so the pairing would not survive. */
    const val MISMATCHED = "Your Mac pinned a different phone. Open a new pairing code and scan it."

    /** The reply did not carry the certificate the code's fingerprint just authenticated. */
    const val WRONG_CERTIFICATE = "Your Mac answered with an unexpected certificate. Try a new code."

    /**
     * Enrols this phone with the laptop the code names, and pairs it on success.
     *
     * [identity] is this phone's certificate — public, and the thing the bridge is being asked to pin.
     * The private key behind it is in the hardware keystore and is not needed here at all: **the
     * enrolment handshake presents no client certificate**, which is spec §5.2 and is what the
     * bridge's enrolment branch requires.
     *
     * [store] is written only on success, and only after everything about the reply has checked out.
     */
    fun enroll(
        payload: EnrollPayload,
        identity: X509Certificate,
        deviceName: String,
        store: PairedLaptop,
    ): EnrollResult = enroll(payload, identity, deviceName, store) { url ->
        WebSocketStream.open(url).asByteStream()
    }

    /**
     * The same exchange over an injected transport.
     *
     * The seam is the *transport* and nothing above it: the TLS, the trust decision, the JSON and the
     * store are the real ones in every test. What it buys is a fake bridge that is a plain TLS server
     * rather than a second Kotlin implementation of the WebSocket framing — and the framing is proven
     * against the actual Go bridge instead, which is the only thing that could prove it.
     */
    internal fun enroll(
        payload: EnrollPayload,
        identity: X509Certificate,
        deviceName: String,
        store: PairedLaptop,
        openStream: (String) -> ByteStream,
    ): EnrollResult {
        // **Before anything is opened.** The payload decoder validates the host as UTF-8 and no
        // further, byte for byte with the Go encoder, so a NUL, a space, a newline or a slash decodes
        // into a payload that looks ordinary, and port 0 decodes too. Tightening the decoder would put
        // the two implementations of one wire format out of step; the dialler is where an address has
        // to be an address.
        undiallable(payload.host, payload.port)?.let { return EnrollResult.Unreachable(it) }

        // **Opening the transport is what divides "not reachable" from "would not accept this".**
        //
        // Everything before this line failed without anything answering. Everything after it failed
        // with a machine on the other end that completed an HTTP upgrade. That boundary, and not the
        // exception type, is what picks the arm below - see the catch around the handshake.
        val stream = try {
            // The whole URL, derived once by the payload, so the transport is handed a string rather
            // than the job of assembling one. The scheme is the owner's declared fact and the
            // bracketing is the format's rule; neither belongs at a call site.
            openStream(payload.dialUrl)
        } catch (e: Exception) {
            return EnrollResult.Unreachable(e)
        }

        return stream.use { transport ->
            // Held so the verdict can be read off a field rather than recovered from a cause chain.
            // The lesson is PinnedTrust's and it was measured there: whether a provider preserves the
            // exception a trust manager threw is behaviour, not contract, and the app runs on
            // Conscrypt while the tests run on the JVM's own provider.
            val trust = FingerprintTrust(payload.fingerprint)
            val driver = try {
                TlsDriver.openWith(
                    transportIn = transport.input,
                    transportOut = transport.output,
                    trust = trust,
                    // No client certificate. The bridge's enrolment branch requires none and never
                    // asks; this connection is anonymous by design and the token is what authorises it.
                    identity = null,
                    applicationProtocol = TlsDriver.PROTOCOL_ENROL,
                )
            } catch (e: Exception) {
                // **A HANDSHAKE FAILURE HERE IS NEVER "NOT REACHABLE", AND THIS IS THE MOST LIKELY
                // FAILURE THE OWNER WILL EVER SEE.**
                //
                // A code that has been used, or whose five minutes are up, meets a bridge with its
                // window shut. Such a bridge offers `agterm/api-1` and nothing else, this connection
                // offers `agterm/enroll-1` and nothing else, and the handshake dies on
                // `no_application_protocol` - before a certificate is presented, so FingerprintTrust
                // is never consulted and has no verdict to give. Neither side logs it.
                //
                // This used to fall to Unreachable, which is false in all three of the ways that
                // matter: something is listening at that address, it completed an HTTP upgrade, and
                // it then declined. It sent the owner to look at their router when the remedy is to
                // open a new code, and it did so in the file that argues that saying "not answering"
                // about a machine that answered is the defect this type exists to prevent.
                //
                // So the arm is chosen by WHERE the failure happened rather than by what was thrown.
                // The transport opened; therefore something answered; therefore this is a refusal.
                // The one distinction still worth making above it is the pinning one, because its
                // remedy is different - that laptop is the wrong machine, not a shut door.
                return@use if (trust.refusedTheLaptop) {
                    EnrollResult.NotTheLaptopInTheCode
                } else {
                    EnrollResult.Refused(REFUSED)
                }
            }

            try {
                driver.output.write(request(payload, identity, deviceName))
                driver.output.flush()
                val line = BufferedReader(InputStreamReader(driver.input, Charsets.UTF_8)).readLine()
                    ?: return@use EnrollResult.Unreachable(
                        IllegalStateException("the laptop closed the connection without answering"),
                    )
                reply(line, payload, identity, store)
            } catch (e: Exception) {
                EnrollResult.Unreachable(e)
            }
        }
    }

    /**
     * The four fields the bridge parses, and no fifth.
     *
     * It refuses a field it does not know — the same reading the API takes of its own input — so this
     * is a closed set rather than a minimum. A newer phone talks to an older bridge by naming a new
     * ALPN protocol, never by adding a key here.
     */
    private fun request(
        payload: EnrollPayload,
        identity: X509Certificate,
        deviceName: String,
    ): ByteArray {
        val body = JSONObject()
            .put("verb", "enroll")
            .put("token", Base64.getEncoder().encodeToString(payload.token))
            .put("certificate", Base64.getEncoder().encodeToString(identity.encoded))
            .put("name", deviceName)
            .toString()
        return (body + "\n").toByteArray(Charsets.UTF_8)
    }

    /**
     * Reads the one line back, and pins nothing it has not checked.
     *
     * Three checks, and each has a failure the phone would otherwise discover as an unexplainable
     * handshake failure days later:
     *
     *  1. `ok` — the bridge accepted the token.
     *  2. The certificate in the reply hashes to the fingerprint off the code. It has to: that digest
     *     is what authenticated this connection, and pinning anything else would silently swap the
     *     identity the owner compared for one they did not.
     *  3. The receipt is this phone's fingerprint. It is what the bridge actually STORED, so a value
     *     that is not ours means the certificate arrived mangled and the next connection would fail
     *     with nothing pointing here.
     */
    private fun reply(
        line: String,
        payload: EnrollPayload,
        identity: X509Certificate,
        store: PairedLaptop,
    ): EnrollResult {
        val parsed = try {
            JSONObject(line)
        } catch (e: JSONException) {
            return EnrollResult.Unreachable(e)
        }
        // `error` is deliberately not read. It is the same string on every failure.
        if (!parsed.optBoolean("ok")) return EnrollResult.Refused(REFUSED)

        val der = try {
            Base64.getDecoder().decode(parsed.optString("certificate"))
        } catch (e: IllegalArgumentException) {
            return EnrollResult.Refused(WRONG_CERTIFICATE)
        }
        if (!MessageDigest.isEqual(payload.fingerprint, sha256(der))) {
            return EnrollResult.Refused(WRONG_CERTIFICATE)
        }
        val certificate = try {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(der.inputStream()) as X509Certificate
        } catch (e: Exception) {
            return EnrollResult.Refused(WRONG_CERTIFICATE)
        }

        val receipt = parsed.optString("fingerprint")
        if (receipt != Fingerprint.of(identity)) return EnrollResult.Refused(MISMATCHED)

        // Written last. Everything above can refuse; nothing above this line has stored anything.
        store.write(
            ConnectionProfile(
                // Stored as the payload said, so the terminal opens the address the same way
                // enrolment just did. Assuming one here is what version 1 of the payload did.
                kind = payload.scheme,
                host = payload.host,
                port = payload.port,
                bridgeCertificate = der,
            ),
        )
        return EnrollResult.Paired(certificate, receipt)
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * Why this address will not be dialled, or null.
     *
     * A host goes into a URL, so anything that could end the authority component early — a slash, a
     * question mark, a hash, an at sign, a backslash — or that is not a printable character at all is
     * refused. Refusing broadly is right here: a host this phone cannot make sense of is a broken
     * code, and the remedy is a new one rather than a connection attempt against whatever the string
     * happens to mean to a URL parser.
     */
    private fun undiallable(host: String, port: Int): Throwable? {
        if (port !in 1..65535) {
            return IllegalArgumentException("the pairing code names port $port, which is not a port")
        }
        // The host itself is never in the message: it is the owner's address and this string can
        // reach a log.
        val bad = IllegalArgumentException("the pairing code does not name an address this phone can dial")
        if (host.isEmpty()) return bad
        if (host.any { it.code <= 0x20 || it.code == 0x7F }) return bad
        if (host.any { it in FORBIDDEN }) return bad
        return null
    }

    /**
     * Brackets are in here because the payload's host is **bare** even for an IPv6 literal — see
     * `EnrollPayload.dialAddress`, which is what adds them. A host that arrives already bracketed
     * would be bracketed twice.
     */
    private val FORBIDDEN = charArrayOf('/', '\\', '?', '#', '@', '[', ']')
}
