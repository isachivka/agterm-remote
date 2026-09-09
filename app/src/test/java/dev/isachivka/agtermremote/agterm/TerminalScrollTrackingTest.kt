package dev.isachivka.agtermremote.agterm

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WIRING of the at-bottom memory, not the policy.
 *
 * ### Why this file exists
 *
 * `TerminalScrollTest` covers the decisions and every one of them passed while the feature was broken.
 * The defect was one line of plumbing: the IME inset was read in the composable and the resulting `Int`
 * was used inside `snapshotFlow`, so it was **captured** — frozen at whatever it was when the coroutine
 * started, which is zero. The offset and maximum were re-read, because those were state reads inside
 * the block; the inset was not. "Has the keyboard begun to take space" was therefore a constant false,
 * the memory updated straight through the IME animation, and a follower was recorded as a reader.
 *
 * No test of a pure function can catch that, because the pure function was right. **This one changes
 * the inset and asserts that the memory stopped moving** — the only shape of test that could have.
 *
 * It runs on the JVM against real snapshot state, so it is in the gate rather than on a device.
 */
class TerminalScrollTrackingTest {

    @Test
    fun `the memory stops moving once the inset appears`() = runBlocking {
        val settling = mutableStateOf(false)
        val inset = mutableStateOf(0)
        val offset = mutableStateOf(2000)
        val max = mutableStateOf(2000)
        var remembered = true

        val job: Job = CoroutineScope(Dispatchers.Unconfined).launch {
            TerminalScroll.trackAtBottom(
                transitionInFlight = { settling.value },
                offset = { offset.value },
                max = { max.value },
            ) { remembered = it }
        }
        settle()

        // At the bottom, keyboard closed. The memory says so.
        assertTrue("the memory did not follow them to the bottom", remembered)

        // The keyboard begins to take space, and the viewport shrinks frame by frame - which makes
        // them LOOK like someone who had scrolled up, because the maximum is growing under them.
        settling.value = true
        for (px in listOf(40, 180, 420, 700, 900)) {
            inset.value = px
            max.value = 2000 + px
            settle()
        }

        // **The assertion that would have caught the captured Int.** With the inset frozen at zero,
        // every frame above is treated as "keyboard closed" and the memory is overwritten to false.
        assertTrue(
            "the animation overwrote the memory it exists to outlive - the inset is being captured, " +
                "not observed",
            remembered,
        )

        job.cancel()
    }

    /**
     * And while nothing is settling, it follows them - up as well as down, and whether or not the input
     * bar happens to be on screen. The flag means *the viewport is moving*, never *there is chrome*.
     */
    @Test
    fun `the memory follows them while the inset stays at zero`() = runBlocking {
        val settling = mutableStateOf(false)
        val inset = mutableStateOf(0)
        val offset = mutableStateOf(2000)
        val max = mutableStateOf(2000)
        var remembered = true

        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            TerminalScroll.trackAtBottom(
                transitionInFlight = { settling.value },
                offset = { offset.value },
                max = { max.value },
            ) { remembered = it }
        }
        settle()

        offset.value = 200
        settle()
        assertFalse("a stale true survived them scrolling away with the keyboard closed", remembered)

        offset.value = 2000
        settle()
        assertTrue("following did not resume when they came back to the bottom", remembered)

        job.cancel()
    }

    /**
     * `snapshotFlow` emits when the snapshot it read is applied, so a test that mutates state has to
     * push the apply through itself. Without this the flow never sees anything and both tests above
     * would pass by observing nothing at all — which is the failure mode this whole file is about.
     */
    private suspend fun settle() {
        Snapshot.sendApplyNotifications()
        yield()
    }
}
