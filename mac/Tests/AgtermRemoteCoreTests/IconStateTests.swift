import Foundation
import Testing
@testable import AgtermRemoteCore

/// The one glyph, and the rule that it must not be a colour.
struct IconStateTests {

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
    /// single dot from reappearing: no state's description is a verdict about the whole.
    ///
    /// It used to be asked of `MenuModel.icon(for:)`, over a `BridgeStatus` carrying three facts.
    /// Nothing ever measured two of those three — the app built that value at its own call site out
    /// of a placeholder address — so the function and the type are gone, and the rule is asked of the
    /// vocabulary that is still live and still drawn.
    @Test func noStateDescribesTheWholeInsteadOfNamingAFact() {
        let verdicts = ["ok", "okay", "fine", "healthy", "good", "working", "all set", "ready"]

        for state in IconState.allCases {
            let said = state.describedAsWords.lowercased()
            for verdict in verdicts {
                #expect(!said.contains(verdict), "\(state) says \(verdict), which is a verdict about the whole")
            }
        }
    }

    /// Five states, five different sentences. A vocabulary with two states saying the same thing is
    /// two states collapsed, which is how the 2026-08-09 viewfinder lost its button.
    @Test func everyStateSaysSomethingDifferent() {
        let said = IconState.allCases.map(\.describedAsWords)

        #expect(Set(said).count == IconState.allCases.count)
        #expect(said.allSatisfy { !$0.isEmpty })
    }
}
