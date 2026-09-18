package dev.isachivka.agtermremote.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **What the shipping app actually hands the settings screen** — read out of `MainActivity`, because
 * that is the only place it exists.
 *
 * ### Why a source read and not a composition
 *
 * `LaptopSettingsTest` pins the rule, `SettingsTest` pins the screen. Both build their own
 * composition, and `SettingsTest` re-implements the gate in a private helper — so between them they
 * establish that *if* the app passes `phoneSection` only for an unusable key, a healthy phone never
 * sees the destructive control. Neither of them can say whether the app does.
 *
 * It does not, and nothing noticed: `MainActivity` was mutated to pass `phoneSection`
 * unconditionally — putting **Replace the key** on every healthy phone's settings screen — and the
 * whole unit suite plus all seven instrumented settings tests stayed green. That is the third time
 * this project has shipped an object that was pinned and a wiring that was not, which is why the
 * Swift half of the same change reads `main.swift` in `OnboardingTests.theAppSuppliesItFromTheStore`.
 * This is the Android half of that pattern.
 *
 * `src/main/java` is declared as a unit-test input in `app/build.gradle.kts`, so editing
 * `MainActivity` re-runs this. Without that the task is UP-TO-DATE and a test that does not run
 * reports success.
 */
class SettingsWiringTest {

    // The unit-test working directory is the module directory.
    private val mainActivity =
        File(File(".").absoluteFile.normalize(), "src/main/java/dev/isachivka/agtermremote/MainActivity.kt")

    private val source: String by lazy { mainActivity.readText() }

    /** Whitespace collapsed, so that reformatting the call does not decide whether this passes. */
    private val dense: String by lazy { source.replace(Regex("""\s+"""), "") }

    /**
     * The argument as written, from `phoneSection =` up to the argument that follows it.
     *
     * Read out of the text rather than matched with one big pattern: a pattern that fails to match
     * has to be told apart from a wiring that is wrong, and this way the two produce different
     * failures.
     */
    private fun argument(name: String, followedBy: String): String {
        val start = dense.indexOf("$name=")
        assertTrue("MainActivity passes no $name to SettingsScreen at all", start >= 0)
        val end = dense.indexOf(followedBy, start)
        assertTrue("the argument after $name is no longer $followedBy - this test needs updating", end > start)
        return dense.substring(start + name.length + 1, end).trimEnd(',')
    }

    @Test
    fun `the file this reads is actually there`() {
        // Otherwise every assertion below is about an empty string, which is the failure shape this
        // whole file exists to break.
        assertTrue("no MainActivity.kt at ${mainActivity.path}", mainActivity.isFile)
        assertTrue("MainActivity does not call SettingsScreen", dense.contains("SettingsScreen("))
    }

    /**
     * **The destructive control is offered to a phone whose key will not sign, and to no other.**
     *
     * Not disabled and not scrolled off: `phoneSection` is null, so the section and its heading are
     * not composed. This is the one line in the shipping app that decides it.
     */
    @Test
    fun `the key replacement is wired to the unusable-key verdict`() {
        assertEquals(
            "MainActivity hands SettingsScreen a phoneSection that is not conditional on " +
                "keyIsUnusable, so every healthy phone is offered \"Replace the key\" - the one " +
                "control in this app that destroys a pairing",
            "if(laptop.keyIsUnusable)({PhoneKeySection(settings=laptop)})elsenull",
            argument("phoneSection", "styledScreen"),
        )
    }

    /**
     * And the keystore is named at exactly one point in the application.
     *
     * `LaptopSettings` takes its two destructive powers as lambdas so that a JVM test can drive them;
     * that seam is worth nothing if the real screen is built with something else behind it.
     */
    @Test
    fun `the real keystore is what the settings screen is built with`() {
        assertTrue(
            "the settings screen is not built on the real keystore, so everything the unit tests " +
                "prove about discarding an identity is a statement about a lambda",
            dense.contains("discardIdentity=PhoneIdentity::clear"),
        )
        assertTrue(
            "the unusable-key verdict is not read from PhoneIdentity",
            dense.contains("keyState=PhoneIdentity::signingState"),
        )
    }
}
