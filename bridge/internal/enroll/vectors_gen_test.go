//go:build vectors

// This file is behind a build tag on purpose, and the tag is the whole point of it.
//
// wire/enroll-payload-vectors.json is a CONTRACT between two implementations in two languages, and
// a generator that ran with the ordinary test suite would quietly rewrite that contract to agree
// with whatever the encoder currently does - which is exactly the drift the vectors exist to catch.
// Behind a tag, regenerating is a thing somebody has to decide to do:
//
//	cd bridge && go test ./internal/enroll/ -tags vectors -run TestWriteVectors
//
// Do that only when the wire format is being changed on purpose, and expect the Kotlin side to fail
// until it is changed to match. Never do it to make a red test go green.
package enroll_test

import (
	"encoding/hex"
	"encoding/json"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/enroll"
)

const vectorsPath = "../../../wire/enroll-payload-vectors.json"

type generated struct {
	Name        string `json:"name"`
	Note        string `json:"note"`
	Host        string `json:"host"`
	Port        int    `json:"port"`
	Fingerprint string `json:"fingerprint_hex"`
	Token       string `json:"token_hex"`
	Expiry      int64  `json:"expiry_unix"`
	Text        string `json:"text"`
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
