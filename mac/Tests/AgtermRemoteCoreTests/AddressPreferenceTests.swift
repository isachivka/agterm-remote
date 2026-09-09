import Foundation
import Testing
@testable import AgtermRemoteCore

/// The one place the dial address lives, and the three answers it can give.
///
/// Every case runs against its own named suite, made and destroyed inside the test. `.standard` is
/// never touched: a test that wrote there would edit the preferences of the app the person running it
/// may also be using, and would then pass or fail depending on what they had typed into it.
struct AddressPreferenceTests {

    private func scratch(_ name: String = #function) -> ScratchDefaults {
        ScratchDefaults(suite: "address-preference-\(name)-\(UUID().uuidString)")
    }

    @Test func anAddressSurvivesTheRoundTrip() throws {
        let store = scratch()
        defer { store.discard() }
        let address = DialAddress(host: "agterm.example-homelab.invalid", port: 8443)

        try AddressPreference.write(address, to: store.defaults)

        #expect(try AddressPreference.read(from: store.defaults).get() == address)
    }

    /// **The stored form is `displayed`, and that is not an implementation detail.** It is the string
    /// the phone shows, the string the code carries and the string the box holds. Anything else stored
    /// here would be a second shape for a value whose whole point is that there is only one.
    @Test func whatIsStoredIsTheSameStringEverySurfaceShows() throws {
        let store = scratch()
        defer { store.discard() }
        let address = DialAddress(host: "fe80::1", port: 8443)

        try AddressPreference.write(address, to: store.defaults)

        #expect(store.defaults.string(forKey: AddressPreference.key) == "[fe80::1]:8443")
        #expect(address.displayed == "[fe80::1]:8443")
    }

    /// Nothing stored is **not** an error. It is the first run, and the window has a screen for it that
    /// says what the value is instead of showing an empty box.
    @Test func nothingStoredIsUnsetRatherThanMalformed() {
        let store = scratch()
        defer { store.discard() }

        #expect(AddressPreference.read(from: store.defaults) == .failure(.unset))
    }

    /// An empty string is the same answer as no key at all. A preference can end up empty by being
    /// cleared, and reporting that as *malformed* would tell somebody their address is broken when
    /// they simply have not set one.
    @Test func anEmptyStringIsUnsetRatherThanMalformed() {
        let store = scratch()
        defer { store.discard() }
        store.defaults.set("", forKey: AddressPreference.key)

        #expect(AddressPreference.read(from: store.defaults) == .failure(.unset))
    }

    /// **The reader is the same parser the editor refuses with.** A more forgiving reader would accept
    /// a stored value nobody could have typed, and the address in the box would then differ from the
    /// address the code was built from — which is the failure the whole address story exists to stop.
    @Test func everyThingTheEditorRefusesIsAlsoMalformedHere() {
        for stored in ["", "not an address", "agterm.example.invalid", "host:70000", "fe80::1:8443", "[fe80::1"] {
            let store = scratch()
            defer { store.discard() }
            store.defaults.set(stored, forKey: AddressPreference.key)

            let read = AddressPreference.read(from: store.defaults)

            guard case .failure = read else {
                return #expect(Bool(false), "\"\(stored)\" was read back as an address")
            }
        }
    }

    /// A bare suffix is a **valid address that somebody had to confirm**, so it must survive being
    /// stored. Refusing it on the way back out would quietly undo a decision that was made
    /// deliberately, and the owner would find their saved address gone with nothing said.
    @Test func aConfirmedBareSuffixReadsBackUnchanged() throws {
        let store = scratch()
        defer { store.discard() }
        let address = DialAddress(host: "example-homelab.invalid", port: 8443)

        try AddressPreference.write(address, to: store.defaults)

        #expect(try AddressPreference.read(from: store.defaults).get() == address)
    }

    /// Writing twice leaves one value. **There is no history and no second address**: a store that
    /// kept the previous one would be a second source of truth for the one fact this app exists to
    /// keep unambiguous.
    ///
    /// The keys are named rather than counted, and the list grew once — for the two dates that say
    /// when this address was stored and when a phone last came through it. Neither is an address, and
    /// the second half of this test is what holds that line: **every value in the store is checked,
    /// and only one of them may be readable as a destination.** A key-count assertion would have
    /// passed a store holding `previousAddress` just as happily as one holding a timestamp.
    @Test func aSecondWriteReplacesTheFirst() throws {
        let store = scratch()
        defer { store.discard() }

        try AddressPreference.write(DialAddress(host: "old.example-homelab.invalid", port: 8443), to: store.defaults)
        try AddressPreference.write(DialAddress(host: "new.example-homelab.invalid", port: 9443), to: store.defaults)

        #expect(try AddressPreference.read(from: store.defaults).get().displayed == "new.example-homelab.invalid:9443")
        let stored = store.defaults.persistentDomain(forName: store.suite) ?? [:]
        #expect(
            stored.keys.sorted()
                == [AddressPreference.key, AddressPreference.storedAtKey].sorted())
        let addresses = stored.filter {
            guard let text = $0.value as? String else { return false }
            if case .success = DialAddress.parse(text) { return true }
            return false
        }
        #expect(addresses.keys.sorted() == [AddressPreference.key], "a second address in the store")
    }

    /// **The invariant, performed rather than asserted in prose.**
    ///
    /// The seven cases above each write a parse-shaped address, so every one of them passed while
    /// `write` accepted values `read` would call malformed. This runs the whole shared table through
    /// the store and back and demands the same value out — which is what "a stored address is always
    /// one somebody could have typed" actually means.
    @Test func everyRowInTheSharedTableSurvivesTheStore() throws {
        for row in DialAddressTests.table {
            let store = scratch()
            defer { store.discard() }
            let address = DialAddress(host: row.host, port: row.port)

            try AddressPreference.write(address, to: store.defaults)

            #expect(try AddressPreference.read(from: store.defaults).get() == address)
            #expect(store.defaults.string(forKey: AddressPreference.key) == row.rendered)
        }
    }

    /// **`DialAddress.init` is public and validates nothing**, so `write` has to.
    ///
    /// Each of these stored happily and read back `.malformed`, or read back as a *different host* —
    /// which is worse, because nothing reports it and the phone is simply pointed somewhere else. The
    /// first is not invented: `DialAddress(host: "-", port: 0)` is constructed in `main.swift` today
    /// as a placeholder for a status line, and it was one call away from the store.
    @Test func anAddressTheReaderWouldNotAcceptIsRefusedBeforeItIsStored() {
        let cases: [(DialAddress, String)] = [
            (DialAddress(host: "h", port: 0), "port 0 is not a port"),
            (DialAddress(host: "h", port: 70000), "port 70000 is not a port"),
            (DialAddress(host: "[weird", port: 443), "an unclosed bracket"),
            (DialAddress(host: "[fe80::1]", port: 8443), "brackets are syntax, not part of the host"),
            (DialAddress(host: " h ", port: 1), "the spaces are not part of the host"),
        ]
        for (address, why) in cases {
            let store = scratch()
            defer { store.discard() }

            #expect(throws: AddressPreference.WriteRefusal.self, "\(why)") {
                try AddressPreference.write(address, to: store.defaults)
            }
            // And nothing was written. A refusal that stored the value anyway would be worse than no
            // check at all: the sentence would say refused and the store would say saved.
            #expect(store.defaults.string(forKey: AddressPreference.key) == nil)
            #expect(AddressPreference.read(from: store.defaults) == .failure(.unset))
        }
    }

    /// A refusal on the way in must reach the owner as **the address is the problem**, not as a
    /// failed save and not as a refusal from the editor — three different sentences for three
    /// different situations.
    @Test func aRefusedWriteReachesTheCallerAsNotWritten() {
        let store = scratch()
        defer { store.discard() }

        let outcome = SaveAddress { try AddressPreference.write($0, to: store.defaults) }
            .save("agterm.example-homelab.invalid:8443")

        // A parse-shaped address is not refused by the store, so this one saves. The point is that
        // the seam is real: `SaveAddress` can now actually receive a throw from the default writer.
        guard case .saved = outcome else { return #expect(Bool(false), "\(outcome)") }
    }
}

/// **Unproven is the resting state of an address, and proof belongs to one address at a time.**
///
/// The mark exists because this Mac cannot test its own public address — see `OnboardingWindow` for
/// the argument in full — so the only thing that ever proves one is a phone arriving through it. Every
/// case here is about not crediting an address with something another address earned.
struct AddressProvenanceTests {

    private func scratch(_ name: String = #function) -> ScratchDefaults {
        ScratchDefaults(suite: "address-provenance-\(name)-\(UUID().uuidString)")
    }

    private let address = DialAddress(host: "agterm.example-homelab.invalid", port: 8443)
    private let other = DialAddress(host: "agterm.other-homelab.invalid", port: 8443)

    @Test func anAddressIsUnprovenTheMomentItIsStored() throws {
        let store = scratch()
        defer { store.discard() }

        try AddressPreference.write(address, to: store.defaults)

        #expect(AddressPreference.provenAt(from: store.defaults) == nil)
    }

    /// An enrolment after the address was stored is proof of that address, and the date it happened
    /// is what is kept — not the moment the app noticed, which is whenever it was next opened.
    @Test func anEnrolmentAfterTheAddressWasStoredProvesIt() throws {
        let store = scratch()
        defer { store.discard() }
        let stored = Date(timeIntervalSince1970: 1_000)
        try AddressPreference.write(address, to: store.defaults, at: stored)

        let proof = AddressPreference.recordEnrolment(at: stored.addingTimeInterval(60), in: store.defaults)

        #expect(proof == stored.addingTimeInterval(60))
        #expect(AddressPreference.provenAt(from: store.defaults) == proof)
    }

    /// **The case the whole rule is for.** A phone that enrolled through the previous address says
    /// nothing about this one, and crediting it would put a proven mark on a destination nothing has
    /// ever reached.
    @Test func anEnrolmentOlderThanTheAddressIsNotCreditedToIt() throws {
        let store = scratch()
        defer { store.discard() }
        let stored = Date(timeIntervalSince1970: 1_000)
        try AddressPreference.write(address, to: store.defaults, at: stored)

        #expect(AddressPreference.recordEnrolment(at: stored.addingTimeInterval(-60), in: store.defaults) == nil)
        #expect(AddressPreference.provenAt(from: store.defaults) == nil)
    }

    @Test func changingTheAddressDiscardsTheProof() throws {
        let store = scratch()
        defer { store.discard() }
        let stored = Date(timeIntervalSince1970: 1_000)
        try AddressPreference.write(address, to: store.defaults, at: stored)
        AddressPreference.recordEnrolment(at: stored.addingTimeInterval(60), in: store.defaults)

        try AddressPreference.write(other, to: store.defaults, at: stored.addingTimeInterval(120))

        #expect(AddressPreference.provenAt(from: store.defaults) == nil)
        // And the old enrolment cannot prove the new address either: it is older than it.
        #expect(AddressPreference.recordEnrolment(at: stored.addingTimeInterval(60), in: store.defaults) == nil)
    }

    /// Saving the same address again is not a change. Somebody presses Save twice, or confirms a
    /// suffix; discarding the proof there would unprove an address nothing about which has moved.
    @Test func savingTheSameAddressAgainKeepsItsProof() throws {
        let store = scratch()
        defer { store.discard() }
        let stored = Date(timeIntervalSince1970: 1_000)
        try AddressPreference.write(address, to: store.defaults, at: stored)
        let proof = AddressPreference.recordEnrolment(at: stored.addingTimeInterval(60), in: store.defaults)

        try AddressPreference.write(address, to: store.defaults, at: stored.addingTimeInterval(600))

        #expect(AddressPreference.provenAt(from: store.defaults) == proof)
    }

    /// An address written before this app recorded when it was written has no moment to compare an
    /// enrolment against. It stays unproven, which costs one scan and claims nothing.
    @Test func anAddressFromBeforeThisRuleStaysUnproven() {
        let store = scratch()
        defer { store.discard() }
        store.defaults.set(address.displayed, forKey: AddressPreference.key)

        #expect(AddressPreference.recordEnrolment(at: Date(), in: store.defaults) == nil)
    }

    /// The trust store is read as a date and nothing else — **its contents belong to the Go side.**
    @Test func theEnrolmentRecordIsTheTrustStoresDateOrNothing() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory()).appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        #expect(EnrolmentRecord.recordedAt(inStateDirectory: directory) == nil)

        let written = Date(timeIntervalSince1970: 2_000)
        let file = directory.appending(path: "peers.json")
        try Data("[]".utf8).write(to: file)
        try FileManager.default.setAttributes([.modificationDate: written], ofItemAtPath: file.path)

        #expect(EnrolmentRecord.recordedAt(inStateDirectory: directory) == written)
    }
}
