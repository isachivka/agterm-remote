import Foundation

/// One pairing window: the address, and the code built from it.
///
/// ### Why one window and not two menu items
///
/// **Pairing is one act**, and it was once modelled as two menu items that happen to sit next to each
/// other — set the address here, show the code there. A person doing this is holding a phone in one
/// hand and looking at the screen with the other eye; making them hunt for a second menu item between
/// those two motions is the program's file layout leaking out as somebody's workflow. The address and
/// the code it produces are one fact, so they are one screen.
///
/// ### Every state says something
///
/// There is no case here that renders an empty window. The rule that a thing which cannot do its job
/// must look dead rather than pressable applies to windows too: a blank rectangle is a mystery, not an
/// affordance.
public enum PairingWindowState: Equatable, Sendable {

    /// No address has been set. **The code cannot be built from nothing**, and this is the first run —
    /// the moment somebody is most likely to type the wrong thing, so it says what the value is rather
    /// than showing an empty box.
    case noAddress(explanation: String)

    /// There is an address, and the code could not be made. Carries what to tell them: `bridgecert`
    /// missing is a different sentence from `bridgecert` refusing.
    case codeUnavailable(address: String, explanation: String)

    /// The code exists and is on screen.
    case ready(address: String, imagePath: String, unproven: [String])

    /// What the window shows above everything else. Never empty.
    public var addressLine: String? {
        switch self {
        case .noAddress: nil
        case .codeUnavailable(let address, _), .ready(let address, _, _): address
        }
    }
}

/// Builds the window's state. Pure: the code is made by something injected, so every branch here is
/// reachable in a test without a `bridgecert` binary on the machine running it.
public enum PairingWindowModel {

    public static let noAddressExplanation =
        "No address is set yet. It is the name and port your PHONE will connect to — which is not the "
            + "address the bridge listens on. Set it, and the code will be built from it."

    /// - Parameters:
    ///   - address: what the stored preference yielded, or why it did not.
    ///   - status: the three facts, or nil before the first check.
    ///   - makeCode: runs `bridgecert qr` and returns where the PNG landed.
    public static func state(
        address: Result<DialAddress, AddressPreference.ReadFailure>,
        status: BridgeStatus?,
        makeCode: (DialAddress) -> Result<String, PairingCodeFailure>,
    ) -> PairingWindowState {
        guard case .success(let dial) = address else {
            return .noAddress(explanation: noAddressExplanation)
        }
        switch makeCode(dial) {
        case .success(let path):
            return .ready(
                address: dial.displayed,
                imagePath: path,
                unproven: PairingCodePanel(imagePath: path, address: dial, status: status).unproven,
            )
        case .failure(let why):
            return .codeUnavailable(address: dial.displayed, explanation: why.explanation)
        }
    }

    /// Saving a typed address, and **what the window must show the instant it is saved**.
    ///
    /// ### The stale-picture trap
    ///
    /// A code on screen built from an address that has since been changed underneath it is worse than
    /// no code: it is scannable, it pairs, and it points the phone at the previous destination. So the
    /// code is re-made from the new address as part of saving, in one step, rather than left to a
    /// refresh somebody has to remember. **There is no arrangement of this window in which the address
    /// shown and the code shown come from different values.**
    ///
    /// ### The three facts are re-asked, not carried over
    ///
    /// `status: nil` is passed deliberately. Whatever was known about the old address says nothing
    /// about this one — a new address is unproven until something answers on it and presents our
    /// certificate — so the panel goes back to *"This address has not been checked yet."* rather than
    /// inheriting a verdict it did not earn.
    ///
    /// - Parameter unchanged: what to keep showing when nothing was written. A refusal must not
    ///   disturb the code already on screen, because nothing about it became untrue.
    /// - Parameter confirmed: the owner's second act on a bare suffix. An unconfirmed one comes back
    ///   as `.needsConfirmation` with nothing written and nothing on screen disturbed, exactly like a
    ///   refusal — because until they answer, that is what it is.
    public static func afterSaving(
        address typed: String,
        using save: SaveAddress,
        makeCode: (DialAddress) -> Result<String, PairingCodeFailure>,
        unchanged: PairingWindowState,
        confirmed: Bool = false,
    ) -> (outcome: SaveAddress.Outcome, state: PairingWindowState) {
        let outcome = save.save(typed, confirmed: confirmed)
        guard case .saved(let address) = outcome else { return (outcome, unchanged) }
        return (outcome, state(address: .success(address), status: nil, makeCode: makeCode))
    }
}

/// Why a code could not be made, in words the owner can act on.
public enum PairingCodeFailure: Error, Equatable, Sendable {

    /// The command is not where the app expects it. This happens for real: a build that produces the
    /// bridge and not its certificate helper leaves the second one existing only as source, and the
    /// only symptom is a pairing window with no code in it.
    case noBinary(path: String)

    /// It ran and refused. Carries what it said, because "it didn't work" is not something anyone can
    /// act on at 2am.
    case refused(status: Int32, message: String)

    /// **Nothing in this build can make one**, which is a different sentence from a tool that is
    /// missing or a tool that refused.
    ///
    /// This app used to run a helper belonging to a separate project on the owner's machine. That
    /// reached outside its own state directory into a live installation the owner is still using, so
    /// it is gone. The panel that asks this app's own bridge for a code over its control socket is
    /// the next change; until it lands, the window says so rather than naming a path that this app
    /// has no business knowing.
    case notBuiltYet

    public var explanation: String {
        switch self {
        case .notBuiltYet:
            "This build cannot make a pairing code yet — the panel that asks the bridge for one is "
                + "not finished. The address above is saved either way, and nothing else on this "
                + "screen is affected."
        case .noBinary(let path):
            "The pairing-code tool is not installed at \(path). Nothing else on this screen is affected."
        case .refused(_, let message):
            "The pairing-code tool refused: \(message.isEmpty ? "it gave no reason." : message)"
        }
    }
}

/// Runs `bridgecert qr` and says where the PNG landed. **Called, never reimplemented** — the Go
/// command owns the payload, the certificate it embeds and the file's mode.
public struct PairingCodeMaker: Sendable {

    private let binary: String
    private let outputPath: String
    private let exists: @Sendable (String) -> Bool
    private let run: CommandRunner

    public init(
        binary: String,
        outputPath: String = PairingCodeCommand.outputPath(),
        exists: @escaping @Sendable (String) -> Bool = { FileManager.default.fileExists(atPath: $0) },
        run: @escaping CommandRunner,
    ) {
        self.binary = binary
        self.outputPath = outputPath
        self.exists = exists
        self.run = run
    }

    public func make(for address: DialAddress) -> Result<String, PairingCodeFailure> {
        // Checked before running, so a missing binary is its own sentence rather than a shell error
        // the owner has to interpret.
        guard exists(binary) else { return .failure(.noBinary(path: binary)) }

        let outcome = run(PairingCodeCommand.invocation(binary: binary, address: address))
        guard outcome.status == 0 else {
            return .failure(.refused(status: outcome.status, message: outcome.output))
        }
        // The command reports success by exiting zero; the window shows an image, so the file has to
        // be there. A missing file after a successful run is a refusal, not a blank window.
        guard exists(outputPath) else {
            return .failure(.refused(status: 0, message: "it reported success but wrote no image."))
        }
        return .success(outputPath)
    }
}
