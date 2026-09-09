import Foundation
import Testing
@testable import AgtermRemoteCore

/// The three facts, and the one that catches 2026-08-09.
struct BridgeStatusTests {

    private let ours = "964F 9676 3C91 1480 0000 0000 0000 0000"
    private let stranger = "1111 2222 3333 4444 5555 6666 7777 8888"
    private let address = DialAddress(host: "agterm.example-homelab.invalid", port: 8443)

    private struct Probe: StatusProbe {
        var running = true
        var outcome: HandshakeOutcome
        /// Records what was dialled, so a test can prove nothing else was.
        final class Seen: @unchecked Sendable { var addresses: [DialAddress] = [] }
        var seen = Seen()

        func bridgeIsRunning() -> Bool { running }
        func handshake(with address: DialAddress) -> HandshakeOutcome {
            seen.addresses.append(address)
            return outcome
        }
    }

    @Test func allThreeHoldWhenOurOwnBridgeAnswers() {
        let status = BridgeStatusAssessment.assess(
            address: address, ourFingerprint: ours, probe: Probe(outcome: .presented(fingerprint: ours)))

        #expect(status.running == .running)
        #expect(status.reachable == .answered)
        #expect(status.identified == .ours)
        #expect(status.problems.isEmpty)
    }

    /// **THE 2026-08-09 CASE, as a test rather than a comment.**
    ///
    /// The bare dynamic-DNS suffix answers perfectly well — it belongs to every other service on the
    /// homelab. A status that stopped at *"something answered"* would have shown green for an address
    /// that could never work, twice, which is exactly what happened.
    @Test func reachableButNotOursIsAFailureAndSaysSo() {
        let status = BridgeStatusAssessment.assess(
            address: address, ourFingerprint: ours, probe: Probe(outcome: .presented(fingerprint: stranger)))

        #expect(status.reachable == .answered, "something did answer, and pretending otherwise is a lie")
        #expect(status.identified == .notOurs(presented: stranger))
        #expect(status.problems.count == 1)
        let said = status.problems[0]
        #expect(said.contains("not this laptop"))
        #expect(said.contains("agterm.example-homelab.invalid:8443"), "the failure must name the address it tried")
        #expect(said.contains(stranger), "the owner cannot compare a certificate they were not shown")
    }

    /// Silence and a stranger are different, and merging them is the aggregate mistake in miniature.
    @Test func nothingAnsweringIsNotTheSameAsSomethingElseAnswering() {
        let silent = BridgeStatusAssessment.assess(
            address: address, ourFingerprint: ours, probe: Probe(outcome: .noAnswer("connection refused")))

        #expect(silent.reachable == .noAnswer("connection refused"))
        #expect(silent.identified == .notEstablished)
        #expect(silent.problems.count == 1)
        #expect(silent.problems[0].contains("Nothing answered at agterm.example-homelab.invalid:8443"))
    }

    /// Reachable and unidentified is its own state: something is there and no certificate arrived.
    /// Calling it `notOurs` would claim we saw a stranger's certificate when we saw none.
    @Test func aFailedHandshakeIsReachableAndUnidentified() {
        let status = BridgeStatusAssessment.assess(
            address: address, ourFingerprint: ours, probe: Probe(outcome: .handshakeFailed("protocol error")))

        #expect(status.reachable == .answered)
        #expect(status.identified == .notEstablished)
        #expect(status.problems.contains { $0.contains("never completed a handshake") })
    }

    /// **A bridge that is down and a name pointing at somebody else are different problems.** Asking
    /// only when our process is up would hide the second behind the first.
    @Test func aStoppedBridgeDoesNotStopUsAskingAboutTheAddress() {
        let probe = Probe(running: false, outcome: .presented(fingerprint: stranger))

        let status = BridgeStatusAssessment.assess(address: address, ourFingerprint: ours, probe: probe)

        #expect(status.running == .notRunning)
        #expect(status.identified == .notOurs(presented: stranger))
        #expect(status.problems.count == 2, "both problems must be reported, not just the first")
        #expect(probe.seen.addresses == [address], "reachability was not checked while the bridge was down")
    }

    /// **The only address that may be dialled is the configured one.** There is no listen address in
    /// this module to fall back to, and this proves nothing invented one.
    @Test func onlyTheConfiguredAddressIsEverDialled() {
        let probe = Probe(outcome: .presented(fingerprint: ours))

        _ = BridgeStatusAssessment.assess(address: address, ourFingerprint: ours, probe: probe)

        #expect(probe.seen.addresses == [address])
    }

    /// Empty problems is the absence of complaints about three separate things, and the screen still
    /// shows three states. This pins that `problems` names each failing fact once.
    @Test func everyFailingFactIsNamedOnce() {
        let status = BridgeStatusAssessment.assess(
            address: address, ourFingerprint: ours,
            probe: Probe(running: false, outcome: .noAnswer("host not found")))

        #expect(status.problems.count == 2)
        #expect(status.problems[0].contains("not running"))
        #expect(status.problems[1].contains("Nothing answered"))
    }
}
