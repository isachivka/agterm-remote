package dev.isachivka.agtermremote.wire

import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared vectors are reachable from this module, and they are read from the repository root.
 *
 * ### What this is for, before the decoder exists
 *
 * `wire/enroll-payload-vectors.json` and `wire/enroll-payload-reject-vectors.json` are the contract
 * between the Go encoder in `bridge/internal/enroll` and the Kotlin decoder that will read the QR
 * code. Two hand-written implementations of one wire format cannot be compiled against each other,
 * and nothing else in this build connects them - the bytes in those two files are the connection.
 *
 * The decoder is not written yet. This file is the harness it will be written into: it proves the
 * path resolves, the property is set, and both files parse - so the first test that decodes a vector
 * fails because the decoder is wrong, not because the plumbing is.
 *
 * ### Read from the ROOT. Never a copy.
 *
 * A copy inside `app/` is how the two sides drift: the copy is what the Kotlin test pins, the
 * original is what the Go test pins, and nothing notices they have diverged until somebody is
 * holding a phone that will not pair. `wire/README.md` states the rule and
 * `scripts/check-wire-vectors-consumed.sh` enforces both halves of it - that these filenames are
 * referenced from the Android tests at all, and that no copy of them appears under this module.
 *
 * `org.json` is the same parser the app uses for the bridge protocol, supplied to JVM tests as a
 * real implementation because the `android.jar` unit tests compile against is stubs that throw.
 */
class WireVectorsTest {

    /**
     * Set by `app/build.gradle.kts` from `rootProject`, which is the repository root for a local
     * build and for CI alike.
     *
     * `error(...)` rather than a default or a skip. A test that quietly does nothing when its
     * fixture is missing is the exact failure the accounting in this repository's guards exists to
     * prevent: it reports success by not running.
     */
    private val wireDir = File(System.getProperty("wire.dir") ?: error("wire.dir is not set"))

    private val accept = wireDir.resolve("enroll-payload-vectors.json")
    private val reject = wireDir.resolve("enroll-payload-reject-vectors.json")

    @Test
    fun `the accept vectors are readable from the repository root`() {
        assertTrue("no vectors at ${accept.absolutePath}", accept.isFile)
        val vectors = JSONArray(accept.readText())
        assertTrue("the accept vectors are empty", vectors.length() > 0)
    }

    @Test
    fun `the reject vectors are readable from the repository root`() {
        assertTrue("no vectors at ${reject.absolutePath}", reject.isFile)
        val vectors = JSONArray(reject.readText())
        assertTrue("the reject vectors are empty", vectors.length() > 0)
    }

    /**
     * **The half that faces the camera.**
     *
     * The accept vectors cannot tell a decoder that shrugs at a version it does not know from one
     * that refuses it, which is why the reject file exists at all. Asserting here that both are
     * present and non-empty means a decoder written against this harness cannot be written against
     * the accept half alone.
     */
    @Test
    fun `the vectors are read from the root and not from a copy inside the module`() {
        val module = File(".").absoluteFile.normalize()
        assertTrue(
            "the wire directory resolved to ${wireDir.absolutePath}, which is inside the app module",
            !wireDir.absolutePath.startsWith(module.absolutePath + File.separator),
        )
    }
}
