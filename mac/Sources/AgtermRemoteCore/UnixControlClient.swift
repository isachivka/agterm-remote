import Foundation

/// Why a control request did not produce an answer, in words a person can act on.
///
/// **`.notListening` is the ordinary first answer, not an error condition.** The bridge is a child
/// this app starts and stops; before the owner has pressed Start there is no socket, and a panel that
/// reported that as a failure of the panel would send somebody looking in the wrong place.
public enum ControlFailure: Error, Equatable, CustomStringConvertible {

    /// Nothing is answering on the socket. Carries the path, because the two ways this happens — the
    /// bridge is not running, and the bridge is running against a different state directory — look
    /// identical without it.
    case notListening(path: String)

    /// It answered, and refused. The bridge's own sentence, verbatim.
    case refused(String)

    /// It answered with something this app cannot read. A bridge newer than the app, or not a bridge.
    case unreadable(String)

    /// **The sentence, and nothing around it.** These go straight onto a panel, and the default
    /// rendering of an enum with an associated value would put the case name in front.
    public var description: String {
        switch self {
        case .notListening(let path):
            "The bridge is not running, so there is nothing to ask for a code. Start it from the menu. "
                + "(Nothing is answering at \(path).)"
        case .refused(let sentence):
            sentence
        case .unreadable(let what):
            "The bridge answered with something this app could not read: \(what)"
        }
    }
}

/// The bridge's local door, spoken over its unix socket.
///
/// ### One request per connection, because that is what the far end serves
///
/// Connect, write one line, read one line, close. The bridge reads nothing after the first newline
/// and closes as soon as it has replied — deliberately, and pinned by a test over there so that
/// whoever wrote this found it in a document rather than in a debugger. A connection here costs a
/// syscall pair and no handshake, so there is nothing to keep.
///
/// ### The socket this names is OUR bridge's, in the state directory this app hands it
///
/// It is not agterm's. agterm's control socket lives under `Library/Application Support/agterm` and
/// this app has no business opening it — every command the phone can cause has argued its way past
/// the allowlist inside the bridge, and a menu-bar app that spoke to agterm directly would walk
/// around that allowlist entirely. `BoundaryTests` holds that line, and it holds it by requiring that
/// the one file naming a control socket builds the path from an injected directory, which is this
/// one.
public final class UnixControlClient: ControlClient, @unchecked Sendable {

    /// The file the bridge serves, inside its state directory. Named by `control.SocketName` at the
    /// other end; one constant here so a client looking in the wrong place cannot report "the bridge
    /// is not running" about a bridge that is.
    static let socketName = "control.sock"

    /// **104 bytes, in the kernel's `sockaddr_un`.** Exceeding it fails as `bind: invalid argument`
    /// at the far end and as a silent refusal here, naming neither the path nor the length. The real
    /// state directory is nowhere near it; a test's temporary directory has been.
    static let maxPathLength = 104

    /// How long a call may take. The far end sets its own deadline; this is the near half, so a
    /// bridge that accepts and then says nothing cannot hold the panel's thread.
    static let timeout: TimeInterval = 5

    /// How much of a reply is read before it is treated as something other than a reply. Every one of
    /// these is a short JSON object; a stored certificate would not fit and is not sent.
    static let maxReplyBytes = 64 * 1024

    private let path: String

    /// - Parameter stateDirectory: the directory this app passes the bridge with `--state-dir`. The
    ///   socket's location is derived from it rather than configured separately, because two answers
    ///   to "where is the bridge" is how a client comes to report a bridge that is running as down.
    public init(stateDirectory: URL) {
        path = stateDirectory.appending(path: Self.socketName).path
    }

    /// Where this client will look. Exposed so the app can say the path in a failure without
    /// rebuilding it a second way.
    public var socketPath: String { path }

    // MARK: - The verbs

    public func status() throws
        -> (listening: String, paired: [PairedPhone], agterm: Bool, window: PairingWindowReport) {
        let reply = try call(["verb": "status"])
        guard let listening = reply["listening"] as? String else {
            throw ControlFailure.unreadable("a status with no listening address")
        }
        let peers = (reply["paired"] as? [[String: Any]] ?? []).compactMap { peer -> PairedPhone? in
            guard let fingerprint = peer["fingerprint"] as? String else { return nil }
            return PairedPhone(fingerprint: fingerprint, name: peer["name"] as? String ?? "")
        }
        let window = reply["window"] as? [String: Any] ?? [:]
        let expiry = window["expires_at"] as? Int
        return (
            listening: listening,
            paired: peers,
            agterm: reply["agterm"] as? Bool ?? false,
            window: PairingWindowReport(
                open: window["open"] as? Bool ?? false,
                // Absent when nothing is open, which is not the epoch. A zero read as a date would
                // put 1970 on the panel and expire every code the instant it was drawn.
                expiresAt: expiry.map { Date(timeIntervalSince1970: TimeInterval($0)) },
                attemptsLeft: window["attempts_left"] as? Int ?? 0,
                ended: PairingEnding(wire: window["ended"] as? String ?? ""))
        )
    }

    public func openPairing(ttl: TimeInterval, advertise: String) throws -> (payload: String, expiresAt: Date) {
        // Whole seconds: the wire field is an integer count and the payload carries Unix seconds.
        // Rounded up rather than truncated, so a ttl expressed as a fraction never becomes zero — the
        // bridge refuses a non-positive ttl, correctly, and it would be this app's rounding that
        // produced it.
        let seconds = max(Int(ttl.rounded(.up)), 1)
        // **Omitted when empty rather than sent empty.** The bridge refuses unknown fields, and an
        // empty `advertise` means "use yours" — which is what leaving the key out already says, in
        // the one spelling the far end documents.
        var request: [String: Any] = ["verb": "pair-open", "ttl_seconds": seconds]
        if !advertise.isEmpty { request["advertise"] = advertise }
        let reply = try call(request)
        guard let payload = reply["payload"] as? String, !payload.isEmpty,
            let expiry = reply["expires_at"] as? Int
        else {
            throw ControlFailure.unreadable("a pairing code with no payload or no expiry")
        }
        // **The expiry the bridge sent, never `now + ttl`.** The window clamps the ttl it was asked
        // for and hands back what it will actually enforce; a panel that counted down its own request
        // would advertise a code that stops working before the number on screen reaches zero.
        return (payload, Date(timeIntervalSince1970: TimeInterval(expiry)))
    }

    public func closePairing() throws {
        _ = try call(["verb": "pair-close"])
    }

    public func unpair(fingerprint: String) throws {
        let reply = try call(["verb": "unpair", "fingerprint": fingerprint])
        // **`unpaired`, not `ok`.** The bridge answers a fingerprint it does not hold with an error
        // rather than a shrug, precisely so a user interface cannot report a phone as removed while
        // it is still able to drive the owner's terminal.
        guard reply["unpaired"] as? Bool == true else {
            throw ControlFailure.refused(reply["error"] as? String ?? "the phone was not unpaired")
        }
    }

    // MARK: - One request, one connection

    private func call(_ request: [String: Any]) throws -> [String: Any] {
        let line = try JSONSerialization.data(withJSONObject: request)
        let raw = try exchange(line)
        guard let object = try? JSONSerialization.jsonObject(with: raw) as? [String: Any] else {
            throw ControlFailure.unreadable(String(decoding: raw.prefix(200), as: UTF8.self))
        }
        // Every refusal before a verb was understood, and every verb this bridge cannot serve, comes
        // back as a bare `error`. Raised here rather than at four call sites.
        if let error = object["error"] as? String, !error.isEmpty {
            throw ControlFailure.refused(error)
        }
        return object
    }

    /// Connect, write, read to end, close. Written against the C API rather than `Network.framework`
    /// because this is one blocking round trip on a local socket with no queue, no path monitor and
    /// nothing to cancel — and because the failure this most has to report clearly is `ENOENT`.
    private func exchange(_ request: Data) throws -> Data {
        guard path.utf8.count < Self.maxPathLength else {
            throw ControlFailure.notListening(path: path)
        }

        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { throw ControlFailure.notListening(path: path) }
        defer { close(fd) }

        var timeout = timeval(tv_sec: Int(Self.timeout), tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

        var address = sockaddr_un()
        address.sun_family = sa_family_t(AF_UNIX)
        // `sun_path` is a fixed C array, so it is filled through a raw pointer to itself rather than
        // by any Swift string API. The length was checked above; the terminator is the zero the
        // struct is already initialised with.
        _ = withUnsafeMutablePointer(to: &address.sun_path) { field in
            path.withCString { source in
                field.withMemoryRebound(to: CChar.self, capacity: Self.maxPathLength) { destination in
                    strncpy(destination, source, Self.maxPathLength - 1)
                }
            }
        }
        let size = socklen_t(MemoryLayout<sockaddr_un>.size)
        let connected = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, size) }
        }
        // Every reason a connect to a unix socket fails is the same fact for the owner: nothing is
        // answering there. `ENOENT` for a bridge that never started, `ECONNREFUSED` for the socket
        // file a cleanly stopped bridge leaves behind — that file is deliberate at the far end, and
        // this is the client half of that decision.
        guard connected == 0 else { throw ControlFailure.notListening(path: path) }

        var line = request
        line.append(0x0A)
        try line.withUnsafeBytes { buffer in
            var sent = 0
            while sent < buffer.count {
                let wrote = write(fd, buffer.baseAddress!.advanced(by: sent), buffer.count - sent)
                guard wrote > 0 else { throw ControlFailure.notListening(path: path) }
                sent += wrote
            }
        }

        var reply = Data()
        var chunk = [UInt8](repeating: 0, count: 4096)
        while reply.count < Self.maxReplyBytes {
            let read = chunk.withUnsafeMutableBytes { Foundation.read(fd, $0.baseAddress, $0.count) }
            guard read > 0 else { break }
            reply.append(contentsOf: chunk[0..<read])
            // The far end writes one line and closes. Stopping at the newline means a bridge that
            // held the connection open would not hold this thread for the whole receive timeout.
            if reply.last == 0x0A { break }
        }
        guard !reply.isEmpty else { throw ControlFailure.notListening(path: path) }
        return reply
    }
}
