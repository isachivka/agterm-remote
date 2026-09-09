import Foundation
import Testing
@testable import AgtermRemoteCore

/// **The port the phone dials and the port traffic arrives on are two settings**, and the day they
/// were one was the day this app could not be set up behind a proxying router.
///
/// The topology that produced this: a router publishing 8443 and delivering to 8444 on the Mac.
/// Deriving the bind from the dial address makes the bridge listen on 8443 — a pairing code that is
/// perfect and a phone that never connects, which is the failure the whole product is built to
/// prevent, arriving from the other side.
struct ListenPortTests {

    private func scratch(_ name: String = #function) -> ScratchDefaults {
        ScratchDefaults(suite: "listen-port-\(name)-\(UUID().uuidString)")
    }

    /// Empty means **follow the dial port**. It is the answer for anybody who should not have to
    /// think about this field, and it is stored as an absence rather than as a copy.
    @Test func nothingTypedMeansFollowTheDialPort() {
        #expect(ListenPortEdit.parse("") == .success(nil))
        #expect(ListenPortEdit.parse("   ") == .success(nil))
    }

    @Test func aPortIsAPort() {
        #expect(ListenPortEdit.parse("8444") == .success(8444))
        #expect(ListenPortEdit.parse(" 8444 ") == .success(8444))
    }

    @Test func anythingElseIsRefusedInWords() {
        #expect(ListenPortEdit.parse("eight") == .failure(.notANumber("eight")))
        #expect(ListenPortEdit.parse("0") == .failure(.outOfRange(0)))
        #expect(ListenPortEdit.parse("70000") == .failure(.outOfRange(70000)))
        // Each refusal says what to do, and the one for an empty-looking mistake says that leaving it
        // empty is a real answer.
        #expect(ListenPortEdit.Refusal.notANumber("x").explanation.contains("straight through"))
    }

    @Test func aPortSurvivesTheRoundTripAndClearingRemovesIt() throws {
        let store = scratch()
        defer { store.discard() }

        try AddressPreference.writeListenPort(8444, to: store.defaults)
        #expect(AddressPreference.listenPort(from: store.defaults) == 8444)

        try AddressPreference.writeListenPort(nil, to: store.defaults)
        #expect(AddressPreference.listenPort(from: store.defaults) == nil)
        #expect(store.defaults.object(forKey: AddressPreference.listenPortKey) == nil)
    }

    /// A port outside the range is refused before it is stored, and a nonsense value that somebody
    /// else put there reads as *no override* rather than being handed to the bridge.
    @Test func anImpossiblePortNeverReachesTheStoreOrTheBridge() {
        let store = scratch()
        defer { store.discard() }

        #expect(throws: AddressPreference.WriteRefusal.self) {
            try AddressPreference.writeListenPort(70000, to: store.defaults)
        }
        store.defaults.set(70000, forKey: AddressPreference.listenPortKey)
        #expect(AddressPreference.listenPort(from: store.defaults) == nil)
    }

    /// **The whole point, end to end.** The store holds one dial address and one arrival port; the QR
    /// carries the first and the bridge binds the second.
    @Test func theCodeCarriesTheDialAddressWhileTheBridgeBindsTheArrivalPort() throws {
        let store = scratch()
        defer { store.discard() }
        let dial = DialAddress(host: "agterm.example-homelab.invalid", port: 8443)
        try AddressPreference.write(dial, to: store.defaults)
        try AddressPreference.writeListenPort(8444, to: store.defaults)

        let stored = try AddressPreference.read(from: store.defaults).get()
        let arrival = AddressPreference.listenPort(from: store.defaults)

        #expect(stored.displayed == "agterm.example-homelab.invalid:8443")
        #expect(Address(stored).listen(on: arrival) == "0.0.0.0:8444")
        // The two go to the bridge as two flags. `--listen` is where traffic arrives at this Mac;
        // `--advertise` is what the QR code names, and it is the dial address unchanged.
        #expect(stored.displayed == "agterm.example-homelab.invalid:8443")
    }

    /// Saving reports which port the bridge will use, in both directions, because the person who has
    /// just told the app about a proxying router needs to see that it heard.
    @Test func savingSaysWhichPortItWillBe() {
        let held = Held()

        #expect(SaveListenPort(write: held.write).save("8444", dialPort: 8443) == .saved(8444))
        #expect(held.port == 8444)
        #expect(
            SaveListenPort(write: held.write).save("", dialPort: 8443) == .following(dialPort: 8443))
        #expect(held.port == nil)
        guard case .refused(let sentence) = SaveListenPort(write: held.write).save("x", dialPort: 8443)
        else { return #expect(Bool(false), "a bad port must be refused") }
        #expect(sentence.contains("not a port"))
    }

    /// A store that refuses is a different sentence from a port that is wrong: one of them is the
    /// person's mistake and the other is not.
    @Test func aFailedWriteIsNotReportedAsARefusal() {
        let outcome = SaveListenPort { _ in throw AddressPreference.WriteRefusal.wouldNotReadBack("x") }
            .save("8444", dialPort: 8443)

        guard case .notWritten(let sentence) = outcome else {
            return #expect(Bool(false), "\(outcome)")
        }
        #expect(sentence.contains("The port is fine"))
    }

    /// **The failure that will actually happen on a machine already running something similar**, said
    /// with the number in it. The owner has two ports in play; a sentence without one is a sentence
    /// they cannot act on.
    @Test func aPortSomebodyElseHoldsIsExplainedWithTheNumber() {
        let reported = "the bridge exited: listen tcp [::]:8444: bind: address already in use"

        let said = PortInUse.explanation(for: reported, port: 8444)

        #expect(said?.contains("8444") == true)
        #expect(said?.lowercased().contains("already listening") == true)
        // And it says what to do about it, both ways out.
        #expect(said?.contains("arrival port") == true)
    }

    /// Anything else keeps the bridge's own words. A translation layer that swallowed every failure
    /// would replace an exact message with a guess.
    @Test func anythingElseIsLeftInTheBridgesOwnWords() {
        #expect(PortInUse.explanation(for: "the bridge exited: permission denied", port: 8444) == nil)
        #expect(PortInUse.explanation(for: "", port: 8444) == nil)
    }

    /// Holds what a save wrote, so the outcome and the write can both be checked.
    private final class Held: @unchecked Sendable {
        private let lock = NSLock()
        private var stored: Int??
        var port: Int? { lock.withLock { stored ?? nil } }
        var write: @Sendable (Int?) throws -> Void {
            { [self] value in lock.withLock { stored = value } }
        }
    }
}
