package dev.isachivka.bewareofsugar.agterm

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The two sequences the owner has felt, end to end, on the JVM.
 *
 * ### What was broken, in their words
 *
 * *"стоп, фичи 2 - сделать подскролл когда я нажму кнопку клавиатуры в шапке и появится подвал - она
 * работает. вторая когда открывается андроид клава - это сломано"*. Two features: the header control
 * bringing up the bar, which worked, and the Android keyboard, which did not.
 *
 * The cause was a `LaunchedEffect` keyed on `ime > 0 || barOpen`. The bar set it true; the keyboard
 * arriving left it true; the key never changed, so the effect never ran a second time. **One boolean
 * OR-ing two independent events cannot say "and the other one as well".**
 *
 * ### And the yank that must never ship
 *
 * Fixing the above makes a second path reachable for the first time, and nobody asked for it out loud,
 * which is exactly how it would ship. The memory used to freeze for as long as any chrome was PRESENT,
 * so scrolling up with the bar open changed nothing it remembered — and the keyboard would then have
 * dragged the owner back to a bottom they had deliberately left. It now freezes only while a
 * transition is in flight.
 *
 * Both sequences below are written as the owner performs them, and both assert the policy's answer
 * rather than a screen.
 */
class KeyboardFollowsTheBottomTest {

    /**
     * **The key can express the second arrival.** `(bar)` and `(bar and keyboard)` are different
     * values, so the one effect runs for each — which is the whole of the fix.
     */
    @Test
    fun `the keyboard arriving after the bar is a different key`() {
        val barOnly = TerminalScroll.BottomChrome(keyboard = false, bar = true)
        val both = TerminalScroll.BottomChrome(keyboard = true, bar = true)

        assertNotEquals("the keyboard arriving must change the key, or nothing runs for it", barOnly, both)
        // And the OR is still available for "is there anything to settle for" - it is simply not the key.
        assertEquals(true, barOnly.anyPresent)
        assertEquals(false, TerminalScroll.BottomChrome(keyboard = false, bar = false).anyPresent)
    }

    /**
     * **The feature they said was broken.** At the bottom, the bar comes up, the keyboard comes up,
     * and they end at the bottom with the newest output still visible.
     */
    @Test
    fun `at the bottom, the bar then the keyboard, and they stay at the bottom`() = runBlocking {
        val view = Viewport()

        view.settled(offset = 2000, max = 2000)
        assertEquals("they are at the bottom to begin with", true, view.remembered)

        view.chromeArrives(newMax = 2400)   // the bar takes space; the view follows to the new bottom
        view.settled(offset = 2400, max = 2400)

        view.chromeArrives(newMax = 3200)   // the keyboard takes space on top of it
        assertEquals(
            "the memory must still say they were following when the keyboard arrived",
            TerminalScroll.Scroll.ToBottom,
            TerminalScroll.onBottomChromeAppeared(view.remembered),
        )

        view.stop()
    }

    /**
     * **The yank that must never ship.** At the bottom, the bar comes up, they scroll up to read
     * something, and then the keyboard arrives. They stay where they put themselves.
     *
     * This is the one that will look like a regression if it goes wrong, because nobody asked for it
     * out loud — they will simply find themselves dragged off the line they were reading.
     */
    @Test
    fun `scrolling up while the bar is open survives the keyboard`() = runBlocking {
        val view = Viewport()

        view.settled(offset = 2000, max = 2000)
        view.chromeArrives(newMax = 2400)
        view.settled(offset = 2400, max = 2400)

        // The bar has settled. This scroll is the owner deciding where to look, and the memory has to
        // follow it - under the old shape it was ignored for as long as the bar was open.
        view.settled(offset = 800, max = 2400)
        assertEquals("a scroll with the bar open was not remembered", false, view.remembered)

        view.chromeArrives(newMax = 3200)
        assertEquals(
            "the keyboard dragged a reader back to a bottom they had left",
            TerminalScroll.Scroll.None,
            TerminalScroll.onBottomChromeAppeared(view.remembered),
        )

        view.stop()
    }

    /**
     * **The flag is up before anything has run.**
     *
     * The window this closes: the IME inset moves during composition, layout writes a new maximum, and
     * the memory's flow can collect from that mid-animation viewport before the effect that handles the
     * change has been dispatched at all. One collect there overwrites the memory with *not at the
     * bottom* for someone who was — the original defect, arriving through a race.
     *
     * Derived from the pair, the flag is true the instant a new pair exists. Nothing has to run first,
     * so there is no window for anything to be late to.
     */
    @Test
    fun `a new chrome pair is settling immediately, before any effect runs`() {
        val none = TerminalScroll.BottomChrome(keyboard = false, bar = false)
        val bar = TerminalScroll.BottomChrome(keyboard = false, bar = true)
        val both = TerminalScroll.BottomChrome(keyboard = true, bar = true)

        assertEquals("the keyboard arriving must read as settling at once", true, TerminalScroll.settling(both, bar))
        assertEquals("the bar arriving must read as settling at once", true, TerminalScroll.settling(bar, none))
        // And once the effect has recorded what it settled for, it is not settling any more.
        assertEquals(false, TerminalScroll.settling(both, both))
    }

    /**
     * The point of deriving it: a viewport that starts moving with no effect having run must still not
     * move the memory. This drives the maximum exactly as the race would and asserts the memory held.
     */
    @Test
    fun `a maximum that moves before any effect runs does not move the memory`() = runBlocking {
        val view = Viewport()
        view.settled(offset = 2000, max = 2000)
        assertEquals(true, view.remembered)

        view.chromeArrives(newMax = 3200)

        assertEquals(
            "the mid-animation viewport overwrote the memory - the flag rose too late",
            TerminalScroll.Scroll.ToBottom,
            TerminalScroll.onBottomChromeAppeared(view.remembered),
        )
        view.stop()
    }

    /**
     * The terminal's viewport and the memory watching it, wired exactly as `AgtermScreen` wires them:
     * the memory freezes while a transition is in flight and tracks otherwise.
     */
    private class Viewport {
        private val settling = mutableStateOf(false)
        private val offset = mutableStateOf(2000)
        private val max = mutableStateOf(2000)
        var remembered = true
            private set

        private val job: Job = CoroutineScope(Dispatchers.Unconfined).launch {
            TerminalScroll.trackAtBottom(
                transitionInFlight = { settling.value },
                offset = { offset.value },
                max = { max.value },
            ) { remembered = it }
        }

        /** Nothing is moving: the numbers are whatever the owner has left them at. */
        suspend fun settled(offset: Int, max: Int) {
            settling.value = false
            this.offset.value = offset
            this.max.value = max
            push()
        }

        /**
         * Something took space at the bottom. The maximum grows across several frames, exactly as an
         * animation delivers it, and the memory must not move for any of them.
         */
        suspend fun chromeArrives(newMax: Int) {
            settling.value = true
            push()
            val from = max.value
            for (step in 1..4) {
                max.value = from + (newMax - from) * step / 4
                push()
            }
        }

        fun stop() = job.cancel()

        /** `snapshotFlow` emits on apply, so a test that mutates state has to push the apply through. */
        private suspend fun push() {
            Snapshot.sendApplyNotifications()
            yield()
        }
    }
}
