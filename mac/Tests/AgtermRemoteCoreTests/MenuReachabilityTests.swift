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

    /// Every combination the menu can actually be shown in. Shared with `MenuActionsTests`, so the
    /// two halves of the rule — reachable, and wired — are asked over the same world.
    private static func everyState() -> [(paired: [PairedPhone], hasAddress: Bool, atLogin: Bool, bridge: BridgeState)] {
        MenuActionsTests.everyState()
    }

    private static func enabledSomewhere(
        _ menu: ([PairedPhone], Bool, Bool, BridgeState) -> [MenuItem],
    ) -> Set<MenuAction> {
        var reachable: Set<MenuAction> = []
        for state in everyState() {
            for item in menu(state.paired, state.hasAddress, state.atLogin, state.bridge) where item.enabled {
                reachable.insert(item.action)
            }
        }
        return reachable
    }

    /// Still worth keeping, and now honest about what it does NOT prove: it asks whether an action is
    /// **reachable**, never whether it **does anything**. `MenuActionsTests` is the half that asks
    /// that, and it exists because this test passed on a menu of five inert items that shipped.
    @Test func everyActionIsEnabledInAtLeastOneReachableState() {
        let reachable = Self.enabledSomewhere { paired, hasAddress, atLogin, bridge in
            MenuModel.items(paired: paired, hasAddress: hasAddress, launchesAtLogin: atLogin, bridge: bridge)
        }

        let unreachable = Set(MenuAction.allCases).subtracting(reachable)
        #expect(unreachable.isEmpty, "disabled in every state, so they do not exist: \(unreachable)")
    }

    /// **The control.** A check that cannot fail is not a check: this plants an action disabled
    /// everywhere and proves the detector above would have caught it.
    @Test func theReachabilityDetectorCatchesAnActionDisabledEverywhere() {
        let crippled: ([PairedPhone], Bool, Bool, BridgeState) -> [MenuItem] = { paired, hasAddress, atLogin, bridge in
            MenuModel.items(paired: paired, hasAddress: hasAddress, launchesAtLogin: atLogin, bridge: bridge).map {
                $0.action == .pairPhone
                    ? MenuItem(action: $0.action, title: $0.title, enabled: false)
                    : $0
            }
        }

        let reachable = Self.enabledSomewhere(crippled)

        #expect(!reachable.contains(.pairPhone), "the detector would have missed today's deadlock")
    }

    /// The menu is never empty and never loses an action it could offer: a state that renders nothing
    /// is the other way to make a feature unreachable.
    ///
    /// **`unpair` is the one exception, and it is the point of the exception.** The item names a
    /// phone; with no phone paired there is nothing for it to name, and an item reading *Unpair* over
    /// an empty trust store asks somebody to destroy something they cannot see. So it is absent rather
    /// than present-and-dead — which is why this asserts the whole menu MINUS that one, and asserts
    /// separately that it appears the moment there is a phone.
    @Test func everyStateOffersTheWholeMenu() {
        for state in Self.everyState() {
            let items = MenuModel.items(
                paired: state.paired, hasAddress: state.hasAddress, launchesAtLogin: state.atLogin,
                bridge: state.bridge)

            let expected = state.paired.isEmpty
                ? MenuAction.allCases.filter { $0 != .unpair }
                : MenuAction.allCases
            #expect(items.map(\.action) == expected)
        }
    }

    /// **Quitting is quitting.** Enabled in every state there is, with nothing conditional about it.
    @Test func quitIsAlwaysAvailable() {
        for state in Self.everyState() {
            let items = MenuModel.items(
                paired: state.paired, hasAddress: state.hasAddress, launchesAtLogin: state.atLogin,
                bridge: state.bridge)

            #expect(items.first { $0.action == .quit }?.enabled == true)
        }
    }

    /// First run: no address yet. The way out must be open, and the code must not be offered for an
    /// address that does not exist.
    @Test func firstRunOffersTheWayOutAndNotACodeForNothing() {
        let items = MenuModel.items(paired: [], hasAddress: false, launchesAtLogin: false)

        #expect(items.first { $0.action == .setUp }?.enabled == true)
        #expect(items.first { $0.action == .pairPhone }?.enabled == false)
    }

    /// Start and Stop are opposites; Restart is available either way, because a pin without a restart
    /// pins nothing and "restart" on a stopped bridge is simply "start it".
    @Test func startAndStopFollowTheBridgeAndRestartAlwaysWorks() {
        let whenUp = MenuModel.items(
            paired: [], hasAddress: true, launchesAtLogin: false, bridge: .running(pid: 4242))
        let whenDown = MenuModel.items(
            paired: [], hasAddress: true, launchesAtLogin: false, bridge: .stopped)

        #expect(whenUp.first { $0.action == .startBridge }?.enabled == false)
        #expect(whenUp.first { $0.action == .stopBridge }?.enabled == true)
        #expect(whenDown.first { $0.action == .startBridge }?.enabled == true)
        #expect(whenDown.first { $0.action == .stopBridge }?.enabled == false)
        #expect(whenUp.first { $0.action == .restartBridge }?.enabled == true)
        #expect(whenDown.first { $0.action == .restartBridge }?.enabled == true)
    }
}
