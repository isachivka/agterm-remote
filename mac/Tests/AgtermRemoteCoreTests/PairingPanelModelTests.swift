import Foundation
import Testing
@testable import AgtermRemoteCore

/// **The panel, as a value, before there is a window to put it in.**
///
/// Written against the four behaviours the task names — a code is asked for and shown, it dies on its
/// own, closing the panel closes the window on the bridge, and the menu offers Unpair only when there
/// is a phone to unpair — and then against the one thing five reviews kept asking for: **the panel
/// says WHY a code stopped working.** The bridge distinguishes four endings and it does so
/// deliberately; a panel that collapses them back into "that code no longer works" throws away the
/// only sentence the owner can act on.
struct PairingPanelModelTests {

    // MARK: - The four behaviours the task names

    @Test func openAsksTheBridgeForAPayloadAndShowsIt() {
        let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
        let model = PairingPanelModel(control: control, now: { Date(timeIntervalSince1970: 0) })

        model.open()

        #expect(model.state == .showing(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300)))
        #expect(control.opened == [PairingPanelModel.ttl], "the panel minted a window with some other ttl")
    }

    @Test func theCodeExpiresOnItsOwn() {
        let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
        var now = Date(timeIntervalSince1970: 0)
        let model = PairingPanelModel(control: control, now: { now })
        model.open()

        now = Date(timeIntervalSince1970: 301)
        model.tick()

        #expect(model.state == .expired, "a stale code on screen is a code someone will scan")
    }

    @Test func closingThePanelClosesTheWindowOnTheBridge() {
        let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
        let model = PairingPanelModel(control: control, now: { Date(timeIntervalSince1970: 0) })
        model.open()

        model.close()

        #expect(control.closed, "the enrolment window must not outlive the panel")
        #expect(model.state == .closed)
    }

    // MARK: - Why a code died, which is the carried requirement

    /// **The whole of requirement 1, as a table.** Each ending the bridge reports produces its own
    /// state, and each state produces its own sentence. Nothing collapses two of them together.
    @Test func eachEndingIsItsOwnStateAndItsOwnSentence() {
        let endings: [(PairingEnding, PairingPanelState)] = [
            (.attempts, .refused(attemptsSpent: PairingPanelModel.maxAttempts)),
            (.closed, .withdrawn),
        ]
        for (ending, expected) in endings {
            let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
            let model = PairingPanelModel(control: control, now: { Date(timeIntervalSince1970: 10) })
            model.open()

            control.window = PairingWindowReport(open: false, expiresAt: nil, attemptsLeft: 0, ended: ending)
            model.tick()

            #expect(model.state == expected, "\(ending) was read as \(model.state)")
        }

        let sentences = [
            PairingPanelState.expired,
            .refused(attemptsSpent: PairingPanelModel.maxAttempts),
            .withdrawn,
            .paired(fingerprint: "ab cd", name: "a phone"),
        ].map(\.sentence)
        #expect(sentences.allSatisfy { $0?.isEmpty == false }, "an ending with nothing to say")
        #expect(Set(sentences.map { $0 ?? "" }).count == sentences.count, "two endings say the same thing")
    }

    /// **Five is looser than it reads, and the sentence says so.** Only a token actually spent moves
    /// the counter — a dropped connection or a retried handshake does not — so the panel must not
    /// tell an owner that somebody made five connections.
    @Test func theRefusalSentenceCountsSpentTokensRatherThanConnections() {
        let sentence = PairingPanelState.refused(attemptsSpent: 5).sentence ?? ""

        #expect(sentence.contains("5"))
        #expect(sentence.lowercased().contains("token"), "the count is of tokens, not of connections")
    }

    /// A phone that walks through the window is the ending everybody wants, and it names the phone.
    /// The fingerprint is on screen because it is the one thing the owner can compare against what
    /// the phone shows.
    @Test func aPhoneThatPairsIsNamedWithItsFingerprint() {
        let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
        let model = PairingPanelModel(control: control, now: { Date(timeIntervalSince1970: 10) })
        model.open()

        control.paired = [PairedPhone(fingerprint: "ab cd ef", name: "a phone")]
        control.window = PairingWindowReport(open: false, expiresAt: nil, attemptsLeft: 5, ended: .paired)
        model.tick()

        #expect(model.state == .paired(fingerprint: "ab cd ef", name: "a phone"))
        #expect(model.state.sentence?.contains("ab cd ef") == true)
    }

    /// **An ending outranks the clock only while the clock has not run out.** A phone that paired one
    /// second before the code expired paired; a code that expired with nothing having happened
    /// expired.
    @Test func pairingJustBeforeTheExpiryIsPairingAndNotExpiry() {
        let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
        var now = Date(timeIntervalSince1970: 299)
        let model = PairingPanelModel(control: control, now: { now })
        model.open()
        control.paired = [PairedPhone(fingerprint: "ab", name: "a phone")]
        control.window = PairingWindowReport(open: false, expiresAt: nil, attemptsLeft: 5, ended: .paired)

        now = Date(timeIntervalSince1970: 400)
        model.tick()

        #expect(model.state == .paired(fingerprint: "ab", name: "a phone"))
    }

    /// A bridge that cannot be asked is said out loud rather than shown as an empty square. The panel
    /// exists to hand somebody a code; when there is none, the reason is the whole content.
    ///
    /// **And it is a sentence.** The bridge's refusals are clauses — *"this bridge was built without a
    /// pairing half"*, lowercase, no subject — and one of those dropped into a panel where every other
    /// line is written for a person reads as a leaked internal string, which is what it was.
    @Test func aBridgeThatRefusesIsSaidRatherThanShownAsAnEmptyPanel() {
        let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
        control.refuseOpenWith = ControlFailure.refused("this bridge was built without a pairing half")
        let model = PairingPanelModel(control: control, now: { Date(timeIntervalSince1970: 0) })

        model.open()

        let said = model.state.sentence ?? ""
        #expect(said.hasPrefix("The bridge would not make a code."), "the panel shows a raw fragment: \(said)")
        #expect(said.contains("this bridge was built without a pairing half"), "it drops what was said")
    }

    /// A bridge that is not running is already a sentence, and it is used unchanged rather than
    /// wrapped in a second one.
    @Test func aBridgeThatIsNotRunningKeepsItsOwnSentence() {
        let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
        control.refuseOpenWith = ControlFailure.notListening(path: "/nowhere/control.sock")
        let model = PairingPanelModel(control: control, now: { Date(timeIntervalSince1970: 0) })

        model.open()

        #expect(model.state == .unavailable(ControlFailure.notListening(path: "/nowhere/control.sock").description))
        #expect(model.state.sentence?.contains("The bridge is not running") == true)
    }

    /// The clock is not consulted for a panel that is not showing a code. A tick on a closed panel
    /// must not mint anything, ask anything, or invent a state.
    @Test func tickingAClosedPanelDoesNothing() {
        let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
        let model = PairingPanelModel(control: control, now: { Date(timeIntervalSince1970: 9999) })

        model.tick()

        #expect(model.state == .closed)
        #expect(control.opened.isEmpty)
        #expect(control.statusCalls == 0)
    }

    /// Closing a panel that never opened a window must not ask the bridge to close one. The verb is
    /// idempotent at the far end, but a panel that fires it on every dismissal would close a window
    /// somebody else's panel had just opened.
    @Test func closingAPanelThatShowedNothingDoesNotReachTheBridge() {
        let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
        let model = PairingPanelModel(control: control, now: { Date(timeIntervalSince1970: 0) })

        model.close()

        #expect(!control.closed, "a panel that minted nothing closed somebody else's window")
    }

    /// The panel closes the window on the bridge when the code dies too. An expired code the bridge
    /// still holds open is an anonymous branch of the front door standing open for no reason.
    @Test func anExpiredCodeIsAlsoClosedAtTheBridge() {
        let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
        var now = Date(timeIntervalSince1970: 0)
        let model = PairingPanelModel(control: control, now: { now })
        model.open()
        now = Date(timeIntervalSince1970: 301)
        model.tick()

        model.close()

        #expect(control.closed)
    }

    // MARK: - The address the code names, read at the press

    /// **The dial address rides on the mint.** It is editable while the bridge runs and nothing
    /// restarts the bridge, so a value fixed at spawn goes stale the moment somebody saves a new one —
    /// the app then says *your phone will dial X* beside a code that says Y.
    @Test func theCodeIsMintedForTheAddressGivenAtThePress() {
        let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
        let model = PairingPanelModel(control: control, now: { Date(timeIntervalSince1970: 0) })

        model.open(advertising: "old.example-homelab.invalid:8443")
        model.close()
        model.open(advertising: "new.example-homelab.invalid:9443")

        #expect(control.advertised == ["old.example-homelab.invalid:8443", "new.example-homelab.invalid:9443"])
    }

    // MARK: - The bridge going away under a live code

    /// **A bridge that cannot be asked is not a reason to keep showing the code.** This used to fall
    /// through to the clock: the bridge died, `status` threw, `try?` swallowed it, and a live-looking
    /// code sat on screen for the rest of its five minutes. Somebody scans that and gets nothing,
    /// which looks exactly like a broken phone.
    @Test func aBridgeThatDiesUnderALiveCodeTakesTheCodeOffTheScreen() {
        let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
        let model = PairingPanelModel(control: control, now: { Date(timeIntervalSince1970: 10) })
        model.open()

        control.refuseStatusWith = ControlFailure.notListening(path: "/tmp/gone/control.sock")
        model.tick()

        #expect(model.state == .unavailable(PairingPanelModel.bridgeStoppedAnswering))
        #expect(model.state.sentence?.contains("no longer works") == true)
    }

    // MARK: - Nothing blocks the caller

    /// **The measurement behind moving the round trips off the caller's thread.**
    ///
    /// Every verb is a blocking call to another process, bounded by five seconds against a bridge that
    /// accepts and then stalls. On a one-second timer on the main thread that is an interface frozen
    /// five seconds out of six — the same hang class `BridgeProcess.stop` was rebuilt to remove.
    ///
    /// The executor here never runs what it is handed, which is the strongest form of the assertion:
    /// if `open` or `tick` touched the socket on the caller's thread, the fake would have recorded it.
    @Test func openAndTickDoNotTouchTheSocketOnTheCallersThread() {
        let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
        let held = HeldWork()
        let model = PairingPanelModel(control: control, now: { Date(timeIntervalSince1970: 0) }, off: held.hold)

        model.open()

        #expect(control.opened.isEmpty, "open() minted a code on the caller's thread")
        #expect(model.state == .asking, "the panel has nothing to draw while it waits")
        held.run()
        #expect(model.state == .showing(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300)))

        model.tick()
        #expect(control.statusCalls == 0, "tick() asked the bridge on the caller's thread")
    }

    /// A dismissal never waits for a socket either — the state is `.closed` before the round trip is
    /// even scheduled. A window that would not go away while the bridge is stalled is the same defect
    /// wearing the other coat.
    @Test func closingIsImmediateEvenWhenTheBridgeIsStalled() {
        let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
        let held = HeldWork()
        let model = PairingPanelModel(control: control, now: { Date(timeIntervalSince1970: 0) }, off: held.hold)
        model.open()
        held.run()

        model.close()

        #expect(model.state == .closed)
        #expect(!control.closed, "the dismissal waited for the bridge")
        held.run()
        #expect(control.closed, "the enrolment window outlived the panel")
    }

    /// **A late answer about a panel that is gone changes nothing — and shuts the window it opened.**
    /// Otherwise a mint that landed after the owner walked away leaves the one anonymous branch of the
    /// front door open for five minutes with no code anywhere on screen.
    @Test func aMintThatLandsAfterTheOwnerClosedThePanelIsShutRatherThanDrawn() {
        let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
        let held = HeldWork()
        let model = PairingPanelModel(control: control, now: { Date(timeIntervalSince1970: 0) }, off: held.hold)
        model.open()

        model.close()
        held.run()

        #expect(model.state == .closed, "a code appeared on a panel the owner had closed")
        #expect(control.closed, "the window that was minted for nobody was left standing")
    }

    /// Work that is only run when the test says so. The strongest available statement of "not on the
    /// caller's thread": it is not on any thread until asked.
    final class HeldWork {
        private var pending: [() -> Void] = []
        var hold: (@escaping () -> Void) -> Void { { [self] in pending.append($0) } }
        func run() {
            let due = pending
            pending = []
            due.forEach { $0() }
        }
    }

    // MARK: - The double

    final class FakeControl: ControlClient, @unchecked Sendable {

        private let payload: String
        private let expiresAt: Date

        var listening = "127.0.0.1:8443"
        var paired: [PairedPhone] = []
        var agterm = true
        var window: PairingWindowReport
        var refuseOpenWith: Error?
        var refuseStatusWith: Error?

        private(set) var opened: [TimeInterval] = []
        private(set) var advertised: [String] = []
        private(set) var closed = false
        private(set) var unpaired: [String] = []
        private(set) var statusCalls = 0

        init(payload: String, expiresAt: Date) {
            self.payload = payload
            self.expiresAt = expiresAt
            window = PairingWindowReport(open: false, expiresAt: nil, attemptsLeft: 5, ended: .never)
        }

        func status() throws -> (listening: String, paired: [PairedPhone], agterm: Bool, window: PairingWindowReport) {
            statusCalls += 1
            if let refuseStatusWith { throw refuseStatusWith }
            return (listening, paired, agterm, window)
        }

        func openPairing(
            ttl: TimeInterval, advertise: String, frontDoor: FrontDoor
        ) throws -> (payload: String, expiresAt: Date) {
            if let refuseOpenWith { throw refuseOpenWith }
            opened.append(ttl)
            advertised.append(advertise)
            window = PairingWindowReport(open: true, expiresAt: expiresAt, attemptsLeft: 5, ended: .never)
            return (payload, expiresAt)
        }

        func closePairing() throws { closed = true }

        func unpair(fingerprint: String) throws { unpaired.append(fingerprint) }
    }
}
