// Package enroll defines the payload a phone reads off the QR code the Mac app shows, and nothing
// else. It is the first half of the project's one central feature: one scan on the phone, zero
// clicks on the Mac.
//
// # What the payload is for
//
// The Mac app draws a QR code; the phone scans it and, from those bytes alone, knows where to dial,
// which certificate to accept there, and what one-time secret proves it was in the room. Everything
// that follows - the TLS handshake, the token, the phone sending its own certificate back - is
// bootstrapped from this and from nothing else. There is no directory, no account and no server in
// the middle to ask.
//
// # Text, not QR byte mode
//
// EncodeToText produces standard, padded base64, and the QR code carries that text. Byte mode would
// be shorter and it was not chosen, deliberately: a payload that survives being read as TEXT also
// survives being copied out of a message, pasted, or typed by hand - which is the fallback when a
// camera will not read the code, in the dark or on a cracked screen. The cost is about a third more
// characters. The benefit is that pairing never has a dead end.
//
// STANDARD alphabet, and PADDED, because the Android side decodes with java.util.Base64.getDecoder().
// That decoder refuses the URL-safe alphabet, which is invisible from inside Go - a URL-safe encoder
// and a standard one round-trip equally well here - so it is pinned by a test rather than left to be
// remembered.
//
// It does NOT refuse missing padding, which is what this comment said until the Kotlin decoder was
// written against the vectors. Measured on JDK 21: getDecoder().decode("AQAMZXhhbXBsZQ") returns the
// same ten bytes as the padded form, because '=' is "accepted and interpreted as the end of the
// encoded byte data, but is not required"; what it does refuse is a final unit of the wrong LENGTH,
// so "AQAMZXhhbXBsZQ=" throws and the unpadded form does not. StdEncoding here refuses both, so the
// two sides would have disagreed about whether an unpadded code is a code at all - and the Kotlin
// side has to enforce the padding itself. The `unpadded` reject vector is what makes that a
// requirement rather than a detail somebody notices.
//
// # A fingerprint, not the certificate
//
// The payload carries the SHA-256 of the bridge certificate's DER, not the DER. It is a third of the
// size, which is a third fewer modules in the symbol and a code that scans across the room instead
// of under the nose; and it costs nothing, because the full certificate arrives moments later over
// the connection this fingerprint is what authenticates. The phone compares what the server presents
// against these 32 bytes and hangs up on a mismatch.
//
// # This decoder is an attack surface
//
// Decode runs before any authentication whatsoever, on bytes a stranger can put in front of the
// owner's camera. So it refuses rather than interprets: a version it does not know, a buffer that
// ends early, a length field that overruns what it was given, and any trailing byte at all. No length
// field sizes an allocation without MaxField over it. A decoder that guesses is a decoder an attacker
// can steer.
package enroll

import (
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"strconv"
	"time"
	"unicode/utf8"
)

// Version is the first byte of every payload, and the version this build MINTS.
//
// A phone that reads a version it does not know must say so to its owner - "this Mac is newer than
// this app" - rather than parse the remainder hopefully. That is why the version is FIRST: it is
// readable from one byte, before any length field has been trusted.
//
// # Why 2, and what version 1 got wrong
//
// Version 1 carried a host and a port and nothing about HOW to reach them, so every implementation
// had to assume one answer for every deployment. That assumption was wrong for the deployment this
// project was written for: a router that publishes the Mac by proxying it, terminating HTTPS at the
// edge. A phone that opens a plain connection to such a front door never reaches the bridge, and
// nothing anywhere says why - the address is right, the fingerprint is right, and the code simply
// does not work.
//
// It cannot be inferred and must not be guessed. Trying one and falling back to the other doubles the
// worst case and replaces a fact the owner knows with a heuristic, in a design whose whole discipline
// about failure is that it never invents a cause. So the owner says it once, on the Mac, and it
// travels in the payload.
const Version = 2

// VersionLegacy is version 1, which this build DECODES and never mints.
//
// It is kept readable rather than retired because a decoder that refuses it would report "this Mac is
// newer than this app" for a code that this build can read perfectly - the wrong sentence, and the
// one a person cannot act on. A version 1 payload means [SchemePlain], because that is the only thing
// version 1 implementations ever did.
const VersionLegacy = 1

// Scheme is how the phone opens the OUTER hop - the one that reaches the front door, not the pinned
// mTLS inside it.
//
// **It says nothing about trust and cannot.** The outer hop authenticates nobody in either form: the
// laptop is identified by the fingerprint in this same payload, compared against the certificate
// presented by the pinned TLS that runs INSIDE the upgraded stream. What this field decides is
// whether the phone can reach the front door at all.
type Scheme uint8

const (
	// SchemePlain is a plain connection to the bridge's own port: `ws://`.
	//
	// Right for a forwarded port, for a fixed address or a dynamic-DNS name, and for a mesh network
	// that carries the packets itself - the phone reaches the bridge, and the bridge is what answers.
	SchemePlain Scheme = 1
	// SchemeTLS is a TLS connection to whatever publishes the Mac: `wss://`.
	//
	// Right for a router that proxies rather than forwards, for a tunnel whose edge is HTTPS, and for
	// a reverse proxy in front. What terminates that TLS is the proxy, with its own certificate,
	// which this design neither pins nor cares about - see the type comment.
	SchemeTLS Scheme = 2
)

// Known reports whether this build understands the scheme.
//
// Separate from the switch in [Scheme.URLScheme] so that a scheme added later cannot be silently
// treated as one of these two by a decoder that forgot to widen its own check.
func (s Scheme) Known() bool { return s == SchemePlain || s == SchemeTLS }

// URLScheme is the two spellings the phone actually dials with.
//
// Here rather than at the call site, and pinned across languages by `dial_url` on every accept
// vector, for the same reason [Payload.DialAddress] is: a Go helper the Android app cannot import
// proves nothing about the Android app.
func (s Scheme) URLScheme() string {
	if s == SchemeTLS {
		return "wss"
	}
	return "ws"
}

func (s Scheme) String() string { return s.URLScheme() }

// MaxField is the ceiling on any length-prefixed field, in bytes.
//
// It exists to bound an allocation made from a number an attacker chose, and it is far above any
// legitimate host: a DNS name cannot exceed 253 bytes, and this leaves room for whatever a later
// version length-prefixes without inviting a 64 KiB allocation per malformed scan.
const MaxField = 4096

// The fixed sizes of the fields that are not length-prefixed. Named so the arithmetic below reads
// as the wire layout rather than as a series of magic numbers.
const (
	fingerprintLen = 32 // SHA-256 of the bridge certificate's DER
	tokenLen       = 32 // the one-time enrolment secret
	expiryLen      = 4  // Unix seconds, uint32
	// version + host length + port, the parts that surround the host, in version 1.
	fixedLenV1 = 1 + 2 + 2 + fingerprintLen + tokenLen + expiryLen
	// Version 2 adds one byte, the scheme, immediately after the version.
	//
	// After the version and before everything length-prefixed, deliberately: it is fixed-width and
	// readable the moment the version is known, so a decoder never has to trust a length field to
	// find it.
	schemeLen  = 1
	fixedLenV2 = fixedLenV1 + schemeLen
)

// Payload is what one QR code says.
//
// It is deliberately comparable with ==, which is what lets a round-trip test assert on the whole
// value instead of field by field, and what will catch a field added to the struct but forgotten in
// the encoder.
//
// # The precondition on that ==, which is not obvious and has already caught one caller
//
// Decode always returns an Expiry of exactly time.Unix(n, 0).UTC(). A payload built any other way is
// NOT == to what a round trip gives back, even though it encodes to identical bytes, and time.Time
// is exactly the type where that goes unnoticed:
//
//   - time.Now() carries a monotonic clock reading. == compares it; the decoded value has none.
//   - time.Now() carries sub-second precision. The wire carries whole seconds, so the fraction is
//     gone on the way back.
//   - time.Unix(n, 0) without .UTC() carries the local location. == compares the location pointer.
//
// Encode truncates and normalises what it writes, so none of this affects the BYTES - only equality
// of the struct. Canonical is the one line that makes a payload compare equal to its own round trip,
// and a test asserting p == round-trip(p) on a time.Now()-derived payload should call it.
type Payload struct {
	// Host is where the phone dials: a DNS name or a literal IP address, as text. It is the owner's
	// own address and never belongs in this repository - see scripts/check-no-addresses.sh.
	Host string
	// Port is the port the phone dials. Whose port it is depends on [Payload.Scheme]: the bridge's
	// own, or that of whatever publishes the Mac.
	Port int
	// Scheme is how the outer hop is opened. See [Scheme]: it decides reachability, never trust.
	//
	// The zero value is not a scheme. [Encode] refuses it rather than defaulting, because a default
	// here is exactly the assumption version 1 made silently, and the failure it produces is a phone
	// that cannot pair for a reason nothing reports.
	Scheme Scheme
	// Fingerprint is the SHA-256 of the bridge certificate's DER, not the certificate.
	Fingerprint [32]byte
	// Token is the one-time enrolment secret. It authorises exactly one pairing and is worthless
	// afterwards, which is why it can be shown on a screen at all.
	Token [32]byte
	// Expiry is when this offer stops being accepted.
	//
	// Carried as Unix SECONDS in a uint32, so the representable range ends on 2106-02-07 and begins
	// at the epoch. That is not a bug waiting to happen the way a 32-bit signed time is - there is no
	// 2038 cliff here - but it is a real ceiling, and Encode refuses a time outside it rather than
	// silently wrapping one. Sub-second precision is not carried; a pairing window is measured in
	// minutes.
	Expiry time.Time
}

// Canonical returns p with its Expiry in the form Decode produces: truncated to the second, in UTC,
// with no monotonic clock reading.
//
// It changes nothing about the encoded bytes - Encode already truncates. What it changes is whether
// the value compares == to its own round trip, which is what a caller holding a time.Now()-derived
// payload actually wants:
//
//	p := enroll.Payload{Host: host, Port: port, Expiry: time.Now().Add(5 * time.Minute)}.Canonical()
func (p Payload) Canonical() Payload {
	p.Expiry = p.Expiry.Truncate(time.Second).UTC()
	return p
}

// DialAddress is Host and Port as a string something can connect to.
//
// # Why this is a method here and not two lines at the call site
//
// **An IPv6 literal has to be bracketed before a port can be appended to it, and the payload does not
// carry the brackets.** Host is a bare string - `2001:db8::1`, not `[2001:db8::1]` - because the
// field is one host and adding brackets to the BYTES would put a display-versus-dial ambiguity into
// the wire format: two spellings of one host, and every implementation guessing which one it holds.
//
// The consequence is that `host + ":" + port` is correct for a DNS name, correct for IPv4, and
// silently wrong for every IPv6 address - `2001:db8::1:8443` is not an address, and what happens next
// is a phone that will not pair for a reason nobody can see from the QR code. It is the obvious two
// lines, it is what a dialler written from the field list does, and nothing in the format says
// otherwise.
//
// So the rule exists once, here, as net.JoinHostPort - which brackets exactly when it has to and
// leaves a name or an IPv4 address alone. **And it is pinned across languages by the `dial_address`
// field on every accept vector**, which is the only mechanism in this repository that reaches the
// Kotlin side: a Go helper the Android app cannot import proves nothing about the Android app. See
// wire/README.md.
func (p Payload) DialAddress() string {
	return net.JoinHostPort(p.Host, strconv.Itoa(p.Port))
}

// DialURL is the whole address the phone opens: scheme, host, port and the path the front door
// ignores.
//
// **Derived here and pinned by `dial_url` on every accept vector, for the same reason as
// [Payload.DialAddress].** The two mistakes it forecloses compose: a dialler that builds its own URL
// has to remember the bracketing AND the scheme mapping, and getting either wrong produces a phone
// that will not pair with nothing on the wire to say why. One string, derived once, checked by both
// languages against the same file.
//
// The path is "/" and the bridge does not look at it - `frontdoor.readUpgrade` deliberately checks
// the method and the version and not the path, so a caller cannot learn a correct path from a
// different answer. It is here because a URL needs one and two implementations should not choose
// differently.
func (p Payload) DialURL() string {
	return p.Scheme.URLScheme() + "://" + p.DialAddress() + "/"
}

// Encode serialises a payload.
//
// The field order is fixed and is asserted by a test, because it is a contract with an
// implementation in another language that cannot be changed in the same commit:
//
//	uint8   version = 2
//	uint8   scheme          <- added in version 2
//	uint16  host length, in BYTES
//	[]byte  host, UTF-8
//	uint16  port
//	[32]byte fingerprint
//	[32]byte token
//	uint32  expiry, Unix seconds
//
// Version 1 is the same without the scheme byte. It is decoded and never emitted; see [VersionLegacy].
//
// All integers big-endian. Big-endian rather than little because it is what every wire format the
// two sides already speak uses, and because Java's DataInput reads nothing else.
func Encode(p Payload) ([]byte, error) {
	if !p.Scheme.Known() {
		// Refused rather than defaulted. See the field comment: a default here is version 1's
		// mistake with a nicer name.
		return nil, fmt.Errorf("enroll: scheme %d is not one this build can encode", p.Scheme)
	}
	if p.Host == "" {
		return nil, errors.New("enroll: host is empty")
	}
	if len(p.Host) > MaxField {
		return nil, fmt.Errorf("enroll: host is %d bytes, over the %d-byte ceiling", len(p.Host), MaxField)
	}
	if !utf8.ValidString(p.Host) {
		// The other side decodes this field as UTF-8. Something that is not UTF-8 would arrive there
		// as replacement characters, i.e. as a different host, so it is refused here instead.
		return nil, errors.New("enroll: host is not valid UTF-8")
	}
	if p.Port < 0 || p.Port > 0xffff {
		return nil, fmt.Errorf("enroll: port %d is outside a uint16", p.Port)
	}
	// Truncated here rather than demanded of the caller. The format has one-second granularity, and a
	// caller writing time.Now().Add(5*time.Minute) - which is every caller minting a pairing window -
	// should not have to know that. Truncate also drops the monotonic reading, which is invisible on
	// the wire and visible to ==; see Canonical.
	secs := p.Expiry.Truncate(time.Second).Unix()
	if secs < 0 || secs > 0xffffffff {
		return nil, fmt.Errorf("enroll: expiry %s is outside the representable range (1970-01-01 to 2106-02-07)", p.Expiry.UTC().Format(time.RFC3339))
	}

	b := make([]byte, 0, fixedLenV2+len(p.Host))
	b = append(b, Version)
	b = append(b, byte(p.Scheme))
	b = binary.BigEndian.AppendUint16(b, uint16(len(p.Host)))
	b = append(b, p.Host...)
	b = binary.BigEndian.AppendUint16(b, uint16(p.Port))
	b = append(b, p.Fingerprint[:]...)
	b = append(b, p.Token[:]...)
	b = binary.BigEndian.AppendUint32(b, uint32(secs))
	return b, nil
}

// Decode parses a payload, and refuses everything it is not certain of.
//
// Every refusal below is a decision not to guess: a version it does not know rather than a field
// layout it assumes, a length field checked against what is actually in hand rather than used to
// size a read, and a trailing byte treated as a different message rather than as slack. The input
// is whatever was in front of the camera.
func Decode(b []byte) (Payload, error) {
	// Version first, before any length is read, so a payload from a future version is reported as
	// such rather than as a malformed one.
	if len(b) < 1 {
		return Payload{}, errors.New("enroll: empty payload")
	}

	// **The two versions are parsed by the same code with one offset difference, and NOT by treating
	// them as interchangeable.** A decoder that read a version 2 payload with version 1's offsets
	// would take the scheme byte as the high half of the host length and produce a length in the tens
	// of thousands - which the ceiling happens to catch today, and which is luck rather than a check.
	// So the version chooses the layout explicitly and every field is read at the offset that version
	// puts it at. `v2-payload-labelled-v1` is the reject vector that holds this.
	var scheme Scheme
	var fixedLen, at int
	switch b[0] {
	case VersionLegacy:
		// Version 1 said nothing about how to reach the address, and every implementation of it
		// opened a plain connection. That is what it meant, so that is what it decodes to.
		scheme, fixedLen, at = SchemePlain, fixedLenV1, 1
	case Version:
		fixedLen, at = fixedLenV2, 2
	default:
		return Payload{}, fmt.Errorf("enroll: unsupported payload version %d, this build speaks %d and reads %d", b[0], Version, VersionLegacy)
	}

	if len(b) < fixedLen {
		return Payload{}, fmt.Errorf("enroll: payload is %d bytes, shorter than the %d-byte minimum", len(b), fixedLen)
	}

	if b[0] == Version {
		scheme = Scheme(b[1])
		if !scheme.Known() {
			// A scheme this build does not know is not a malformed payload and must not be reported
			// as one: it is a Mac that can arrange something this phone cannot. Refused rather than
			// treated as either of the two known ones, because guessing wrong here is a phone that
			// cannot reach a front door and cannot say so.
			return Payload{}, fmt.Errorf("enroll: unsupported scheme %d, this build knows %d and %d", b[1], SchemePlain, SchemeTLS)
		}
	}

	hostLen := int(binary.BigEndian.Uint16(b[at : at+2]))
	// Both halves matter. The ceiling stops a hostile length becoming an allocation; the bounds check
	// stops it becoming a read past the end. Neither implies the other: 4096 is a comfortable
	// allocation and still far past the end of a 76-byte buffer.
	if hostLen > MaxField {
		return Payload{}, fmt.Errorf("enroll: host length %d is over the %d-byte ceiling", hostLen, MaxField)
	}
	if hostLen == 0 {
		return Payload{}, errors.New("enroll: host is empty")
	}
	want := fixedLen + hostLen
	if len(b) != want {
		// One message for short and for long on purpose: the length the header describes is the only
		// length this payload may have. Trailing bytes are refused here - they are the tail of a
		// second message, or somebody probing for a parser that ignores what it does not understand.
		return Payload{}, fmt.Errorf("enroll: payload is %d bytes, but its header describes %d", len(b), want)
	}

	host := string(b[at+2 : at+2+hostLen])
	if !utf8.ValidString(host) {
		return Payload{}, errors.New("enroll: host is not valid UTF-8")
	}

	rest := b[at+2+hostLen:]
	p := Payload{
		Host:   host,
		Port:   int(binary.BigEndian.Uint16(rest[:2])),
		Scheme: scheme,
	}
	copy(p.Fingerprint[:], rest[2:2+fingerprintLen])
	copy(p.Token[:], rest[2+fingerprintLen:2+fingerprintLen+tokenLen])
	p.Expiry = time.Unix(int64(binary.BigEndian.Uint32(rest[2+fingerprintLen+tokenLen:])), 0).UTC()
	return p, nil
}

// EncodeToText renders a payload as the text a QR code carries: standard, padded base64.
func EncodeToText(p Payload) (string, error) {
	b, err := Encode(p)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(b), nil
}

// DecodeText parses the text a QR code carried.
//
// Strict on the alphabet and on the padding, and it does not trim: what it accepts has to be what the
// Android decoder accepts, or the two sides disagree about which codes are valid. Two divergences
// from java.util.Base64.getDecoder() are known and neither is left to chance: Java accepts an
// unpadded final unit where this refuses one, so the Kotlin side checks the length itself against the
// `unpadded` reject vector; and this skips \r and \n where Java refuses them, which nothing here ever
// emits and no vector can pin.
func DecodeText(s string) (Payload, error) {
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		return Payload{}, fmt.Errorf("enroll: payload is not standard padded base64: %w", err)
	}
	return Decode(b)
}
