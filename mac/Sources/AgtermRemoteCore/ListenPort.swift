import Foundation

/// The port traffic **arrives on** at this Mac, which is not always the port the phone dials.
///
/// ### Why this is a second field and not a second copy of the first
///
/// A one-to-one port forward makes the two the same, and that is the common case — so this is
/// stored as an absence and follows the dial port until somebody says otherwise. Everything else
/// breaks the assumption: a router that publishes 8443 and proxies it to 8444, a tunnel that
/// terminates on a port of its own, a reverse proxy in front of both. The source project's runbook
/// records the same topology as the thing that cost it two failed pairings, and its pairing tool
/// takes the host and the port with **no defaults, deliberately**, so that nobody can reuse one
/// address as the other.
///
/// **Deriving the bind from the dial address is that same conflation, running backwards**: the code
/// is perfect, the bridge is listening, and the phone reaches a port nothing is on.
public enum ListenPortEdit {

    public enum Refusal: Error, Equatable, Sendable {
        case notANumber(String)
        case outOfRange(Int)

        public var explanation: String {
            switch self {
            case .notANumber(let text):
                "\"\(text)\" is not a port. Leave this empty if your router forwards straight through, "
                    + "or type the number this Mac receives on."
            case .outOfRange(let port):
                "\(port) is not a port. A port is a number from 1 to 65535."
            }
        }
    }

    /// Empty means **follow the dial port**, which is the answer for anybody who should not have to
    /// think about this field at all.
    public static func parse(_ typed: String) -> Result<Int?, Refusal> {
        let text = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return .success(nil) }
        guard let port = Int(text) else { return .failure(.notANumber(text)) }
        guard (1...65535).contains(port) else { return .failure(.outOfRange(port)) }
        return .success(port)
    }
}

/// Parse, then write — to the same store the address lives in, never a second one.
public struct SaveListenPort: Sendable {

    public enum Outcome: Equatable, Sendable {
        /// An arrival port of its own.
        case saved(Int)
        /// Cleared, so the bind follows the dial port. Carries it, when there is one, because the
        /// sentence that follows says which port that now is.
        case following(dialPort: Int?)
        case refused(String)
        case notWritten(String)
    }

    private let write: @Sendable (Int?) throws -> Void

    public init(write: @escaping @Sendable (Int?) throws -> Void = {
        try AddressPreference.writeListenPort($0)
    }) {
        self.write = write
    }

    public func save(_ typed: String, dialPort: Int?) -> Outcome {
        switch ListenPortEdit.parse(typed) {
        case .failure(let refusal):
            return .refused(refusal.explanation)
        case .success(let port):
            do {
                try write(port)
            } catch {
                return .notWritten("The port is fine; saving it failed: \(error)")
            }
            return port.map(Outcome.saved) ?? .following(dialPort: dialPort)
        }
    }
}

/// **A port that is already somebody else's, said in a sentence rather than in a log line.**
///
/// The bridge reports what the operating system told it — `bind: address already in use` — which is
/// exact and tells nobody what to do about it. It is also the single most likely way a start fails
/// on a machine that is already running something similar: another copy of this bridge, an earlier
/// installation of it, or an unrelated service that got there first. The port has to be in the
/// sentence, because the owner has two ports in play and the wrong one is the whole problem.
public enum PortInUse {

    /// The owner's sentence when that is what happened, or nil when it is not — in which case the
    /// bridge's own words are the better answer and are used unchanged.
    public static func explanation(for reported: String, port: Int) -> String? {
        let said = reported.lowercased()
        guard said.contains("address already in use") || said.contains("bind: address in use") else {
            return nil
        }
        return "Something on this Mac is already listening on port \(port), so the bridge could not "
            + "start. That is usually another copy of it — including one from a separate installation "
            + "you are still running. Stop whatever holds the port, or set a different arrival port "
            + "on the pairing screen."
    }
}
