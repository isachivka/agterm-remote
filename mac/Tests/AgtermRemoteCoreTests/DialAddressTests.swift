import Foundation
import Testing
@testable import AgtermRemoteCore

/// The same case table as `PairingAddressTest.kt`, deliberately.
///
/// The phone shows this address on its comparison screen and the Mac shows it beside the code. Nothing
/// at runtime checks that the two agree, so nothing at runtime would notice them diverging — the owner
/// would compare two renderings of one address, correctly, and get the wrong answer. Both sides are
/// held to these cases; if a row here stops matching the Kotlin, one of them has grown its own rule.
///
/// Hosts are `.invalid` and `.example` by construction. The laptop's real address does not belong in
/// this repository.
struct DialAddressTests {

    /// **The case table, once, so the formatter and the parser cannot each have their own.**
    static let table: [(host: String, port: Int, rendered: String)] = [
        ("agterm.example-homelab.invalid", 8443, "agterm.example-homelab.invalid:8443"),
        ("agterm.example-homelab.invalid.", 8443, "agterm.example-homelab.invalid.:8443"),
        ("AGTERM.Example-Homelab.invalid", 8443, "AGTERM.Example-Homelab.invalid:8443"),
        ("agterm.example-homelab.invalid", 443, "agterm.example-homelab.invalid:443"),
        ("example-homelab.invalid", 8443, "example-homelab.invalid:8443"),
        ("192.0.2.10", 8444, "192.0.2.10:8444"),
        ("2001:db8::1", 8443, "[2001:db8::1]:8443"),
        ("workshop", 8443, "workshop:8443"),
        ("a-long.subdomain.example-homelab.invalid", 65535, "a-long.subdomain.example-homelab.invalid:65535"),
    ]

    /// **Format, then parse, and get the same value back** — for every row. A parser that disagrees
    /// with the formatter turns one destination into two, and the editor now round-trips through both.
    @Test func everyRowSurvivesFormatThenParse() throws {
        for row in Self.table {
            let rendered = DialAddress(host: row.host, port: row.port).displayed
            #expect(rendered == row.rendered)

            let back = try DialAddress.parse(rendered).get()

            #expect(back == DialAddress(host: row.host, port: row.port), "\(rendered) did not survive")
        }
    }

    /// **Parse, then format, and get the same string back.** The other direction, because a round trip
    /// that only holds one way still loses somebody's address.
    @Test func everyRowSurvivesParseThenFormat() throws {
        for row in Self.table {
            #expect(try DialAddress.parse(row.rendered).get().displayed == row.rendered)
        }
    }

    /// **The one asymmetry, stated rather than hidden.** `[2001:db8::1]` and `2001:db8::1` are the same
    /// machine and render identically, so parsing cannot recover which of the two was typed — brackets
    /// are the syntax that makes `host:port` readable, not part of the name. Parsing yields the
    /// unbracketed host, whose rendering is the same string. Nothing else is unwrapped, folded or
    /// dropped.
    @Test func bracketsAreSyntaxAndNotPartOfTheName() throws {
        let bracketed = DialAddress(host: "[2001:db8::1]", port: 8443)

        let back = try DialAddress.parse(bracketed.displayed).get()

        #expect(back.host == "2001:db8::1")
        #expect(back.displayed == bracketed.displayed, "the two renderings must stay identical")
    }

    /// Several colons and no brackets cannot be split without guessing which one starts the port —
    /// **and guessing wrong here points a phone at a port nobody serves.** This is what brackets are
    /// for, and the refusal says so.
    @Test func anUnbracketedIPv6IsRefusedRatherThanGuessedAt() {
        guard case .failure(let why) = DialAddress.parse("2001:db8::1:8443") else {
            return #expect(Bool(false), "it guessed")
        }
        #expect(why == .ambiguousWithoutBrackets("2001:db8::1:8443"))
    }

    @Test func aPlainHostRendersAsHostAndPort() {
        #expect(DialAddress(host: "agterm.example-homelab.invalid", port: 8443).displayed
            == "agterm.example-homelab.invalid:8443")
    }

    /// **The port is never elided, not even when it looks like a default.** The bridge listens on 8444
    /// and the router publishes 8443; a QR carrying the listen address pairs and then never connects,
    /// so a renderer that hid "the obvious port" would hide the field most likely to be wrong.
    @Test func port443IsShownRatherThanTreatedAsADefault() {
        #expect(DialAddress(host: "agterm.example-homelab.invalid", port: 443).displayed
            == "agterm.example-homelab.invalid:443")
        #expect(DialAddress(host: "agterm.example-homelab.invalid", port: 443).displayed
            != DialAddress(host: "agterm.example-homelab.invalid", port: 8443).displayed)
    }

    /// **The failure that actually happened.** The bare dynamic-DNS name is the homelab's suffix, shared
    /// with every other service on it; the bridge lives on its own subdomain. They are one label
    /// apart and one is a suffix of the other, so a renderer that truncated or right-aligned could
    /// show them identically.
    @Test func aBareSuffixAndAHostUnderItRenderDistinctlyAndInFull() {
        let suffix = DialAddress(host: "example-homelab.invalid", port: 8443).displayed
        let host = DialAddress(host: "agterm.example-homelab.invalid", port: 8443).displayed

        #expect(suffix != host)
        #expect(suffix == "example-homelab.invalid:8443")
        #expect(host == "agterm.example-homelab.invalid:8443")
        #expect(host.hasPrefix("agterm."), "the distinguishing label was dropped")
    }

    /// A trailing dot is a different name, so it must look like one.
    @Test func aTrailingDotSurvivesAndStaysDistinguishable() {
        #expect(DialAddress(host: "agterm.example-homelab.invalid.", port: 8443).displayed
            == "agterm.example-homelab.invalid.:8443")
        #expect(DialAddress(host: "agterm.example-homelab.invalid.", port: 8443).displayed
            != DialAddress(host: "agterm.example-homelab.invalid", port: 8443).displayed)
    }

    @Test func anIPv6LiteralIsBracketed() {
        #expect(DialAddress(host: "2001:db8::1", port: 8443).displayed == "[2001:db8::1]:8443")
    }

    @Test func aHostThatAlreadyCarriesBracketsIsNotBracketedTwice() {
        #expect(DialAddress(host: "[2001:db8::1]", port: 8443).displayed == "[2001:db8::1]:8443")
    }

    @Test func anIPv4LiteralIsLeftAlone() {
        #expect(DialAddress(host: "192.0.2.10", port: 8444).displayed == "192.0.2.10:8444")
    }

    /// Shortening belongs to the screen, which may wrap. It may never abbreviate: the abbreviated
    /// part is where a wrong address hides.
    @Test func aLongHostIsNotTruncatedOrEllipsised() {
        let long = "a-really-quite-long-subdomain.beneath-another-long-one.example-homelab.invalid"

        let rendered = DialAddress(host: long, port: 65535).displayed

        #expect(rendered == "\(long):65535")
        #expect(!rendered.contains("…") && !rendered.contains("..."), "the host was abbreviated")
    }

    /// Case is carried through rather than folded. DNS does not care, but two profiles differing by a
    /// byte must not become one string on the screen that exists to tell them apart.
    @Test func caseIsPreservedRatherThanNormalisedAway() {
        #expect(DialAddress(host: "AGTERM.Example-Homelab.invalid", port: 8443).displayed
            == "AGTERM.Example-Homelab.invalid:8443")
    }
}
