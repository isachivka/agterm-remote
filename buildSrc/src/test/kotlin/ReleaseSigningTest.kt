import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How release signing material is resolved from the environment.
 *
 * The interesting cases are not "it works". They are: a normal local build must be unaffected, a
 * half-configured environment must fail rather than silently produce an unsigned or differently-signed
 * build, and **no failure path may ever put a secret into a message**. That last one cannot be checked
 * by reading the code once; it has to be a test, because it is the kind of thing a later edit breaks
 * without noticing.
 */
class ReleaseSigningTest {

    private val complete = mapOf(
        "SIGNING_KEYSTORE_BASE64" to "a2V5c3RvcmU=",
        "SIGNING_KEYSTORE_PASSWORD" to "store-secret-value",
        "SIGNING_KEY_ALIAS" to "beware-of-sugar",
        "SIGNING_KEY_PASSWORD" to "key-secret-value",
    )

    private fun envOf(values: Map<String, String>): (String) -> String? = { values[it] }

    @Test
    fun `resolves when every variable is present`() {
        val config = releaseSigningFrom(envOf(complete))
        assertEquals("a2V5c3RvcmU=", config?.keystoreBase64)
        assertEquals("beware-of-sugar", config?.keyAlias)
    }

    @Test
    fun `an unconfigured environment yields no signing config`() {
        // The normal local build. It must not fail, and it must not sign - debug is untouched.
        assertNull(releaseSigningFrom(envOf(emptyMap())))
    }

    @Test
    fun `blank values count as absent`() {
        val blank = complete.mapValues { "" }
        assertNull(releaseSigningFrom(envOf(blank)))
    }

    @Test
    fun `a partially configured environment fails instead of guessing`() {
        complete.keys.forEach { missing ->
            val partial = complete - missing
            val error = runCatching { releaseSigningFrom(envOf(partial)) }.exceptionOrNull()
            assertTrue("expected failure when $missing is absent", error != null)
            assertTrue(
                "the message must name the missing variable, was: ${error!!.message}",
                error.message.orEmpty().contains(missing),
            )
        }
    }

    @Test
    fun `no failure message ever contains a secret value`() {
        val secrets = listOf("store-secret-value", "key-secret-value", "a2V5c3RvcmU=")
        complete.keys.forEach { missing ->
            val message = runCatching { releaseSigningFrom(envOf(complete - missing)) }
                .exceptionOrNull()!!.message.orEmpty()
            secrets.forEach { secret ->
                assertTrue(
                    "message leaked a secret value when $missing was absent: $message",
                    !message.contains(secret),
                )
            }
        }
    }

    @Test
    fun `the config does not expose secrets through toString`() {
        // Gradle prints objects in stack traces and --info logs. A data class would print every
        // field, so this type must not.
        val rendered = releaseSigningFrom(envOf(complete)).toString()
        listOf("store-secret-value", "key-secret-value", "a2V5c3RvcmU=").forEach {
            assertTrue("toString leaked a secret: $rendered", !rendered.contains(it))
        }
    }
}
