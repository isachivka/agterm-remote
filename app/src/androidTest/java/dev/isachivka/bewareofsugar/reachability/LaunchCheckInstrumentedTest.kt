package dev.isachivka.bewareofsugar.reachability

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.repeatOnLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * **Nothing runs while the app is off screen.**
 *
 * This is the property REQ-0005 protected by refusing a `ViewModel` altogether, and REQ-0006 keeps by
 * separating where results live from what drives the work. It is the one thing in this design that a
 * plausible refactor could silently undo — moving the launch into `viewModelScope`, or into the
 * `ViewModel`'s `init`, would look tidier and would keep checking after the owner walked away.
 *
 * So it is a test rather than a paragraph, and it fails if either of those happens: both survive
 * `onStop`, and this drives a real `Lifecycle` below `STARTED` and asserts the server stops hearing
 * from us.
 *
 * The clock is injected, so the staleness half is exercised without waiting 90 seconds.
 */
@RunWith(AndroidJUnit4::class)
class LaunchCheckInstrumentedTest {

    private lateinit var server: MockWebServer

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** Four services that each take a second, so backgrounding lands in the middle of a round. */
    private fun slowResults(): ReachabilityResults {
        repeat(40) {
            server.enqueue(MockResponse.Builder().headersDelay(1, TimeUnit.SECONDS).code(200).build())
        }
        server.start()
        val services = (1..4).map { HomeService("s$it", "S$it", server.url("/$it").toString()) }
        return ReachabilityResults(
            prober = ReachabilityProber(ReachabilityProber.defaultClient()),
            services = services,
        )
    }

    /** The wiring MainActivity uses, so the test exercises the arrangement and not a paraphrase. */
    private fun CoroutineScope.driveFrom(owner: Owner, results: ReachabilityResults) = launch {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            results.check()
        }
    }

    @Test
    fun backgroundingStopsTheRequestsInFlight() = runBlocking {
        val results = slowResults()
        val owner = Owner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.STARTED }
        scope.driveFrom(owner, results)
        delay(600)

        val whileForeground = server.requestCount
        assertTrue("the check never started", whileForeground > 0)

        // The owner walks away.
        withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.CREATED }
        delay(1_500)

        assertEquals(
            "requests were still being made with the app off screen",
            whileForeground,
            server.requestCount,
        )
        scope.cancel()
    }

    /** And what it leaves behind is honest: no row stuck on Checking. */
    @Test
    fun backgroundingLeavesNoRowSpinning() = runBlocking {
        val results = slowResults()
        val owner = Owner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.STARTED }
        scope.driveFrom(owner, results)
        delay(600)
        withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.CREATED }
        delay(500)

        assertEquals(
            emptyList<String>(),
            results.results.value.filterValues { it.reachability == Reachability.Checking }.keys.toList(),
        )
        scope.cancel()
    }

    /**
     * **The rule REQ-0007 replaced the freshness window with**: every return runs a fresh round.
     *
     * The two tests this replaces asserted the opposite at the boundary - away 10 s, back, and *no*
     * requests. They were wrong rather than stale once the owner asked for a reload on every return,
     * so they were deleted rather than retuned. Five seconds here is deliberately well inside the
     * old 90-second window, so this test would have failed against the previous behaviour.
     */
    @Test
    fun everyReturnToTheForegroundRunsAFreshRound() = runBlocking {
        repeat(40) { server.enqueue(MockResponse.Builder().code(200).build()) }
        server.start()
        val services = (1..4).map { HomeService("s$it", "S$it", server.url("/$it").toString()) }
        val results = ReachabilityResults(
            prober = ReachabilityProber(ReachabilityProber.defaultClient()),
            services = services,
        )
        val owner = Owner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.STARTED }
        scope.driveFrom(owner, results)
        delay(1_500)
        val afterLaunch = server.requestCount
        assertEquals("one round of four", 4, afterLaunch)

        // Away five seconds - well inside the window that used to suppress this - and back.
        withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.CREATED }
        delay(500)
        withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.STARTED }
        delay(1_500)

        assertEquals("every return must re-check", afterLaunch * 2, server.requestCount)
        scope.cancel()
    }

    /**
     * **Nothing is written down.** The never-persist rule is the other half of never-outlives-its-
     * network, and it is the one a future "just cache it so the tile is instant" change would break
     * without touching anything else. Asserted against the app's own storage directories rather than
     * against the class, because the failure would be a file appearing, not an API changing.
     */
    @Test
    fun aCheckWritesNothingToDisk() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Every file under the app's own storage, recursively - not just datastore/ and
        // shared_prefs/. The first version listed only those two, which are the mechanisms this app
        // happens to use, and would have passed while somebody wrote a plain cache.json beside them.
        // A negative requirement has to be asserted against the whole surface it forbids.
        fun stored(): Set<String> = listOf(context.filesDir, context.cacheDir, context.noBackupFilesDir)
            .flatMap { root -> root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path } }
            .toSet()

        val before = stored()

        repeat(20) { server.enqueue(MockResponse.Builder().code(200).build()) }
        server.start()
        val services = (1..4).map { HomeService("s$it", "S$it", server.url("/$it").toString()) }
        ReachabilityResults(
            prober = ReachabilityProber(ReachabilityProber.defaultClient()),
            services = services,
        ).check()

        assertEquals("a check must leave nothing on disk", before, stored())
    }
}
