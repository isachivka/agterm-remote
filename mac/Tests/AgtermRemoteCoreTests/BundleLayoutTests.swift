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

    /// **An ordinary development symlink is accepted.**
    ///
    /// This is the half the containment check nearly cost. `.isRegularFileKey` does not follow links,
    /// so asking it of the path as given rejected *every* symlink — including the one somebody makes
    /// during development to point a build directory at a Go tree, which is the case `locate` exists
    /// to serve. It greyed both menu items with no explanation. The link is resolved first, so what
    /// is judged is the file at the end of it.
    @Test func aSymlinkToARealBinaryInsideTheBundleIsStillTheBridge() throws {
        let app = try bundle(named: "AgtermRemote", containing: ["real-bridge": 0o755])
        let resources = try #require(app.resourceURL)
        try FileManager.default.createSymbolicLink(
            at: resources.appending(path: "agterm-remote-bridge"),
            withDestinationURL: resources.appending(path: "real-bridge"))

        // Spawnable: the link resolves to an executable regular file, which is all `locate` asks.
        #expect(BundledBridge.isSpawnable(resources.appending(path: "agterm-remote-bridge").path))
        // And accepted, because what it points at is still something this bundle carries. The rule
        // is "inside the resource directory", not "at exactly this path" — the stricter version
        // refused this too, which is a rule stricter than its own reason.
        #expect(BundledBridge.url(in: app) != nil)
    }

    /// And `locate` — where containment is not the question — accepts one beside the executable,
    /// which is exactly the development layout `mac/README.md` tells people to create.
    @Test func aDevelopmentBridgeReachedThroughASymlinkIsFound() throws {
        let build = URL(fileURLWithPath: NSTemporaryDirectory())
            .appending(path: "agterm-remote-dev-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: build, withIntermediateDirectories: true)
        let real = build.appending(path: "built-by-go")
        FileManager.default.createFile(atPath: real.path, contents: Data())
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: real.path)
        try FileManager.default.createSymbolicLink(
            at: build.appending(path: "agterm-remote-bridge"), withDestinationURL: real)

        let found = BridgeProcess.locate(
            resources: nil, beside: build.appending(path: "AgtermRemote"),
            stateDir: URL(fileURLWithPath: "/nowhere"))

        #expect(found?.lastPathComponent == "agterm-remote-bridge")
    }

    /// **A link out of the bundle is not the bridge this bundle carries.**
    ///
    /// Both checks above follow symbolic links — they have to, or the development layout would fail
    /// — so containment is the only thing that catches a link planted in `Contents/Resources`
    /// pointing at a binary from anywhere on the disk. The bundle's signature seals the link and not
    /// its target, so the seal does not cover this either.
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
        // It passes the spawnability test — that is the point. Containment is what refuses it, and
        // if this expectation ever flips, the containment check below has become dead code.
        #expect(BundledBridge.isSpawnable(planted.path), "the earlier guard must NOT be what stops it")
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
/// Against real files with real extended attributes, not a stub. The whole claim this rests on is
/// that an *unentitled, ad-hoc-signed* process can read `com.apple.quarantine` — and a stubbed
/// `getxattr` would assert nothing about that.
struct QuarantineTests {

    private func file(_ value: String?) throws -> URL {
        let at = URL(fileURLWithPath: NSTemporaryDirectory())
            .appending(path: "agterm-remote-quarantine-\(UUID().uuidString)")
        FileManager.default.createFile(atPath: at.path, contents: Data("x".utf8))
        if let value {
            let written = setxattr(at.path, Quarantine.attribute, value, value.utf8.count, 0, 0)
            try #require(written == 0, "could not set the attribute this suite is about")
        }
        return at
    }

    /// **Presence is not the condition, and this is the case that made that expensive.**
    ///
    /// Every downloaded application on the machine this was measured on still carries the attribute
    /// while running perfectly — `01c1`, `03c1`. Approving an app does not remove it; it sets a bit.
    /// A check on presence alone refused every one of them, which is the path every real owner takes.
    @Test(arguments: [
        // flags, held?
        ("0081;6aa0e764;Safari;", true),
        ("0083;6aa0e764;Safari;", true),
        ("0041;6aa0e764;Safari;", false),
        ("00c1;6aa0e764;Safari;", false),
        ("01c1;6aa0e764;Arc;E1D0A0E6-0000-4000-8000-000000000000", false),
        ("03c1;6aa0e764;Arc;E1D0A0E6-0000-4000-8000-000000000000", false),
    ])
    func onlyAnUnapprovedQuarantineWouldHoldTheBinary(value: String, held: Bool) throws {
        #expect(Quarantine.wouldBeHeld(try file(value)) == held, "flags \(value.prefix(4))")
    }

    /// The bit itself, named once and asserted against the two spellings it arrives in.
    @Test func theApprovedBitIsTheOneMacOSSets() {
        #expect(Quarantine.flags(in: "0081;a;b;c") == 0x0081)
        #expect(Quarantine.flags(in: "03c1;a;b;c")! & Quarantine.userApproved != 0)
        #expect(Quarantine.flags(in: "0081;a;b;c")! & Quarantine.userApproved == 0)
    }

    @Test func anOrdinaryFileIsNotHeld() throws {
        #expect(Quarantine.wouldBeHeld(try file(nil)) == false)
    }

    /// **An unreadable value lets the binary through, deliberately.**
    ///
    /// The two errors are not symmetrical. Wrongly refusing costs a working application and a false
    /// explanation — the defect this rule replaced. Wrongly allowing costs one silent hang, which the
    /// launch backstop catches and reports with the evidence in it.
    @Test func aValueThisCannotParseIsNotTreatedAsAHold() throws {
        #expect(Quarantine.wouldBeHeld(try file("not-hexadecimal;a;b;c")) == false)
        #expect(Quarantine.wouldBeHeld(try file("")) == false)
    }

    /// A file that is not there is not held either. It is a state the caller has already excluded —
    /// the binary was located before this is asked — and it must not read as held.
    @Test func aFileThatIsNotThereIsNotHeld() {
        #expect(Quarantine.wouldBeHeld(URL(fileURLWithPath: "/nowhere/agterm-remote-bridge")) == false)
    }

    /// **The app never writes an extended attribute, and never removes one.** It reads.
    ///
    /// Held at the source, the way `BoundaryTests` holds the list of processes this app may launch.
    ///
    /// This is the whole of the rule, and it deliberately makes no claim about whether removal
    /// *would* succeed. Two review rounds have carried a confident answer in opposite directions —
    /// that macOS forbids it, then that it does not — and measurement here (unentitled, and from
    /// inside the quarantined bundle, across approved and unapproved flags, with and without a UUID)
    /// succeeded every time while review measured EPERM. The reason the app does not do it survives
    /// either answer: quarantine is macOS's record that this code came from outside, and an app that
    /// erases that record about itself has removed the only Gatekeeper signal a non-notarised
    /// application is subject to.
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
