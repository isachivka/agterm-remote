import Foundation
import Testing
@testable import AgtermRemoteCore

/// The bridge is a child of this app, and these are the three things that has to mean.
///
/// ### Why this replaced a supervisor rather than renaming one
///
/// The file this one stands in place of asked `launchctl` to act on a job with a label of its own.
/// **No installer in this repository ever wrote that plist**, and there is no `install.sh` here to
/// write one — so every Start and every Stop in the menu was a request against a job launchd had
/// never heard of, reported to the owner as a bridge that is not running. The menu items were lit and the machinery behind them could not work, which is the exact
/// defect of 2026-08-10 wearing a different coat.
///
/// The bridge in this repository says what it is in its own package comment: started by the menu-bar
/// app, meant to die with it, configured by flags because the parent already holds every answer. So
/// the app holds it as a child and this type is the holding.
///
/// ### The three properties, and why each one is here
///
/// 1. **The launch passes the address and this app's own pid.** The pid is not decoration: the bridge
///    polls it and exits when it goes, which is the only thing standing between a crashed menu-bar
///    app and a listener left on the owner's exposed port with no user interface anywhere that could
///    close it.
/// 2. **Repeated failure backs off and then gives up, in `.failed`, carrying a sentence.** A bridge
///    that cannot bind its port will never bind it by being asked a sixth time; a retry loop would
///    hide the reason forever behind a process that is always about to start.
/// 3. **Stop terminates and says so.** A stop that quietly did nothing is indistinguishable from one
///    that worked until somebody goes looking for the process.
struct BridgeProcessTests {

    /// A shared, test-controlled clock. The thirty-second reset is a property about elapsed time, and
    /// a test that measured it against the real clock would take half a minute to say so.
    private final class Clock: @unchecked Sendable {
        private let lock = NSLock()
        private var value = Date(timeIntervalSince1970: 0)
        var now: Date { lock.withLock { value } }
        func advance(_ seconds: TimeInterval) { lock.withLock { value += seconds } }
    }

    /// Launches nothing. Records what it was asked for, and **delivers exits the way a real one
    /// does: later, on another thread.**
    ///
    /// ### The double this replaced certified a defect
    ///
    /// It called `onExit` synchronously from inside `terminate`, so the exit of a killed child always
    /// arrived while `stopping` was still true and could never be mistaken for anything. `Process`
    /// does not do that. Its `terminationHandler` fires on a queue of its own, milliseconds later, so
    /// the exit of the child that Stop killed arrives **after** the child that Start spawned is
    /// already running — and the supervisor read it as the new child crashing, restarted, and left
    /// two bridges alive holding the owner's port with nothing tracking either.
    ///
    /// Reproduced with this double: `launched=[1001, 1002, 1003] terminated=[1001] leaked=[1002, 1003]`.
    ///
    /// A double that can only express the safe interleaving is a double that certifies whatever was
    /// written. This one models the loosest ordering the protocol permits, which is the one that has
    /// to be correct.
    private final class RecordingLauncher: ProcessLauncher, @unchecked Sendable {
        private let lock = NSLock()
        /// Serial, like the queue `Process` delivers its termination handlers on.
        private let exits = DispatchQueue(label: "bridge-process-tests.exits")
        private var _executables: [URL] = []
        private var _arguments: [String] = []
        private var _terminated: [Int32] = []
        private var _launched: [Int32] = []
        private var _lastPid: Int32 = 0
        private var onExitByPid: [Int32: @Sendable (Int32) -> Void] = [:]

        var executables: [URL] { lock.withLock { _executables } }
        var arguments: [String] { lock.withLock { _arguments } }
        var terminated: [Int32] { lock.withLock { _terminated } }
        var launched: [Int32] { lock.withLock { _launched } }
        var lastPid: Int32 { lock.withLock { _lastPid } }
        var launches: Int { lock.withLock { _launched.count } }

        /// Everything that was started and never terminated. The orphans, by name.
        var leaked: [Int32] {
            lock.withLock { _launched.filter { !_terminated.contains($0) } }
        }

        func launch(
            _ executable: URL, _ arguments: [String], onExit: @escaping @Sendable (Int32) -> Void,
        ) throws -> Int32 {
            lock.withLock {
                _executables.append(executable)
                _arguments = arguments
                _lastPid = 1001 + Int32(_launched.count)
                _launched.append(_lastPid)
                onExitByPid[_lastPid] = onExit
                return _lastPid
            }
        }

        /// Signals, and returns. The exit arrives afterwards, from somewhere else — which is the
        /// whole point of this double.
        func terminate(_ pid: Int32) {
            let exit: (@Sendable (Int32) -> Void)? = lock.withLock {
                _terminated.append(pid)
                return onExitByPid.removeValue(forKey: pid)
            }
            guard let exit else { return }
            exits.async { exit(15) }
        }

        /// Blocks until every exit queued so far has been delivered.
        func drain() { exits.sync {} }
    }

    /// Calls `onExit` before `launch` even returns, which is the hard case: the state machine must
    /// not overwrite the exit it has already handled with the `.running` it was about to announce.
    private final class AlwaysFailingLauncher: ProcessLauncher, @unchecked Sendable {
        private let lock = NSLock()
        private var _launches = 0
        private let clock: Clock?
        /// How long each run lasts before it dies, by launch number. Short of the list, runs are
        /// instantaneous.
        private let runFor: [TimeInterval]
        /// What the child said on the way out, so the failure can be shown to a person.
        let said: String

        init(clock: Clock? = nil, runFor: [TimeInterval] = [], said: String = "") {
            self.clock = clock
            self.runFor = runFor
            self.said = said
        }

        var launches: Int { lock.withLock { _launches } }

        func launch(
            _: URL, _: [String], onExit: @escaping @Sendable (Int32) -> Void,
        ) throws -> Int32 {
            let index: Int = lock.withLock {
                _launches += 1
                return _launches - 1
            }
            if index < runFor.count { clock?.advance(runFor[index]) }
            onExit(1)
            return 4242 + Int32(index)
        }

        func terminate(_: Int32) {}

        func lastOutput(of _: Int32) -> String? { said.isEmpty ? nil : said }
    }

    /// Cannot spawn at all — the shape of a bridge binary that is missing or not executable.
    private struct RefusingLauncher: ProcessLauncher {
        struct NoSuchFile: Error {}
        func launch(_: URL, _: [String], onExit _: @escaping @Sendable (Int32) -> Void) throws -> Int32 {
            throw NoSuchFile()
        }

        func terminate(_: Int32) {}
    }

    /// Retries run through the injected scheduler, so the four waits are asserted as values instead
    /// of being waited out. Every delay is recorded and then run immediately.
    private final class ImmediateSchedule: @unchecked Sendable {
        private let lock = NSLock()
        private var _delays: [TimeInterval] = []
        var delays: [TimeInterval] { lock.withLock { _delays } }

        var run: @Sendable (TimeInterval, @escaping @Sendable () -> Void) -> Void {
            { [self] delay, work in
                lock.withLock { _delays.append(delay) }
                work()
            }
        }
    }

    private static let executable = URL(fileURLWithPath: "/opt/agterm-remote/agterm-remote-bridge")
    private static let stateDir = URL(fileURLWithPath: "/opt/agterm-remote/state")

    private func bridge(
        _ launcher: ProcessLauncher,
        clock: Clock = Clock(),
        schedule: ImmediateSchedule = ImmediateSchedule(),
    ) -> BridgeProcess {
        BridgeProcess(
            launcher: launcher, executable: Self.executable, stateDir: Self.stateDir,
            now: { clock.now }, schedule: schedule.run)
    }

    // MARK: - 1. The launch

    /// **The address it listens on, and the pid it must not outlive.**
    @Test func startPassesTheAddressAndItsOwnPidToTheBridge() throws {
        let launcher = RecordingLauncher()
        let bridge = bridge(launcher)

        try bridge.start(listen: "0.0.0.0:8443", socket: nil)

        #expect(launcher.arguments.contains("--listen"))
        #expect(launcher.arguments.contains("0.0.0.0:8443"))
        #expect(launcher.arguments.contains("--parent-pid"))
        #expect(launcher.arguments.contains(String(ProcessInfo.processInfo.processIdentifier)))
        #expect(bridge.state == .running(pid: launcher.lastPid))
    }

    /// The state directory is required by the binary and has no default there on purpose, so the app
    /// passes its own rather than letting the bridge guess where the owner's identity lives.
    @Test func startPassesTheStateDirectoryItWasGiven() throws {
        let launcher = RecordingLauncher()

        try bridge(launcher).start(listen: "0.0.0.0:8443", socket: nil)

        #expect(launcher.arguments.contains("--state-dir"))
        #expect(launcher.arguments.contains(Self.stateDir.path))
        #expect(launcher.executables == [Self.executable])
    }

    /// **The app names no agterm socket.** `--socket` is the bridge's flag for a non-default control
    /// socket, and passing nothing means the bridge resolves its own default — which is the whole
    /// point: this app has no business knowing that path, and `BoundaryTests` fails if it learns it.
    @Test func noSocketIsPassedUnlessOneWasAskedFor() throws {
        let launcher = RecordingLauncher()

        try bridge(launcher).start(listen: "0.0.0.0:8443", socket: nil)

        #expect(!launcher.arguments.contains("--socket"))
        #expect(!launcher.arguments.contains { $0.hasSuffix(".sock") })
    }

    @Test func aSocketIsPassedWhenOneWasAskedFor() throws {
        let launcher = RecordingLauncher()

        try bridge(launcher).start(listen: "0.0.0.0:8443", socket: "/somewhere/else.socket")

        #expect(launcher.arguments.contains("--socket"))
        #expect(launcher.arguments.contains("/somewhere/else.socket"))
    }

    @Test func constructingItLaunchesNothing() {
        let launcher = RecordingLauncher()

        _ = bridge(launcher)

        #expect(launcher.launches == 0)
    }

    // MARK: - 2. Failure, backoff, and giving up

    /// Five launches and four waits, then it stops and says why. The numbers are asserted rather than
    /// the shape: "it backs off" is true of a loop that never ends.
    @Test func anExitRestartsWithBackoffAndThenGivesUp() throws {
        let launcher = AlwaysFailingLauncher()
        let schedule = ImmediateSchedule()

        let bridge = bridge(launcher, schedule: schedule)
        try bridge.start(listen: "0.0.0.0:8443", socket: nil)

        #expect(launcher.launches == 5, "must stop retrying rather than loop forever")
        #expect(schedule.delays == [0.5, 1, 2, 4])
        guard case .failed = bridge.state else {
            Issue.record("state must be .failed, was \(bridge.state)")
            return
        }
    }

    /// **The sentence is the point of `.failed`.** A port already in use is the failure this will
    /// actually meet, and "the bridge is not running" would leave the owner with nowhere to go.
    @Test func theFailureCarriesTheExitStatusAndWhatTheBridgeSaid() throws {
        let launcher = AlwaysFailingLauncher(said: "listen: address already in use")

        let bridge = bridge(launcher)
        try bridge.start(listen: "0.0.0.0:8443", socket: nil)

        guard case .failed(let sentence) = bridge.state else {
            Issue.record("state must be .failed, was \(bridge.state)")
            return
        }
        #expect(sentence.contains("address already in use"))
        #expect(sentence.contains("1"), "the exit status is what a log search starts from")
    }

    /// A bridge that ran for a working day and then died is not a bridge that cannot start, and it
    /// must not inherit the previous week's attempts. Thirty seconds is the line.
    @Test func aRunThatOutlastedThirtySecondsClearsTheAttemptsBeforeIt() throws {
        let clock = Clock()
        let launcher = AlwaysFailingLauncher(clock: clock, runFor: [31])
        let schedule = ImmediateSchedule()

        let bridge = bridge(launcher, clock: clock, schedule: schedule)
        try bridge.start(listen: "0.0.0.0:8443", socket: nil)

        #expect(launcher.launches == 6, "the long first run must not count towards the five")
        #expect(schedule.delays == [0.5, 0.5, 1, 2, 4], "and the wait starts again from the shortest")
    }

    /// Pressing Start after a failure tries again. Otherwise the owner fixes the port clash and the
    /// menu still refuses, which is a dead control with a live look.
    @Test func startingAgainAfterGivingUpIsAllowedToTryAgain() throws {
        let launcher = AlwaysFailingLauncher()

        let bridge = bridge(launcher)
        try bridge.start(listen: "0.0.0.0:8443", socket: nil)
        #expect(launcher.launches == 5)

        try bridge.start(listen: "0.0.0.0:8443", socket: nil)

        #expect(launcher.launches == 10)
    }

    /// A spawn that cannot even happen is thrown to the caller AND left in `.failed`, because the
    /// menu reads one and the alert reads the other, and a missing binary is not something a sixth
    /// attempt fixes.
    @Test func aSpawnThatCannotHappenIsReportedRatherThanRetried() {
        let bridge = bridge(RefusingLauncher())

        #expect(throws: (any Error).self) {
            try bridge.start(listen: "0.0.0.0:8443", socket: nil)
        }
        guard case .failed(let sentence) = bridge.state else {
            Issue.record("state must be .failed, was \(bridge.state)")
            return
        }
        #expect(sentence.contains("agterm-remote-bridge"), "the sentence must name what would not start")
    }

    // MARK: - 3. Stopping

    @Test func stopTerminatesAndReportsStopped() throws {
        let launcher = RecordingLauncher()
        let bridge = bridge(launcher)

        try bridge.start(listen: "0.0.0.0:8443", socket: nil)
        bridge.stop()

        #expect(launcher.terminated == [launcher.lastPid])
        #expect(bridge.state == .stopped)
    }

    /// **Stop is not the first of five restarts.** The exit a deliberate stop causes looks exactly
    /// like the exit a crash causes, and telling them apart is the difference between a Stop button
    /// and a Restart button that lies about its name.
    @Test func stopIsNotMistakenForACrashAndRestarted() throws {
        let launcher = RecordingLauncher()
        let bridge = bridge(launcher)

        try bridge.start(listen: "0.0.0.0:8443", socket: nil)
        bridge.stop()

        #expect(launcher.launches == 1)
        #expect(bridge.state == .stopped)
    }

    @Test func stoppingSomethingThatNeverStartedTerminatesNothing() {
        let launcher = RecordingLauncher()
        let bridge = bridge(launcher)

        bridge.stop()

        #expect(launcher.terminated.isEmpty)
        #expect(bridge.state == .stopped)
    }

    /// **Stop, then Start, must leave exactly one bridge.**
    ///
    /// The exit of the child Stop killed arrives after the child Start spawned is already running.
    /// Nothing in `onExit` says which child it belongs to — it carries a status and nothing else — so
    /// a supervisor that reads every exit as "the current child died" restarts on the strength of a
    /// death that already happened, and the bridge it just started is left running with nothing
    /// holding it. The owner's exposed port is then held by a process no menu item can reach.
    @Test func stopThenStartLeavesNoOrphan() throws {
        let launcher = RecordingLauncher()
        let bridge = bridge(launcher)

        try bridge.start(listen: "0.0.0.0:8443", socket: nil)
        bridge.stop()
        try bridge.start(listen: "0.0.0.0:8443", socket: nil)
        // The killed child's exit lands here, while the new one is running.
        launcher.drain()
        bridge.stop()
        launcher.drain()

        #expect(launcher.launches == 2, "the stale exit was read as the new child crashing")
        #expect(launcher.leaked.isEmpty, "left running with nothing tracking them: \(launcher.leaked)")
        #expect(launcher.terminated == launcher.launched)
        #expect(bridge.state == .stopped)
    }

    /// The same defect from the state machine's side: the stale exit must not move the state at all.
    @Test func aStaleExitIsNotReadAsTheLivingChildCrashing() throws {
        let launcher = RecordingLauncher()
        let bridge = bridge(launcher)

        try bridge.start(listen: "0.0.0.0:8443", socket: nil)
        bridge.stop()
        try bridge.start(listen: "0.0.0.0:8443", socket: nil)
        let second = launcher.lastPid
        launcher.drain()

        #expect(bridge.state == .running(pid: second))
    }

    /// Stop, Start, Stop, Start — the ordering that produced two live bridges in the field.
    @Test func repeatedStopAndStartNeverAccumulatesChildren() throws {
        let launcher = RecordingLauncher()
        let bridge = bridge(launcher)

        for _ in 0 ..< 4 {
            try bridge.start(listen: "0.0.0.0:8443", socket: nil)
            bridge.stop()
            launcher.drain()
        }

        #expect(launcher.launches == 4)
        #expect(launcher.leaked.isEmpty, "orphans: \(launcher.leaked)")
    }

    // MARK: - Finding the binary

    /// **The bundle first, the build directory second, the owner's own directory last** — and `PATH`
    /// nowhere. This app hands the bridge the port it listens on; running whichever binary of that
    /// name came first on somebody's `PATH` would be handing it to a different program.
    @Test func theBridgeIsLookedForInThreePlacesInOrder() {
        let resources = URL(fileURLWithPath: "/App.app/Contents/Resources")
        let running = URL(fileURLWithPath: "/build/debug/AgtermRemote")
        let state = URL(fileURLWithPath: "/home/.config/agterm-remote")

        var asked: [String] = []
        _ = BridgeProcess.locate(resources: resources, beside: running, stateDir: state) {
            asked.append($0)
            return false
        }

        #expect(asked == [
            "/App.app/Contents/Resources/agterm-remote-bridge",
            "/build/debug/agterm-remote-bridge",
            "/home/.config/agterm-remote/bin/agterm-remote-bridge",
        ])
    }

    @Test func theFirstOneThatIsThereWins() {
        let found = BridgeProcess.locate(
            resources: URL(fileURLWithPath: "/App.app/Contents/Resources"),
            beside: URL(fileURLWithPath: "/build/debug/AgtermRemote"),
            stateDir: URL(fileURLWithPath: "/home/.config/agterm-remote"),
        ) { $0.hasPrefix("/build") }

        #expect(found?.path == "/build/debug/agterm-remote-bridge")
    }

    /// Nothing anywhere is not an error and not a guess. It is the answer the menu turns into two
    /// greyed items.
    @Test func nowhereMeansNothingRatherThanAPathThatDoesNotExist() {
        let found = BridgeProcess.locate(
            resources: nil,
            beside: URL(fileURLWithPath: "/build/debug/AgtermRemote"),
            stateDir: URL(fileURLWithPath: "/home/.config/agterm-remote"),
        ) { _ in false }

        #expect(found == nil)
    }

    // MARK: - What the menu is told

    /// The menu is rebuilt from what it is told, so every transition has to arrive. A state that
    /// changes without announcing itself is a menu that offers Start for a running bridge.
    @Test func everyTransitionIsAnnounced() throws {
        let launcher = RecordingLauncher()
        let seen = Announcements()
        let bridge = bridge(launcher)
        bridge.onStateChange = { seen.append($0) }

        try bridge.start(listen: "0.0.0.0:8443", socket: nil)
        bridge.stop()

        #expect(seen.states == [.starting, .running(pid: launcher.lastPid), .stopped])
    }

    private final class Announcements: @unchecked Sendable {
        private let lock = NSLock()
        private var _states: [BridgeState] = []
        var states: [BridgeState] { lock.withLock { _states } }
        func append(_ state: BridgeState) { lock.withLock { _states.append(state) } }
    }

    // MARK: - Spawned and frozen

    /// A launcher whose children are alive and mute — the shape of a quarantined bridge, and of every
    /// other way a process can be started and never run.
    private final class MuteLauncher: ProcessLauncher, @unchecked Sendable {
        private let lock = NSLock()
        private var _terminated: [Int32] = []
        var terminated: [Int32] { lock.withLock { _terminated } }

        func launch(
            _: URL, _: [String], onExit _: @escaping @Sendable (Int32) -> Void,
        ) throws -> Int32 { 5150 }

        func terminate(_ pid: Int32) { lock.withLock { _terminated.append(pid) } }
        /// Nothing, ever. Not an empty string — a child that has written nothing at all.
        func lastOutput(of _: Int32) -> String? { nil }
    }

    /// Alive and talking, which is what a working bridge does within milliseconds of exec.
    private final class TalkativeLauncher: ProcessLauncher, @unchecked Sendable {
        private let lock = NSLock()
        private var _terminated: [Int32] = []
        var terminated: [Int32] { lock.withLock { _terminated } }

        func launch(
            _: URL, _: [String], onExit _: @escaping @Sendable (Int32) -> Void,
        ) throws -> Int32 { 5151 }

        func terminate(_ pid: Int32) { lock.withLock { _terminated.append(pid) } }
        func lastOutput(of _: Int32) -> String? { "listening on 0.0.0.0:8443" }
    }

    /// **Refused before the spawn, because after it there is nothing left to notice.**
    ///
    /// A quarantined binary spawns successfully and then freezes. Measured on macOS 26.6 against the
    /// real bundle: `Process.run` returns a pid, the child never reaches `main`, writes nothing and
    /// never exits. So the only honest moment to ask is before.
    @Test func aQuarantinedBridgeIsRefusedBeforeAnythingIsSpawned() {
        let launcher = RecordingLauncher()
        let bridge = BridgeProcess(
            launcher: launcher, executable: Self.executable, stateDir: Self.stateDir,
            isQuarantined: { _ in true })

        #expect(throws: BridgeProcess.Failure.self) {
            try bridge.start(listen: "0.0.0.0:8443", socket: nil)
        }
        #expect(launcher.launches == 0, "nothing may be spawned once quarantine is known")
        guard case .failed(let sentence) = bridge.state else {
            Issue.record("state must be .failed, was \(bridge.state)")
            return
        }
        // The sentence has to carry the cause AND the command, because the owner cannot see either
        // anywhere else: there is no dialogue, no log and no exit status.
        #expect(sentence.contains("quarantine"))
        #expect(sentence.contains("xattr -dr com.apple.quarantine"))
    }

    /// The instruction names the `.app`, not the binary inside it. Quarantine is set on every file of
    /// a downloaded bundle, so clearing it from the bridge alone would leave the app still held.
    @Test func theRemedyNamesTheApplicationRatherThanTheBinaryInsideIt() {
        let inside = URL(fileURLWithPath: "/Applications/AgtermRemote.app/Contents/Resources/agterm-remote-bridge")

        #expect(Quarantine.explanation(for: inside).contains("/Applications/AgtermRemote.app\""))
        #expect(Quarantine.enclosingBundle(of: inside)?.lastPathComponent == "AgtermRemote.app")
    }

    /// Outside a bundle — a development build with the binary beside the executable — there is no
    /// `.app` to name, and the instruction falls back to the file itself rather than inventing a path.
    @Test func outsideABundleTheRemedyNamesTheFileItself() {
        let loose = URL(fileURLWithPath: "/build/debug/agterm-remote-bridge")

        #expect(Quarantine.enclosingBundle(of: loose) == nil)
        #expect(Quarantine.explanation(for: loose).contains("/build/debug/agterm-remote-bridge\""))
    }

    /// **A live pid is not a working bridge.** Nothing else in this class can tell the two apart, so
    /// silence is the signal — and the child is stopped rather than left frozen under a menu that
    /// offers Start again.
    @Test func aChildThatSaysNothingIsStoppedAndReported() throws {
        let launcher = MuteLauncher()
        let silence = ImmediateSchedule()
        let bridge = BridgeProcess(
            launcher: launcher, executable: Self.executable, stateDir: Self.stateDir,
            watchForSilence: silence.run)

        try bridge.start(listen: "0.0.0.0:8443", socket: nil)

        #expect(silence.delays == [BridgeProcess.silenceCeiling])
        #expect(launcher.terminated == [5150], "a frozen child must not be left running")
        guard case .failed(let sentence) = bridge.state else {
            Issue.record("state must be .failed, was \(bridge.state)")
            return
        }
        #expect(sentence.contains("never said anything"))
    }

    /// And it does not fire on a bridge that is working. This is the half that would break a healthy
    /// app every two seconds if the condition were wrong.
    @Test func aChildThatSpokeIsLeftAlone() throws {
        let launcher = TalkativeLauncher()
        let silence = ImmediateSchedule()
        let bridge = BridgeProcess(
            launcher: launcher, executable: Self.executable, stateDir: Self.stateDir,
            watchForSilence: silence.run)

        try bridge.start(listen: "0.0.0.0:8443", socket: nil)

        #expect(launcher.terminated.isEmpty)
        #expect(bridge.state == .running(pid: 5151))
    }

    /// **The coupling that makes silence readable, held in place by a test.**
    ///
    /// The backstop above works only because the bridge's startup chatter reaches this app on stderr.
    /// Passing `--log` would send it to a file, every healthy bridge would then be silent, and the
    /// timer would kill it two seconds after every start. That is not a comment's job to prevent.
    @Test func noLogFileIsPassedBecauseSilenceIsReadAsAHang() throws {
        let launcher = RecordingLauncher()
        let bridge = bridge(launcher)

        try bridge.start(listen: "0.0.0.0:8443", socket: nil)

        #expect(!launcher.arguments.contains("--log"))
    }
}
