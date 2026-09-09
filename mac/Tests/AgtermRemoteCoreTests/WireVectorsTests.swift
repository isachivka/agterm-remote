import Foundation
import Testing

@testable import AgtermRemoteCore

/// The third reader, held to the same file as the other two.
///
/// ### Why this exists
///
/// `wire/enroll-payload-vectors.json` is the contract between hand-written implementations of one
/// format that cannot be compiled against each other. Go and Kotlin have always been held to it. The
/// Swift reader in `BridgeIntegrationTests` was not, and it drifted the moment version 2 landed:
/// it went on reading version 1's offsets, every suite stayed green, and a person noticed afterwards.
///
/// A reader that decodes only payloads minted seconds earlier by the encoder under test proves that
/// the encoder agrees with itself. Reading the file is what makes it a second opinion.
///
/// It cannot simply be deleted — the integration tests decode freshly minted codes and have to
/// understand them — so it is held to the contract instead.
struct WireVectorsTests {

    private struct Vector: Decodable {
        let name: String
        let version: Int
        let host: String
        let port: Int
        let scheme: Int
        let fingerprintHex: String
        let expiryUnix: Int64
        let text: String
        let dialAddress: String
        let dialURL: String

        enum CodingKeys: String, CodingKey {
            case name, version, host, port, scheme, text
            case fingerprintHex = "fingerprint_hex"
            case expiryUnix = "expiry_unix"
            case dialAddress = "dial_address"
            case dialURL = "dial_url"
        }
    }

    /// The repository root, from this file's own path. The same trick `BoundaryTests` uses, and the
    /// reason the guard greps for the filename: **read from the root, never from a copy.** A copy in
    /// `mac/` would be what this suite pins while Go pins the original, both green, pinning different
    /// bytes.
    private static func vectors() throws -> [Vector] {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent()
        let file = root.appending(path: "wire/enroll-payload-vectors.json")
        return try JSONDecoder().decode([Vector].self, from: Data(contentsOf: file))
    }

    /// **Every case, and the count is asserted.**
    ///
    /// A loop over an empty array passes. So does a loop over a file that lost half its cases, which
    /// is the shape a bad regeneration takes.
    @Test func everyVectorDecodesToWhatItNames() throws {
        let vectors = try Self.vectors()
        #expect(vectors.count == 7, "the vector set changed size; this reader must cover all of it")

        for v in vectors {
            let got = try EnrolmentPayload(base64: v.text)
            #expect(Int(got.version) == v.version, "\(v.name): version")
            #expect(Int(got.scheme) == v.scheme, "\(v.name): scheme")
            #expect(got.host == v.host, "\(v.name): host")
            #expect(got.port == v.port, "\(v.name): port")
            #expect(got.fingerprint == v.fingerprintHex, "\(v.name): fingerprint")
            #expect(Int64(got.expiry) == v.expiryUnix, "\(v.name): expiry")
        }
    }

    /// **Both layouts, and the set has to carry both.**
    ///
    /// This is the assertion that would have gone red the moment version 2 landed on a reader still
    /// reading version 1's offsets — which is exactly what happened, with nothing to say so.
    @Test func bothVersionsAreCoveredAndVersionOneMeansPlain() throws {
        let vectors = try Self.vectors()
        let versions = Set(vectors.map(\.version))
        #expect(versions == [1, 2], "the set must carry both layouts, found \(versions.sorted())")

        for v in vectors where v.version == 1 {
            let got = try EnrolmentPayload(base64: v.text)
            #expect(got.scheme == 1, "\(v.name): a version 1 code means a plain connection")
        }
        #expect(
            Set(vectors.filter { $0.version == 2 }.map(\.scheme)) == [1, 2],
            "the version 2 vectors must exercise both schemes or the field is untested")
    }

    /// The derived fields, which are not in the bytes. This reader has no dialler of its own, so what
    /// it can honestly check is that the address it decoded is the one the derived strings are built
    /// from — which is what catches a host or a port read from the wrong offset.
    @Test func theDecodedAddressAgreesWithTheDerivedFields() throws {
        for v in try Self.vectors() {
            let got = try EnrolmentPayload(base64: v.text)
            let bracketed = got.host.contains(":") ? "[\(got.host)]" : got.host
            #expect("\(bracketed):\(got.port)" == v.dialAddress, "\(v.name): dial_address")
            let scheme = got.scheme == 2 ? "wss" : "ws"
            #expect("\(scheme)://\(bracketed):\(got.port)/" == v.dialURL, "\(v.name): dial_url")
        }
    }

    /// **A lenient third reader is worse than no third reader**, because it agrees with everything and
    /// therefore pins nothing. This one used to accept all four of these.
    ///
    /// The reject vectors are not read here: they are the contract for a decoder facing a camera, and
    /// this reader faces a control socket on the same machine. What it owes is that it is not a
    /// pushover, and these are the four ways it was.
    @Test func itRefusesWhatTheFormatSaysIsNotAPayload() throws {
        let valid = try #require(try Self.vectors().first { $0.version == 2 })
        var raw = [UInt8](try #require(Data(base64Encoded: valid.text)))

        #expect(throws: (any Error).self) { try EnrolmentPayload(base64: "not base64 at all!") }
        #expect(throws: (any Error).self) { try EnrolmentPayload(base64: "") }

        var unknownVersion = raw
        unknownVersion[0] = 9
        #expect(throws: (any Error).self) {
            try EnrolmentPayload(base64: Data(unknownVersion).base64EncodedString())
        }

        var unknownScheme = raw
        unknownScheme[1] = 9
        #expect(throws: (any Error).self) {
            try EnrolmentPayload(base64: Data(unknownScheme).base64EncodedString())
        }

        raw.append(0)
        #expect(
            throws: (any Error).self,
            "a trailing byte is the tail of a second message, not slack"
        ) { try EnrolmentPayload(base64: Data(raw).base64EncodedString()) }
    }
}
