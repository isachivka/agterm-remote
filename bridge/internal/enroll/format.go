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
// STANDARD alphabet, and PADDED, because the Android side decodes with java.util.Base64.getDecoder(),
// which refuses the URL-safe alphabet and refuses missing padding. Both refusals are invisible from
// inside Go, where a URL-safe encoder and a standard one round-trip equally well, so the property is
// pinned by a test rather than left to be remembered.
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
	"time"
	"unicode/utf8"
)

// Version is the first byte of every payload.
//
// A phone that reads a version it does not know must say so to its owner - "this Mac is newer than
// this app" - rather than parse the remainder hopefully. That is why the version is FIRST: it is
// readable from one byte, before any length field has been trusted.
const Version = 1

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
	// version + host length + port, the parts that surround the host.
	fixedLen = 1 + 2 + 2 + fingerprintLen + tokenLen + expiryLen
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
	// Port is the bridge's TLS port.
	Port int
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

// Encode serialises a payload.
//
// The field order is fixed and is asserted by a test, because it is a contract with an
// implementation in another language that cannot be changed in the same commit:
//
//	uint8   version
//	uint16  host length, in BYTES
//	[]byte  host, UTF-8
//	uint16  port
//	[32]byte fingerprint
//	[32]byte token
//	uint32  expiry, Unix seconds
//
// All integers big-endian. Big-endian rather than little because it is what every wire format the
// two sides already speak uses, and because Java's DataInput reads nothing else.
func Encode(p Payload) ([]byte, error) {
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

	b := make([]byte, 0, fixedLen+len(p.Host))
	b = append(b, Version)
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
	if b[0] != Version {
		return Payload{}, fmt.Errorf("enroll: unsupported payload version %d, this build speaks %d", b[0], Version)
	}
	if len(b) < fixedLen {
		return Payload{}, fmt.Errorf("enroll: payload is %d bytes, shorter than the %d-byte minimum", len(b), fixedLen)
	}

	hostLen := int(binary.BigEndian.Uint16(b[1:3]))
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

	host := string(b[3 : 3+hostLen])
	if !utf8.ValidString(host) {
		return Payload{}, errors.New("enroll: host is not valid UTF-8")
	}

	rest := b[3+hostLen:]
	p := Payload{
		Host: host,
		Port: int(binary.BigEndian.Uint16(rest[:2])),
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
// Strict on the alphabet and on the padding, and it does not trim: what it accepts has to be what
// java.util.Base64.getDecoder() accepts, or the two sides disagree about which codes are valid. The
// one divergence left is that Go's decoder skips \r and \n where Java's refuses them, and nothing
// here ever emits either.
func DecodeText(s string) (Payload, error) {
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		return Payload{}, fmt.Errorf("enroll: payload is not standard padded base64: %w", err)
	}
	return Decode(b)
}
