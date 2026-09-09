import Foundation

/// Showing the pairing code, with the address it was built from beside it.
///
/// ### The code is opaque; the string is the only part a human can check
///
/// `bridgecert qr` reads this laptop's existing identity rather than minting one, so **every code it
/// generates shows the same fingerprint** — comparing fingerprints proves the code came from this
/// laptop and says nothing about where the phone will dial. On 2026-08-09 that produced two codes that
/// paired perfectly and then never connected. The host and port are the only fields that discriminate,
/// so they are on screen **at the same moment as the code, with no click and no hover**.
///
/// ### A code built from an unproven address is shown, not withheld
///
/// Somebody may legitimately be setting the address up before the router forwards it. Refusing to
/// show the code until all three facts hold would withhold the one thing they came for on the grounds
/// of a check they are in the middle of satisfying. So it is shown **with the state of the three facts
/// stated on it.**
public struct PairingCodePanel: Equatable, Sendable {

    /// Where `bridgecert qr` put the PNG. This type never draws a QR: rendering one here would be a
    /// second implementation of a payload the Go side already owns, and the two would drift.
    public let imagePath: String

    /// The address the code encodes, from the same formatter the phone's comparison screen uses.
    public let address: String

    /// What is not yet established about that address, in the owner's words. Empty when all three
    /// facts hold. **Never a reason to hide the code.**
    public let unproven: [String]

    public init(imagePath: String, address: DialAddress, status: BridgeStatus?) {
        self.imagePath = imagePath
        self.address = address.displayed
        self.unproven = switch status {
        case .none:
            ["This address has not been checked yet."]
        case .some(let status):
            status.problems
        }
    }

    /// True when the code is being shown for an address that has not answered as us. The panel still
    /// shows the code; this is what puts the sentence on it.
    public var carriesAWarning: Bool { !unproven.isEmpty }
}

/// How `bridgecert qr` is asked for a code. **It is called, never reimplemented.**
///
/// The Go command already owns the payload format, the certificate it embeds, the file mode and the
/// fingerprint it prints. A Swift reimplementation would be a second encoder of a wire format, and
/// the first thing it would do is disagree.
public struct PairingCodeCommand: Equatable, Sendable {

    /// The command takes `--host` and `--port` with **no defaults, deliberately**: reusing the listen
    /// address produces a code that pairs and then cannot connect. The app passes both explicitly,
    /// from the stored address, and has no other address to pass.
    public static func invocation(binary: String, address: DialAddress) -> CommandInvocation {
        CommandInvocation(
            executable: binary,
            arguments: ["qr", "--host", address.host, "--port", String(address.port)],
        )
    }

    /// Where the PNG lands, matching the default the Go command writes to. `--out` is not passed: one
    /// place for the file means a stale code cannot be lying around under another name, which is why
    /// the Go side overwrites rather than versions it.
    public static func outputPath(home: URL = FileManager.default.homeDirectoryForCurrentUser) -> String {
        home.appending(path: "Desktop/agterm-remote-pairing.png").path
    }
}
