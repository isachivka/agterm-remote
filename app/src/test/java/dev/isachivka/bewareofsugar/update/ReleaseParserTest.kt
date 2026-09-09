package dev.isachivka.bewareofsugar.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing GitHub's releases JSON, and above all not crashing on it.
 *
 * The fixtures are shaped like the real payload, cut down to the fields this app reads. The point of
 * most of these is the negative: a field GitHub adds, omits, or sends as null must never take the
 * app down on launch, because this parse happens during the launch check.
 */
class ReleaseParserTest {

    private val realistic = """
        [
          {
            "tag_name": "v0.3.0",
            "name": "v0.3.0",
            "body": "## What's Changed\n* feat: in-app updates by @isachivka\n",
            "draft": false,
            "prerelease": false,
            "assets": [
              {"id": 490736218, "name": "beware-of-sugar-0.3.0.apk", "size": 5242880}
            ]
          },
          {
            "tag_name": "v0.2.0",
            "name": "v0.2.0",
            "body": "older",
            "draft": false,
            "prerelease": false,
            "assets": []
          }
        ]
    """.trimIndent()

    @Test
    fun `reads what the app uses from a realistic payload`() {
        val releases = ReleaseParser.parseReleases(realistic)

        assertEquals(2, releases.size)
        val newest = releases.first()
        assertEquals("v0.3.0", newest.tag)
        assertEquals(AppVersion(0, 3, 0), newest.version)
        assertTrue(newest.notes.contains("in-app updates"))
        assertEquals(1, newest.assets.size)
        assertEquals(490736218L, newest.assets.first().id)
        assertEquals("beware-of-sugar-0.3.0.apk", newest.assets.first().name)
        assertEquals(5242880L, newest.assets.first().sizeBytes)
    }

    @Test
    fun `a field GitHub adds later is ignored`() {
        val withNewField = """
            [{"tag_name":"v0.3.0","name":"n","body":"b","draft":false,"prerelease":false,
              "assets":[],"some_field_invented_in_2027":{"nested":true}}]
        """.trimIndent()
        assertEquals("v0.3.0", ReleaseParser.parseReleases(withNewField).single().tag)
    }

    @Test
    fun `fields GitHub omits fall back rather than throwing`() {
        val sparse = """[{"tag_name":"v0.3.0"}]"""
        val release = ReleaseParser.parseReleases(sparse).single()

        assertEquals("v0.3.0", release.tag)
        // No name: the tag is a better label than an empty line.
        assertEquals("v0.3.0", release.title)
        assertEquals("", release.notes)
        assertTrue(release.assets.isEmpty())
    }

    @Test
    fun `an explicit JSON null does not become the string null`() {
        // optString turns a JSON null into "null", which would show the owner the word null as
        // their release notes.
        val nulls = """[{"tag_name":"v0.3.0","name":null,"body":null,"assets":null}]"""
        val release = ReleaseParser.parseReleases(nulls).single()

        assertEquals("v0.3.0", release.title)
        assertEquals("", release.notes)
        assertTrue(release.assets.isEmpty())
    }

    @Test
    fun `draft and prerelease flags are read`() {
        val flagged = """[{"tag_name":"v9.9.9","draft":true,"prerelease":true}]"""
        val release = ReleaseParser.parseReleases(flagged).single()
        assertTrue(release.draft)
        assertTrue(release.prerelease)
    }

    @Test
    fun `a tag that is not a version parses as a release with no version`() {
        val odd = """[{"tag_name":"nightly"}]"""
        val release = ReleaseParser.parseReleases(odd).single()
        assertEquals("nightly", release.tag)
        assertNull(release.version)
    }

    @Test
    fun `malformed json is no releases rather than a crash`() {
        // This parse runs during the launch check, so the cost of throwing here is the app failing
        // to open.
        listOf("", "not json at all", "{", "{\"message\":\"Not Found\"}", "null", "42")
            .forEach { assertTrue("\"$it\"", ReleaseParser.parseReleases(it).isEmpty()) }
    }

    @Test
    fun `entries that are not objects are skipped rather than fatal`() {
        val mixed = """["a string", 7, {"tag_name":"v0.3.0"}, null]"""
        assertEquals(listOf("v0.3.0"), ReleaseParser.parseReleases(mixed).map { it.tag })
    }

    @Test
    fun `an asset with no id is not offered as something to download`() {
        val broken = """
            [{"tag_name":"v0.3.0","assets":[
              {"name":"no-id.apk"},
              {"id":0,"name":"zero-id.apk"},
              {"id":123,"name":""},
              {"id":456,"name":"good.apk","size":10}
            ]}]
        """.trimIndent()
        val assets = ReleaseParser.parseReleases(broken).single().assets
        assertEquals(1, assets.size)
        assertEquals(456L, assets.single().id)
    }
}
