package dev.isachivka.bewareofsugar.update.play

/**
 * Turns the `versionCode` Play reports back into the version *name* a person recognises — REQ-0050.
 *
 * Play's update API says only what the newest `versionCode` is; it never carries a name. This app's
 * codes are not opaque counters, though — `buildSrc/src/main/kotlin/VersionCode.kt` derives every
 * one of them from the semver string as
 *
 *     versionCode = major * 10000 + minor * 100 + patch
 *
 * so the mapping is invertible, and the owner can be shown `0.34.0` instead of `3400`. This is the
 * only place in the app that knows that, and it is arithmetic that fails by producing a
 * wrong-but-plausible string, which is why it is a function with tests rather than a format call at
 * the call site.
 *
 * It cannot import the real one: `buildSrc` is build logic and is not on the app's classpath.
 * `VersionCodeNameTest` pins both directions against the same worked examples so the two cannot
 * drift apart silently.
 *
 * ## It is total on every non-negative code, and that was not obvious
 *
 * The first version of this function also refused codes "outside the scheme" — ones whose minor or
 * patch component breached the ceiling `versionCodeOf` enforces. That branch was unreachable, and
 * two tests written to cover it failed by naming perfectly ordinary versions: 199 is `0.1.99`, and
 * `minor` and `patch` are `(code / 100) % 100` and `code % 100`, both below 100 by construction. No
 * non-negative integer can breach a ceiling that the arithmetic imposes on the way out.
 *
 * The guard is gone rather than kept "just in case", because a branch no input can reach is a branch
 * no test can honestly cover.
 *
 * @return the version name, or null for a negative code — which Play does not send, and which has no
 * name to give.
 */
fun versionNameOfCode(versionCode: Int): String? {
    if (versionCode < 0) return null

    val major = versionCode / 10_000
    val minor = (versionCode / 100) % 100
    val patch = versionCode % 100

    return "$major.$minor.$patch"
}
