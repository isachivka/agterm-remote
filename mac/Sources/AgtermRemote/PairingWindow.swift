import AgtermRemoteCore
import AppKit

/// The pairing panel: a code the bridge minted, and what happened to it.
///
/// **Every state renders something.** A window that opens blank is a mystery, not an affordance — the
/// same rule that cost the owner a pairing on 2026-08-10 when a menu item looked pressable and was
/// not. Each case of `PairingPanelState` gets a heading, a sentence, and — where there is one — a
/// code and the string it carries.
///
/// ### What moved out of this window, and why
///
/// It used to hold the address editor as well, on the argument that a code and the address it encodes
/// are one fact. That argument is still true and the address is still shown here; what left is the
/// **editing**. The address now belongs to the setup window, beside the paragraphs explaining what it
/// is for and what the second port is — three fields with no explanation next to a live enrolment
/// window was a screen that asked somebody to reconfigure their router while a stranger could walk
/// through the door it had just opened.
///
/// ### The code is opaque; the address is the only part a human can check
///
/// Every code this laptop makes carries the same certificate fingerprint, so comparing fingerprints
/// proves the code came from here and says nothing about where the phone will dial. On 2026-08-09
/// that produced two codes that paired perfectly and then never connected. The address is on screen
/// **at the same moment as the code, with no click and no hover**.
@MainActor
final class PairingWindow: NSObject, NSWindowDelegate {

    private var window: NSWindow?

    /// True while this window is on screen. The app redraws what is open without opening what is not:
    /// `show` makes a window when there is none, so calling it to refresh would put a live enrolment
    /// code in front of somebody who asked for neither.
    var isOpen: Bool { window != nil }

    /// The owner is finished with the panel — by closing the window, or by pressing Done. **The
    /// enrolment window at the bridge must not outlive this**, so the app closes it from here.
    var onClose: (() -> Void)?

    /// Ask for another code. Offered on every ending, because every ending leaves somebody who came
    /// here to pair a phone still holding one.
    var onAskForAnotherCode: (() -> Void)?

    /// The address the code encodes, in the same rendering the phone will show.
    var address = ""

    /// The bridge's account of why a pairing is not arriving, or nil. Drawn under whatever state is
    /// on screen, because it is true of the port rather than of the code.
    var warning: String?

    /// Opens, or redraws what is already open.
    func show(_ state: PairingPanelState) {
        let window = self.window ?? make()
        window.contentView = view(for: state)
        window.delegate = self
        // This app is an accessory: without activating, the window opens behind whatever the owner is
        // looking at, which for a thing they asked to see is the same as not opening.
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        if self.window == nil { window.center() }
        self.window = window
    }

    /// Redraws only if the window is already up. The panel's clock calls this every second, and a
    /// timer that could conjure a window would put a code on screen after the owner closed it.
    func refreshIfOpen(_ state: PairingPanelState) {
        guard isOpen else { return }
        show(state)
    }

    func close() {
        window?.close()
    }

    private func make() -> NSWindow {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 460, height: 560),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false,
        )
        window.title = "Pair a phone"
        // Closing it must not end the app: this is an accessory with a menu bar item, and quitting is
        // something the owner does from the menu, deliberately.
        window.isReleasedWhenClosed = false
        return window
    }

    /// **Closing the window closes the window at the bridge.** The enrolment window is the one moment
    /// this bridge will talk to a phone it has never met, and the argument for having that branch at
    /// all is that it lasts seconds and needs a person at the Mac. A panel that went away without
    /// closing it would leave the second clause resting on a timer.
    func windowWillClose(_: Notification) {
        window = nil
        onClose?()
    }

    // MARK: - The states

    /// The one rendering, shared with the setup window's last pane.
    private lazy var panel: PairingPanelView = {
        let panel = PairingPanelView()
        panel.onAskForAnotherCode = { [weak self] in self?.onAskForAnotherCode?() }
        panel.onDone = { [weak self] in self?.close() }
        return panel
    }()

    private func view(for state: PairingPanelState) -> NSView {
        panel.make(state: state, address: address, warning: warning)
    }
}
