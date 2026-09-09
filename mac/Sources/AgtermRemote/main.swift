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

    /// **The single source of what is pressable and what is wired.** An action absent here gets no
    /// handler AND no enabled item, because `MenuModel.items` is given the same set. They cannot come
    /// apart, which is the structural version of the rule the owner met the hard way: on 2026-08-10
    /// every item but Quit was lit and inert.
    private var implemented: Set<MenuAction> {
        [.quit, .showPairingCode, .setAddress, .startAtLogin, .startBridge, .stopBridge]
    }

    private let loginItem = LoginItem(service: SystemLoginItem())

    func applicationDidFinishLaunching(_: Notification) {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        // Before the first check there is nothing to claim, and `notChecked` is a state with its own
        // glyph rather than an optimistic default.
        apply(.notChecked, to: item)
        self.item = item
        // The title says what the SYSTEM says, from the first draw: reading it here rather than
        // assuming false means the menu is never briefly wrong after a relaunch.
        rebuildMenu()
    }

    /// The certificate helper that renders a pairing code. Named once.
    ///
    /// Like `BridgeSupervisor`, this is the shape the app had before it could speak to the bridge
    /// directly. The bridge in this repository answers `pair-open` on its control socket, and the task
    /// that rewrites the supervisor rewrites this with it: a code will be asked for over that socket
    /// rather than by running a second binary the owner has to have built.
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
            pairing.reportOnAddress(
                "Saved. Your phone will dial \(address.displayed), and the code below is built from it. "
                    + "Nothing has answered there yet — that is checked separately.")
        case .refused(let sentence), .notWritten(let sentence):
            pairing.reportOnAddress(sentence)

        case .needsConfirmation(let explanation, let question):
            // **Deliberate, not impossible.** A two-label name is a valid destination that looks like
            // the mistake of 2026-08-09, so it costs a second act rather than being prohibited — the
            // same shape as deleting a session on the phone.
            pairing.reportOnAddress(explanation)
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
        pairing.show(state, field: field)
        // The menu's "Show the pairing code…" is enabled by whether an address exists, and one may have
        // just started existing.
        rebuildMenu()
    }

    private var supervisor: BridgeSupervisor { BridgeSupervisor(run: shell) }

    /// **Stop, with the cost said BEFORE the press takes effect.**
    ///
    /// Stopping cuts any phone connected through the bridge, and unlike a restart there is no
    /// supervisor bringing it back: `bootout` unloads the job so `KeepAlive` cannot revive it, and the
    /// Start item is the only way back. The owner confirms that sentence first — afterwards would be
    /// an apology, not a warning.
    @objc private func stopBridge() {
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

        act("Stop") { try self.supervisor.stop() }
    }

    /// Start, which after a stop means bootstrapping the job back from its plist — `kickstart` alone
    /// cannot start a job launchd has unloaded.
    @objc private func startBridge() {
        act("Start") {
            let plist = FileManager.default.homeDirectoryForCurrentUser
                .appending(path: "Library/LaunchAgents/\(BridgeSupervisor.label).plist")
            if FileManager.default.fileExists(atPath: plist.path) {
                // Bootstrapping an already-loaded job errors; kickstart is the right verb then. Try
                // the one that matches the state we are actually in rather than guessing.
                if self.supervisor.isRunning() { try self.supervisor.start() }
                else { try? self.supervisor.startAfterStop(plist: plist); try self.supervisor.start() }
            } else {
                try self.supervisor.start()
            }
        }
    }

    /// Runs one supervisor action and **reports what launchd says afterwards, not what we asked for**.
    ///
    /// A refusal becomes a sentence the owner reads. A stop that quietly did nothing is
    /// indistinguishable from a stop that worked, until they go looking for the process — which is the
    /// silent-failure shape this whole branch is about.
    private func act(_ what: String, _ body: () throws -> Void) {
        do {
            try body()
        } catch {
            notify("\(what) failed.", "launchd refused: \(error)")
            rebuildMenu()
            return
        }
        let running = supervisor.isRunning()
        notify(
            running ? "The bridge is running." : "The bridge is not running.",
            running
                ? "Asked launchd after the change; it reports the job alive."
                : "Asked launchd after the change; it reports no running job. Start it from this menu.",
        )
        rebuildMenu()
    }

    /// Rebuilt after anything that changes what the menu should say. The titles come from the model,
    /// and the model is given the system's answer rather than our memory of it.
    private func rebuildMenu() {
        // Asked, not assumed: Start and Stop are enabled by what launchd reports, so the menu cannot
        // offer Stop for a job that is already down.
        let running = supervisor.isRunning()
        item?.menu = menu(
            status: BridgeStatus(
                address: DialAddress(host: "-", port: 0),
                running: running ? .running : .notRunning,
                reachable: .noAnswer("not checked"),
                identified: .notEstablished,
            ),
            hasAddress: AddressPreference.read().isSuccess,
            launchesAtLogin: loginItem.status() == .registered,
        )
    }

    /// Says something, always. An alert rather than a notification: this app has no notification
    /// permission and asking for one to report a menu toggle would be a second permission for a
    /// sentence.
    private func notify(_ message: String, _ detail: String) {
        let alert = NSAlert()
        alert.messageText = message
        alert.informativeText = detail
        alert.alertStyle = .informational
        NSApp.activate(ignoringOtherApps: true)
        alert.runModal()
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

    /// One place a process is started, so the allow-list in BoundaryTests has one thing to check.
    private let shell: BridgeSupervisor.Runner = { invocation in
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

    /// Derived from the same `implemented` set the menu's enabled state is derived from, and asserted
    /// against it below: an action cannot be lit without a handler, or handled without being lit.
    private func selector(for action: MenuAction) -> Selector? {
        switch action {
        case .quit: #selector(quit)
        case .showPairingCode: #selector(showPairingCode)
        case .setAddress: #selector(setAddress)
        case .startAtLogin: #selector(toggleStartAtLogin)
        case .startBridge: #selector(startBridge)
        case .stopBridge: #selector(stopBridge)
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

let app = NSApplication.shared
// The programmatic half of "no Dock icon, no window at launch". See the note on `MenuBarApp`.
app.setActivationPolicy(.accessory)
let menuBar = MenuBarApp()
app.delegate = menuBar
app.run()
