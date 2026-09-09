import Foundation

/// What the menu bar shows, as data, so the thing that matters about it can be tested without a Mac.
///
/// ### Why this is a value and not a pile of `NSMenuItem`s
///
/// On 2026-08-09 the phone shipped a viewfinder whose button could not be reached: two states were
/// collapsed after their justification expired, and **590 unit tests were green while the feature was
/// unreachable on every phone in existence**. Every assertion described the states correctly. Nothing
/// described whether the screen those states rendered could be got to.
///
/// A menu has the identical hazard — an item disabled in every state it can be in is a feature that
/// does not exist — so the menu is a value that [MenuReachabilityTests] can enumerate.
public enum MenuAction: String, CaseIterable, Sendable {
    case showPairingCode
    case setAddress
    case startBridge
    case stopBridge
    case restartBridge
    case startAtLogin
    case quit
}

public struct MenuItem: Equatable, Sendable {
    public let action: MenuAction
    public let title: String
    public let enabled: Bool
}

/// The one glyph in the menu bar.
///
/// **This is not an aggregate verdict.** It never says "ok" or "not ok"; it names **which fact is
/// unresolved**, and the menu underneath always lists all three regardless. The distinction matters
/// because a single green dot is exactly what would have shown green for the bare dynamic-DNS suffix.
public enum IconState: String, CaseIterable, Sendable {
    case notChecked
    case bridgeNotRunning
    case nothingAnswered
    case answeredBySomethingElse
    case allThreeHold

    /// The drawing is in `Mark`: three solid squares that never change, and a fourth cell that says
    /// which fact is unresolved. **Five shapes, never five colours** — the mark is a template image
    /// and has no colour to give. `MarkTests` fails if two states ever render the same pixels.

    /// What a screen reader says, and what the tooltip shows. Never a colour word.
    public var describedAsWords: String {
        switch self {
        case .notChecked: "Not checked yet"
        case .bridgeNotRunning: "The bridge is not running"
        case .nothingAnswered: "Nothing answered at the configured address"
        case .answeredBySomethingElse: "Something answered, but it is not this laptop"
        case .allThreeHold: "Running, reachable, and this laptop"
        }
    }
}

public struct MenuModel: Sendable {

    static let startLaunching = "Start at login"
    static let stopLaunching = "Don't start at login"

    /// Which fact is unresolved, in the order that makes the next action obvious. **Not a severity
    /// ranking and not a summary** — the menu shows all three either way.
    public static func icon(for status: BridgeStatus?) -> IconState {
        guard let status else { return .notChecked }
        if case .notOurs = status.identified { return .answeredBySomethingElse }
        if case .noAnswer = status.reachable { return .nothingAnswered }
        if status.running == .notRunning { return .bridgeNotRunning }
        if status.identified == .notEstablished { return .nothingAnswered }
        return .allThreeHold
    }

    /// The menu for a given moment.
    ///
    /// `status` is nil before the first check. `hasAddress` is false on first run, when `dial.txt`
    /// does not exist yet.
    /// - Parameter implemented: the actions that actually do something in this build. **An action
    ///   outside this set is never enabled**, whatever the state says — that is the structural version
    ///   of the rule rather than the remembered one. The menu derives its handlers from the same set,
    ///   so a pressable item and a working item cannot come apart.
    public static func items(
        status: BridgeStatus?,
        hasAddress: Bool,
        launchesAtLogin: Bool,
        implemented: Set<MenuAction> = Set(MenuAction.allCases),
    ) -> [MenuItem] {
        stateItems(status: status, hasAddress: hasAddress, launchesAtLogin: launchesAtLogin).map {
            // `&&`, never `=`: an unimplemented action cannot be argued back into being pressable by
            // a state that thinks it should be.
            MenuItem(action: $0.action, title: $0.title, enabled: $0.enabled && implemented.contains($0.action))
        }
    }

    /// What the *state* alone says. Never used directly by the menu — [items] narrows it by what is
    /// actually wired.
    private static func stateItems(status: BridgeStatus?, hasAddress: Bool, launchesAtLogin: Bool) -> [MenuItem] {
        let running = status?.running == .running
        return [
            // Needs an address to encode. Offering it without one would mint a code for nothing.
            MenuItem(action: .showPairingCode, title: "Show the pairing code…", enabled: hasAddress),
            // ALWAYS available: it is the only way out of first run, and the only way to correct a
            // wrong address - which is the failure this whole app exists to make visible.
            MenuItem(action: .setAddress, title: "Set the address…", enabled: true),
            MenuItem(action: .startBridge, title: "Start the bridge", enabled: !running),
            MenuItem(action: .stopBridge, title: "Stop the bridge", enabled: running),
            // Enabled even when it is down, because "restart" on a stopped bridge is "start it", and a
            // pin without a restart pins nothing - main.go reads phone-cert.pem once at startup.
            MenuItem(action: .restartBridge, title: "Restart the bridge", enabled: true),
            // The two titles are named constants rather than a ternary of string literals, and that
            // is not style. Written inline, the second literal would sit immediately after a colon
            // that follows the word this menu item is about - and scripts/check-no-credentials.sh
            // reads a keyword, a colon and a quoted string as a committed service login. Reshaping
            // one menu title is cheaper than loosening a check that guards passwords, and the check
            // is right to be blunt: it scans comments too, because a credential in a comment is
            // still committed. (Which is why this note describes the shape instead of quoting it.)
            MenuItem(
                action: .startAtLogin,
                title: launchesAtLogin ? Self.stopLaunching : Self.startLaunching,
                enabled: true,
            ),
            // Quitting is quitting. Always enabled, never conditional, and the handler does not
            // relaunch or ask.
            MenuItem(action: .quit, title: "Quit", enabled: true),
        ]
    }
}
