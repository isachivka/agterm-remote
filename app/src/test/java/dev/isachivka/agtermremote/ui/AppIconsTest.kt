package dev.isachivka.agtermremote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **Every bundled glyph is drawn by a screen, and every glyph a screen draws is bundled.**
 *
 * ### What this replaces, and why a count was not enough
 *
 * `AppIconsTest` on the device pinned `AppIcons.all.size` at 38, with a comment explaining each bump.
 * That is a ratchet against *adding* a glyph carelessly, and it is worth having. It is no defence at
 * all against *removing a screen*: when the launcher, the updater, the limits tile and the car screen
 * went, twenty-two glyphs lost their last caller, the count still said 38, and every one of them kept
 * shipping inside the `.apk`. One was a third-party logo referenced by nothing.
 *
 * A number cannot notice that, because the number does not know what the app draws. This does: it
 * reads the app's own sources for `AppIcons.X` and `@drawable/x`, and compares three sets that must
 * be identical - what is declared, what is referenced, and what is on disk.
 *
 * ### Why it reads source rather than reflecting
 *
 * A reflective test would find every declaration and no callers: the interesting fact is not what
 * `AppIcons` contains, it is whether anything still uses it. That fact only exists in the call sites,
 * so the call sites are what is read.
 *
 * `AppIconGrid` is excluded from the caller scan on purpose. It draws `AppIcons.all` by definition,
 * so counting it as a caller would make every glyph permanently "used" - which is precisely how the
 * orphans survived a deletion of the screens that drew them.
 *
 * The directories read here are declared as task inputs in `app/build.gradle.kts`; `src/main/res`
 * already was, and `src/main/java` is added for this test. Without that the task is UP-TO-DATE after
 * a screen is deleted, and this reports success by not running.
 */
class AppIconsTest {

    // The unit-test working directory is the module directory.
    private val module = File(".").absoluteFile.normalize()
    private val iconsFile = File(module, "src/main/java/dev/isachivka/agtermremote/ui/AppIcons.kt")
    private val drawableDir = File(module, "src/main/res/drawable")

    /** `val Foo = R.drawable.ic_foo` -> "Foo" to "ic_foo". */
    private val declared: Map<String, String> =
        Regex("""val (\w+) = R\.drawable\.(\w+)""")
            .findAll(iconsFile.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** Everything the app itself draws, from the call sites rather than from the declarations. */
    private val referenced: Set<String> by lazy {
        val excluded = setOf("AppIcons.kt", "AppIconGrid.kt")
        val sources = File(module, "src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { it.name !in excluded }
            .filterNot { it.path.contains("${File.separator}res${File.separator}drawable") }
        val names = mutableSetOf<String>()
        sources.forEach { file ->
            val text = file.readText()
            declared.forEach { (name, drawable) ->
                if (Regex("""AppIcons\.$name\b""").containsMatchIn(text)) names += name
                if (Regex("""[@R][.]?drawable[./]$drawable\b""").containsMatchIn(text)) names += name
            }
        }
        names
    }

    @Test
    fun `the file the scan reads is actually there`() {
        // Otherwise every set below is empty and every assertion passes vacuously - which is the
        // failure shape this whole file exists to break.
        assertTrue("no AppIcons.kt at ${iconsFile.path}", iconsFile.isFile)
        assertTrue("no drawables at ${drawableDir.path}", drawableDir.isDirectory)
        assertTrue("no declarations parsed out of AppIcons.kt", declared.isNotEmpty())
        assertTrue("no references found in src/main - is the scan reading anything?", referenced.isNotEmpty())
    }

    @Test
    fun `every declared glyph is drawn by a screen`() {
        val orphans = (declared.keys - referenced).sorted()
        assertEquals(
            "these glyphs are declared and shipped but nothing draws them; delete the declaration " +
                "and the drawable together, or the .apk carries artwork it never shows",
            emptyList<String>(),
            orphans,
        )
    }

    @Test
    fun `every declared glyph has a drawable on disk`() {
        val missing = declared.values.filterNot { File(drawableDir, "$it.xml").isFile }.sorted()
        assertEquals("declared but not bundled", emptyList<String>(), missing)
    }

    @Test
    fun `every bundled drawable is declared`() {
        val onDisk = drawableDir.listFiles()!!.filter { it.extension == "xml" }.map { it.nameWithoutExtension }
        val undeclared = (onDisk - declared.values.toSet()).sorted()
        // A drawable nothing declares is a file in the .apk that no code can name. It is how
        // ic_mic, ic_keyboard and ic_view_list survived the deletion of the car screen.
        assertEquals("bundled but declared nowhere", emptyList<String>(), undeclared)
    }

    @Test
    fun `the preview grid lists every glyph, once, and no two names share a drawable`() {
        val grid = Regex(""""(\w+)" to (\w+),""")
            .findAll(iconsFile.readText())
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        assertEquals(
            "AppIcons.all must list every declared glyph - the grid exists to be looked at, and one " +
                "it does not draw is one nobody checks",
            declared.keys.sorted(),
            grid.map { it.second }.sorted(),
        )
        assertEquals("glyph names must be unique", grid.size, grid.map { it.first }.toSet().size)
        // Two names pointing at one drawable is the copy-paste mistake this catches, and it would
        // otherwise show up as the wrong icon on one screen and nowhere else.
        assertEquals("drawables must be distinct", grid.size, grid.map { it.second }.toSet().size)
    }
}
