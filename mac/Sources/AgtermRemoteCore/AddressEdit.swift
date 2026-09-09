import Foundation

/// Typing an address, and refusing a wrong one **in words**.
///
/// ### Why the app edits this at all
///
/// The alternative is a configuration file somebody edits by hand, and that is the act that produced
/// the afternoon this whole type exists to prevent: a bare suffix typed into a file nobody re-read, a
/// code built from it, a phone that paired and never connected. A hand-edited file cannot refuse
/// anything, so the first thing that tells you it was wrong is the phone failing to connect, hours
/// later, with nothing on screen that explains it.
///
/// ### What it must not become
///
/// **There is one address and one place it lives.** This writes the app's preference and never a
/// second copy, a cache, an environment variable or a remembered last value. A parallel source of
/// truth is the failure this whole feature exists to prevent, and it would arrive wearing the
/// disguise of a convenience.
///
/// ### Nothing here normalises
///
/// No lowercasing, no trailing-dot stripping, no eliding a default port, no punycode. Same rule as
/// `DialAddress.displayed`, for the same reason: **two addresses that connect to different places must
/// look different**, and a normaliser is a machine for making them look the same. The only text
/// removed is whitespace around the outside, which cannot be part of a hostname and is not something
/// two destinations can differ by.
public enum AddressEdit {

    /// Why a typed address was not saved. Each case is its own sentence, because "invalid" tells
    /// somebody that they are wrong and not what to do next.
    public enum Refusal: Error, Equatable, Sendable {
        case blank
        case hasWhitespace
        case looksLikeAURL(String)
        /// **The one that cost an afternoon.** See `bareSuffixExplanation`.
        case bareSuffix(String)
        /// No port. **The half of the address nobody can see in a QR**, so its absence is named rather
        /// than filled in.
        case noPort(String)
        case portNotANumber(String)
        case portOutOfRange(Int)
        case ambiguousWithoutBrackets(String)
        case unclosedBracket(String)

        public var explanation: String {
            switch self {
            case .blank:
                "Type the address your PHONE will connect to, as host:port. It is not the address the "
                    + "bridge listens on."
            case .hasWhitespace:
                "An address cannot contain a space. Type it as one value, host:port, exactly as the "
                    + "phone shows it."
            case .looksLikeAURL(let text):
                "Type the address on its own — no scheme and no path. From \"\(text)\", the part to keep "
                    + "is everything between the // and the next /, plus the port."
            case .bareSuffix(let host):
                AddressEdit.bareSuffixExplanation(host)
            case .noPort(let text):
                "\"\(text)\" has no port. Add one, as in \"\(text):8443\" — and check it rather than "
                    + "assuming 8443: the port your phone reaches is whatever your router forwards, and "
                    + "it is the half of the address you cannot see in a pairing code."
            case .portNotANumber(let text):
                "\"\(text)\" is not a port. A port is a number from 1 to 65535."
            case .portOutOfRange(let port):
                "\(port) is not a port. A port is a number from 1 to 65535."
            case .ambiguousWithoutBrackets(let text):
                "\"\(text)\" has several colons, so there is no way to tell which one starts the port. "
                    + "An IPv6 address goes in brackets, as in \"[fe80::1]:8443\"."
            case .unclosedBracket(let text):
                "\"\(text)\" opens a bracket and never closes it. A bracketed address looks like "
                    + "\"[fe80::1]:8443\"."
            }
        }
    }

    /// **The one refusal that can be overridden**, and the only one.
    ///
    /// Every other refusal describes something that cannot be a destination: a URL, a blank, a port
    /// that is not a number. A bare suffix is different in kind — `example-homelab.invalid:8443` is a
    /// perfectly valid place to connect, and for somebody whose bridge really does sit on a two-label
    /// name it is the right answer. Refusing it outright would prohibit a legitimate thing in order to
    /// prevent one specific mistake.
    ///
    /// **So it is made deliberate rather than impossible**: refused by default with the sentence
    /// below, and saveable behind an explicit second act. That is the same shape as deleting a session
    /// on the phone — two acts, not a locked door — and it is the shape this window already uses at
    /// the other end, where an unproven address gets its code *with the warning on it* rather than
    /// being withheld. Withholding the code and refusing the save would have been the same idea
    /// contradicted.
    static let confirmationQuestion = "Save it anyway?"

    /// **This refusal is the whole reason validation exists**, so it says what happened rather than
    /// what is wrong.
    ///
    /// A two-label name like `example-homelab.invalid` is the homelab's *suffix* — the name every
    /// other service hangs off. It resolves. It answers. Something on the other end completes a TLS
    /// handshake and it is a media server, or a photo library, or the router's own web interface. A
    /// code built for it pairs perfectly and then never connects, and nothing in the pairing flow can
    /// tell the owner why, because from the phone's side the handshake simply fails later.
    static func bareSuffixExplanation(_ host: String) -> String {
        "\"\(host)\" looks like the suffix your whole homelab hangs off, not the bridge's own name. "
            + "That name answers — it belongs to every other service behind the same router — so a code "
            + "built from it pairs and then never connects. This is the mistake that cost an afternoon "
            + "on 2026-08-09. Put the bridge's own label in front of it, as in \"agterm.\(host)\". If "
            + "the bridge really does sit on the bare name, save it anyway — deliberately."
    }

    /// Whether a typed address is the one refusal that a second act can override. Exposed so the caller
    /// can offer the confirmation rather than inferring it from the text of a sentence.
    public static func isConfirmable(_ refusal: Refusal) -> Bool {
        if case .bareSuffix = refusal { return true }
        return false
    }

    /// Parses **one typed address** — `host:port`, exactly as every other surface renders it.
    ///
    /// ### Why one field, written after two were shipped
    ///
    /// This was two boxes — host and port — for a day, and splitting them was the defect.
    /// `DialAddress.displayed` exists so the address is **one string a person compares against another
    /// one string**: the phone shows `host:port`, the code carries `host:port`, the comparison screen
    /// shows `host:port`, and the single place it was *entered* was the single place it stopped being
    /// one value.
    ///
    /// The tell, which should have been enough on its own: with two boxes, typing
    /// `agterm.example.invalid:8443` into the host box was a **refusal**. That is what a person
    /// naturally types and what every other surface displays. A validation error had been written for
    /// the correct input, and a rule that has to reject the natural gesture is a rule describing the
    /// program's storage layout rather than the person's address.
    ///
    /// - Parameter allowingBareSuffix: set only by a save the owner has confirmed. It is the single
    ///   check in here that describes a *risk* rather than an impossibility, so it is the single one a
    ///   second act can switch off.
    public static func parse(_ typed: String, allowingBareSuffix: Bool = false) -> Result<DialAddress, Refusal> {
        let text = typed.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !text.isEmpty else { return .failure(.blank) }
        guard !text.contains(where: { $0.isWhitespace }) else { return .failure(.hasWhitespace) }
        if text.contains("://") || text.contains("/") { return .failure(.looksLikeAURL(text)) }

        // The structure is the formatter's, read backwards; only the judgement about what the address
        // MEANS is here.
        switch DialAddress.parse(text) {
        case .failure(let why):
            return .failure(Refusal.from(why))
        case .success(let address):
            if isBareSuffix(address.host), !allowingBareSuffix { return .failure(.bareSuffix(address.host)) }
            return .success(address)
        }
    }

    /// Exactly two labels, and not an address literal. `agterm.example.invalid` passes; `workshop`
    /// passes, because a single LAN name is somebody's real host and not a suffix; `192.0.2.10` and
    /// `[fe80::1]` pass, because a literal names one machine and cannot be a shared suffix.
    ///
    /// A trailing dot is ignored **for this test only** — `example.invalid.` is the same suffix — and
    /// is preserved in what gets saved.
    private static func isBareSuffix(_ host: String) -> Bool {
        if host.hasPrefix("[") || host.contains(":") { return false }
        let labels = host.split(separator: ".", omittingEmptySubsequences: true)
        guard labels.count == 2 else { return false }
        // 1.2 is not a hostname anybody means, but it is not a suffix either; leave it to the resolver.
        if labels.allSatisfy({ $0.allSatisfy(\.isNumber) }) { return false }
        // `.local` names are mDNS: `workshop.local` is one machine, not a suffix.
        if labels[1].lowercased() == "local" { return false }
        return true
    }
}

private extension AddressEdit.Refusal {

    /// The structural failures, given the owner's sentences. `DialAddress.ParseFailure` says what the
    /// string is; this says what to do about it.
    static func from(_ failure: DialAddress.ParseFailure) -> AddressEdit.Refusal {
        switch failure {
        case .empty: .blank
        case .noPort(let text): .noPort(text)
        case .portNotANumber(let text): .portNotANumber(text)
        case .portOutOfRange(let port): .portOutOfRange(port)
        case .ambiguousWithoutBrackets(let text): .ambiguousWithoutBrackets(text)
        case .unclosedBracket(let text): .unclosedBracket(text)
        }
    }
}

/// What the one text field holds. A string, not an address: the point of the screen is that somebody
/// is part-way through typing something that is not yet valid.
public struct AddressField: Equatable, Sendable {

    public let text: String

    public init(text: String = "") {
        self.text = text
    }

    /// **Prefilled with `displayed`** — the same rendering the phone's comparison screen and the QR
    /// carry. The box they edit shows the same string as every other surface, so there is no moment
    /// where the address exists in two shapes.
    ///
    /// A missing or malformed file leaves it empty rather than offering `:8443` as though a port had
    /// been checked; the port the phone reaches is whatever the router forwards.
    public init(_ address: Result<DialAddress, AddressPreference.ReadFailure>) {
        switch address {
        case .success(let dial): self.init(text: dial.displayed)
        case .failure: self.init()
        }
    }
}

/// Parse, then write — **once, to the one place the address lives**.
public struct SaveAddress: Sendable {

    public enum Outcome: Equatable, Sendable {
        case saved(DialAddress)
        /// Nothing was written. Carries the sentence for the owner.
        case refused(String)
        /// **Nothing was written yet**, and it can be, if they say so. The only outcome that offers a
        /// second act: the address is a valid destination that looks like the mistake of 2026-08-09.
        /// Carries the same sentence plus the question, so the caller does not compose either.
        case needsConfirmation(explanation: String, question: String)
        /// It parsed and the write failed, which is a different sentence: the address they typed is
        /// fine and the file is the problem.
        case notWritten(String)
    }

    private let write: @Sendable (DialAddress) throws -> Void

    /// `AddressPreference.write` by default — the one place the address lives, injected so the
    /// outcome for a store that refuses can be tested without a store that refuses.
    public init(write: @escaping @Sendable (DialAddress) throws -> Void = { try AddressPreference.write($0) }) {
        self.write = write
    }

    /// - Parameter confirmed: the owner's second act, and **only ever their second act**. It overrides
    ///   exactly one refusal — the bare suffix — and no other. A confirmation that swept aside a blank
    ///   host or a port of 70000 would be a way of typing around validation rather than a way of
    ///   meaning it.
    public func save(_ typed: String, confirmed: Bool = false) -> Outcome {
        // Confirming loosens exactly one check, in the parser, rather than routing around it here.
        switch AddressEdit.parse(typed, allowingBareSuffix: confirmed) {
        case .failure(let refusal) where AddressEdit.isConfirmable(refusal):
            return .needsConfirmation(
                explanation: refusal.explanation, question: AddressEdit.confirmationQuestion)
        case .failure(let refusal):
            return .refused(refusal.explanation)
        case .success(let address):
            do {
                try write(address)
            } catch {
                return .notWritten("The address is fine; saving it failed: \(error)")
            }
            return .saved(address)
        }
    }
}
