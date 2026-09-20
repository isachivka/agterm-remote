import AgtermRemoteCore
import AppKit

/// **The one window.** Address, port, how the phone gets here, and the code - together.
///
/// It replaces a three-step ladder and a separate pairing window. The first person to set this up
/// met, in order: a last step promising a code it did not show, a button opening a second window, no
/// way back to the address, a "Look again" button nobody could explain, and more text on the address
/// pane than they were willing to read. Their verdict was that every one of those was cognitive load
/// on somebody who wants to type an address and hold up a phone - and that the code should simply
/// appear once the address is saved. This is that.
///
/// ### What it does not decide
///
/// Nothing. It draws what the app hands it and reports what was typed or pressed. Saving, minting,
/// starting the bridge and asking the bridge what happened are the app's, so every rule about them
/// lives in one place and is testable without a window.
///
/// ### It keeps the owner's typing
///
/// The app redraws this window whenever the bridge changes state or the code moves, which can be
/// every few seconds. A redraw that rebuilt the boxes from the store would take a half-typed address
/// away under somebody's cursor, so a redraw reads the boxes first and puts the same text back.
@MainActor
final class SettingsWindow: NSObject, NSWindowDelegate, NSTextFieldDelegate {

    private var window: NSWindow?
    var isOpen: Bool { window != nil }

    // MARK: What the app hands in

    var field = AddressField()
    var arrivalPortText = ""
    var agtermIsThere = false
    /// What the bridge is doing, in the app's words.
    var bridgeLine = ""
    /// The save's outcome - a refusal, or what was written. Cleared by the app on the next save.
    var addressMessage = ""
    /// The phone this bridge holds, if one. Shown instead of a code.
    var paired: PairedPhone?
    var hasAddress = false
    /// The code and what happened to it.
    var panelState: PairingPanelState = .closed
    /// The bridge's sentence for the last phone it turned away - shown until a phone is pinned, so
    /// a code that "did not work" on the phone has its reason on the Mac.
    var lastRefusal: String?

    // MARK: What the owner did

    /// Every keystroke in either box, with both current values. The app saves what parses, says what
    /// does not, and applies the result - bridge restart, fresh code - after a moment of quiet.
    var onEdited: ((_ typed: String, _ port: String) -> Void)?
    /// The one thing that still needs a press: a two-label host the parser will not take on faith.
    var onConfirmSuffix: (() -> Void)?
    /// Whether the message row should offer that press.
    var offersSuffixConfirmation = false
    var onUnpair: (() -> Void)?
    var onClose: (() -> Void)?

    private var addressBox: NSTextField?
    private var portBox: NSTextField?
    private let codePanel = PairingPanelView()

    func show() {
        let window = self.window ?? make()
        window.contentView = view()
        window.delegate = self
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        if self.window == nil {
            window.center()
            if !hasAddress, let addressBox { window.makeFirstResponder(addressBox) }
        }
        self.window = window
    }

    /// Redraw what is open, keeping whatever is in the boxes. Never opens a window: a timer that
    /// could conjure one would put a code in front of somebody who closed it.
    func refreshIfOpen() {
        guard isOpen else { return }
        if let addressBox { field = AddressField(text: addressBox.stringValue) }
        if let portBox { arrivalPortText = portBox.stringValue }
        let editing = window?.firstResponder is NSTextView
        let wasAddress = editing && addressBox.map { $0.currentEditor() != nil } == true
        let wasPort = editing && portBox.map { $0.currentEditor() != nil } == true
        show()
        if wasAddress, let addressBox { window?.makeFirstResponder(addressBox) }
        if wasPort, let portBox { window?.makeFirstResponder(portBox) }
    }

    func close() { window?.close() }

    private func make() -> NSWindow {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 520, height: 640),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false,
        )
        window.title = "Agterm Remote"
        window.isReleasedWhenClosed = false
        return window
    }

    func windowWillClose(_: Notification) {
        window = nil
        addressBox = nil
        portBox = nil
        onClose?()
    }

    // MARK: - The view

    private func view() -> NSView {
        let stack = NSStackView()
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 10
        stack.edgeInsets = NSEdgeInsets(top: 18, left: 20, bottom: 18, right: 20)

        stack.addArrangedSubview(
            caption(agtermIsThere ? "agterm is running." : "agterm is not running - a phone can connect but has nothing to drive."))

        // **No Save button.** The first person to set this up pressed it twice before both boxes
        // took, and could not say which one had not. Whatever is typed is saved as it is typed, and
        // the code below follows a moment after the typing stops. There is nothing to press.
        stack.addArrangedSubview(label("Address your phone dials"))
        let box = NSTextField(string: field.text)
        box.placeholderString = OnboardingCopy.addressPlaceholder
        box.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        box.translatesAutoresizingMaskIntoConstraints = false
        box.widthAnchor.constraint(equalToConstant: 460).isActive = true
        box.delegate = self
        addressBox = box
        stack.addArrangedSubview(box)

        // The port traffic arrives on. Blank follows the address, which is the port-forward case.
        stack.addArrangedSubview(label("Port this Mac listens on"))
        let port = NSTextField(string: arrivalPortText)
        port.placeholderString = "same as the address"
        port.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        port.translatesAutoresizingMaskIntoConstraints = false
        port.widthAnchor.constraint(equalToConstant: 160).isActive = true
        port.delegate = self
        portBox = port
        stack.addArrangedSubview(port)

        // Nothing here asks how the phone reaches this Mac. It used to - three answers, and two real
        // setups in two days picked the wrong one. The bridge now serves TLS and plain HTTP on its
        // one port, decided per connection, and the phone tries TLS first and remembers what worked.

        // The message row is updated in place by `sayAboutAddress`, so a keystroke never rebuilds the
        // boxes it was typed into.
        let message = body(addressMessage)
        messageLabel = message
        stack.addArrangedSubview(message)
        message.isHidden = addressMessage.isEmpty
        if offersSuffixConfirmation {
            let confirm = NSButton(title: "Use it anyway", target: self, action: #selector(confirmSuffixTapped))
            confirm.bezelStyle = .rounded
            stack.addArrangedSubview(confirm)
        }

        stack.addArrangedSubview(separator())

        // The code, or the phone, or the one sentence that says why neither.
        if let paired {
            stack.addArrangedSubview(heading("Paired with \(MenuModel.describe(paired))"))
            stack.addArrangedSubview(caption("Fingerprint \(paired.fingerprint) - the phone shows the same one."))
            let unpair = NSButton(title: "Unpair", target: self, action: #selector(unpairTapped))
            unpair.bezelStyle = .rounded
            stack.addArrangedSubview(unpair)
        } else if !hasAddress {
            stack.addArrangedSubview(body("Save an address to get a pairing code."))
        } else {
            stack.addArrangedSubview(codePanel.make(state: panelState, address: field.text))
            if let lastRefusal {
                stack.addArrangedSubview(caption("The last phone that tried was turned away: \(lastRefusal)"))
            }
        }

        if !bridgeLine.isEmpty { stack.addArrangedSubview(caption(bridgeLine)) }
        return stack
    }

    private var messageLabel: NSTextField?

    /// Replace the sentence under the boxes without touching the boxes.
    func sayAboutAddress(_ sentence: String) {
        addressMessage = sentence
        guard let messageLabel else { return }
        messageLabel.stringValue = sentence
        messageLabel.isHidden = sentence.isEmpty
    }

    @objc private func confirmSuffixTapped() { onConfirmSuffix?() }

    @objc private func unpairTapped() { onUnpair?() }

    // MARK: - Typing

    /// Both boxes report through here, on every change. `NSTextFieldDelegate` delivers the text
    /// field's own notification, so this is not a key monitor and paste counts as typing.
    func controlTextDidChange(_ note: Notification) {
        onEdited?(addressBox?.stringValue ?? "", portBox?.stringValue ?? "")
    }

    // MARK: - Pieces

    private func heading(_ text: String) -> NSTextField {
        let f = NSTextField(labelWithString: text)
        f.font = .boldSystemFont(ofSize: 15)
        return f
    }

    private func label(_ text: String) -> NSTextField {
        let f = NSTextField(labelWithString: text)
        f.font = .systemFont(ofSize: 12, weight: .semibold)
        return f
    }

    private func caption(_ text: String) -> NSTextField {
        let f = NSTextField(wrappingLabelWithString: text)
        f.font = .systemFont(ofSize: 11)
        f.textColor = .secondaryLabelColor
        f.preferredMaxLayoutWidth = 470
        return f
    }

    private func body(_ text: String) -> NSTextField {
        let f = NSTextField(wrappingLabelWithString: text)
        f.font = .systemFont(ofSize: 12)
        f.preferredMaxLayoutWidth = 470
        return f
    }

    private func separator() -> NSView {
        let line = NSBox()
        line.boxType = .separator
        line.translatesAutoresizingMaskIntoConstraints = false
        line.widthAnchor.constraint(equalToConstant: 470).isActive = true
        return line
    }
}
