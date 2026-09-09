import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *
 * This is arithmetic that silently produces a wrong-but-plausible integer when it goes wrong, and a
 * versionCode that is wrong or goes backwards ends the section 2 user story at the final tap, with the
 * phone flatly refusing the install. So it is tested rather than trusted.
 */
class VersionCodeTest {

    @Test
    fun `maps major minor patch into the documented formula`() {
        assertEquals(10203, versionCodeOf("1.2.3"))
    }

    @Test
    fun `first release maps to a non-zero code`() {
        assertEquals(10000, versionCodeOf("1.0.0"))
    }

    @Test
    fun `strictly increases across successive versions`() {
        val ordered = listOf("0.1.0", "0.1.1", "0.2.0", "0.9.99", "1.0.0", "1.2.3", "2.0.0")
        val codes = ordered.map { versionCodeOf(it) }
        codes.zipWithNext { lower, higher ->
            assertTrue("$codes must be strictly increasing", higher > lower)
        }
    }

    @Test
    fun `a patch bump never overtakes a minor bump`() {
        assertTrue(versionCodeOf("0.2.0") > versionCodeOf("0.1.99"))
        assertTrue(versionCodeOf("1.0.0") > versionCodeOf("0.99.99"))
    }

    // --- The ceiling. Breaching it must fail the build loudly, never wrap silently. ---

    @Test
    fun `minor reaching one hundred fails instead of wrapping`() {
        val e = runCatching { versionCodeOf("1.100.0") }.exceptionOrNull()
        assertTrue("expected a failure, got a value", e != null)
        // Without this guard 1.100.0 would produce 20000 - a plausible-looking integer that silently
        // collides with 2.0.0. The message has to say what to do, not just that something is wrong.
        val message = e!!.message.orEmpty()
        assertTrue("message must name the offending version, was: $message", message.contains("1.100.0"))
        assertTrue("message must explain the limit, was: $message", message.contains("100"))
    }

    @Test
    fun `patch reaching one hundred fails instead of wrapping`() {
        val e = runCatching { versionCodeOf("1.2.100") }.exceptionOrNull()
        assertTrue("expected a failure, got a value", e != null)
        assertTrue(e!!.message.orEmpty().contains("1.2.100"))
    }

    @Test
    fun `the value just below the ceiling is still allowed`() {
        assertEquals(19999, versionCodeOf("1.99.99"))
    }

    @Test
    fun `a malformed version fails rather than guessing`() {
        listOf("1.2", "127.0.0.1", "", "v1.2.3", "1.2.x").forEach { bad ->
            assertTrue(
                "expected \"$bad\" to be rejected",
                runCatching { versionCodeOf(bad) }.exceptionOrNull() != null,
            )
        }
    }
}
