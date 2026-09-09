import Foundation
import Testing
@testable import AgtermRemoteCore

/// The one glyph, and the rule that it must not be a colour.
struct IconStateTests {

    private let address = DialAddress(host: "agterm.example-homelab.invalid", port: 8443)

    /// **A status conveyed only by hue is one the owner cannot read at a glance and may not be able to
    /// read at all.** Five states, five different shapes, and the menu bar applies no tint. The shapes
    /// themselves are checked in `MarkTests`, which renders them rather than comparing names — a
    /// distinctness test over strings would have passed for five drawings that look identical.

    /// The words a screen reader reads. No colour vocabulary anywhere, because "it's green" is not a
    /// description of anything to somebody who cannot see it.
    /// Matched as WORDS, not substrings. The first version of this check failed on "answe**red**" and
    /// "configu**red**" — a detector that reports a colour word inside an ordinary verb is the same
    /// instrument fault as a grep that fires on its own documentation, and the fix is the instrument
    /// rather than the copy.
    @Test func nothingIsDescribedByItsColour() {
        let colours: Set<String> = ["green", "red", "amber", "yellow", "orange", "grey", "gray"]

        for state in IconState.allCases {
            let words = Set(
                state.describedAsWords.lowercased()
                    .split(whereSeparator: { !$0.isLetter })
                    .map(String.init))

            #expect(words.isDisjoint(with: colours), "\(state) is described by its colour")
            #expect(!words.isEmpty)
        }
    }

    /// The control for the check above: a description that really is a colour must be caught, and an
    /// ordinary word that merely contains one must not.
    @Test func theColourDetectorSeesColoursAndNotVerbs() {
        func colourWords(_ text: String) -> Set<String> {
            Set(text.lowercased().split(whereSeparator: { !$0.isLetter }).map(String.init))
                .intersection(["green", "red", "amber", "yellow", "orange", "grey", "gray"])
        }

        #expect(colourWords("The dot is green") == ["green"], "the detector would miss a real colour")
        #expect(colourWords("Nothing answered at the configured address").isEmpty,
                "the detector fires on ordinary verbs")
    }

    /// **The icon names which fact is unresolved. It never says "ok".** This is the one that keeps a
    /// single dot from reappearing: something answering that is not us must not look like health.
    @Test func somethingElseAnsweringNeverLooksLikeHealth() {
        let impostor = BridgeStatus(address: address, running: .running, reachable: .answered,
                                    identified: .notOurs(presented: "1111 2222"))

        #expect(MenuModel.icon(for: impostor) == .answeredBySomethingElse)
        #expect(MenuModel.icon(for: impostor) != .allThreeHold)
    }

    @Test func eachUnresolvedFactHasItsOwnIcon() {
        #expect(MenuModel.icon(for: nil) == .notChecked)
        #expect(MenuModel.icon(for: BridgeStatus(address: address, running: .running, reachable: .answered,
                                                 identified: .ours)) == .allThreeHold)
        #expect(MenuModel.icon(for: BridgeStatus(address: address, running: .notRunning, reachable: .answered,
                                                 identified: .ours)) == .bridgeNotRunning)
        #expect(MenuModel.icon(for: BridgeStatus(address: address, running: .running,
                                                 reachable: .noAnswer("refused"),
                                                 identified: .notEstablished)) == .nothingAnswered)
    }

    /// A bridge that is down AND an address answering as somebody else: the icon names the address
    /// problem, because that is the one nobody would guess. The menu still shows both.
    @Test func theStrangerOutranksTheStoppedProcessInTheGlyph() {
        let both = BridgeStatus(address: address, running: .notRunning, reachable: .answered,
                                identified: .notOurs(presented: "1111 2222"))

        #expect(MenuModel.icon(for: both) == .answeredBySomethingElse)
        #expect(both.problems.count == 2, "the menu must still say both")
    }
}
