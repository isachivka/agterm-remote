import Foundation

/// The address a person compares against the phone, rendered exactly one way.
///
/// ### Why this is a type and not a string built at each call site
///
/// The fingerprint cannot catch a wrong host. `bridgecert qr` reads the laptop's existing identity
/// rather than minting one, so **every QR this laptop generates shows the same fingerprint** — a code
/// built for the wrong host looks perfectly correct, pairs, and then never connects. That happened
/// twice on 2026-08-09. The host and port are the only fields that discriminate.
///
/// The phone renders this same value on its comparison screen, from `PairingAddress.kt`. **Two
/// renderings of one address is a comparison the owner performs correctly and still gets wrong** — one
/// padded and one not, one hiding a port it thought was default, one quietly stripping a trailing dot.
/// The Kotlin cannot be linked from here, so the *cases* are the contract and both sides are held to
/// the same table. A drift between them is as bad as a wrong address, because it is indistinguishable
/// from one.
///
/// ### Nothing here normalises
///
/// No lowercasing, no trailing-dot stripping, no punycode, no eliding a "default" port. Every one of
/// those makes two *different* addresses render *identically*, which is the failure this exists to
/// prevent. **If two profiles would connect to different places, they must look different on screen.**
public struct DialAddress: Equatable, Sendable {

    public let host: String
    public let port: Int

    public init(host: String, port: Int) {
        self.host = host
        self.port = port
    }

    /// Host and port, always both.
    ///
    /// An IPv6 literal is bracketed, because otherwise the colon before the port is one of several and
    /// the reader cannot tell where the address ends. One that arrives already bracketed is left alone
    /// rather than bracketed twice.
    public var displayed: String {
        needsBrackets ? "[\(host)]:\(port)" : "\(host):\(port)"
    }

    private var needsBrackets: Bool {
        host.contains(":") && !(host.hasPrefix("[") && host.hasSuffix("]"))
    }

    /// **The mirror of `displayed`, and it lives here for that reason.**
    ///
    /// The address is one string everywhere it appears — the phone's comparison screen, the QR payload,
    /// this window's label. Reading one back is the same contract in the other direction, so the two
    /// halves sit in one file, are held to one case table, and change together. Split across files they
    /// would drift, and a formatter and parser that disagree turn one destination into two.
    ///
    /// ### What it does not do
    ///
    /// It does not lowercase, does not strip a trailing dot, does not supply a default port and does
    /// not guess. The single exception is the **brackets around an IPv6 literal**, which are removed:
    /// they are the syntax that makes `host:port` unambiguous, not part of the name, and `[fe80::1]`
    /// and `fe80::1` are the same machine. `displayed` puts them back.
    public enum ParseFailure: Error, Equatable, Sendable {
        case empty
        /// No colon at all, so no port. Carries what was typed, to name it in the sentence.
        case noPort(String)
        case portNotANumber(String)
        case portOutOfRange(Int)
        /// More than one colon and no brackets: `fe80::1:8443` cannot be split without guessing which
        /// colon is the separator. **This is the whole reason brackets exist.**
        case ambiguousWithoutBrackets(String)
        case unclosedBracket(String)
    }

    /// Parses `host:port`, or `[literal]:port`. Whitespace around the outside is removed — it cannot
    /// be part of an address and no two destinations differ by it.
    public static func parse(_ text: String) -> Result<DialAddress, ParseFailure> {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return .failure(.empty) }

        let host: String
        let portText: String

        if trimmed.hasPrefix("[") {
            guard let close = trimmed.firstIndex(of: "]") else { return .failure(.unclosedBracket(trimmed)) }
            host = String(trimmed[trimmed.index(after: trimmed.startIndex)..<close])
            let rest = trimmed[trimmed.index(after: close)...]
            guard rest.hasPrefix(":") else { return .failure(.noPort(trimmed)) }
            portText = String(rest.dropFirst())
        } else {
            let colons = trimmed.filter { $0 == ":" }.count
            guard colons != 0 else { return .failure(.noPort(trimmed)) }
            guard colons == 1 else { return .failure(.ambiguousWithoutBrackets(trimmed)) }
            let parts = trimmed.split(separator: ":", omittingEmptySubsequences: false)
            host = String(parts[0])
            portText = String(parts[1])
        }

        guard !host.isEmpty else { return .failure(.empty) }
        guard !portText.isEmpty else { return .failure(.noPort(trimmed)) }
        guard let port = Int(portText) else { return .failure(.portNotANumber(portText)) }
        guard (1...65535).contains(port) else { return .failure(.portOutOfRange(port)) }
        return .success(DialAddress(host: host, port: port))
    }
}
