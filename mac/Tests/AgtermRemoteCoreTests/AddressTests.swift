import Foundation
import Testing
@testable import AgtermRemoteCore

/// The onboarding view of an address: does it parse, and what does the bridge bind because of it.
///
/// The detailed refusals a person editing an address needs live in `AddressEditTests`, which has a
/// sentence for each of nine outcomes. These four are the coarse gate onboarding asks with — is there
/// an address here at all — and the two questions that only this type answers: what the bridge is told
/// to listen on, and that it is **not** derived from the host somebody typed.
struct AddressTests {

    @Test func aHostAndAPortParse() {
        #expect(Address.parse("example.test:8443") == .success(Address(host: "example.test", port: 8443)))
    }

    @Test func nothingTypedIsEmpty() {
        #expect(Address.parse("") == .failure(.empty))
    }

    /// **A port with nothing in front of it is not "you typed nothing".** `:8443` used to report
    /// `.empty`, which is a sentence somebody can see is wrong while looking at their own text — and
    /// a screen that is wrong about something visible is not trusted about anything else.
    @Test func aPortWithNoHostSaysTheHostIsMissing() {
        #expect(Address.parse(":8443") == .failure(.hostMissing))
        #expect(Address.parse("") == .failure(.empty))
        #expect(Address.parse("   ") == .failure(.empty))
    }

    @Test func aHostWithNoPortIsNoPort() {
        #expect(Address.parse("example.test") == .failure(.noPort))
    }

    /// Zero and 99999 are the two ends of the same mistake: a number sits where a port goes and it is
    /// not one.
    @Test func aPortOutsideTheRangeIsABadPort() {
        #expect(Address.parse("example.test:0") == .failure(.badPort))
        #expect(Address.parse("example.test:99999") == .failure(.badPort))
    }

    @Test func aPortThatIsNotANumberIsABadPort() {
        #expect(Address.parse("example.test:https") == .failure(.badPort))
    }

    /// **The one refusal that is about what somebody typed rather than about the syntax.** A pasted
    /// URL parses as a host of `https` and a port of nothing, which would be reported as a missing
    /// port — a sentence that sends them off to add `:8443` to a string whose real problem is the
    /// scheme and the path.
    @Test func aPastedURLIsSaidToBeAURL() {
        #expect(Address.parse("https://example.test:8443") == .failure(.hostLooksLikeAURL))
    }

    /// Several colons and no brackets: there is no port to be read, because there is no way to tell
    /// which colon starts it. Same answer as no colon at all — **no port could be read from this** —
    /// which is what `noPort` means in this four-word vocabulary.
    @Test func aColonThatCannotBeFoundIsNoPort() {
        #expect(Address.parse("fe80::1:8443") == .failure(.noPort))
        #expect(Address.parse("[fe80::1:8443") == .failure(.noPort))
    }

    /// Bracketed IPv6 is one machine, and it parses. The brackets are syntax, not name.
    @Test func aBracketedLiteralParsesWithoutItsBrackets() {
        #expect(Address.parse("[fe80::1]:8443") == .success(Address(host: "fe80::1", port: 8443)))
    }

    /// **The listen address takes the port and nothing else.**
    ///
    /// Two different destinations that forward to the same port produce the same listen address,
    /// because the host in a dial address belongs to a router and never to an interface on this Mac.
    @Test func theBridgeIsToldToListenOnEveryInterfaceAtTheDialledPort() {
        #expect(Address(host: "example.test", port: 8443).listen == "0.0.0.0:8443")
        #expect(Address(host: "203.0.113.5", port: 8443).listen == "0.0.0.0:8443")
        #expect(Address(host: "fe80::1", port: 9000).listen == "0.0.0.0:9000")
    }

    /// The property the sentence above is really about: **the host somebody typed never reaches the
    /// bind.** A bridge that bound the dial host would either refuse to start, on a name no interface
    /// here holds, or bind one interface on a Mac whose phone arrives over another.
    @Test func theDialHostNeverAppearsInWhatTheBridgeBinds() {
        for host in ["example.test", "agterm.example-homelab.invalid", "203.0.113.5", "fe80::1"] {
            #expect(!Address(host: host, port: 8443).listen.contains(host))
        }
    }

    /// **The dial port and the arrival port differ, and that is a topology rather than a mistake.**
    ///
    /// A router that publishes 8443 and proxies it to 8444 on this Mac is the case the source
    /// project's runbook records as the one that cost it two failed pairings. Deriving the bind from
    /// the dial address makes the bridge listen on 8443 while traffic lands on 8444 — a perfect code
    /// and a phone that never connects.
    @Test func theArrivalPortIsWhatTheBridgeBindsWhenItDiffers() {
        let address = Address(host: "agterm.example-homelab.invalid", port: 8443)

        #expect(address.listen(on: 8444) == "0.0.0.0:8444")
        // And the dial address is untouched by it. The two travel to the bridge as two flags — the
        // bind and the address a phone dials — because a code minted from the bind names every
        // interface and therefore none.
        #expect(address.dial.displayed == "agterm.example-homelab.invalid:8443")
    }

    /// No arrival port means follow the dial port — **stored as an absence, never as a copy**. A copy
    /// goes stale the moment the address is edited, and it would go stale for the person with the
    /// simplest setup.
    @Test func theArrivalPortFollowsTheDialPortUntilItIsSet() {
        #expect(Address(host: "example.test", port: 8443).listen(on: nil) == "0.0.0.0:8443")
        #expect(Address(host: "example.test", port: 8443).listen == "0.0.0.0:8443")
        #expect(Address(host: "example.test", port: 9000).listen(on: nil) == "0.0.0.0:9000")
    }

    /// One address, one shape, on both types. `Address` is the onboarding-shaped view of the value
    /// `DialAddress` renders for the phone; if they could disagree there would be two addresses.
    @Test func itIsTheSameValueTheRestOfTheAppRenders() {
        let dial = DialAddress(host: "agterm.example-homelab.invalid", port: 8443)

        #expect(Address(dial) == Address(host: dial.host, port: dial.port))
        #expect(Address(dial).dial == dial)
        #expect(Address.parse(dial.displayed) == .success(Address(dial)))
    }
}
