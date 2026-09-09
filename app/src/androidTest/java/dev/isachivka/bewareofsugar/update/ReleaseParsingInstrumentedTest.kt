package dev.isachivka.bewareofsugar.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The same parsing, on the platform's own `org.json`.
 *
 * This exists because of a gap the unit tests cannot close by themselves: they run against the
 * `org.json:json` artifact, while the app runs against Android's implementation of the same API.
 * Those are different codebases. Every claim `ReleaseParserTest` makes about tolerance — a null
 * body, a missing field, an unknown field, malformed input — is therefore re-made here, on a device,
 * against the implementation that actually ships.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseParsingInstrumentedTest {

    @Test
    fun aRealisticPayloadParsesTheSameOnTheDevice() {
        val body = """
            [{"tag_name":"v0.3.0","name":"v0.3.0",
              "body":"## What's Changed\n* feat: in-app updates\n",
              "draft":false,"prerelease":false,
              "assets":[{"id":490736218,"name":"beware-of-sugar-0.3.0.apk","size":5242880}]}]
        """.trimIndent()

        val release = ReleaseParser.parseReleases(body).single()

        assertEquals("v0.3.0", release.tag)
        assertEquals(AppVersion(0, 3, 0), release.version)
        assertTrue(release.notes.contains("in-app updates"))
        assertEquals(490736218L, release.assets.single().id)
    }

    @Test
    fun aJsonNullDoesNotBecomeTheStringNullOnTheDevice() {
        // The specific behaviour the JVM and platform implementations could differ on, and the one
        // that would show the owner the word "null" as their release notes.
        val release = ReleaseParser.parseReleases("""[{"tag_name":"v0.3.0","name":null,"body":null}]""").single()

        assertEquals("v0.3.0", release.title)
        assertEquals("", release.notes)
    }

    @Test
    fun omittedAndUnknownFieldsBehaveTheSameOnTheDevice() {
        val sparse = ReleaseParser.parseReleases("""[{"tag_name":"v0.3.0","invented_later":{"a":1}}]""").single()

        assertEquals("v0.3.0", sparse.title)
        assertEquals("", sparse.notes)
        assertTrue(sparse.assets.isEmpty())
    }

    @Test
    fun malformedJsonIsNoReleasesOnTheDeviceToo() {
        // This parse runs during the launch check, so throwing here would mean failing to open.
        listOf("", "not json at all", "{", """{"message":"Not Found"}""", "null")
            .forEach { assertTrue(it, ReleaseParser.parseReleases(it).isEmpty()) }
    }
}
