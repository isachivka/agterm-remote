import Foundation

/// What stands between the owner's phone and this Mac, as the owner would describe it.
///
/// ### Why this exists at all, and what it cost to learn
///
/// Two constants used to answer this, and both were wrong for somebody. The phone dialled `wss://`,
/// which cannot reach a bridge with nothing in front of it; then it dialled `ws://`, which cannot
/// reach a router that proxies — the deployment this project was written for. Neither failure is
/// visible from either side: the address is right, the fingerprint is right, the code simply does not
/// work, and nothing anywhere logs a reason.
///
/// **It cannot be inferred and must not be guessed.** Nothing this Mac can observe distinguishes the
/// cases; only the person who set up their own network knows. Trying one and falling back to the
/// other was considered and rejected — it doubles the worst-case connect and replaces a fact somebody
/// knows with a heuristic, in a design whose whole discipline about failure is that it never invents
/// a cause.
///
/// ### One question with three answers, not two questions
///
/// There are two underlying facts: how the phone opens the address, and whether the bridge serves TLS
/// on its own port. They are genuinely different — a proxy can be configured either way — so a
/// two-question form is the honest decomposition, and it is the wrong shape for a person. The second
/// question is meaningless unless the first is answered "through something", and the phrase it needs
/// is one nobody should have to know.
///
/// So it is one question with three rungs, and **the fourth combination is not representable**: a
/// direct connection that also serves TLS on the hop is a bridge nothing can reach, and a type that
/// cannot express it is better than a validation rule that rejects it.
///
/// The third rung names a symptom rather than a mechanism, because that is what its owner will
/// actually have in front of them: pairing fails and the thing in front says 502. That is exactly
/// what a router insisting on an HTTPS backend produces against a plaintext listener — measured, and
/// the reason the on-link hop exists at all.
public enum FrontDoor: String, CaseIterable, Equatable, Sendable {

    /// Nothing in between: the phone opens a connection and this Mac's bridge is what answers.
    ///
    /// A forwarded port at a fixed address or a dynamic-DNS name, and a mesh network that carries the
    /// packets itself. The phone dials `ws://` and the bridge serves its port plainly.
    case direct

    /// Something publishes this Mac and terminates HTTPS at its edge, then speaks plain HTTP back
    /// here.
    ///
    /// A tunnel, a reverse proxy, or a router that proxies and is content with an HTTP backend. The
    /// phone dials `wss://` — to that thing, whose certificate this design neither pins nor cares
    /// about — and the bridge serves its port plainly.
    case httpsInFront

    /// The same, except the thing in front insists on speaking HTTPS to this Mac as well.
    ///
    /// Some routers do, and against a plaintext listener the result is a 502 the phone reports as a
    /// laptop that will not answer. The phone dials `wss://` and the bridge serves TLS on its own
    /// port — opportunistically, authenticating nobody, purely so that the thing in front can connect
    /// at all.
    case httpsBothWays

    /// The default for somebody who has not been asked yet.
    ///
    /// The simplest deployment, and the one whose failure is easiest to recognise: a phone that
    /// cannot open a plain connection to a proxy gets no further than the first hop, whereas a wrong
    /// guess in the other direction fails inside somebody else's proxy with a status code they have
    /// to go and find.
    public static let unset: FrontDoor = .direct

    /// The word the bridge's `-advertise-scheme` flag and the control socket's `scheme` field take.
    ///
    /// Words here and on the wire between these two local processes; numbers only in the payload,
    /// which is the thing that has to stay stable across two languages forever.
    public var advertiseScheme: String {
        switch self {
        case .direct: return "plain"
        case .httpsInFront, .httpsBothWays: return "tls"
        }
    }

    /// Whether the bridge serves TLS on its own port.
    ///
    /// **This is not a trust decision and must never be described as one.** Whatever connects does
    /// not validate that certificate — there is no name it could check it against — so it buys
    /// confidentiality against a passive listener on the LAN and nothing else. What it is for is
    /// reachability: a proxy that insists on an HTTPS backend cannot otherwise connect.
    public var servesOnLinkTLS: Bool { self == .httpsBothWays }
}

/// What the setup screen asks, in the project's own voice.
///
/// Held here rather than in a view so that `FrontDoorTests` can assert that every case has copy and
/// that none of it uses the words a person would have to look up.
public enum FrontDoorCopy {

    public static let heading = "How your phone reaches this Mac"

    /// The question. It asks about a thing the owner set up, not about a protocol.
    public static let explanation =
        "This decides how your phone opens the address above. It is not something this Mac can work "
            + "out for itself — only you know what you put in front of it — and getting it wrong looks "
            + "like a pairing code that simply does not work."

    /// One line per case, in the order the cases are listed. The label is what somebody picks; the
    /// detail is what tells them whether it is theirs.
    public static func label(for door: FrontDoor) -> String {
        switch door {
        case .direct: return "Straight to this Mac"
        case .httpsInFront: return "Through something that adds HTTPS"
        case .httpsBothWays: return "Through something that adds HTTPS, and expects HTTPS back"
        }
    }

    public static func detail(for door: FrontDoor) -> String {
        switch door {
        case .direct:
            return "A port forward on your router, Tailscale, or WireGuard. Your phone connects and "
                + "this Mac answers."
        case .httpsInFront:
            return "A tunnel, a reverse proxy, or a router that publishes this Mac. Pick this if the "
                + "address you typed is one you would open in a browser as https."
        case .httpsBothWays:
            return "Some routers insist on it. Pick this if the one above looks right and pairing "
                + "still fails, or if the thing in front reports a 502."
        }
    }
}
