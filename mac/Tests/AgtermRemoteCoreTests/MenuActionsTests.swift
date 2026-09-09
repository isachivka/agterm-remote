import Foundation
import Testing
@testable import AgtermRemoteCore

/// **The assertion that replaces the one which measured the wrong property.**
///
/// `MenuReachabilityTests` asserts every action is *enabled in some state*. It never asserts that any
/// action *does* anything, so it passed on a menu of five inert items — and those items shipped to the
/// owner's menu bar, where they pressed two of them and nothing happened.
///
/// **Enabled is not wired.** These tests are written before a single item is wired, against a menu
/// that still does nothing, because a test written after the capability exists is a test written to
/// pass — the day's evidence for that is `BoundaryTests`, which was honest only in the moment it had
/// nothing to catch.
struct MenuActionsTests {

    private let address = DialAddress(host: "agterm.example-homelab.invalid", port: 8443)

    /// Pressing an action produces **exactly one** recorded invocation. Not "at least one" — a menu
    /// that fires twice is its own defect, and on `restartBridge` it would be two restarts.
    @Test func performingAnActionRecordsExactlyOneInvocation() {
        for action in MenuAction.allCases {
            let sink = RecordingMenuActions()

            sink.perform(action)

            #expect(sink.invocations == [action], "\(action) recorded \(sink.invocations)")
        }
    }

    /// **THE CONTROL, and the whole point of the file.** An action deliberately left unwired must
    /// fail to be offered. If this ever passes with `showPairingCode` absent from `implemented`, the
    /// suite has gone back to measuring the wrong property.
    @Test func anActionThatIsNotImplementedIsNeverEnabledInAnyState() {
        let halfBuilt = Set(MenuAction.allCases).subtracting([.showPairingCode])

        for state in Self.everyState() {
            let items = MenuModel.items(
                status: state.status, hasAddress: state.hasAddress,
                launchesAtLogin: state.atLogin, implemented: halfBuilt)

            let dead = items.first { $0.action == .showPairingCode }
            #expect(dead?.enabled == false, "an unwired action was offered as pressable")
        }
    }

    /// The rule stated as a property over every action and every state: **enabled implies wired.**
    /// This is what makes "we will remember to grey it out" into "it cannot be pressable unless it is
    /// wired".
    @Test func everyEnabledItemIsAnImplementedAction() {
        for implemented in Self.interestingSets() {
            for state in Self.everyState() {
                let items = MenuModel.items(
                    status: state.status, hasAddress: state.hasAddress,
                    launchesAtLogin: state.atLogin, implemented: implemented)

                for item in items where item.enabled {
                    #expect(implemented.contains(item.action), "\(item.action) is enabled and not wired")
                }
            }
        }
    }

    /// A build where NOTHING is wired offers nothing pressable. That is what this once shipped as, and
    /// it should have looked like it.
    @Test func aMenuWithNothingWiredHasNothingEnabled() {
        let items = MenuModel.items(
            status: nil, hasAddress: true, launchesAtLogin: false, implemented: [])

        #expect(items.count == MenuAction.allCases.count, "the items still exist; they are just dead")
        #expect(items.allSatisfy { !$0.enabled })
    }

    /// Wiring an action does not resurrect one the STATE disables: `stopBridge` stays dead while the
    /// bridge is down, even though it is implemented. Both conditions have to hold.
    @Test func wiringDoesNotOverrideTheState() {
        let down = BridgeStatus(address: address, running: .notRunning, reachable: .answered, identified: .ours)

        let items = MenuModel.items(status: down, hasAddress: true, launchesAtLogin: false)

        #expect(items.first { $0.action == .stopBridge }?.enabled == false)
        #expect(items.first { $0.action == .startBridge }?.enabled == true)
    }

    // --- shared with the reachability tests -------------------------------------------------------

    static func everyState() -> [(status: BridgeStatus?, hasAddress: Bool, atLogin: Bool)] {
        let address = DialAddress(host: "agterm.example-homelab.invalid", port: 8443)
        let statuses: [BridgeStatus?] = [
            nil,
            BridgeStatus(address: address, running: .running, reachable: .answered, identified: .ours),
            BridgeStatus(address: address, running: .notRunning, reachable: .noAnswer("refused"),
                         identified: .notEstablished),
            BridgeStatus(address: address, running: .running, reachable: .answered,
                         identified: .notOurs(presented: "1111 2222")),
        ]
        var states: [(BridgeStatus?, Bool, Bool)] = []
        for status in statuses {
            for hasAddress in [true, false] {
                for atLogin in [true, false] { states.append((status, hasAddress, atLogin)) }
            }
        }
        return states
    }

    static func interestingSets() -> [Set<MenuAction>] {
        [
            [],
            [.quit],
            [.quit, .showPairingCode],
            Set(MenuAction.allCases).subtracting([.setAddress]),
            Set(MenuAction.allCases),
        ]
    }
}
