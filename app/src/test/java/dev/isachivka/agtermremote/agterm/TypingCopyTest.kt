package dev.isachivka.agtermremote.agterm

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **No typing status string may echo what the owner typed.**
 *
 * The status line used to read `Sent “%1$s” — waiting for your laptop`. The string naming the
 * password-prompt case was the same string that printed the characters back, and `NoChange` is the
 * state a password prompt lands in — so a password the terminal had deliberately hidden was rendered
 * in plain sight, precisely in the case the copy anticipated. The terminal hid it and the app put it
 * back.
 *
 * There is no safe conditional version. Nothing on the phone, and nothing in agterm's control channel,
 * can tell a password prompt from any other prompt. So the honest answer is not to echo at all, and
 * the payload was removed from the states rather than merely left unrendered — see [TypingState.Sent].
 */
class TypingCopyTest {

    private val strings: String by lazy {
        listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        ).first { it.isFile }.readText()
    }

    private fun valueOf(name: String): String =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(strings)?.groupValues?.get(1)
            ?: error("no string named $name")

    private val statusStrings =
        listOf("agterm_typing_sent", "agterm_typing_no_change", "agterm_typing_failed")

    @Test
    fun `no typing status string takes a format argument`() {
        statusStrings.forEach { name ->
            val text = valueOf(name)
            assertTrue(
                "$name interpolates a value: \"$text\". The only value it could carry is what the " +
                    "owner typed, and this is the copy that names the password-prompt case.",
                !text.contains("%"),
            )
        }
    }

    /**
     * The control: this check can see a format argument when one is present.
     *
     * A regex that silently matched nothing, or a lookup that returned an empty string, would pass the
     * assertion above for free — which is how a copy test rots into a comment.
     */
    @Test
    fun `the check can detect a format argument, and is reading real strings`() {
        // agterm_fit_a11y carries the column count for a screen reader, so it is a live example of a
        // string that SHOULD interpolate. It replaced agterm_fit_to_phone when the fit control became
        // an icon toggle - and this control caught that rename by failing, which is what it is for.
        assertTrue(
            "the control failed: no string in this file uses a format argument, so the check above " +
                "proves nothing",
            valueOf("agterm_fit_a11y").contains("%"),
        )
        statusStrings.forEach {
            assertTrue("$it resolved to an empty string; the lookup is not reading the file",
                valueOf(it).isNotEmpty())
        }
    }

    /** The states themselves must not carry it either, which is where the removal actually happened. */
    @Test
    fun `only the draft the owner is looking at is held in a typing state`() {
        val fields = TypingState::class.java.declaredClasses.flatMap { sub ->
            // Enum constants are cases, not payloads; the synthetic ones are the compiler's. That a
            // case cannot GROW a payload is asserted below rather than assumed here.
            sub.declaredFields
                .filterNot { it.isEnumConstant || it.isSynthetic }
                .map { "${sub.simpleName}.${it.name}" }
        }.filterNot { it.contains("INSTANCE") || it.contains("stable") }

        assertTrue("the walk found no states at all", fields.isNotEmpty())
        assertTrue(
            "a typing state carries something beyond the draft and its notice: $fields. Sent, " +
                "NoChange and Failed held what was typed until 2026-07-29, which is how a password " +
                "reached the screen.",
            fields.sorted() == listOf("Composing.notice"),
        )

        // **`notice` arrived on 2026-08-09 and it is a CASE, never text.** A
        // refused send puts the owner's words back in Composing, and the sentence explaining why is
        // chosen from a closed set in the resource file. Two fields, so a message can never be
        // assembled out of what they typed - which is this file's whole subject.
        assertTrue(
            "Notice stopped being an enum, so it can carry text now, and the first text it will " +
                "carry is the draft",
            TypingState.Notice::class.java.isEnum,
        )
    }
}
