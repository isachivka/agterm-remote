import Foundation

/// Starting, stopping and restarting the bridge. **That is the entire set.**
///
/// ### This app is not the bridge's parent
///
/// `launchd` owns the bridge and keeps owning it. The agent is installed with `KeepAlive`, and it
/// **must keep surviving with nobody logged in and no window open** — a menu bar app that becomes load-bearing
/// is a regression dressed as a feature. So this asks `launchctl` to act on a job that exists whether
/// or not this app is running; it never spawns the bridge itself, never holds it as a child, and its
/// own death changes nothing about whether the bridge is up.
///
/// ### Why restart is a first-class action and not a convenience
///
/// `cmd/agtermbridge/main.go` reads `phone-cert.pem` **once, at startup**. There is no watcher and no
/// reload. So after a re-pair the bridge is still pinning the previous phone's identity — which, after
/// a re-pair, exists nowhere — until it restarts. **A pin without a restart pins nothing**, and an app
/// that offered pinning without this would ship that trap to a person instead of to an agent.
///
/// ### What it may not do
///
/// No agterm verb. It never opens agterm's control socket. It grows no place to type a command. The
/// only executable it names is `/bin/launchctl` and the only job it names is the bridge's own label —
/// `BoundaryTests` was written before this file existed and checks both by reading the source.
public struct BridgeSupervisor: Sendable {

    /// The bridge's launchd job.
    ///
    /// **Not the app's own bundle identifier**, which is `dev.isachivka.agtermremote`. The rename that
    /// brought this file over collapsed two distinct strings into one, and a job label equal to the
    /// bundle id of the app that supervises it is wrong in both directions: `launchctl print` would
    /// name the app when asked about the bridge, and a future `bootstrap` would install a job under
    /// the identifier macOS already associates with an application. `BridgeSupervisorTests` reads the
    /// bundle identifier out of Info.plist and fails if the two are ever the same string again.
    public static let label = "dev.isachivka.agterm-remote-bridge"

    /// What ran, so a test can assert the exact argv rather than the shape of a string.
    public struct Invocation: Equatable, Sendable {
        public let executable: String
        public let arguments: [String]
    }

    public enum Failure: Error, Equatable {
        /// launchctl exited non-zero. Carries its status and whatever it said, for the owner.
        case refused(status: Int32, message: String)
    }

    /// Runs a command and reports how it went. Injected so the argv can be asserted without launchctl.
    public typealias Runner = @Sendable (Invocation) -> (status: Int32, output: String)

    private let domain: String
    private let run: Runner

    /// `gui/<uid>` is the owner's own login session — the same domain `install.sh` uses. Not
    /// `system/`: this is a per-user agent, and reaching into the system domain would need root and
    /// would be a different thing entirely.
    public init(uid: uid_t = getuid(), run: @escaping Runner) {
        self.domain = "gui/\(uid)"
        self.run = run
    }

    private var target: String { "\(domain)/\(Self.label)" }

    /// Starts the job if it is loaded but not running. `kickstart` without `-k` leaves a running job
    /// alone, which is what "start" should mean.
    public func start() throws {
        try launchctl(["kickstart", target])
    }

    /// Stops the job **and tells launchd not to bring it back**, because `KeepAlive` would restart it
    /// within seconds otherwise and the owner would see a stop button that does nothing.
    ///
    /// The cost, stated where it happens: after this the job is unloaded, and `start()` on its own
    /// cannot bring it back — it must be bootstrapped again from its plist. That is what
    /// `startAfterStop()` is for, and it is why stopping is offered as a deliberate action rather
    /// than as a toggle somebody flips by accident.
    public func stop() throws {
        try launchctl(["bootout", target])
    }

    /// Loads the job again after a `stop()`, from the plist `install.sh` wrote.
    public func startAfterStop(plist: URL) throws {
        try launchctl(["bootstrap", domain, plist.path])
    }

    /// **The one that matters after a re-pair.** `-k` kills the running job and starts it again, so
    /// the new `phone-cert.pem` is read — see the note above about `main.go` reading it once.
    public func restart() throws {
        try launchctl(["kickstart", "-k", target])
    }

    /// Whether launchd says the job is running **right now**.
    ///
    /// Asked rather than assumed. `kickstart` exiting zero means launchd accepted the request, not
    /// that a process is alive — and a menu that reported the command's exit status would be
    /// reporting what we asked for instead of what happened. Same distinction as reading the signer
    /// off a released artifact rather than trusting the build that produced it.
    public func isRunning() -> Bool {
        let outcome = run(Invocation(executable: "/bin/launchctl", arguments: ["print", target]))
        guard outcome.status == 0 else { return false }
        // launchd prints `state = running` for a live job; a loaded-but-idle one says `not running`.
        return outcome.output.contains("state = running")
    }

    private func launchctl(_ arguments: [String]) throws {
        // The executable is named in full and never resolved through PATH: an app that ran whatever
        // `launchctl` happened to be first on the owner's PATH would be a different program than the
        // one this was reviewed as.
        let outcome = run(Invocation(executable: "/bin/launchctl", arguments: arguments))
        guard outcome.status == 0 else {
            throw Failure.refused(status: outcome.status, message: outcome.output)
        }
    }
}
