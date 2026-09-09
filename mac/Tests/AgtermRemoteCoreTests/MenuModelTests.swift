import Foundation
import Testing
@testable import AgtermRemoteCore

/// **What the menu says about the bridge and about the phone, as data.**
///
/// Two things are asserted here that were previously only argued for in prose:
///
///  - **Unpair exists only when there is a phone to unpair.** An item naming a phone that is not
///    there is the 2026-08-10 defect in its politest form — lit, pressable, and about nothing.
///  - **`.starting` and `.stopping` are states the menu has to have an answer for.** `.starting` was
///    split out of "running" three commits ago and the menu half of that fix was asserted nowhere;
///    `.stopping` arrives with this change, because `terminate` blocked the main thread for twice the
///    grace period and the fix is to stop waiting for it.
struct MenuModelTests {

    private static let phone = PairedPhone(fingerprint: "ab", name: "a phone")

    // MARK: - The behaviour the task names

    @Test func menuOffersUnpairOnlyWhenAPhoneIsPaired() {
        let withPhone = MenuModel.items(
            paired: [.init(fingerprint: "ab", name: "a phone")], hasAddress: true, launchesAtLogin: false)
        #expect(withPhone.contains { $0.action == .unpair })

        let without = MenuModel.items(paired: [], hasAddress: true, launchesAtLogin: false)
        #expect(!without.contains { $0.action == .unpair })
    }

    /// The item names the phone, because "Unpair" alone asks somebody to destroy something they
    /// cannot see. The bridge hands back a name and a fingerprint and both are the owner's only
    /// handle on which device this is.
    @Test func unpairNamesThePhoneItWouldDrop() {
        let items = MenuModel.items(paired: [Self.phone], hasAddress: true, launchesAtLogin: false)

        let unpair = items.first { $0.action == .unpair }
        #expect(unpair?.title.contains("a phone") == true, "the item does not say which phone")
        #expect(unpair?.enabled == true)
    }

    /// Pairing needs an address to put in the code. Offering it without one mints a code for nothing —
    /// the same reason the item was conditional before it was renamed.
    @Test func pairingIsOfferedOnlyOnceThereIsAnAddressToEncode() {
        #expect(
            MenuModel.items(paired: [], hasAddress: true, launchesAtLogin: false)
                .first { $0.action == .pairPhone }?.enabled == true)
        #expect(
            MenuModel.items(paired: [], hasAddress: false, launchesAtLogin: false)
                .first { $0.action == .pairPhone }?.enabled == false)
    }

    /// **The way into setup is never closed.** It is how somebody leaves first run and the only way to
    /// correct a wrong address, which is the failure this whole app exists to make visible.
    @Test func setUpIsAlwaysAvailable() {
        for hasAddress in [true, false] {
            for bridge in Self.everyBridgeState() {
                let items = MenuModel.items(
                    paired: [], hasAddress: hasAddress, launchesAtLogin: false, bridge: bridge)

                #expect(items.first { $0.action == .setUp }?.enabled == true)
            }
        }
    }

    // MARK: - The four bridge states, including the two nothing asserted

    /// **`.starting` is not running, and Stop must work on it.** A bridge that was spawned and has not
    /// announced a listener is exactly what somebody wants to call off — a frozen binary sits in this
    /// state for the whole ten-second ceiling.
    @Test func startingOffersStopAndNotStart() {
        let items = MenuModel.items(paired: [], hasAddress: true, launchesAtLogin: false, bridge: .starting)

        #expect(items.first { $0.action == .startBridge }?.enabled == false, "two bridges, one port")
        #expect(items.first { $0.action == .stopBridge }?.enabled == true, "a start cannot be called off")
    }

    /// **`.stopping` offers neither**, and that is the visible half of not blocking the main thread.
    /// The kill is on its way and takes up to twice the grace period; a second Stop signals a pid that
    /// is already being killed, and a Start in that window races the exit that has not landed yet.
    @Test func stoppingOffersNeitherStartNorStop() {
        let items = MenuModel.items(paired: [], hasAddress: true, launchesAtLogin: false, bridge: .stopping)

        #expect(items.first { $0.action == .startBridge }?.enabled == false)
        #expect(items.first { $0.action == .stopBridge }?.enabled == false)
        #expect(items.first { $0.action == .restartBridge }?.enabled == false)
    }

    /// The title says so too. A greyed item with the same words as a live one tells somebody the app
    /// has hung; the word is what says the machine is mid-way through what they asked for.
    @Test func stoppingSaysSoInTheTitle() {
        let items = MenuModel.items(paired: [], hasAddress: true, launchesAtLogin: false, bridge: .stopping)

        #expect(items.first { $0.action == .stopBridge }?.title.lowercased().contains("stopping") == true)
    }

    @Test func runningOffersStopAndStoppedOffersStart() {
        let up = MenuModel.items(paired: [], hasAddress: true, launchesAtLogin: false, bridge: .running(pid: 1))
        let down = MenuModel.items(paired: [], hasAddress: true, launchesAtLogin: false, bridge: .stopped)

        #expect(up.first { $0.action == .startBridge }?.enabled == false)
        #expect(up.first { $0.action == .stopBridge }?.enabled == true)
        #expect(down.first { $0.action == .startBridge }?.enabled == true)
        #expect(down.first { $0.action == .stopBridge }?.enabled == false)
    }

    /// A bridge that gave up can be started again. Pressing Start after a failure is a fresh five —
    /// the owner has just done something about the port or the binary.
    @Test func aFailedBridgeCanBeStartedAgain() {
        let items = MenuModel.items(
            paired: [], hasAddress: true, launchesAtLogin: false, bridge: .failed("address already in use"))

        #expect(items.first { $0.action == .startBridge }?.enabled == true)
        #expect(items.first { $0.action == .stopBridge }?.enabled == false)
    }

    /// Every bridge state renders a whole menu. A state that drops an item is the other way to make a
    /// feature unreachable.
    @Test func everyBridgeStateOffersTheSameActions() {
        for bridge in Self.everyBridgeState() {
            let items = MenuModel.items(
                paired: [Self.phone], hasAddress: true, launchesAtLogin: false, bridge: bridge)

            #expect(Set(items.map(\.action)) == Set(MenuAction.allCases), "\(bridge) drops an item")
        }
    }

    static func everyBridgeState() -> [BridgeState] {
        [.stopped, .starting, .running(pid: 4242), .stopping, .failed("address already in use")]
    }
}
