import Foundation
import Testing
@testable import AgtermRemoteCore

/// Registering is easy. **The assertion that matters is the removal**, and that it is seen to have
/// worked rather than assumed from a call that did not throw.
struct LoginItemTests {

    private final class Fake: LoginItem.Service, @unchecked Sendable {
        var state: LoginItem.Registration = .notRegistered
        /// What the system reports after the call, when the fake is told to disagree with it.
        var stateAfterRegister: LoginItem.Registration?
        var stateAfterUnregister: LoginItem.Registration?
        var registerError: (any Error)?
        var unregisterError: (any Error)?
        var calls: [String] = []

        func status() -> LoginItem.Registration { state }

        func register() throws {
            calls.append("register")
            if let registerError { throw registerError }
            state = stateAfterRegister ?? .registered
        }

        func unregister() throws {
            calls.append("unregister")
            if let unregisterError { throw unregisterError }
            state = stateAfterUnregister ?? .notRegistered
        }
    }

    private struct Refused: Error {}

    /// **The one this type exists for.** Unregister, then read it back and find it gone.
    @Test func removalIsConfirmedByReadingTheStatusBack() throws {
        let fake = Fake()
        fake.state = .registered

        let after = try LoginItem(service: fake).stopStartingAtLogin()

        #expect(after == .notRegistered)
        #expect(fake.calls == ["unregister"])
    }

    /// A call that did not throw is not evidence. If the system still says it will start at login,
    /// that is a failure and the owner is told, rather than being shown a toggle that lies.
    @Test func removalThatDidNotTakeIsAFailureAndNotASuccess() {
        let fake = Fake()
        fake.state = .registered
        fake.stateAfterUnregister = .registered

        #expect(throws: LoginItem.Failure.stillRegisteredAfterRemoval(.registered)) {
            try LoginItem(service: fake).stopStartingAtLogin()
        }
    }

    /// Half-removed is not removed. `awaitingApproval` after an unregister means the system has not
    /// finished with it, and reporting that as gone is the same lie one step quieter.
    @Test func aRemovalLeftAwaitingApprovalIsNotARemoval() {
        let fake = Fake()
        fake.state = .registered
        fake.stateAfterUnregister = .awaitingApproval

        #expect(throws: LoginItem.Failure.stillRegisteredAfterRemoval(.awaitingApproval)) {
            try LoginItem(service: fake).stopStartingAtLogin()
        }
    }

    @Test func registrationIsAlsoConfirmedRatherThanAssumed() throws {
        let fake = Fake()

        let after = try LoginItem(service: fake).startAtLogin()

        #expect(after == .registered)
        #expect(fake.calls == ["register"])
    }

    /// Pending approval is an honest outcome of asking, so it is reported as itself rather than as
    /// done — the owner finds out at the next reboot otherwise.
    @Test func awaitingApprovalAfterRegisteringIsReportedAsPending() throws {
        let fake = Fake()
        fake.stateAfterRegister = .awaitingApproval

        #expect(try LoginItem(service: fake).startAtLogin() == .awaitingApproval)
    }

    @Test func registeringThatDidNotTakeIsAFailure() {
        let fake = Fake()
        fake.stateAfterRegister = .notRegistered

        #expect(throws: LoginItem.Failure.notRegisteredAfterRequest(.notRegistered)) {
            try LoginItem(service: fake).startAtLogin()
        }
    }

    /// **The status is read from the system, never remembered.** The owner can switch this off in
    /// System Settings while the app is running, and a cached `true` is how an app tells somebody
    /// something that stopped being true.
    @Test func theStatusFollowsTheSystemRatherThanWhatTheAppLastDid() throws {
        let fake = Fake()
        let item = LoginItem(service: fake)
        _ = try item.startAtLogin()
        #expect(item.status() == .registered)

        // The owner turns it off in System Settings. Nothing tells the app.
        fake.state = .notRegistered

        #expect(item.status() == .notRegistered, "the app is reporting its own memory, not the system")
    }

    /// A refusal from the system is not swallowed: an owner who pressed a toggle and saw nothing
    /// happen has no idea whether it worked.
    @Test func aRefusalFromTheSystemIsRaisedRatherThanSwallowed() {
        let fake = Fake()
        fake.state = .registered
        fake.unregisterError = Refused()

        #expect(throws: Refused.self) {
            try LoginItem(service: fake).stopStartingAtLogin()
        }
    }

    /// Constructing it registers nothing. The first thing in this branch that changes the owner's
    /// machine may only do so when the owner asks.
    @Test func constructingItChangesNothing() {
        let fake = Fake()

        _ = LoginItem(service: fake)

        #expect(fake.calls.isEmpty)
        #expect(fake.state == .notRegistered)
    }
}
