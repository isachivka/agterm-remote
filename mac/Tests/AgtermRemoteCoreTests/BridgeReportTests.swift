import Foundation
import Testing
@testable import AgtermRemoteCore

/// **The two surfaces a bridge failure reaches, and the two ways they were wrong.**
///
/// Both of these lived inside the menu-bar app, where they were prose and a couple of `if`s and
/// nothing could ask them a question. They are values now, and these are the questions.
struct BridgeReportTests {

    private static let portClash =
        "The bridge stopped 5 times in a row, the last time with exit status 1, so it is no longer "
            + "being restarted.\n\nIt said:\nlisten tcp 0.0.0.0:8444: bind: address already in use"

    // MARK: - The tooltip, and the failure that would not go away

    /// **The carried defect, stated as a test.** A failure arrives, the owner fixes the port, the
    /// bridge starts — and the tooltip still says the port is taken, because the line that set it was
    /// `if let failure`, which has no branch for the good news.
    @Test func aClearedFailureLeavesNothingBehindInTheTooltip() {
        let during = BridgeReport.tooltip(for: .failed(Self.portClash), failure: Self.portClash)
        #expect(during == Self.portClash)

        let after = BridgeReport.tooltip(for: .running(pid: 4242), failure: nil)

        #expect(!after.contains("address already in use"), "the tooltip kept a failure that is over")
        #expect(after == IconState.allThreeHold.describedAsWords)
    }

    /// There is always something true to say, in every state, including the ones nobody presses
    /// anything to reach. A tooltip that is empty in some state is the surface going stale again in a
    /// quieter way.
    @Test func everyStateHasSomethingTrueToSay() {
        for state in [BridgeState.stopped, .starting, .running(pid: 1), .stopping, .failed("x")] {
            #expect(!BridgeReport.tooltip(for: state, failure: nil).isEmpty, "\(state) says nothing")
        }
    }

    /// A stop in flight says so. The menu greys Start and Stop for the duration; the tooltip is where
    /// the reason for that lives.
    @Test func aStopInFlightIsNamedRatherThanLookingLikeAHang() {
        #expect(BridgeReport.tooltip(for: .stopping, failure: nil).lowercased().contains("stopping"))
    }

    /// An empty failure is not a failure. It would render as a blank tooltip, which is the same
    /// nothing the old shape produced, arriving by a different door.
    @Test func anEmptyFailureIsNotTreatedAsOne() {
        #expect(BridgeReport.tooltip(for: .stopped, failure: "") == IconState.bridgeNotRunning.describedAsWords)
    }

    // MARK: - The announcement, and the port clash that has to reach a person

    /// **The port-in-use failure reaches the person who pressed Start, with the number in it.**
    ///
    /// This is not hypothetical on the machine this was written on: it already runs another bridge on
    /// 8444. The supervisor ends in `.failed` carrying the bridge's own `address already in use`, and
    /// that sentence is unreadable without the port — the owner has two of them in play.
    @Test func aPortClashTheOwnerAskedForIsSaidOutLoudWithTheNumber() throws {
        let said = try #require(
            BridgeReport.announcement(for: .failed(Self.portClash), startWasAsked: true, listeningOn: 8444))

        #expect(said.contains("8444"), "the sentence does not name the port")
        #expect(said.lowercased().contains("already listening"))
    }

    /// **A failure nobody pressed anything for stays in the menu.** Measured at 9.57 seconds from the
    /// press for the full ladder; a focus-stealing modal ten seconds after somebody went back to their
    /// work is worse than the thing it reports.
    @Test func aFailureNobodyAskedForIsNotPutInFrontOfThem() {
        #expect(BridgeReport.announcement(for: .failed(Self.portClash), startWasAsked: false, listeningOn: 8444) == nil)
    }

    /// Nothing is announced about a bridge that is fine, whatever was pressed.
    @Test func nothingIsAnnouncedAboutAWorkingBridge() {
        for state in [BridgeState.stopped, .starting, .running(pid: 1), .stopping] {
            #expect(BridgeReport.announcement(for: state, startWasAsked: true, listeningOn: 8444) == nil)
        }
    }

    /// A failure that is not a port clash is passed through in the bridge's own words. A summary of
    /// somebody else's diagnosis is a diagnosis nobody can act on.
    @Test func aFailureThatIsNotAPortClashIsPassedThroughVerbatim() {
        let refused = "the bridge exited: permission denied"

        #expect(
            BridgeReport.announcement(for: .failed(refused), startWasAsked: true, listeningOn: 8444)
                == refused)
    }

    // MARK: - And the app actually uses them

    /// **Wired, not merely available.** The defect these replace was two expressions inside the app;
    /// a library that answers the question while the app keeps its own answer is worse than either.
    @Test func theAppDrawsBothSurfacesFromHere() throws {
        let main = try #require(
            AppSources.all().first { $0.name.hasSuffix("Sources/AgtermRemote/main.swift") })

        #expect(main.code.contains("BridgeReport.tooltip("), "the app still computes its own tooltip")
        #expect(main.code.contains("BridgeReport.announcement("), "the app still decides this itself")
        // The shape that could not clear itself. If it comes back, it comes back here.
        #expect(
            !main.code.contains("if let failure { item?.button?.toolTip"),
            "the tooltip is set only when there is bad news again")
    }
}
