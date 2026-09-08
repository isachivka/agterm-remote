package enroll_test

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"os"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/enroll"
)

func TestRoundTrip(t *testing.T) {
	want := enroll.Payload{
		Host:        "example.test",
		Port:        8443,
		Fingerprint: [32]byte{1, 2, 3},
		Token:       [32]byte{4, 5, 6},
		Expiry:      time.Unix(1_800_000_000, 0).UTC(),
	}
	text, err := enroll.EncodeToText(want)
	if err != nil {
		t.Fatal(err)
	}
	got, err := enroll.DecodeText(text)
	if err != nil {
		t.Fatal(err)
	}
	if got != want {
		t.Fatalf("round trip changed the payload:\n want %+v\n got  %+v", want, got)
	}
}

func TestFieldOrderIsFixed(t *testing.T) {
	p := enroll.Payload{Host: "ab", Port: 0x1234, Expiry: time.Unix(0x01020304, 0)}
	b, err := enroll.Encode(p)
	if err != nil {
		t.Fatal(err)
	}
	// version, host length, host, port, fingerprint, token, expiry
	if b[0] != enroll.Version {
		t.Fatalf("version must be first, got %d", b[0])
	}
	if b[1] != 0x00 || b[2] != 0x02 {
		t.Fatalf("host length must be big-endian uint16, got %x", b[1:3])
	}
	if string(b[3:5]) != "ab" {
		t.Fatalf("host must follow its length, got %q", b[3:5])
	}
	if b[5] != 0x12 || b[6] != 0x34 {
		t.Fatalf("port must be big-endian uint16, got %x", b[5:7])
	}
	if len(b) != 3+2+2+32+32+4 {
		t.Fatalf("unexpected length %d", len(b))
	}
}

func TestRejectsAWrongVersion(t *testing.T) {
	b, _ := enroll.Encode(enroll.Payload{Host: "a", Port: 1, Expiry: time.Unix(1, 0)})
	b[0] = 99
	if _, err := enroll.Decode(b); err == nil {
		t.Fatal("a payload from a newer version must be refused, not misread")
	}
}

func TestRejectsTrailingBytes(t *testing.T) {
	b, _ := enroll.Encode(enroll.Payload{Host: "a", Port: 1, Expiry: time.Unix(1, 0)})
	if _, err := enroll.Decode(append(b, 0)); err == nil {
		t.Fatal("trailing bytes must be refused")
	}
}

func TestMatchesTheGoldenVectors(t *testing.T) {
	raw, err := os.ReadFile("../../../wire/enroll-payload-vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var vectors []struct {
		Name        string `json:"name"`
		Host        string `json:"host"`
		Port        int    `json:"port"`
		Fingerprint string `json:"fingerprint_hex"`
		Token       string `json:"token_hex"`
		Expiry      int64  `json:"expiry_unix"`
		Text        string `json:"text"`
	}
	if err := json.Unmarshal(raw, &vectors); err != nil {
		t.Fatal(err)
	}
	// The names, not a count. A count lets the most valuable vector be deleted without anything going
	// red - measured: with `len(vectors) >= 3`, dropping boundary-values leaves the suite green, and
	// boundary-values is the one pinning the uint32 expiry ceiling that a second implementation is
	// most likely to get wrong. Adding a vector means adding a line here, deliberately.
	names := make([]string, 0, len(vectors))
	for _, v := range vectors {
		names = append(names, v.Name)
	}
	wantNames := []string{"short-host", "host-at-253-bytes", "non-ascii-host", "boundary-values"}
	if !slices.Equal(names, wantNames) {
		t.Fatalf("the vector set changed\n want %v\n got  %v\nIf a vector was added on purpose, add its name here too; if one went missing, put it back.", wantNames, names)
	}
	for _, v := range vectors {
		var fp, tok [32]byte
		mustHex(t, v.Fingerprint, fp[:])
		mustHex(t, v.Token, tok[:])
		got, err := enroll.EncodeToText(enroll.Payload{
			Host: v.Host, Port: v.Port, Fingerprint: fp, Token: tok,
			Expiry: time.Unix(v.Expiry, 0).UTC(),
		})
		if err != nil {
			t.Fatalf("%s: %v", v.Name, err)
		}
		if got != v.Text {
			t.Fatalf("%s: encoder drifted from the vector\n want %s\n got  %s", v.Name, v.Text, got)
		}
	}
}

// The vectors are also the only place the DECODER is checked against bytes it did not just produce
// itself. A round trip cannot tell a consistently wrong encoder from a correct one; this can.
func TestDecodesTheGoldenVectors(t *testing.T) {
	for _, v := range goldenVectors(t) {
		var fp, tok [32]byte
		mustHex(t, v.Fingerprint, fp[:])
		mustHex(t, v.Token, tok[:])
		want := enroll.Payload{
			Host: v.Host, Port: v.Port, Fingerprint: fp, Token: tok,
			Expiry: time.Unix(v.Expiry, 0).UTC(),
		}
		got, err := enroll.DecodeText(v.Text)
		if err != nil {
			t.Fatalf("%s: %v", v.Name, err)
		}
		if got != want {
			t.Fatalf("%s: decoded payload differs from the vector\n want %+v\n got  %+v", v.Name, want, got)
		}
	}
}

// The other half of the contract, and the half that faces the camera.
//
// The accept vectors pin the encoder: given these inputs, produce this text. Nothing in them says
// what a decoder must REFUSE, so a Kotlin decoder that interpreted a version it did not know, or read
// past a length field, would pass every one of them and ship. These vectors say the other thing, and
// they are exercised here as well as in Task 21 so the file is live from the day it lands rather than
// inert until somebody remembers it.
//
// The `refusal` field names the KIND of refusal, not a message. Each side maps it onto whatever it
// raises - Go checks the error text below, Kotlin will map it to its own exception types. What both
// sides must agree on is that the text is refused at all.
func TestRefusesTheGoldenRejectVectors(t *testing.T) {
	raw, err := os.ReadFile("../../../wire/enroll-payload-reject-vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var vectors []struct {
		Name    string `json:"name"`
		Refusal string `json:"refusal"`
		Text    string `json:"text"`
	}
	if err := json.Unmarshal(raw, &vectors); err != nil {
		t.Fatal(err)
	}

	// Every refusal this repository knows about, and the Go message that stands for it. A vector
	// naming a refusal that is not here fails: a typo would otherwise be a vector asserting nothing.
	refusals := map[string]string{
		"not-standard-base64":      "not standard padded base64",
		"empty-payload":            "empty payload",
		"unsupported-version":      "unsupported payload version",
		"too-short":                "shorter than the",
		"host-length-over-ceiling": "over the 4096-byte ceiling",
		"empty-host":               "host is empty",
		"length-mismatch":          "but its header describes",
		"host-not-utf8":            "not valid UTF-8",
	}

	names := make([]string, 0, len(vectors))
	for _, v := range vectors {
		names = append(names, v.Name)
	}
	wantNames := []string{
		"wrong-version", "version-zero", "trailing-byte", "host-length-overruns-buffer",
		"host-length-65535", "host-length-zero", "truncated-in-the-middle", "truncated-to-a-stub",
		"url-safe-alphabet", "unpadded", "host-not-utf8", "empty-text",
	}
	if !slices.Equal(names, wantNames) {
		t.Fatalf("the reject vector set changed\n want %v\n got  %v", wantNames, names)
	}

	for _, v := range vectors {
		t.Run(v.Name, func(t *testing.T) {
			want, known := refusals[v.Refusal]
			if !known {
				t.Fatalf("refusal %q is not one this package knows about", v.Refusal)
			}
			got, err := enroll.DecodeText(v.Text)
			if err == nil {
				t.Fatalf("this text must be refused (%s), and was decoded as %+v", v.Refusal, got)
			}
			if got != (enroll.Payload{}) {
				t.Fatalf("a refused payload must come back zero, got %+v", got)
			}
			if !strings.Contains(err.Error(), want) {
				t.Fatalf("refused for the wrong reason\n want a %s error containing %q\n got  %v", v.Refusal, want, err)
			}
		})
	}
}

// The Android side decodes with java.util.Base64.getDecoder(), which refuses the URL-safe alphabet
// and refuses missing padding. Neither refusal shows up in a Go round trip, so it is asserted here:
// the text this produces must be the standard padded alphabet and nothing else.
func TestTextIsStandardPaddedBase64(t *testing.T) {
	// '/' and '+' both appear in this one, which is exactly what the URL-safe alphabet would spell
	// as '_' and '-'. Found by search rather than by luck - see the token below.
	p := enroll.Payload{
		Host:        "example.test",
		Port:        8443,
		Fingerprint: [32]byte{0xff, 0xef, 0xbf},
		Token:       [32]byte{0xfb, 0xef, 0xbe},
		Expiry:      time.Unix(1_800_000_000, 0).UTC(),
	}
	text, err := enroll.EncodeToText(p)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.ContainsAny(text, "+/") {
		t.Fatalf("this fixture is meant to exercise the two alphabet-specific characters, got %s", text)
	}
	if strings.ContainsAny(text, "-_") {
		t.Fatalf("URL-safe alphabet: java.util.Base64.getDecoder() would refuse this, got %s", text)
	}
	if len(text)%4 != 0 {
		t.Fatalf("padding is missing: java.util.Base64.getDecoder() would refuse this, got %s", text)
	}
	urlSafe := base64.URLEncoding.EncodeToString(mustBytes(t, p))
	if urlSafe == text {
		t.Fatal("the fixture no longer distinguishes the two alphabets")
	}
	if _, err := enroll.DecodeText(urlSafe); err == nil {
		t.Fatal("the URL-safe alphabet must be refused, so that the two sides cannot disagree about it")
	}
}

// A length field is the classic way to make a parser allocate for an attacker. This one runs before
// any authentication, in front of a camera, so the ceiling is not optional.
func TestRejectsAHostLengthThatOverrunsTheBuffer(t *testing.T) {
	b, err := enroll.Encode(enroll.Payload{Host: "abc", Port: 1, Expiry: time.Unix(1, 0)})
	if err != nil {
		t.Fatal(err)
	}
	b[1], b[2] = 0xff, 0xff // 65535 bytes of host in a 76-byte buffer
	if _, err := enroll.Decode(b); err == nil {
		t.Fatal("a host length that overruns the buffer must be refused")
	}
	b[1], b[2] = 0x10, 0x01 // 4097: one over MaxField, and still past the end
	if _, err := enroll.Decode(b); err == nil {
		t.Fatal("a host length over MaxField must be refused before anything is allocated for it")
	}
}

func TestRejectsAShortBuffer(t *testing.T) {
	b, err := enroll.Encode(enroll.Payload{Host: "abc", Port: 1, Expiry: time.Unix(1, 0)})
	if err != nil {
		t.Fatal(err)
	}
	for n := 0; n < len(b); n++ {
		if _, err := enroll.Decode(b[:n]); err == nil {
			t.Fatalf("a %d-byte prefix of a %d-byte payload must be refused", n, len(b))
		}
	}
}

// Encode is the other half of the same surface: whatever it will not refuse, Decode has to cope
// with. A host that does not fit its length field, a port outside a uint16 and a time outside a
// uint32 are all refused where they are cheapest to refuse.
func TestEncodeRefusesWhatTheFormatCannotCarry(t *testing.T) {
	base := enroll.Payload{Host: "example.test", Port: 8443, Expiry: time.Unix(1, 0)}
	tests := []struct {
		name  string
		alter func(*enroll.Payload)
	}{
		{"empty host", func(p *enroll.Payload) { p.Host = "" }},
		{"host over MaxField", func(p *enroll.Payload) { p.Host = strings.Repeat("a", enroll.MaxField+1) }},
		{"negative port", func(p *enroll.Payload) { p.Port = -1 }},
		{"port over a uint16", func(p *enroll.Payload) { p.Port = 65536 }},
		{"expiry before the epoch", func(p *enroll.Payload) { p.Expiry = time.Unix(-1, 0) }},
		{"expiry past 2106", func(p *enroll.Payload) { p.Expiry = time.Unix(1<<32, 0) }},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			p := base
			tc.alter(&p)
			if _, err := enroll.Encode(p); err == nil {
				t.Fatalf("%s must be refused", tc.name)
			}
		})
	}
}

// The host length counts BYTES, not runes. The Kotlin side reads the same field into a ByteArray and
// then decodes UTF-8, so a decoder that counted characters would part company with it on exactly the
// hosts an owner outside the ASCII world would have.
func TestHostLengthCountsBytes(t *testing.T) {
	const host = "münchen.example.test" // 20 runes, 21 bytes
	b, err := enroll.Encode(enroll.Payload{Host: host, Port: 1, Expiry: time.Unix(1, 0)})
	if err != nil {
		t.Fatal(err)
	}
	if b[1] != 0x00 || b[2] != 21 {
		t.Fatalf("host length must be the byte count, got %x", b[1:3])
	}
	got, err := enroll.Decode(b)
	if err != nil {
		t.Fatal(err)
	}
	if got.Host != host {
		t.Fatalf("host survived encoding as %q", got.Host)
	}
}

// The way every real caller builds an expiry - Task 10 mints its pairing window from time.Now() -
// and the one shape in which Payload's == quietly stops holding.
func TestANowBasedPayloadRoundTripsToEquality(t *testing.T) {
	raw := enroll.Payload{
		Host:        "example.test",
		Port:        8443,
		Fingerprint: [32]byte{1, 2, 3},
		Token:       [32]byte{4, 5, 6},
		Expiry:      time.Now().Add(5 * time.Minute),
	}
	text, err := enroll.EncodeToText(raw)
	if err != nil {
		t.Fatal(err)
	}
	got, err := enroll.DecodeText(text)
	if err != nil {
		t.Fatal(err)
	}
	if want := raw.Canonical(); got != want {
		t.Fatalf("a time.Now()-derived payload did not round trip:\n want %+v\n got  %+v", want, got)
	}
	// The precondition itself, pinned rather than described: the un-canonicalised value carries a
	// monotonic reading and a sub-second fraction, so == fails even though the BYTES are identical.
	// A future change that made Payload compare equal here would make Canonical pointless, and this
	// is where somebody would find that out.
	if got == raw {
		t.Fatal("Canonical has become unnecessary - say so on Payload and delete it")
	}
	// Encode truncates, so the difference above is about the struct and never about the wire.
	again, err := enroll.EncodeToText(raw.Canonical())
	if err != nil {
		t.Fatal(err)
	}
	if again != text {
		t.Fatalf("Canonical changed the encoded bytes:\n before %s\n after  %s", text, again)
	}
	// The other two shapes the doc comment names: a local-zone time and a sub-second one.
	local := raw
	local.Expiry = time.Unix(raw.Expiry.Unix(), 0)
	if enc, err := enroll.EncodeToText(local); err != nil || enc != text {
		t.Fatalf("the local-zone form must encode identically: %s, %v", enc, err)
	}
}

func TestDecodeTextRefusesTextThatIsNotBase64(t *testing.T) {
	for _, s := range []string{"", "not base64 at all", "AAAA"} {
		if _, err := enroll.DecodeText(s); err == nil {
			t.Fatalf("%q must be refused", s)
		}
	}
}

type vector struct {
	Name        string `json:"name"`
	Host        string `json:"host"`
	Port        int    `json:"port"`
	Fingerprint string `json:"fingerprint_hex"`
	Token       string `json:"token_hex"`
	Expiry      int64  `json:"expiry_unix"`
	Text        string `json:"text"`
}

func goldenVectors(t *testing.T) []vector {
	t.Helper()
	raw, err := os.ReadFile("../../../wire/enroll-payload-vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var vectors []vector
	if err := json.Unmarshal(raw, &vectors); err != nil {
		t.Fatal(err)
	}
	return vectors
}

func mustHex(t *testing.T, s string, into []byte) {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	if len(b) != len(into) {
		t.Fatalf("want %d bytes of hex, got %d", len(into), len(b))
	}
	copy(into, b)
}

func mustBytes(t *testing.T, p enroll.Payload) []byte {
	t.Helper()
	b, err := enroll.Encode(p)
	if err != nil {
		t.Fatal(err)
	}
	return b
}
