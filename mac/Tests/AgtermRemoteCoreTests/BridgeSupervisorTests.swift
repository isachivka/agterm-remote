import Foundation
import Testing
@testable import AgtermRemoteCore

/// What the app asks launchd to do, asserted as exact argv rather than as the shape of a string.
struct BridgeSupervisorTests {

    private final class Log: @unchecked Sendable {
        var invocations: [BridgeSupervisor.Invocation] = []
        var status: Int32 = 0
        var output = ""
    }

    private func supervisor(_ log: Log, uid: uid_t = 501) -> BridgeSupervisor {
        BridgeSupervisor(uid: uid) { invocation in
            log.invocations.append(invocation)
            return (log.status, log.output)
        }
    }

    @Test func startKicksTheJobWithoutKillingIt() throws {
        let log = Log()

        try supervisor(log).start()

        #expect(log.invocations == [.init(executable: "/bin/launchctl",
                                          arguments: ["kickstart", "gui/501/dev.isachivka.agterm-remote-bridge"])])
    }

    /// **The one that matters after a re-pair.** `main.go` reads `phone-cert.pem` once at startup, so
    /// a pin without a restart pins nothing — and `-k` is the whole difference between a restart and
    /// a no-op against an already-running job.
    @Test func restartKillsAndStartsSoTheNewCertificateIsRead() throws {
        let log = Log()

        try supervisor(log).restart()

        #expect(log.invocations.first?.arguments == ["kickstart", "-k", "gui/501/dev.isachivka.agterm-remote-bridge"])
        #expect(log.invocations.first?.arguments.contains("-k") == true,
                "without -k a running bridge keeps the old phone-cert.pem")
    }

    /// KeepAlive would restart it within seconds, so a stop that only killed the process would be a
    /// button that visibly does nothing.
    @Test func stopUnloadsTheJobRatherThanKillingAProcessLaunchdWillRevive() throws {
        let log = Log()

        try supervisor(log).stop()

        #expect(log.invocations.first?.arguments == ["bootout", "gui/501/dev.isachivka.agterm-remote-bridge"])
    }

    @Test func startingAfterAStopBootstrapsThePlistBack() throws {
        let log = Log()

        try supervisor(log).startAfterStop(plist: URL(filePath: "/somewhere/Library/LaunchAgents/x.plist"))

        #expect(log.invocations.first?.arguments
            == ["bootstrap", "gui/501", "/somewhere/Library/LaunchAgents/x.plist"])
    }

    /// The domain follows the running user. Hard-coding uid 501 would work on exactly one Mac.
    @Test func theDomainIsTheOwnersOwnLoginSession() throws {
        let log = Log()

        try supervisor(log, uid: 502).start()

        #expect(log.invocations.first?.arguments.last == "gui/502/dev.isachivka.agterm-remote-bridge")
    }

    /// **Only launchctl, only by absolute path.** Resolving it through PATH would run whichever
    /// `launchctl` came first on the owner's, which is a different program than the reviewed one.
    @Test func theOnlyExecutableIsLaunchctlByAbsolutePath() throws {
        let log = Log()
        let supervisor = supervisor(log)

        try supervisor.start()
        try supervisor.stop()
        try supervisor.restart()

        #expect(log.invocations.allSatisfy { $0.executable == "/bin/launchctl" })
        #expect(log.invocations.allSatisfy { !$0.arguments.contains { $0.contains("agterm.sock") } })
    }

    /// A refusal is reported with what launchd said, because "it didn't work" is not something the
    /// owner can act on at 2am.
    @Test func aRefusalCarriesTheStatusAndTheMessage() {
        let log = Log()
        log.status = 113
        log.output = "Could not find service"

        #expect(throws: BridgeSupervisor.Failure.refused(status: 113, message: "Could not find service")) {
            try supervisor(log).restart()
        }
    }

    /// **Asked, not assumed.** The menu's state after a stop or a start comes from launchd's answer,
    /// because `kickstart` exiting zero means the request was accepted, not that a process is alive.
    @Test func runningIsReadFromLaunchdRatherThanFromTheExitStatus() {
        let log = Log()
        log.output = "dev.isachivka.agterm-remote-bridge = {\n\tstate = running\n\tpid = 4242\n}"

        #expect(supervisor(log).isRunning())
        #expect(log.invocations.first?.arguments == ["print", "gui/501/dev.isachivka.agterm-remote-bridge"])
    }

    @Test func aLoadedButIdleJobIsNotRunning() {
        let log = Log()
        log.output = "dev.isachivka.agterm-remote-bridge = {\n\tstate = not running\n}"

        #expect(!supervisor(log).isRunning())
    }

    /// A job launchd has never heard of is not running — and is not an exception either, because the
    /// menu asks this on every rebuild.
    @Test func anUnknownJobIsNotRunningRatherThanAnError() {
        let log = Log()
        log.status = 113
        log.output = "Could not find service"

        #expect(!supervisor(log).isRunning())
    }

    /// Nothing is asked of launchd unless the owner asked for it: constructing the supervisor runs
    /// nothing at all.
    @Test func constructingItRunsNothing() {
        let log = Log()

        _ = supervisor(log)

        #expect(log.invocations.isEmpty)
    }

    /// **The launchd job label is not the app's bundle identifier**, and this reads the plist rather
    /// than a copy of the string.
    ///
    /// The rename that brought this package over collapsed two distinct identifiers into one: the
    /// label was the bridge's and the bundle id was the app's, and a blanket substitution made them
    /// the same. Nothing failed — a wrong label is a job that is never found, reported as a bridge
    /// that is not running — which is why it took a reader to notice and why it is asserted here.
    @Test func theJobLabelIsNotTheAppsOwnBundleIdentifier() throws {
        let plist = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "Sources/AgtermRemote/Info.plist")
        let text = try String(contentsOf: plist, encoding: .utf8)

        // The value of CFBundleIdentifier: the <string> on the line after the key.
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        guard let keyLine = lines.firstIndex(where: { $0.contains("<key>CFBundleIdentifier</key>") }),
              keyLine + 1 < lines.count
        else { return #expect(Bool(false), "Info.plist declares no CFBundleIdentifier") }
        let bundleID = lines[keyLine + 1]
            .replacingOccurrences(of: "<string>", with: "")
            .replacingOccurrences(of: "</string>", with: "")
            .trimmingCharacters(in: .whitespaces)

        #expect(!bundleID.isEmpty, "the plist parse in this test found nothing, so it proves nothing")
        #expect(BridgeSupervisor.label != bundleID,
                "the bridge's launchd job is labelled with the app's own bundle identifier")
    }
}
