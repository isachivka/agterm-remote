//go:build vectors

// This file is behind a build tag on purpose, and the tag is the whole point of it.
//
// The two files in wire/ are a CONTRACT between two implementations in two languages, and a
// generator that ran with the ordinary test suite would quietly rewrite that contract to agree with
// whatever the encoder currently does - which is exactly the drift the vectors exist to catch.
// Behind a tag, regenerating is a thing somebody has to decide to do:
//
//	cd bridge && go test ./internal/enroll/ -tags vectors -run 'TestWrite.*Vectors'
//
// Do that only when the wire format is being changed on purpose, and expect the Kotlin side to fail
// until it is changed to match. Never do it to make a red test go green.
package enroll_test

import (
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/enroll"
)

const (
	vectorsPath       = "../../../wire/enroll-payload-vectors.json"
	rejectVectorsPath = "../../../wire/enroll-payload-reject-vectors.json"
)

type generated struct {
	Name        string `json:"name"`
	Note        string `json:"note"`
	Host        string `json:"host"`
	Port        int    `json:"port"`
	Fingerprint string `json:"fingerprint_hex"`
	Token       string `json:"token_hex"`
	Expiry      int64  `json:"expiry_unix"`
	Text        string `json:"text"`
	// DialAddress is DERIVED, not an input: it is host and port joined the way something that is
	// about to connect must join them, and it comes from [enroll.Payload.DialAddress] so the
	// bracketing rule has one implementation.
	//
	// **It is here because a Go helper cannot reach Kotlin.** The bug it closes is one layer past
	// decoding: both sides already agree that the host field of an IPv6 vector is `2001:db8::1`, and
	// a dialler that writes `host + ":" + port` from that produces something that is not an address.
	// The vectors are the only mechanism in this repository that both languages read, so this is
	// where the rule can be enforced rather than merely written down.
	DialAddress string `json:"dial_address"`
}

func TestWriteVectors(t *testing.T) {
	// A DNS name may be 253 bytes. Three 63-byte labels and a 61-byte one, with their three dots,
	// come to exactly that - the longest host this format will ever legitimately carry.
	longHost := strings.Join([]string{
		strings.Repeat("a", 63),
		strings.Repeat("b", 63),
		strings.Repeat("c", 63),
		strings.Repeat("d", 61),
	}, ".")
	if len(longHost) != 253 {
		t.Fatalf("the long host is %d bytes, not 253", len(longHost))
	}

	cases := []struct {
		name, note, host string
		port             int
		fp, tok          [32]byte
		expiry           int64
	}{
		{
			name:   "short-host",
			note:   "The ordinary case: a short name, the usual port, a mid-range expiry.",
			host:   "example.test",
			port:   8443,
			fp:     counting(0, 1),
			tok:    counting(0xff, -1),
			expiry: 1_800_000_000,
		},
		{
			name:   "host-at-253-bytes",
			note:   "The longest legitimate DNS name: four labels, 253 bytes. Pins the uint16 length prefix against an implementation that assumed one byte would do.",
			host:   longHost,
			port:   443,
			fp:     counting(0x40, 1),
			tok:    counting(0x80, 1),
			expiry: 2_000_000_000,
		},
		{
			name:   "non-ascii-host",
			note:   "A host with a multi-byte character. The length prefix counts BYTES, not characters: this host is 20 characters and 21 bytes.",
			host:   "münchen.example.test",
			port:   9443,
			fp:     counting(0x11, 3),
			tok:    counting(0x22, 5),
			expiry: 1_900_000_000,
		},
		{
			name:   "ipv6-literal-host",
			note:   "An IPv6 literal, unbracketed in the host field as every host is. The payload carries one host and no brackets, so a dialler that appends \":\" and the port produces \"2001:db8::1:8443\", which is not an address - see dial_address, which is what this vector exists to pin.",
			host:   "2001:db8::1",
			port:   8443,
			fp:     counting(0x55, 2),
			tok:    counting(0xaa, -2),
			expiry: 1_850_000_000,
		},
		{
			name:   "boundary-values",
			note:   "Every field at its ceiling: the highest port a uint16 holds, and the last second a uint32 expiry can name (2106-02-07T06:28:15Z).",
			host:   "a",
			port:   65535,
			fp:     filled(0xff),
			tok:    filled(0x00),
			expiry: 4_294_967_295,
		},
	}

	out := make([]generated, 0, len(cases))
	for _, c := range cases {
		text, err := enroll.EncodeToText(enroll.Payload{
			Host: c.host, Port: c.port, Fingerprint: c.fp, Token: c.tok,
			Expiry: time.Unix(c.expiry, 0).UTC(),
		})
		if err != nil {
			t.Fatalf("%s: %v", c.name, err)
		}
		out = append(out, generated{
			Name:        c.name,
			Note:        c.note,
			Host:        c.host,
			Port:        c.port,
			Fingerprint: hex.EncodeToString(c.fp[:]),
			Token:       hex.EncodeToString(c.tok[:]),
			Expiry:      c.expiry,
			Text:        text,
			// Derived from the helper rather than formatted here, so the file cannot record a
			// bracketing rule the code does not implement.
			DialAddress: enroll.Payload{Host: c.host, Port: c.port}.DialAddress(),
		})
	}

	buf, err := json.MarshalIndent(out, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(vectorsPath, append(buf, '\n'), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Logf("wrote %d vectors to %s", len(out), vectorsPath)
}

type generatedReject struct {
	Name    string `json:"name"`
	Refusal string `json:"refusal"`
	Note    string `json:"note"`
	Text    string `json:"text"`
}

// TestWriteRejectVectors emits the other half of the contract: text that must be REFUSED.
//
// The accept vectors pin the encoder. Nothing in them says what a decoder must not do, and the
// decoder is the half facing the camera - it parses whatever a stranger holds in front of a lens,
// before any authentication exists. A Kotlin decoder that read past a length field, or interpreted
// a version it did not know, would pass every accept vector and ship.
func TestWriteRejectVectors(t *testing.T) {
	valid, err := enroll.Encode(enroll.Payload{
		Host: "example.test", Port: 8443,
		Fingerprint: counting(0, 1), Token: counting(0xff, -1),
		Expiry: time.Unix(1_800_000_000, 0).UTC(),
	})
	if err != nil {
		t.Fatal(err)
	}
	// The base64 of this one differs between the standard and the URL-safe alphabets, which is what
	// makes the alphabet vectors below mean anything.
	if !strings.ContainsAny(base64.StdEncoding.EncodeToString(valid), "+/") {
		t.Fatal("the base payload no longer distinguishes the two base64 alphabets")
	}

	// mutate returns a copy of the valid payload with f applied, so no case can corrupt another's
	// input.
	mutate := func(f func(b []byte) []byte) []byte {
		b := make([]byte, len(valid))
		copy(b, valid)
		return f(b)
	}

	cases := []struct {
		name, refusal, note string
		text                string
	}{
		{
			name: "wrong-version", refusal: "unsupported-version",
			note: "Version byte 2 in a build that speaks 1. Must be reported as a version this build does not know, never parsed hopefully - the remaining bytes mean nothing here.",
			text: vectorB64(mutate(func(b []byte) []byte { b[0] = 2; return b })),
		},
		{
			name: "version-zero", refusal: "unsupported-version",
			note: "Version 0 - the shape a zero-filled or truncated-then-padded buffer takes.",
			text: vectorB64(mutate(func(b []byte) []byte { b[0] = 0; return b })),
		},
		{
			name: "trailing-byte", refusal: "length-mismatch",
			note: "A valid payload with one byte appended. The length the header describes is the only length the payload may have; a trailing byte is the tail of a second message, or somebody probing for a parser that ignores what it does not understand.",
			text: vectorB64(mutate(func(b []byte) []byte { return append(b, 0) })),
		},
		{
			name: "host-length-overruns-buffer", refusal: "length-mismatch",
			note: "Host length 300 in an 85-byte buffer. Under MaxField, so the ceiling does not catch it: this is the case a decoder that reads hostLen bytes without checking what it holds gets wrong.",
			text: vectorB64(mutate(func(b []byte) []byte { binary.BigEndian.PutUint16(b[1:3], 300); return b })),
		},
		{
			name: "host-length-65535", refusal: "host-length-over-ceiling",
			note: "The largest number the uint16 length field can hold. Must be refused before anything is allocated for it - a length field an attacker chose must never size an allocation.",
			text: vectorB64(mutate(func(b []byte) []byte { binary.BigEndian.PutUint16(b[1:3], 0xffff); return b })),
		},
		{
			name: "host-length-zero", refusal: "empty-host",
			note: "Host length 0, and a buffer exactly that long, so only the empty host is wrong. A payload naming no host is nothing the phone can act on.",
			text: vectorB64(func() []byte {
				b := append([]byte{}, valid[:3]...)
				binary.BigEndian.PutUint16(b[1:3], 0)
				return append(b, valid[3+len("example.test"):]...)
			}()),
		},
		{
			name: "truncated-in-the-middle", refusal: "length-mismatch",
			note: "A valid payload with its last four bytes - the expiry - cut off. Long enough to pass a minimum-length check, short of what its own header describes.",
			text: vectorB64(valid[:len(valid)-4]),
		},
		{
			name: "truncated-to-a-stub", refusal: "too-short",
			note: "Twenty bytes: a correct version byte and a plausible host length, and nothing else. Refused on the minimum length before any field is read.",
			text: vectorB64(valid[:20]),
		},
		{
			name: "url-safe-alphabet", refusal: "not-standard-base64",
			note: "The valid payload rendered in the URL-safe base64 alphabet. java.util.Base64.getDecoder() refuses this and so must every other decoder here: two sides that disagree about the alphabet disagree about which codes exist.",
			text: base64.URLEncoding.EncodeToString(valid),
		},
		{
			name: "unpadded", refusal: "not-standard-base64",
			note: "The valid payload rendered without '=' padding. java.util.Base64.getDecoder() refuses this too.",
			text: base64.RawStdEncoding.EncodeToString(valid),
		},
		{
			name: "host-not-utf8", refusal: "host-not-utf8",
			note: "The host field filled with 0xff bytes, which are not UTF-8 in any position. A decoder that lets them through arrives at a host of replacement characters - a different host from the one the owner is looking at.",
			text: vectorB64(mutate(func(b []byte) []byte {
				for i := 3; i < 3+len("example.test"); i++ {
					b[i] = 0xff
				}
				return b
			})),
		},
		{
			name: "empty-text", refusal: "empty-payload",
			note: "The empty string. Valid base64 of zero bytes, so the refusal has to come from the payload parser rather than from the alphabet.",
			text: "",
		},
	}

	out := make([]generatedReject, 0, len(cases))
	for _, c := range cases {
		// Proof, at generation time, that each case is actually refused - a reject vector that its own
		// generator's encoder accepts would pin the opposite of what it claims.
		if _, err := enroll.DecodeText(c.text); err == nil {
			t.Fatalf("%s: this vector must be refused, and is not", c.name)
		}
		out = append(out, generatedReject{Name: c.name, Refusal: c.refusal, Note: c.note, Text: c.text})
	}

	buf, err := json.MarshalIndent(out, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(rejectVectorsPath, append(buf, '\n'), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Logf("wrote %d reject vectors to %s", len(out), rejectVectorsPath)
}

// vectorB64 is this file's own, and it is not called b64 because handler_test.go declares that name
// in the same package. It collided the moment handler_test.go landed, and the collision is invisible
// from an ordinary run: this file is behind the `vectors` tag, so `go test ./...` never compiles the
// two together and the documented regeneration command was the only thing that failed - which is a
// command nobody runs unless the wire format is being changed on purpose. Found while adding the
// IPv6 vector, i.e. the first time it was run since.
func vectorB64(b []byte) string { return base64.StdEncoding.EncodeToString(b) }

// counting fills 32 bytes with an arithmetic ramp, so that a byte swapped anywhere in the
// fingerprint or the token changes the encoded text. Nothing here is a secret or is derived from
// one: these are fixtures, and the real values are 32 bytes from crypto/rand.
func counting(start byte, step int) [32]byte {
	var b [32]byte
	for i := range b {
		b[i] = byte(int(start) + i*step)
	}
	return b
}

func filled(v byte) [32]byte {
	var b [32]byte
	for i := range b {
		b[i] = v
	}
	return b
}
