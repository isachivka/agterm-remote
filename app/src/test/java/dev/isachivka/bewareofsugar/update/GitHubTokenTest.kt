package dev.isachivka.bewareofsugar.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Trimming is the whole point of [GitHubToken.fromInput]: a token pasted on a phone arrives with a
 * trailing newline more often than not, and REQ-0003 requires the whitespace gone before the value
 * is used anywhere.
 */
class GitHubTokenTest {

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("abc123", GitHubToken.fromInput("  abc123\n")?.value)
    }

    @Test
    fun `a trailing newline alone is trimmed`() {
        assertEquals("abc123", GitHubToken.fromInput("abc123\n")?.value)
    }

    @Test
    fun `whitespace only is not a token`() {
        assertNull(GitHubToken.fromInput("   \n\t "))
    }

    @Test
    fun `empty input is not a token`() {
        assertNull(GitHubToken.fromInput(""))
    }

    @Test
    fun `inner characters are left alone`() {
        assertEquals("a b", GitHubToken.fromInput(" a b ")?.value)
    }
}
