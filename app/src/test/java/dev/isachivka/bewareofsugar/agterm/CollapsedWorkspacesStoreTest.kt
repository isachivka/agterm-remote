package dev.isachivka.bewareofsugar.agterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The folded-workspace file — REQ-0031. **What comes out the other side is ids and nothing else**,
 * asserted against the bytes on disk rather than against the API that wrote them.
 *
 * (This used to open by calling the store "the one door in the package that writes to disk" and
 * pointing at `NothingPersistedTest`. That test enforced a rule the owner never set; both are gone,
 * REQ-0046. The id-only shape is still worth pinning on its own merits.)
 */
class CollapsedWorkspacesStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(): Pair<CollapsedWorkspacesStore, File> {
        val file = File(folder.root, "collapsed-workspaces")
        return CollapsedWorkspacesStore(file) to file
    }

    private val one = "11111111-1111-4111-8111-111111111111"
    private val two = "22222222-2222-4222-8222-222222222222"

    /** The feature: what was folded shut comes back folded shut. */
    @Test
    fun `what was written comes back`() {
        val (store, _) = store()

        store.write(setOf(one, two))

        assertEquals(setOf(one, two), CollapsedWorkspacesStore(File(folder.root, "collapsed-workspaces")).read())
    }

    /** First run. Nothing folded, and nothing thrown. */
    @Test
    fun `a file that is not there yet reads as nothing folded`() {
        val (store, file) = store()

        assertFalse("the fixture is wrong if the file already exists", file.exists())
        assertEquals(emptySet<String>(), store.read())
    }

    /**
     * **THE TEST THIS FILE EXISTS FOR.**
     *
     * A workspace name and a line of terminal output are handed to the door alongside a real id. The
     * bytes on disk must contain the id and neither of the others — not "should not normally", but
     * cannot, because the door admits only a UUID.
     *
     * Asserted against the file's own bytes, because asserting against `read()` would only prove the
     * two ends agree. What matters is what is sitting on the phone.
     */
    @Test
    fun `a name handed to the door never reaches the disk`() {
        val (store, file) = store()
        val aName = "Beware of Sugar — production"
        val someOutput = "total 48\ndrwxr-xr-x  6 is staff 192 Aug 22 01:14 ."

        store.write(setOf(one, aName, someOutput, "not-a-uuid", ""))

        val onDisk = String(file.readBytes())
        assertTrue("the id should have been kept", onDisk.contains(one))
        assertFalse("a workspace name reached the disk: $onDisk", onDisk.contains("Beware"))
        assertFalse("terminal output reached the disk: $onDisk", onDisk.contains("drwx"))
        assertFalse("a non-id reached the disk: $onDisk", onDisk.contains("not-a-uuid"))
        assertEquals("only the id should be stored", listOf(one), onDisk.split('\n').filter { it.isNotEmpty() })
    }

    /** And the same door in the other direction: a hand-edited file cannot inject anything either. */
    @Test
    fun `rubbish in the file is ignored rather than returned`() {
        val (store, file) = store()
        file.writeBytes("$one\nBeware of Sugar\n\nnot-a-uuid\n$two".toByteArray())

        assertEquals(setOf(one, two), store.read())
    }

    /** Folding everything open must actually clear it, not leave yesterday's set behind. */
    @Test
    fun `writing an empty set forgets everything`() {
        val (store, _) = store()
        store.write(setOf(one, two))

        store.write(emptySet())

        assertEquals(emptySet<String>(), store.read())
    }

    /**
     * A directory where the file should be: unwritable and unreadable. **Degrades to "nothing folded"
     * rather than taking the session list down**, which is the trade the class comment names.
     */
    @Test
    fun `an unusable file costs a fold and not a crash`() {
        val path = File(folder.root, "collapsed-workspaces")
        assertTrue("the fixture needs the path to be a directory", path.mkdirs())
        val store = CollapsedWorkspacesStore(path)

        store.write(setOf(one))

        assertEquals(emptySet<String>(), store.read())
    }
}
