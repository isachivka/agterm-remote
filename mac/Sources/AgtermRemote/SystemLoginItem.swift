import AgtermRemoteCore
import ServiceManagement

/// The real `SMAppService`, and the only part of the login item that cannot be tested.
///
/// **Nothing in the test suite may register a login item.** A test that did would change the machine
/// running it — the developer's Mac, or a CI runner that is about to be the owner's Mac — and a test
/// that alters the system it is measuring is not a test. So the decisions live in `LoginItem`, where
/// they are exercised against a fake, and this file is the thin translation of Apple's enum into ours.
///
/// It is thin on purpose: every line here is a line that only the owner's own run can prove.
struct SystemLoginItem: LoginItem.Service {

    private var service: SMAppService { .mainApp }

    /// Read every time, never stored. The owner can switch this off in System Settings while the app
    /// is running, and a remembered answer is how an app tells somebody something that stopped being
    /// true.
    func status() -> LoginItem.Registration {
        switch service.status {
        case .enabled: .registered
        case .notRegistered: .notRegistered
        // The owner has been asked and has not answered. Reporting this as enabled would be a lie
        // they discover at the next reboot; reporting it as notRegistered would have the toggle fight
        // them by trying to register something already pending.
        case .requiresApproval: .awaitingApproval
        // `.notFound` and anything a future macOS adds. Named rather than folded into notRegistered:
        // guessing here produces a toggle that argues with the system.
        default: .unknown
        }
    }

    func register() throws {
        try service.register()
    }

    func unregister() throws {
        try service.unregister()
    }
}
