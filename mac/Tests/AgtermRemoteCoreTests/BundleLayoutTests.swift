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

    /// **A directory wearing the name passes `isExecutableFile`.** Measured, not supposed: mode 755
    /// on a directory answers true, and the app would have carried that URL to `Process.run` and
    /// reported the failure as its own. The type is asked as well as the mode.
    @Test func aDirectoryWearingTheNameIsNotTheBridge() throws {
        let app = try bundle(named: "AgtermRemote", containing: [:])
        let resources = try #require(app.resourceURL)
        let impostor = resources.appending(path: "agterm-remote-bridge")
        try FileManager.default.createDirectory(at: impostor, withIntermediateDirectories: false)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: impostor.path)

        #expect(FileManager.default.isExecutableFile(atPath: impostor.path), "the trap this closes")
        #expect(BundledBridge.isSpawnable(impostor.path) == false)
        #expect(BundledBridge.url(in: app) == nil)
    }

    /// **A link out of the bundle is not the bridge this bundle carries.**
    ///
    /// `isExecutableFile` follows symbolic links, so without a containment check a link planted in
    /// `Contents/Resources` would make the app spawn a binary from anywhere on the disk while every
    /// other check said it came from inside. The bundle's signature seals the link and not its
    /// target, so the seal does not cover this either.
    @Test func aLinkPointingOutOfTheBundleIsNotTheBridge() throws {
        let app = try bundle(named: "AgtermRemote", containing: [:])
        let resources = try #require(app.resourceURL)
        // Somewhere else entirely, and genuinely executable.
        let elsewhere = URL(fileURLWithPath: NSTemporaryDirectory())
            .appending(path: "elsewhere-\(UUID().uuidString)")
        FileManager.default.createFile(atPath: elsewhere.path, contents: Data())
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: elsewhere.path)
        let planted = resources.appending(path: "agterm-remote-bridge")
        try FileManager.default.createSymbolicLink(at: planted, withDestinationURL: elsewhere)

        #expect(FileManager.default.isExecutableFile(atPath: planted.path), "the trap this closes")
        #expect(BundledBridge.url(in: app) == nil)
    }

    /// And the containment check does not reject an ordinary bundle. Every temporary directory on
    /// macOS is reached through a symbolic link (`/var` to `/private/var`), so a check that compared
    /// unresolved paths would answer nil for every bundle in this suite — and, worse, pass on the
    /// developer's own machine and fail somewhere else.
    @Test func anOrdinaryBundleIsNotMistakenForAPlantedLink() throws {
        let app = try bundle(named: "AgtermRemote", containing: ["agterm-remote-bridge": 0o755])

        #expect(BundledBridge.url(in: app) != nil)
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

/// **The attribute that decides whether the bridge will run at all, read the way the app reads it.**
///
/// Against a real file with a real extended attribute, not a stub. The whole claim this rests on is
/// that an *unentitled, ad-hoc-signed* process can read `com.apple.quarantine` even though it cannot
/// remove it — and a stubbed `getxattr` would assert nothing about that.
struct QuarantineTests {

    private func file(quarantined: Bool) throws -> URL {
        let at = URL(fileURLWithPath: NSTemporaryDirectory())
            .appending(path: "agterm-remote-quarantine-\(UUID().uuidString)")
        FileManager.default.createFile(atPath: at.path, contents: Data("x".utf8))
        if quarantined {
            let value = "0081;00000000;test;"
            let written = setxattr(at.path, Quarantine.attribute, value, value.utf8.count, 0, 0)
            try #require(written == 0, "could not set the attribute this suite is about")
        }
        return at
    }

    @Test func aQuarantinedFileIsSeenAsQuarantined() throws {
        #expect(Quarantine.isSet(on: try file(quarantined: true)))
    }

    @Test func anOrdinaryFileIsNot() throws {
        #expect(Quarantine.isSet(on: try file(quarantined: false)) == false)
    }

    /// A file that is not there is not quarantined either. It is a state the caller has already
    /// excluded — the binary was located before this is asked — and it must not read as held.
    @Test func aFileThatIsNotThereIsNotQuarantined() {
        #expect(Quarantine.isSet(on: URL(fileURLWithPath: "/nowhere/agterm-remote-bridge")) == false)
    }

    /// **The app CAN clear this, and that is exactly why the rule needs a test rather than a note.**
    ///
    /// It is tempting to write that macOS forbids it. Measured here, on this machine: `removexattr`
    /// from an ordinary unentitled process succeeds. So the reason the app does not is a decision —
    /// quarantine is the record that this code came from outside, and an app that erases that record
    /// about itself because somebody pressed Start has removed the only Gatekeeper signal a
    /// non-notarised application is subject to.
    ///
    /// A decision is what drifts. This test states the capability so nobody re-derives the false
    /// premise, and the one below holds the app to the choice.
    @Test func theAppCouldClearItWhichIsWhyTheChoiceIsWrittenDown() throws {
        let held = try file(quarantined: true)

        #expect(removexattr(held.path, Quarantine.attribute, 0) == 0, "removal is permitted")
        #expect(Quarantine.isSet(on: held) == false, "and it worked")
    }

    /// **So the app never writes an extended attribute, and never removes one.** It reads.
    ///
    /// Held at the source, the way `BoundaryTests` holds the list of processes this app may launch:
    /// the capability is available and one line would use it, so the check names the two calls rather
    /// than trusting a paragraph above them.
    @Test func nothingInThisAppRemovesOrSetsAnExtendedAttribute() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        var checked = 0
        for target in ["Sources/AgtermRemoteCore", "Sources/AgtermRemote"] {
            let directory = root.appending(path: target)
            for name in try FileManager.default.contentsOfDirectory(atPath: directory.path)
                where name.hasSuffix(".swift") {
                let text = try String(contentsOf: directory.appending(path: name), encoding: .utf8)
                checked += 1
                for call in ["removexattr(", "setxattr("] {
                    #expect(!text.contains(call), "\(target)/\(name) calls \(call)")
                }
            }
        }
        #expect(checked > 0, "found no sources — the detector is looking in the wrong place")
    }
}
