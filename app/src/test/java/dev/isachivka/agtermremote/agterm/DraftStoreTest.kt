package dev.isachivka.agtermremote.agterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The drafts on disk — REQ-0046. One file per session id, gone when the draft is. */
class DraftStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val one = "11111111-1111-4111-8111-111111111111"
    private val two = "22222222-2222-4222-8222-222222222222"

    private fun store() = DraftStore(File(folder.root, "drafts"))

    @Test
    fun `what was written comes back, per session, across instances`() {
        store().write(one, "git commit -m 'wip")
        store().write(two, "ls")

        assertEquals("git commit -m 'wip", store().read(one))
        assertEquals("ls", store().read(two))
    }

    @Test
    fun `a session with no draft reads as empty and throws nothing`() {
        assertEquals("", store().read(one))
    }

    @Test
    fun `an empty draft removes the file`() {
        store().write(one, "something")
        assertTrue(File(folder.root, "drafts/$one").exists())

        store().write(one, "")

        assertFalse(File(folder.root, "drafts/$one").exists())
        assertEquals("", store().read(one))
    }

    @Test
    fun `line breaks and non-latin text round-trip`() {
        val text = "первая строка\nвторая — с тире\n\ttab"
        store().write(one, text)
        assertEquals(text, store().read(one))
    }

    /** The file name is the id and nothing else; an id that is not one is refused, not sanitised. */
    @Test
    fun `an id that is not a uuid is refused`() {
        store().write("../escape", "x")
        store().write("active", "x")

        assertFalse(File(folder.root, "escape").exists())
        assertEquals(emptyList<String>(), File(folder.root, "drafts").list()?.toList() ?: emptyList<String>())
        assertEquals("", store().read("../escape"))
    }

    @Test
    fun `no staging file is left behind`() {
        store().write(one, "a")
        store().write(one, "ab")

        assertEquals(listOf(one), File(folder.root, "drafts").list()?.toList())
    }
}
