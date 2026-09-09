package dev.isachivka.agtermremote.agterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * The overpull's decisions, REQ-0029 — every one of them, without a screen.
 *
 * The gesture itself needs a finger and an emulator, and this project has neither in CI. What it does
 * not need either is: which key a direction means, when the key is armed, and that the icon the owner
 * watches is driven by the same number that decides. Those are arithmetic, and they are here.
 */
class TerminalPullTest {

    /** 56dp of travel at density 2.25, which is the owner's phone. */
    private val threshold = 126f

    /** What the finger must cover to reach [threshold], given the resistance. */
    private val fingerToArm = threshold / TerminalPull.RESISTANCE

    /**
     * **The direction, and the sign underneath it, which came from a thumb rather than from a manual.**
     *
     * v0.15.0 shipped this backwards. The requirement was never in doubt — finger UP sends PgUp, asked
     * for three times — but the premise underneath it was: which sign of leftover a rising finger
     * produces was READ OUT OF THE NESTED-SCROLL API and never measured. The old version of this test
     * asserted `-400f` is upward, passed, and was a true sentence about a world that is not this one.
     *
     * **Measured 2026-08-22, by the owner, on his own phone:** he pulled upward on v0.15.0 and his
     * laptop received `pagedown`. `released` returns `pagedown` only for a non-negative pull, so an
     * upward finger produces a POSITIVE leftover here. That is the only instrument this question has
     * ever had, and it outranks four layers of our own agreement.
     */
    @Test
    fun `finger up is page up and finger down is page down`() {
        // Positive is upward - see TerminalPull.isUpward for the measurement and the date.
        // **Sized from [fingerToArm] rather than a literal.** A magic 400f was enough to arm the
        // gesture at the old resistance and not at the new one, so raising the distance silently
        // broke three tests that were not about distance at all.
        val up = TerminalPull.accumulate(0f, fingerToArm + 1f)
        val down = TerminalPull.accumulate(0f, -(fingerToArm + 1f))

        assertEquals(TerminalPull.PAGE_UP, TerminalPull.released(up, threshold))
        assertEquals(TerminalPull.PAGE_DOWN, TerminalPull.released(down, threshold))
    }

    /**
     * **The label, the end it sits at, and the key that fires — asserted as ONE value, both ways.**
     *
     * Every test on this feature used to check one of these at a time, which is exactly how it shipped
     * with the indicator naming one direction and a different key leaving. A tuple cannot drift: if a
     * later change flips one of them, the pair stops matching and this fails.
     */
    @Test
    fun `what the indicator says and what fires are the same decision`() {
        val up = TerminalPull.accumulate(0f, fingerToArm + 1f)
        val down = TerminalPull.accumulate(0f, -(fingerToArm + 1f))

        assertEquals(
            "pulling up must name pageup, and sit at the end the content vacated",
            TerminalPull.Pulling(key = TerminalPull.PAGE_UP, atTop = true),
            TerminalPull.pulling(up),
        )
        assertEquals(
            TerminalPull.Pulling(key = TerminalPull.PAGE_DOWN, atTop = false),
            TerminalPull.pulling(down),
        )

        // And the key that actually fires is the one the label named, not a second decision.
        assertEquals(TerminalPull.pulling(up).key, TerminalPull.released(up, threshold))
        assertEquals(TerminalPull.pulling(down).key, TerminalPull.released(down, threshold))
    }

    /**
     * **THE TEST NOBODY WROTE, AND THE BUG IT WOULD HAVE CAUGHT.**
     *
     * Pull past the threshold, reverse without releasing, and the pull must come back to zero: no
     * opacity, no armed key. v0.15.0 could not do this — `onPostScroll` accumulated and nothing ever
     * subtracted, so once the finger turned round the child had somewhere to scroll again, consumed
     * the whole delta, and the connection was handed zero. The number climbed and would not come down.
     *
     * **Every test on this feature moved in a single direction, which is why a gesture that only works
     * in one direction passed all of them.**
     */
    @Test
    fun `reversing before release unwinds the pull to nothing`() {
        var pull = TerminalPull.accumulate(0f, fingerToArm + 1f)
        assertEquals("the pull must be armed before it is unwound", 1f, TerminalPull.progress(pull, threshold), 0f)

        // The finger turns round. This arrives BEFORE the child, which is the half that was missing.
        val unwound = TerminalPull.unwind(pull, -(fingerToArm + 1f))
        pull = unwound.pull

        assertEquals("the pull did not come back down", 0f, pull, 0f)
        assertEquals("nothing is showing", 0f, TerminalPull.progress(pull, threshold), 0f)
        assertNull("a key fired after the owner changed their mind", TerminalPull.released(pull, threshold))
        assertEquals("the whole reversal was spent unwinding", -(fingerToArm + 1f), unwound.consumed, 0.0001f)
    }

    /** A reversal bigger than the pull unwinds it exactly and hands the rest down to scroll. */
    @Test
    fun `an over-reversal settles the pull and passes the remainder on`() {
        val unwound = TerminalPull.unwind(200f, -500f)

        assertEquals(0f, unwound.pull, 0f)
        assertEquals("only the standing pull may be claimed", -200f, unwound.consumed, 0f)
    }

    /** Winding further is onPostScroll's job. Claiming it here would take the same delta twice. */
    @Test
    fun `a delta in the same direction is not this connection's to claim`() {
        assertEquals(0f, TerminalPull.unwind(200f, 50f).consumed, 0f)
        assertEquals(200f, TerminalPull.unwind(200f, 50f).pull, 0f)
        assertEquals("with nothing wound there is nothing to unwind", 0f, TerminalPull.unwind(0f, -50f).consumed, 0f)
    }

    /**
     * **Winding and unwinding are one rule**: any sequence of deltas summing to zero must leave the
     * pull at exactly zero. This is the property that makes "a number that climbs and will not come
     * down" impossible rather than merely fixed.
     */
    @Test
    fun `any sequence that sums to zero leaves no pull behind`() {
        var pull = 0f
        val deltas = listOf(120f, 90f, 30f, -55f, -100f, 40f, -125f)

        for (d in deltas) {
            val unwound = TerminalPull.unwind(pull, d)
            pull = unwound.pull
            // Whatever the unwind did not claim is what the child would have seen, and anything it
            // leaves over at a limit comes back as leftover.
            val leftover = d - unwound.consumed
            if (leftover != 0f) pull = TerminalPull.accumulate(pull, leftover)
        }

        assertEquals("deltas summing to ${deltas.sum()} left $pull behind", 0f, pull, 0.0001f)
    }

    /** Short of the threshold nothing is sent, which is the owner's "release short and nothing happens". */
    @Test
    fun `a pull short of the threshold sends nothing`() {
        // 100px of finger is 50px of travel against a 126px threshold.
        assertNull(TerminalPull.released(-100f, threshold))
        assertNull(TerminalPull.released(100f, threshold))
    }

    /** And a pull that never started is not a downward pull of zero length. */
    @Test
    fun `no pull at all sends nothing`() {
        assertNull(TerminalPull.released(0f, threshold))
        assertEquals(0f, TerminalPull.progress(0f, threshold), 0f)
    }

    /**
     * **The icon and the trigger are the same number.**
     *
     * The owner's rule is *"release at 100 and the key is sent"*. That is only true if full opacity and
     * armed are one condition — two thresholds that agree today are two thresholds that drift, and the
     * drift would show up as a fully-lit icon that does nothing.
     */
    @Test
    fun `full opacity is exactly when the key is armed`() {
        var pull = 0f
        var firstArmed: Float? = null
        var firstFullyOpaque: Float? = null

        while (pull > -600f) {
            pull -= 1f
            if (firstArmed == null && TerminalPull.released(pull, threshold) != null) firstArmed = pull
            if (firstFullyOpaque == null && TerminalPull.progress(pull, threshold) >= 1f) {
                firstFullyOpaque = pull
            }
        }

        assertEquals("armed and fully opaque must begin together", firstFullyOpaque, firstArmed)
        assertTrue("the sweep must actually reach the threshold", firstArmed != null)
    }

    /** Opacity never exceeds one, however far the pull goes — a tint alpha above 1 is a crash. */
    @Test
    fun `progress is bounded at one`() {
        assertEquals(1f, TerminalPull.progress(-99999f, threshold), 0f)
        assertEquals(1f, TerminalPull.progress(99999f, threshold), 0f)
    }

    /**
     * A zero threshold means the density is not known yet. **Report no progress rather than dividing.**
     * The alternative is a NaN reaching a tint alpha on the first frame of the first composition.
     */
    @Test
    fun `an unmeasured threshold arms nothing`() {
        assertEquals(0f, TerminalPull.progress(-500f, 0f), 0f)
        assertNull(TerminalPull.released(-500f, 0f))
    }

    /** The terminal moves less than the finger, which is what makes it read as a stretch. */
    @Test
    fun `the terminal travels less far than the finger`() {
        assertEquals(-30f, TerminalPull.travel(-100f), 0.0001f)
        assertTrue(TerminalPull.RESISTANCE < 1f)
    }

    /**
     * **The checkmark and the keystroke are the same moment.**
     *
     * The owner could not tell when releasing would fire. A colour was tried and rejected — *"давай не
     * цвет, давай перед текстом добавим иконку галочки"* — so a tick appears instead. Either way the
     * mark is the only part of this indicator that reports a boolean, alpha being a ramp with no line
     * in it, which makes it worth exactly as much as its agreement with the key. **A tick that shows
     * while nothing would be sent is worse than no mark at all.**
     *
     * This asserts the CONDITION and never a colour: the green it was first written for is gone, and a
     * test still checking that colour would be a test passing about a thing that no longer exists.
     *
     * Swept rather than sampled, because two conditions agreeing at one chosen value is also what a
     * pair of drifting thresholds looks like.
     */
    @Test
    fun `the checkmark shows exactly when the key is armed`() {
        var pull = 0f
        var disagreements = 0

        while (pull < fingerToArm * 2f) {
            val tickShowing = TerminalPull.armed(pull, threshold)
            val fires = TerminalPull.released(pull, threshold) != null
            if (tickShowing != fires) disagreements++
            pull += 1f
        }

        assertEquals("the tick and the keystroke parted company somewhere in the sweep", 0, disagreements)
    }

    /**
     * **The finger has to travel further than it did**, which is the whole answer to
     * *"высокая вероятность ложных срабатываний"*.
     *
     * Asserted in FINGER distance rather than in travel, because that is the number a thumb reports
     * on — the constant names the terminal's movement and the gap between the two is why this shipped
     * too light. A change that makes the gesture easier to trigger again fails here.
     */
    @Test
    fun `arming takes a deliberate amount of finger`() {
        assertEquals("the finger distance is threshold over resistance", 420f, fingerToArm, 0.01f)

        // Just short of it does nothing; just past it fires. Both sides, so a threshold that moved to
        // zero would fail rather than pass the first half.
        assertNull(TerminalPull.released(fingerToArm - 1f, threshold))
        assertEquals(TerminalPull.PAGE_UP, TerminalPull.released(fingerToArm + 1f, threshold))

        // And it is meaningfully further than the version he reported as false-triggering, which was
        // this same threshold at a resistance of 0.5.
        assertTrue("the gesture got easier, not harder", fingerToArm > threshold / 0.5f)
    }

    /**
     * Deltas add up across frames; one flick is not one event.
     *
     * **This test named a direction it was not testing, and CI caught it.** It asserted that ten
     * deltas of `-30f` arm `PAGE_UP` — an incidental claim about the sign, carried along by a test
     * whose actual subject is accumulation. When the measured sign replaced the read one it failed,
     * correctly, having quietly been a second copy of the premise that was wrong.
     *
     * So the key is now asserted **through `pulling()`** rather than named literally: this checks that
     * the accumulated pull arms the key the indicator names, whichever direction that is. The
     * direction itself has one test and does not need a second.
     */
    @Test
    fun `travel accumulates across frames`() {
        val perFrame = -30f
        // Enough frames to carry it past the arming distance, DERIVED rather than counted out: the
        // literal ten frames stopped being enough the moment the resistance changed.
        val frames = ceil(fingerToArm / -perFrame).toInt() + 1
        var pull = 0f
        repeat(frames) { pull = TerminalPull.accumulate(pull, perFrame) }

        assertEquals(perFrame * frames, pull, 0.0001f)
        assertEquals(TerminalPull.pulling(pull).key, TerminalPull.released(pull, threshold))
    }

    /**
     * **The gesture sends the same two key names the buttons do**, and they are names the bridge
     * accepts.
     *
     * REQ-0029 moved `PgUp` and `PgDn` under the fold rather than deleting them, so there are now two
     * ways to send each. If these strings drifted from the cells', one route would work and the other
     * would be refused four layers away as a keystroke that silently does nothing — which is exactly
     * the failure `KeyCell` was made a sealed type to prevent.
     */
    @Test
    fun `the gesture sends the same names the key bar does`() {
        val onTheBar = KEY_ROWS.flatten().filterIsInstance<KeyCell.Key>().map { it.name }.toSet()

        assertTrue("pageup is not on the bar any more - has it been deleted?", TerminalPull.PAGE_UP in onTheBar)
        assertTrue("pagedown is not on the bar any more - has it been deleted?", TerminalPull.PAGE_DOWN in onTheBar)
    }
}
