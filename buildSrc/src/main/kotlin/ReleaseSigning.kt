/**
 * Release signing material, resolved from the environment.
 *
 * Credentials come from environment variables only — never from a file inside this repo, and never
 * from `gradle.properties`. In CI they are the four repository secrets; locally they are exported by
 * whoever is doing a signed build. Nothing here is ever written to a tracked file.
 */

/** The four variables that must be present together, or not at all. */
private val REQUIRED = listOf(
    "SIGNING_KEYSTORE_BASE64",
    "SIGNING_KEYSTORE_PASSWORD",
    "SIGNING_KEY_ALIAS",
    "SIGNING_KEY_PASSWORD",
)

class ReleaseSigningConfig(
    val keystoreBase64: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
) {
    /**
     * Deliberately not a `data class`.
     *
     * Gradle prints objects into stack traces and `--info` output, and a data class's generated
     * `toString()` would print every field — including two passwords and the keystore itself. This
     * renders nothing but the type name.
     */
    override fun toString(): String = "ReleaseSigningConfig(<redacted>)"
}

/**
 * Returns the signing configuration, or `null` when the environment carries none of it.
 *
 * - **All four present** → a config. This is CI, and any local signed build.
 * - **None present** → `null`. This is the ordinary local build: it must not fail and must not sign.
 *   Debug builds never come near this.
 * - **Some present** → an error. A half-configured environment is the dangerous case: silently
 *   falling back to no signing, or to a debug key, produces an `.apk` that looks fine and cannot be
 *   installed over a real release. Mismatched keys are the default outcome, not
 *   an unlikely one, so this fails loudly instead of guessing.
 *
 * The failure message names the **variables** that are missing and never the values of the ones that
 * are present — see `ReleaseSigningTest`.
 */
fun releaseSigningFrom(env: (String) -> String?): ReleaseSigningConfig? {
    val present = REQUIRED.filter { !env(it).isNullOrBlank() }
    if (present.isEmpty()) return null

    val missing = REQUIRED - present.toSet()
    require(missing.isEmpty()) {
        "Release signing is partially configured: ${missing.joinToString(", ")} " +
            "${if (missing.size == 1) "is" else "are"} missing or empty, while " +
            "${present.joinToString(", ")} ${if (present.size == 1) "is" else "are"} set. " +
            "All four must be provided together. Set them from the repository secrets, or unset them " +
            "all for an unsigned local build. (Values are never printed.)"
    }

    return ReleaseSigningConfig(
        keystoreBase64 = env("SIGNING_KEYSTORE_BASE64")!!,
        storePassword = env("SIGNING_KEYSTORE_PASSWORD")!!,
        keyAlias = env("SIGNING_KEY_ALIAS")!!,
        keyPassword = env("SIGNING_KEY_PASSWORD")!!,
    )
}
