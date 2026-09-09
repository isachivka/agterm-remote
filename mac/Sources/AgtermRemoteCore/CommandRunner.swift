import Foundation

/// Running a command and reading what it said, as a value and a function type.
///
/// ### Why these two live on their own
///
/// They were nested inside `BridgeSupervisor` — the type that asked `launchctl` to act on a launchd
/// job. That job never existed in this repository: no plist is written here and no installer writes
/// one, so every Start and Stop in the menu was a request against a label launchd had never heard of.
/// The supervisor is gone, replaced by [BridgeProcess], which holds the bridge as a child.
///
/// These outlived it because they were never about launchd. The pairing-code path runs `bridgecert`
/// and reads its output, and that is a different thing from supervising a long-lived process: one
/// finishes and hands back text, the other is started, watched and terminated. Leaving them nested
/// inside a type about the bridge's lifecycle would have made the pairing code look like part of it.
public struct CommandInvocation: Equatable, Sendable {
    public let executable: String
    public let arguments: [String]

    public init(executable: String, arguments: [String]) {
        self.executable = executable
        self.arguments = arguments
    }
}

/// Runs a command to completion and reports how it went. Injected everywhere, so the exact argv can
/// be asserted in a test without anything being spawned.
public typealias CommandRunner = @Sendable (CommandInvocation) -> (status: Int32, output: String)
