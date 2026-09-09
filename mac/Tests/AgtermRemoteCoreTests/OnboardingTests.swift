import AppKit
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

/// **Setting up and running are two different questions**, and one of them was answered with the
/// other for a day.
///
/// `step` puts agterm first, because without it nothing this product does works. `setup` leaves it
/// out, because nothing about storing an address or pairing a phone needs the terminal to be
/// running — and gating the setup window on `step` put that window in front of a finished owner
/// every time their Mac started this app before agterm was up.
struct OnboardingSetupTests {

    @Test func setupIgnoresAgtermEntirely() {
        for agterm in [true, false] {
            #expect(Onboarding(agtermSocketExists: agterm, address: nil, isPaired: false).setup == .address)
            #expect(
                Onboarding(agtermSocketExists: agterm, address: "example.test:8443", isPaired: false).setup
                    == .pairing)
            #expect(
                Onboarding(agtermSocketExists: agterm, address: "example.test:8443", isPaired: true).setup
                    == .done)
        }
    }

    /// **The trap, named.** A finished owner with agterm not yet running is `agtermMissing` and has
    /// nothing left to set up — the window must not open for them.
    @Test func aFinishedOwnerWithoutAgtermHasNothingLeftToSetUp() {
        let finished = Onboarding(agtermSocketExists: false, address: "example.test:8443", isPaired: true)

        #expect(finished.step == .agtermMissing)
        #expect(finished.setup == .done)
    }

    /// `setup` never reports `agtermMissing`, whatever it is given. It is the answer to a question
    /// that does not have that word in it.
    @Test func setupNeverReportsAgterm() {
        for agterm in [true, false] {
            for address in [nil, "", "example.test:8443"] as [String?] {
                for paired in [true, false] {
                    #expect(
                        Onboarding(agtermSocketExists: agterm, address: address, isPaired: paired).setup
                            != .agtermMissing)
                }
            }
        }
    }
}

/// Every Swift source in the app, read the way the detectors below read it.
///
/// **The walk is the point.** The first version of the no-test-button detector named two files, so
/// the same button in `PairingWindow.swift`, or in a new file called anything at all, tripped
/// nothing — a rule with a published way around it. `BoundaryTests` in this suite already walks both
/// directories; this is the same walk, shared by the two detectors that need it.
enum AppSources {

    static func all() throws -> [(name: String, code: String)] {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        var found: [(String, String)] = []
        for target in ["Sources/AgtermRemoteCore", "Sources/AgtermRemote"] {
            let directory = root.appending(path: target)
            for name in try FileManager.default.contentsOfDirectory(atPath: directory.path)
                where name.hasSuffix(".swift") {
                found.append((
                    "\(target)/\(name)",
                    NoAggregateVerdictTests.stripped(
                        try String(contentsOf: directory.appending(path: name), encoding: .utf8))))
            }
        }
        return found
    }

    /// Every string handed to a `title:` label — which is how every button in this app is made.
    /// Checking titles rather than identifiers is what catches the button whose function is called
    /// something innocent.
    static func buttonTitles(in code: String) -> [String] {
        var titles: [String] = []
        var rest = Substring(code)
        while let marker = rest.range(of: "title: \"") {
            let after = rest[marker.upperBound...]
            guard let close = after.firstIndex(of: "\"") else { break }
            titles.append(String(after[..<close]))
            rest = after[close...]
        }
        return titles
    }
}

/// The address screen's copy, and **the button that must never appear on it.**
///
/// Checked by reading every source rather than by trusting them, in the same shape as
/// `BoundaryTests`: a rule that lives only in a comment is a rule the next contributor deletes in
/// good faith, and this is the one they are most likely to delete — a screen that asks for an address
/// and then says nothing about whether it works reads as an unfinished feature.
struct AddressPaneTests {

    private func source(_ path: String) throws -> String {
        guard let file = try AppSources.all().first(where: { $0.name == path }) else {
            Issue.record("\(path) is not in the walk")
            return ""
        }
        return file.code
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
        #expect(OnboardingCopy.addressExplanation.contains("your job"))
    }

    /// The absence of a check is stated on the screen, not only in the source. Somebody who is not
    /// told reads a missing check as an oversight and goes looking for it.
    @Test func theScreenSaysWhyNothingHereTriesTheAddress() {
        let said = OnboardingCopy.addressIsUnprovenUntilAPhoneArrives

        #expect(said.contains("unproven"))
        #expect(said.contains("scan"))
    }

    /// **What the wildcard bind costs, disclosed on the screen.**
    ///
    /// Three of the four routes the copy lists involve no port forward at all, so nobody can be
    /// assumed to have opened this port on purpose — and the bridge opens it on every network this
    /// Mac joins regardless. The sentence says that, and says what stands in front of it.
    @Test func theScreenSaysWhatThePortIsOpenOn() {
        let said = OnboardingCopy.addressExposure

        #expect(said.contains("every network this Mac joins"))
        #expect(said.lowercased().contains("paired"), "it must say what gets past the port")
        // Not an alarm. A sentence that told somebody they were exposed and stopped there would send
        // them to turn off the thing they just set up.
        for panic in ["danger", "warning", "insecure", "at risk"] {
            #expect(!said.lowercased().contains(panic), "the disclosure reads as an alarm (\(panic))")
        }
    }

    /// **The two addresses, said as two things.** Somebody with a straight port forward has to be
    /// able to read this and stop; somebody behind a proxying router has to learn that the field
    /// exists.
    @Test func theScreenSaysWhatTheSecondPortIs() {
        let said = OnboardingCopy.arrivalPortExplanation

        #expect(said.contains("your phone dials"))
        #expect(said.lowercased().contains("straight through"), "the simple case must be named")
        #expect(said.lowercased().contains("empty"), "it must say what doing nothing means")
    }

    /// **The rule, enforced.** No control anywhere in the app offers to try the address.
    ///
    /// Two detectors, because a contributor writes either one. Identifiers catch the plumbing; button
    /// titles catch the button whose function has an innocent name — `NSButton(title: "Check
    /// reachability", …)` wired to `refresh()` passes an identifier check and is exactly the thing
    /// this forbids.
    static let forbiddenIdentifiers = [
        "test this address", "test the address", "testaddress", "checkaddress", "probeaddress",
        "verifyaddress", "tryaddress", "testconnection", "checkconnection", "probetheaddress",
    ]
    /// Applied to button titles only, so `BridgeStatus.Reachable` and every other honest use of the
    /// word in code is untouched.
    static let forbiddenInTitles = ["test", "check reach", "reachab", "probe", "verify", "try it"]

    /// Nil when the file is clean, otherwise what it does wrong. Pure, so a file that does not exist
    /// can be handed to it — which is how the control below proves the rule is not a list of two
    /// filenames.
    static func offersToTestTheAddress(_ file: (name: String, code: String)) -> String? {
        let code = file.code.lowercased()
        if let hit = forbiddenIdentifiers.first(where: code.contains) { return hit }
        for title in AppSources.buttonTitles(in: file.code) {
            if let hit = forbiddenInTitles.first(where: title.lowercased().contains) {
                return "a button titled \"\(title)\" (\(hit))"
            }
        }
        return nil
    }

    @Test func nothingInTheAppOffersToTestTheAddress() throws {
        let sources = try AppSources.all()
        // The walk, asserted before its verdict is trusted: a detector reading an empty list reports
        // every file clean.
        #expect(sources.count > 8, "the walk found \(sources.count) sources")
        #expect(sources.contains { $0.name.hasSuffix("PairingWindow.swift") }, "the walk misses a window")

        for file in sources {
            #expect(Self.offersToTestTheAddress(file) == nil, "\(file.name) offers to test the address")
        }
    }

    /// The control, in three parts. The detector must see the button **in a file nobody thought to
    /// watch**, must see the innocently-named one, and must not fire on the argument against the
    /// button — the real file argues that case at length, and a detector that fired on its own
    /// reasoning would be deleted by the first person it blocked.
    @Test func theDetectorSeesAButtonAnywhereAndIgnoresTheArgumentAgainstOne() {
        let unwatched = (
            name: "Sources/AgtermRemote/AddressProbe.swift",
            code: NoAggregateVerdictTests.stripped(
                #"let button = NSButton(title: "Test this address", target: self, action: #selector(go))"#))
        let innocentlyNamed = (
            name: "Sources/AgtermRemote/PairingWindow.swift",
            code: NoAggregateVerdictTests.stripped(
                #"let b = NSButton(title: "Check reachability", target: self, action: #selector(refresh))"#))
        let argued = (
            name: "Sources/AgtermRemote/OnboardingWindow.swift",
            code: NoAggregateVerdictTests.stripped(
                "// There is no button that offers to test the address, and there must never be one:\n"
                    + "// a Mac cannot honestly test its own public address, and a probe that binds\n"
                    + "// proves nothing. Nobody may verify the address from here."))

        #expect(Self.offersToTestTheAddress(unwatched) != nil)
        #expect(Self.offersToTestTheAddress(innocentlyNamed) != nil)
        #expect(Self.offersToTestTheAddress(argued) == nil, "the detector fires on its own reasoning")
    }

    /// The pane renders the library's copy rather than a second version of it. Two copies of a
    /// paragraph is two paragraphs the moment one of them is edited.
    @Test func theWindowRendersTheCopyRatherThanRepeatingIt() throws {
        let window = try source("Sources/AgtermRemote/OnboardingWindow.swift")

        for reference in [
            "OnboardingCopy.addressHeading", "OnboardingCopy.addressExplanation",
            "OnboardingCopy.addressRoutes", "OnboardingCopy.addressIsUnprovenUntilAPhoneArrives",
            "OnboardingCopy.addressExposure", "OnboardingCopy.arrivalPortHeading",
            "OnboardingCopy.arrivalPortExplanation",
        ] {
            #expect(window.contains(reference), "the address pane does not render \(reference)")
        }
    }
}

/// **This app touches nothing belonging to another installation on the owner's machine.**
///
/// It reached into a *different project's* directory in the home of somebody who is still running
/// that project in parallel with this one, for a certificate helper this repository does not build.
/// Nothing here owns that binary or its output; the app was borrowing a tool from somebody else's
/// setup and presenting the result as its own.
///
/// Everything this app writes now lives under the state directory it passes the bridge, and the rule
/// is checked rather than remembered — with the same walk the button detector uses, so a legacy path
/// in a file nobody thought to watch fails too.
struct NoLegacyPathTests {

    /// Directory names belonging to installations that are not this one. `agterm-remote` is this
    /// app's own state directory and is not on the list.
    static let foreign = ["agterm-bridge", "bridgecert", ".config/agterm/"]

    static func namesAForeignInstallation(_ file: (name: String, code: String)) -> String? {
        let code = file.code.lowercased()
        return foreign.first { code.contains($0.lowercased()) }
    }

    @Test func noSourceNamesAnotherInstallation() throws {
        let sources = try AppSources.all()
        #expect(sources.count > 8, "the walk found \(sources.count) sources")

        for file in sources {
            #expect(
                Self.namesAForeignInstallation(file) == nil,
                "\(file.name) reaches into another installation")
        }
    }

    /// The control: a path planted in a file this test names nowhere is still seen, and the prose
    /// that explains why the path is gone is not.
    @Test func theDetectorSeesAPlantedPathAndIgnoresTheExplanation() {
        let unwatched = (
            name: "Sources/AgtermRemote/CodeMaker.swift",
            code: NoAggregateVerdictTests.stripped(
                #"let tool = home.appending(path: ".config/agterm-bridge/bin/bridgecert").path"#))
        let explained = (
            name: "Sources/AgtermRemote/main.swift",
            code: NoAggregateVerdictTests.stripped(
                "// It used to run a helper out of another project's directory in the owner's home.\n"
                    + "// That is gone: this app owns only its own state directory."))

        #expect(Self.namesAForeignInstallation(unwatched) != nil)
        #expect(Self.namesAForeignInstallation(explained) == nil, "the detector fires on its own prose")
    }
}

/// **Cmd-V, pinned by structure.**
///
/// This app shipped with no main menu, which means macOS had nowhere to deliver Paste, Copy, Cut,
/// Select All or Undo — in every text field it will ever show, including the one the phone flow
/// falls back to when a camera will not read a code. Nothing in a diff shows a line that was never
/// written, so the shape is asserted here instead.
@MainActor
struct EditingMenuTests {

    @Test func theEditMenuCarriesAllSixCommands() {
        let edit = EditingMenu.make().items.compactMap(\.submenu).first { $0.title == "Edit" }

        guard let edit else { return #expect(Bool(false), "there is no Edit menu") }
        #expect(edit.items.map(\.title) == ["Undo", "Redo", "Cut", "Copy", "Paste", "Select All"])
    }

    /// **The selectors are the ones macOS actually sends**, and the key equivalents are the ones
    /// people press. An item titled Paste that sends something else is a menu that looks right and
    /// does nothing — the defect this replaces, wearing a disguise.
    @Test func everyCommandSendsWhatMacOSSendsToTheFirstResponder() {
        let edit = EditingMenu.make().items.compactMap(\.submenu).first { $0.title == "Edit" }!

        for command in EditingMenu.commands {
            guard let item = edit.items.first(where: { $0.title == command.title }) else {
                #expect(Bool(false), "\(command.title) is missing")
                continue
            }
            #expect(item.action == command.selector, "\(command.title) sends the wrong message")
            #expect(item.keyEquivalent == command.key)
            #expect(item.keyEquivalentModifierMask.contains(.command))
            #expect(item.keyEquivalentModifierMask.contains(.shift) == command.holdsShift)
            // **nil, so it goes to whatever is focused.** A target here would send every command to
            // one object that implements none of them, and the menu would grey out in every window.
            #expect(item.target == nil, "\(command.title) is aimed at something other than the focus")
        }
    }

    @Test func theSixAreTheOnesPeopleReachFor() {
        #expect(
            EditingMenu.commands.map(\.key).sorted() == ["a", "c", "v", "x", "z", "z"],
            "the standard editing keys are not all here")
    }

    /// Quit is a menu item too, for the same reason: Cmd-Q is delivered by one.
    @Test func theApplicationMenuHoldsQuit() {
        let application = EditingMenu.make().items.compactMap(\.submenu).first

        #expect(application?.items.first?.action == #selector(NSApplication.terminate(_:)))
        #expect(application?.items.first?.keyEquivalent == "q")
    }

    /// **It is installed, not merely constructible.** A menu nothing assigns is the same defect it
    /// was written to fix, so the assignment is read out of the app's source.
    @Test func theAppInstallsIt() throws {
        let main = try AppSources.all().first { $0.name.hasSuffix("Sources/AgtermRemote/main.swift") }

        guard let main else { return #expect(Bool(false), "main.swift is not in the walk") }
        #expect(
            main.code.contains("NSApp.mainMenu = EditingMenu.make()"),
            "the app never assigns a main menu, so nothing delivers the editing commands")
    }
}
