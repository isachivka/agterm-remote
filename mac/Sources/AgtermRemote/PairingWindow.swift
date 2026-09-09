import AgtermRemoteCore
import AppKit

/// The one pairing window: the address, then the code built from it.
///
/// **Every state renders something.** A window that opens blank is a mystery, not an affordance — the
/// same rule that cost the owner a pairing on 2026-08-10 when a menu item looked pressable and was not.
/// The three states from `PairingWindowState` each get a sentence and, where there is one, a code.
///
/// The address is above the code and always visible, because **the code is opaque to a human and the
/// address is the only part they can check**. Every code this laptop makes carries the same
/// fingerprint, so comparing fingerprints proves it came from here and says nothing about where the
/// phone will dial.
@MainActor
final class PairingWindow: NSObject, NSWindowDelegate {

    private var window: NSWindow?

    /// True while this window is on screen. **A save can arrive from the onboarding window**, and the
    /// app must be able to redraw what is open without opening what is not: `show` makes a window
    /// when there is none, so calling it to refresh would put a pairing code in front of somebody who
    /// asked for neither.
    var isOpen: Bool { window != nil }

    /// Handed what was typed: the address, and the port traffic arrives on at this Mac. The view
    /// never saves either: parsing, refusing and writing live in `AddressEdit`/`SaveAddress` and
    /// `ListenPortEdit`/`SaveListenPort`, where they are tested without a window.
    var onSave: ((String, String) -> Void)?

    private var field = AddressField()
    private var addressBox: NSTextField?
    private var portBox: NSTextField?

    /// What the address editor is saying right now — a refusal, or what was saved. Held here rather
    /// than in the view, because `show` rebuilds the window's contents around it and a sentence
    /// somebody is part-way through reading must not be wiped by a redraw.
    private var addressMessage = ""

    /// Opens, or brings forward what is already open. Rebuilds the contents each time, so a code minted
    /// after an address change is never a stale picture of the old one.
    ///
    /// - Parameters:
    ///   - field: what the editable box starts with, read from the saved address rather than
    ///     remembered.
    ///   - focusAddress: true when the owner arrived by *Set the address…*. **Both menu items open this
    ///     same window** — one address, one screen, two doors that lead somewhere identical rather than
    ///     somewhere similar — and the only difference is where the cursor lands.
    func show(_ state: PairingWindowState, field: AddressField, focusAddress: Bool = false) {
        self.field = field
        let window = self.window ?? make()
        window.contentView = view(for: state)
        window.delegate = self
        // This app is an accessory: without activating, the window opens behind whatever the owner is
        // looking at, which for a thing they asked to see is the same as not opening.
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        window.center()
        // After the window is key, or the field is focused in a window nobody is typing into.
        if focusAddress, let addressBox { window.makeFirstResponder(addressBox) }
        self.window = window
    }

    private func make() -> NSWindow {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 460, height: 620),
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

    func windowWillClose(_: Notification) {
        window = nil
    }

    // MARK: - The three states

    private func view(for state: PairingWindowState) -> NSView {
        let stack = NSStackView()
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 12
        stack.edgeInsets = NSEdgeInsets(top: 20, left: 20, bottom: 20, right: 20)

        switch state {
        case .noAddress(let explanation):
            stack.addArrangedSubview(heading("No address is set"))
            stack.addArrangedSubview(body(explanation))
            stack.addArrangedSubview(addressEditor())

        case .codeUnavailable(let address, let explanation):
            stack.addArrangedSubview(caption("This phone will connect to"))
            stack.addArrangedSubview(addressLabel(address))
            stack.addArrangedSubview(addressEditor())
            stack.addArrangedSubview(heading("The code could not be made"))
            stack.addArrangedSubview(body(explanation))

        case .ready(let address, let imagePath, let unproven):
            stack.addArrangedSubview(caption("This phone will connect to"))
            stack.addArrangedSubview(addressLabel(address))
            stack.addArrangedSubview(addressEditor())
            stack.addArrangedSubview(code(at: imagePath))
            stack.addArrangedSubview(
                body("Point the phone at this code. Check the address above matches what the phone shows "
                    + "before you confirm — the fingerprint is the same in every code this laptop makes, "
                    + "so the address is the part that can be wrong."))
            for line in unproven {
                stack.addArrangedSubview(warning(line))
            }
        }

        // There is no second half to this screen and no second step for the owner. The phone proves
        // itself to the bridge during enrolment, over the wire, in the same act as scanning the code -
        // so there is no certificate to carry back by hand and nowhere to put it if there were.
        return stack
    }

    // MARK: - The address, editable, on the same screen

    /// **One box and a button**, on every state of this window.
    ///
    /// The label above shows the address as it is saved right now; this shows what is being typed.
    /// Keeping both means the value the phone will be compared against is never hidden behind an edit
    /// in progress — the address is the only part of a pairing a human can actually check.
    ///
    /// **One field, holding `host:port`, because that is what the address IS.** It was two boxes for a
    /// day; the phone shows one string, the code carries one string, the comparison screen shows one
    /// string, and the single place it was entered was the place it stopped being one value. The tell
    /// was that typing what every other surface displays produced a validation error.
    private func addressEditor() -> NSView {
        let box = NSTextField(string: field.text)
        box.placeholderString = "agterm.your-homelab.example:8443"
        box.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        box.translatesAutoresizingMaskIntoConstraints = false
        box.widthAnchor.constraint(equalToConstant: 340).isActive = true
        addressBox = box

        let save = NSButton(title: "Save", target: self, action: #selector(saveTapped))
        save.bezelStyle = .rounded
        save.keyEquivalent = "\r"

        let row = NSStackView(views: [box, save])
        row.orientation = .horizontal
        row.spacing = 8

        // **The second port, and it is a field rather than a derivation.** A router that publishes
        // one port and delivers to another is ordinary, and taking the bind from the dial address
        // makes the bridge listen where nothing arrives - a perfect code and a phone that never
        // connects. Empty means the two are the same, which is the straight-through case.
        let port = NSTextField(string: arrivalPortText)
        port.placeholderString = "same as above"
        port.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        port.translatesAutoresizingMaskIntoConstraints = false
        port.widthAnchor.constraint(equalToConstant: 120).isActive = true
        portBox = port

        let column = NSStackView(views: [
            caption("The address your phone will dial"), row,
            caption(OnboardingCopy.arrivalPortHeading), port,
        ])
        column.orientation = .vertical
        column.alignment = .leading
        column.spacing = 4
        // A refusal has to be visible without scrolling or hovering; it is the whole point of refusing
        // in words rather than in silence.
        if !addressMessage.isEmpty { column.addArrangedSubview(body(addressMessage)) }
        return column
    }

    /// What the arrival-port box starts with, read from the store rather than remembered. Empty when
    /// it follows the dial port, which is what an empty box means when it is saved.
    var arrivalPortText = ""

    @objc private func saveTapped() {
        onSave?(addressBox?.stringValue ?? "", portBox?.stringValue ?? "")
    }

    /// What the editor says after a save or a refusal. Set by the app, from the outcome — the view does
    /// not decide whether something was written.
    func reportOnAddress(_ message: String) {
        addressMessage = message
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

    private func warning(_ text: String) -> NSTextField {
        let field = NSTextField(wrappingLabelWithString: text)
        field.font = .systemFont(ofSize: 12)
        field.preferredMaxLayoutWidth = 420
        return field
    }

    /// The code, big enough to scan from a phone held in front of the screen.
    ///
    /// If the file cannot be read the window says so rather than showing an empty square — a blank
    /// where a code should be is the worst of both: it looks like it worked and cannot be scanned.
    private func code(at path: String) -> NSView {
        guard let image = NSImage(contentsOfFile: path) else {
            return body("The code was written to \(path) but could not be read back.")
        }
        let view = NSImageView(image: image)
        view.imageScaling = .scaleProportionallyUpOrDown
        // 380pt of a 776px PNG. A phone camera needs pixels per module, and this is the whole reason
        // the window is this wide.
        view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            view.widthAnchor.constraint(equalToConstant: 380),
            view.heightAnchor.constraint(equalToConstant: 380),
        ])
        return view
    }
}
