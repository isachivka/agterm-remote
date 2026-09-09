import Foundation
import Testing
@testable import AgtermRemoteCore

/// **Where the bridge lives inside the app, asserted against a real bundle on disk.**
///
/// The promise this whole macOS design rests on is that somebody installs one thing. No `go install`,
/// no launchd plist, no daemon left behind: the app carries the bridge in `Contents/Resources`,
/// spawns it as a child, and it dies with the app. `BundledBridge` is the one place that knows that
/// layout, and this file is what holds it to it.
///
/// ### Why the bundles here are built rather than mocked
///
/// `Bundle.resourceURL` is the part that can be wrong. A stub returning a path would assert that this
/// file agrees with itself; a directory laid out the way `bundle.sh` lays one out asserts that
/// Foundation resolves `Contents/Resources` where the script wrote it. Measured: `Bundle(url:)`
/// answers nil for a directory that is not a bundle at all, which is a third failure mode and has its
/// own case below.
struct BundleLayoutTests {

    /// Lays out `<name>.app/Contents/Resources` in a fresh temporary directory and returns the
    /// `Bundle` for it. `contents` names the files to create in `Resources`, with the mode to give
    /// them — 0o755 for something spawnable, 0o644 for a file that is present and useless.
    private func bundle(named name: String, containing contents: [String: Int16]) throws -> Bundle {
        let root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appending(path: "agterm-remote-bundle-layout-\(UUID().uuidString)")
        let resources = root.appending(path: "\(name).app/Contents/Resources")
        try FileManager.default.createDirectory(at: resources, withIntermediateDirectories: true)
        for (file, mode) in contents {
            let at = resources.appending(path: file)
            FileManager.default.createFile(atPath: at.path, contents: Data())
            try FileManager.default.setAttributes([.posixPermissions: mode], ofItemAtPath: at.path)
        }
        return try #require(Bundle(url: resources.deletingLastPathComponent().deletingLastPathComponent()))
    }

    @Test func theBridgeIsLookedUpInResources() throws {
        let app = try bundle(named: "AgtermRemote", containing: ["agterm-remote-bridge": 0o755])

        let url = BundledBridge.url(in: app)

        #expect(url?.lastPathComponent == "agterm-remote-bridge")
        #expect(url?.path.contains("Resources") == true)
    }

    /// **The failure mode is the point.** A nil here is what greys Start and Stop, so a bundle without
    /// the binary has to answer nil rather than a path that is not there — a URL to a missing file
    /// would light both items and fail at the press, which is the exact defect the menu was fixed for.
    @Test func aBundleThatCarriesNoBridgeAnswersNothing() throws {
        let app = try bundle(named: "AgtermRemote", containing: [:])

        #expect(BundledBridge.url(in: app) == nil)
    }

    /// Present and not spawnable is the same answer as absent. `lipo` writing into a directory nobody
    /// chmod'd, or a bundle assembled by hand, produces a file that exists and cannot be run; treating
    /// it as found would trade a greyed menu item for a spawn failure at the press.
    @Test func aFileThatIsNotExecutableIsNotTheBridge() throws {
        let app = try bundle(named: "AgtermRemote", containing: ["agterm-remote-bridge": 0o644])

        #expect(BundledBridge.url(in: app) == nil)
    }

    /// A neighbouring file with a similar job is not the bridge. The name is exact.
    @Test func nothingElseInResourcesIsMistakenForIt() throws {
        let app = try bundle(named: "AgtermRemote", containing: [
            "AgtermRemote.icns": 0o644, "agterm-remote-bridge-old": 0o755,
        ])

        #expect(BundledBridge.url(in: app) == nil)
    }

    /// The test bundle this suite is running from carries no bridge, and never will. It is here
    /// because it is the one bundle that exists without anybody building it, and it answers nil.
    @Test func theSuitesOwnBundleAnswersNothing() {
        #expect(BundledBridge.url(in: Bundle(for: Marker.self)) == nil)
    }

    /// **One name, shared with the supervisor.** The app spawns what it finds, and a lookup that
    /// resolved a different file name from the one `BoundaryTests` allows would walk around that
    /// allow-list by spelling.
    @Test func theNameIsTheOneTheSupervisorIsAllowedToLaunch() {
        #expect(BundledBridge.executableName == BridgeProcess.executableName)
    }
}

/// Only so `Bundle(for:)` has a class to be handed. A struct-based suite has no type for it.
private final class Marker {}
