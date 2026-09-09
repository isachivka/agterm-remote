import Foundation
import Testing
@testable import AgtermRemoteCore

/// Two properties this source must keep, checked by reading the source rather than by trusting it.
///
/// The same shape as `NothingPersistedTest` on the Android side, control included: **a check that
/// cannot fail is not a check**, so each test plants a violation and asserts the detector sees it.
struct NoAggregateVerdictTests {

    private func sources() throws -> [(name: String, text: String)] {
        // #filePath is this file; the package root is three directories up from Tests/<target>/.
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let directory = root.appending(path: "Sources/AgtermRemoteCore")
        let names = try FileManager.default.contentsOfDirectory(atPath: directory.path)
            .filter { $0.hasSuffix(".swift") }
        #expect(!names.isEmpty, "found no sources to check — the detector is looking in the wrong place")
        return try names.map {
            ($0, Self.stripped(try String(contentsOf: directory.appending(path: $0), encoding: .utf8)))
        }
    }

    /// Comments are removed before scanning, because **the sources' own documentation discusses the
    /// things this forbids** — the doc comment on `IconState` explains why there is no aggregate,
    /// and `AddressPreference`'s explains why the bridge's own configuration is left alone. Scanning
    /// prose finds the explanation and calls it the violation, which is a detector that fires on its
    /// own reasoning.
    ///
    /// The control below plants a violation in CODE and proves stripping does not hide it.
    /// Shared with `BoundaryTests`, which greps the same sources for a different property.
    static func stripped(_ source: String) -> String {
        var out = ""
        var inBlock = false
        for line in source.split(separator: "\n", omittingEmptySubsequences: false) {
            var text = String(line)
            if inBlock {
                guard let end = text.range(of: "*/") else { continue }
                text = String(text[end.upperBound...])
                inBlock = false
            }
            while let start = text.range(of: "/*") {
                if let end = text.range(of: "*/", range: start.upperBound..<text.endIndex) {
                    text = String(text[..<start.lowerBound]) + String(text[end.upperBound...])
                } else {
                    text = String(text[..<start.lowerBound])
                    inBlock = true
                }
            }
            if let comment = text.range(of: "//") {
                text = String(text[..<comment.lowerBound])
            }
            out += text + "\n"
        }
        return out
    }

    /// Anything that reduces the three facts to one answer. **The moment a single green dot exists,
    /// this app stops being able to catch the mistake it was built for**: the bare suffix answers, so
    /// two facts out of three would light it.
    private static let aggregates = [
        "isHealthy", "isOk", "isOK", "isGood", "isUp", "isFine", "allGood", "isWorking",
        "isConnected", "everythingOk", "overallStatus", "healthy:", "var ok",
    ]

    @Test func noAggregateVerdictIsDerivedFromTheThreeFacts() throws {
        for source in try sources() {
            for aggregate in Self.aggregates {
                #expect(
                    !source.text.contains(aggregate),
                    "\(source.name) derives an aggregate verdict (\(aggregate)) from the three facts",
                )
            }
        }
    }

    /// The control: a violation in code survives stripping and is seen, while the same words in a
    /// comment do not fire. Both halves matter — a detector that fires on prose gets deleted by the
    /// next person, and one that misses code was never a check.
    @Test func theAggregateDetectorSeesCodeAndIgnoresComments() {
        let planted = Self.stripped("public var isHealthy: Bool { running == .running }")
        let discussed = Self.stripped("/// There is deliberately no isHealthy here, and this says why.")

        #expect(Self.aggregates.contains { planted.contains($0) }, "the detector would miss a real one")
        #expect(!Self.aggregates.contains { discussed.contains($0) }, "the detector fires on its own prose")
    }

    /// **The listen address must be unreachable from here, not merely unused.** Reusing it for the
    /// dial address is what produced a QR that paired and then never connected; this module has no
    /// way to learn it, and that is the property worth keeping.
    private static let listenSources = ["config.json", "\"listen\"", "lan_cert", "lan_key", "8444"]

    @Test func nothingHereCanLearnTheListenAddress() throws {
        for source in try sources() {
            for reference in Self.listenSources {
                let complaint = "\(source.name) reaches for the bridge's own configuration "
                    + "(\(reference)), which is where a fallback to the listen address would come from"
                #expect(!source.text.contains(reference), "\(complaint)")
            }
        }
    }

    @Test func theListenAddressDetectorSeesCodeAndIgnoresComments() {
        let planted = Self.stripped(#"let fallback = try Data(contentsOf: dir.appending(path: "config.json"))"#)
        let discussed = Self.stripped("// config.json is deliberately not read here, and this says why.")

        #expect(Self.listenSources.contains { planted.contains($0) }, "the detector would miss a real one")
        #expect(!Self.listenSources.contains { discussed.contains($0) }, "the detector fires on its own prose")
    }
}
