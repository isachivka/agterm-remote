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

    // MARK: - The bridge is not something the menu asks about

    /// **No bridge control in any state.** It runs while the app is open and an address exists; the
    /// three items that used to manage it were three ways to end up with a code and nothing
    /// listening behind it. Every state still renders the same menu - see the test below.
    @Test func noStateOffersABridgeControl() {
        for state in [BridgeState.stopped, .starting, .running(pid: 1), .stopping, .failed("port")] {
            let titles = MenuModel.items(paired: [], hasAddress: true, launchesAtLogin: false, bridge: state)
                .map { $0.title.lowercased() }
            #expect(!titles.contains { $0.contains("start the bridge") || $0.contains("stop") || $0.contains("restart") },
                    "\(state) offered a bridge control")
        }
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
