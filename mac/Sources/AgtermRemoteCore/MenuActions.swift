import Foundation

/// What happens when a menu item is pressed, as a value the tests can watch.
///
/// ### Why this type exists, in one sentence
///
/// On 2026-08-10 the owner pressed *Set the address* and *Show the pairing code* on the running app
/// and **nothing happened**, because only `quit` had a selector and every other item was enabled with
/// no action behind it.
///
/// ### And why the test suite did not catch it
///
/// `MenuReachabilityTests` asserts every action is **enabled in at least one state**. It never asserts
/// that any action **does** anything, so it passes on a menu of five inert items — which is exactly
/// what shipped. **Enabled is not wired.** The assertion that replaces it needs somewhere to look, and
/// this is that somewhere: pressing an action goes through a sink that records the invocation, so a
/// test can see the difference between a button that works and a button that is merely lit.
///
/// ### The structural rule that follows
///
/// [implemented] is the single source of both facts: an item is offered **only** if the action is in
/// that set, and the menu attaches a handler **only** for actions in that set. They cannot disagree,
/// because they are the same set. Before this, `isEnabled` came from the model's opinion alone and a
/// dead item looked exactly like a live one — the owner pressed a lie, and that is how we got here.
public protocol MenuActions: Sendable {

    /// Which actions actually do something in this build. **An action absent here can never be
    /// pressable**, because the menu derives both the handler and the enabled state from it.
    var implemented: Set<MenuAction> { get }

    /// Perform one. Only ever called for actions in [implemented].
    func perform(_ action: MenuAction)
}

/// A sink that records rather than acts. **Tests only** — it is what makes "pressing this does
/// something" an observable fact instead of an assumption.
public final class RecordingMenuActions: MenuActions, @unchecked Sendable {

    public private(set) var invocations: [MenuAction] = []
    public let implemented: Set<MenuAction>

    /// Defaults to everything, so a test that cares about wiring does not have to list the world.
    /// Pass a smaller set to model a half-built menu — which is the control the replacement assertion
    /// needs.
    public init(implemented: Set<MenuAction> = Set(MenuAction.allCases)) {
        self.implemented = implemented
    }

    public func perform(_ action: MenuAction) {
        invocations.append(action)
    }
}
