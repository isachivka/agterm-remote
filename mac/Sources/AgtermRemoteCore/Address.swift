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
            case .empty: .empty
            // The three ways a port can fail to be findable are one answer here: nothing in this
            // string can be read as a port. The bracket lesson is `AddressEdit`'s to teach, in a
            // sentence, at the moment somebody is typing.
            case .noPort, .ambiguousWithoutBrackets, .unclosedBracket: .noPort
            case .portNotANumber, .portOutOfRange: .badPort
            }
            return .failure(error)
        }
    }

    /// **What the bridge is told to listen on: every interface, at the port the phone will dial.**
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
    /// which is every route in the onboarding text. The exposure the wildcard implies is the exposure
    /// the owner arranged deliberately when they forwarded a port, and the protection against it is
    /// the pinned client certificate the listener demands, not the address family it binds.
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
    public var listen: String { "0.0.0.0:\(port)" }
}
