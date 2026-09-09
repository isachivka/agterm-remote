package dev.isachivka.agtermremote.wire

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether this app may open the outer hop at all, asked of the platform rather than of a comment.
 *
 * ### The bug this exists because of, and why the end-to-end run did not find it
 *
 * The bridge in this repository terminates no outer TLS — `main.go` hands a bare TCP listener to
 * `frontdoor` — so what the phone opens is `ws://`. At this `targetSdk` Android refuses cleartext by
 * default, which would make every real pairing fail with `CLEARTEXT communication not permitted`.
 *
 * The first end-to-end run against a real Go bridge **passed anyway**, and that is the trap: it dials
 * `127.0.0.1` through `adb reverse`, and the platform's default configuration carries a `localhost`
 * exemption the app's own file inherits. So the one test that puts the two halves in the same room
 * cannot see this, because the address it has to use is the address the rule does not apply to.
 *
 * Hence a test that asks the policy directly, about a host that is not loopback. It measures the
 * decision rather than a connection, so it is not fooled by where the fixture happens to live.
 *
 * ### What permitting cleartext does and does not give up
 *
 * It gives up nothing this design ever relied on. The trust anchor is the pinned mTLS **inside** the
 * upgraded stream, terminating on the laptop; the outer hop authenticates nobody and is explicitly
 * optional in the architecture — the whole reason `frontdoor` exists is that a proxy in front may
 * terminate TLS and move bytes it cannot read. The traffic is not in the clear either: what travels
 * over this socket is TLS 1.3 records with a byte-pinned peer.
 *
 * What is given up is Android's own belt-and-braces check, which can only see the outer socket. That
 * is a real loss and it is the reason this is asserted rather than assumed: if the payload ever grows
 * a scheme, or the bridge ever terminates its own TLS, this test is where the decision is revisited.
 */
@RunWith(AndroidJUnit4::class)
class CleartextPolicyTest {

    /**
     * A laptop is reached at a name or a LAN address, never at loopback, and the pairing payload
     * carries whatever the owner typed. So the policy has to permit cleartext generally; permitting
     * it for loopback alone is the state that passes every test here and fails on the owner's phone.
     */
    @Test
    fun theOuterHopMayBeCleartextForAnAddressThatIsNotLoopback() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertTrue(
            "a laptop at a DNS name must be reachable",
            policy.isCleartextTrafficPermitted("laptop.example"),
        )
        assertTrue(
            "a laptop at a LAN address must be reachable",
            policy.isCleartextTrafficPermitted("198.51.100.7"),
        )
    }

    /**
     * The exemption that made the end-to-end run pass before the configuration said anything. Pinned
     * so that the next reader of the test above knows why it could not have caught this on its own.
     */
    @Test
    fun loopbackWasAlwaysPermitted() {
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("localhost"))
    }
}
