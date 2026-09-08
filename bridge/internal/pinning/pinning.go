// Package pinning holds the bridge's whole trust model: two self-signed certificates that accept
// each other and nothing else.
//
// There is no certificate authority anywhere in this design, and that is the point rather than a
// simplification. Under a CA the issuer's key mints unlimited valid clients, so "nobody can produce a
// second valid certificate" would depend on a key staying secret. Here it is true by construction:
// each side pins the exact bytes of one peer certificate, so there is nothing to sign and nothing to
// be tricked into signing. An earlier draft of this design did have a CA, and the test that pins the
// bytes is what records why it was dropped.
//
// Nothing in this package weakens certificate validation.
// Pinning REPLACES chain validation with a strictly narrower test: one specific
// certificate, compared byte for byte. A permissive verifier widens what is accepted; this narrows it
// to exactly one. The distinction matters because the mechanism below sets InsecureSkipVerify, which
// looks identical to the thing that is forbidden and is the opposite of it — see pinnedPeer.
package pinning

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"strings"
	"time"
)

// Identity is one side's own certificate and private key, PEM-encoded.
//
// The key half is a secret that must never be transmitted, logged, or committed. The certificate half
// is PUBLIC — it is what the peer pins, and it carries no secret at all. That asymmetry is what makes
// provisioning tractable: the leg that crosses between the two devices needs to be AUTHENTIC (the
// owner must know they pinned the right certificate) but does not need to be CONFIDENTIAL, because
// there is nothing confidential on it.
type Identity struct {
	CertPEM []byte
	KeyPEM  []byte
}

// Mint generates a P-256 keypair and self-signs a certificate for it.
//
// Deliberately NOT a CA: BasicConstraintsValid with IsCA false, and no KeyUsageCertSign. A
// certificate that cannot sign other certificates cannot become an issuer later by accident, which is
// the property the whole trust model turns on. It also means this certificate can never be used in a ClientCAs
// pool — see ServerConfig, where that is the correct outcome and not a limitation.
//
// validFor is expected to be long, and that was ruled deliberately: an expiry on a pinned
// self-signed pair buys an attacker nothing, and guarantees a day the owner's phone stops working
// while they are away from the only machine that can fix it.
func Mint(commonName string, validFor time.Duration) (Identity, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return Identity{}, fmt.Errorf("generate key: %w", err)
	}

	// A 128-bit random serial. Nothing consumes it — there is no issuer keeping a register — but a
	// fixed serial would make two mints of the same name indistinguishable in a log.
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return Identity{}, fmt.Errorf("generate serial: %w", err)
	}

	now := time.Now()
	template := x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: commonName},
		// One minute of backdating, so a phone and a laptop whose clocks disagree by seconds do not
		// produce a certificate that is not yet valid on the machine that has to pin it.
		NotBefore:             now.Add(-time.Minute),
		NotAfter:              now.Add(validFor),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth},
		BasicConstraintsValid: true,
		IsCA:                  false,
	}

	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		return Identity{}, fmt.Errorf("self-sign: %w", err)
	}
	keyDER, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return Identity{}, fmt.Errorf("marshal key: %w", err)
	}

	return Identity{
		CertPEM: pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}),
		KeyPEM:  pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER}),
	}, nil
}

// Fingerprint is the SHA-256 of the certificate's DER, grouped for a human to read aloud.
//
// This is the authenticity mechanism for provisioning. The certificates themselves are public, so the
// transfer between phone and laptop does not need to be private — it needs the owner to be sure they
// pinned the certificate they meant to. Both devices show this string; the owner compares them. It is
// the SSH host-key model, and it is the only step in this milestone that depends on a human.
func Fingerprint(cert *x509.Certificate) string {
	sum := sha256.Sum256(cert.Raw)
	groups := make([]string, 0, len(sum)/2)
	for i := 0; i < len(sum); i += 2 {
		groups = append(groups, fmt.Sprintf("%02X%02X", sum[i], sum[i+1]))
	}
	return strings.Join(groups, " ")
}

// LoadIdentity parses one side's own certificate and key for use in a handshake.
func LoadIdentity(id Identity) (tls.Certificate, error) {
	return tls.X509KeyPair(id.CertPEM, id.KeyPEM)
}

// LoadPeer parses a peer's PEM certificate — the thing that gets pinned.
//
// Rejects a file carrying more than one certificate. A pinned peer is exactly one certificate; a
// bundle means either a mistake or a chain, and a chain is the shape of the CA model this design does
// not have.
func LoadPeer(certPEM []byte) (*x509.Certificate, error) {
	block, rest := pem.Decode(certPEM)
	if block == nil || block.Type != "CERTIFICATE" {
		return nil, errors.New("no CERTIFICATE block")
	}
	if len(pemBlocks(rest)) > 0 {
		return nil, errors.New("more than one certificate: a pinned peer is exactly one")
	}
	return x509.ParseCertificate(block.Bytes)
}

func pemBlocks(rest []byte) [][]byte {
	var out [][]byte
	for {
		block, remainder := pem.Decode(rest)
		if block == nil {
			return out
		}
		out = append(out, block.Bytes)
		rest = remainder
	}
}

// ErrNotPinned is returned when a peer presents a certificate that is not the pinned one — wrong
// bytes, or more than one certificate.
var ErrNotPinned = errors.New("peer certificate is not the pinned certificate")

// ErrExpired is returned when the peer presented exactly the pinned certificate and its validity
// window has passed.
//
// # Two audiences, and only one of them may learn anything
//
// This used to be ErrNotPinned as well, and the reason given was that a caller must learn it was
// rejected and never why. **That reason is still right and is unchanged**: neither error is ever sent
// anywhere. A rejected peer gets a failed handshake and a generic TLS alert either way, and nothing in
// this package or the listener writes an error to a connection.
//
// What changed is who else is looking. These two mean different things to the OWNER, reading their own
// laptop's log or holding the phone: a wrong certificate means the machine is not the one that was
// paired, and an expired one means an identity needs re-minting. The app already had to tell them
// apart — the app rebuilt how its verdict travels precisely so that it could — and a
// bridge that collapsed them would leave the same property asserted at one end and not the other.
//
// **The peer still cannot tell**, and that is a test rather than a claim.
var ErrExpired = errors.New("peer certificate has expired")

// pinnedPeer builds the verifier both sides use: the peer must present exactly one certificate, and
// it must be byte-identical to the pinned one.
//
// Byte equality, not "signed by" and not "has the same public key". Two things follow that are worth
// stating because they are the whole security argument:
//
//   - A certificate correctly signed by the pinned certificate's key still fails. That is the case a
//     CA model would accept and this one must not, and it is the assertion the package test makes.
//   - A re-issued certificate with the same key fails too. Re-pinning is a deliberate act by the
//     owner, never something that happens quietly.
//
// The validity window is checked explicitly. It was measured that PKIX does not validate a trust
// anchor's own validity dates, so a pinned certificate — which IS its own anchor — would otherwise
// never be checked for expiry at all.
func pinnedPeer(want *x509.Certificate) func([][]byte, [][]*x509.Certificate) error {
	return func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
		if len(rawCerts) != 1 {
			return ErrNotPinned
		}
		if subtle.ConstantTimeCompare(rawCerts[0], want.Raw) != 1 {
			return ErrNotPinned
		}
		now := time.Now()
		if now.Before(want.NotBefore) || now.After(want.NotAfter) {
			return ErrExpired
		}
		return nil
	}
}

// ServerConfig is the bridge's listener configuration: TLS 1.3, a client certificate required, and
// that certificate pinned.
//
// ClientAuth is RequireAnyClientCert rather than RequireAndVerifyClientCert, and ClientCAs is
// deliberately empty. RequireAndVerify means "build a chain to something in ClientCAs", which is CA
// semantics — and a certificate minted by Mint cannot appear in that pool at all, because it is not a
// CA. Putting it there would mean marking the phone's certificate IsCA: a certificate permitted to
// sign others, which is precisely what this design says must not exist. The verification that matters
// happens in VerifyPeerCertificate, which is stricter than any chain check: one certificate, exact
// bytes.
//
// Rejection still happens during the handshake. A VerifyPeerCertificate error aborts it with a TLS
// alert, so an unauthenticated caller gets a handshake failure and never reaches a request parser, a
// handler, or a log line containing anything they chose. That is the fail-closed rule, and
// it is a property of where this runs rather than of anything a handler remembers to do.
func ServerConfig(own tls.Certificate, pinnedClient *x509.Certificate) *tls.Config {
	return &tls.Config{
		Certificates:          []tls.Certificate{own},
		MinVersion:            tls.VersionTLS13,
		ClientAuth:            tls.RequireAnyClientCert,
		VerifyPeerCertificate: pinnedPeer(pinnedClient),
	}
}

// ClientConfig is the peer side of the same arrangement. The bridge does not use it; it exists so the
// handshake can be proven end to end in a test, and as the reference the Android client is written
// against.
//
// InsecureSkipVerify is set, and this is the one place in this repo where that appears. It disables
// Go's chain-and-hostname verification so that VerifyPeerCertificate can replace it with a strictly
// narrower test. Without it, Go would additionally demand a chain to a public root, which a
// self-signed certificate does not have — the connection would fail for a reason that has nothing to
// do with whether it is talking to the right laptop. What is accepted here is exactly one
// certificate. A permissive verifier accepts anything; this accepts one thing. They are opposites,
// and the flag name is why the comment is this long.
func ClientConfig(own tls.Certificate, pinnedServer *x509.Certificate) *tls.Config {
	return &tls.Config{
		Certificates:          []tls.Certificate{own},
		MinVersion:            tls.VersionTLS13,
		InsecureSkipVerify:    true, //nolint:gosec // replaced by pinnedPeer, which is narrower
		VerifyPeerCertificate: pinnedPeer(pinnedServer),
	}
}
