import Foundation

/// **The one Gatekeeper state that produces no error anywhere, and what to say about it.**
///
/// This app is signed ad-hoc. There is no Apple Developer account and there will be no notarisation,
/// so a copy that arrives by download carries `com.apple.quarantine` and macOS holds its executables
/// pending an approval. For something a person double-clicks that approval is a dialogue. For the
/// bridge it is nothing at all: the app spawns it, so there is nobody for the dialogue to appear to.
///
/// Measured on macOS 26.6, against a quarantined copy of the built bundle: `Process.run()` **succeeds**
/// and returns a pid, the child appears in `ps` in state `SN`, it never reaches `main`, it writes
/// nothing, and it never exits. Every signal this app has said about the bridge — spawned, running,
/// exited, retried — reports exactly what it would report for a healthy one. The owner presses Start
/// and the button does nothing, forever, with no sentence anywhere naming a cause.
///
/// That is the shipped first-run experience for anybody who downloads a release, so it is checked
/// **before** the spawn rather than diagnosed after it.
///
/// ### Detect, do not repair — and **not** because we cannot
///
/// It would be convenient to say macOS forbids clearing this. It does not. Measured on the same
/// machine and the same bundle: `removexattr` from an ordinary unentitled process **succeeds**, on
/// the nested binary and on the `.app` directory alike, and `xattr -d` succeeds too. The app is
/// perfectly able to erase its own quarantine.
///
/// It must not, and the reason is not technical. Quarantine is the record that this code came from
/// outside. This app is not notarised, so that record is the *only* Gatekeeper signal standing
/// between the owner and whatever they actually downloaded — and an app that quietly deletes the
/// evidence about itself, as a side effect of somebody pressing Start, has done the one thing the
/// mechanism exists to prevent, on behalf of a person who was never asked. Clearing it is also a
/// bigger act than the press implies: the attribute is on every file of the bundle, so any useful
/// removal is recursive over the whole application.
///
/// So the owner is handed the command and runs it knowingly. Reading the attribute is enough for
/// that, and reading it needs no entitlement.
public enum Quarantine {

    /// The extended attribute macOS sets on anything that arrived from elsewhere.
    public static let attribute = "com.apple.quarantine"

    /// Whether macOS considers this file to have come from somewhere else.
    ///
    /// - Parameter size: injected so the decision is testable without a file, and so the production
    ///   path is one `getxattr` with a zero-length buffer — the size query, which allocates nothing.
    public static func isSet(
        on url: URL, size: (String, String) -> Int = Self.sizeOfAttribute,
    ) -> Bool {
        size(url.path, attribute) >= 0
    }

    /// `getxattr` for its size alone. Negative means the attribute is not there — or that the file is
    /// not there, which is a case the caller has already excluded by locating the binary.
    ///
    /// `XATTR_NOFOLLOW` is deliberately NOT passed. The question is about the file that will be
    /// executed, and that is the one at the end of the link.
    public static func sizeOfAttribute(_ path: String, _ name: String) -> Int {
        getxattr(path, name, nil, 0, 0, 0)
    }

    /// **What the owner is told, and the command that fixes it.**
    ///
    /// The path in the instruction is the enclosing `.app` when there is one, because quarantine is
    /// set on every file of a downloaded bundle and clearing it from the bridge alone would leave the
    /// app itself still held. `-dr` — recursive — for the same reason.
    public static func explanation(for executable: URL) -> String {
        let target = enclosingBundle(of: executable) ?? executable
        return """
            macOS has quarantined the bridge, so starting it would freeze it rather than run it.

            This happens to anything that arrives by download. Agterm Remote is signed ad-hoc rather \
            than notarised, so macOS holds it pending an approval — and because this app launches the \
            bridge itself, there is no window for you to approve it in. It would simply never start, \
            and nothing would say why.

            Clear it in Terminal and press Start again:

                xattr -dr com.apple.quarantine "\(target.path)"

            Agterm Remote will not do this for you. Quarantine is macOS's record that this app came \
            from somewhere else, and an app that erased that record about itself — because you \
            pressed Start — would be removing the one check you have on it.
            """
    }

    /// The `.app` this path is inside, or nil when it is not inside one — which is the ordinary case
    /// during development, where the bridge sits beside a bare executable in the build directory.
    ///
    /// Walks up rather than assuming a depth. `Contents/Resources` is three levels today, and a path
    /// arithmetic that encoded the three would be wrong the moment anything moved.
    static func enclosingBundle(of executable: URL) -> URL? {
        var directory = executable.deletingLastPathComponent()
        while directory.path != "/" && !directory.path.isEmpty {
            if directory.pathExtension == "app" { return directory }
            let parent = directory.deletingLastPathComponent()
            // A path that stops shrinking is a loop. It cannot happen for an absolute path, and a
            // relative one would spin here forever without this.
            guard parent.path != directory.path else { return nil }
            directory = parent
        }
        return nil
    }
}
