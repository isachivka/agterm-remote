import Foundation
import Testing
@testable import AgtermRemoteCore

/// **The launcher, against real processes.** Every one of these is here because a double said the
/// code was fine and a measurement said otherwise.
///
/// The doubles in `BridgeProcessTests` model the protocol; this file models the operating system.
/// The three defects it pins — a child that ignores `SIGTERM` being forgotten rather than killed, a
/// map that grew forever because the insert happened after the child could already have exited, and
/// a byte ceiling enforced in Characters — were all invisible from the other side of the seam.
struct ChildProcessLauncherTests {

    /// A shell that traps `SIGTERM` and keeps going, which is exactly the child the escalation
    /// exists for. `/bin/sh` is named here and nowhere in `Sources` — `BoundaryTests` reads the
    /// sources, and this app's boundary is about what it ships, not about what its tests spawn.
    private static let shell = URL(fileURLWithPath: "/bin/sh")

    private final class Waiter: @unchecked Sendable {
        private let semaphore = DispatchSemaphore(value: 0)
        private let lock = NSLock()
        private var _status: Int32?
        var status: Int32? { lock.withLock { _status } }

        var onExit: @Sendable (Int32) -> Void {
            { [self] status in
                lock.withLock { _status = status }
                semaphore.signal()
            }
        }

        @discardableResult func wait(_ seconds: TimeInterval = 5) -> Bool {
            semaphore.wait(timeout: .now() + seconds) == .success
        }
    }

    private static func alive(_ pid: Int32) -> Bool { kill(pid, 0) == 0 }

    // MARK: - A child that will not go quietly

    /// **`.stopped` must be a fact, not a request.**
    ///
    /// Before this, `stop()` sent one `SIGTERM`, announced `.stopped` and cleared the pid. Measured:
    /// the child was still alive, the second Stop was a no-op because there was nothing left to
    /// signal, and the next Start added a *second* live bridge to the same port. `--parent-pid` only
    /// rescues that when the whole app quits.
    @Test func aChildThatIgnoresTerminationIsKilledAndTerminateWaitsForIt() throws {
        let launcher = ChildProcessLauncher(gracePeriod: 0.3)
        let waiter = Waiter()

        let pid = try launcher.launch(
            Self.shell, ["-c", "trap '' TERM; while true; do sleep 0.05; done"],
            onExit: waiter.onExit)
        // Give the trap time to be installed; killing it before that would prove nothing.
        Thread.sleep(forTimeInterval: 0.4)
        #expect(Self.alive(pid), "the child was supposed to survive SIGTERM, so the test needs it alive")

        launcher.terminate(pid)

        // The contract: terminate RETURNS only once the child has gone.
        #expect(!Self.alive(pid), "terminate returned while the child was still running")
        #expect(waiter.status != nil, "the exit was never reported")
        #expect(launcher.tracked.children == 0)
        #expect(launcher.tracked.waiters == 0)
    }

    /// The ordinary case still uses the polite signal, and does not wait out the grace period to
    /// discover the child already left.
    @Test func aWellBehavedChildIsAskedRatherThanKilled() throws {
        let launcher = ChildProcessLauncher(gracePeriod: 5)
        let waiter = Waiter()

        let pid = try launcher.launch(
            Self.shell, ["-c", "while true; do sleep 0.05; done"], onExit: waiter.onExit)
        Thread.sleep(forTimeInterval: 0.2)
        let before = Date()
        launcher.terminate(pid)
        let took = Date().timeIntervalSince(before)

        #expect(!Self.alive(pid))
        #expect(took < 2, "it waited out a grace period a cooperative child did not need: \(took)s")
        // 128 + SIGTERM. A bridge that was cut down must not report a bare zero.
        #expect(waiter.status == 128 + SIGTERM)
    }

    /// Terminating something that already exited is a no-op, not a wait and not a signal to whatever
    /// inherited the pid.
    @Test func terminatingAnAlreadyDeadChildReturnsAtOnce() throws {
        let launcher = ChildProcessLauncher(gracePeriod: 5)
        let waiter = Waiter()

        let pid = try launcher.launch(URL(fileURLWithPath: "/usr/bin/true"), [], onExit: waiter.onExit)
        waiter.wait()
        let before = Date()
        launcher.terminate(pid)

        #expect(Date().timeIntervalSince(before) < 1)
    }

    // MARK: - Nothing grows

    /// **A hundred spawn-and-exit cycles, and every map back to where it started.**
    ///
    /// `children[pid]` used to leak on its own: the insert happened after `task.run()`, so a child
    /// that died inside that window was removed by its own termination handler before it was ever
    /// inserted, and the insert then put it back for good. `/usr/bin/true` dies inside that window
    /// often enough to catch it.
    @Test func nothingIsRememberedAboutAChildThatHasGone() throws {
        let launcher = ChildProcessLauncher()

        for _ in 0 ..< 100 {
            let waiter = Waiter()
            _ = try launcher.launch(URL(fileURLWithPath: "/usr/bin/true"), [], onExit: waiter.onExit)
            #expect(waiter.wait(), "a child did not report its exit")
        }
        // The termination handler runs on its own queue; the last one may still be unwinding.
        Thread.sleep(forTimeInterval: 0.2)

        #expect(launcher.tracked.children == 0, "processes still tracked: \(launcher.tracked.children)")
        #expect(launcher.tracked.output == 0, "output still held for silent children")
        #expect(launcher.tracked.waiters == 0)
    }

    /// **The leak, made deterministic.**
    ///
    /// The insert happened after `task.run()`, so a child that died in that window was removed by its
    /// own termination handler before it had ever been inserted — and the insert then put it back for
    /// good. The window is real and narrow: a hundred `/usr/bin/true` cycles hit it zero times, so a
    /// test that waited for the coincidence would have passed over the defect forever. The seam holds
    /// the window open instead.
    @Test func aChildThatDiesBeforeItIsRecordedIsNotRecordedAfterwards() throws {
        let launcher = ChildProcessLauncher()
        let waiter = Waiter()
        launcher.betweenRunAndRegistration = { Thread.sleep(forTimeInterval: 0.25) }

        _ = try launcher.launch(URL(fileURLWithPath: "/usr/bin/true"), [], onExit: waiter.onExit)
        #expect(waiter.wait())
        Thread.sleep(forTimeInterval: 0.2)

        #expect(
            launcher.tracked.children == 0,
            "a child that exited before it was recorded is now recorded forever")
    }

    /// Children that DO talk are remembered, but only a handful of them. `.failed` reads the most
    /// recent and nothing older.
    @Test func onlyAHandfulOfChildrensOutputIsKept() throws {
        let launcher = ChildProcessLauncher()

        for index in 0 ..< 40 {
            let waiter = Waiter()
            _ = try launcher.launch(
                URL(fileURLWithPath: "/bin/echo"), ["run \(index)"], onExit: waiter.onExit)
            #expect(waiter.wait())
        }
        Thread.sleep(forTimeInterval: 0.2)

        #expect(launcher.tracked.children == 0)
        #expect(
            launcher.tracked.output <= ChildProcessLauncher.keepOutputsFor,
            "kept output for \(launcher.tracked.output) children")
    }

    // MARK: - The byte ceiling

    /// **Bytes, not Characters.** The ceiling was documented as 4 KB and enforced with
    /// `String.suffix`, which counts Characters — so a stderr in a language that is not ASCII kept
    /// up to four times what the constant said. The accented text below is two bytes per character.
    @Test func theOutputCeilingIsCountedInBytesEvenWhenTheOutputIsNot() throws {
        let launcher = ChildProcessLauncher(keepBytes: 64)
        let waiter = Waiter()

        // 200 two-byte characters, well past the ceiling in both units.
        let noisy = String(repeating: "é", count: 200)
        let pid = try launcher.launch(
            URL(fileURLWithPath: "/bin/echo"), [noisy], onExit: waiter.onExit)
        #expect(waiter.wait())
        Thread.sleep(forTimeInterval: 0.2)

        let kept = try #require(launcher.lastOutput(of: pid))
        #expect(kept.utf8.count <= 64, "kept \(kept.utf8.count) bytes against a 64-byte ceiling")
        #expect(!kept.isEmpty)
    }

    /// The same unit mistake lived a second time in the sentence the owner reads.
    @Test func theSentencesOwnLimitIsAlsoCountedInBytes() {
        let trimmed = BridgeProcess.lastBytes(String(repeating: "é", count: 200), 64)

        #expect(trimmed.utf8.count <= 64)
    }

    @Test func textShorterThanTheLimitIsReturnedWhole() {
        #expect(BridgeProcess.lastBytes("bind: address already in use", 800)
            == "bind: address already in use")
    }
}
