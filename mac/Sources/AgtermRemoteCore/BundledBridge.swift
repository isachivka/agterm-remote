import Foundation

/// **The bridge that ships inside the app, and the one place that knows where.**
///
/// The macOS half of this project promises that somebody installs exactly one thing. Not a `go
/// install` beforehand, not a launchd plist afterwards, not a daemon left running once the app is
/// dragged to the Bin: `bundle.sh` builds the Go bridge for both architectures, `lipo` joins them,
/// and the result sits in `Contents/Resources/agterm-remote-bridge`. The app spawns it as a child
/// (see `BridgeProcess`) and it dies with the app.
///
/// This enum is the lookup half of that promise. It is separate from `BridgeProcess` because the two
/// answer different questions — *where is it* and *how is it supervised* — and because the answer
/// here is what `BridgeProcess.locate` asks first.
///
/// ### Nil is an answer, not a failure
///
/// A fresh clone that has only run `swift build` has no bundle and no bridge. That is not an error to
/// report; it is the state in which Start and Stop are correctly greyed, because there is nothing to
/// start. So the lookup returns nil rather than a URL to a file that is not there — a path handed on
/// optimistically would light both menu items and fail at the press, which is the defect this app has
/// already been fixed for once.
public enum BundledBridge {

    /// The file `bundle.sh` writes and `BridgeProcess` launches, spelled once for both. Borrowed from
    /// the supervisor rather than repeated, so the name the app looks for cannot drift from the name
    /// `BoundaryTests` allows it to spawn.
    public static var executableName: String { BridgeProcess.executableName }

    /// Where the bridge would be inside `bundle`'s resources — **whether or not anything is there.**
    ///
    /// Internal on purpose. The layout is knowledge worth having in one place, and
    /// `BridgeProcess.locate` uses it for its bundle candidate, but nothing outside this package
    /// should hold a path that has not been checked.
    static func candidate(inResources resources: URL) -> URL {
        resources.appending(path: executableName)
    }

    /// **What every candidate has to be**, and the default test for both lookups here.
    ///
    /// `FileManager.isExecutableFile` alone is not that test, which was measured rather than argued:
    /// it answers **true for a directory** with mode 755 named `agterm-remote-bridge`, and spawning
    /// one fails at `Process.run` with an error the owner reads as the app being broken. So the type
    /// is asked for as well as the mode.
    ///
    /// **The link is resolved first**, and that matters here rather than being tidiness. A previous
    /// version asked `.isRegularFileKey` of the path as given, which does not follow links — so every
    /// symbolic link failed this test, including the perfectly ordinary one somebody makes during
    /// development to point `.build/debug/agterm-remote-bridge` at a Go build tree. That greyed both
    /// menu items with no explanation, in the one place `locate` exists to serve. Containment is a
    /// question for the bundle and is asked in `url(in:)`; here the only question is whether the
    /// thing at the end is a file that can be executed.
    ///
    /// - Parameter path: a file system path, not a URL, so it can stand in as `locate`'s predicate.
    public static func isSpawnable(_ path: String) -> Bool {
        guard FileManager.default.isExecutableFile(atPath: path) else { return false }
        let resolved = URL(fileURLWithPath: path).resolvingSymlinksInPath()
        let values = try? resolved.resourceValues(forKeys: [.isRegularFileKey])
        return values?.isRegularFile == true
    }

    /// The embedded bridge, or nil.
    ///
    /// Three things have to be true, and none of them is decoration:
    ///
    ///  1. **It is there.** A fresh clone that has only run `swift build` has no bundle and no bridge.
    ///  2. **It is spawnable** — an executable *regular file*. One that exists with the wrong mode, or
    ///     a directory wearing the name, is indistinguishable from a working install until somebody
    ///     presses Start.
    ///  3. **It is the one this bundle carries.** Both checks above follow symbolic links — they
    ///     have to, or an ordinary development symlink would fail them — so without this a link
    ///     planted in `Contents/Resources` would make the app spawn a binary from anywhere on the
    ///     disk while every check above said the bridge came from inside the bundle. Measured: it
    ///     resolved and would have run. The bundle's signature seals the link, not its target, so
    ///     the seal does not cover this either. This is the ONLY check that catches it, which is why
    ///     it is here and not folded into `isSpawnable`: `locate`'s other two candidates are not
    ///     inside any bundle and have nothing to be contained by.
    ///
    /// Nil for all three, because nil is what greys Start and Stop — and a menu item that looks dead
    /// is the correct report for a bridge that is not really here.
    ///
    /// - Parameter isExecutable: injected so the decision can be asserted without a file system,
    ///   the same seam `BridgeProcess.locate` uses. The containment check in (3) is not injected: it
    ///   is a question about the disk, and the tests that assert it build real bundles on one.
    public static func url(
        in bundle: Bundle,
        isExecutable: (String) -> Bool = isSpawnable,
    ) -> URL? {
        // A bundle with no resource directory at all — which is what Foundation reports for one that
        // was never assembled — is the same answer as an empty one.
        guard let resources = bundle.resourceURL else { return nil }
        let bridge = candidate(inResources: resources)
        guard isExecutable(bridge.path) else { return nil }
        // **Inside the resource directory — not at one exact path.** The question is whether the app
        // would spawn something this bundle carries, and a link to a sibling in the same sealed
        // directory is something this bundle carries. An equality test here refused that too, which
        // is a rule stricter than its own reason.
        //
        // Both sides resolved, so this is about links inside the bundle rather than about the ones
        // every temporary directory on macOS is reached through (`/var` to `/private/var`).
        let landsOn = bridge.resolvingSymlinksInPath().standardizedFileURL.path
        var inside = resources.resolvingSymlinksInPath().standardizedFileURL.path
        // The separator is load-bearing: without it `/x/Resources` would also contain
        // `/x/ResourcesElsewhere/agterm-remote-bridge`.
        if !inside.hasSuffix("/") { inside += "/" }
        return landsOn.hasPrefix(inside) ? bridge : nil
    }
}
