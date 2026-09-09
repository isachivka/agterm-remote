package dev.isachivka.bewareofsugar.reachability

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * What may be in the registry, and what may never be.
 *
 * Most of this file is not testing arithmetic; it is enforcing the two promises REQ-0005 makes about
 * this app's reach. The app may now contact the owner's own hosts and nothing else, and no address
 * from the credential-bearing half of `services.ts` may ship. Both are properties of a list that a
 * future edit adds one line to, which is exactly the kind of promise that needs a test rather than a
 * paragraph.
 */
class ServiceRegistryTest {

    @Test
    fun `the ten services with a remote address are the ten`() {
        assertEquals(
            listOf(
                "torrent", "files", "immich", "n8n", "inventory",
                "ha", "docs", "frigate", "navidrome", "jellyfin",
            ),
            ServiceRegistry.remote.map { it.id },
        )
    }

    @Test
    fun `ids are unique, because results are keyed by them`() {
        assertEquals(ServiceRegistry.remote.size, ServiceRegistry.remote.map { it.id }.toSet().size)
    }

    @Test
    fun `an unknown id is not a service`() {
        assertNull(ServiceRegistry.byId("plex"))
    }

    // -- The reach ---------------------------------------------------------------------------------

    /**
     * The narrow half of "REQ-0004's github.com-and-nowhere-else is lifted".
     *
     * An eleventh entry pointing at anything else fails here rather than silently widening what the
     * app is allowed to talk to. This is the test that keeps the lift narrow instead of nominal.
     */
    @Test
    fun `every address is a host under the one permitted domain`() {
        ServiceRegistry.remote.forEach { service ->
            val url = service.url.toHttpUrlOrNull()
            assertNotNull("${service.id}: ${service.url} is not a URL", url)
            assertTrue(
                "${service.id}: ${url!!.host} is outside ${ServiceRegistry.PERMITTED_HOST_SUFFIX}",
                url.host.endsWith(ServiceRegistry.PERMITTED_HOST_SUFFIX),
            )
        }
    }

    /**
     * Cleartext is off app-wide and stays off, so an `http://` address here would not fail as a
     * network error the screen could explain - it would fail as a policy exception nobody chose.
     * Jellyfin is the entry this exists for: its address in `services.ts` is cleartext, and the one
     * here is the HTTPS endpoint that address redirects to.
     */
    @Test
    fun `every address is https`() {
        ServiceRegistry.remote.forEach { service ->
            assertEquals("${service.id} must be https", "https", service.url.toHttpUrlOrNull()?.scheme)
        }
    }

    @Test
    fun `jellyfin is checked over https rather than the cleartext address the registry lists`() {
        val jellyfin = ServiceRegistry.byId("jellyfin")

        assertEquals("https://jellyfin.bewareofsugar.keenetic.link:8443", jellyfin?.url)
    }

    // -- What must not ship ------------------------------------------------------------------------

    /**
     * The services with no remote address are names and nothing else.
     *
     * Their addresses stay out for two reasons and only one of them is "they are local": both 3x-ui
     * entries carry an unguessable panel path, which is access control by obscurity and therefore a
     * credential in all but name. A name is not an address, so this asserts that none of these ten
     * has grown one.
     */
    @Test
    fun `the services without a remote address carry no address`() {
        ServiceRegistry.withoutRemoteAddress.forEach { name ->
            assertFalse("$name looks like an address", name.contains("://"))
            assertFalse("$name looks like an address", name.contains(":") && name.any { it.isDigit() })
            assertFalse("$name contains a host", name.contains("."))
        }
    }

    @Test
    fun `no local address reaches the registry`() {
        // The LAN the owner's services actually sit on. Nothing in this app is allowed to know it:
        // a local address is useless from a mobile network, which is the entire premise of REQ-0005,
        // and it is one of the things `services.ts` carries that must not travel.
        val everything = ServiceRegistry.remote.flatMap { listOf(it.id, it.name, it.url) } +
            ServiceRegistry.withoutRemoteAddress

        everything.forEach { text ->
            assertFalse("$text contains a private address", text.contains("192.168."))
            assertFalse("$text contains a private address", text.contains("10.0."))
            assertFalse("$text contains localhost", text.contains("localhost"))
        }
    }

    /**
     * No credential field exists to be filled in, which is a claim about the *shape* of the data
     * rather than about today's values - so it is asserted against the type, not by reading the
     * list. `HomeService` has three properties and none of them is a secret.
     */
    @Test
    fun `a service carries an address and never a credential`() {
        // Instance fields only. The Compose compiler plugin adds a static `$stable` to every class
        // in the module, which is not something a HomeService carries.
        val properties = HomeService::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

        assertEquals(setOf("id", "name", "url"), properties)
    }

    // -- The other half of the estate --------------------------------------------------------------

    @Test
    fun `all twenty services are accounted for, so the screen cannot imply the estate is ten`() {
        assertEquals(10, ServiceRegistry.remote.size)
        assertEquals(10, ServiceRegistry.withoutRemoteAddress.size)
    }

    @Test
    fun `no service is in both lists`() {
        val remoteNames = ServiceRegistry.remote.map { it.name }.toSet()

        assertEquals(emptySet<String>(), remoteNames intersect ServiceRegistry.withoutRemoteAddress.toSet())
    }

    @Test
    fun `every name is real text`() {
        (ServiceRegistry.remote.map { it.name } + ServiceRegistry.withoutRemoteAddress).forEach {
            assertTrue("blank name", it.isNotBlank())
        }
    }
}
