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

    /// One address, one shape, on both types. `Address` is the onboarding-shaped view of the value
    /// `DialAddress` renders for the phone; if they could disagree there would be two addresses.
    @Test func itIsTheSameValueTheRestOfTheAppRenders() {
        let dial = DialAddress(host: "agterm.example-homelab.invalid", port: 8443)

        #expect(Address(dial) == Address(host: dial.host, port: dial.port))
        #expect(Address(dial).dial == dial)
        #expect(Address.parse(dial.displayed) == .success(Address(dial)))
    }
}
