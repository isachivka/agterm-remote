package dev.isachivka.bewareofsugar.update.play

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch check, the manual one, and what happens when Play refuses to start — REQ-0050.
 *
 * The seam is [PlayUpdateSource], the same shape [ReleaseSource] gives the GitHub path: everything
 * that talks to Play is behind it, so none of this needs a device or a track.
 */
class PlayUpdateViewModelTest {

    private val now = 1_700_000_000L

    /**
     * Only [PlayUpdateSource.check] is faked here. Starting is exercised through the lambda
     * overload of `startUpdate` instead, because `startImmediate` takes an `Activity` and android.jar
     * is stubs that throw when constructed - the reason that overload exists.
     */
    private class FakeSource(
        var answer: PlayUpdateStatus = PlayUpdateStatus.UpToDate(0L),
    ) : PlayUpdateSource {
        var checks = 0

        override suspend fun check(nowEpochSeconds: Long): PlayUpdateStatus {
            checks++
            return answer
        }

        override fun startImmediate(activity: android.app.Activity): Boolean =
            throw UnsupportedOperationException("not reachable from a unit test")
    }

    private fun viewModel(source: FakeSource) = PlayUpdateViewModel(
        source = source,
        nowEpochSeconds = { now },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `it checks on construction`() {
        // REQ-0003 asked for a check on launch, and REQ-0050 keeps the promise on the new channel.
        // A check that only runs once the owner opens the update screen is a check they had to
        // think of first.
        val source = FakeSource()
        viewModel(source)
        assertEquals(1, source.checks)
    }

    @Test
    fun `an available update reaches the screen`() {
        val source = FakeSource(answer = PlayUpdateStatus.Available(3401, now, immediateAllowed = true))
        val vm = viewModel(source)
        assertEquals(PlayUpdateStatus.Available(3401, now, immediateAllowed = true), vm.status.value)
    }

    @Test
    fun `a manual check runs again`() {
        val source = FakeSource()
        val vm = viewModel(source)
        vm.checkNow()
        assertEquals(2, source.checks)
    }

    @Test
    fun `a failure is reported rather than swallowed`() {
        val source = FakeSource(answer = PlayUpdateStatus.Failed(-6))
        val vm = viewModel(source)
        assertEquals(PlayUpdateStatus.Failed(-6), vm.status.value)
    }

    @Test
    fun `a build that did not come from play is told so, not told it is up to date`() {
        val source = FakeSource(answer = PlayUpdateStatus.NotFromPlay)
        val vm = viewModel(source)
        assertEquals(PlayUpdateStatus.NotFromPlay, vm.status.value)
    }

    @Test
    fun `a refused start does not leave the screen silent`() {
        // The failure this test exists for: a button that visibly does nothing. Play declining to
        // open its flow has to become a sentence, because the alternative is the owner pressing it
        // again, and again.
        val vm = viewModel(FakeSource(PlayUpdateStatus.Available(3401, now, immediateAllowed = true)))
        var asked = false
        vm.startUpdate { asked = true; false }
        assertTrue(asked)
        assertTrue(vm.status.value is PlayUpdateStatus.Unknown)
    }

    @Test
    fun `a started update leaves the status alone`() {
        // Play takes the screen and restarts the app on success, so there is no "updating" state
        // this app could ever draw. Inventing one would mean drawing a screen nobody can see.
        val available = PlayUpdateStatus.Available(3401, now, immediateAllowed = true)
        val vm = viewModel(FakeSource(answer = available))
        vm.startUpdate { true }
        assertEquals(available, vm.status.value)
    }
}
