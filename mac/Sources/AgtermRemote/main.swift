import AgtermRemoteCore
import AppKit

/// The menu-bar shell.
///
/// Every item but Quit was once lit and inert, and on 2026-08-10 the owner pressed two of them and
/// nothing happened. **Both halves of that are fixed here**: `implemented` decides what is pressable
/// AND what is wired, and *Show the pairing code* opens a real window. The items still missing are
/// absent from that set, so they are greyed rather than pressable.
///
/// ### No window at launch, and no Dock icon
///
/// Declared twice on purpose. `LSUIElement` in `Info.plist` is what macOS reads when the app is
/// launched as a bundle; `setActivationPolicy(.accessory)` is what applies when it is run straight
/// from `swift run`, which is how it will be run all through development. Relying on only the plist
/// would mean every developer run behaves differently from every real one.
///
/// This once said *"nothing here creates an NSWindow"*, which stopped being true when the pairing
/// screen arrived. **A window is opened only when the owner asks for one** — never at launch, which
/// is the property that sentence was really protecting.
@MainActor
final class MenuBarApp: NSObject, NSApplicationDelegate {

    private var item: NSStatusItem?
    private let pairing = PairingWindow()
    private let onboarding = OnboardingWindow()

    /// **The single source of what is pressable and what is wired.** An action absent here gets no
    /// handler AND no enabled item, because `MenuModel.items` is given the same set. They cannot come
    /// apart, which is the structural version of the rule the owner met the hard way: on 2026-08-10
    /// every item but Quit was lit and inert.
    ///
    /// Start and Stop are in it **only when there is a bridge to start**. Until this change they were
    /// permanently in it, over a supervisor that asked launchd about a job no installer here creates —
    /// lit, pressable, and incapable. A missing binary is not a reason to offer the control and
    /// apologise afterwards; it is a reason for the control to look as dead as it is.
    private var implemented: Set<MenuAction> {
        var actions: Set<MenuAction> = [.quit, .showPairingCode, .setAddress, .startAtLogin]
        if bridge != nil { actions.formUnion([.startBridge, .stopBridge]) }
        return actions
    }

    private let loginItem = LoginItem(service: SystemLoginItem())

    /// The bridge, as a child process. `nil` when the binary is not where it should be — which is a
    /// state the menu shows by greying two items rather than by failing at the press.
    ///
    /// Resolved once, at launch. A menu whose enabled items depended on a file system lookup
    /// performed on every rebuild would change under somebody mid-press; the three places it looks
    /// are `BridgeProcess.locate`.
    private let bridge: BridgeProcess?

    /// The bridge's state directory, and this app's only claim about where the owner's identity
    /// lives. The bridge requires this and defaults it to nothing on purpose, so somebody has to
    /// decide — and the app that spawns it is the one holding that answer.
    static let stateDirectory = FileManager.default.homeDirectoryForCurrentUser
        .appending(path: ".config/agterm-remote")

    override init() {
        let found = Self.findBridge()
        bridge = found.map {
            BridgeProcess(
                launcher: ChildProcessLauncher(), executable: $0, stateDir: Self.stateDirectory)
        }
        super.init()
        // Every transition rebuilds the menu, on the main thread. The state changes on whatever
        // thread the child died on, which is never this one.
        bridge?.onStateChange = { [weak self] state in
            DispatchQueue.main.async { self?.bridgeChanged(to: state) }
        }
    }

    /// Where the bridge is. The order and the reasoning live in `BridgeProcess.locate`, in the
    /// library, so they are asserted by a test rather than by whoever next reads this file.
    private static func findBridge() -> URL? {
        BridgeProcess.locate(
            resources: Bundle.main.resourceURL,
            beside: URL(fileURLWithPath: CommandLine.arguments[0]),
            stateDir: stateDirectory)
    }

    func applicationDidFinishLaunching(_: Notification) {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        // Before the first check there is nothing to claim, and `notChecked` is a state with its own
        // glyph rather than an optimistic default.
        apply(.notChecked, to: item)
        self.item = item
        // The title says what the SYSTEM says, from the first draw: reading it here rather than
        // assuming false means the menu is never briefly wrong after a relaunch.
        rebuildMenu()
        watchForTermination()
        // **The one window that opens without being asked for**, and only while the three things do
        // not all hold. A person who has finished setting up never sees it again; a person who has
        // not cannot be expected to know that the way in is a menu-bar icon they have never met.
        showOnboardingIfUnfinished()
    }

    /// The three facts, as they stand right now.
    ///
    /// Each is read fresh rather than remembered: agterm can be quit, an address can be cleared in
    /// System Settings, and the bridge writes its trust store without telling this process. A cached
    /// answer to any of the three is how an app comes to say something that stopped being true.
    private func onboardingNow() -> Onboarding {
        // The trust store is the bridge's own record that a phone completed enrolment. Credited to
        // the address only if it happened after that address was stored - the rule is in
        // `AddressPreference.recordEnrolment`, and it is why a new address starts unproven.
        AddressPreference.recordEnrolment(
            at: EnrolmentRecord.recordedAt(inStateDirectory: Self.stateDirectory))
        return Onboarding(
            // Named for the socket in the design document; what this app may look at is the running
            // application. See `AgtermPresence` - the socket is on the far side of a boundary this
            // app does not cross.
            agtermSocketExists: AgtermPresence.isRunning(),
            address: AddressPreference.stored(),
            isPaired: AddressPreference.provenAt() != nil)
    }

    private func showOnboardingIfUnfinished() {
        let now = onboardingNow()
        guard now.step != .done else { return }
        showOnboarding(now)
    }

    private func showOnboarding(_ now: Onboarding) {
        // One save path for the whole app: both windows hand the typed string to the same function,
        // which is the same `SaveAddress` with the same refusals. Two savers would be two opinions
        // about what an address is.
        onboarding.onSave = { [weak self] typed in self?.saveAddress(typed) }
        onboarding.onShowPairingCode = { [weak self] in self?.openPairing(focusAddress: false) }
        onboarding.onRecheck = { [weak self] in
            guard let self else { return }
            let again = onboardingNow()
            onboarding.show(again, field: AddressField(AddressPreference.read()))
            rebuildMenu()
        }
        onboarding.show(now, field: AddressField(AddressPreference.read()))
    }

    /// **The bridge dies with this app, and that is stated three times because two of them fail.**
    ///
    /// `applicationWillTerminate` covers Quit and a logout. It does NOT cover `kill`, a crash, or a
    /// force-quit, and a menu-bar app is killed that way more often than most: nobody sees a window
    /// disappear, so nobody notices it did not shut down. The `SIGTERM` source covers the kill.
    /// Neither covers `SIGKILL` or a crash, and nothing running in this process ever can — which is
    /// why the bridge polls `--parent-pid` and exits on its own when this pid goes. It polls every
    /// couple of seconds, so a crashed app leaves a bridge alive for up to that long. That is the
    /// shape of the guarantee, not a hole in it: the alternative is a listener on the port the owner
    /// deliberately exposed to the internet with no user interface left anywhere that could close it.
    private func watchForTermination() {
        // Ignored first, and that ordering is the whole trick: a dispatch source observes a signal
        // rather than replacing its disposition, so without this the default action terminates the
        // process before the handler is ever reached.
        signal(SIGTERM, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
        source.setEventHandler { [weak self] in
            self?.bridge?.stop()
            // Not NSApp.terminate: a modal alert on screen would swallow it, and something that was
            // sent SIGTERM has already been told to go rather than asked.
            exit(0)
        }
        source.resume()
        sigterm = source
    }

    /// Held so the source outlives the function that made it. A dispatch source with no strong
    /// reference is cancelled, and the handler above would then never run.
    private var sigterm: DispatchSourceSignal?

    func applicationWillTerminate(_: Notification) {
        bridge?.stop()
    }

    /// The certificate helper that renders a pairing code. Named once.
    ///
    /// This is the shape the app had before it could speak to the bridge directly. The bridge in this
    /// repository answers `pair-open` on its control socket, and the task that builds the pairing
    /// panel replaces this: a code will be asked for over that socket rather than by running a second
    /// binary the owner has to have built.
    private var certificateTool: String {
        FileManager.default.homeDirectoryForCurrentUser
            .appending(path: ".config/agterm-bridge/bin/bridgecert").path
    }

    @objc private func showPairingCode() { openPairing(focusAddress: false) }

    /// *Set the address…* and *Show the pairing code…* are **two doors into one window**.
    ///
    /// The owner asked for a single pairing screen holding the code and the certificate; the address
    /// belongs on it for the same reason, because a code and the address it encodes are one fact. The
    /// menu item stays because that is where somebody looks for it, and it lands on the same screen
    /// with the cursor in the host box — somewhere identical, not somewhere similar.
    @objc private func setAddress() { openPairing(focusAddress: true) }

    private func openPairing(focusAddress: Bool) {
        // `shell`, not a second copy of it: one place starts a process, so the allow-list in
        // BoundaryTests has one thing to check and two runners cannot drift apart.
        let maker = PairingCodeMaker(binary: certificateTool, run: shell)
        pairing.onSave = { [weak self] typed in self?.saveAddress(typed) }
        // The status is nil until the three facts are checked, which is its own commit. The window
        // says "not checked yet" rather than implying the address is good.
        let address = AddressPreference.read()
        current = PairingWindowModel.state(address: address, status: nil, makeCode: maker.make)
        pairing.show(current, field: AddressField(address), focusAddress: focusAddress)
    }

    /// What the window is showing, so a save can be told what to leave alone when it refuses.
    private var current: PairingWindowState = .noAddress(explanation: PairingWindowModel.noAddressExplanation)

    /// **Saving is one step: write, then re-make the code from what was written.**
    ///
    /// A code on screen built from an address that has changed underneath it is scannable, pairs, and
    /// points the phone at the previous destination — worse than no code at all. `afterSaving` does
    /// both halves or neither, and passes `status: nil` so the new address is unproven until something
    /// answers on it and presents our certificate.
    private func saveAddress(_ typed: String, confirmed: Bool = false) {
        let maker = PairingCodeMaker(binary: certificateTool, run: shell)
        let (outcome, state) = PairingWindowModel.afterSaving(
            address: typed, using: SaveAddress(), makeCode: maker.make, unchanged: current,
            confirmed: confirmed)

        switch outcome {
        case .saved(let address):
            report(
                "Saved. Your phone will dial \(address.displayed), and the code below is built from it. "
                    + "Nothing has answered there yet — that is checked separately.")
        case .refused(let sentence), .notWritten(let sentence):
            report(sentence)

        case .needsConfirmation(let explanation, let question):
            // **Deliberate, not impossible.** A two-label name is a valid destination that looks like
            // the mistake of 2026-08-09, so it costs a second act rather than being prohibited — the
            // same shape as deleting a session on the phone.
            report(explanation)
            if confirmSuffix(explanation: explanation, question: question) {
                return saveAddress(typed, confirmed: true)
            }
        }

        current = state
        // After a save the field comes from the store, read back rather than assumed. After a refusal
        // it keeps what was typed: wiping somebody's text because it was wrong makes them retype it
        // from memory, which is how the wrong address gets entered twice.
        let field = switch outcome {
        // Read back from the store rather than echoed, so the box shows what was actually saved, in
        // the same rendering the phone will show.
        case .saved: AddressField(AddressPreference.read())
        // A declined confirmation keeps their text for the same reason a refusal does: they may be
        // about to add the missing label to the front of it.
        case .refused, .notWritten, .needsConfirmation: AddressField(text: typed)
        }
        // **Only the windows that are already open are redrawn.** Both of them can save, and a save
        // from one must not conjure the other: somebody on the second onboarding step who presses
        // Save has not asked to see a pairing code, and a window arriving unasked over their work is
        // the same rudeness as a modal alert.
        if pairing.isOpen { pairing.show(state, field: field) }
        // The address they just typed may have been the second onboarding step, and a pane that
        // stayed on "type an address" after one was saved would be showing a state that has stopped
        // being true.
        if onboarding.isOpen { onboarding.show(onboardingNow(), field: field) }
        // The menu's "Show the pairing code…" is enabled by whether an address exists, and one may have
        // just started existing.
        rebuildMenu()
    }

    /// One sentence, put in front of whoever is looking. The two windows edit the same address
    /// through the same saver, so they say the same thing about it.
    private func report(_ sentence: String) {
        pairing.reportOnAddress(sentence)
        onboarding.reportOnAddress(sentence)
    }

    /// **Stop, with the cost said BEFORE the press takes effect.**
    ///
    /// Stopping cuts any phone connected through the bridge, and nothing brings it back: there is no
    /// supervisor above this app and no job anywhere that outlives it. The Start item is the only way
    /// back. The owner confirms that sentence first — afterwards would be an apology, not a warning.
    @objc private func stopBridge() {
        guard let bridge else { return }
        let confirm = NSAlert()
        confirm.messageText = "Stop the bridge?"
        confirm.informativeText =
            "Any phone connected through it drops immediately, and nothing brings it back by itself — "
                + "you start it again from this menu."
        confirm.addButton(withTitle: "Stop it")
        confirm.addButton(withTitle: "Cancel")
        confirm.alertStyle = .warning
        NSApp.activate(ignoringOtherApps: true)
        guard confirm.runModal() == .alertFirstButtonReturn else { return }

        bridge.stop()
    }

    /// Start it.
    ///
    /// ### The port is the only half of the dial address that is also a local fact
    ///
    /// The bridge binds an address; the phone dials a different one, and confusing the two is what
    /// produced a code that paired and then never connected. So the host is not reused: the bridge is
    /// bound on every interface, and the stored dial address contributes only its port — the one
    /// number that has to agree on both sides for a forwarded connection to land here.
    ///
    /// Without a stored address there is no port and nothing to bind, so the owner is sent to the
    /// screen that fixes that instead of being shown a failure.
    @objc private func startBridge() {
        guard let bridge else { return }
        guard case .success(let address) = AddressPreference.read() else {
            notify(
                "There is no address yet.",
                "The bridge listens on the port your phone will dial, and that port comes from the "
                    + "address you set. Set it first, from this menu.")
            return openPairing(focusAddress: true)
        }
        do {
            // **`Address.listen`, not a string built here.** The dial address and the listen address
            // are different addresses, and the decision about how one becomes the other - the port
            // travels, the host never does - lives on the value with the reasoning attached to it.
            try bridge.start(listen: Address(address).listen, socket: nil)
        } catch {
            // A start that could not happen at all: the binary vanished between launch and now, or
            // macOS is holding it because the app arrived by download. The error's own words, not a
            // summary of them — a quarantine refusal is a paragraph carrying the command that fixes
            // it, and there is nowhere else the owner would ever see it.
            //
            // `copyable` is the command, when there is one. See `notify`.
            notify(
                "The bridge would not start.", "\(error)",
                copyable: (error as? BridgeProcess.Failure)?.copyable)
        }
    }

    /// **What the bridge did, not what we asked it to do — said without taking the screen.**
    ///
    /// Every transition arrives here, including the ones nobody pressed: a bridge that dies on its
    /// own backs off, retries, and eventually gives up.
    ///
    /// The giving up used to raise an `NSAlert` after `activate(ignoringOtherApps:)`. Measured at
    /// **9.57 seconds** from the press — the full ladder — which is a focus-stealing modal arriving
    /// ten seconds after somebody touched a background menu and went back to their work. It is now
    /// a line at the top of the menu and the status item's tooltip: in the place they will look when
    /// they notice, rather than on top of whatever they were doing when they did not.
    private func bridgeChanged(to state: BridgeState) {
        if case .failed(let sentence) = state {
            failure = sentence
        } else if case .running = state {
            failure = nil
        }
        rebuildMenu()
    }

    /// The last thing the bridge said on its way out, or nil. Shown, never announced.
    private var failure: String?

    /// Rebuilt after anything that changes what the menu should say. The titles come from the model,
    /// and the model is given the system's answer rather than our memory of it.
    private func rebuildMenu() {
        // Asked, not assumed — and now there is somewhere to ask. The bridge is this app's own child,
        // so this is whether we hold a live pid rather than an opinion we are keeping.
        //
        // **Passed through as three states rather than flattened to a bool**, because `.starting` is
        // no longer only the retry ladder: it is also the window between spawning a child and that
        // child announcing a bound listener, which for a binary macOS has frozen never ends.
        // Flattening it to `running` told the owner a held bridge was Running for the whole ceiling.
        //
        // Stop stays available across both, and that is the point rather than a shortcut: the owner
        // can watch a start fail and call it off. Start is greyed in the same window, which is right
        // — one is already in flight. `MenuModel` derives both from this.
        let running: BridgeStatus.Running = switch bridge?.state {
        case .running: .running
        case .starting: .starting
        default: .notRunning
        }
        item?.menu = menu(
            status: BridgeStatus(
                address: DialAddress(host: "-", port: 0),
                running: running,
                reachable: .noAnswer("not checked"),
                identified: .notEstablished,
            ),
            hasAddress: AddressPreference.read().isSuccess,
            launchesAtLogin: loginItem.status() == .registered,
        )
        // The whole sentence, where a tooltip can hold what a menu item cannot. Still nothing that
        // takes focus.
        if let failure { item?.button?.toolTip = failure }
    }

    /// Says something, always. An alert rather than a notification: this app has no notification
    /// permission and asking for one to report a menu toggle would be a second permission for a
    /// sentence.
    ///
    /// ### `copyable`, and why an alert needed one
    ///
    /// `NSAlert.informativeText` is **not selectable**. A shell command that appears only there is a
    /// command the owner retypes by hand off the screen — and the one this exists for is
    /// `xattr -dr com.apple.quarantine "/Applications/AgtermRemote.app"`, a line with a quoted
    /// absolute path in it, typed from memory into a shell. Mistyping it is silent: they get an error
    /// or, worse, clear the attribute from the wrong path.
    ///
    /// So the command gets an accessory view of its own: a selectable, monospaced field with a Copy
    /// button beside it. The button lives inside the accessory rather than being a third alert
    /// button, because an alert button dismisses the alert — copying would close the window that
    /// explains what the copied thing is for.
    private func notify(_ message: String, _ detail: String, copyable: String? = nil) {
        let alert = NSAlert()
        alert.messageText = message
        alert.informativeText = detail
        alert.alertStyle = .informational
        if let copyable { alert.accessoryView = Self.copyableField(copyable) }
        NSApp.activate(ignoringOtherApps: true)
        alert.runModal()
    }

    /// A selectable field holding `text`, and a button that puts it on the pasteboard.
    ///
    /// **Known and not fixed here:** with a very long path the fixed 420-point row can leave the
    /// layout ambiguous rather than truncating cleanly. The Copy button is unaffected and is the
    /// path that matters — but somebody with a deeply nested application directory may see the field
    /// laid out oddly. Recorded rather than papered over.
    private static func copyableField(_ text: String) -> NSView {
        let field = NSTextField(labelWithString: text)
        field.font = .monospacedSystemFont(ofSize: NSFont.smallSystemFontSize, weight: .regular)
        // Selectable, so somebody who prefers the mouse can drag it out; the button is for everyone
        // else. `isEditable` stays false — this is not somewhere to type.
        field.isSelectable = true
        field.lineBreakMode = .byTruncatingMiddle
        field.translatesAutoresizingMaskIntoConstraints = false

        let copy = NSButton(title: "Copy", target: CopyTarget.shared, action: #selector(CopyTarget.copy(_:)))
        copy.bezelStyle = .rounded
        copy.identifier = NSUserInterfaceItemIdentifier(text)
        copy.translatesAutoresizingMaskIntoConstraints = false

        let row = NSView(frame: NSRect(x: 0, y: 0, width: 420, height: 24))
        row.addSubview(field)
        row.addSubview(copy)
        NSLayoutConstraint.activate([
            row.widthAnchor.constraint(equalToConstant: 420),
            row.heightAnchor.constraint(equalToConstant: 24),
            field.leadingAnchor.constraint(equalTo: row.leadingAnchor),
            field.centerYAnchor.constraint(equalTo: row.centerYAnchor),
            copy.leadingAnchor.constraint(equalTo: field.trailingAnchor, constant: 8),
            copy.trailingAnchor.constraint(equalTo: row.trailingAnchor),
            copy.centerYAnchor.constraint(equalTo: row.centerYAnchor),
        ])
        return row
    }

    /// Toggle "start at login", and **say what happened either way**.
    ///
    /// The title already reflects the system's answer rather than what we last did, because the owner
    /// can switch this off in System Settings while the app is running and a remembered `true` is how
    /// an app tells somebody something that stopped being true.
    ///
    /// Both directions report. A toggle that silently fails is indistinguishable from one that worked
    /// until the next reboot proves otherwise — which is the whole shape of defect this branch is
    /// about.
    @objc private func toggleStartAtLogin() {
        let registeredNow = loginItem.status() == .registered
        do {
            let after = registeredNow ? try loginItem.stopStartingAtLogin() : try loginItem.startAtLogin()
            switch after {
            case .registered:
                notify("Agterm Remote will start at login.", "Turn it off here, or in System Settings › General › Login Items.")
            case .notRegistered:
                notify("Agterm Remote will no longer start at login.", "It is gone from System Settings › General › Login Items.")
            case .awaitingApproval:
                notify("macOS is waiting for you to approve it.", "Open System Settings › General › Login Items and allow Agterm Remote.")
            case .unknown:
                notify("macOS reported a login-item state this app does not recognise.", "Check System Settings › General › Login Items.")
            }
        } catch {
            // Never silent. A failed toggle that says nothing is the same defect as a menu item that
            // looks pressable and is not.
            notify("That did not work.", "\(error)")
        }
        rebuildMenu()
    }

    /// The second act. **The refusal's own sentence is the body of the question**, not a shortened
    /// version of it: an alert that said only "save it anyway?" would ask them to decide without the
    /// reason in front of them, which is a confirmation in form and a shrug in substance.
    ///
    /// The default button is the cautious one. Return-to-confirm on a dialogue about the address the
    /// phone dials is how somebody agrees to something they did not read.
    private func confirmSuffix(explanation: String, question: String) -> Bool {
        let alert = NSAlert()
        alert.messageText = question
        alert.informativeText = explanation
        alert.alertStyle = .warning
        alert.addButton(withTitle: "Cancel")
        alert.addButton(withTitle: "Save it anyway")
        NSApp.activate(ignoringOtherApps: true)
        return alert.runModal() == .alertSecondButtonReturn
    }

    /// Runs the certificate helper and hands back what it said. Short-lived, waited on, and nothing
    /// like the bridge: that one is spawned and supervised by `ChildProcessLauncher`, which is why
    /// the two are separate rather than one runner asked to be both.
    private let shell: CommandRunner = { invocation in
        let task = Process()
        task.executableURL = URL(filePath: invocation.executable)
        task.arguments = invocation.arguments
        let output = Pipe()
        task.standardOutput = output
        task.standardError = output
        do { try task.run() } catch { return (127, "\(error)") }
        let text = String(decoding: output.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        task.waitUntilExit()
        return (task.terminationStatus, text.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    /// The glyph, and the same words for anyone who cannot see it.
    private func apply(_ state: IconState, to item: NSStatusItem) {
        item.button?.image = Mark.image(for: state)
        // No tint is set, anywhere. The states are five shapes; a status conveyed by hue is one the
        // owner cannot read at a glance and may not be able to read at all.
        item.button?.toolTip = state.describedAsWords
    }

    private func menu(status: BridgeStatus?, hasAddress: Bool, launchesAtLogin: Bool) -> NSMenu {
        let menu = NSMenu()
        // The failure, at the top, disabled, and in the owner's own words rather than a code. Outside
        // the loop below because it is not an action: nothing happens when it is pressed, and the
        // assertion that ties actions to handlers is about the things that do.
        if let failure {
            let line = NSMenuItem(title: Self.firstLine(of: failure), action: nil, keyEquivalent: "")
            line.isEnabled = false
            menu.addItem(line)
            menu.addItem(.separator())
        }
        // **The address, and the word unproven, in the place people look.**
        //
        // Nothing on this Mac can say whether that address reaches it from outside - the argument is
        // on the address pane of `OnboardingWindow`, beside the button that must never be added - so
        // the menu reports the only thing that is known: whether a phone has ever come through it.
        // Disabled, like the failure line above, because it is a fact rather than an action.
        if let addressLine = Self.addressLine() {
            let line = NSMenuItem(title: addressLine, action: nil, keyEquivalent: "")
            line.isEnabled = false
            menu.addItem(line)
            menu.addItem(.separator())
        }
        for model in MenuModel.items(
            status: status, hasAddress: hasAddress, launchesAtLogin: launchesAtLogin,
            implemented: implemented,
        ) {
            // Belt and braces on the rule, at the one place the two halves meet: if a selector exists
            // the item must be offered, and if none exists it must not be. A mismatch here is the
            // shipped defect returning, and it is cheaper to catch on the next launch than in a menu.
            assert(
                (selector(for: model.action) != nil) == implemented.contains(model.action),
                "\(model.action) is wired and unlit, or lit and unwired",
            )
            let entry = NSMenuItem(title: model.title, action: selector(for: model.action), keyEquivalent: "")
            entry.target = self
            entry.isEnabled = model.enabled
            menu.addItem(entry)
        }
        // NSMenu enables items by asking a validator unless told not to. Left on, every item would
        // appear enabled regardless of what MenuModel decided - and the model is the thing the
        // reachability test checks.
        menu.autoenablesItems = false
        return menu
    }

    /// The address and what is known about it, or nil when there is no address to say anything about.
    ///
    /// **Unproven is not a warning and not a failure.** It is the resting state of every address until
    /// a phone has come through it, and the word is chosen so that somebody reading the menu on a
    /// perfectly working setup is told what has and has not been established rather than being told
    /// something is wrong.
    private static func addressLine() -> String? {
        guard case .success(let address) = AddressPreference.read() else { return nil }
        guard let proven = AddressPreference.provenAt() else {
            return "\(address.displayed) — unproven"
        }
        return "\(address.displayed) — a phone paired through it on \(proven.formatted(date: .abbreviated, time: .shortened))"
    }

    /// One line for a menu, the whole of it for a tooltip. The bridge's own words can run to several
    /// lines and a menu item is one.
    private static func firstLine(of sentence: String) -> String {
        let first = sentence.split(separator: "\n").first.map(String.init) ?? sentence
        return first.count > 90 ? String(first.prefix(89)) + "\u{2026}" : first
    }

    /// Derived from the same `implemented` set the menu's enabled state is derived from, and asserted
    /// against it below: an action cannot be lit without a handler, or handled without being lit.
    private func selector(for action: MenuAction) -> Selector? {
        switch action {
        case .quit: #selector(quit)
        case .showPairingCode: #selector(showPairingCode)
        case .setAddress: #selector(setAddress)
        case .startAtLogin: #selector(toggleStartAtLogin)
        // Wired only when there is a bridge binary to run. This is the same condition `implemented`
        // uses and it has to be, because the assertion below holds them to each other: an item with a
        // handler must be offered, and an item with none must not be lit.
        case .startBridge: bridge == nil ? nil : #selector(startBridge)
        case .stopBridge: bridge == nil ? nil : #selector(stopBridge)
        // Not yet built. They are ABSENT from `implemented`, so they are also not enabled - a menu
        // item that looks pressable and is not is the defect the owner met on 2026-08-10.
        default: nil
        }
    }

    /// **Quitting is quitting.** No confirmation, no relaunch, no "are you sure".
    @objc private func quit() {
        NSApp.terminate(nil)
    }
}

private extension Result {
    var isSuccess: Bool { if case .success = self { true } else { false } }
}

/// The Copy button's target. A separate object because the alert's accessory view outlives no
/// particular menu action, and the text to copy travels on the button's own identifier rather than in
/// a captured closure — `NSButton`'s action is a selector, not a block.
@MainActor
final class CopyTarget: NSObject {
    static let shared = CopyTarget()

    @objc func copy(_ sender: NSButton) {
        guard let text = sender.identifier?.rawValue else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        // Says it happened. A button that looks identical before and after is one people press twice
        // and still do not trust.
        sender.title = "Copied"
        sender.isEnabled = false
    }
}

let app = NSApplication.shared
// The programmatic half of "no Dock icon, no window at launch". See the note on `MenuBarApp`.
app.setActivationPolicy(.accessory)
let menuBar = MenuBarApp()
app.delegate = menuBar
app.run()
