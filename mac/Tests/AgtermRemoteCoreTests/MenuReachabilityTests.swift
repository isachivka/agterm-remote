import Foundation
import Testing
@testable import AgtermRemoteCore

/// **The direct descendant of 2026-08-09's deadlock, and the assertion that was missing that morning.**
///
/// The phone hid its scan button until the camera permission was held, and the permission was
/// requested from that button. Every unit test about the state machine passed. None of them asked
/// whether the screen those states rendered could be got to, because a state machine cannot see its
/// own reachability.
///
/// So: enumerate every state this menu can be in, and assert every action is enabled in at least one
/// of them. An action disabled everywhere is a feature that does not exist.
struct MenuReachabilityTests {

    /// Every combination the menu can actually be shown in.
    private static func everyState() -> [(status: BridgeStatus?, hasAddress: Bool, atLogin: Bool)] {
        let address = DialAddress(host: "agterm.example-homelab.invalid", port: 8443)
        let statuses: [BridgeStatus?] = [
            nil,
            BridgeStatus(address: address, running: .running, reachable: .answered, identified: .ours),
            BridgeStatus(address: address, running: .notRunning, reachable: .answered, identified: .ours),
            BridgeStatus(address: address, running: .running, reachable: .noAnswer("refused"),
                         identified: .notEstablished),
            BridgeStatus(address: address, running: .notRunning, reachable: .noAnswer("refused"),
                         identified: .notEstablished),
            BridgeStatus(address: address, running: .running, reachable: .answered,
                         identified: .notOurs(presented: "1111 2222")),
            BridgeStatus(address: address, running: .running, reachable: .answered,
                         identified: .notEstablished),
        ]
        var states: [(BridgeStatus?, Bool, Bool)] = []
        for status in statuses {
            for hasAddress in [true, false] {
                for atLogin in [true, false] {
                    states.append((status, hasAddress, atLogin))
                }
            }
        }
        return states
    }

    private static func enabledSomewhere(
        _ menu: (BridgeStatus?, Bool, Bool) -> [MenuItem],
    ) -> Set<MenuAction> {
        var reachable: Set<MenuAction> = []
        for state in everyState() {
            for item in menu(state.status, state.hasAddress, state.atLogin) where item.enabled {
                reachable.insert(item.action)
            }
        }
        return reachable
    }

    /// Still worth keeping, and now honest about what it does NOT prove: it asks whether an action is
    /// **reachable**, never whether it **does anything**. `MenuActionsTests` is the half that asks
    /// that, and it exists because this test passed on a menu of five inert items that shipped.
    @Test func everyActionIsEnabledInAtLeastOneReachableState() {
        let reachable = Self.enabledSomewhere { status, hasAddress, atLogin in
            MenuModel.items(status: status, hasAddress: hasAddress, launchesAtLogin: atLogin)
        }

        let unreachable = Set(MenuAction.allCases).subtracting(reachable)
        #expect(unreachable.isEmpty, "disabled in every state, so they do not exist: \(unreachable)")
    }

    /// **The control.** A check that cannot fail is not a check: this plants an action disabled
    /// everywhere and proves the detector above would have caught it.
    @Test func theReachabilityDetectorCatchesAnActionDisabledEverywhere() {
        let crippled: (BridgeStatus?, Bool, Bool) -> [MenuItem] = { status, hasAddress, atLogin in
            MenuModel.items(status: status, hasAddress: hasAddress, launchesAtLogin: atLogin).map {
                $0.action == .showPairingCode
                    ? MenuItem(action: $0.action, title: $0.title, enabled: false)
                    : $0
            }
        }

        let reachable = Self.enabledSomewhere(crippled)

        #expect(!reachable.contains(.showPairingCode), "the detector would have missed today's deadlock")
    }

    /// The menu is never empty and never loses an action: a state that renders nothing is the other
    /// way to make a feature unreachable.
    @Test func everyStateOffersTheWholeMenu() {
        for state in Self.everyState() {
            let items = MenuModel.items(
                status: state.status, hasAddress: state.hasAddress, launchesAtLogin: state.atLogin)

            #expect(items.map(\.action) == MenuAction.allCases)
        }
    }

    /// **Quitting is quitting.** Enabled in every state there is, with nothing conditional about it.
    @Test func quitIsAlwaysAvailable() {
        for state in Self.everyState() {
            let items = MenuModel.items(
                status: state.status, hasAddress: state.hasAddress, launchesAtLogin: state.atLogin)

            #expect(items.first { $0.action == .quit }?.enabled == true)
        }
    }

    /// First run: no address yet. The way out must be open, and the code must not be offered for an
    /// address that does not exist.
    @Test func firstRunOffersTheWayOutAndNotACodeForNothing() {
        let items = MenuModel.items(status: nil, hasAddress: false, launchesAtLogin: false)

        #expect(items.first { $0.action == .setAddress }?.enabled == true)
        #expect(items.first { $0.action == .showPairingCode }?.enabled == false)
    }

    /// Start and Stop are opposites; Restart is available either way, because a pin without a restart
    /// pins nothing and "restart" on a stopped bridge is simply "start it".
    @Test func startAndStopFollowTheBridgeAndRestartAlwaysWorks() {
        let address = DialAddress(host: "agterm.example-homelab.invalid", port: 8443)
        let up = BridgeStatus(address: address, running: .running, reachable: .answered, identified: .ours)
        let down = BridgeStatus(address: address, running: .notRunning, reachable: .answered, identified: .ours)

        let whenUp = MenuModel.items(status: up, hasAddress: true, launchesAtLogin: false)
        let whenDown = MenuModel.items(status: down, hasAddress: true, launchesAtLogin: false)

        #expect(whenUp.first { $0.action == .startBridge }?.enabled == false)
        #expect(whenUp.first { $0.action == .stopBridge }?.enabled == true)
        #expect(whenDown.first { $0.action == .startBridge }?.enabled == true)
        #expect(whenDown.first { $0.action == .stopBridge }?.enabled == false)
        #expect(whenUp.first { $0.action == .restartBridge }?.enabled == true)
        #expect(whenDown.first { $0.action == .restartBridge }?.enabled == true)
    }
}
