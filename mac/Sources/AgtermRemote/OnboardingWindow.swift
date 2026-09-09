import AgtermRemoteCore
import AppKit

/// The three things a person must get right before a phone can ever reach their Mac, one screen at a
/// time: **is agterm there, what address will the phone dial, and here is the code.**
///
/// ### One pane at a time, in the only order they can be established
///
/// Not a checklist with three ticks. Each step is a prerequisite for the next one — an address typed
/// for a terminal that is not running is a value nothing will read, and a code for an address that
/// does not exist is a code that cannot work — so `OnboardingStep` decides which single pane is on
/// screen and the others are not rendered at all. A person on step one is not shown a disabled
/// version of step two to look at and wonder about.
///
/// ### It opens at first launch and then stops
///
/// `main.swift` says a window is opened only when the owner asks for one. This is the one exception
/// the design document names, and it is narrow: the window opens at launch **only while the three
/// things do not all hold.** Once they do, this app is a menu-bar item and nothing else, and it stays
/// that way through every subsequent launch.
@MainActor
final class OnboardingWindow: NSObject, NSWindowDelegate {

    private var window: NSWindow?

    /// Handed what was typed, as one string. Saving is `SaveAddress`, reached through the same path
    /// the pairing window uses: **one address, one writer, one set of refusals.** A second save here
    /// would be a second place that decides what an address is.
    var onSave: ((String, String) -> Void)?
    /// Told the moment the owner picks, because every choice is valid and a Save button here would
    /// only be a way to forget to press it.
    var onFrontDoor: ((FrontDoor) -> Void)?

    /// Opens the pairing window. The code is rendered in one place, by the type that owns the
    /// invocation of `bridgecert`; drawing a second one here would be a second picture of the same
    /// payload and the two would drift.
    var onShowPairingCode: (() -> Void)?

    /// Re-reads the three facts and shows whichever pane they now call for. **Local facts only** —
    /// whether agterm is running, whether an address is stored, whether the bridge has recorded an
    /// enrolment. Nothing here reaches the network. See the note on the address pane.
    var onRecheck: (() -> Void)?

    private var field = AddressField()
    private var addressBox: NSTextField?
    private var portBox: NSTextField?
    private var frontDoorBox: NSPopUpButton?
    /// What the popup is showing. Seeded by the caller from the store before the pane is drawn.
    var frontDoor = FrontDoor.unset

    /// What the arrival-port box starts with. Empty means it follows the dial port.
    var arrivalPortText = ""

    /// What the address editor is saying right now — a refusal, or what was saved. Held here rather
    /// than in the view, because `show` rebuilds the contents around it and a sentence somebody is
    /// part-way through reading must not be wiped by a redraw.
    private var addressMessage = ""

    /// True while the window is on screen, so the app can refresh it after a save without opening it
    /// for somebody who closed it.
    var isOpen: Bool { window != nil }

    /// What the window is showing, so the pane can be rebuilt after a press without the app being
    /// asked for the three facts again.
    private var current = Onboarding(agtermSocketExists: false, address: nil, isPaired: false)

    /// Set by *Set the address anyway*. **Step 1 is not a locked door**: agterm's absence is a runtime
    /// state, and nothing about storing an address or pairing a phone needs the terminal to be
    /// running. Somebody whose agterm this app cannot recognise — a build run from source — would
    /// otherwise be held at a pane whose only button is *look again*, forever, with the whole of
    /// setup on the far side of it.
    private var pastAgterm = false

    func show(_ onboarding: Onboarding, field: AddressField) {
        self.field = field
        current = onboarding
        let pane = self.pane(for: onboarding)
        let window = self.window ?? make()
        window.contentView = view(for: onboarding, pane: pane)
        window.delegate = self
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        window.center()
        if pane == .address, let addressBox { window.makeFirstResponder(addressBox) }
        self.window = window
    }

    /// The ladder, unless the owner has walked past agterm — after which the pane is whatever is left
    /// to set up, which is a question agterm does not enter into.
    private func pane(for onboarding: Onboarding) -> OnboardingStep {
        pastAgterm ? onboarding.setup : onboarding.step
    }

    func close() {
        window?.close()
        window = nil
    }

    /// What the editor says after a save or a refusal. Set by the app, from the outcome — the view
    /// does not decide whether anything was written.
    func reportOnAddress(_ message: String) {
        addressMessage = message
    }

    private func make() -> NSWindow {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 480, height: 460),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false,
        )
        window.title = "Set up Agterm Remote"
        // Closing it must not end the app: this is an accessory with a menu-bar item, and quitting is
        // something the owner does from the menu, deliberately.
        window.isReleasedWhenClosed = false
        return window
    }

    func windowWillClose(_: Notification) {
        window = nil
    }

    // MARK: - The panes

    private func view(for onboarding: Onboarding, pane: OnboardingStep) -> NSView {
        let stack = NSStackView()
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 12
        stack.edgeInsets = NSEdgeInsets(top: 20, left: 20, bottom: 20, right: 20)
        stack.addArrangedSubview(caption(Self.position(of: pane)))

        switch pane {
        case .agtermMissing: agtermPane(into: stack)
        case .address: addressPane(into: stack)
        case .pairing: pairingPane(into: stack, address: onboarding.address)
        case .done: donePane(into: stack, address: onboarding.address)
        }
        return stack
    }

    /// Where somebody is, said in words. Three steps and the count is fixed, so "Step 2 of 3" is a
    /// promise about how much is left rather than a decoration.
    private static func position(of step: OnboardingStep) -> String {
        switch step {
        case .agtermMissing: "Step 1 of 3 — agterm"
        case .address: "Step 2 of 3 — the address"
        case .pairing: "Step 3 of 3 — the code"
        case .done: "Set up"
        }
    }

    /// **Step 1. Nothing else is offered, because nothing else would work.**
    ///
    /// This app does not talk to agterm and must not: every command a phone can cause argues its way
    /// past an allowlist inside the bridge, and a menu-bar app holding its own connection would walk
    /// around that allowlist entirely — which is why `BoundaryTests` fails the build if any source
    /// here so much as names agterm's control socket. So the question this pane can honestly ask is
    /// whether agterm is *running*, which is in any case the fact that matters: a socket file left
    /// behind by a crashed terminal is not a terminal.
    private func agtermPane(into stack: NSStackView) {
        stack.addArrangedSubview(heading("agterm is not running"))
        stack.addArrangedSubview(
            body("This app is a door onto agterm — the terminal on your phone is a session in the "
                + "one on this Mac. Without agterm running there is nothing on the other side of the "
                + "door, so there is nothing to set up yet."))
        stack.addArrangedSubview(
            body("Install agterm if you have not, and start it. You can set the rest up now either "
                + "way: storing an address and pairing a phone need nothing from agterm — the phone "
                + "simply has nothing to drive until it is running."))
        let onwards = NSButton(
            title: "Set the address anyway", target: self, action: #selector(pastAgtermTapped))
        onwards.bezelStyle = .rounded
        let row = NSStackView(views: [recheckButton("Look again"), onwards])
        row.orientation = .horizontal
        row.spacing = 8
        stack.addArrangedSubview(row)
    }

    /// **Step 2. The address, and the sentence that says whose job it is.**
    ///
    /// The copy is `OnboardingCopy`, held in the library so the list of routes is covered by a test
    /// and cannot quietly become an endorsement of one of them. This app integrates with no provider
    /// and recommends none.
    ///
    /// ### There is no button on this pane that tests the address, and there must not be one
    ///
    /// It is the obvious thing to add. Somebody types an address, presses Save, and gets no answer
    /// about whether it works; a tick or a cross right here looks like the missing half of the
    /// feature. **It is not, and adding it would make this app lie to people.** Two separate reasons,
    /// either of which is enough:
    ///
    ///  1. **A Mac cannot honestly test its own public address.** The check would have to leave this
    ///     machine, come back through the router, and land on the bridge. Behind a router that
    ///     hairpins, that succeeds whether or not anything outside can reach it — a green tick for an
    ///     address the phone will never get through on the mobile network. Behind a router that does
    ///     not hairpin, the identical, correctly forwarded address fails — a red cross on an address
    ///     that works perfectly from anywhere but here. The answer is wrong in both directions and the
    ///     program cannot tell which router it is behind. A button that is sometimes wrong is worse
    ///     than no button, because people believe it.
    ///  2. **A successful bind does not mean the port is yours**, so even the local half of such a
    ///     check cannot be trusted. Measured with `lsof` while Task 15 was being built: Go resolves
    ///     `0.0.0.0:PORT` to a dual-stack `[::]:PORT` socket, and an IPv4-only listener belonging to
    ///     something else coexists with it on the same port. A check reporting *I am listening* is
    ///     then true while a phone arriving over IPv4 reaches whatever else is there.
    ///
    /// What replaces it is the truth: the address is **unproven** until a phone actually connects
    /// through it, and the scan on the next pane is what proves it. That is a real end-to-end test
    /// performed by the only party that can perform it — the phone, from where the phone is.
    private func addressPane(into stack: NSStackView) {
        stack.addArrangedSubview(heading(OnboardingCopy.addressHeading))
        stack.addArrangedSubview(body(OnboardingCopy.addressExplanation))
        for route in OnboardingCopy.addressRoutes {
            stack.addArrangedSubview(body("•  \(route)"))
        }
        stack.addArrangedSubview(addressEditor())
        stack.addArrangedSubview(body(OnboardingCopy.addressIsUnprovenUntilAPhoneArrives))
        // **What the wildcard bind costs, to the person it costs it to.** Three of the four routes
        // above involve no port forward at all, so nobody can be assumed to have opened this port
        // deliberately - and the bridge opens it on every network this Mac joins regardless. It is
        // disclosure rather than an alarm: the pinned certificate is what stands in front of it.
        stack.addArrangedSubview(body(OnboardingCopy.addressExposure))
    }

    /// **Step 3. The code, and the warning that rides on it.**
    private func pairingPane(into stack: NSStackView, address: Address?) {
        stack.addArrangedSubview(heading("Scan the code with your phone"))
        if let address {
            stack.addArrangedSubview(caption("Your phone will connect to"))
            stack.addArrangedSubview(addressLabel(address.dial.displayed))
        }
        stack.addArrangedSubview(
            body("The code carries this address and this Mac's certificate. Check that what the phone "
                + "shows matches the address above before you confirm on the phone — every code this "
                + "Mac makes carries the same fingerprint, so the address is the part that can be "
                + "wrong."))
        stack.addArrangedSubview(
            body("Until a phone comes through, this address is unproven: nothing on this Mac can tell "
                + "you whether it reaches you from outside. The scan is what proves it."))

        let show = NSButton(title: "Pair a phone…", target: self, action: #selector(showCodeTapped))
        show.bezelStyle = .rounded
        show.keyEquivalent = "\r"
        let row = NSStackView(views: [show, recheckButton("Look again")])
        row.orientation = .horizontal
        row.spacing = 8
        stack.addArrangedSubview(row)
    }

    /// All three hold. It says which three, because "you are set up" is not something anybody can
    /// check, and this window's whole argument is that a claim nobody can check is worth nothing.
    private func donePane(into stack: NSStackView, address: Address?) {
        stack.addArrangedSubview(heading("A phone has connected through this Mac"))
        if let address {
            stack.addArrangedSubview(caption("Your phone connects to"))
            stack.addArrangedSubview(addressLabel(address.dial.displayed))
        }
        stack.addArrangedSubview(
            body("agterm is running, the address is set, and a phone has completed pairing through it "
                + "— which is the only thing that ever proves an address. Everything from here lives "
                + "in the menu bar."))
        let done = NSButton(title: "Done", target: self, action: #selector(closeTapped))
        done.bezelStyle = .rounded
        done.keyEquivalent = "\r"
        stack.addArrangedSubview(done)
    }

    // MARK: - Pieces

    /// One box and a button, prefilled from the store rather than from memory — the same shape and
    /// the same rendering as the pairing window's, because it is the same address.
    private func addressEditor() -> NSView {
        let box = NSTextField(string: field.text)
        box.placeholderString = OnboardingCopy.addressPlaceholder
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

        // **The second port.** See `OnboardingCopy.arrivalPortExplanation`: the address above is the
        // router's and this is where that traffic comes out on this Mac. Empty is the straight-through
        // answer and follows the address, so anybody with a plain port forward can ignore this field.
        let port = NSTextField(string: arrivalPortText)
        port.placeholderString = "same as above"
        port.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        port.translatesAutoresizingMaskIntoConstraints = false
        port.widthAnchor.constraint(equalToConstant: 120).isActive = true
        portBox = port

        // **What stands in front, which this Mac cannot observe and must ask.**
        //
        // See FrontDoor: it decides how the phone opens this address and whether the bridge serves
        // TLS on its own port. Both constants that answered it before were wrong for somebody, and
        // the failure is a code that pairs nowhere with nothing anywhere saying why.
        //
        // A popup rather than a checkbox, because there are three answers and the third one is the
        // one nobody would guess at: it names the symptom - a 502 - rather than the mechanism.
        let door = NSPopUpButton(frame: .zero, pullsDown: false)
        for choice in FrontDoor.allCases {
            door.addItem(withTitle: FrontDoorCopy.label(for: choice))
            door.lastItem?.representedObject = choice.rawValue
        }
        door.selectItem(at: FrontDoor.allCases.firstIndex(of: frontDoor) ?? 0)
        door.target = self
        door.action = #selector(frontDoorChanged(_:))
        door.translatesAutoresizingMaskIntoConstraints = false
        door.widthAnchor.constraint(equalToConstant: 340).isActive = true
        frontDoorBox = door

        let column = NSStackView(views: [
            row, caption(OnboardingCopy.arrivalPortHeading), port,
            body(OnboardingCopy.arrivalPortExplanation),
            caption(FrontDoorCopy.heading), body(FrontDoorCopy.explanation), door,
            body(FrontDoorCopy.detail(for: frontDoor)),
        ])
        column.orientation = .vertical
        column.alignment = .leading
        column.spacing = 4
        // A refusal has to be visible without scrolling or hovering; it is the whole point of
        // refusing in words rather than in silence.
        if !addressMessage.isEmpty { column.addArrangedSubview(body(addressMessage)) }
        return column
    }

    /// Re-reads the three local facts. Named for what it does — it looks again at this Mac — rather
    /// than for something it cannot do.
    private func recheckButton(_ title: String) -> NSButton {
        let button = NSButton(title: title, target: self, action: #selector(recheckTapped))
        button.bezelStyle = .rounded
        return button
    }

    /// Stored on the change rather than on Save, because there is no way to get it wrong: every
    /// choice is valid, and the only thing a Save button would add is a way to forget to press it.
    @objc private func frontDoorChanged(_ sender: NSPopUpButton) {
        guard let raw = sender.selectedItem?.representedObject as? String,
            let chosen = FrontDoor(rawValue: raw)
        else { return }
        frontDoor = chosen
        onFrontDoor?(chosen)
        // Redrawn so the detail under the popup describes what is now selected. The third answer's
        // detail is the whole reason somebody would pick it.
        show(current, field: field)
    }

    @objc private func saveTapped() { onSave?(addressBox?.stringValue ?? "", portBox?.stringValue ?? "") }
    @objc private func showCodeTapped() { onShowPairingCode?() }
    @objc private func recheckTapped() { onRecheck?() }

    /// Walks past step 1. Nothing is stored and nothing is claimed about agterm — the pane simply
    /// stops being in the way, and the menu keeps saying agterm is not running for as long as it is
    /// not.
    @objc private func pastAgtermTapped() {
        pastAgterm = true
        show(current, field: field)
    }
    @objc private func closeTapped() { close() }

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

    /// Monospaced, selectable and **never truncated**: two addresses one label apart, where one is a
    /// suffix of the other, become the same string the moment an ellipsis lands in the wrong place.
    private func addressLabel(_ text: String) -> NSTextField {
        let field = NSTextField(labelWithString: text)
        field.font = .monospacedSystemFont(ofSize: 14, weight: .regular)
        field.isSelectable = true
        field.lineBreakMode = .byWordWrapping
        field.maximumNumberOfLines = 3
        field.preferredMaxLayoutWidth = 430
        return field
    }

    private func body(_ text: String) -> NSTextField {
        let field = NSTextField(wrappingLabelWithString: text)
        field.font = .systemFont(ofSize: 12)
        field.textColor = .secondaryLabelColor
        field.preferredMaxLayoutWidth = 430
        return field
    }
}
