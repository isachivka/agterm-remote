import Foundation
import Testing
@testable import AgtermRemoteCore

/// The one pairing window, and the rule that no state of it is blank.
struct PairingWindowModelTests {

    private let dial = DialAddress(host: "agterm.example-homelab.invalid", port: 8443)
    private let png = "/somewhere/Desktop/agterm-remote-pairing.png"

    private func ok(_ status: BridgeStatus.Identified = .ours) -> BridgeStatus {
        BridgeStatus(address: dial, running: .running, reachable: .answered, identified: status)
    }

    @Test func theCodeAndTheAddressArriveTogether() {
        let state = PairingWindowModel.state(address: .success(dial), status: ok()) { _ in .success(png) }

        #expect(state == .ready(address: "agterm.example-homelab.invalid:8443", imagePath: png, unproven: []))
        #expect(state.addressLine == "agterm.example-homelab.invalid:8443")
    }

    /// **The address is the formatter's output**, the same rule the phone's comparison screen uses.
    /// If these disagree the owner compares two renderings of one address and gets the wrong answer.
    @Test func theAddressIsTheSharedFormattersOutput() {
        let state = PairingWindowModel.state(address: .success(dial), status: ok()) { _ in .success(png) }

        #expect(state.addressLine == dial.displayed)
    }

    /// First run. **Not an empty box** — it says what the value is and, in the sentence that has cost
    /// this project two afternoons, that it is not the address the bridge listens on.
    @Test func noAddressExplainsItselfRatherThanShowingNothing() {
        let state = PairingWindowModel.state(address: .failure(.unset), status: nil) { _ in .success(png) }

        guard case .noAddress(let explanation) = state else { return #expect(Bool(false), "wrong state") }
        #expect(explanation.contains("not the address the bridge listens on"))
        #expect(!explanation.isEmpty)
    }

    @Test func aMalformedAddressIsTheSameAsNoneForThisWindow() {
        let state = PairingWindowModel.state(address: .failure(.malformed), status: nil) { _ in .success(png) }

        guard case .noAddress = state else { return #expect(Bool(false), "wrong state") }
    }

    /// A missing tool is its own sentence, and it names the path so the owner can see what was looked
    /// for. This happens for real: the helper is built nowhere the app can find it.
    @Test func aMissingToolSaysSoAndKeepsTheAddressOnScreen() {
        let state = PairingWindowModel.state(address: .success(dial), status: ok()) { _ in
            .failure(.noBinary(path: "/nowhere/bridgecert"))
        }

        guard case .codeUnavailable(let address, let explanation) = state else {
            return #expect(Bool(false), "wrong state")
        }
        #expect(address == "agterm.example-homelab.invalid:8443", "the address must survive a failed code")
        #expect(explanation.contains("/nowhere/bridgecert"))
    }

    /// A refusal carries what the tool said. "It didn't work" is not something anyone can act on.
    @Test func aRefusalRepeatsWhatTheToolSaid() {
        let state = PairingWindowModel.state(address: .success(dial), status: ok()) { _ in
            .failure(.refused(status: 1, message: "no bridge certificate in that directory"))
        }

        guard case .codeUnavailable(_, let explanation) = state else {
            return #expect(Bool(false), "wrong state")
        }
        #expect(explanation.contains("no bridge certificate"))
    }

    /// **The code is shown even when the address has not proved itself**, with the state said on it.
    /// Withholding it would leave them with nothing to scan and no way to tell why.
    @Test func anUnprovenAddressStillGetsItsCodeWithTheWarningOnIt() {
        let state = PairingWindowModel.state(
            address: .success(dial), status: ok(.notOurs(presented: "1111 2222"))) { _ in .success(png) }

        guard case .ready(_, let path, let unproven) = state else { return #expect(Bool(false), "wrong state") }
        #expect(path == png)
        #expect(unproven.contains { $0.contains("not this laptop") })
    }

    @Test func beforeAnyCheckTheWindowSaysTheAddressIsUnchecked() {
        let state = PairingWindowModel.state(address: .success(dial), status: nil) { _ in .success(png) }

        guard case .ready(_, _, let unproven) = state else { return #expect(Bool(false), "wrong state") }
        #expect(unproven == ["This address has not been checked yet."])
    }
}

/// Running `bridgecert qr`, and the three ways it can fail to produce a code.
struct PairingCodeMakerTests {

    private let dial = DialAddress(host: "agterm.example-homelab.invalid", port: 8443)

    private func maker(
        binaryExists: Bool = true,
        imageExists: Bool = true,
        status: Int32 = 0,
        output: String = "",
        record: (@Sendable (CommandInvocation) -> Void)? = nil,
    ) -> PairingCodeMaker {
        PairingCodeMaker(
            binary: "/opt/bin/bridgecert",
            outputPath: "/tmp/code.png",
            exists: { $0 == "/opt/bin/bridgecert" ? binaryExists : imageExists },
            run: { invocation in record?(invocation); return (status, output) },
        )
    }

    @Test func itRunsQrWithTheHostAndPortAndReturnsTheImagePath() {
        final class Seen: @unchecked Sendable { var argv: [String] = [] }
        let seen = Seen()

        let result = maker(record: { seen.argv = $0.arguments }).make(for: dial)

        #expect(result == .success("/tmp/code.png"))
        #expect(seen.argv == ["qr", "--host", "agterm.example-homelab.invalid", "--port", "8443"])
    }

    /// Checked before running, so a missing tool is its own sentence rather than a shell error the
    /// owner has to interpret.
    @Test func aMissingBinaryIsReportedWithoutRunningAnything() {
        final class Ran: @unchecked Sendable { var count = 0 }
        let ran = Ran()

        let result = maker(binaryExists: false, record: { _ in ran.count += 1 }).make(for: dial)

        #expect(result == .failure(.noBinary(path: "/opt/bin/bridgecert")))
        #expect(ran.count == 0, "it ran a binary it had already decided was missing")
    }

    @Test func aNonZeroExitCarriesTheStatusAndTheMessage() {
        let result = maker(status: 2, output: "--host is required").make(for: dial)

        #expect(result == .failure(.refused(status: 2, message: "--host is required")))
    }

    /// **Exit zero and no file is a refusal, not a blank window.** The window shows an image; if the
    /// image is not there, saying nothing would be the empty rectangle this design forbids.
    @Test func successWithNoImageIsTreatedAsAFailure() {
        let result = maker(imageExists: false).make(for: dial)

        guard case .failure(.refused(_, let message)) = result else {
            return #expect(Bool(false), "a missing image passed as success")
        }
        #expect(message.contains("wrote no image"))
    }
}
