package dev.isachivka.bewareofsugar.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The token must never reach a log line, an exception message or a crash report. The mechanism is
 * that nothing which holds it prints it, so this test asserts that mechanism directly rather than
 * trusting that nobody will ever interpolate one of these types into a string.
 *
 * The constant below is deliberately not shaped like a real GitHub token. Nothing in this repo —
 * tests, fixtures or CI — carries a value that someone could later mistake for a real one and
 * "helpfully" replace.
 */
class TokenRedactionTest {

    private val secret = "not-a-real-token"
    private val token = GitHubToken(secret)

    @Test
    fun `the token does not print itself`() {
        assertFalse(token.toString().contains(secret))
        assertTrue(token.toString().contains("len=${secret.length}"))
    }

    @Test
    fun `string interpolation does not leak it`() {
        assertFalse("token is $token".contains(secret))
    }

    @Test
    fun `the stored-token wrapper does not print it`() {
        val stored: StoredToken = StoredToken.Present(token, validatedAtEpochSeconds = 1_700_000_000L)
        assertFalse(stored.toString().contains(secret))
    }

    @Test
    fun `the decryption failure carries no plaintext`() {
        val failure = TokenDecryptionFailed("the key could not be used right now", permanent = false)
        assertFalse(failure.message.orEmpty().contains(secret))
    }
}
