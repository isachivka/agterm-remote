/**
 * Derives an Android `versionCode` from a semver `versionName`.
 *
 *     versionCode = major * 10000 + minor * 100 + patch
 *
 * `versionName` is the single source of truth — release-please maintains it, and this is a pure
 * function of it. Nothing has to be kept in sync and nothing can be forgotten at release time.
 *
 * This lives in `buildSrc` rather than in `app/build.gradle.kts` for one reason: it is arithmetic that
 * fails by producing a wrong-but-plausible integer, so it needs real unit tests, and build logic is
 * only testable if it lives somewhere a test can call it. See `VersionCodeTest`.
 */

/** Highest permitted value, exclusive, for the minor and patch components. */
private const val COMPONENT_CEILING = 100

private val SEMVER = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

fun versionCodeOf(versionName: String): Int {
    val match = SEMVER.matchEntire(versionName.trim())
        ?: error(
            "Cannot derive a versionCode from versionName \"$versionName\": expected exactly " +
                "MAJOR.MINOR.PATCH with numeric components, e.g. \"1.2.3\". " +
                "versionName is set in app/build.gradle.kts and is maintained by release-please.",
        )

    val (major, minor, patch) = match.destructured.toList().map(String::toInt)

    // A component at or above 100 would carry into the next column and produce a silently wrong
    // code - 1.100.0 would collide with 2.0.0. Android refuses to install an .apk whose versionCode
    // is not greater than the installed one, so a wrapped value ends the update at the final
    // tap, on the owner's phone, with no explanation. Fail here instead, where it is cheap.
    require(minor < COMPONENT_CEILING && patch < COMPONENT_CEILING) {
        "versionName \"$versionName\" breaches the versionCode ceiling: minor and patch must each " +
            "stay below $COMPONENT_CEILING. Continuing would produce a versionCode that " +
            "collides with a later release and would make the app un-updatable on a real device. " +
            "Raise the next component instead - after 1.99.x comes 2.0.0, not 1.100.0."
    }

    return major * 10000 + minor * 100 + patch
}
