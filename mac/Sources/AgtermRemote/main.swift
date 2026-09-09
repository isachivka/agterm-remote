import AgtermRemoteCore
import AppKit

/// The menu-bar shell.
///
/// Every item but Quit was once lit and inert, and on 2026-08-10 the owner pressed two of them and
/// nothing happened. **Both halves of that are fixed here**: `implemented` decides what is pressable
/// AND what is wired, and *Pair a phone…* opens a real panel over the bridge's own control socket.
/// The items still missing are absent from that set, so they are greyed rather than pressable.
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
        // `.pairPhone` is in this set now, and the sentence it replaces said it would come back "when
        // the panel that asks the bridge for a code lands, and not one change earlier". This is that
        // change: the panel opens an enrolment window over the bridge's own control socket and draws
        // what comes back. It is offered even when the bridge is down, because the panel's answer to
        // that is a sentence naming it — which is more use than a greyed item that says nothing.
        //
        // `.unpair` likewise: the menu only carries the item when the bridge reports a phone, so an
        // action that cannot apply is absent rather than lit.
        var actions: Set<MenuAction> = [.quit, .setUp, .startAtLogin, .pairPhone, .unpair]
        if bridge != nil { actions.formUnion([.startBridge, .stopBridge]) }
        return actions
    }

    /// The bridge's local door. Constructed once and pointed at the state directory this app hands the
    /// bridge, so the two cannot disagree about where the socket is.
    private let control = UnixControlClient(stateDirectory: MenuBarApp.stateDirectory)

    /// **Every control-socket round trip in this app runs here, and nowhere else.**
    ///
    /// Serial, so two answers cannot race each other into the menu, and off the main thread because
    /// each one of them is a blocking call to another process with a five-second ceiling. Driven from
    /// a one-second timer on the main thread, as the panel is, that is an interface frozen five
    /// seconds out of six against a bridge that accepts and then stalls — the same hang class
    /// `BridgeProcess.stop` was rebuilt to remove, one layer up.
    private let socketWork = DispatchQueue(label: "agterm-remote.control", qos: .userInitiated)

    /// The panel, as a value. The window renders it and the clock advances it; neither decides
    /// anything, and neither waits for a socket.
    private lazy var panel: PairingPanelModel = {
        let model = PairingPanelModel(
            control: control, now: Date.init,
            off: { [socketWork] work in socketWork.async(execute: work) })
        model.onChange = { [weak self] state in
            // It changed on the socket queue. Everything below this line touches AppKit.
            DispatchQueue.main.async { self?.panelChanged(to: state) }
        }
        return model
    }()

    /// What the panel showed last, so a redraw is not asked for on a state that has not moved.
    private func panelChanged(to state: PairingPanelState) {
        pairing.refreshIfOpen(state)
        // A phone that walked through the window has proven the address it dialled. This is the
        // moment that fact becomes true, and the menu says it.
        if case .paired = state {
            refreshProvenance()
            rebuildMenu()
        }
    }

    /// What the bridge last said it holds. **Read from the socket, not remembered from a file**: the
    /// trust store's modification date says a phone once enrolled and keeps saying it after the phone
    /// is dropped.
    private var pairedPhones: [PairedPhone] = []

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
        // **The main menu, which is how macOS delivers ⌘V.**
        //
        // Without it every text field in this app is type-only: the standard editing commands are
        // menu items, and an app with no main menu has no items for the key equivalents to reach.
        // The owner found it by pasting an address and watching nothing happen. See `EditingMenu` —
        // including why this does not put the app in the Dock.
        NSApp.mainMenu = EditingMenu.make()
        // **Before the first draw**, because the first draw says whether the address is proven and
        // the answer lives in a file this process has not looked at yet. Asking afterwards left the
        // menu saying `unproven` under a setup that had been paired for weeks, until something else
        // happened to rebuild it.
        refreshProvenance { [weak self] in self?.rebuildMenu() }
        // The title says what the SYSTEM says, from the first draw: reading it here rather than
        // assuming false means the menu is never briefly wrong after a relaunch. Drawn now from what
        // is already stored, and again above when the bridge has answered — the alternative is a
        // menu bar with nothing in it until a socket replies.
        rebuildMenu()
        watchForTermination()
        // **The one window that opens without being asked for**, and only while the three things do
        // not all hold. A person who has finished setting up never sees it again; a person who has
        // not cannot be expected to know that the way in is a menu-bar icon they have never met.
        showOnboardingIfUnfinished()
        watchForPairing()
    }

    /// The three facts, as they stand right now.
    ///
    /// Each is read fresh rather than remembered: agterm can be quit, an address can be cleared in
    /// System Settings, and the bridge writes its trust store without telling this process. A cached
    /// answer to any of the three is how an app comes to say something that stopped being true.
    /// The trust store is the bridge's own record that a phone completed enrolment. Credited to the
    /// address only if it happened after that address was stored — the rule is in
    /// `AddressPreference.recordEnrolment`, and it is why a new address starts unproven.
    ///
    /// ### The bridge's own list outranks the file's date, and that inversion arrived with Unpair
    ///
    /// `EnrolmentRecord` reads the modification date of the trust store, and it was honest while
    /// nothing on this side could unpair: only a phone completing enrolment ever wrote that file. Its
    /// own note said so and named this as the thing that would break it — an emptied trust store is
    /// still a file with a recent date, so after an unpair the address would go on claiming a phone
    /// paired through it minutes ago. So when the bridge can be asked, it is: an empty list is
    /// unproven, whatever the file's date says. When it cannot — the bridge is not running, which is
    /// most of the time — the file is still the best answer available and nothing here pretends
    /// otherwise.
    /// **Asked on the socket queue, applied on the main one.** It was a blocking round trip called
    /// from a five-second timer on the main thread, so a bridge that accepted and then said nothing
    /// froze the menu bar for five seconds out of every five.
    ///
    /// The file is read here too, and that half is cheap: one `stat`.
    private func refreshProvenance(then finished: (@MainActor () -> Void)? = nil) {
        socketWork.async { [weak self] in
            guard let self else { return }
            let paired = try? control.status().paired
            let enrolment = EnrolmentRecord.recordedAt(inStateDirectory: Self.stateDirectory)
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    if let paired { self.pairedPhones = paired }
                    // An empty list from a bridge that answered outranks the file's date. Nothing else
                    // does: when the bridge cannot be asked - which is most of the time, because it is
                    // not running - the file is the best answer available.
                    self.applyProvenance(paired?.isEmpty == true ? nil : enrolment)
                    finished?()
                }
            }
        }
    }

    /// The synchronous half, so the rule stays in one place. Never touches the socket.
    @discardableResult
    private func applyProvenance(_ enrolment: Date?) -> Date? {
        AddressPreference.recordEnrolment(at: enrolment)
    }

    /// **Reads what is stored; does not go and ask.** It used to call `refreshProvenance`, which is a
    /// blocking round trip — and this is called from inside window construction. The five-second timer
    /// below owns the asking, and it redraws this pane when the answer moves.
    private func onboardingNow() -> Onboarding {
        Onboarding(
            // Named for the socket in the design document; what this app may look at is the running
            // application. See `AgtermPresence` - the socket is on the far side of a boundary this
            // app does not cross.
            agtermSocketExists: AgtermPresence.isRunning(),
            address: AddressPreference.stored(),
            isPaired: AddressPreference.provenAt() != nil)
    }

    /// **Opened for what is left to SET UP, never for agterm being down.**
    ///
    /// agterm is quit and started all day and none of it changes what is configured. Gating this
    /// window on it meant a finished owner whose Mac starts this app at login — before agterm is up —
    /// got a setup window over their work every morning, for a fact that resolves itself. That fact
    /// belongs in the menu, and it is there.
    private func showOnboardingIfUnfinished() {
        let now = onboardingNow()
        guard now.setup != .done else { return }
        showOnboarding(now)
    }

    private func showOnboarding(_ now: Onboarding) {
        // One save path for the whole app: both windows hand the typed string to the same function,
        // which is the same `SaveAddress` with the same refusals. Two savers would be two opinions
        // about what an address is.
        onboarding.onSave = { [weak self] typed, port in self?.saveAddress(typed, arrivalPort: port) }
        onboarding.arrivalPortText = Self.arrivalPortText()
        onboarding.frontDoor = AddressPreference.frontDoor()
        // **Stored, and then the bridge is restarted if it is running.** One of the two flags it
        // decides is settled when the listener is built, so a bridge that is already up is serving
        // the old answer - and the symptom of that is a pairing code that cannot work, which is the
        // failure this whole question exists to end.
        onboarding.onFrontDoor = { [weak self] chosen in
            AddressPreference.writeFrontDoor(chosen)
            self?.restartTheBridgeIfItIsRunning()
        }
        onboarding.onShowPairingCode = { [weak self] in self?.openPairing() }
        onboarding.onRecheck = { [weak self] in
            guard let self else { return }
            let again = onboardingNow()
            onboarding.show(again, field: AddressField(AddressPreference.read()))
            rebuildMenu()
        }
        onboarding.show(now, field: AddressField(AddressPreference.read()))
    }

    /// **Noticing a phone that pairs while this app is running.**
    ///
    /// The proof of an address is written by the bridge, in its own process, at whatever moment the
    /// owner holds their phone up. Nothing tells this app about it, so the menu said *unproven* under
    /// a setup that had just been proven until somebody happened to reopen something.
    ///
    /// One `stat` of one file, every few seconds. It is polling, and the honest defence of it is the
    /// size: an FSEvents subscription for a fact that changes once in the lifetime of a setup is more
    /// machinery than the thing it watches. Nothing is redrawn unless the answer moved.
    private func watchForPairing() {
        let timer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                let before = AddressPreference.provenAt()
                let phones = self.pairedPhones
                self.refreshProvenance { [weak self] in
                    guard let self else { return }
                    // Nothing is redrawn unless an answer moved — including the paired list, which is
                    // what the menu's Unpair item is made of.
                    guard AddressPreference.provenAt() != before || self.pairedPhones != phones else { return }
                    self.rebuildMenu()
                    // The pane on screen is about to be wrong for the same reason the menu was.
                    if self.onboarding.isOpen {
                        self.onboarding.show(
                            self.onboardingNow(), field: AddressField(AddressPreference.read()))
                    }
                }
            }
        }
        // Menus and modal alerts run their own run-loop mode; without this the timer stops firing
        // for as long as one is open, which is exactly when somebody is looking at the line it keeps
        // honest.
        RunLoop.main.add(timer, forMode: .common)
        pairingWatch = timer
    }

    /// Held so it outlives the function that made it, and invalidated on the way out.
    private var pairingWatch: Timer?

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
            // **`stopAndWait`, not `stop`.** The kill is off the caller's thread now, and this line is
            // immediately followed by this process ceasing to exist — a stop that returned early here
            // would take the app with it before the signal was ever delivered.
            self?.bridge?.stopAndWait()
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
        pairingWatch?.invalidate()
        panelClock?.invalidate()
        // The enrolment window is closed on the way out. It would expire on its own within five
        // minutes; leaving the one anonymous branch of the front door open for five minutes because
        // somebody quit the app is not a thing to leave to a timer.
        panel.close()
        // See the SIGTERM handler: this is the other path that must not return before the child is
        // actually gone.
        bridge?.stopAndWait()
    }

    /// **The code comes from this app's own bridge, over its own control socket.**
    ///
    /// It used to come from a certificate helper in a *different project's* directory in the owner's
    /// home — the installation they are still running, in parallel with this one. Nothing in this
    /// repository builds that binary and nothing here owns its output, so the app was borrowing a
    /// tool from somebody else's setup and calling the result its own. That is gone, and so is the
    /// PNG on the Desktop it wrote: the code now lives exactly as long as the panel showing it.
    @objc private func pairPhone() { openPairing() }

    /// Setting the address is **setup**, and setup is one screen with the explanation on it.
    ///
    /// This was *Set the address…*, and it opened the pairing window with the cursor in a box. That
    /// made sense while the pairing window held the address; it does not now. A person changing where
    /// their phone dials needs the paragraph about port forwards, the second port and what stays
    /// unproven — all of which is on the setup screen and none of which fits beside a live enrolment
    /// code.
    @objc private func setUp() { showOnboarding(onboardingNow()) }

    private func openPairing() {
        guard case .success(let address) = AddressPreference.read() else {
            // Nothing to encode. The way out is the setup screen, and the owner is sent there rather
            // than shown a panel apologising — the menu item is greyed without an address anyway, so
            // this is the belt to that braces.
            notify(
                "There is no address yet.",
                "The pairing code carries the address your phone will dial, so it has to exist first. "
                    + "Set it up, then come back.")
            return setUp()
        }
        pairing.address = address.displayed
        pairing.onClose = { [weak self] in self?.closePairing() }
        pairing.onAskForAnotherCode = { [weak self] in self?.mintACode() }
        mintACode()
        startThePanelClock()
    }

    /// Ask the bridge for a code and draw whatever came back — including a refusal, which is a
    /// sentence on the panel rather than an empty square.
    /// **The address is read here, at the press, and travels with the mint.**
    ///
    /// Not from the bridge's command line, which was set when it was started and is not revisited when
    /// the owner saves a new address. That was the whole of the second half of this defect: the app
    /// said *your phone will dial X* and the code beside it said Y, with nothing anywhere restarting
    /// the bridge to reconcile them.
    private func mintACode() {
        let address = (try? AddressPreference.read().get())?.displayed ?? ""
        pairing.address = address
        // Read at the press, exactly like the address and for the same reason: the owner can put a
        // proxy in front of this Mac while the bridge is running, and the bridge is not restarted
        // when they do.
        panel.open(advertising: address, frontDoor: AddressPreference.frontDoor())
        pairing.show(panel.state)
    }

    /// **The panel's clock**, and it stops the moment there is nothing left to watch.
    ///
    /// A code has to leave the screen when it stops working: a stale code on screen is a code somebody
    /// will scan, and the phone's failure at that point is indistinguishable from a broken bridge. The
    /// tick also asks the bridge what happened, which is how the panel can say *five wrong tokens*
    /// rather than *that code no longer works*.
    private func startThePanelClock() {
        panelClock?.invalidate()
        let timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                // The window may have gone since the last tick. Stopping the clock from the held
                // reference rather than from the callback's own argument keeps this on the main actor,
                // which is where every other line of this closure already is.
                guard let self, self.pairing.isOpen else { return self?.stopThePanelClock() ?? () }
                // Returns at once. The round trip is on the socket queue and the redraw arrives
                // through `panelChanged`, so a bridge that accepts and then stalls costs this timer
                // nothing at all.
                self.panel.tick()
            }
        }
        // Menus and modal alerts run their own run-loop mode; without this the code on screen would
        // outlive its expiry for as long as somebody had the menu open.
        RunLoop.main.add(timer, forMode: .common)
        panelClock = timer
    }

    private var panelClock: Timer?

    private func stopThePanelClock() {
        panelClock?.invalidate()
        panelClock = nil
    }

    /// The panel is gone, so the enrolment window goes with it.
    private func closePairing() {
        stopThePanelClock()
        panel.close()
        refreshProvenance { [weak self] in self?.rebuildMenu() }
    }

    /// **Unpairing, with the cost said before the press takes effect.**
    ///
    /// It is total in v1 — the trust store keeps exactly one peer — so this is not "remove one of
    /// several", and the phone loses its way in immediately. Confirmed first, because afterwards it is
    /// an apology.
    @objc private func unpairPhone() {
        guard let phone = pairedPhones.last else { return }
        let confirm = NSAlert()
        // The same rendering the menu item uses. Two ways of naming a phone is two phones as far as
        // anybody reading a confirmation is concerned.
        confirm.messageText = "Unpair \(MenuModel.describe(phone))?"
        confirm.informativeText =
            "It loses access immediately and cannot get it back without scanning a new code. "
                + "This bridge holds one phone, so this unpairs every phone it has."
        confirm.addButton(withTitle: "Unpair it")
        confirm.addButton(withTitle: "Cancel")
        confirm.alertStyle = .warning
        NSApp.activate(ignoringOtherApps: true)
        guard confirm.runModal() == .alertFirstButtonReturn else { return }

        // On the socket queue, like everything else that dials the bridge. The alert that follows is
        // AppKit, so it comes back to the main thread to be raised.
        socketWork.async { [weak self] in
            guard let self else { return }
            var refusal: Error?
            do { try control.unpair(fingerprint: phone.fingerprint) } catch { refusal = error }
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    if let refusal {
                        // Never silent. The bridge refuses a fingerprint it does not hold rather than
                        // shrugging, precisely so this can say something true instead of reporting a
                        // removal that did not happen.
                        self.notify("That phone was not unpaired.", "\(refusal)")
                        return self.rebuildMenu()
                    }
                    self.pairedPhones = []
                    // The address stops being proven in the same act. See `refreshProvenance`.
                    self.refreshProvenance { self.rebuildMenu() }
                    self.rebuildMenu()
                    self.notify(
                        "That phone is unpaired.",
                        "It can no longer connect. Pair it again from this menu when you want it back.")
                }
            }
        }
    }

    /// The arrival port as text for the box: empty when it follows the dial port, because empty is
    /// what an owner types to mean *they are the same*.
    private static func arrivalPortText() -> String {
        AddressPreference.listenPort().map(String.init) ?? ""
    }

    private func saveAddress(_ typed: String, arrivalPort: String, confirmed: Bool = false) {
        // **Saved in the same act as the address, because they are one setting in two halves.** The
        // port is written first: an address that is refused must not leave a port change on the
        // floor, and the port has its own refusals which say their own sentence.
        let port = SaveListenPort().save(arrivalPort, dialPort: try? AddressPreference.read().get().port)
        let outcome = SaveAddress().save(typed, confirmed: confirmed)

        switch outcome {
        case .saved(let address):
            report(
                "Saved. Your phone will dial \(address.displayed), and the code will be built from it. "
                    + "Nothing has answered there yet — that is checked separately. "
                    + Self.sentence(for: port, dial: address))
        case .refused(let sentence), .notWritten(let sentence):
            report(sentence + Self.portRefusal(port))

        case .needsConfirmation(let explanation, let question):
            // **Deliberate, not impossible.** A two-label name is a valid destination that looks like
            // the mistake of 2026-08-09, so it costs a second act rather than being prohibited — the
            // same shape as deleting a session on the phone.
            report(explanation)
            if confirmSuffix(explanation: explanation, question: question) {
                return saveAddress(typed, arrivalPort: arrivalPort, confirmed: true)
            }
        }

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
        onboarding.arrivalPortText = Self.arrivalPortText()
        // The address they just typed may have been the second onboarding step, and a pane that
        // stayed on "type an address" after one was saved would be showing a state that has stopped
        // being true.
        if onboarding.isOpen { onboarding.show(onboardingNow(), field: field) }
        // **A code on screen for an address that has just changed underneath it is worse than no
        // code**: it is scannable, it pairs, and it points the phone at the previous destination. The
        // window it was minted against is closed and the panel goes back to offering a fresh one,
        // rather than being left showing a picture of somewhere the owner no longer lives.
        if case .saved(let address) = outcome, pairing.isOpen {
            pairing.address = address.displayed
            panel.close()
            pairing.show(panel.state)
        }
        // The menu's "Pair a phone…" is enabled by whether an address exists, and one may have just
        // started existing.
        rebuildMenu()
    }

    /// What happened to the arrival port, in the same breath as the address — the two are one
    /// setting, and a person who has just told this app about a proxying router needs to see that it
    /// heard.
    private static func sentence(for outcome: SaveListenPort.Outcome, dial: DialAddress) -> String {
        switch outcome {
        case .saved(let port):
            "The bridge will listen on port \(port) — traffic arrives there, not on \(dial.port)."
        case .following:
            "The bridge will listen on port \(dial.port), the same port your phone dials."
        case .refused(let sentence), .notWritten(let sentence):
            sentence
        }
    }

    /// The port's own refusal, appended to an address refusal rather than replacing it: two fields
    /// were saved, and silence about one of them is how somebody fixes the wrong thing.
    private static func portRefusal(_ outcome: SaveListenPort.Outcome) -> String {
        switch outcome {
        case .refused(let sentence), .notWritten(let sentence): " \(sentence)"
        case .saved, .following: ""
        }
    }

    /// One sentence, put in front of whoever is looking. There is one address editor now — the setup
    /// screen — so this is the one place its outcome is said.
    private func report(_ sentence: String) {
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

    /// **A running bridge is holding the old answer, so it is put down and picked up again.**
    ///
    /// One of the two flags the choice decides - whether this port serves TLS - is settled when the
    /// listener is built, so a bridge already up cannot honour a change. Silently leaving it would
    /// produce exactly the failure the question exists to end: a screen saying one thing and a
    /// listener doing another, with the phone reporting a laptop that will not answer.
    ///
    /// No confirmation, unlike the Stop item, because this is not stopping the bridge - it is
    /// applying a setting, and it comes straight back up. A bridge that was already down stays down.
    private func restartTheBridgeIfItIsRunning() {
        guard let bridge, bridge.state != .stopped else { return }
        bridge.stop()
        startBridge()
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
            return setUp()
        }
        // The port the owner says traffic arrives on, or the dial port when they have not said. Held
        // for the failure message: `address already in use` is unreadable without the number, and the
        // owner has two ports in play.
        let arrival = AddressPreference.listenPort()
        listeningOn = arrival ?? address.port
        startWasAsked = true
        do {
            // **`Address.listen(on:)`, not a string built here.** The dial address and the listen
            // address are different addresses - the host never travels, and the port travels only
            // when the owner has not said otherwise. The reasoning lives on the value.
            // **Two addresses, passed as two arguments.** The bind is the wildcard, because this Mac
            // cannot know which of its interfaces the router forwards to; the code has to name the
            // one the owner published, and `0.0.0.0` names every interface and therefore none. Before
            // this the bridge minted codes from what it was bound to, which is exactly the shape of
            // the 2026-08-09 failure: a code that pairs and then never connects.
            try bridge.start(
                listen: Address(address).listen(on: arrival), socket: nil,
                advertise: address.displayed,
                // The other half of the same fact, and it has to be here because it decides a flag
                // rather than a request: whether this port serves TLS is settled when the listener is
                // built. Changing it therefore needs a restart, which is why it is asked on the setup
                // screen rather than beside the code.
                frontDoor: AddressPreference.frontDoor())
        } catch {
            // A start that could not happen at all: the binary vanished between launch and now, or
            // macOS is holding it because the app arrived by download. The error's own words, not a
            // summary of them — a quarantine refusal is a paragraph carrying the command that fixes
            // it, and there is nowhere else the owner would ever see it.
            //
            // `copyable` is the command, when there is one. See `notify`.
            startWasAsked = false
            notify(
                "The bridge would not start.",
                PortInUse.explanation(for: "\(error)", port: listeningOn) ?? "\(error)",
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
            // **A failure the owner asked for is said out loud, once.** Which failures those are, and
            // what they say, is `BridgeReport.announcement` — a value, because the two ways this was
            // wrong before were both invisible to every test in the suite.
            if let said = BridgeReport.announcement(
                for: state, startWasAsked: startWasAsked, listeningOn: listeningOn) {
                startWasAsked = false
                notify("The bridge would not start.", said)
            }
        } else if case .running = state {
            failure = nil
            startWasAsked = false
        }
        rebuildMenu()
    }

    /// True between the owner pressing Start and the bridge reaching a state. See `bridgeChanged`.
    private var startWasAsked = false

    /// The port the last start was pointed at. Named in the failure, because the owner has two.
    private var listeningOn = 0

    /// The last thing the bridge said on its way out, or nil. Shown, never announced.
    private var failure: String?

    /// Rebuilt after anything that changes what the menu should say. The titles come from the model,
    /// and the model is given the system's answer rather than our memory of it.
    private func rebuildMenu() {
        // **The supervisor's own state, not a translation of it.** This used to be mapped onto a
        // three-way running state built here out of a placeholder address and the words `not
        // checked` — a shape with no measurement behind it, since retired — and every translation
        // lost a case. Two of the five now change what is pressable: `.starting` is the window
        // between spawning a child and that child announcing a bound listener, and `.stopping` is
        // the kill on its way.
        item?.menu = menu(
            paired: pairedPhones,
            hasAddress: AddressPreference.read().isSuccess,
            launchesAtLogin: loginItem.status() == .registered,
            bridge: bridge?.state ?? .stopped,
        )
        // **Assigned every time, never only when there is something to say.** The rule and the reason
        // are in `BridgeReport.tooltip`, where a test can ask them a question — this used to be an
        // `if let` here, which can set a tooltip and has no way to take one back.
        item?.button?.toolTip = BridgeReport.tooltip(for: bridge?.state ?? .stopped, failure: failure)
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

    /// The glyph, and the same words for anyone who cannot see it.
    private func apply(_ state: IconState, to item: NSStatusItem) {
        item.button?.image = Mark.image(for: state)
        // No tint is set, anywhere. The states are five shapes; a status conveyed by hue is one the
        // owner cannot read at a glance and may not be able to read at all.
        item.button?.toolTip = state.describedAsWords
    }

    private func menu(
        paired: [PairedPhone], hasAddress: Bool, launchesAtLogin: Bool, bridge state: BridgeState,
    ) -> NSMenu {
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
        // **agterm's absence, in the menu, because that is where a runtime fact belongs.**
        //
        // It is not a setup step: nothing about storing an address or pairing a phone needs agterm to
        // be running, and the terminal is quit and restarted all day. What it does mean is that a
        // paired phone has nothing to drive until it is back, so it is said here rather than in a
        // window that interrupts.
        if !AgtermPresence.isRunning() {
            let line = NSMenuItem(
                title: "agterm is not running — a phone can connect but has nothing to drive",
                action: nil, keyEquivalent: "")
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
            paired: paired, hasAddress: hasAddress, launchesAtLogin: launchesAtLogin,
            bridge: state, implemented: implemented,
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
        let when = proven.formatted(date: .abbreviated, time: .shortened)
        return "\(address.displayed) — a phone paired through it on \(when)"
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
        case .setUp: #selector(setUp)
        case .pairPhone: #selector(pairPhone)
        case .unpair: #selector(unpairPhone)
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
