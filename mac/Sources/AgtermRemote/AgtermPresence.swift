import AppKit

/// Whether agterm is there — **asked of the running applications, never of agterm's control socket.**
///
/// ### Why not the socket, which is what the design document says to check
///
/// This app supervises one process and reaches nothing else. Every command a phone can cause has
/// argued its way past the allowlist inside the bridge; a menu-bar app that opened agterm's control
/// socket would walk around that allowlist entirely, so `BoundaryTests` fails the build if any source
/// in either target so much as spells that path. The rule is older than this screen and it is right,
/// so onboarding asks a question it is allowed to ask.
///
/// It is also the better question. A socket file is left behind by a terminal that crashed, and a
/// path that exists is not a terminal that answers. What the bridge needs on the other end is a
/// **running agterm**, which is exactly what this reports.
///
/// ### What it costs to be wrong
///
/// A false *no* sends somebody to start an application that is already running, and one press of
/// *Look again* undoes it. A false *yes* lets them go on to set an address for a terminal that is not
/// there, and the bridge then says so when a session is asked for. Neither is silent, which is the
/// property that matters.
enum AgtermPresence {

    /// agterm's own bundle identifier. One constant, so the day it changes there is one place to
    /// change it rather than a predicate spread across the app.
    static let bundleIdentifier = "com.umputun.agterm"

    /// True when agterm is running for this user.
    ///
    /// The bundle identifier is the reliable half; the application's own name is the fallback for a
    /// build that is not signed with it — somebody running agterm from source, which is precisely the
    /// audience this project has. Our own process is never a match: this app's identifier is
    /// different, and nothing here looks at process names.
    static func isRunning(_ workspace: NSWorkspace = .shared) -> Bool {
        workspace.runningApplications.contains {
            $0.bundleIdentifier == bundleIdentifier
                || $0.bundleURL?.deletingPathExtension().lastPathComponent.lowercased() == "agterm"
        }
    }
}
