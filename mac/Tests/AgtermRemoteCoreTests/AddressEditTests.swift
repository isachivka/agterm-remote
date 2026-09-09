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

/// Saving an address, and **what happens to a code that was minted from the previous one.**
///
/// This used to be a set of tests over `PairingWindowModel.afterSaving`, which saved the address and
/// re-made the code in one call. The pairing window no longer edits addresses — that moved to the
/// setup screen, beside the explanation of what an address is for — so the two acts are no longer one
/// function. **The trap they were closing is still open, and it is worse now, not better:** a code on
/// screen built from an address that has since changed is scannable, it pairs, and it points the
/// phone at the previous destination.
///
/// So what is asserted is the same property in its new home. The refusals belong to `SaveAddress` and
/// are asserted directly; the screen's half — a saved address closes the enrolment window the old code
/// belonged to — is read out of the app, which is the only place it now lives.
struct SavingAnAddressUnderALiveCodeTests {

    /// The same shape `SaveAddressTests` uses: a reference the writer can append to, because the
    /// writer is `@Sendable` and a captured `var` is not.
    private final class Log: @unchecked Sendable {
        var written: [DialAddress] = []
    }

    /// A refused edit writes nothing, so there is nothing for a code to be stale against.
    @Test func aRefusedEditWritesNothing() {
        let log = Log()
        let save = SaveAddress(write: { log.written.append($0) })

        let outcome = save.save("https://old.example-homelab.invalid/")

        #expect(log.written.isEmpty, "it wrote an address it refused")
        guard case .refused = outcome else { return #expect(Bool(false), "\(outcome)") }
    }

    /// **A question is not a change.** An unconfirmed bare suffix writes nothing either.
    @Test func anUnansweredConfirmationWritesNothing() {
        let log = Log()
        let save = SaveAddress(write: { log.written.append($0) })

        let outcome = save.save("example-homelab.invalid:8443")

        #expect(log.written.isEmpty)
        guard case .needsConfirmation = outcome else { return #expect(Bool(false), "\(outcome)") }
    }

    @Test func aConfirmedSuffixSavesLikeAnyOtherAddress() {
        let log = Log()
        let save = SaveAddress(write: { log.written.append($0) })

        let outcome = save.save("example-homelab.invalid:8443", confirmed: true)

        #expect(outcome == .saved(DialAddress(host: "example-homelab.invalid", port: 8443)))
        #expect(log.written == [DialAddress(host: "example-homelab.invalid", port: 8443)])
    }

    /// **The stale-picture trap, closed in the one place it can now be closed.**
    ///
    /// Read out of the app's source because there is no value left to hand a fake: the panel holds a
    /// live enrolment window at the bridge, and what has to happen on a save is that the window is
    /// closed and the address on the panel is replaced. A comment promising it is not a mechanism.
    @Test func savingAnAddressClosesTheWindowTheOldCodeBelongedTo() throws {
        let main = try #require(
            AppSources.all().first { $0.name.hasSuffix("Sources/AgtermRemote/main.swift") })

        // The save path reaches for the panel's own close, which is what sends `pair-close` to the
        // bridge, and it replaces the address the panel shows in the same act.
        #expect(main.code.contains("panel.close()"), "a saved address leaves the old code minted")
        #expect(main.code.contains("pairing.address = address.displayed"))
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
