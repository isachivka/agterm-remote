import Foundation
import Testing
@testable import AgtermRemoteCore

/// The code, and the string beside it that is the only part a human can check.
struct PairingCodePanelTests {

    private let address = DialAddress(host: "agterm.example-homelab.invalid", port: 8443)
    /// Not a `/Users/...` path: a committed home directory is refused by
    /// `scripts/check-no-addresses.sh`, and this test only needs a root to compose against.
    private let png = "/somewhere/Desktop/agterm-remote-pairing.png"

    private func status(_ identified: BridgeStatus.Identified,
                        running: BridgeStatus.Running = .running,
                        reachable: BridgeStatus.Reachable = .answered) -> BridgeStatus {
        BridgeStatus(address: address, running: running, reachable: reachable, identified: identified)
    }

    /// **Same formatter as commit 1, therefore the same rule as the phone's comparison screen.** If
    /// these two ever disagree the owner compares two renderings of one address, correctly, and gets
    /// the wrong answer.
    @Test func theAddressShownIsTheFormattersOutput() {
        let panel = PairingCodePanel(imagePath: png, address: address, status: status(.ours))

        #expect(panel.address == address.displayed)
        #expect(panel.address == "agterm.example-homelab.invalid:8443")
    }

    /// It is beside the code in the same value, so there is no state in which one is present and the
    /// other is a click away.
    @Test func theCodeAndTheAddressArriveTogether() {
        let panel = PairingCodePanel(imagePath: png, address: address, status: status(.ours))

        #expect(!panel.imagePath.isEmpty)
        #expect(!panel.address.isEmpty)
    }

    @Test func anAddressThatAnsweredAsUsCarriesNoWarning() {
        let panel = PairingCodePanel(imagePath: png, address: address, status: status(.ours))

        #expect(panel.unproven.isEmpty)
        #expect(!panel.carriesAWarning)
    }

    /// **Shown, not withheld.** They may be setting the address up before the router forwards it, and
    /// refusing the code would withhold the one thing they came to this window for.
    @Test func anUnprovenAddressStillGetsItsCode() {
        let panel = PairingCodePanel(
            imagePath: png, address: address,
            status: status(.notEstablished, running: .notRunning, reachable: .noAnswer("refused")))

        #expect(panel.imagePath == png, "the code was withheld")
        #expect(panel.address == "agterm.example-homelab.invalid:8443")
        #expect(panel.carriesAWarning)
    }

    /// The 2026-08-09 shape: something answers, it is not us, and the code is offered with that said
    /// on it rather than silently.
    @Test func aCodeForAnAddressThatIsNotUsSaysSoOnTheCode() {
        let panel = PairingCodePanel(
            imagePath: png, address: address, status: status(.notOurs(presented: "1111 2222")))

        #expect(panel.carriesAWarning)
        #expect(panel.unproven.contains { $0.contains("not this laptop") })
        #expect(panel.unproven.contains { $0.contains("agterm.example-homelab.invalid:8443") })
    }

    /// Before any check has run, that is what it says — not silence, which would read as approval.
    @Test func anUncheckedAddressSaysItIsUnchecked() {
        let panel = PairingCodePanel(imagePath: png, address: address, status: nil)

        #expect(panel.unproven == ["This address has not been checked yet."])
        #expect(panel.carriesAWarning)
    }
}

/// How the Go command is called. **Called, never reimplemented.**
struct PairingCodeCommandTests {

    private let address = DialAddress(host: "agterm.example-homelab.invalid", port: 8443)

    @Test func hostAndPortArePassedExplicitly() {
        let invocation = PairingCodeCommand.invocation(binary: "/opt/bin/bridgecert", address: address)

        #expect(invocation.executable == "/opt/bin/bridgecert")
        #expect(invocation.arguments == ["qr", "--host", "agterm.example-homelab.invalid", "--port", "8443"])
    }

    /// `bridgecert qr` has no defaults for these, deliberately: reusing the listen address produces a
    /// code that pairs and then cannot connect. They must never be inferred from anything.
    @Test func neitherHostNorPortIsEverOmitted() {
        let invocation = PairingCodeCommand.invocation(binary: "/opt/bin/bridgecert", address: address)

        #expect(invocation.arguments.contains("--host"))
        #expect(invocation.arguments.contains("--port"))
    }

    /// The PNG lands where `defaultQRName` puts it, and `--out` is not passed: one place for the file
    /// means a stale code cannot be lying around under another name.
    @Test func theOutputPathMatchesTheGoSidesDefault() {
        let path = PairingCodeCommand.outputPath(home: URL(filePath: "/somewhere"))

        #expect(path == "/somewhere/Desktop/agterm-remote-pairing.png")
        #expect(!PairingCodeCommand.invocation(binary: "b", address: address).arguments.contains("--out"))
    }

    /// No QR is drawn here. A second encoder of a wire format the Go side owns would drift, and the
    /// first thing it would do is disagree about the payload.
    @Test func nothingHereEncodesAPayload() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let source = try String(
            contentsOf: root.appending(path: "Sources/AgtermRemoteCore/PairingCodePanel.swift"),
            encoding: .utf8)

        for encoder in ["CIQRCodeGenerator", "base64Encoded", "CoreImage", "correctionLevel"] {
            #expect(!NoAggregateVerdictTests.stripped(source).contains(encoder),
                    "the app is encoding a payload the Go side owns (\(encoder))")
        }
    }
}
