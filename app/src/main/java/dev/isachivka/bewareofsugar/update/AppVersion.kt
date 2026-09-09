package dev.isachivka.bewareofsugar.update

/**
 * A version of this app, as three numbers that can be compared.
 *
 * The semantics are `buildSrc/src/main/kotlin/VersionCode.kt`'s, deliberately: that file is where
 * this project decides what a version *is*, and a second, looser opinion here is how an updater
 * quietly starts offering things it cannot install. It cannot be imported — `buildSrc` is build
 * logic and is not on the app's classpath — so it is mirrored, and the mirroring is the reason both
 * the strict shape and the ceiling appear again below.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int = compareValuesBy(
        this,
        other,
        AppVersion::major,
        AppVersion::minor,
        AppVersion::patch,
    )

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /** Matches `versionCodeOf`'s: exactly MAJOR.MINOR.PATCH, nothing appended. */
        private val SEMVER = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

        /** From `versionCodeOf`: at 100 a component carries into the next column. */
        private const val COMPONENT_CEILING = 100

        /**
         * Parses a release tag or a `versionName`, or returns null when it is not a version this
         * project could have produced.
         *
         * Null rather than an exception, because the caller is an update check: an unparseable tag
         * means "no update to offer", not a crash on launch. Null covers, deliberately:
         *
         *  - anything that is not exactly MAJOR.MINOR.PATCH, including pre-releases like
         *    `v1.0.0-rc1` and build metadata. Pre-release channels are out of scope for REQ-0003,
         *    and silently treating `-rc1` as a stable release is how someone ends up on one.
         *  - a component at or above 100. `versionCodeOf` refuses to build such a version because
         *    its versionCode would collide with a later release; an .apk carrying a colliding code
         *    is one Android will refuse to install, so offering it would fail at the final tap on
         *    the owner's phone rather than here.
         */
        fun parse(raw: String): AppVersion? {
            val trimmed = raw.trim().removePrefix("v").removePrefix("V")
            val match = SEMVER.matchEntire(trimmed) ?: return null
            val (major, minor, patch) = match.destructured.toList().map(String::toInt)
            if (minor >= COMPONENT_CEILING || patch >= COMPONENT_CEILING) return null
            return AppVersion(major, minor, patch)
        }
    }
}
