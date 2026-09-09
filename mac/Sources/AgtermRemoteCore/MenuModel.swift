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
    /// Show a code and wait for a phone to walk through it. Named for the act rather than for the
    /// picture: what the owner is doing is pairing a phone, and the code is how.
    case pairPhone
    /// Drop the paired phone. **Offered only when there is one**, which is the one item in this menu
    /// whose presence — not merely whose enabled state — depends on what the bridge reports.
    case unpair
    /// The address, the arrival port, and everything else that has to be true before a phone can
    /// connect. It was called *Set the address…* and opened the pairing window with the cursor in a
    /// box; the address now lives in the setup window with the explanation of what it is for, so the
    /// item is named for where it goes.
    case setUp
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
/// unresolved**, and the menu underneath says what it knows regardless. The distinction matters
/// because a single green dot is exactly what would have shown green for the bare dynamic-DNS suffix.
///
/// ### Three of these five have no measurement behind them, and that is now stated rather than faked
///
/// `nothingAnswered` and `answeredBySomethingElse` are about whether the configured address answers
/// and whether what answers is this laptop. **Nothing in this app has ever established either.** They
/// were derived from a `BridgeStatus` the menu-bar app built at its own call site out of a
/// placeholder address and the words `not checked`, and that type — with its assessment, its probe
/// protocol and its handshake outcomes — has been retired rather than left standing as a vocabulary
/// for a probe nobody has scheduled. The next thing that would have happened to it is a second fake
/// caller.
///
/// The cases stay because [Mark] draws five distinct shapes and `MarkTests` holds them apart; when a
/// reachability probe is built, this is the vocabulary it reports into, and it will report measured
/// facts. Until then the app uses `notChecked`, `bridgeNotRunning` and `allThreeHold`, which are the
/// three it can actually answer.
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

    /// The menu for a given moment.
    ///
    /// ### `paired` replaced a status this app was inventing
    ///
    /// The previous signature took a `BridgeStatus` — three facts about whether the configured address
    /// answers and whether what answered is us. **Nothing ever established those facts.** The app
    /// handed this function a `BridgeStatus` built at the call site out of a placeholder address, `not
    /// checked` and `notEstablished`, on every rebuild; the three-way verdict was a shape with no
    /// measurement behind it. The bridge's control socket reports what is actually true — which
    /// phones are paired — so that is what the menu is drawn from now, and the facts nothing can
    /// establish are not drawn at all.
    ///
    /// - Parameters:
    ///   - paired: the phones the bridge holds, from `status`. Empty is the ordinary first state.
    ///   - hasAddress: false on first run, when nothing is stored yet.
    ///   - bridge: what the supervisor says about its child. The supervisor's own state rather than a
    ///     three-way translation of it: `.starting` and `.stopping` are both windows in which some of
    ///     these items must not be pressable, and a translation that flattened either was how a frozen
    ///     bridge came to be described as Running.
    ///   - implemented: the actions that actually do something in this build. **An action outside this
    ///     set is never enabled**, whatever the state says — that is the structural version of the
    ///     rule rather than the remembered one. The menu derives its handlers from the same set, so a
    ///     pressable item and a working item cannot come apart.
    public static func items(
        paired: [PairedPhone],
        hasAddress: Bool,
        launchesAtLogin: Bool,
        bridge: BridgeState = .stopped,
        implemented: Set<MenuAction> = Set(MenuAction.allCases),
    ) -> [MenuItem] {
        stateItems(paired: paired, hasAddress: hasAddress, launchesAtLogin: launchesAtLogin, bridge: bridge)
            .map {
                // `&&`, never `=`: an unimplemented action cannot be argued back into being pressable
                // by a state that thinks it should be.
                MenuItem(action: $0.action, title: $0.title, enabled: $0.enabled && implemented.contains($0.action))
            }
    }

    /// What the *state* alone says. Never used directly by the menu — [items] narrows it by what is
    /// actually wired.
    private static func stateItems(
        paired: [PairedPhone], hasAddress: Bool, launchesAtLogin: Bool, bridge: BridgeState,
    ) -> [MenuItem] {
        // **Three questions, and two of them were one bool.** Whether the bridge is up decides the
        // glyph; whether something is up OR on its way decides whether Stop can be pressed; and
        // whether a stop is already in flight decides whether ANYTHING about the bridge can be. That
        // last one arrived with `.stopping`: the kill escalates and can take twice the grace period,
        // and during it a second Stop signals a pid that is already being killed while a Start races
        // an exit that has not landed.
        let inFlight = bridge != .stopped && !bridge.hasFailed
        let stopping = bridge == .stopping

        var items: [MenuItem] = [
            // Needs an address to encode. Offering it without one would mint a code for nothing.
            MenuItem(action: .pairPhone, title: "Pair a phone…", enabled: hasAddress),
        ]
        // **Present only when there is a phone to drop.** Not "present and disabled": an item naming a
        // phone that does not exist is the 2026-08-10 defect wearing a politer face, and the name is
        // the whole content of this item — "Unpair" alone asks somebody to destroy something they
        // cannot see.
        if let phone = paired.last {
            items.append(MenuItem(action: .unpair, title: "Unpair \(Self.describe(phone))", enabled: true))
        }
        items += [
            // ALWAYS available: it is the only way out of first run, and the only way to correct a
            // wrong address - which is the failure this whole app exists to make visible.
            MenuItem(action: .setUp, title: "Set up…", enabled: true),
            MenuItem(action: .startBridge, title: "Start the bridge", enabled: !inFlight),
            MenuItem(
                action: .stopBridge,
                // The title says which of the two it is. A greyed *Stop the bridge* during a stop
                // reads as an app that has hung; the word is what says the machine is part-way
                // through what was asked of it.
                title: stopping ? "Stopping the bridge…" : "Stop the bridge",
                enabled: inFlight && !stopping,
            ),
            // Enabled even when it is down, because "restart" on a stopped bridge is "start it", and a
            // pin without a restart pins nothing - main.go reads phone-cert.pem once at startup. Not
            // during a stop, for the same reason Start is not: the child is on its way out.
            MenuItem(action: .restartBridge, title: "Restart the bridge", enabled: !stopping),
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
        return items
    }

    /// How a phone is named in the menu. The name the phone gave, and its fingerprint when it gave no
    /// name — **never an empty pair of quotes**, which is what an unnamed phone rendered as before
    /// this existed.
    public static func describe(_ phone: PairedPhone) -> String {
        let name = phone.name.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? phone.fingerprint : name
    }
}
