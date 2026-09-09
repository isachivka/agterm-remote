package dev.isachivka.bewareofsugar.update

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One GitHub release, reduced to what this app uses.
 *
 * @param version null when the tag is not a version this project could have produced — see
 * [AppVersion.parse]. Kept as a field rather than dropped, so "there is a newest release and we
 * cannot read its tag" stays distinguishable from "there are no releases".
 */
data class Release(
    val tag: String,
    val version: AppVersion?,
    val title: String,
    val notes: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

/**
 * @param id the API asset id. Iteration 3 downloads through
 * `GET /repos/{owner}/{repo}/releases/assets/{id}`, because a private repo's
 * `browser_download_url` expects a browser session.
 */
data class ReleaseAsset(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
)

/**
 * The asset this app would install: the `.apk`.
 *
 * Defined once and used by both the screen and the download, so the size the owner is shown before
 * tapping is necessarily the size of the file that then arrives. Two independent opinions about
 * "which asset" would be a quiet way to display one number and fetch another.
 *
 * **Chosen by name, not by size.** "The biggest attached file" is a proxy for "the `.apk`" that is
 * correct only while exactly one file is attached — which is true of this repository today and is not
 * a property anyone has promised. Attach a symbols archive, a mapping file or a bundle that happens
 * to be larger, and the updater would quietly pick that instead and hand a non-`.apk` to the
 * installer, which rejects it with an error the owner can do nothing about.
 *
 * The size fallback survives only for the case where nothing is named `.apk` at all. That means a
 * release built by some process this one does not know about, and the biggest file is then the least
 * bad guess; if it turns out not to be installable, Android says so and nothing has been replaced.
 */
val Release.updateAsset: ReleaseAsset?
    get() = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }.maxByOrNull { it.sizeBytes }
        ?: assets.maxByOrNull { it.sizeBytes }

/**
 * Parses GitHub's releases JSON.
 *
 * **The parser is `org.json`, and that is a decision, not an inheritance.** It is in the Android
 * platform, so it costs the `.apk` nothing, and its accessor style is null-safe by construction:
 * `optString`, `optJSONArray` and friends return a default rather than throwing when a field is
 * absent, so a field GitHub adds is ignored and a field GitHub stops sending degrades to a default.
 * That is exactly the tolerance this needs.
 *
 * kotlinx.serialization was the alternative and was rejected on merit: it would add a Gradle plugin
 * and a runtime dependency, and it would need `ignoreUnknownKeys` plus nullable fields to reach the
 * same tolerance this gets for free — to read five fields off one endpoint.
 *
 * The one cost of `org.json` is that the JVM unit tests need a real implementation on their
 * classpath, because the android.jar the tests compile against is stubs that throw. That is a
 * test-only dependency, and to make sure the two implementations agree about *our* usage, the same
 * fixture is parsed again on a device in `ReleaseParsingInstrumentedTest` against the platform's own
 * `org.json`.
 */
object ReleaseParser {

    /**
     * @return the releases in the order GitHub returned them (newest first), skipping any entry that
     * is not a JSON object. Returns an empty list rather than throwing on malformed input: a broken
     * response means "no update to offer", never a crash on launch.
     */
    fun parseReleases(body: String): List<Release> {
        val array = try {
            JSONArray(body)
        } catch (e: JSONException) {
            return emptyList()
        }
        return (0 until array.length())
            .mapNotNull { index -> array.optJSONObject(index) }
            .map(::parseRelease)
    }

    private fun parseRelease(json: JSONObject): Release {
        val tag = json.optString("tag_name")
        // GitHub sends name and body as null for a release published without either, and optString
        // turns a JSON null into the string "null" - so they are read explicitly.
        val title = json.optStringOrNull("name")?.takeIf { it.isNotBlank() } ?: tag
        return Release(
            tag = tag,
            version = AppVersion.parse(tag),
            title = title,
            notes = json.optStringOrNull("body").orEmpty(),
            draft = json.optBoolean("draft", false),
            prerelease = json.optBoolean("prerelease", false),
            assets = json.optJSONArray("assets").parseAssets(),
        )
    }

    private fun JSONArray?.parseAssets(): List<ReleaseAsset> {
        if (this == null) return emptyList()
        return (0 until length())
            .mapNotNull { index -> optJSONObject(index) }
            .mapNotNull { asset ->
                val id = asset.optLong("id", 0L)
                val name = asset.optStringOrNull("name")
                // An asset with no id cannot be downloaded, so it is not an asset as far as this app
                // is concerned.
                if (id <= 0L || name.isNullOrBlank()) null
                else ReleaseAsset(id = id, name = name, sizeBytes = asset.optLong("size", 0L))
            }
    }

    /** `optString` returns the literal "null" for a JSON null; this returns Kotlin's. */
    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
}
