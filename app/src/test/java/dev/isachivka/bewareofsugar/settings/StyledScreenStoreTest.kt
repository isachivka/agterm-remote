package dev.isachivka.bewareofsugar.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StyledScreenStoreTest {

    @get:Rule
    val dir = TemporaryFolder()

    @Test
    fun `off until the owner turns it on`() {
        assertFalse(StyledScreenStore(dir.root.path).read())
    }

    @Test
    fun `a choice survives a new store on the same directory`() {
        StyledScreenStore(dir.root.path).write(true)
        assertTrue(StyledScreenStore(dir.root.path).read())
        StyledScreenStore(dir.root.path).write(false)
        assertFalse(StyledScreenStore(dir.root.path).read())
    }

    @Test
    fun `a corrupt file reads as off`() {
        val store = StyledScreenStore(dir.root.path)
        store.write(true)
        dir.root.listFiles()!!.single().writeText("maybe")
        assertFalse(store.read())
    }
}
