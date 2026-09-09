package dev.isachivka.agtermremote.agterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restoring the session list to where they left it.
 *
 * Every test here is about the case the owner will actually hit: the list is not the same list. It is
 * regrouped from every listing, sessions come and go on the laptop while they are away, and now
 * groups fold shut under their thumb.
 *
 * No workspace or session name appears in this file — ids only.
 */
class ListPositionTest {

    private fun session(id: String, workspaceId: String) =
        BridgeSession(id = id, workspaceId = workspaceId, workspace = "", title = "", active = false, name = id)

    private val groups = groupByWorkspace(
        listOf(
            session("s1", "W1"),
            session("s2", "W1"),
            session("s3", "W2"),
        ),
    )

    private val nothingClosed = emptySet<String>()

    /** The keys are what LazyColumn holds: a heading per workspace, then its sessions. */
    @Test
    fun `the keys are the rows the list actually holds`() {
        assertEquals(
            listOf("workspace-W1", "s1", "s2", "workspace-W2", "s3"),
            ListPosition.listKeys(groups, nothingClosed),
        )
    }

    /** A heading key can never be mistaken for a session id, whatever a workspace is called. */
    @Test
    fun `heading keys cannot collide with session ids`() {
        val keys = ListPosition.listKeys(groups, nothingClosed)
        assertEquals(keys.size, keys.toSet().size)
    }

    /** Folding hides a group's rows and never the group. The heading is how it is opened again. */
    @Test
    fun `a closed group keeps its heading and loses its rows`() {
        assertEquals(
            listOf("workspace-W1", "workspace-W2", "s3"),
            ListPosition.listKeys(groups, setOf("W1")),
        )
    }

    /**
     * **Folding takes rows off the screen and never out of the listing.**
     *
     * `SessionList` keys its restore on `listKeys(groups, emptySet())` — the listing — precisely
     * because that expression cannot see what is folded, so a collapse cannot re-run the restore and
     * scroll the list under the owner's thumb. That the call site passes an empty set is a property
     * of the source rather than of this function, and the instrumented test is what holds it.
     *
     * What is worth asserting here is the shape the two calls have to each other: whatever is folded,
     * the rendered rows are a subset of the listing, and every heading survives — a group is never
     * folded out of existence, only shut.
     */
    @Test
    fun `folding hides rows without shrinking the listing`() {
        val listing = ListPosition.listKeys(groups, nothingClosed)
        val headings = groups.map { ListPosition.headingKey(it.id) }

        for (closed in listOf(setOf("W1"), setOf("W2"), setOf("W1", "W2"))) {
            val rendered = ListPosition.listKeys(groups, closed)
            assertTrue("$closed rendered a row the listing does not hold", listing.containsAll(rendered))
            assertTrue("$closed folded a group out of existence", rendered.containsAll(headings))
        }
        // And the listing itself is untouched by any of it.
        assertEquals(listing, ListPosition.listKeys(groups, nothingClosed))
    }

    @Test
    fun `a remembered row is found where it now is`() {
        assertEquals(
            ListPosition.Target(index = 4, offset = 40),
            ListPosition.restore(ListPosition.Anchor("s3", offset = 40), groups, nothingClosed),
        )
    }

    /**
     * **The case that decides the design.** A session vanished from ABOVE where they were, so every
     * index below it shifted. Restoring the old index would land them somewhere plausible and wrong;
     * restoring the anchor lands them on the same row.
     */
    @Test
    fun `a row that moved is followed rather than lost`() {
        val after = groupByWorkspace(listOf(session("s2", "W1"), session("s3", "W2")))

        // They were on s3, which was index 4 and is now index 3 - one session vanished from above
        // them, and the workspace headings still count. Getting this arithmetic wrong is exactly the
        // failure mode of restoring by index, and it caught me writing the test.
        assertEquals(
            ListPosition.Target(index = 3, offset = 12),
            ListPosition.restore(ListPosition.Anchor("s3", offset = 12), after, nothingClosed),
        )
    }

    /**
     * **And when the row itself is gone, the top.** Not the nearest surviving neighbour: that is a
     * guess dressed as a restore, and the row they were reading no longer exists to be restored to.
     */
    @Test
    fun `a row that is gone restores to the top`() {
        val after = groupByWorkspace(listOf(session("s1", "W1")))

        assertNull(ListPosition.restore(ListPosition.Anchor("s3", offset = 12), after, nothingClosed))
    }

    /** A whole workspace closing takes its heading with it, and that is the same case. */
    @Test
    fun `a heading that is gone restores to the top`() {
        val after = groupByWorkspace(listOf(session("s1", "W1")))

        assertNull(ListPosition.restore(ListPosition.Anchor("workspace-W2", offset = 0), after, nothingClosed))
    }

    /**
     * **A folded row is not a gone row.** The group's heading is where it went — not a guess about
     * where it might be, which is the objection this file makes about restoring by index.
     */
    @Test
    fun `a row folded away restores to the heading that now holds it`() {
        // With W2 shut the rows are [workspace-W1, s1, s2, workspace-W2], so the heading is index 3 -
        // and I wrote 1 here first, which is the index it would have if folding W2 also folded W1.
        // The same arithmetic slip the comment two tests down records, made again while writing the
        // test that catches it.
        assertEquals(
            ListPosition.Target(index = 3, offset = 0),
            ListPosition.restore(ListPosition.Anchor("s3", offset = 12), groups, setOf("W2")),
        )
    }

    /**
     * The offset is dropped on the way, deliberately. It was measured against a session row, and a
     * heading is a different row of a different height — carrying it would scroll partway through the
     * heading, which is arithmetically faithful and visually wrong.
     */
    @Test
    fun `folding drops an offset measured against a row that is no longer there`() {
        assertEquals(
            ListPosition.Anchor("workspace-W1", offset = 0),
            ListPosition.fold(ListPosition.Anchor("s2", offset = 55), groups, setOf("W1")),
        )
    }

    /** A heading is visible whether its group is open or shut, so it passes through untouched. */
    @Test
    fun `an anchor on a heading survives its own group closing`() {
        assertEquals(
            ListPosition.Target(index = 0, offset = 7),
            ListPosition.restore(ListPosition.Anchor("workspace-W1", offset = 7), groups, setOf("W1")),
        )
    }

    /** A row in a group that is still open is not folded by another group being shut. */
    @Test
    fun `a row in an open group is untouched by a collapse elsewhere`() {
        assertEquals(
            ListPosition.Anchor("s1", offset = 9),
            ListPosition.fold(ListPosition.Anchor("s1", offset = 9), groups, setOf("W2")),
        )
    }

    /** Nothing remembered is the top, and so is an empty list — all the same to the owner. */
    @Test
    fun `nothing remembered restores to the top`() {
        assertNull(ListPosition.restore(null, groups, nothingClosed))
        assertNull(ListPosition.restore(ListPosition.Anchor("s1", 0), emptyList(), nothingClosed))
    }
}
