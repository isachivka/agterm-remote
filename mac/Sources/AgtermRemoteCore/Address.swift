import Foundation

/// Why an address is not one, in the four words onboarding can act on.
///
/// **This is deliberately coarser than `AddressEdit.Refusal`**, which has nine cases and a sentence
/// for each. That type is what a person editing an address is answered with; this one is the gate
/// onboarding asks at — *is there an address here at all* — and a gate with nine answers is a gate
/// whose caller has to know about brackets.
///
/// One case is about the person rather than the syntax. `hostLooksLikeAURL` exists because a pasted
/// URL otherwise reports as a missing port, and that sentence sends somebody off to append `:8443`
/// to a string whose real problem is the scheme in front of it.
public enum AddressError: Error, Equatable, Sendable {
    case empty
    /// **No port could be read.** Both when there is no colon and when there are several and none of
    /// them can be picked out — `fe80::1:8443` has no port for the same practical reason `example.test`
    /// does: there is no way to know what it is.
    case noPort
    /// A port is there and is not a port: not a number, or a number outside 1–65535.
    case badPort
    case hostLooksLikeAURL
    /// A port with nothing in front of it — `:8443`. Its own case because the string is not empty:
    /// the person typed something, and being told otherwise is a sentence they can see is wrong.
    case hostMissing
}

/// The address a phone dials, and — separately, and this is the whole point of the type — the address
/// the bridge binds because of it.
///
/// ### The two addresses are not the same address
///
/// A phone dials a name a router answers on. The bridge binds a socket on this Mac. Conflating them
/// is how somebody ends up serving the world by accident, and it is also how a pairing code comes to
/// pair perfectly and then never connect — both directions of the same confusion, and the second one
/// cost an afternoon on 2026-08-09. So the dial address is stored, shown, compared against the phone
/// and carried in the code, while `listen` is **derived from one field of it**: the port.
///
/// Task 15 wrote `0.0.0.0:<port>` at the call site and said the decision belonged here. This is it,
/// with the reasoning attached to the value rather than to the caller, so the next person to start a
/// process does not get to make it again.
///
/// ### One value with two renderings would be two addresses
///
/// This does not reimplement parsing. `DialAddress` owns the syntax and `AddressEdit` owns the
/// judgement about what an address MEANS to a person; `Address.parse` runs both and narrows the
/// answer. A second parser here would be a second opinion about where a port ends, and the first
/// thing two parsers do is disagree.
public struct Address: Equatable, Sendable {

    public let host: String
    public let port: Int

    public init(host: String, port: Int) {
        self.host = host
        self.port = port
    }

    /// The same value the phone's comparison screen, the QR payload and the address box all carry.
    public init(_ dial: DialAddress) {
        self.init(host: dial.host, port: dial.port)
    }

    /// Back to the type every other surface renders from, so nothing here becomes a second store of
    /// an address.
    public var dial: DialAddress { DialAddress(host: host, port: port) }

    /// Parses `host:port`, or says which of the four things is wrong with it.
    public static func parse(_ text: String) -> Result<Address, AddressError> {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return .failure(.empty) }
        // Asked before the syntax, because a URL is syntactically a host and a missing port, and
        // being told to add a port is the one answer that would make somebody edit the wrong half.
        if trimmed.contains("://") || trimmed.contains("/") { return .failure(.hostLooksLikeAURL) }

        switch DialAddress.parse(trimmed) {
        case .success(let dial):
            return .success(Address(dial))
        case .failure(let why):
            let error: AddressError = switch why {
            // Not `.empty`: something WAS typed. `:8443` is a port with nothing in front of it, and
            // answering "you typed nothing" to somebody looking at their own text is the kind of
            // small lie that makes a person distrust the rest of the screen.
            case .empty: .hostMissing
            // The three ways a port can fail to be findable are one answer here: nothing in this
            // string can be read as a port. The bracket lesson is `AddressEdit`'s to teach, in a
            // sentence, at the moment somebody is typing.
            case .noPort, .ambiguousWithoutBrackets, .unclosedBracket: .noPort
            case .portNotANumber, .portOutOfRange: .badPort
            }
            return .failure(error)
        }
    }

    /// **What the bridge is told to listen on: every interface, at the port traffic ARRIVES on.**
    ///
    /// ### The port is a setting, not a derivation — and it used to be one
    ///
    /// This read `0.0.0.0:\(port)`, taking the port straight from the dial address. That is right for
    /// a one-to-one port forward and **wrong for everything else**, and it walked back into a trap the
    /// source project had already written down: a router that publishes 8443 and proxies it to 8444
    /// on the Mac. Deriving the bind from the dial address makes this bridge listen on 8443 while the
    /// router delivers to 8444 — a perfect pairing code and a phone that never connects, which is the
    /// exact failure this whole app exists to prevent, arriving from the other direction.
    ///
    /// That project's pairing tool takes `--host` and `--port` with **no defaults, deliberately**, for
    /// the same reason. So the arrival port is stored, editable, and merely *defaults* to the dial
    /// port; `listen` without an argument is that default and nothing more.
    ///
    /// ### The host is dropped on purpose, and this is the decision Task 15 deferred to here
    ///
    /// The convenient answer is to hand the bridge the address the owner typed — it is right there,
    /// it is already validated, and it is what the phone uses. It is also wrong in every deployment
    /// this app is for. A dial address is a *router's* name or a tunnel's name; this Mac holds no
    /// interface by that name, so binding it either fails outright or, worse, resolves to one
    /// interface on a machine whose phone arrives over another. The only field of a dial address that
    /// is simultaneously a fact about this Mac is the **port**, because a forwarded connection lands
    /// on the port the forward was written for.
    ///
    /// The wildcard is not a shrug either. A narrower bind — loopback — cannot be reached by a port
    /// forward, by Tailscale, by WireGuard or by a reverse proxy running anywhere but this machine,
    /// which is every route in the onboarding text: a tunnel hands this Mac an interface of its own,
    /// and something bound to loopback is invisible on it.
    ///
    /// ### What the wildcard costs, said accurately
    ///
    /// This used to read *"the exposure the owner arranged deliberately when they forwarded a port"*.
    /// **That is true of one route out of four and was asserted of all of them.** Somebody on
    /// Tailscale, WireGuard or a Cloudflare Tunnel arranged the opposite of a port forward, and this
    /// bind still opens the port on every interface this Mac ever joins — a café network included.
    ///
    /// Nothing here is broken by that: the API accepts only a connection presenting the pinned
    /// phone's certificate, and the enrolment path is ALPN-gated, open for seconds at the owner's
    /// request and compared in constant time. So it is **disclosure rather than a hole**, and it is
    /// owed a sentence on the screen rather than a comment in a file — see
    /// `OnboardingCopy.addressExposure`, which is what the owner actually reads.
    ///
    /// ### This string is a request, not a receipt
    ///
    /// Two things known about it, and both belong to whoever next reasons about ports:
    ///
    ///  - **Go widens it.** `0.0.0.0` is the unspecified address, so Go's resolver hands `net.Listen`
    ///    a dual-stack `[::]:PORT` socket. The bridge logs the address it actually bound rather than
    ///    the one it was given, precisely because these two spellings are one truth.
    ///  - **A successful bind does not mean the port is yours.** Verified with `lsof` while building
    ///    Task 15: an IPv4-only listener coexists on the same port with the dual-stack socket above.
    ///    So even a local check that says *I am listening* can be true while a phone arriving over
    ///    IPv4 reaches something else entirely.
    public var listen: String { listen(on: nil) }

    /// The bind, given the arrival port the owner set — or the dial port when they have set none.
    ///
    /// - Parameter arrivalPort: nil means *follow the dial port*, which is stored as an absence rather
    ///   than as a copy. A copy would go stale the moment somebody changed the dial port, and the
    ///   straight-through case is the one nobody should have to maintain by hand.
    public func listen(on arrivalPort: Int?) -> String { "0.0.0.0:\(arrivalPort ?? port)" }
}
