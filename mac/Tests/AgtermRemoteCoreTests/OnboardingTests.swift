import Foundation
import Testing
@testable import AgtermRemoteCore

/// The three things that must be true before a phone can reach this Mac, in the order they can be
/// established.
///
/// ### Why the order is asserted rather than described
///
/// Each step is a prerequisite for the one after it, and a screen that offered them in any other
/// order would ask somebody to set an address for a terminal that is not there, or to scan a code
/// for an address that does not exist. The tests below are about the ORDER as much as the states:
/// every one of them holds two facts steady and moves the third.
struct OnboardingTests {

    @Test func stepIsAgtermMissingBeforeAnythingElse() {
        let onboarding = Onboarding(agtermSocketExists: false, address: "example.test:8443", isPaired: true)

        #expect(onboarding.step == .agtermMissing, "agterm is a prerequisite; nothing else matters without it")
    }

    @Test func stepIsAddressWhenThereIsNone() {
        #expect(Onboarding(agtermSocketExists: true, address: nil, isPaired: false).step == .address)
    }

    @Test func stepIsPairingWhenAnAddressExistsButNoPhoneDoes() {
        #expect(
            Onboarding(agtermSocketExists: true, address: "example.test:8443", isPaired: false).step
                == .pairing)
    }

    @Test func doneOnlyWhenAllThreeHold() {
        #expect(
            Onboarding(agtermSocketExists: true, address: "example.test:8443", isPaired: true).step
                == .done)
    }

    /// **A paired phone does not carry somebody past the address step.** It can happen: an address is
    /// set, a phone enrols, and the address is then cleared. What that person needs is the address
    /// screen, not a code for an address that no longer exists.
    @Test func aPairedPhoneDoesNotSkipAMissingAddress() {
        #expect(Onboarding(agtermSocketExists: true, address: nil, isPaired: true).step == .address)
    }

    /// A stored string that is not an address is the same situation as no address at all: there is
    /// nothing a phone could dial. It is `.address` rather than a fifth step, because the screen that
    /// fixes it is the same screen.
    @Test func anAddressThatDoesNotParseIsTheSameAsNone() {
        for stored in ["", "   ", "example.test", "https://example.test:8443", "example.test:0"] {
            #expect(
                Onboarding(agtermSocketExists: true, address: stored, isPaired: false).step == .address,
                "\"\(stored)\" is not an address a phone could dial")
        }
    }

    /// The address, parsed, for the screen that shows it. Nil at every step where there is not one —
    /// including `agtermMissing`, where a stored address exists but nothing can be done with it yet.
    @Test func theParsedAddressIsCarriedWhereThereIsOne() {
        #expect(
            Onboarding(agtermSocketExists: true, address: "example.test:8443", isPaired: false).address
                == Address(host: "example.test", port: 8443))
        #expect(Onboarding(agtermSocketExists: true, address: "example.test", isPaired: false).address == nil)
    }

    /// Every step is reachable, and no state produces two of them. The same hazard `MenuModel` is
    /// written against: a step that no combination of facts can produce is a screen nobody will ever
    /// see, and it would be found by a person rather than by this.
    @Test func everyStepIsReachable() {
        let steps = Set(
            [
                (false, nil as String?, false), (true, nil, false),
                (true, "example.test:8443", false), (true, "example.test:8443", true),
            ]
            .map { Onboarding(agtermSocketExists: $0.0, address: $0.1, isPaired: $0.2).step })

        #expect(steps == Set(OnboardingStep.allCases))
    }
}

/// The address screen's copy, and **the button that must never appear on it.**
///
/// Both halves are checked by reading the sources rather than by trusting them, in the same shape as
/// `BoundaryTests`: a rule that lives only in a comment is a rule the next contributor deletes in
/// good faith, and this is the one they are most likely to delete — a screen that asks for an address
/// and then says nothing about whether it works reads as an unfinished feature.
struct AddressPaneTests {

    private func source(_ path: String) throws -> String {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        return NoAggregateVerdictTests.stripped(
            try String(contentsOf: root.appending(path: path), encoding: .utf8))
    }

    /// The routes from the design document, all of them, and **no order of preference**. Listing what
    /// people use is the feature; picking one would make this app a front end for somebody else's
    /// service and make every other route feel unsupported.
    @Test func theCopyListsWhatPeopleUseAndRecommendsNone() {
        let text = ([OnboardingCopy.addressExplanation] + OnboardingCopy.addressRoutes).joined(separator: " ")

        for route in ["port forward", "IP:PORT", "dynamic-DNS", "Tailscale", "WireGuard", "Cloudflare Tunnel", "reverse proxy"] {
            #expect(text.contains(route), "the address copy does not mention \(route)")
        }
        for endorsement in ["recommend", "we suggest", "best", "easiest", "simplest", "should use"] {
            #expect(!text.lowercased().contains(endorsement), "the address copy endorses a route (\(endorsement))")
        }
        // Whose job it is, said rather than implied.
        #expect(OnboardingCopy.addressExplanation.contains("your job"))
    }

    /// The absence of a check is stated on the screen, not only in the source. Somebody who is not
    /// told reads a missing check as an oversight and goes looking for it.
    @Test func theScreenSaysWhyNothingHereTriesTheAddress() {
        let said = OnboardingCopy.addressIsUnprovenUntilAPhoneArrives

        #expect(said.contains("unproven"))
        #expect(said.contains("scan"))
    }

    /// **The rule, enforced.** No control on the address pane offers to try the address.
    ///
    /// The forbidden words are the ones a contributor would actually write. It is not a proof — a
    /// button called `runIt` would walk past this — but it is the difference between a rule that is
    /// argued in a comment and a rule that fails a build.
    private static let forbidden = [
        "test this address", "test the address", "testaddress", "checkaddress", "probeaddress",
        "verifyaddress", "tryaddress", "testconnection", "checkconnection", "title: \"test",
    ]

    @Test func nothingOnTheAddressPaneOffersToTestTheAddress() throws {
        for path in ["Sources/AgtermRemote/OnboardingWindow.swift", "Sources/AgtermRemoteCore/Onboarding.swift"] {
            let code = try source(path).lowercased()
            for phrase in Self.forbidden {
                #expect(!code.contains(phrase), "\(path) offers to test the address (\(phrase))")
            }
        }
    }

    /// The control. The detector is shown the button in code and must see it, and shown the argument
    /// against the button in prose and must not — **the real file argues the case at length**, so a
    /// detector that fired on prose would be deleted by whoever it first blocked, taking the rule
    /// with it.
    @Test func theDetectorSeesAButtonAndIgnoresTheArgumentAgainstOne() {
        let planted = NoAggregateVerdictTests.stripped(
            #"let button = NSButton(title: "Test this address", target: self, action: #selector(check))"#
        ).lowercased()
        let argued = NoAggregateVerdictTests.stripped(
            "// There is no button that offers to test the address, and there must never be one: a\n"
                + "// Mac cannot honestly test its own public address.").lowercased()

        #expect(Self.forbidden.contains { planted.contains($0) })
        #expect(!Self.forbidden.contains { argued.contains($0) }, "the detector fires on its own reasoning")
    }

    /// The pane renders the library's copy rather than a second version of it. Two copies of a
    /// paragraph is two paragraphs the moment one of them is edited.
    @Test func theWindowRendersTheCopyRatherThanRepeatingIt() throws {
        let window = try source("Sources/AgtermRemote/OnboardingWindow.swift")

        for reference in [
            "OnboardingCopy.addressHeading", "OnboardingCopy.addressExplanation",
            "OnboardingCopy.addressRoutes", "OnboardingCopy.addressIsUnprovenUntilAPhoneArrives",
        ] {
            #expect(window.contains(reference), "the address pane does not render \(reference)")
        }
    }
}
