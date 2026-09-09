import Foundation

/// **What the owner is told about the bridge, and where.**
///
/// Two decisions, and both used to live inside the menu-bar app where nothing could ask them a
/// question. They are here because each one was got wrong in a way no test could see.
public enum BridgeReport {

    /// The status item's tooltip.
    ///
    /// ### It is a function of the whole state, not a thing that is set when there is bad news
    ///
    /// The app did this: `if let failure { toolTip = failure }` — a line that can set a tooltip and
    /// has no way to take one back. So a start that failed, was fixed and was started again left the
    /// failure hanging there for the rest of the session: the menu's own failure line went away with
    /// the state, and hovering the icon on a healthy bridge produced a sentence about a port clash
    /// from ten minutes ago. **A surface that can only be written is a surface that goes stale**, and
    /// the fix is that there is always something true to say.
    ///
    /// - Parameter failure: the last thing the bridge said on its way out, or nil once that has
    ///   stopped being true. Nil is the case the old shape could not express.
    public static func tooltip(for state: BridgeState, failure: String?) -> String {
        if let failure, !failure.isEmpty { return failure }
        return switch state {
        case .running: IconState.allThreeHold.describedAsWords
        case .starting: "The bridge is starting"
        case .stopping: "The bridge is stopping"
        case .stopped, .failed: IconState.bridgeNotRunning.describedAsWords
        }
    }

    /// What to put in front of the owner, or nil to say nothing out loud and leave it in the menu.
    ///
    /// ### Why this is conditional at all
    ///
    /// Raising a modal for every failure was measured at **9.57 seconds** from the press — the whole
    /// retry ladder — which is a focus-stealing window arriving ten seconds after somebody touched a
    /// background menu and went back to their work. So a bridge that dies at 3am and gives up backs
    /// off into the menu and the tooltip, where they will look when they notice.
    ///
    /// ### And why it is not unconditional the other way
    ///
    /// A failure the owner **asked for** is different: they are sitting there, they pressed Start
    /// thirty seconds ago, and the most likely failure by far is a port somebody else already holds.
    /// That is unactionable unless the number is in front of them — and the owner of this app has a
    /// second bridge on 8444 already, so it is the path rather than the hypothetical. A menu tooltip
    /// is not where somebody who just pressed a button looks.
    ///
    /// - Parameters:
    ///   - startWasAsked: true between the press and the state it produces.
    ///   - listeningOn: the port the last start was pointed at. Named in the sentence, because the
    ///     owner has two ports in play and the wrong one is the whole problem.
    public static func announcement(
        for state: BridgeState, startWasAsked: Bool, listeningOn: Int,
    ) -> String? {
        guard case .failed(let sentence) = state, startWasAsked else { return nil }
        // The bridge's own words unless we have a better sentence for them. `address already in use`
        // is the whole diagnosis and it is unreadable without the number.
        return PortInUse.explanation(for: sentence, port: listeningOn) ?? sentence
    }
}
