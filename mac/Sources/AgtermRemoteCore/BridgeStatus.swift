import Foundation

/// Whether the bridge is up, whether the configured address answers, and whether what answered is us.
///
/// ### Three facts, and they are never one light
///
/// On 2026-08-09 a pairing code was built for the homelab's bare dynamic-DNS suffix. That name
/// **resolves and answers** — a TLS-terminating proxy sits on it and every other service behind the
/// same router hangs off it — so a status that asked only *"did something answer?"* would have shown
/// green for an address that could never work. The phone paired perfectly and then never connected,
/// twice, and it cost an afternoon.
///
/// **Only the third fact separates *something answered* from *we answered*.** That is why there is no
/// aggregate here: no `isHealthy`, no `isOk`, no single `Bool` derived from the three. A caller that
/// wants one has to write it, and `NoAggregateVerdictTests` fails if anybody does.
///
/// ### It cannot fall back to the listen address
///
/// The bridge listens on one address and the phone dials another; reusing the first for the second is
/// what produces a QR that pairs and cannot connect. **This module never reads the bridge's
/// `config.json` and has no way to learn the listen address**, so the fallback is not something we
/// avoid doing — there is nothing here to do it with. The only address in scope is the [DialAddress]
/// handed in, and every message names it.
public struct BridgeStatus: Equatable, Sendable {

    /// Fact 1: our half is up.
    ///
    /// **Three cases, because a spawned process is not a running bridge.** `.starting` is the window
    /// between the child being spawned and it announcing a bound listener — up to
    /// `BridgeProcess.readyCeiling`, and forever for a binary macOS has frozen. Reporting that window
    /// as `.running` told the owner a held bridge was Running for ten seconds before it flipped to
    /// failed, which is the one thing this status exists not to do.
    public enum Running: Equatable, Sendable {
        case running
        /// Spawned, not yet answering. Stop applies; Start does not.
        case starting
        case notRunning
    }

    /// Fact 2: the **configured** address answers. Not the listen address; there isn't one here.
    public enum Reachable: Equatable, Sendable {
        case answered
        /// Carries why, for the owner, not for a log.
        case noAnswer(String)
    }

    /// Fact 3: what answered is this laptop.
    public enum Identified: Equatable, Sendable {
        case ours
        /// Something answered and presented a different certificate. **This is the 2026-08-09 case.**
        case notOurs(presented: String)
        /// Nothing answered, so there was nothing to identify. Distinct from `notOurs`: one is a
        /// silence and the other is a stranger, and telling the owner they are the same would be the
        /// aggregate mistake in miniature.
        case notEstablished
    }

    public let address: DialAddress
    public let running: Running
    public let reachable: Reachable
    public let identified: Identified

    public init(address: DialAddress, running: Running, reachable: Reachable, identified: Identified) {
        self.address = address
        self.running = running
        self.reachable = reachable
        self.identified = identified
    }

    /// What is wrong, in the owner's terms, naming the address every time.
    ///
    /// Empty means all three hold. **It is a list rather than a verdict** — an empty list is not a
    /// green light, it is the absence of complaints about three separate things, and the screen shows
    /// three states regardless.
    public var problems: [String] {
        var found: [String] = []
        if running == .notRunning {
            found.append("The bridge is not running on this laptop.")
        }
        if case .noAnswer(let why) = reachable {
            found.append("Nothing answered at \(address.displayed) — \(why)")
        }
        switch identified {
        case .notOurs(let presented):
            found.append(
                "Something answered at \(address.displayed), but it is not this laptop. "
                    + "It presented \(presented)."
            )
        case .notEstablished where reachable == .answered:
            found.append("Something answered at \(address.displayed) but never completed a handshake.")
        default:
            break
        }
        return found
    }
}

/// What the world outside says, so the assessment above is a pure function of it.
///
/// The real one opens a TLS connection; the tests hand over the answers. That seam is the reason the
/// reachable-but-not-ours case is a **test** rather than a comment in a file nobody re-reads.
public protocol StatusProbe: Sendable {
    func bridgeIsRunning() -> Bool
    /// Connects to the given address — and only that address — and reports what presented itself.
    func handshake(with address: DialAddress) -> HandshakeOutcome
}

public enum HandshakeOutcome: Equatable, Sendable {
    /// TCP and TLS completed; the peer's certificate fingerprint, formatted as `bridgecert` prints it.
    case presented(fingerprint: String)
    /// Nothing answered, or the connection failed before a certificate arrived.
    case noAnswer(String)
    /// Something answered and the TLS handshake itself failed. Reachable, unidentified.
    case handshakeFailed(String)
}

public enum BridgeStatusAssessment {

    /// The three facts, established independently.
    ///
    /// `ourFingerprint` is this laptop's bridge certificate, read from disk by the caller. Comparing
    /// against it is the whole of fact 3, and it is the only thing that distinguishes our bridge from
    /// every other service hanging off the same suffix.
    public static func assess(
        address: DialAddress,
        ourFingerprint: String,
        probe: StatusProbe,
    ) -> BridgeStatus {
        let running: BridgeStatus.Running = probe.bridgeIsRunning() ? .running : .notRunning

        // Reachability is asked about the CONFIGURED address whether or not our process is up: a
        // bridge that is down and a name that points at somebody else are different problems, and
        // skipping the question when the process is down would hide the second behind the first.
        switch probe.handshake(with: address) {
        case .presented(let fingerprint):
            return BridgeStatus(
                address: address,
                running: running,
                reachable: .answered,
                identified: fingerprint == ourFingerprint ? .ours : .notOurs(presented: fingerprint),
            )
        case .handshakeFailed:
            // Reachable and unidentified, which is its own thing: something is there and we could not
            // establish that it is us. Reporting it as `notOurs` would claim we saw a stranger's
            // certificate when we saw none at all.
            return BridgeStatus(
                address: address,
                running: running,
                reachable: .answered,
                identified: .notEstablished,
            )
        case .noAnswer(let why):
            return BridgeStatus(
                address: address,
                running: running,
                reachable: .noAnswer(why),
                identified: .notEstablished,
            )
        }
    }
}
