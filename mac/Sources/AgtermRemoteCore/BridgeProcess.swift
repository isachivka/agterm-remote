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
    /// - Parameter isExecutable: injected so the order can be asserted without a file system.
    public static func locate(
        resources: URL?,
        beside: URL,
        stateDir: URL,
        isExecutable: (String) -> Bool = { FileManager.default.isExecutableFile(atPath: $0) },
    ) -> URL? {
        let candidates = [
            resources?.appending(path: executableName),
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

    /// Terminates it and says so immediately.
    ///
    /// `.stopped` is not deferred until the exit arrives: a stop that leaves the menu saying
    /// *running* until a signal lands is a control the owner presses twice.
    public func stop() {
        lock.withLock {
            stopping = true
            generation += 1
            let pidToKill = pid
            pid = nil
            // Announced BEFORE the signal goes out, deliberately. A launcher that reports the exit
            // synchronously would otherwise announce `.stopped` from inside the exit and again from
            // here, and a menu rebuilt twice for one press is a menu that flickers for no reason.
            if _state != .stopped { set(.stopped) }
            if let pidToKill { launcher.terminate(pidToKill) }
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
        launchInFlight = true
        exitDuringLaunch = nil
        // Before the spawn, not after it: the child's life starts when it is spawned, and a launcher
        // that returns only once the child is already dead would otherwise make every run look
        // instantaneous — which is the measurement the thirty-second reset is made of.
        startedRunningAt = now()
        let started: Int32
        do {
            started = try launcher.launch(executable, arguments()) { [weak self] status in
                self?.handle(exit: status)
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
            handle(exit: status)
        } else {
            set(.running(pid: started))
        }
        return nil
    }

    private func handle(exit status: Int32) {
        lock.withLock {
            if launchInFlight {
                exitDuringLaunch = status
                return
            }
            // We asked for this one. Nothing to explain and nothing to restart.
            if stopping {
                if _state != .stopped { set(.stopped) }
                return
            }
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
            sentence += "\n\nIt said:\n\(said.suffix(Self.sentenceOutputLimit))"
        }
        return sentence
    }

    /// How much of the child's talk reaches an alert. The launcher keeps more than a dialogue box can
    /// show; the end is the part that explains the death.
    private static let sentenceOutputLimit = 800

    private func set(_ next: BridgeState) {
        _state = next
        onStateChange?(next)
    }
}

/// The production launcher. **The only thing in this package that spawns a process it supervises.**
///
/// It keeps the last of the child's output because that is the difference between a `.failed` that
/// names a port clash and one that says nothing. Bounded, and never written anywhere: the bridge's
/// own log is a file with a size limit and a rotation policy, and this is a few kilobytes in memory
/// for the length of one failure.
public final class ChildProcessLauncher: ProcessLauncher, @unchecked Sendable {

    /// How much of the child's talk is kept. A refusal to bind is one line; a bridge that ran for a
    /// day and then died says more, and the end of it is the part that explains the death.
    private static let keepBytes = 4096

    private let lock = NSLock()
    private var children: [Int32: Process] = [:]
    private var said: [Int32: String] = [:]

    public init() {}

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
            self?.lock.withLock { _ = self?.children.removeValue(forKey: pid) }
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
        lock.withLock { children[pid] = task }
        return pid
    }

    public func terminate(_ pid: Int32) {
        let task = lock.withLock { children[pid] }
        // `terminate()` on the object rather than a signal to a number: a pid can be reused between
        // the moment a child dies and the moment somebody presses Stop, and this app must not be
        // capable of signalling a process it never started.
        task?.terminate()
    }

    public func lastOutput(of pid: Int32) -> String? {
        lock.withLock { said[pid] }
    }

    private func remember(_ text: String, of pid: Int32) {
        guard !text.isEmpty else { return }
        lock.withLock {
            var kept = (said[pid] ?? "") + text
            if kept.utf8.count > Self.keepBytes {
                kept = String(kept.suffix(Self.keepBytes))
            }
            said[pid] = kept
        }
    }
}
