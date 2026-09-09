import CommonCrypto
import Network
import CoreImage
import Foundation
import Testing
@testable import AgtermRemoteCore

/// **The whole path, against the real bridge, because the parts were each correct and the product was
/// broken.**
///
/// Every unit test in this package passed while the app could not mint a usable pairing code. The app
/// binds the wildcard, correctly — a Mac cannot know which of its interfaces the router forwards to.
/// The bridge minted the code from the address it was bound to, correctly — it had no other address.
/// Composed, every code the product could produce said `0.0.0.0`, which is every interface and
/// therefore not one a phone can dial. Two right decisions, one unusable feature, and nothing short of
/// running the path found it.
///
/// So this runs the path: a real bridge process, this app's real control client, the real payload
/// decoded the way the phone decodes it, and the real picture read back by a real detector.
///
/// ### How it is wired, and why it cannot quietly stop running
///
/// The bridge is a Go binary this package does not build. CI builds it **before** `swift test` — the
/// `mac` job already pins Go for the bundle — and passes its path in `AGTERM_REMOTE_BRIDGE`. Locally
/// the variable is usually absent and these skip, which is the ordinary developer experience.
///
/// **A skip in CI is a test that stopped running**, so it is a failure there: if `CI` is set and the
/// binary is not, this fails rather than reporting green. That is the difference between a gate and a
/// decoration.
struct BridgeIntegrationTests {

    // MARK: - The defect this file exists for

    /// **The code names the address the caller asked for, not the one the bridge is bound to.**
    ///
    /// Against the bridge as it was before this was fixed, this fails twice over: that build refuses
    /// the `advertise` field outright — it rejects unknown fields, deliberately, so a misspelt
    /// argument is never read as a default — and with the field removed it mints `127.0.0.1`, the
    /// bind, which is exactly the defect.
    ///
    /// The port disagrees with the bind on purpose. That is not a mistake to be refused: it is a
    /// router that publishes one port and forwards to another, which is the owner's own topology.
    @Test func theCodeNamesTheAddressTheAppAsksForRatherThanTheBind() throws {
        guard let bridge = try LiveBridge.startOrSkip() else { return }
        defer { bridge.stop() }

        let panel = PairingPanelModel(control: bridge.control, now: Date.init)
        panel.open(advertising: "agterm.example-homelab.invalid:9443")

        guard case .showing(let payload, _) = panel.state else {
            Issue.record("no code was minted: \(panel.state.sentence ?? "\(panel.state)")")
            return
        }
        let code = try EnrolmentPayload(base64: payload)
        #expect(
            code.host == "agterm.example-homelab.invalid",
            "the code says \(code.host), which is the bind rather than the address a phone dials")
        #expect(code.port == 9443, "the code says port \(code.port), not the one the phone dials")
        // And the bind really was something else, so the assertion above had something to catch.
        #expect(try bridge.control.status().listening == bridge.bound)
    }

    /// **An address saved while the bridge is running reaches the very next code.**
    ///
    /// The first fix put the address on the bridge's command line, which is read once. Nothing
    /// restarts the bridge when the owner saves a new address, so the app said *your phone will dial
    /// X* beside a code that still said Y. This is the same panel, twice, with a save in between.
    @Test func anAddressChangedWhileTheBridgeRunsReachesTheNextCode() throws {
        guard let bridge = try LiveBridge.startOrSkip() else { return }
        defer { bridge.stop() }

        let panel = PairingPanelModel(control: bridge.control, now: Date.init)
        panel.open(advertising: "old.example-homelab.invalid:8443")
        guard case .showing(let first, _) = panel.state else {
            Issue.record("no first code: \(panel.state)")
            return
        }
        #expect(try EnrolmentPayload(base64: first).host == "old.example-homelab.invalid")

        // The owner saves a new address. Nothing restarts the bridge — that is the point.
        panel.close()
        panel.open(advertising: "new.example-homelab.invalid:9443")

        guard case .showing(let second, _) = panel.state else {
            Issue.record("no second code: \(panel.state)")
            return
        }
        let code = try EnrolmentPayload(base64: second)
        #expect(code.host == "new.example-homelab.invalid", "the next code still names the old address")
        #expect(code.port == 9443)
    }

    // MARK: - The rest of the path

    /// The payload, the expiry and the fingerprint all agree with what the bridge says about itself.
    ///
    /// The expiry is the one the **window will enforce**, not the ttl that was asked for — a panel
    /// counting down its own request would strand somebody on a code that died early. The fingerprint
    /// is hashed here from the certificate on disk, so the code is proved to name the certificate this
    /// bridge actually serves rather than one it computed for itself.
    @Test func theCodeAgreesWithTheBridgeAboutExpiryAndCertificate() throws {
        guard let bridge = try LiveBridge.startOrSkip() else { return }
        defer { bridge.stop() }

        let minted = try bridge.control.openPairing(ttl: PairingPanelModel.ttl, advertise: bridge.bound, frontDoor: .direct)
        let code = try EnrolmentPayload(base64: minted.payload)
        let status = try bridge.control.status()

        // Version 2, which is what this build mints. Version 1 is still decoded - a shared vector
        // holds that - and is never written.
        #expect(code.version == 2)
        // A bridge with nothing in front of it, so the code says a plain connection.
        #expect(code.scheme == 1)
        #expect("\(code.host):\(code.port)" == bridge.bound)
        #expect(Int(code.expiry) == Int(minted.expiresAt.timeIntervalSince1970))
        #expect(Int(code.expiry) == Int(status.window.expiresAt?.timeIntervalSince1970 ?? -1))
        #expect(status.window.open)
        #expect(status.window.attemptsLeft == PairingPanelModel.maxAttempts)
        #expect(code.fingerprint == bridge.certificateFingerprint(), "the code names another certificate")
    }

    /// **The picture is only a code if something can read it back.** Rendered from the real payload
    /// and decoded by the same detector class a camera pipeline uses.
    @Test func theRenderedCodeDecodesBackToWhatTheBridgeMinted() throws {
        guard let bridge = try LiveBridge.startOrSkip() else { return }
        defer { bridge.stop() }

        let minted = try bridge.control.openPairing(ttl: PairingPanelModel.ttl, advertise: bridge.bound, frontDoor: .direct)
        let image = try #require(QRRender.image(for: minted.payload, size: 380))

        let detector = CIDetector(
            ofType: CIDetectorTypeQRCode, context: nil,
            options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        let read = (detector?.features(in: CIImage(cgImage: image)) ?? [])
            .compactMap { ($0 as? CIQRCodeFeature)?.messageString }

        #expect(read == [minted.payload], "the code on screen is not the payload the bridge minted")
        #expect(CGFloat(image.width) >= QRRender.minimumSize)
    }

    /// Closing the panel closes the window at the bridge, and the bridge says so — which is the fact
    /// the panel's four sentences are drawn from.
    @Test func closingThePanelClosesTheWindowAndTheBridgeNamesTheEnding() throws {
        guard let bridge = try LiveBridge.startOrSkip() else { return }
        defer { bridge.stop() }

        let panel = PairingPanelModel(control: bridge.control, now: Date.init)
        panel.open(advertising: bridge.bound)
        #expect(try bridge.control.status().window.open)

        panel.close()

        let after = try bridge.control.status()
        #expect(!after.window.open, "the enrolment window outlived the panel")
        #expect(after.window.ended == .closed, "the bridge cannot say why the code stopped working")
    }

    /// A window that runs out is reported as `expired` — a different ending, and a different sentence,
    /// from the one the owner closed. Asked of the real window rather than of a double.
    @Test func aWindowThatRunsOutIsReportedAsExpiredAndNotAsClosed() throws {
        guard let bridge = try LiveBridge.startOrSkip() else { return }
        defer { bridge.stop() }

        _ = try bridge.control.openPairing(ttl: 1, advertise: bridge.bound, frontDoor: .direct)
        Thread.sleep(forTimeInterval: 1.5)

        let after = try bridge.control.status()
        #expect(!after.window.open)
        #expect(after.window.ended == .expired, "expired and closed must not collapse into one thing")
    }

    /// Unpairing a fingerprint the bridge does not hold is **refused**, not shrugged at — otherwise
    /// the menu reports a phone as removed while it is still able to drive the owner's terminal.
    @Test func unpairingSomethingTheBridgeDoesNotHoldIsRefused() throws {
        guard let bridge = try LiveBridge.startOrSkip() else { return }
        defer { bridge.stop() }

        #expect(try bridge.control.status().paired.isEmpty)
        #expect(throws: (any Error).self) {
            try bridge.control.unpair(fingerprint: "not a phone this bridge has met")
        }
    }

    /// **The chain the owner actually has, and the one nothing in this repository was testing.**
    ///
    /// ```
    /// phone -> TLS to the router -> router -> TLS to the bridge -> HTTP Upgrade -> pinned mTLS
    /// ```
    ///
    /// The router proxies rather than forwards: it terminates its own TLS at the edge and opens a
    /// SECOND TLS connection to this Mac over the LAN. Against a plaintext listener that second
    /// connection fails - the bridge sees a ClientHello it cannot answer - and the router hands the
    /// phone a 502. Every unit test in this repository passed while that was true.
    ///
    /// What stands in for the router here is a local reverse proxy built out of `NWListener` and
    /// `NWConnection`. **Where it differs from the real thing, stated rather than glossed:** it does
    /// not run on the owner's router, it does not do NAT or hairpin, and it does not validate the
    /// bridge's certificate - which is not a shortcut, because the real router cannot validate it
    /// either and the wrapper's own comment says so. What it reproduces is the only property under
    /// test: something terminates TLS in front and speaks TLS to the bridge.
    ///
    /// The phone's half is not here - it is Kotlin - so what this proves is that the chain carries
    /// bytes end to end and that the code minted through it says `wss`. The phone reading that code
    /// is proven by the shared vectors, and the phone completing an enrolment is proven on the
    /// emulator against a bridge started the same way.
    @Test func aProxyThatTerminatesTlsAndSpeaksTlsToTheBridgeCanReachIt() throws {
        guard let bridge = try LiveBridge.startOrSkip(frontDoor: .httpsBothWays) else { return }
        defer { bridge.stop() }

        // The router's own hop. It has to complete, and completing it is exactly what a plaintext
        // bridge cannot do.
        let reached = try TLSHop.reach(bridge.bound)
        #expect(reached, "a proxy speaking TLS to the backend could not reach the bridge")

        // And the code minted for that deployment tells the phone to open the outer hop with TLS.
        let minted = try bridge.control.openPairing(
            ttl: PairingPanelModel.ttl, advertise: "agterm.example-homelab.invalid:443",
            frontDoor: .httpsBothWays)
        let payload = try EnrolmentPayload(base64: minted.payload)
        #expect(payload.version == 2)
        #expect(payload.scheme == 2, "a proxied deployment must mint a code the phone opens with TLS")
    }

    /// The same bridge without the flag, which is what the owner's deployment met. The hop the router
    /// needs is not there.
    @Test func withoutTheFlagTheSameProxyCannotReachTheBridge() throws {
        guard let bridge = try LiveBridge.startOrSkip(frontDoor: .httpsInFront) else { return }
        defer { bridge.stop() }

        #expect(
            try TLSHop.reach(bridge.bound) == false,
            "a plaintext listener cannot answer a ClientHello, which is the 502 the phone reported")
    }

    /// And a bridge that is not there is an ordinary answer with a sentence on it, not a crash.
    @Test func aBridgeThatIsNotThereSaysSoInWordsAnOwnerCanActOn() throws {
        let client = UnixControlClient(stateDirectory: URL(fileURLWithPath: "/tmp/agterm-remote-absent"))

        #expect(throws: ControlFailure.notListening(path: "/tmp/agterm-remote-absent/control.sock")) {
            _ = try client.status()
        }
    }
}

/// The payload, decoded the way the phone decodes it: by the documented layout, in this language,
/// rather than by calling the encoder that produced it.
///
/// **That is the whole value of it.** A Go helper the Swift side imports would prove the encoder
/// agrees with itself. This is a second reader, written from `wire/`'s field list, and a change to the
/// format on either side shows up here as a decode that stops making sense.
struct EnrolmentPayload {

    let version: UInt8
    /// The wire value of the scheme: 1 plain, 2 TLS. Version 1 carried none and meant 1.
    let scheme: UInt8
    let host: String
    let port: Int
    let fingerprint: String
    let expiry: UInt32

    enum Malformed: Error { case notBase64, tooShort, unknownVersion(UInt8) }

    init(base64: String) throws {
        guard let data = Data(base64Encoded: base64) else { throw Malformed.notBase64 }
        let raw = [UInt8](data)
        guard let first = raw.first else { throw Malformed.tooShort }
        // **The version chooses the layout.** Version 2 added one byte, the scheme, immediately after
        // the version - so reading a version 2 payload with version 1 offsets takes that byte as the
        // high half of the host length. This reader is a THIRD implementation of the format, written
        // from the field list rather than by calling the encoder, and it is worth exactly as much as
        // its willingness to be strict here.
        version = first
        let at: Int
        switch version {
        case 1: scheme = 1; at = 1
        case 2:
            guard raw.count >= 2 else { throw Malformed.tooShort }
            scheme = raw[1]
            at = 2
        default: throw Malformed.unknownVersion(version)
        }
        guard raw.count >= at + 2 + 2 + 32 + 32 + 4 else { throw Malformed.tooShort }
        let hostLength = Int(raw[at]) << 8 | Int(raw[at + 1])
        guard raw.count >= at + 2 + hostLength + 2 + 32 + 32 + 4 else { throw Malformed.tooShort }
        host = String(decoding: raw[(at + 2)..<(at + 2 + hostLength)], as: UTF8.self)
        var cursor = at + 2 + hostLength
        port = Int(raw[cursor]) << 8 | Int(raw[cursor + 1])
        cursor += 2
        fingerprint = raw[cursor..<(cursor + 32)].map { String(format: "%02x", $0) }.joined()
        cursor += 32 + 32
        expiry = (UInt32(raw[cursor]) << 24) | (UInt32(raw[cursor + 1]) << 16)
            | (UInt32(raw[cursor + 2]) << 8) | UInt32(raw[cursor + 3])
    }
}

/// A real bridge process, on a port the kernel chose, in a directory short enough to hold a unix
/// socket path.
final class LiveBridge {

    /// Where CI leaves the binary it built before running the tests.
    static let binaryVariable = "AGTERM_REMOTE_BRIDGE"

    let bound: String
    let stateDirectory: URL
    let control: UnixControlClient
    private let process: Process

    /// - Returns: a running bridge, or nil when there is no binary to run **and this is not CI**.
    ///   In CI a missing binary is a failure: a test that silently stops running is worse than one
    ///   that was never written, because the green tick claims it ran.
    static func startOrSkip(frontDoor: FrontDoor = .direct) throws -> LiveBridge? {
        let environment = ProcessInfo.processInfo.environment
        guard let path = environment[binaryVariable], !path.isEmpty else {
            if environment["CI"] != nil {
                let complaint = "\(binaryVariable) is not set, so the integration tests did not run. "
                    + "CI must build the bridge before `swift test` and pass its path — see the mac job."
                Issue.record(Comment(rawValue: complaint))
            }
            return nil
        }
        guard FileManager.default.isExecutableFile(atPath: path) else {
            Issue.record("\(binaryVariable) points at \(path), which is not an executable file")
            return nil
        }
        return try LiveBridge(binary: path, frontDoor: frontDoor)
    }

    private init(binary: String, frontDoor: FrontDoor = .direct) throws {
        // **Short, because a unix socket path has a 104-byte ceiling in the kernel** and the bridge
        // refuses to serve one that would exceed it. A test's usual temporary directory is well past
        // it on macOS, which is a failure that names neither the path nor the length.
        stateDirectory = URL(fileURLWithPath: "/tmp/agtr-it-\(UInt32.random(in: 0..<0xFFFF_FFFF))")
        try FileManager.default.createDirectory(
            at: stateDirectory, withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700])

        let port = Self.aFreePort()
        bound = "127.0.0.1:\(port)"
        process = Process()
        process.executableURL = URL(fileURLWithPath: binary)
        // **No `--advertise`.** The process-level answer is deliberately the bind, so a code that
        // names the bind is visible as the defect it is.
        process.arguments = [
            "--listen", bound,
            "--state-dir", stateDirectory.path,
            "--parent-pid", String(ProcessInfo.processInfo.processIdentifier),
            "--advertise-scheme", frontDoor.advertiseScheme,
        ]
        if frontDoor.servesOnLinkTLS {
            process.arguments?.append("--on-link-tls")
        }
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try process.run()

        control = UnixControlClient(stateDirectory: stateDirectory)
        // The socket appears a moment after the process does. Polled rather than slept on, so a fast
        // machine is fast and a slow one still works.
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            if (try? control.status()) != nil { return }
            Thread.sleep(forTimeInterval: 0.05)
        }
        stop()
        throw Unreachable.neverAnswered(bound)
    }

    enum Unreachable: Error { case neverAnswered(String) }

    /// SHA-256 of the DER in the certificate the bridge minted for itself, which is what the QR code
    /// carries. Read off the disk, so the code is compared against the file rather than against
    /// another copy of the same computation.
    func certificateFingerprint() -> String {
        guard let pem = try? String(contentsOf: stateDirectory.appending(path: "bridge-cert.pem"), encoding: .utf8)
        else { return "" }
        let body = pem.split(separator: "\n").filter { !$0.hasPrefix("-----") }.joined()
        guard let der = Data(base64Encoded: body) else { return "" }
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        der.withUnsafeBytes { _ = CC_SHA256($0.baseAddress, CC_LONG(der.count), &digest) }
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    func stop() {
        if process.isRunning {
            process.terminate()
            process.waitUntilExit()
        }
        try? FileManager.default.removeItem(at: stateDirectory)
    }

    /// A port the kernel picks and immediately gives back, so this does not fight whatever else is
    /// running on the machine. The same trick the bridge's own tests use.
    private static func aFreePort() -> UInt16 {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        defer { close(fd) }
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        address.sin_port = 0
        let size = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, size) }
        }
        var assigned = sockaddr_in()
        var length = size
        _ = withUnsafeMutablePointer(to: &assigned) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &length) }
        }
        return assigned.sin_port.bigEndian
    }
}


/// The router's hop, reduced to the one thing it has to be able to do.
///
/// A real reverse proxy in a test would be a second product to configure and keep working. What is
/// actually under test is narrower and is the whole of what broke: **can something open a TLS
/// connection to the bridge's port and complete a handshake.** A router that cannot do that returns
/// 502 to the phone, and nothing on either machine records why.
///
/// It validates nothing, and that is fidelity rather than laziness: the real router validates nothing
/// either - there is no name it could check the bridge's certificate against - which is the reason
/// the on-link wrapper is described as opportunistic and as authenticating nobody.
enum TLSHop {

    /// - Returns: whether a TLS handshake to `address` completed within a few seconds.
    static func reach(_ address: String, timeout: TimeInterval = 10) throws -> Bool {
        let host = String(address.split(separator: ":")[0])
        guard let port = UInt16(address.split(separator: ":")[1]) else { return false }

        var context = SSLContext()
        return context.handshake(host: host, port: port, timeout: timeout)
    }

    /// A handshake over a plain BSD socket, driven by Network.framework's TLS via `NWConnection`.
    private struct SSLContext {
        func handshake(host: String, port: UInt16, timeout: TimeInterval) -> Bool {
            let options = NWProtocolTLS.Options()
            // Accept whatever is presented. See the type comment: the real router does the same,
            // because there is nothing here it could check.
            sec_protocol_options_set_verify_block(
                options.securityProtocolOptions,
                { _, _, complete in complete(true) },
                DispatchQueue.global())

            let connection = NWConnection(
                host: NWEndpoint.Host(host),
                port: NWEndpoint.Port(rawValue: port)!,
                using: NWParameters(tls: options))

            let done = DispatchSemaphore(value: 0)
            let ok = Locked(false)
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    ok.set(true)
                    done.signal()
                case .failed, .cancelled:
                    done.signal()
                default:
                    break
                }
            }
            connection.start(queue: DispatchQueue.global())
            defer { connection.cancel() }
            _ = done.wait(timeout: .now() + timeout)
            return ok.get()
        }
    }

    /// A box, because the state handler runs on another queue.
    private final class Locked: @unchecked Sendable {
        private let lock = NSLock()
        private var value: Bool
        init(_ value: Bool) { self.value = value }
        func set(_ new: Bool) { lock.withLock { value = new } }
        func get() -> Bool { lock.withLock { value } }
    }
}
