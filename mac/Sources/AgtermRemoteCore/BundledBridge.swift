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

    /// The embedded bridge, or nil.
    ///
    /// Two things have to be true, and the second is not decoration: the file must be there **and**
    /// it must be spawnable. A `agterm-remote-bridge` that exists with the wrong mode — a bundle
    /// assembled by hand, an archive unpacked by something that dropped the executable bit — is
    /// indistinguishable from a working install until the moment somebody presses Start. Answering
    /// nil for it turns that into a greyed item instead.
    ///
    /// - Parameter isExecutable: injected so the decision can be asserted without a file system,
    ///   the same seam `BridgeProcess.locate` uses.
    public static func url(
        in bundle: Bundle,
        isExecutable: (String) -> Bool = { FileManager.default.isExecutableFile(atPath: $0) },
    ) -> URL? {
        // A bundle with no resource directory at all — which is what Foundation reports for one that
        // was never assembled — is the same answer as an empty one.
        guard let resources = bundle.resourceURL else { return nil }
        let bridge = candidate(inResources: resources)
        return isExecutable(bridge.path) ? bridge : nil
    }
}
