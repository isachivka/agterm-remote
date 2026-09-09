import Foundation
import Testing
@testable import AgtermRemoteCore

/// Typing **one address**, the way every other surface renders it.
struct AddressEditTests {

    private func parsed(_ typed: String) throws -> DialAddress {
        try AddressEdit.parse(typed).get()
    }

    private func refusal(_ typed: String) throws -> AddressEdit.Refusal {
        guard case .failure(let why) = AddressEdit.parse(typed) else { throw Failed.accepted(typed) }
        return why
    }

    private enum Failed: Error { case accepted(String) }

    @Test func anOrdinaryAddressIsAccepted() throws {
        let address = try parsed("agterm.example-homelab.invalid:8443")

        #expect(address == DialAddress(host: "agterm.example-homelab.invalid", port: 8443))
    }

    /// **The defect this change exists to fix.** With two boxes, this exact string — what the phone
    /// shows, what the QR carries, what the comparison screen displays — was a *refusal*. We had
    /// written a validation error for the correct input.
    /// The bare-suffix row is the one exception, and it is a *judgement* about the destination rather
    /// than a complaint about the shape — it is refused pending the owner's second act, and parses
    /// structurally the moment they give it. Every row is checked with that granted, so this asserts
    /// the shape and nothing else.
    @Test func theStringEverySurfaceDisplaysIsAcceptedRatherThanRefused() throws {
        for row in DialAddressTests.table {
            let address = try AddressEdit.parse(row.rendered, allowingBareSuffix: true).get()

            #expect(address.displayed == row.rendered, "\(row.rendered) did not come back as itself")
        }
    }

    /// **The failure that actually happened, and the reason this validation exists.**
    @Test func aBareSuffixIsRefusedWithItsOwnSentence() throws {
        let why = try refusal("example-homelab.invalid:8443")

        #expect(why == .bareSuffix("example-homelab.invalid"))
        let sentence = why.explanation
        #expect(sentence.contains("agterm.example-homelab.invalid"), "it does not say what to type instead")
        #expect(sentence.contains("pairs and then never connects"), "it does not say what goes wrong")
    }

    /// **Deliberate, not impossible** — and the only refusal a second act can override.
    @Test func theBareSuffixIsTheOnlyRefusalThatCanBeConfirmed() {
        #expect(AddressEdit.isConfirmable(.bareSuffix("x.invalid")))

        for refusal: AddressEdit.Refusal in [
            .blank, .hasWhitespace, .looksLikeAURL("x"), .noPort("x.invalid"), .portNotANumber("x"),
            .portOutOfRange(0), .ambiguousWithoutBrackets("a:b:c"), .unclosedBracket("[x"),
        ] {
            #expect(!AddressEdit.isConfirmable(refusal), "\(refusal) can be typed around")
        }
    }

    @Test func aConfirmedBareSuffixParsesExactlyAsTyped() throws {
        let address = try AddressEdit.parse("example-homelab.invalid:8443", allowingBareSuffix: true).get()

        #expect(address == DialAddress(host: "example-homelab.invalid", port: 8443))
    }

    /// A confirmation is not a skeleton key.
    @Test func confirmingDoesNotSweepAsideTheOtherRefusals() {
        for typed in ["", "https://x/:8443", "x.example.invalid:70000", "x.example.invalid", "2001:db8::1:8443"] {
            guard case .failure = AddressEdit.parse(typed, allowingBareSuffix: true) else {
                return #expect(Bool(false), "confirming let \"\(typed)\" through")
            }
        }
    }

    @Test func aBareSuffixWithATrailingDotIsStillTheSuffix() throws {
        #expect(try refusal("example-homelab.invalid.:8443") == .bareSuffix("example-homelab.invalid."))
    }

    @Test func aHostUnderTheSuffixIsNotRefused() throws {
        _ = try parsed("agterm.example-homelab.invalid:8443")
        _ = try parsed("bridge.agterm.example-homelab.invalid:8443")
    }

    /// A single LAN name, an mDNS name and address literals each name one machine and cannot be a
    /// shared suffix, so refusing them would be this check overreaching.
    @Test func namesThatAreOneMachineRatherThanASuffixAreAccepted() throws {
        #expect(try parsed("workshop:8443").host == "workshop")
        #expect(try parsed("workshop.local:8443").host == "workshop.local")
        #expect(try parsed("192.0.2.10:8443").host == "192.0.2.10")
        #expect(try parsed("[fe80::1]:8443").host == "fe80::1")
    }

    /// **Nothing is normalised.** Case, trailing dots and non-default ports are differences between
    /// destinations; the parser is the formatter's mirror and keeps every one of them.
    @Test func whatIsTypedIsWhatIsSaved() throws {
        #expect(try parsed("Agterm.Example-Homelab.Invalid:8443").host == "Agterm.Example-Homelab.Invalid")
        #expect(try parsed("agterm.example-homelab.invalid.:8443").host == "agterm.example-homelab.invalid.")
        #expect(try parsed("agterm.example-homelab.invalid:443").port == 443)
    }

    @Test func surroundingWhitespaceIsRemovedAndInternalWhitespaceIsRefused() throws {
        #expect(try parsed("  agterm.example-homelab.invalid:8443 \n").host == "agterm.example-homelab.invalid")
        #expect(try refusal("agterm.example invalid:8443") == .hasWhitespace)
    }

    /// **The port is the half nobody can see in a QR**, so its absence is named and what to add is
    /// spelled out — including that 8443 is a guess they should check rather than a default we supply.
    @Test func aMissingPortIsRefusedBySayingWhatToAdd() throws {
        let why = try refusal("agterm.example-homelab.invalid")

        #expect(why == .noPort("agterm.example-homelab.invalid"))
        #expect(why.explanation.contains("agterm.example-homelab.invalid:8443"), "it does not show what to add")
        #expect(why.explanation.contains("router forwards"))
        #expect(why.explanation.contains("cannot see in a pairing code"))
    }

    @Test func aTrailingColonWithNoPortIsAlsoAMissingPort() throws {
        guard case .noPort = try refusal("agterm.example-homelab.invalid:") else {
            return #expect(Bool(false), "wrong refusal")
        }
    }

    /// Brackets exist so `host:port` can be read at all; without them there is no answer, and a guess
    /// would send the phone at a port nobody serves.
    @Test func anUnbracketedIPv6SaysToBracketIt() throws {
        let why = try refusal("2001:db8::1:8443")

        #expect(why == .ambiguousWithoutBrackets("2001:db8::1:8443"))
        #expect(why.explanation.contains("[fe80::1]:8443"))
    }

    @Test func anUnclosedBracketIsItsOwnSentence() throws {
        #expect(try refusal("[2001:db8::1:8443") == .unclosedBracket("[2001:db8::1:8443"))
    }

    @Test func aURLIsRefusedByNamingThePartToKeep() throws {
        let why = try refusal("https://agterm.example-homelab.invalid/")

        #expect(why == .looksLikeAURL("https://agterm.example-homelab.invalid/"))
        #expect(why.explanation.contains("//"))
    }

    @Test func anEmptyFieldAsksForTheRightThingRatherThanSayingInvalid() throws {
        let why = try refusal("   ")

        #expect(why == .blank)
        // The distinction that started all of this: the dial address is not the listen address.
        #expect(why.explanation.contains("PHONE"))
        #expect(why.explanation.contains("listens on"))
        #expect(why.explanation.contains("host:port"), "it does not say what shape to type")
    }

    @Test func aPortThatIsNotAPortIsRefusedByName() throws {
        #expect(try refusal("agterm.example-homelab.invalid:eight") == .portNotANumber("eight"))
        #expect(try refusal("agterm.example-homelab.invalid:0") == .portOutOfRange(0))
        #expect(try refusal("agterm.example-homelab.invalid:65536") == .portOutOfRange(65536))
        #expect(try parsed("agterm.example-homelab.invalid:65535").port == 65535)
    }

    /// **Every refusal is a sentence somebody can act on.**
    @Test func everyRefusalSaysSomething() {
        let refusals: [AddressEdit.Refusal] = [
            .blank, .hasWhitespace, .looksLikeAURL("x"), .bareSuffix("x.invalid"), .noPort("x.invalid"),
            .portNotANumber("x"), .portOutOfRange(0), .ambiguousWithoutBrackets("a:b:c"), .unclosedBracket("[x"),
        ]

        for refusal in refusals {
            #expect(refusal.explanation.count > 30, "\(refusal) has nothing to say")
            #expect(refusal.explanation.hasSuffix("."), "\(refusal) is not a sentence")
        }
    }
}

/// What the one box opens with.
struct AddressFieldTests {

    /// **The same rendering as everywhere else.** The box shows the string the phone shows, so the
    /// address never exists in two shapes at once.
    @Test func theFieldIsPrefilledWithTheSameRenderingTheQRCarries() {
        let address = DialAddress(host: "agterm.example-homelab.invalid", port: 8443)

        #expect(AddressField(.success(address)) == AddressField(text: address.displayed))
        #expect(AddressField(.success(address)).text == "agterm.example-homelab.invalid:8443")
    }

    /// **No guessed default.** Offering `:8443` in an empty box states a fact nobody checked.
    @Test func aMissingFileLeavesTheFieldEmpty() {
        #expect(AddressField(.failure(.unset)) == AddressField(text: ""))
        #expect(AddressField(.failure(.malformed)) == AddressField(text: ""))
    }
}

/// Saving: one file, one write, and nothing written on a refusal.
struct SaveAddressTests {

    private final class Log: @unchecked Sendable {
        var written: [DialAddress] = []
        var failure: Error?
    }

    private func saver(_ log: Log) -> SaveAddress {
        SaveAddress { address in
            if let failure = log.failure { throw failure }
            log.written.append(address)
        }
    }

    @Test func aValidAddressIsWrittenExactlyOnce() {
        let log = Log()

        let outcome = saver(log).save("agterm.example-homelab.invalid:8443")

        #expect(outcome == .saved(DialAddress(host: "agterm.example-homelab.invalid", port: 8443)))
        #expect(log.written == [DialAddress(host: "agterm.example-homelab.invalid", port: 8443)])
    }

    @Test func aRefusalLeavesTheFileAlone() {
        let log = Log()

        let outcome = saver(log).save("https://example-homelab.invalid/")

        #expect(log.written.isEmpty)
        guard case .refused = outcome else { return #expect(Bool(false), "\(outcome)") }
    }

    /// **The second act: asked, and nothing written until it is answered.**
    @Test func aBareSuffixAsksBeforeItWritesAnything() {
        let log = Log()

        let outcome = saver(log).save("example-homelab.invalid:8443")

        #expect(log.written.isEmpty, "it wrote a bare suffix without being told twice")
        guard case .needsConfirmation(let explanation, let question) = outcome else {
            return #expect(Bool(false), "\(outcome)")
        }
        #expect(explanation.contains("pairs and then never connects"))
        #expect(question == "Save it anyway?")
    }

    @Test func aConfirmedBareSuffixIsWrittenUnchanged() {
        let log = Log()

        let outcome = saver(log).save("example-homelab.invalid:8443", confirmed: true)

        #expect(outcome == .saved(DialAddress(host: "example-homelab.invalid", port: 8443)))
        #expect(log.written == [DialAddress(host: "example-homelab.invalid", port: 8443)])
    }

    /// The confirmation applies to the save it was given and nothing else.
    @Test func theConfirmationIsNotStickyAcrossSaves() {
        let log = Log()
        let saver = saver(log)

        _ = saver.save("example-homelab.invalid:8443", confirmed: true)
        let second = saver.save("other-homelab.invalid:8443")

        guard case .needsConfirmation = second else { return #expect(Bool(false), "\(second)") }
        #expect(log.written.count == 1)
    }

    @Test func aFailedWriteIsNotReportedAsARefusal() {
        let log = Log()
        log.failure = CocoaError(.fileWriteNoPermission)

        let outcome = saver(log).save("agterm.example-homelab.invalid:8443")

        guard case .notWritten(let sentence) = outcome else { return #expect(Bool(false), "\(outcome)") }
        #expect(sentence.contains("The address is fine"))
    }

    /// **End to end through the real store, for every row of the shared table**: what is typed is
    /// saved unchanged, and comes back out rendering as the same string.
    @Test func everyRowReachesTheStoreAndComesBackIdentical() throws {
        for row in DialAddressTests.table where row.host != "example-homelab.invalid" {
            let suite = "address-edit-\(UUID().uuidString)"
            // `UserDefaults` is not Sendable and `SaveAddress`'s writer is. This suite is made, used
            // and destroyed inside one iteration and is reachable from nothing else, so the unchecked
            // box states what is actually true here rather than dodging the diagnostic.
            let store = ScratchDefaults(suite: suite)
            defer { store.discard() }

            _ = SaveAddress { try AddressPreference.write($0, to: store.defaults) }.save(row.rendered)

            #expect(try AddressPreference.read(from: store.defaults).get().displayed == row.rendered)
        }
    }

    /// The default saver writes the one place the address lives and **no second copy anywhere**.
    @Test func thereIsOnlyOneStoreInTheSource() throws {
        let source = try String(
            contentsOf: URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
                .appending(path: "Sources/AgtermRemoteCore/AddressEdit.swift"),
            encoding: .utf8)
        let code = NoAggregateVerdictTests.stripped(source)

        #expect(code.contains("AddressPreference.write"))
        // Not "no store" — one store, reached through the type that owns it. An editor that opened
        // the defaults itself would be a second writer of the same key, which is how the box and the
        // code end up disagreeing about what was saved.
        #expect(!code.contains("UserDefaults"), "a second source of truth for the address")
        #expect(!code.contains("config.json"), "the bridge's listen config is not the dial address")
    }
}

/// Saving from the pairing window: the code that is on screen afterwards.
struct SaveFromThePairingWindowTests {

    private let old = DialAddress(host: "old.example-homelab.invalid", port: 8443)

    private func afterSaving(
        _ typed: String,
        write: @escaping @Sendable (DialAddress) throws -> Void = { _ in },
        made: @escaping (DialAddress) -> Void = { _ in },
    ) -> (outcome: SaveAddress.Outcome, state: PairingWindowState) {
        PairingWindowModel.afterSaving(
            address: typed,
            using: SaveAddress(write: write),
            makeCode: { address in
                made(address)
                return .success("/tmp/code-for-\(address.host).png")
            },
            unchanged: .ready(address: old.displayed, imagePath: "/tmp/old.png", unproven: []),
        )
    }

    /// **The stale-picture trap, closed.**
    @Test func theCodeIsRemadeFromTheAddressThatWasJustSaved() {
        var madeFor: [String] = []

        let (_, state) = afterSaving("new.example-homelab.invalid:9443", made: { madeFor.append($0.displayed) })

        #expect(madeFor == ["new.example-homelab.invalid:9443"])
        guard case .ready(let address, let path, _) = state else { return #expect(Bool(false), "\(state)") }
        #expect(address == "new.example-homelab.invalid:9443")
        #expect(path.contains("new.example-homelab.invalid"))
    }

    /// **The three facts are re-asked, not inherited.**
    @Test func aSavedAddressIsUnprovenUntilSomethingAnswersOnIt() {
        let (_, state) = afterSaving("new.example-homelab.invalid:9443")

        guard case .ready(_, _, let unproven) = state else { return #expect(Bool(false), "\(state)") }
        #expect(unproven == ["This address has not been checked yet."])
    }

    @Test func aRefusedEditLeavesTheCodeOnScreenExactlyAsItWas() {
        var madeFor: [String] = []

        let (outcome, state) = afterSaving("https://old.example-homelab.invalid/", made: { madeFor.append($0.displayed) })

        #expect(madeFor.isEmpty, "it re-made a code for an address it refused to save")
        #expect(state == .ready(address: old.displayed, imagePath: "/tmp/old.png", unproven: []))
        guard case .refused = outcome else { return #expect(Bool(false), "\(outcome)") }
    }

    /// **A question is not a change.**
    @Test func anUnansweredConfirmationDisturbsNothingOnScreen() {
        var madeFor: [String] = []

        let (outcome, state) = afterSaving("example-homelab.invalid:8443", made: { madeFor.append($0.displayed) })

        #expect(madeFor.isEmpty)
        #expect(state == .ready(address: old.displayed, imagePath: "/tmp/old.png", unproven: []))
        guard case .needsConfirmation = outcome else { return #expect(Bool(false), "\(outcome)") }
    }

    @Test func aConfirmedSuffixSavesAndRemakesTheCodeLikeAnyOtherAddress() {
        var madeFor: [String] = []

        let (outcome, state) = PairingWindowModel.afterSaving(
            address: "example-homelab.invalid:8443",
            using: SaveAddress(write: { _ in }),
            makeCode: { address in
                madeFor.append(address.displayed)
                return .success("/tmp/code.png")
            },
            unchanged: .ready(address: old.displayed, imagePath: "/tmp/old.png", unproven: []),
            confirmed: true)

        #expect(outcome == .saved(DialAddress(host: "example-homelab.invalid", port: 8443)))
        #expect(madeFor == ["example-homelab.invalid:8443"])
        guard case .ready(let address, _, let unproven) = state else { return #expect(Bool(false), "\(state)") }
        #expect(address == "example-homelab.invalid:8443")
        #expect(unproven == ["This address has not been checked yet."])
    }

    @Test func aSavedAddressWhoseCodeFailsSaysSoWithTheNewAddressOnIt() {
        let (outcome, state) = PairingWindowModel.afterSaving(
            address: "new.example-homelab.invalid:9443",
            using: SaveAddress(write: { _ in }),
            makeCode: { _ in .failure(.noBinary(path: "/nowhere/certificate-tool")) },
            unchanged: .ready(address: old.displayed, imagePath: "/tmp/old.png", unproven: []))

        #expect(outcome == .saved(DialAddress(host: "new.example-homelab.invalid", port: 9443)))
        guard case .codeUnavailable(let address, _) = state else { return #expect(Bool(false), "\(state)") }
        #expect(address == "new.example-homelab.invalid:9443")
    }
}

/// A `UserDefaults` suite that exists for the length of one test.
///
/// It is `@unchecked Sendable` because `UserDefaults` is not Sendable and the writer `SaveAddress`
/// takes is. Each instance is created, written, read and destroyed inside a single test body and is
/// reachable from nowhere else, which is the property the compiler cannot see and this type asserts.
struct ScratchDefaults: @unchecked Sendable {

    let suite: String
    let defaults: UserDefaults

    init(suite: String) {
        self.suite = suite
        // A named suite rather than `.standard`: a test must never write into the preferences of the
        // app the person running it may also be using.
        self.defaults = UserDefaults(suiteName: suite)!
    }

    func discard() {
        defaults.removePersistentDomain(forName: suite)
    }
}
