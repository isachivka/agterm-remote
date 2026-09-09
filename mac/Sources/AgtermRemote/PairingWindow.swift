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

    private func view(for state: PairingPanelState) -> NSView {
        let stack = NSStackView()
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 12
        stack.edgeInsets = NSEdgeInsets(top: 20, left: 20, bottom: 20, right: 20)

        stack.addArrangedSubview(caption("This phone will connect to"))
        stack.addArrangedSubview(addressLabel(address))

        switch state {
        case .closed:
            // Reachable only for the instant between a dismissal and the window going away. It says
            // the ordinary thing rather than rendering an empty rectangle.
            stack.addArrangedSubview(heading("No code is on screen"))
            stack.addArrangedSubview(button("Show a code", #selector(askForAnotherCode)))

        case .showing(let payload, let expiresAt):
            stack.addArrangedSubview(code(for: payload))
            stack.addArrangedSubview(
                body("Point the phone at this code. Check the address above matches what the phone "
                    + "shows before you confirm — the fingerprint is the same in every code this "
                    + "laptop makes, so the address is the part that can be wrong."))
            stack.addArrangedSubview(caption("This code stops working at \(Self.clock(expiresAt))"))
            // **The paste fallback, and it is the same string the picture carries.** A camera that
            // will not read the code is the failure this exists for, and a second encoding of the
            // payload would be a second thing to go wrong.
            stack.addArrangedSubview(caption("If the camera will not read it, type or paste this instead"))
            stack.addArrangedSubview(payloadField(payload))

        case .expired, .refused, .withdrawn:
            stack.addArrangedSubview(heading("That code no longer works"))
            stack.addArrangedSubview(body(state.sentence ?? ""))
            stack.addArrangedSubview(button("Show a code", #selector(askForAnotherCode)))

        case .paired(_, _):
            stack.addArrangedSubview(heading("Paired"))
            stack.addArrangedSubview(body(state.sentence ?? ""))
            stack.addArrangedSubview(button("Done", #selector(done)))

        case .unavailable:
            stack.addArrangedSubview(heading("There is no code"))
            stack.addArrangedSubview(body(state.sentence ?? ""))
            stack.addArrangedSubview(button("Show a code", #selector(askForAnotherCode)))
        }

        return stack
    }

    @objc private func askForAnotherCode() { onAskForAnotherCode?() }

    @objc private func done() { close() }

    /// The expiry as a wall clock, not as a countdown. A number ticking down on a screen is something
    /// people watch instead of holding up their phone; an instant is something they can compare with
    /// the clock in the corner and forget about.
    private static func clock(_ instant: Date) -> String {
        instant.formatted(date: .omitted, time: .shortened)
    }

    // MARK: - Pieces

    private func heading(_ text: String) -> NSTextField {
        let field = NSTextField(labelWithString: text)
        field.font = .boldSystemFont(ofSize: 15)
        return field
    }

    private func caption(_ text: String) -> NSTextField {
        let field = NSTextField(labelWithString: text)
        field.font = .systemFont(ofSize: 11)
        field.textColor = .secondaryLabelColor
        return field
    }

    /// Monospaced and selectable, and **never truncated**: `example.invalid` and
    /// `agterm.example.invalid` are one label apart and one is a suffix of the other, so an ellipsis
    /// in the wrong place turns two destinations into the same string.
    private func addressLabel(_ text: String) -> NSTextField {
        let field = NSTextField(labelWithString: text)
        field.font = .monospacedSystemFont(ofSize: 14, weight: .regular)
        field.isSelectable = true
        field.lineBreakMode = .byWordWrapping
        field.maximumNumberOfLines = 3
        field.preferredMaxLayoutWidth = 420
        return field
    }

    private func body(_ text: String) -> NSTextField {
        let field = NSTextField(wrappingLabelWithString: text)
        field.font = .systemFont(ofSize: 12)
        field.textColor = .secondaryLabelColor
        field.preferredMaxLayoutWidth = 420
        return field
    }

    private func button(_ title: String, _ action: Selector) -> NSButton {
        let button = NSButton(title: title, target: self, action: action)
        button.bezelStyle = .rounded
        return button
    }

    /// The payload as text, selectable and wrapped. Not truncated and not shortened: it is the whole
    /// of what the picture carries, and a middle-elided version of it is not a code.
    private func payloadField(_ payload: String) -> NSTextField {
        let field = NSTextField(wrappingLabelWithString: payload)
        field.font = .monospacedSystemFont(ofSize: 10, weight: .regular)
        field.isSelectable = true
        field.preferredMaxLayoutWidth = 420
        return field
    }

    /// The code, big enough to scan from a phone held in front of the screen.
    ///
    /// If the payload cannot be drawn the window says so rather than showing an empty square — a blank
    /// where a code should be is the worst of both: it looks like it worked and cannot be scanned.
    private func code(for payload: String) -> NSView {
        guard let rendered = QRRender.image(for: payload, size: 380) else {
            return body("The bridge sent a code this app could not draw. The text below is the same "
                + "code; the phone will accept it typed or pasted.")
        }
        let image = NSImage(cgImage: rendered, size: NSSize(width: 380, height: 380))
        let view = NSImageView(image: image)
        // Never interpolated. The modules are whole pixels and smoothing them is how a code becomes
        // one a camera reads slowly or not at all.
        view.imageScaling = .scaleNone
        view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            view.widthAnchor.constraint(equalToConstant: 380),
            view.heightAnchor.constraint(equalToConstant: 380),
        ])
        return view
    }
}
