import AppKit

/// **The main menu, which this app did not have — so Cmd-V did nothing, in every window, forever.**
///
/// ### What was actually broken
///
/// macOS does not implement the standard editing commands inside text fields. It routes them
/// through the **main menu**: Cmd-V is delivered by the Edit menu's Paste item, which sends
/// `paste:` to the first responder. An app that never assigns `NSApp.mainMenu` has no such item, so
/// the key equivalent reaches nothing at all — and neither do Cmd-C, Cmd-X, Cmd-A or Cmd-Z. Every
/// field in the app is type-only.
///
/// It was found by the owner pasting an address they had in their clipboard and watching nothing
/// happen, then typing it by hand. Five reviews had gone past it, because nothing about it is
/// visible in a diff: the defect is a line that was never written.
///
/// The blast radius is wider than the address box. The phone flow's fallback — **paste the pairing
/// code as text**, which exists precisely for a camera that will not read — is a text field, and so
/// is the selectable command in the quarantine alert.
///
/// ### It does not put the app in the Dock
///
/// The Dock tile is decided by the activation policy, and this app is `.accessory` twice over —
/// `LSUIElement` in the plist and `setActivationPolicy` in code. A main menu is orthogonal: an
/// accessory app keeps one, and it appears at the top of the screen only while one of its windows
/// is frontmost, which for this app means only while somebody is looking at the pairing or setup
/// window.
///
/// ### Why it is built here rather than in the app
///
/// So that its shape can be asserted. `EditingMenuTests` builds this and checks the six commands are
/// present with the selectors macOS actually sends; a menu assembled inline in `main.swift` could
/// only be checked by a person opening it, which is exactly how it came to be missing.
public enum EditingMenu {

    /// One editing command: what it says, what it sends, and what it answers to.
    public struct Command: Equatable, Sendable {
        public let title: String
        public let selector: Selector
        public let key: String
        public let holdsShift: Bool
    }

    /// **The six, with the selectors macOS sends to the first responder.** They are `NSText`'s and
    /// `NSResponder`'s own, not ours: a field editor implements them already, which is why wiring the
    /// items to nil — the responder chain — is the whole of the work.
    public static let commands: [Command] = [
        Command(title: "Undo", selector: Selector(("undo:")), key: "z", holdsShift: false),
        Command(title: "Redo", selector: Selector(("redo:")), key: "z", holdsShift: true),
        Command(title: "Cut", selector: #selector(NSText.cut(_:)), key: "x", holdsShift: false),
        Command(title: "Copy", selector: #selector(NSText.copy(_:)), key: "c", holdsShift: false),
        Command(title: "Paste", selector: #selector(NSText.paste(_:)), key: "v", holdsShift: false),
        Command(title: "Select All", selector: #selector(NSText.selectAll(_:)), key: "a", holdsShift: false),
    ]

    /// The whole main menu: an application menu, and Edit.
    ///
    /// The application menu holds Quit and nothing else. This app's real menu is the one in the
    /// status bar, and duplicating it here would be a second place to press the same things — which
    /// is how two menus come to disagree about what is enabled.
    @MainActor
    public static func make(applicationNamed name: String = "Agterm Remote") -> NSMenu {
        let main = NSMenu()

        let application = NSMenuItem()
        let applicationMenu = NSMenu()
        // Quit is here as well as in the status menu because Cmd-Q is delivered the same way
        // Cmd-V is: by a menu item. Without it, an app with a key window swallows the shortcut.
        applicationMenu.addItem(
            withTitle: "Quit \(name)", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        application.submenu = applicationMenu
        main.addItem(application)

        let edit = NSMenuItem()
        let editMenu = NSMenu(title: "Edit")
        for command in commands {
            let item = NSMenuItem(
                title: command.title, action: command.selector, keyEquivalent: command.key)
            if command.holdsShift { item.keyEquivalentModifierMask = [.command, .shift] }
            // **nil target, deliberately.** The command goes to whatever is focused — the field
            // editor inside whichever text field somebody is typing in — and a target here would
            // send every one of them to one object that implements none of them.
            item.target = nil
            editMenu.addItem(item)
        }
        edit.submenu = editMenu
        main.addItem(edit)

        return main
    }
}
