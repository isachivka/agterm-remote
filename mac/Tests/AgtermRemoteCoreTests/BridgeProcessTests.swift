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

        /// What the child has written. Nil by default, which is now the honest default: a spawned
        /// process that has said nothing is `.starting`, not `.running`.
        nonisolated(unsafe) var says: String?
        func lastOutput(of _: Int32) -> String? { says }

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

    /// The kill runs inline in these tests, which keeps every assertion about Stop reading exactly as
    /// it did before the kill moved off the caller's thread: terminate, then `.stopped`, in one act.
    ///
    /// **That is not the property being hidden.** The kill was made asynchronous because it blocks for
    /// up to twice the grace period, and what these tests are about is what happens to the child and
    /// to the state — the same questions, whichever thread answers them. The property that the caller
    /// is not made to wait is its own test, with its own launcher, and it holds the real seam:
    /// `stopDoesNotBlockTheCallerForTheGracePeriod`.
    private static let killInline: @Sendable (@escaping @Sendable () -> Void) -> Void = { $0() }

    private func bridge(
        _ launcher: ProcessLauncher,
        clock: Clock = Clock(),
        schedule: ImmediateSchedule = ImmediateSchedule(),
        watchForSilence: ImmediateSchedule? = nil,
        terminateOn: @escaping @Sendable (@escaping @Sendable () -> Void) -> Void = BridgeProcessTests.killInline,
    ) -> BridgeProcess {
        // Never scheduled unless a test asks for it. A synchronous readiness poll runs its whole ten
        // seconds of turns inside `start`, which is the point in the tests that are about readiness
        // and noise in every other one — those assert `.starting`, which is what a child that has not
        // announced itself is.
        let idle: @Sendable (TimeInterval, @escaping @Sendable () -> Void) -> Void = { _, _ in }
        return BridgeProcess(
            launcher: launcher, executable: Self.executable, stateDir: Self.stateDir,
            now: { clock.now }, schedule: schedule.run,
            watchForSilence: watchForSilence.map(\.run) ?? idle, terminateOn: terminateOn)
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
        // `.starting`, and this is the assertion that changed: a spawned pid is not a running bridge.
        // It becomes `.running` when the child announces a bound listener, which this launcher's
        // child has not done.
        #expect(bridge.state == .starting)
    }

    /// **The two flags that carry what stands in front of this Mac, which the bridge cannot observe.**
    ///
    /// `--advertise-scheme` decides how a pairing code tells the phone to open the address, and it is
    /// passed on every launch rather than left to the bridge's own default, so the two processes
    /// cannot drift into disagreeing about a deployment neither can see. `--on-link-tls` decides
    /// whether this port serves TLS, and it is a flag rather than a request because the answer is
    /// settled when the listener is built.
    @Test func startTellsTheBridgeWhatStandsInFrontOfThisMac() throws {
        for door in FrontDoor.allCases {
            let launcher = RecordingLauncher()
            try bridge(launcher).start(listen: "0.0.0.0:8443", socket: nil, frontDoor: door)

            let argv = launcher.arguments
            guard let at = argv.firstIndex(of: "--advertise-scheme") else {
                #expect(Bool(false), "\(door): the scheme must always be passed")
                continue
            }
            #expect(argv[at + 1] == door.advertiseScheme)
            #expect(argv.contains("--on-link-tls") == door.servesOnLinkTLS)
        }
    }

    /// The simplest deployment asks for nothing extra, so a bridge started by an owner who has never
    /// seen the question behaves exactly as it did before the question existed.
    @Test func theSimplestDeploymentAddsNoOnLinkTls() throws {
        let launcher = RecordingLauncher()

        try bridge(launcher).start(listen: "0.0.0.0:8443", socket: nil)

        #expect(!launcher.arguments.contains("--on-link-tls"))
        #expect(launcher.arguments.contains("plain"))
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

        // Still the second child, and still `.starting` because it has not announced itself — the
        // property under test is that the FIRST child's exit did not knock it out of that state.
        #expect(bridge.state == .starting)
        #expect(launcher.leaked == [second])
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
        // Its child announces itself, so the run reaches `.running` — through the readiness poll,
        // which is what promotes it now.
        launcher.says = "\(BridgeProcess.readyMarker) 0.0.0.0:8443"
        let seen = Announcements()
        let bridge = bridge(launcher, watchForSilence: ImmediateSchedule())
        bridge.onStateChange = { seen.append($0) }

        try bridge.start(listen: "0.0.0.0:8443", socket: nil)
        bridge.stop()

        // **`.stopping` is in this list, and that is the point of it.** A stop is not instantaneous —
        // the kill signals, waits, escalates and waits again — and the menu has to be able to say so
        // for the duration rather than freezing on `.running` or lying about `.stopped`.
        #expect(seen.states == [.starting, .running(pid: launcher.lastPid), .stopping, .stopped])
    }

    /// **The measurement that made `.stopping` exist.**
    ///
    /// `ChildProcessLauncher.terminate` returns only when the child is gone: `SIGTERM`, wait the grace
    /// period, `SIGKILL`, wait again — four seconds at the shipped grace period, on the caller's
    /// thread, which is the main thread every time the owner presses Stop. Four seconds of a menu-bar
    /// app not answering is an app that has crashed as far as anybody looking at it can tell.
    ///
    /// So the kill is timed here against a launcher that takes a measurable age to return, and the
    /// call is required to come back long before it does. The two halves after it are the ones that
    /// made the old blocking version worth keeping: the state says `.stopping` rather than a lie, and
    /// `.stopped` still arrives only once the child has actually gone.
    @Test func stopDoesNotBlockTheCallerForTheGracePeriod() throws {
        let launcher = SlowToDieLauncher(takes: 1)
        let seen = Announcements()
        // **Built without a `terminateOn`, deliberately.** Every other test in this file runs the kill
        // inline so its assertions stay about the child rather than about threads; this one is about
        // the thread, so it takes the production default and nothing else.
        let bridge = BridgeProcess(
            launcher: launcher, executable: Self.executable, stateDir: Self.stateDir,
            watchForSilence: { _, _ in })
        bridge.onStateChange = { seen.append($0) }
        try bridge.start(listen: "0.0.0.0:8443", socket: nil)

        let began = Date()
        bridge.stop()
        let waited = Date().timeIntervalSince(began)

        #expect(waited < 0.2, "stop() held its caller for \(waited)s")
        #expect(bridge.state == .stopping, "the menu has nothing honest to say during the kill")
        launcher.finish()
        #expect(bridge.state == .stopped, "`.stopped` must still mean the child is gone")
        #expect(launcher.terminated == [launcher.lastPid])
    }

    /// A launcher whose `terminate` does not return until it is told to, which is what a child that
    /// ignores `SIGTERM` looks like from the supervisor's side.
    private final class SlowToDieLauncher: ProcessLauncher, @unchecked Sendable {
        private let lock = NSLock()
        private let gate = DispatchSemaphore(value: 0)
        private let done = DispatchSemaphore(value: 0)
        private let takes: TimeInterval
        private var _terminated: [Int32] = []
        private(set) var lastPid: Int32 = 0

        var terminated: [Int32] { lock.withLock { _terminated } }

        init(takes: TimeInterval) { self.takes = takes }

        func launch(
            _: URL, _: [String], onExit _: @escaping @Sendable (Int32) -> Void,
        ) throws -> Int32 {
            lastPid = 4242
            return lastPid
        }

        func terminate(_ pid: Int32) {
            // Waits, exactly as the real one does across two grace periods. Released by `finish`, or
            // by the ceiling, so a mistake here cannot hang the suite.
            _ = gate.wait(timeout: .now() + takes)
            lock.withLock { _terminated.append(pid) }
            done.signal()
        }

        /// Lets the kill complete, and waits for the supervisor to have seen it.
        func finish() {
            gate.signal()
            _ = done.wait(timeout: .now() + takes + 1)
        }
    }

    private final class Announcements: @unchecked Sendable {
        private let lock = NSLock()
        private var _states: [BridgeState] = []
        var states: [BridgeState] { lock.withLock { _states } }
        func append(_ state: BridgeState) { lock.withLock { _states.append(state) } }
    }

    // MARK: - Spawned and frozen

    /// A launcher whose child is alive and says exactly `said` — nil for one that says nothing at
    /// all. The shape of a bridge macOS froze, of one that failed before binding, and of a healthy
    /// one, depending on what it is given.
    private final class SayingLauncher: ProcessLauncher, @unchecked Sendable {
        private let lock = NSLock()
        private var _terminated: [Int32] = []
        private let said: String?
        var terminated: [Int32] { lock.withLock { _terminated } }

        init(_ said: String?) { self.said = said }

        func launch(
            _: URL, _: [String], onExit _: @escaping @Sendable (Int32) -> Void,
        ) throws -> Int32 { 5150 }

        func terminate(_ pid: Int32) { lock.withLock { _terminated.append(pid) } }
        func lastOutput(of _: Int32) -> String? { said }
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

    /// **A live pid is not a working bridge, and neither is a talkative one.**
    ///
    /// The condition is the bridge's own ready line, not "it said something". A child that printed a
    /// warning and then hung before binding satisfies "said something" while answering nothing —
    /// which is why the first row below is here alongside total silence.
    @Test(arguments: [
        (nil, "a child that never wrote a byte"),
        ("warning: could not read the resize cache", "a child that spoke but never bound"),
    ] as [(String?, String)])
    func aChildThatNeverReportsAListeningAddressIsStoppedAndReported(
        said: String?, what: String,
    ) throws {
        let launcher = SayingLauncher(said)
        let watch = ImmediateSchedule()
        // The kill runs inline, as it does everywhere else in this file: the question here is whether
        // the frozen child is ended and what the owner is told, not which thread ends it.
        let bridge = BridgeProcess(
            launcher: launcher, executable: Self.executable, stateDir: Self.stateDir,
            watchForSilence: watch.run, terminateOn: Self.killInline)

        try bridge.start(listen: "0.0.0.0:8443", socket: nil)

        // The poll, spelled out: one turn every `readyStep` until the ceiling is spent. Asserted as
        // the shape rather than as a single delay, because the state is `.starting` throughout and
        // the turns are what make that a bounded claim rather than a permanent one.
        #expect(watch.delays.count == BridgeProcess.readyAttempts, "\(what)")
        #expect(watch.delays.allSatisfy { $0 == BridgeProcess.readyStep }, "\(what)")
        #expect(launcher.terminated == [5150], "\(what): a frozen child must not be left running")
        guard case .failed(let sentence) = bridge.state else {
            Issue.record("\(what): state must be .failed, was \(bridge.state)")
            return
        }
        #expect(sentence.contains("never reported a listening address"))
        // Whatever it did manage to say is evidence, and this app did not write it.
        if let said { #expect(sentence.contains(said)) }
    }

    /// And it does not fire on a bridge that is working. This is the half that would kill a healthy
    /// app on every start if the condition were wrong — which it was: the ceiling was two seconds,
    /// measured against announce-after-`main` rather than spawn-to-`main`, and a fresh 18 MB copy
    /// costs 548-606 ms in signature validation before the bridge runs its first instruction.
    @Test func aChildThatReportedItsListeningAddressIsLeftAlone() throws {
        let launcher = SayingLauncher("\(BridgeProcess.readyMarker) 0.0.0.0:8443")
        let watch = ImmediateSchedule()
        let bridge = BridgeProcess(
            launcher: launcher, executable: Self.executable, stateDir: Self.stateDir,
            watchForSilence: watch.run)

        try bridge.start(listen: "0.0.0.0:8443", socket: nil)

        #expect(launcher.terminated.isEmpty)
        #expect(bridge.state == .running(pid: 5150))
        // One turn, not forty. A bridge that is up stops being described as starting immediately.
        #expect(watch.delays == [BridgeProcess.readyStep])
    }

    /// **A spawned pid is announced as `.starting`, and the menu is told so.**
    ///
    /// This is the whole of the previous defect: `.running` was announced on spawn, so a bridge macOS
    /// had frozen was described to the owner as Running for the entire ceiling — ten seconds of a
    /// menu asserting something that was never true — before flipping to failed.
    @Test func aSpawnedChildIsStartingUntilItAnnouncesItself() throws {
        let launcher = SayingLauncher(nil)
        let watch = ImmediateSchedule()
        let seen = Announcements()
        let bridge = BridgeProcess(
            launcher: launcher, executable: Self.executable, stateDir: Self.stateDir,
            watchForSilence: { _, _ in })
        bridge.onStateChange = { seen.append($0) }

        try bridge.start(listen: "0.0.0.0:8443", socket: nil)

        #expect(bridge.state == .starting)
        #expect(seen.states == [.starting], "nothing may claim it is running")
        _ = watch
    }

    /// **The freeze message must not hand out a command that cannot work.**
    ///
    /// Measured, four times out of four: `com.apple.quarantine` can be removed right up until a
    /// blocked exec has been attempted on that item, and is refused with EPERM from then on — on the
    /// binary and on the enclosing `.app`, permanently. This sentence is printed only after a spawn
    /// that produced nothing, which in the Gatekeeper case IS that blocked exec. So `xattr -dr` here
    /// would fail every time, and the owner would conclude the instructions are broken.
    ///
    /// The pre-spawn refusal is the opposite case and keeps the command: nothing has been spawned
    /// there, so removal still works.
    @Test func theFreezeMessageOffersTheFinderRatherThanACommandThatWouldBeRefused() {
        let sentence = BridgeProcess.notReadySentence(executable: Self.executable, said: nil)

        #expect(!sentence.contains("xattr -dr"), "a command that is guaranteed to fail here")
        #expect(sentence.contains("Finder"), "the door that does still work")
        // `xattr -l` stays: reading the attribute is never refused, and it is how somebody confirms
        // this is what happened.
        #expect(sentence.contains("xattr -l"))

        // And the pre-spawn message, which is printed before anything has been blocked, still does.
        #expect(Quarantine.explanation(for: Self.executable).contains("xattr -dr com.apple.quarantine"))
    }

    /// **The contract, read out of the other language.**
    ///
    /// The invariant that makes the backstop mean anything — *the bridge prints this once its
    /// listener is bound* — lives in the Go half. A test in this package asserting only that this app
    /// omits `--log` held nothing on that side: a reworded log line there would have disarmed the
    /// check here silently, and nothing would have failed.
    ///
    /// So the literal is compared against the bridge's own source. The Go suite proves the constant
    /// is actually printed, on a real bound port; this proves the string this app waits for is that
    /// constant.
    @Test func theReadyMarkerIsTheOneTheBridgePrints() throws {
        let repository = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        let main = repository.appending(path: "bridge/cmd/agterm-remote-bridge/main.go")
        let go = try String(contentsOf: main, encoding: .utf8)

        #expect(
            go.contains("const readyLine = \"\(BridgeProcess.readyMarker)\""),
            "the bridge's readyLine and this app's readyMarker have come apart")
    }

    /// **A second thing the ready line depends on.** `--log` would send the bridge's output to a
    /// file, this app would never see the marker, and the backstop would stop a healthy bridge on
    /// every start. Weaker than the cross-language check above, and kept because it guards a
    /// different way of breaking the same thing.
    @Test func noLogFileIsPassedBecauseTheReadyLineArrivesOnStderr() throws {
        let launcher = RecordingLauncher()
        let bridge = bridge(launcher)

        try bridge.start(listen: "0.0.0.0:8443", socket: nil)

        #expect(!launcher.arguments.contains("--log"))
    }

    /// The refusal carries the command **separately**, because an `NSAlert`'s informative text is not
    /// selectable and a command that exists only inside the paragraph is one somebody retypes by hand.
    @Test func theQuarantineRefusalCarriesACopyableCommand() {
        let bridge = BridgeProcess(
            launcher: RecordingLauncher(), executable: Self.executable, stateDir: Self.stateDir,
            isQuarantined: { _ in true })

        do {
            try bridge.start(listen: "0.0.0.0:8443", socket: nil)
            Issue.record("it started")
        } catch let failure as BridgeProcess.Failure {
            #expect(failure.copyable == "xattr -dr com.apple.quarantine \"\(Self.executable.path)\"")
            // And the paragraph still contains it, for the tooltip and for anywhere with no button.
            #expect("\(failure)".contains(failure.copyable ?? "\u{0}"))
        } catch {
            Issue.record("wrong error: \(error)")
        }
    }
}
