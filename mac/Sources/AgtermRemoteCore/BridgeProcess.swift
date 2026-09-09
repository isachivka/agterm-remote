import Foundation

/// Where the bridge is, as far as anything that draws a menu is concerned.
///
/// Four cases and no fifth. `.failed` carries a sentence rather than a code because the only reader
/// is a person: the two failures this will actually meet are a port already in use and a binary that
/// is not there, and neither is diagnosable from a number.
public enum BridgeState: Equatable, Sendable {
    case stopped
    case starting
    case running(pid: Int32)
    /// Gave up. The string is shown to the owner verbatim, so it has to be a sentence.
    case failed(String)
}

/// Starting a child process, being told when it dies, and killing it. **The whole seam.**
///
/// It exists so [BridgeProcess] — the backoff, the ceiling, the state machine — is testable without
/// anything being spawned. The production conformance is [ChildProcessLauncher], below, and it is the
/// only place in this package that calls `Process.run`.
public protocol ProcessLauncher: Sendable {

    /// Spawns it and returns the pid. `onExit` is called once, with the exit status, whenever the
    /// child dies — including before this function returns, which is a case the caller has to survive
    /// rather than an abuse.
    func launch(
        _ executable: URL, _ arguments: [String], onExit: @escaping @Sendable (Int32) -> Void,
    ) throws -> Int32

    /// Asks it to stop. `SIGTERM` rather than `SIGKILL`: the bridge unwinds on that signal — it
    /// closes its listener, removes its control socket and puts back a window it resized — and a
    /// process killed outright would leave the owner looking at the consequences of the last one.
    func terminate(_ pid: Int32)

    /// What the child said on its way out, if anything was kept. **Defaulted to nothing on purpose**,
    /// so a test double implements the two members above and no more; a launcher that captures
    /// output overrides it, and that is the whole reason `.failed` can say *address already in use*
    /// instead of *the bridge is not running*.
    func lastOutput(of pid: Int32) -> String?
}

public extension ProcessLauncher {
    func lastOutput(of _: Int32) -> String? { nil }
}

/// The bridge, held as a child of this app.
///
/// ### It is a child, not a service, and that is the bridge's own position
///
/// The binary says so in its package comment: started by the menu-bar app, meant to die with it,
/// configured by flags because the parent already holds every answer and a configuration file would
/// be a second copy of them that can disagree. What stood here before asked `launchctl` to act on a
/// job labelled for a plist **no installer in this repository writes**, so both menu items were lit
/// over machinery that could not work.
///
/// ### Two halves of one guarantee, and neither is enough alone
///
/// This app terminates the bridge when it quits — from `applicationWillTerminate` and from a `SIGTERM`
/// handler, because a menu-bar app can be killed without its delegate ever running. And the bridge
/// watches `--parent-pid` and exits when it goes, because a process that is `SIGKILL`ed runs no
/// handler at all and a child is re-parented rather than reaped. The second half polls, so a crashed
/// app can leave a bridge alive for a couple of seconds; that is the cost of the guarantee, not a gap
/// in it.
///
/// ### Why it gives up
///
/// A bridge whose port is taken will not take it on the sixth attempt. Retrying forever would put the
/// reason permanently out of reach behind a process that is always about to start, and would leave
/// the menu offering Stop for something that is not running. Five launches, four waits, then
/// `.failed` with what the bridge said.
public final class BridgeProcess: @unchecked Sendable {

    /// The one executable this app ever launches for the bridge. Named here rather than at the call
    /// site so `BoundaryTests` has a single string to hold the app to.
    public static let executableName = "agterm-remote-bridge"

    /// **Where the bridge is looked for, in order, and nowhere else.**
    ///
    /// Three places and no search path: inside the bundle, where `bundle.sh` puts it; beside the
    /// running executable, which is what `swift run` produces during development; and under the
    /// owner's own state directory, which is where somebody who built the Go half by hand would put
    /// it. `PATH` is deliberately not consulted — running whichever `agterm-remote-bridge` happened
    /// to come first on somebody's `PATH` would be a different program than the reviewed one, and
    /// this app hands that program the port it listens on.
    ///
    /// Returns nil when it is in none of them, and the caller's job is then to grey the menu items
    /// rather than to offer a control that cannot work.
    ///
    /// The first candidate is spelled by `BundledBridge`, not here. That layout —
    /// `Contents/Resources/agterm-remote-bridge` — is a thing `bundle.sh` and this lookup have to
    /// agree about, and a second copy of it in this file is the way they would stop agreeing.
    ///
    /// - Parameter isExecutable: injected so the order can be asserted without a file system.
    public static func locate(
        resources: URL?,
        beside: URL,
        stateDir: URL,
        isExecutable: (String) -> Bool = { FileManager.default.isExecutableFile(atPath: $0) },
    ) -> URL? {
        let candidates = [
            resources.map(BundledBridge.candidate(inResources:)),
            beside.deletingLastPathComponent().appending(path: executableName),
            stateDir.appending(path: "bin").appending(path: executableName),
        ]
        return candidates.compactMap(\.self).first { isExecutable($0.path) }
    }

    /// The waits between the five launches. Four values for four gaps.
    static let backoff: [TimeInterval] = [0.5, 1, 2, 4]

    /// Five, and then it stops. See the note above about the sixth attempt.
    static let maxLaunches = 5

    /// A run longer than this was a bridge that worked, so the attempts before it are not evidence
    /// about the attempt after it.
    static let longEnoughToForget: TimeInterval = 30

    public enum Failure: Error, Equatable {
        /// Nothing was spawned at all. Carries the sentence that is also in `.failed`.
        case couldNotStart(String)
    }

    private let launcher: ProcessLauncher
    private let executable: URL
    private let stateDir: URL
    private let parentPID: Int32
    private let now: @Sendable () -> Date
    private let schedule: @Sendable (TimeInterval, @escaping @Sendable () -> Void) -> Void

    private let lock = NSRecursiveLock()
    private var _state: BridgeState = .stopped
    private var listenAddress = ""
    private var socketPath: String?
    private var launches = 0
    private var startedRunningAt = Date(timeIntervalSince1970: 0)
    private var pid: Int32?
    /// True between `stop()` and the exit it causes, so a deliberate stop is not read as a crash.
    private var stopping = false
    /// True while `launcher.launch` has not returned yet. A launcher may report the exit before it
    /// hands back the pid, and announcing `.running` after that would overwrite the truth with an
    /// announcement of something that already stopped being so.
    private var launchInFlight = false
    private var exitDuringLaunch: Int32?
    /// Bumped by every `start` and every `stop`. A retry waiting out its backoff belongs to the run
    /// that scheduled it, and a press that happened since is the newer instruction — without this,
    /// pressing Start while a retry was pending would leave two bridges fighting over one port.
    private var generation = 0

    /// **Which child an exit belongs to.** Bumped once per launch, captured in that launch's callback,
    /// and compared when the exit arrives.
    ///
    /// `onExit` carries a status and nothing else, and a real `Process` delivers it from its own
    /// queue milliseconds after the fact. So the exit of a child that Stop killed lands **after** the
    /// child that Start spawned is already running. Without this counter that exit was read as the
    /// living child crashing: the supervisor restarted, and the bridge it had just started was left
    /// running with nothing tracking it — a listener on the owner's exposed port that no menu item
    /// could reach. Measured as `launched=[1001, 1002, 1003] terminated=[1001, 1003] leaked=[1002]`.
    ///
    /// `stopping` cannot do this job: `start()` clears it, and by the time the stale exit arrives it
    /// is false again.
    private var runID = 0

    /// Every transition, in order. The menu is rebuilt from this rather than from a poll, because a
    /// menu that asks whenever it happens to be opened is wrong for as long as it is closed.
    ///
    /// Called on whatever thread the child died on. A caller that touches AppKit must hop.
    public var onStateChange: (@Sendable (BridgeState) -> Void)?

    /// - Parameters:
    ///   - parentPID: the pid the bridge should follow. This app's own, always, in production — it is
    ///     an argument so a test can assert what was passed without spawning anything.
    ///   - now: the clock, so the thirty-second reset is testable in less than thirty seconds.
    ///   - schedule: how a retry waits. Asynchronous in production, deliberately: waiting inline
    ///     would hold this object's lock for up to four seconds on whatever thread the child died on,
    ///     and the main thread would then block behind it the next time somebody opened the menu.
    public init(
        launcher: ProcessLauncher,
        executable: URL,
        stateDir: URL,
        parentPID: Int32 = ProcessInfo.processInfo.processIdentifier,
        now: @escaping @Sendable () -> Date = Date.init,
        schedule: @escaping @Sendable (TimeInterval, @escaping @Sendable () -> Void) -> Void = {
            delay, work in
            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + delay, execute: work)
        },
    ) {
        self.launcher = launcher
        self.executable = executable
        self.stateDir = stateDir
        self.parentPID = parentPID
        self.now = now
        self.schedule = schedule
    }

    public var state: BridgeState { lock.withLock { _state } }

    /// Starts it, and keeps starting it up to the ceiling if it keeps dying.
    ///
    /// Throws only when nothing could be spawned — a missing or unexecutable binary — because that is
    /// the one failure the caller can act on synchronously and the one no retry repairs. A bridge
    /// that starts and then exits is reported through `state` and `onStateChange` instead, since by
    /// the time it has given up the press that caused it is long over.
    public func start(listen: String, socket: String?) throws {
        try lock.withLock {
            if case .running = _state { return }
            listenAddress = listen
            socketPath = socket
            // Pressing Start after a failure is a fresh five. The owner has just done something about
            // the port, or the binary, and a counter that remembered would refuse to try.
            launches = 0
            stopping = false
            generation += 1
            set(.starting)
            if let error = launchOnce() { throw error }
        }
    }

    /// Terminates it, and reports `.stopped` **only once the child has actually gone**.
    ///
    /// The announcement used to come first, on the reasoning that the owner's instruction is not in
    /// doubt. It was wrong for a reason measured rather than argued: a child that ignores `SIGTERM`
    /// stayed alive while the state said `.stopped`, the pid was cleared, a second Stop was a no-op
    /// because there was nothing left to signal, and the next Start added a **second** live bridge.
    /// `terminate` now escalates and returns only when the child is gone — see
    /// [ChildProcessLauncher.terminate] — so the sentence the menu shows is a fact rather than a
    /// request.
    ///
    /// Cost, stated where it happens: the caller waits for however long the child takes to die, up to
    /// the launcher's grace period. In practice that is milliseconds; the bridge closes its listener
    /// and goes. It is bounded, and the alternative is a stop button that lies.
    public func stop() {
        lock.withLock {
            stopping = true
            generation += 1
            if let pidToKill = pid {
                launcher.terminate(pidToKill)
                pid = nil
            }
            if _state != .stopped { set(.stopped) }
        }
    }

    /// The argv. Every flag the binary requires, and nothing it does not.
    ///
    /// `--log` is not passed, so the bridge writes to stderr and [ChildProcessLauncher] keeps the last
    /// of it — which is what turns a failed bind into a sentence the owner can read. A log file would
    /// be a better home for a running bridge's chatter and a worse one for the thing that has to
    /// reach a menu.
    func arguments() -> [String] {
        var argv = [
            "--listen", listenAddress,
            "--state-dir", stateDir.path,
            // The other half of dying with the app. Zero would disable it, and this app never passes
            // zero: a bridge outliving its parent holds the owner's exposed port with no user
            // interface left anywhere that could close it.
            "--parent-pid", String(parentPID),
        ]
        // Absent unless somebody asked. The bridge resolves its own default, and this app must not
        // learn that path — see BoundaryTests.
        if let socketPath, !socketPath.isEmpty {
            argv += ["--socket", socketPath]
        }
        return argv
    }

    /// - Returns: the spawn error, when there was one, for `start` to rethrow.
    private func launchOnce() -> Failure? {
        launches += 1
        runID += 1
        let mine = runID
        launchInFlight = true
        exitDuringLaunch = nil
        // Before the spawn, not after it: the child's life starts when it is spawned, and a launcher
        // that returns only once the child is already dead would otherwise make every run look
        // instantaneous — which is the measurement the thirty-second reset is made of.
        startedRunningAt = now()
        let started: Int32
        do {
            started = try launcher.launch(executable, arguments()) { [weak self] status in
                self?.handle(exit: status, run: mine)
            }
        } catch {
            launchInFlight = false
            let sentence = "\(executable.lastPathComponent) would not start: \(error). "
                + "The bridge is at \(executable.path)."
            set(.failed(sentence))
            return .couldNotStart(sentence)
        }
        launchInFlight = false
        pid = started
        // It may already be dead. Handled here rather than inside the callback so the exit is never
        // processed before the pid it belongs to has been recorded — and `.running` is not announced
        // for a process that has already gone.
        if let status = exitDuringLaunch {
            exitDuringLaunch = nil
            handle(exit: status, run: mine)
        } else {
            set(.running(pid: started))
        }
        return nil
    }

    private func handle(exit status: Int32, run: Int) {
        lock.withLock {
            // **An exit from a child we are no longer running is not news about the one we are.**
            // See the note on `runID`: this is the whole fix for Stop-then-Start orphaning a bridge.
            guard run == runID else { return }
            if launchInFlight {
                exitDuringLaunch = status
                return
            }
            // We asked for this one. `stop()` owns the announcement, and it made it only after the
            // launcher confirmed the child had actually gone.
            if stopping { return }
            let pidThatDied = pid
            pid = nil
            if now().timeIntervalSince(startedRunningAt) > Self.longEnoughToForget {
                launches = 0
            }
            guard launches < Self.maxLaunches else {
                set(.failed(giveUpSentence(status: status, pid: pidThatDied)))
                return
            }
            // Clamped at BOTH ends. The upper end is the obvious one — a fifth wait would run off
            // the list. The lower end is the one that crashed: a run past the reset sets the count
            // back to zero, and the series that follows starts again from the shortest wait rather
            // than from an index before the first.
            let wait = Self.backoff[min(max(launches - 1, 0), Self.backoff.count - 1)]
            let mine = generation
            set(.starting)
            schedule(wait) { [weak self] in
                guard let self else { return }
                lock.withLock {
                    // A Stop, or a fresh Start, arrived while this retry was waiting. Either way it
                    // is no longer ours to make.
                    guard generation == mine, !stopping, case .starting = _state else { return }
                    _ = launchOnce()
                }
            }
        }
    }

    /// The action first and the child's own words last, on their own lines.
    ///
    /// Measured against the real binary: a failed bind arrives as
    /// `listen tcp <address>: bind: address already in use`, which is the whole diagnosis. Run
    /// together with our sentence it read as one ungrammatical paragraph, so the two are separated —
    /// what the owner must do is ours to say, and what went wrong is the bridge's.
    private func giveUpSentence(status: Int32, pid: Int32?) -> String {
        var sentence = "The bridge stopped \(Self.maxLaunches) times in a row, the last time with "
            + "exit status \(status), so it is no longer being restarted. Fix what is stopping it "
            + "and start it again from this menu."
        if let pid, let said = launcher.lastOutput(of: pid)?
            .trimmingCharacters(in: .whitespacesAndNewlines), !said.isEmpty {
            sentence += "\n\nIt said:\n\(Self.lastBytes(said, Self.sentenceOutputLimit))"
        }
        return sentence
    }

    /// How much of the child's talk reaches the owner, **in bytes**. The launcher keeps more than a
    /// status line can show; the end is the part that explains the death.
    private static let sentenceOutputLimit = 800

    /// The last `limit` BYTES, not the last `limit` Characters. `String.suffix` counts Characters,
    /// so a non-ASCII diagnostic would carry up to four times the ceiling named above — a limit
    /// documented in one unit and enforced in another.
    static func lastBytes(_ text: String, _ limit: Int) -> String {
        let bytes = Array(text.utf8)
        guard bytes.count > limit else { return text }
        return String(decoding: bytes.suffix(limit), as: UTF8.self)
    }

    private func set(_ next: BridgeState) {
        _state = next
        onStateChange?(next)
    }
}

/// The production launcher. **The only thing in this package that spawns a process it supervises.**
///
/// It keeps the last of the child's output because that is the difference between a `.failed` that
/// names a port clash and one that says nothing. Bounded two ways — a byte ceiling per child and a
/// ceiling on how many children are remembered — because this object lives as long as the app does
/// and a map that only grows is a leak with a slow fuse.
public final class ChildProcessLauncher: ProcessLauncher, @unchecked Sendable {

    /// How much of one child's talk is kept, **in bytes**.
    ///
    /// Bytes and not Characters. This was `String.suffix`, which counts Characters, so a stderr in a
    /// non-ASCII language retained up to four times the ceiling this constant names — a limit that
    /// was documented in one unit and enforced in another.
    public static let defaultKeepBytes = 4096

    /// How many children's output is remembered at once. The one that just exited plus a little
    /// history; `.failed` only ever reads the most recent.
    static let keepOutputsFor = 8

    /// How long a child gets to leave on its own after `SIGTERM` before it is killed outright.
    public static let defaultGracePeriod: TimeInterval = 2

    private let keepBytes: Int
    private let grace: TimeInterval

    private let lock = NSLock()
    private var children: [Int32: Process] = [:]
    /// Signalled when a child's termination handler has run, so `terminate` can wait for the fact
    /// rather than for a timeout.
    private var gone: [Int32: DispatchSemaphore] = [:]
    /// A child that died between `run()` returning and its pid being recorded. **This is a real
    /// window**: the termination handler fires on another queue and used to remove a key that had
    /// not been inserted yet, after which the insert put it back and nothing ever took it out again.
    private var exitedBeforeRegistration: Set<Int32> = []
    private var said: [Int32: String] = [:]
    private var outputOrder: [Int32] = []

    /// - Parameters:
    ///   - gracePeriod: how long `terminate` waits after `SIGTERM` before escalating. Injected so a
    ///     test does not have to wait two seconds to prove the escalation happens.
    public init(gracePeriod: TimeInterval = defaultGracePeriod, keepBytes: Int = defaultKeepBytes) {
        grace = gracePeriod
        self.keepBytes = keepBytes
    }

    public func launch(
        _ executable: URL, _ arguments: [String], onExit: @escaping @Sendable (Int32) -> Void,
    ) throws -> Int32 {
        let task = Process()
        task.executableURL = executable
        task.arguments = arguments
        let output = Pipe()
        task.standardOutput = output
        task.standardError = output
        // No environment of our own and no working directory of our own: the bridge takes every
        // answer it needs as a flag, on purpose, and a second channel for configuration is a second
        // place for the two to disagree.

        // Drained continuously rather than read at the end. A pipe nobody reads fills, and a child
        // that blocks writing to a full pipe is a bridge that stops answering the phone for reasons
        // nothing in this app would ever explain.
        output.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let chunk = handle.availableData
            guard !chunk.isEmpty else { return }
            self?.remember(String(decoding: chunk, as: UTF8.self), of: task.processIdentifier)
        }

        task.terminationHandler = { [weak self] finished in
            let pid = finished.processIdentifier
            output.fileHandleForReading.readabilityHandler = nil
            // Whatever was written between the last readability callback and the exit.
            if let rest = try? output.fileHandleForReading.readToEnd(), !rest.isEmpty {
                self?.remember(String(decoding: rest, as: UTF8.self), of: pid)
            }
            if let launcher = self {
                launcher.lock.withLock {
                    // Either we are removing a registered child, or we got here first and the
                    // registration below must know not to insert it.
                    if launcher.children.removeValue(forKey: pid) == nil {
                        launcher.exitedBeforeRegistration.insert(pid)
                    }
                    // Signalled BEFORE onExit: `terminate` is waiting on this, and `onExit` reaches
                    // BridgeProcess, which takes a lock the stopping thread is holding. Signal first
                    // and neither side waits on the other.
                    launcher.gone[pid]?.signal()
                }
            }
            // A process killed by a signal has no exit status of its own; the shell convention of
            // 128 plus the signal is used so the number in the sentence is never a bare zero for a
            // bridge that was cut down.
            let status = finished.terminationReason == .uncaughtSignal
                ? 128 + finished.terminationStatus
                : finished.terminationStatus
            onExit(status)
        }

        try task.run()
        let pid = task.processIdentifier
        // The window this class's one race lives in, made reachable on purpose. A short-lived child
        // can be dead and handled before the line below records it, and the test that proves the
        // record is not put back afterwards cannot rely on winning a race by coincidence — measured:
        // a hundred spawn-and-exit cycles hit it zero times.
        betweenRunAndRegistration?()
        lock.withLock {
            // It may already have exited, in which case the handler has been here and there is
            // nothing to register.
            if exitedBeforeRegistration.remove(pid) == nil { children[pid] = task }
        }
        return pid
    }

    /// Asks the child to stop, escalates if it will not, and **returns only once it has gone**.
    ///
    /// `SIGTERM` first, because the bridge unwinds on it: it closes its listener, removes its control
    /// socket and puts back a window it resized. A process killed outright would leave the owner
    /// looking at the consequences of the last one.
    ///
    /// But a child that ignores `SIGTERM` used to be forgotten rather than killed — the state said
    /// `.stopped`, the process was alive, and the next Start added a second bridge to the same port.
    /// So after the grace period it is killed, and killed only if it is still one of ours: the pid of
    /// a child that already exited can be reused by an unrelated process, and this app must never be
    /// capable of signalling something it did not start.
    public func terminate(_ pid: Int32) {
        let waiting: (task: Process, gone: DispatchSemaphore)? = lock.withLock {
            guard let task = children[pid] else { return nil }
            let semaphore = gone[pid] ?? DispatchSemaphore(value: 0)
            gone[pid] = semaphore
            return (task, semaphore)
        }
        // Already gone. Nothing to signal and nothing to wait for.
        guard let waiting else { return }

        waiting.task.terminate()
        if waiting.gone.wait(timeout: .now() + grace) == .timedOut {
            let stillOurs = lock.withLock { children[pid] != nil }
            if stillOurs { kill(pid, SIGKILL) }
            _ = waiting.gone.wait(timeout: .now() + grace)
        }
        lock.withLock { gone[pid] = nil }
    }

    public func lastOutput(of pid: Int32) -> String? {
        lock.withLock { said[pid] }
    }

    /// Called between `run()` returning and the child being recorded. **Nil in production**, and
    /// internal so it cannot be set from outside this package. See the note at the call site.
    var betweenRunAndRegistration: (@Sendable () -> Void)?

    /// What is still being tracked. Internal, for the tests that prove none of these maps grow.
    var tracked: (children: Int, output: Int, waiters: Int) {
        lock.withLock { (children.count, said.count, gone.count) }
    }

    private func remember(_ text: String, of pid: Int32) {
        guard !text.isEmpty else { return }
        lock.withLock {
            if said[pid] == nil { outputOrder.append(pid) }
            // Counted in bytes, trimmed in bytes. A cut can land inside a multi-byte character and
            // `String(decoding:)` renders that one as a replacement — one mangled character at the
            // front of a diagnostic is a better trade than a ceiling nobody can predict.
            var bytes = Array(said[pid]?.utf8 ?? "".utf8)
            bytes.append(contentsOf: text.utf8)
            if bytes.count > keepBytes { bytes = Array(bytes.suffix(keepBytes)) }
            said[pid] = String(decoding: bytes, as: UTF8.self)
            while outputOrder.count > Self.keepOutputsFor {
                said.removeValue(forKey: outputOrder.removeFirst())
            }
        }
    }
}
