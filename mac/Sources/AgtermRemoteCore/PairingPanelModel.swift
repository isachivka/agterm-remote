import Foundation

/// One phone the bridge will talk to, as the owner sees it.
///
/// **The certificate is not here.** It is what authenticates the phone, it is large, and no menu has
/// anything to do with it — the same reading `control.pairedPeer` takes at the other end of the
/// socket. The fingerprint is the owner's handle on which device this is; the name is what makes the
/// menu item readable.
public struct PairedPhone: Equatable, Sendable {

    public let fingerprint: String
    public let name: String

    public init(fingerprint: String, name: String) {
        self.fingerprint = fingerprint
        self.name = name
    }
}

/// Why the last enrolment window stopped.
///
/// **The four endings are four different things to tell an owner**, and the bridge distinguishes them
/// on purpose — see `enroll.Ending`, where the argument for telling the owner's own machine this and
/// telling the anonymous caller nothing is written down. A panel that collapsed them back into "that
/// code no longer works" would throw away the only sentence somebody can act on: a code that ran out
/// is retried, and a code somebody spent five wrong tokens against is a different afternoon.
public enum PairingEnding: Equatable, Sendable {

    /// Nothing has ended: no window has ever been opened, or one is open right now.
    case never
    /// It ran out. Nobody necessarily tried the token.
    case expired
    /// Five wrong tokens were spent against it. See [PairingPanelModel.maxAttempts].
    case attempts
    /// A phone walked through it. The good ending.
    case paired
    /// The owner closed it — from this panel, or from another window of it.
    case closed
    /// An ending this build does not know. **Kept rather than flattened into `never`**: a bridge
    /// newer than this app must not be able to make the panel say "nothing happened" about something
    /// that did.
    case other(String)

    /// The wire spelling, which is `enroll.Ending`.
    public init(wire: String) {
        switch wire {
        case "": self = .never
        case "expired": self = .expired
        case "attempts": self = .attempts
        case "paired": self = .paired
        case "closed": self = .closed
        default: self = .other(wire)
        }
    }
}

/// The enrolment window as `status` reports it.
///
/// A value rather than three accessors, because every field describes the **same instant**. Asking
/// "is it open" and then "when does it expire" is asking about two, and the second answer can be
/// about a window the first one did not see.
public struct PairingWindowReport: Equatable, Sendable {

    public let open: Bool
    /// The instant the window will actually enforce, or nil when nothing is open.
    public let expiresAt: Date?
    /// How many more wrong tokens this window survives. **Only a token actually spent moves it** — a
    /// dropped connection or a retried handshake does not — so five is looser than it reads.
    public let attemptsLeft: Int
    public let ended: PairingEnding

    public init(open: Bool, expiresAt: Date?, attemptsLeft: Int, ended: PairingEnding) {
        self.open = open
        self.expiresAt = expiresAt
        self.attemptsLeft = attemptsLeft
        self.ended = ended
    }
}

/// The bridge's local door, as this app reaches it. **Four verbs and no fifth.**
///
/// ### Why `status` carries the window, when the task's sketch had three members
///
/// Because requirement 1 — the panel says WHY a code stopped working — has nowhere else to come
/// from, and a second round trip is the wrong way to get it. The bridge answers `status` in one verb
/// rather than four *precisely* so the picture is consistent: "the paired list and the window state
/// read separately are two moments, and a phone that enrolled between them would show up as a paired
/// phone against a window that was never open". Splitting the window back out into its own call here
/// would reintroduce exactly the inconsistency the Go side went out of its way to prevent.
///
/// `throws` on every member, and that is the interface rather than an omission: the bridge is a
/// separate process over a unix socket, and *it is not running* is the ordinary first answer.
public protocol ControlClient {

    /// Everything the menu and the panel are drawn from, in one line.
    func status() throws -> (listening: String, paired: [PairedPhone], agterm: Bool, window: PairingWindowReport)

    /// Mint a code. **The only thing anywhere that opens an enrolment window**, which is why the panel
    /// on screen is the second half of the argument for having an anonymous branch at all.
    ///
    /// - Returns: the payload, and the expiry **the window will actually enforce** — clamped to
    ///   `enroll.MaxTTL` at the far end. A panel that displayed the ttl it asked for would strand
    ///   somebody mid-pairing on a code that advertised an hour against a five-minute window.
    func openPairing(ttl: TimeInterval) throws -> (payload: String, expiresAt: Date)

    /// Shut it. Idempotent at the far end; this app still only calls it for a window it opened.
    func closePairing() throws

    /// Drop a paired phone. Never a wildcard and never optional — the fingerprint is required and an
    /// empty one is refused by the bridge rather than read as "unpair everything".
    func unpair(fingerprint: String) throws
}

/// What the pairing panel is showing.
///
/// **Every case says something.** There is no state here that renders an empty rectangle: a blank
/// where a code should be is the worst of both — it looks like it worked and cannot be scanned.
public enum PairingPanelState: Equatable, Sendable {

    /// Nothing is on screen and no window is open at the bridge.
    case closed

    /// The code, and the instant it stops being accepted. The payload is shown as selectable text
    /// beneath the picture: **that text is the phone's paste fallback and it is the same string.**
    case showing(payload: String, expiresAt: Date)

    /// It ran out.
    case expired

    /// Five wrong tokens were spent against it and the bridge shut the window.
    case refused(attemptsSpent: Int)

    /// The window was closed from somewhere that is not this panel — another window of it, or the
    /// palette command. Distinct from [closed], which is this panel's own dismissal.
    case withdrawn

    /// A phone walked through. Names it, because the fingerprint is the one thing the owner can
    /// compare against what the phone shows.
    case paired(fingerprint: String, name: String)

    /// There is no code, and this is why. **Not a blank panel** — the sentence is the whole content
    /// when there is nothing else.
    case unavailable(String)

    /// What the panel says, under the picture or in place of it. Nil only while a code is on screen,
    /// where the code itself is the message.
    public var sentence: String? {
        switch self {
        case .closed, .showing:
            nil
        case .expired:
            "That code has run out. Nobody used it. Press Show a code again for a fresh one."
        case .refused(let spent):
            // The count is of tokens SPENT, not of connections made: a dropped connection or a
            // retried handshake costs nothing and moves nothing. Saying "five attempts" would tell
            // an owner that somebody reached this Mac five times, which is not what happened.
            "That code stopped working after \(spent) wrong tokens were spent against it, so the "
                + "bridge shut the window. Press Show a code again for a fresh one, and check who is "
                + "on the other end of the address before you do."
        case .withdrawn:
            "That code was closed from somewhere else on this Mac, so it no longer works."
        case .paired(let fingerprint, let name):
            "\(name) is paired. Its fingerprint is \(fingerprint) — it should match what the phone "
                + "shows. Nothing else is needed; you can close this."
        case .unavailable(let why):
            why
        }
    }

    /// True while there is a code somebody could still scan. The panel's timer runs only for this.
    public var isShowingACode: Bool { if case .showing = self { true } else { false } }
}

/// The pairing panel, as a value. **No window, no timer, no socket** — all three are injected, so
/// every state this screen can be in is reachable in a test.
///
/// ### The clock is asked, never trusted to fire
///
/// `tick` is called by whatever is driving the panel and does the whole decision: it asks the bridge
/// what happened, and it compares the expiry against the clock. Neither half is sufficient alone. The
/// bridge is the only thing that knows a phone paired or that five tokens were burnt; the clock is
/// the only thing that keeps a dead code off the screen when the bridge cannot be reached at all —
/// **a stale code on screen is a code someone will scan.**
public final class PairingPanelModel {

    /// How long a code is asked for. `enroll.MaxTTL` is five minutes and the window clamps to it, so
    /// asking for more would advertise an expiry the bridge will not honour. Asking for exactly the
    /// ceiling means the number on screen is the number the window enforces.
    public static let ttl: TimeInterval = 300

    /// `enroll.MaxAttempts`. Mirrored rather than derived, because there is nothing here to derive it
    /// from — the bridge reports how many are LEFT, and the panel reports how many were spent.
    public static let maxAttempts = 5

    private let control: ControlClient
    private let now: () -> Date

    public private(set) var state: PairingPanelState = .closed

    /// True once this panel has opened a window at the bridge and has not closed it. **Closing is
    /// conditional on this**: a panel that fired `pair-close` on every dismissal would shut a window
    /// another panel had just opened.
    private var weOpenedAWindow = false

    public init(control: ControlClient, now: @escaping () -> Date) {
        self.control = control
        self.now = now
    }

    /// Ask the bridge for a code and show it.
    ///
    /// A refusal is a sentence rather than an empty panel: *the bridge is not running* is the ordinary
    /// first answer here, and it is the whole content of the screen when it happens.
    public func open() {
        do {
            let minted = try control.openPairing(ttl: Self.ttl)
            weOpenedAWindow = true
            state = .showing(payload: minted.payload, expiresAt: minted.expiresAt)
        } catch {
            state = .unavailable("\(error)")
        }
    }

    /// The owner is done with the panel. **The enrolment window must not outlive it.**
    ///
    /// The window is the one moment this bridge will talk to a phone it has never met, and the
    /// argument for having that branch at all is that it lasts seconds and needs a person at the Mac.
    /// A panel that closed without closing it would leave the second clause resting on a timer.
    public func close() {
        if weOpenedAWindow {
            // A refusal here is not something to put on a screen that is going away. The window
            // expires on its own, and the bridge is idempotent about closing one that is already shut.
            try? control.closePairing()
            weOpenedAWindow = false
        }
        state = .closed
    }

    /// One turn of the panel's clock.
    ///
    /// The order is deliberate. **What the bridge says outranks the clock**, because a phone that
    /// paired one second before the expiry paired — reporting that as "it ran out" would send somebody
    /// to re-scan a code for a phone that is already enrolled. The clock is the backstop for the case
    /// the bridge cannot answer at all.
    public func tick() {
        guard case .showing(_, let expiresAt) = state else { return }

        if let report = try? control.status() {
            switch report.window.ended {
            case .paired:
                // The phone is whichever one the bridge now holds. Unpairing is total in v1, so there
                // is exactly one, and the last of the list is the one that just arrived. A `paired`
                // ending with an empty list is a bridge contradicting itself; the clock below then
                // decides, rather than this putting an unnamed phone on the screen.
                if let phone = report.paired.last {
                    weOpenedAWindow = false
                    state = .paired(fingerprint: phone.fingerprint, name: phone.name)
                    return
                }
            case .attempts:
                weOpenedAWindow = false
                state = .refused(attemptsSpent: Self.maxAttempts - report.window.attemptsLeft)
                return
            case .closed:
                weOpenedAWindow = false
                state = .withdrawn
                return
            case .expired:
                weOpenedAWindow = false
                state = .expired
                return
            case .never, .other:
                break
            }
        }

        if now() >= expiresAt { state = .expired }
    }
}
