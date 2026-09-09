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
/// ### The attribute's presence is NOT the condition. Its flags are.
///
/// An earlier version of this file asked only whether the attribute existed, and that refused every
/// application that works. Measured on this machine: **every** downloaded app in `/Applications` —
/// all of them running fine — still carries `com.apple.quarantine`, with flags `01c1` or `03c1`.
/// The attribute is never removed by approving an app; a bit is set in it.
///
/// The bit is `0x0040`. Measured against this exact bundle, same binary, only the flags differing:
///
/// | flags  | the bridge |
/// |--------|------------|
/// | `0081` | frozen at exec — no output, no exit |
/// | `0041` | runs to completion |
///
/// "Open Anyway" is the only route by which a downloaded copy launches at all, so the path every
/// real owner takes ends with this bit set. Refusing on presence alone meant refusing them with a
/// paragraph that was factually false — it said starting would freeze the bridge, and it would not.
///
/// ### Detect, do not repair
///
/// Quarantine is macOS's record that this code came from outside. This app is not notarised, so that
/// record is the *only* Gatekeeper signal standing between the owner and whatever they actually
/// downloaded — and an app that quietly deletes the evidence about itself, as a side effect of
/// somebody pressing Start, has done the one thing the mechanism exists to prevent, on behalf of a
/// person who was never asked. Clearing it is also a bigger act than the press implies: the attribute
/// is on every file of the bundle, so any useful removal is recursive over the whole application.
///
/// **That argument stands on its own, and it is deliberately the only one here.** Two rounds of
/// review have now carried a confident claim about whether `removexattr` *would* succeed — first
/// that macOS forbids it, then that it does not. Measured here, from an unentitled process and from
/// one running inside the quarantined bundle itself, across approved and unapproved flags and with
/// and without a UUID field, removal **succeeded every time**; review measured `EPERM` after the
/// bundle's main executable had run and I could not reproduce that in any configuration. Rather than
/// pick a third guess, the design no longer rests on the answer: the app does not remove it because
/// it must not, and `nothingInThisAppRemovesOrSetsAnExtendedAttribute` is what holds that.
public enum Quarantine {

    /// The extended attribute macOS sets on anything that arrived from elsewhere.
    public static let attribute = "com.apple.quarantine"

    /// `QTN_FLAG_USER_APPROVED`. Set when somebody has answered Gatekeeper about this file — which,
    /// for an unnotarised app, is the only way it ever launches.
    public static let userApproved: UInt32 = 0x0040

    /// **Whether macOS would freeze this binary at exec.**
    ///
    /// Not "is it quarantined": see the table above. The attribute survives approval, so the question
    /// is whether the approval bit is in it.
    ///
    /// - Parameter read: injected so the decision is testable without a file system.
    public static func wouldBeHeld(
        _ url: URL, read: (String, String) -> String? = Self.value,
    ) -> Bool {
        guard let raw = read(url.path, attribute) else { return false }
        guard let flags = flags(in: raw) else {
            // Present and unreadable. **Let it through**, deliberately — but the cost of that is
            // worse than it first looked and is written down at full price here.
            //
            // Being wrong this way costs a hang AND disarms the remedy that hang then prints: the
            // spawn is a blocked exec, and once macOS has blocked an exec on an item it refuses
            // `removexattr` on that item and its enclosing bundle permanently. So the owner reaches
            // the launch backstop's message, which for exactly that reason no longer offers a
            // command — only the Finder door, which still works.
            //
            // Still the right default. Nothing here enforces anything: the kernel does, and this
            // check only decides whether to show a paragraph. Being wrong the other way refuses an
            // application that works, with a false explanation, on the path every real owner takes —
            // which is the defect this rule replaced.
            return false
        }
        return flags & userApproved == 0
    }

    /// The flags field — the first of the four semicolon-separated fields, in hexadecimal.
    /// `0081;6aa0e764;Safari;<uuid>` is a typical value; the UUID is often empty.
    static func flags(in raw: String) -> UInt32? {
        guard let field = raw.split(separator: ";", omittingEmptySubsequences: false).first else {
            return nil
        }
        return UInt32(field, radix: 16)
    }

    /// The attribute's value, or nil when it is not set — or when the file is not there, which is a
    /// case the caller has already excluded by locating the binary.
    ///
    /// `XATTR_NOFOLLOW` is deliberately NOT passed. The question is about the file that will be
    /// executed, and that is the one at the end of the link.
    public static func value(_ path: String, _ name: String) -> String? {
        let size = getxattr(path, name, nil, 0, 0, 0)
        guard size > 0 else { return nil }
        var buffer = [UInt8](repeating: 0, count: size)
        guard getxattr(path, name, &buffer, size, 0, 0) == size else { return nil }
        return String(decoding: buffer, as: UTF8.self)
    }

    /// **What the owner is told, and the command that fixes it.**
    ///
    /// The path in the instruction is the enclosing `.app` when there is one, because quarantine is
    /// set on every file of a downloaded bundle and clearing it from the bridge alone would leave the
    /// app itself still held. `-dr` — recursive — for the same reason.
    public static func explanation(for executable: URL) -> String {
        """
            macOS has quarantined the bridge, so starting it would freeze it rather than run it.

            This happens to anything that arrives by download. Agterm Remote is signed ad-hoc rather \
            than notarised, so macOS holds it pending an approval — and because this app launches the \
            bridge itself, there is no window for you to approve it in. It would simply never start, \
            and nothing would say why.

            Clear it in Terminal and press Start again:

                \(command(for: executable))

            Agterm Remote will not do this for you. Quarantine is macOS's record that this app came \
            from somewhere else, and an app that erased that record about itself — because you \
            pressed Start — would be removing the one check you have on it.
            """
    }

    /// **The command on its own, separate from the paragraph that explains it.**
    ///
    /// Separate because an `NSAlert`'s informative text cannot be selected, so a command buried in it
    /// is one the owner retypes from the screen by hand — a `xattr -dr` line with a path in it, typed
    /// from memory, into a shell. The caller puts this in something copyable.
    public static func command(for executable: URL) -> String {
        let target = enclosingBundle(of: executable) ?? executable
        return "xattr -dr com.apple.quarantine \"\(target.path)\""
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
