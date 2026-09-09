package dev.isachivka.bewareofsugar.wire

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * **The blocking measurement, before any driver code.**
 *
 * PLAN-0009 iteration 2 records that "ordinary public-CA validation of the router's certificate" is
 * an *assumption*. It was measured from a VPS, whose trust store is not Android's. Nothing yet
 * establishes that the KeenDNS certificate for the bridge's name validates against **the phone's**
 * store at the phone's API level — and the whole outer layer of the wire client rests on it.
 *
 * **Nothing here weakens anything.** A default `OkHttpClient`: the platform trust store, the platform
 * hostname verifier, no exceptions of any kind. That is the point — the question is precisely whether
 * the unmodified client succeeds.
 *
 * If it does not, the ruling is recorded in advance and is not mine to revisit: not a permissive
 * `TrustManager`, not a hostname-verifier override, not a debug-only exception, not cleartext. The
 * work stops and the result goes back for a decision. A weakening shipped as a workaround outlives
 * every memory of why it was added.
 */
@RunWith(AndroidJUnit4::class)
class RouterCertificateTrustTest {

    @Test
    fun theRouterCertificateValidatesAgainstThePhonesTrustStore() {
        // Deliberately the default. Anything customised here would answer a different question.
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(URL).head().build()

        try {
            client.newCall(request).execute().use { response ->
                // Any HTTP status at all means the TLS handshake completed and the certificate was
                // accepted by the platform. The status itself is not the subject.
                Log.i(TAG, "VALIDATES: handshake completed, HTTP ${response.code}")
                Log.i(TAG, "TLS: ${response.handshake?.tlsVersion} ${response.handshake?.cipherSuite}")
                response.handshake?.peerCertificates?.forEachIndexed { i, cert ->
                    Log.i(TAG, "CHAIN[$i]: $cert".take(300))
                }
            }
        } catch (e: SSLHandshakeException) {
            Log.i(TAG, "DOES NOT VALIDATE: $e")
            fail(
                "The KeenDNS certificate does not validate against Android's trust store: $e\n" +
                    "PLAN-0009 iteration 2: the work stops here. The answer is NOT a permissive " +
                    "TrustManager, a hostname-verifier override, a debug-only exception or cleartext.",
            )
        } catch (e: Exception) {
            // A network failure is not an answer to the trust question, and must not be reported as
            // one - reaching nothing and being refused by the trust store look the same to a caller
            // who only checks for an exception.
            Log.i(TAG, "INCONCLUSIVE (not a trust failure): $e")
            fail("Could not reach the endpoint, so trust was never tested: $e")
        }
    }

    private companion object {
        const val TAG = "TRUSTPROBE"
        const val URL = "https://agterm.bewareofsugar.keenetic.link:8443/"
    }
}
