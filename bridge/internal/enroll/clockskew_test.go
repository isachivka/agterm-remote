package enroll_test

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/enroll"
)

// certificateStarting mints a usable self-signed certificate whose validity begins at `at`.
func certificateStarting(t *testing.T, at time.Time) []byte {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(7), Subject: pkix.Name{CommonName: "a phone"},
		NotBefore: at, NotAfter: at.Add(365 * 24 * time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return der
}

// A phone whose clock is a few minutes ahead of this Mac mints a certificate that starts in this
// Mac's future. It used to be refused - after the token was spent, so the owner's second scan
// worked and the first was a mystery. Ordinary skew is tolerated; a certificate half an hour out is
// still refused, and the refusal is now something the owner's window can read.
func TestAPhoneAFewMinutesAheadStillPairsAndHalfAnHourAheadDoesNot(t *testing.T) {
	window, store, _, h := handlerFixture(t)

	code, _ := window.Open(time.Minute)
	reply := exchange(t, h, enroll.Request{
		Verb: "enroll", Token: b64(code[:]),
		Certificate: b64(certificateStarting(t, time.Now().Add(5*time.Minute)))})
	if !reply.OK {
		t.Fatalf("five minutes of skew was refused: %s", reply.Error)
	}
	if len(store.Peers()) != 1 {
		t.Fatal("nothing was pinned")
	}
	if s := window.State(); s.LastRefusal != "" {
		t.Fatalf("a successful pairing left a refusal standing: %q", s.LastRefusal)
	}

	code, _ = window.Open(time.Minute)
	reply = exchange(t, h, enroll.Request{
		Verb: "enroll", Token: b64(code[:]),
		Certificate: b64(certificateStarting(t, time.Now().Add(30*time.Minute)))})
	if reply.OK {
		t.Fatal("a certificate half an hour in the future was pinned")
	}
	s := window.State()
	if s.LastRefusal == "" || s.LastRefusalAt.IsZero() {
		t.Fatal("the refusal was not kept for the owner's window")
	}
}
