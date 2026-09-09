import Foundation
import Testing

@testable import AgtermRemoteCore

/// The one fact this app cannot observe and must ask for.
///
/// Two constants answered it before, and both were wrong for somebody: `wss://` cannot reach a bridge
/// with nothing in front of it, and `ws://` cannot reach a router that proxies — which is the
/// deployment this project was written for. Neither failure is visible from either end.
@Suite struct FrontDoorTests {

    /// **The two facts, and which case carries which.**
    ///
    /// They are genuinely different — a proxy can be configured either way — so the table is written
    /// out rather than derived, and a case added later has to appear here.
    @Test func eachAnswerDecidesBothFlags() {
        #expect(FrontDoor.direct.advertiseScheme == "plain")
        #expect(FrontDoor.direct.servesOnLinkTLS == false)

        #expect(FrontDoor.httpsInFront.advertiseScheme == "tls")
        #expect(FrontDoor.httpsInFront.servesOnLinkTLS == false)

        #expect(FrontDoor.httpsBothWays.advertiseScheme == "tls")
        #expect(FrontDoor.httpsBothWays.servesOnLinkTLS == true)
    }

    /// **The fourth combination is not representable, and that is the design.**
    ///
    /// A plain outer hop with TLS on this port is a bridge nothing can reach: the phone opens a plain
    /// connection straight into a TLS listener. A type that cannot express it is worth more than a
    /// validation rule that rejects it, because the rule has to be remembered at every call site.
    @Test func nothingCanAskForTlsOnTheHopWithoutTlsInFront() {
        for door in FrontDoor.allCases where door.servesOnLinkTLS {
            #expect(
                door.advertiseScheme == "tls",
                "serving TLS on this port with a plain outer hop is a bridge nothing can reach")
        }
    }

    /// The words the two local processes actually take. They are a wire contract with the Go side's
    /// `-advertise-scheme` flag and the control socket's `scheme` field, and a typo here is a bridge
    /// that refuses to mint a code.
    @Test func theSchemesAreTheWordsTheBridgeAccepts() {
        #expect(Set(FrontDoor.allCases.map(\.advertiseScheme)) == ["plain", "tls"])
    }

    /// Every case has copy, and adding one without it fails here rather than showing a blank row.
    @Test func everyAnswerHasSomethingAPersonCanRead() {
        for door in FrontDoor.allCases {
            #expect(!FrontDoorCopy.label(for: door).isEmpty)
            #expect(!FrontDoorCopy.detail(for: door).isEmpty)
        }
        #expect(Set(FrontDoor.allCases.map(FrontDoorCopy.label(for:))).count == FrontDoor.allCases.count)
    }

    /// **The copy may not require a person to know the mechanism.**
    ///
    /// This screen is read by somebody who set up a router, not by somebody who knows what an on-link
    /// hop is. The third answer is the one at risk, because the thing it configures has no
    /// plain-language name — so it names the SYMPTOM its owner will actually have in front of them.
    @Test func theCopyNamesSymptomsRatherThanMechanisms() {
        let words = ["on-link", "ALPN", "mTLS", "TLS termination", "backend TLS", "opportunistic"]
        for door in FrontDoor.allCases {
            let text = FrontDoorCopy.label(for: door) + " " + FrontDoorCopy.detail(for: door)
            for word in words {
                #expect(
                    !text.lowercased().contains(word.lowercased()),
                    "\(door) asks a person to know \(word)")
            }
        }
        #expect(FrontDoorCopy.detail(for: .httpsBothWays).contains("502"))
    }

    /// Absent means the simplest deployment, and an unreadable stored value means the same rather
    /// than an error: it is the shape a downgrade leaves behind, and the remedy is identical.
    @Test func anUnsetOrUnreadableChoiceIsTheSimplestDeployment() {
        let defaults = UserDefaults(suiteName: "front-door-\(UUID().uuidString)")!
        #expect(AddressPreference.frontDoor(from: defaults) == .direct)

        defaults.set("something a later version wrote", forKey: AddressPreference.frontDoorKey)
        #expect(AddressPreference.frontDoor(from: defaults) == .direct)
    }

    @Test func theChoiceSurvivesBeingWrittenAndReadBack() {
        let defaults = UserDefaults(suiteName: "front-door-\(UUID().uuidString)")!
        for door in FrontDoor.allCases {
            AddressPreference.writeFrontDoor(door, to: defaults)
            #expect(AddressPreference.frontDoor(from: defaults) == door)
        }
    }

    /// **Changing what stands in front does not discard the proof an address has earned.**
    ///
    /// Unlike changing the address itself. A pairing that has already happened happened; what a wrong
    /// answer here breaks is the next code, which is why the setup screen asks before minting one.
    @Test func changingTheFrontDoorKeepsTheAddressProof() throws {
        let defaults = UserDefaults(suiteName: "front-door-\(UUID().uuidString)")!
        let address = try DialAddress.parse("example.test:8443").get()
        try AddressPreference.write(address, to: defaults, at: Date(timeIntervalSince1970: 1_000))
        AddressPreference.recordEnrolment(at: Date(timeIntervalSince1970: 2_000), in: defaults)
        let proven = AddressPreference.provenAt(from: defaults)
        #expect(proven != nil)

        AddressPreference.writeFrontDoor(.httpsBothWays, to: defaults)

        #expect(AddressPreference.provenAt(from: defaults) == proven)
    }
}
