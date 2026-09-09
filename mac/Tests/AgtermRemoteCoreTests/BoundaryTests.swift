import Foundation
import Testing
@testable import AgtermRemoteCore

/// **Written before the first line of code that can launch a process, deliberately.**
///
/// A test written after a capability exists is a test written to pass: it gets shaped, unconsciously,
/// around whatever the code already does. This one was committed in the same change as the supervisor
/// but authored first, and it failed on nothing because there was nothing to fail on — which is the
/// only moment a boundary test is honest.
///
/// ### The boundary
///
/// This app **supervises one process and reaches nothing else.** It may start, stop and restart the
/// bridge. It gains no agterm verb, it never opens agterm's control socket, and it grows no place to
/// type a command.
///
/// Every command the phone can cause has argued its way past the allowlist inside the bridge. A menu
/// bar app that spoke to agterm directly would walk around that allowlist entirely — the same hole a
/// self-hosted CI runner opens, and the reason both are guarded here rather than promised in a
/// document.
struct BoundaryTests {

    private func sources() throws -> [(name: String, text: String)] {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        var found: [(String, String)] = []
        for target in ["Sources/AgtermRemoteCore", "Sources/AgtermRemote"] {
            let directory = root.appending(path: target)
            guard FileManager.default.fileExists(atPath: directory.path) else { continue }
            for name in try FileManager.default.contentsOfDirectory(atPath: directory.path)
                where name.hasSuffix(".swift") {
                found.append((
                    "\(target)/\(name)",
                    NoAggregateVerdictTests.stripped(
                        try String(contentsOf: directory.appending(path: name), encoding: .utf8)),
                ))
            }
        }
        #expect(!found.isEmpty, "found no sources — the detector is looking in the wrong place")
        return found
    }

    /// agterm's control socket, by both of the paths it is known by.
    private static let sockets = ["agterm.sock", "control.sock", "Application Support/agterm"]

    /// Every verb the bridge's allowlist holds, read off `bridge/internal/agterm`. If this app names
    /// one of them it is speaking a protocol it has no business speaking.
    private static let verbs = [
        "session.close", "session.delete", "session.new", "session.rename", "session.search",
        "session.text", "session.type", "window.close", "window.list", "window.resize", "window.zoom",
    ]

    @Test func theAppNeverNamesAgtermsControlSocket() throws {
        for source in try sources() {
            for socket in Self.sockets {
                #expect(!source.text.contains(socket), "\(source.name) reaches for agterm's socket (\(socket))")
            }
        }
    }

    @Test func theAppKnowsNoAgtermVerb() throws {
        for source in try sources() {
            for verb in Self.verbs {
                #expect(!source.text.contains(verb), "\(source.name) speaks an agterm verb (\(verb))")
            }
        }
    }

    /// **The processes this app may launch, named one by one.**
    ///
    /// Not "we do not launch anything else" — the check names each executable, so a second `Process()`
    /// pointed at a shell fails here rather than in review.
    ///
    /// The list has only ever grown by a line in this file. It began as launchctl alone; the
    /// certificate helper was added so a pairing code could be rendered by the side that owns the
    /// payload. It also **shrank** by a line: `pin` went with the drag-and-drop certificate handover,
    /// because the phone now proves itself during enrolment and there is no PEM for anybody to carry
    /// across. A boundary that only ever widens is a boundary nobody is reading.
    private static let allowedExecutables = ["/bin/launchctl", "bridgecert"]

    @Test func theOnlyProcessesLaunchedAreLaunchctlAndTheCertificateTool() throws {
        for source in try sources() {
            for forbidden in ["/bin/sh", "/bin/bash", "/bin/zsh", "-c \"", "system(", "posix_spawn", "NSTask"] {
                #expect(!source.text.contains(forbidden), "\(source.name) can run a shell (\(forbidden))")
            }
            if source.text.contains("Process(") {
                #expect(
                    Self.allowedExecutables.contains(where: source.text.contains),
                    "\(source.name) launches a process that is on no allow-list")
            }
        }
    }

    /// **Making a code makes a picture and nothing else.**
    ///
    /// This test was once called *the only subcommand is qr*, and it kept passing after a second,
    /// destructive subcommand was admitted — because it only ever looked at the `qr` invocation. **A
    /// test whose name claims more than it checks is the same defect as a menu item that looks
    /// pressable and is not**, so it is named for what it actually asserts: this one invocation, and
    /// the destructive verbs it must never grow.
    @Test func theCodeInvocationCanOnlyEverMakeACode() {
        let address = DialAddress(host: "agterm.example-homelab.invalid", port: 8443)

        let invocation = PairingCodeCommand.invocation(binary: "/opt/bin/bridgecert", address: address)

        #expect(invocation.arguments.first == "qr")
        for destructive in ["pin", "mint", "rotate", "delete"] {
            #expect(!invocation.arguments.contains(destructive), "making a code can run \(destructive)")
        }
    }

    /// The controls. Each detector is shown a violation in code and must see it, and shown the same
    /// words in a comment and must not — three detectors today needed repair before they could be
    /// trusted, so none of them ships without this.
    @Test func everyBoundaryDetectorSeesCodeAndIgnoresComments() {
        let plantedSocket = NoAggregateVerdictTests.stripped(
            #"let s = home.appending(path: "Library/Application Support/agterm/agterm.sock")"#)
        let plantedVerb = NoAggregateVerdictTests.stripped(#"try send(verb: "session.type", text: line)"#)
        let plantedShell = NoAggregateVerdictTests.stripped(#"task.executableURL = URL(filePath: "/bin/sh")"#)
        let discussed = NoAggregateVerdictTests.stripped(
            "// This never opens agterm.sock and knows no session.type verb, and /bin/sh is not run.")

        #expect(Self.sockets.contains { plantedSocket.contains($0) })
        #expect(Self.verbs.contains { plantedVerb.contains($0) })
        #expect(plantedShell.contains("/bin/sh"))
        #expect(!Self.sockets.contains { discussed.contains($0) }, "the detector fires on its own prose")
        #expect(!Self.verbs.contains { discussed.contains($0) }, "the detector fires on its own prose")
    }
}
