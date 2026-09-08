package pinning

import (
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"io"
	"math/big"
	"net"
	"testing"
	"time"
)

const year = 365 * 24 * time.Hour

func mint(t *testing.T, name string) (Identity, tls.Certificate, *x509.Certificate) {
	t.Helper()
	id, err := Mint(name, 10*year)
	if err != nil {
		t.Fatalf("Mint(%q): %v", name, err)
	}
	own, err := LoadIdentity(id)
	if err != nil {
		t.Fatalf("LoadIdentity(%q): %v", name, err)
	}
	peer, err := LoadPeer(id.CertPEM)
	if err != nil {
		t.Fatalf("LoadPeer(%q): %v", name, err)
	}
	return id, own, peer
}

// handshake runs a real TLS 1.3 handshake over a real TCP connection and reports what each side saw.
//
// A real listener rather than net.Pipe: the property under test is that rejection happens at the TLS
// layer before anything reaches a request parser, and only a real connection shows that.
//
// **The client must READ, not merely dial and write.** Under TLS 1.3 the client finishes its
// handshake and can send application data before the server has looked at its certificate — client
// authentication is carried in the client's own last flight, so the server's verdict arrives
// afterwards, as an alert. A rejected client therefore sees tls.Dial SUCCEED and fails on the first
// read. Asserting on Dial alone would have made every server-side rejection test pass vacuously.
//
// This is a property of the protocol, not of this package, and the phone inherits it: on Android a
// refused certificate will look like "connected, then the connection dropped", never like "could not
// connect".
func handshake(t *testing.T, server, client *tls.Config) (clientErr, serverErr error) {
	t.Helper()
	ln, err := tls.Listen("tcp", "127.0.0.1:0", server)
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()

	done := make(chan error, 1)
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			done <- err
			return
		}
		defer conn.Close()
		// Accept is lazy; the read is what drives the handshake and the certificate check.
		_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
		buf := make([]byte, 1)
		if _, err := conn.Read(buf); err != nil && !errors.Is(err, io.EOF) {
			done <- err
			return
		}
		_, err = conn.Write([]byte("y"))
		done <- err
	}()

	conn, err := tls.Dial("tcp", ln.Addr().String(), client)
	if err != nil {
		return err, <-done
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err = conn.Write([]byte("x")); err != nil {
		return err, <-done
	}
	// The read is where a server-side rejection surfaces.
	buf := make([]byte, 1)
	if _, err = conn.Read(buf); err != nil && !errors.Is(err, io.EOF) {
		return err, <-done
	}
	return nil, <-done
}

// The happy path, and the only configuration that is supposed to exist in production.
func TestPinnedPairCompletesHandshake(t *testing.T) {
	_, bridgeOwn, bridgeCert := mint(t, "agterm-bridge")
	_, phoneOwn, phoneCert := mint(t, "agterm-remote phone")

	clientErr, serverErr := handshake(t,
		ServerConfig(bridgeOwn, []*x509.Certificate{phoneCert}),
		ClientConfig(phoneOwn, bridgeCert))

	if clientErr != nil {
		t.Errorf("the pinned phone must reach the bridge, got: %v", clientErr)
	}
	if serverErr != nil {
		t.Errorf("the bridge must accept the pinned phone, got: %v", serverErr)
	}
}

// A different phone. The ordinary rejection case.
func TestUnpinnedClientIsRejected(t *testing.T) {
	_, bridgeOwn, bridgeCert := mint(t, "agterm-bridge")
	_, _, phoneCert := mint(t, "agterm-remote phone")
	_, strangerOwn, _ := mint(t, "somebody else")

	clientErr, _ := handshake(t,
		ServerConfig(bridgeOwn, []*x509.Certificate{phoneCert}),
		ClientConfig(strangerOwn, bridgeCert))

	if clientErr == nil {
		t.Fatal("a certificate that is not the pinned one must not reach the bridge")
	}
}

// No certificate at all: the anonymous caller the fail-closed rule is about.
func TestClientWithNoCertificateIsRejected(t *testing.T) {
	_, bridgeOwn, bridgeCert := mint(t, "agterm-bridge")
	_, _, phoneCert := mint(t, "agterm-remote phone")

	anonymous := &tls.Config{
		MinVersion:            tls.VersionTLS13,
		InsecureSkipVerify:    true, //nolint:gosec // the test IS the attacker
		VerifyPeerCertificate: pinnedPeers([]*x509.Certificate{bridgeCert}),
	}
	clientErr, _ := handshake(t, ServerConfig(bridgeOwn, []*x509.Certificate{phoneCert}), anonymous)

	if clientErr == nil {
		t.Fatal("a caller with no client certificate must not get a connection")
	}
}

// The phone pins the bridge too, so a substituted server is refused by the client.
func TestUnpinnedServerIsRejectedByClient(t *testing.T) {
	_, imposterOwn, _ := mint(t, "not the owner's laptop")
	_, bridgeOwn, bridgeCert := mint(t, "agterm-bridge")
	_, phoneOwn, phoneCert := mint(t, "agterm-remote phone")
	_ = bridgeOwn

	clientErr, _ := handshake(t,
		ServerConfig(imposterOwn, []*x509.Certificate{phoneCert}),
		ClientConfig(phoneOwn, bridgeCert))

	if clientErr == nil {
		t.Fatal("the phone must refuse a laptop that is not the pinned one")
	}
}

// --- The hole a CA would have opened -------------------------------------------------------------

// The load-bearing test of this milestone.
//
// This design originally had the laptop SIGN the phone's request, which is a CA model. Under a CA,
// anything the issuer's key signs is valid — so a second certificate minted from that key would be
// accepted, and the claim that "no issuer exists that can be tricked into minting a second valid
// one" would have been false. This asserts the property that makes it true: a certificate
// correctly signed by the pinned certificate's own key is still refused, because pinning compares
// bytes and has no concept of an issuer at all.
func TestCertificateSignedByThePinnedKeyIsStillRejected(t *testing.T) {
	_, bridgeOwn, bridgeCert := mint(t, "agterm-bridge")
	phoneID, _, phoneCert := mint(t, "agterm-remote phone")

	forged := forgeSignedBy(t, phoneID, phoneCert, bridgeCert)
	_, serverErr := handshake(t, ServerConfig(bridgeOwn, []*x509.Certificate{phoneCert}), forged)

	if serverErr == nil {
		t.Fatal("a certificate signed by the pinned key is not the pinned certificate and must be refused")
	}
	if !errors.Is(serverErr, ErrNotPinned) {
		t.Errorf("expected the pinning refusal, got %v", serverErr)
	}
}

// Re-issuing with the same keypair is also refused: re-pinning is a deliberate act by the owner.
func TestReissuedCertificateWithTheSameKeyIsRejected(t *testing.T) {
	_, bridgeOwn, bridgeCert := mint(t, "agterm-bridge")
	phoneID, phoneOwn, _ := mint(t, "agterm-remote phone")

	// Pin a DIFFERENT mint of the same name; the live phone still holds the first one.
	_, _, otherPhoneCert := mint(t, "agterm-remote phone")
	_ = phoneID

	clientErr, _ := handshake(t,
		ServerConfig(bridgeOwn, []*x509.Certificate{otherPhoneCert}),
		ClientConfig(phoneOwn, bridgeCert))

	if clientErr == nil {
		t.Fatal("only the exact pinned certificate is accepted")
	}
}

// --- Why ClientCAs is not the mechanism ----------------------------------------------------------

// What ClientCAs actually does, measured — because I got this wrong by reasoning about it.
//
// An earlier sketch used RequireAndVerifyClientCert with the phone's certificate in ClientCAs, and I
// claimed that could not work with a non-CA certificate. **It works.** Go accepts a self-signed leaf
// that is itself in the root pool, without requiring IsCA. The claim was wrong and this test is what
// corrected it.
//
// It is kept because the interesting result is the second one: ClientCAs ALSO refuses a certificate
// signed by the pinned key, with "parent certificate cannot sign this kind of certificate". So both
// configurations are safe, and they are safe for the same underlying reason — Mint produces something
// that is not a CA.
//
// The reason ServerConfig still uses byte-exact pinning is narrower than "ClientCAs is broken", and
// worth stating precisely: **under ClientCAs the safety depends on IsCA staying false in a template
// in another file.** Flip that one field and the pool silently begins accepting children. Byte
// equality does not read any field of the certificate, so it cannot be undermined from a distance.
// That is a difference in how the property is held, not in whether it holds today.
func TestClientCAsIsViableButHoldsTheSamePropertyMoreLoosely(t *testing.T) {
	_, bridgeOwn, bridgeCert := mint(t, "agterm-bridge")
	phoneID, phoneOwn, phoneCert := mint(t, "agterm-remote phone")

	pool := x509.NewCertPool()
	pool.AddCert(phoneCert)
	caStyle := func() *tls.Config {
		return &tls.Config{
			Certificates: []tls.Certificate{bridgeOwn},
			MinVersion:   tls.VersionTLS13,
			ClientAuth:   tls.RequireAndVerifyClientCert,
			ClientCAs:    pool,
		}
	}

	if _, serverErr := handshake(t, caStyle(), ClientConfig(phoneOwn, bridgeCert)); serverErr != nil {
		t.Fatalf("ClientCAs does accept a non-CA self-signed leaf; if this fails, "+
			"the correction recorded in this test's doc comment needs re-checking: %v", serverErr)
	}

	forged := forgeSignedBy(t, phoneID, phoneCert, bridgeCert)
	_, serverErr := handshake(t, caStyle(), forged)
	if serverErr == nil {
		t.Fatal("ClientCAs must still refuse a certificate signed by the pinned key")
	}
	t.Logf("ClientCAs refused the forged certificate: %v", serverErr)
}

// forgeSignedBy mints a certificate signed by `issuer`'s own private key — what a CA model would call
// valid, and what a stolen issuer key would produce.
func forgeSignedBy(t *testing.T, issuerID Identity, issuerCert, pinnedServer *x509.Certificate) *tls.Config {
	t.Helper()
	block, _ := pem.Decode(issuerID.KeyPEM)
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		t.Fatalf("parse issuer key: %v", err)
	}
	key := parsed.(*ecdsa.PrivateKey)

	template := x509.Certificate{
		SerialNumber:          big.NewInt(2),
		Subject:               pkix.Name{CommonName: "forged"},
		NotBefore:             time.Now().Add(-time.Minute),
		NotAfter:              time.Now().Add(year),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, &template, issuerCert, &key.PublicKey, key)
	if err != nil {
		t.Fatalf("forge: %v", err)
	}
	return &tls.Config{
		Certificates:          []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}},
		MinVersion:            tls.VersionTLS13,
		InsecureSkipVerify:    true, //nolint:gosec // the test IS the attacker
		VerifyPeerCertificate: pinnedPeers([]*x509.Certificate{pinnedServer}),
	}
}

func TestMintedCertificateIsNotAnIssuer(t *testing.T) {
	_, _, cert := mint(t, "agterm-remote phone")

	if cert.IsCA {
		t.Error("a pinned certificate must never be a CA")
	}
	if cert.KeyUsage&x509.KeyUsageCertSign != 0 {
		t.Error("a pinned certificate must not carry KeyUsageCertSign")
	}
	if !cert.BasicConstraintsValid {
		t.Error("basic constraints must be present and valid, or IsCA=false is not asserted")
	}
}

// --- Validity, fingerprints, and the shape of what gets carried ----------------------------------

// It was measured that PKIX does not validate a trust anchor's own validity dates. A pinned
// certificate IS its own anchor, so without the explicit check in pinnedPeers an expired one would be
// accepted forever.
func TestExpiredPinnedCertificateIsRejected(t *testing.T) {
	_, bridgeOwn, bridgeCert := mint(t, "agterm-bridge")

	expiredID, expiredOwn, expiredCert := func() (Identity, tls.Certificate, *x509.Certificate) {
		id, err := Mint("expired phone", time.Millisecond)
		if err != nil {
			t.Fatalf("Mint: %v", err)
		}
		own, err := LoadIdentity(id)
		if err != nil {
			t.Fatalf("LoadIdentity: %v", err)
		}
		peer, err := LoadPeer(id.CertPEM)
		if err != nil {
			t.Fatalf("LoadPeer: %v", err)
		}
		return id, own, peer
	}()
	_ = expiredID
	time.Sleep(10 * time.Millisecond)

	clientErr, _ := handshake(t,
		ServerConfig(bridgeOwn, []*x509.Certificate{expiredCert}),
		ClientConfig(expiredOwn, bridgeCert))

	if clientErr == nil {
		t.Fatal("an expired pinned certificate must be refused")
	}
}

func TestFingerprintIdentifiesExactlyOneCertificate(t *testing.T) {
	_, _, a := mint(t, "phone")
	_, _, b := mint(t, "phone")

	if Fingerprint(a) == Fingerprint(b) {
		t.Fatal("two mints of the same name must not share a fingerprint")
	}
	if got := Fingerprint(a); len(got) != 16*4+15 {
		t.Errorf("fingerprint should be 16 groups of 4 hex separated by spaces, got %q", got)
	}
}

func TestLoadPeerRefusesABundle(t *testing.T) {
	a, _, _ := mint(t, "one")
	b, _, _ := mint(t, "two")

	if _, err := LoadPeer(append(a.CertPEM, b.CertPEM...)); err == nil {
		t.Fatal("a pinned peer is exactly one certificate; a bundle must be refused")
	}
}

// The provisioning leg carries a certificate, and enrolment claims it fits in a QR code. Measured
// here rather than assumed, so the claim is one this repository can reproduce.
func TestCertificateFitsInAQRCode(t *testing.T) {
	const qrVersion40BinaryCapacityL = 2953

	_, _, cert := mint(t, "agterm-remote phone")
	derBytes := len(cert.Raw)
	b64 := len(base64.StdEncoding.EncodeToString(cert.Raw))

	t.Logf("P-256 self-signed certificate: %d bytes DER, %d bytes base64; QR capacity %d",
		derBytes, b64, qrVersion40BinaryCapacityL)

	if b64 >= qrVersion40BinaryCapacityL {
		t.Fatalf("certificate does not fit in a QR code: %d >= %d", b64, qrVersion40BinaryCapacityL)
	}
}

// A key must never reach a log, a wire, or a repo. This pins the one property a test can check: what
// Mint hands back is separable, so the certificate can be published without the key riding along.
func TestIdentitySeparatesThePublicHalfFromTheSecret(t *testing.T) {
	id, err := Mint("phone", year)
	if err != nil {
		t.Fatalf("Mint: %v", err)
	}
	if _, err := LoadPeer(id.CertPEM); err != nil {
		t.Fatalf("the certificate half must stand alone: %v", err)
	}
	if block, _ := pem.Decode(id.CertPEM); block == nil || block.Type != "CERTIFICATE" {
		t.Fatal("CertPEM must contain only a CERTIFICATE block")
	}
	if _, rest := pem.Decode(id.CertPEM); len(rest) != 0 {
		t.Fatal("CertPEM must not carry anything after the certificate")
	}
}

var _ = net.Dial

// An expired peer is refused, and refused as EXPIRED rather than as a stranger.
//
// The two failures need different words on the owner's phone — a wrong certificate means the machine
// is not the one that was paired, an expired one means an identity needs re-minting — and the app was
// rebuilt so its verdict could survive the trip. Asserting it only there
// would leave the same property held at one end of the wire and not the other.
//
// The certificate here lives for a moment on purpose. A short lifetime is mintable on demand, and it
// is why the expiry path is testable on the owner's phone at all rather than argued from
// the contract forever.
func TestAnExpiredPeerIsRefusedAsExpiredRatherThanAsAStranger(t *testing.T) {
	id, err := Mint("briefly valid", 2*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	peer, err := LoadPeer(id.CertPEM)
	if err != nil {
		t.Fatal(err)
	}
	verify := pinnedPeers([]*x509.Certificate{peer})

	// While it is valid, the same bytes are accepted - so the refusal below is about the clock and
	// nothing else.
	if err := verify([][]byte{peer.Raw}, nil); err != nil {
		t.Fatalf("a valid pinned certificate must be accepted: %v", err)
	}

	// Long enough that the window has genuinely closed rather than nearly closed. The first version
	// used 50ms, which had already elapsed by the time the certificate was loaded - so the "accepted
	// while valid" half never ran and the test proved only half of what it claims.
	time.Sleep(2200 * time.Millisecond)

	err = verify([][]byte{peer.Raw}, nil)
	if !errors.Is(err, ErrExpired) {
		t.Fatalf("an expired pin must be refused as expired, got %v", err)
	}
	if errors.Is(err, ErrNotPinned) {
		t.Fatal("expiry must not be reported as a wrong certificate: the remedies differ")
	}
}

// The other half, so the two cannot quietly become one value again.
func TestAStrangerIsStillRefusedAsAStrangerRatherThanAsExpired(t *testing.T) {
	mine, err := Mint("mine", time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	theirs, err := Mint("theirs", time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	pinned, err := LoadPeer(mine.CertPEM)
	if err != nil {
		t.Fatal(err)
	}
	stranger, err := LoadPeer(theirs.CertPEM)
	if err != nil {
		t.Fatal(err)
	}

	err = pinnedPeers([]*x509.Certificate{pinned})([][]byte{stranger.Raw}, nil)

	if !errors.Is(err, ErrNotPinned) {
		t.Fatalf("a wrong certificate must be refused as not pinned, got %v", err)
	}
	if errors.Is(err, ErrExpired) {
		t.Fatal("a stranger's certificate is not an expiry, whatever its dates say")
	}
}

// --- The list, and what an empty one means -------------------------------------------------------

// **Any phone on the list is accepted, not merely the first.**
//
// The store holds a list because a second phone should cost a screen and a call to Add rather than a
// migration of the file, and a verifier that only ever consulted element zero would make that
// promise false at the one point where it is enforced. Asserted with the LAST element, because a
// loop that returns early on the first match passes with the first.
func TestAnyPinnedPeerOnTheListIsAccepted(t *testing.T) {
	_, bridgeOwn, bridgeCert := mint(t, "agterm-bridge")
	_, _, firstPhone := mint(t, "agterm-remote phone")
	_, secondOwn, secondPhone := mint(t, "agterm-remote phone")

	clientErr, serverErr := handshake(t,
		ServerConfig(bridgeOwn, []*x509.Certificate{firstPhone, secondPhone}),
		ClientConfig(secondOwn, bridgeCert))

	if clientErr != nil || serverErr != nil {
		t.Fatalf("a phone on the list must be accepted: client=%v server=%v", clientErr, serverErr)
	}
}

// **An empty list accepts nobody**, which is what a bridge nobody has paired yet must do.
//
// This is the case that lets the bridge start before enrolment: the door is open, and no certificate
// that exists can get through it. A verifier that treated "nothing to compare against" as "nothing
// to object to" would turn an unpaired bridge into an open one.
func TestAnEmptyPeerListAcceptsNobody(t *testing.T) {
	_, bridgeOwn, bridgeCert := mint(t, "agterm-bridge")
	_, phoneOwn, _ := mint(t, "agterm-remote phone")

	clientErr, _ := handshake(t,
		ServerConfig(bridgeOwn, nil),
		ClientConfig(phoneOwn, bridgeCert))

	if clientErr == nil {
		t.Fatal("a bridge with no paired phones must accept nobody")
	}
}

// A stranger is still refused when other phones ARE paired, so the loop above widened what is
// accepted by exactly the certificates on the list and by nothing else.
func TestAStrangerIsRefusedByANonEmptyList(t *testing.T) {
	_, bridgeOwn, bridgeCert := mint(t, "agterm-bridge")
	_, _, firstPhone := mint(t, "agterm-remote phone")
	_, _, secondPhone := mint(t, "agterm-remote phone")
	_, strangerOwn, _ := mint(t, "somebody else")

	clientErr, _ := handshake(t,
		ServerConfig(bridgeOwn, []*x509.Certificate{firstPhone, secondPhone}),
		ClientConfig(strangerOwn, bridgeCert))

	if clientErr == nil {
		t.Fatal("a certificate that is on no list must not be accepted")
	}
}
