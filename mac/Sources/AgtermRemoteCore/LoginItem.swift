import Foundation

/// Starting at login, and — the part that matters — stopping.
///
/// ### This is the first thing in this app that changes the owner's machine
///
/// Everything before it read a file, formatted a string, or asked launchd about a job that already
/// existed. Registering a login item **writes into the owner's system settings** and makes this app
/// start on every boot, whether or not they remember installing it.
///
/// So the assertion that matters is not that registration works. It is that **removal works and can
/// be seen to have worked**: unregister, then read the status back and find it not-registered. An app
/// that installs itself into login items and can only be removed from inside itself is a bad
/// neighbour; an unsigned one that did that would be worse.
///
/// ### Two ways out, and neither is ours alone
///
/// `SMAppService` registrations appear in **System Settings → General → Login Items & Extensions**,
/// so the owner can switch this off without asking us and without launching it. The app's own toggle
/// is a convenience on top of that, never the only route. `LoginItemTests` pins that the toggle
/// reports what the system says rather than what the app last did.
public struct LoginItem: Sendable {

    /// What the system says, read back rather than remembered.
    ///
    /// **Never cached.** The owner can turn this off in System Settings while the app is running, and
    /// a remembered `true` is how an app tells somebody something that stopped being true — the same
    /// rule the phone's pairing screen follows for the camera, for the same reason: the affordance
    /// exists whenever the thing behind it does, and the permission is asked when it is used.
    public enum Registration: Equatable, Sendable {
        case registered
        case notRegistered
        /// The system has a state for "the owner has to approve this", and it is neither of the above.
        /// Showing it as registered would be a lie the owner discovers at the next reboot.
        case awaitingApproval
        /// Something the running OS reports that this code does not model. Named rather than folded
        /// into `notRegistered`, because guessing here produces a toggle that fights the owner.
        case unknown
    }

    /// The system service, injected so the decisions above are testable without registering anything
    /// on the machine running the tests. **A test that really registered a login item would be a test
    /// that changes the developer's Mac**, which is not a thing a test may do.
    public protocol Service: Sendable {
        func status() -> Registration
        func register() throws
        func unregister() throws
    }

    public enum Failure: Error, Equatable {
        /// Asked to stop starting at login, and afterwards the system still says it will.
        case stillRegisteredAfterRemoval(Registration)
        /// Asked to start at login, and afterwards the system does not agree.
        case notRegisteredAfterRequest(Registration)
    }

    private let service: any Service

    public init(service: any Service) {
        self.service = service
    }

    public func status() -> Registration { service.status() }

    /// Registers, then **reads the status back and insists the system agrees.**
    ///
    /// `awaitingApproval` is a success here: the owner has been asked, and the honest report is that
    /// it is pending rather than done.
    public func startAtLogin() throws -> Registration {
        try service.register()
        let after = service.status()
        guard after == .registered || after == .awaitingApproval else {
            throw Failure.notRegisteredAfterRequest(after)
        }
        return after
    }

    /// Unregisters, then **reads the status back and insists it is gone.**
    ///
    /// This is the assertion the whole type exists for. Calling `unregister()` and reporting success
    /// because it did not throw is how an app leaves itself in somebody's login items while telling
    /// them it did not.
    @discardableResult
    public func stopStartingAtLogin() throws -> Registration {
        try service.unregister()
        let after = service.status()
        guard after == .notRegistered else {
            throw Failure.stillRegisteredAfterRemoval(after)
        }
        return after
    }
}
