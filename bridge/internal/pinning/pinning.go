// Package pinning holds the bridge's whole trust model: two self-signed certificates that accept
// each other and nothing else.
//
// There is no certificate authority anywhere in this design, and that is the point rather than a
// simplification. Under a CA the issuer's key mints unlimited valid clients, so "nobody can produce a
// second valid certificate" would depend on a key staying secret. Here it is true by construction:
// each side pins the exact bytes of one peer certificate, so there is nothing to sign and nothing to
// be tricked into signing. An earlier draft of this design did have a CA, and the test that pins
// the bytes is what records why it was dropped.
//
// Nothing in this package weakens certificate validation. Pinning REPLACES chain validation with a
// strictly narrower test: one specific certificate, compared byte for byte. A permissive verifier
// widens what is accepted; this narrows it to exactly one. The distinction matters because the
// mechanism below sets InsecureSkipVerify, which looks identical to the thing that is forbidden and
// is the opposite of it — see pinnedPeers.
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
// the property the whole trust model turns on. It also means this certificate can never be used in
// a ClientCAs pool — see ServerConfig, where that is the correct outcome and not a limitation.
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

// pinnedPeers builds the verifier both sides use: the peer must present exactly one certificate,
// and it must be byte-identical to one of the pinned ones.
//
// Byte equality, not "signed by" and not "has the same public key". Two things follow that are worth
// stating because they are the whole security argument:
//
//   - A certificate correctly signed by a pinned certificate's key still fails. That is the case a
//     CA model would accept and this one must not, and it is the assertion the package test makes.
//   - A re-issued certificate with the same key fails too. Re-pinning is a deliberate act by the
//     owner, never something that happens quietly.
//
// **An empty list accepts nobody**, which is the whole of what a bridge nobody has paired yet
// should do. It is a plain consequence of "must equal one of these" rather than a case handled
// separately, and it is the reason the bridge can start and listen before a phone has ever
// enrolled: an unpaired bridge is not a broken one, it is one whose door opens for no certificate
// that exists.
//
// The loop does not stop at the match. Its running time is a function of how many phones are paired
// and of nothing the caller sends, so a peer cannot learn its position in the list from how long a
// rejection took.
//
// The validity window is checked explicitly, and against the certificate that MATCHED rather than
// against the list. It was measured that PKIX does not validate a trust anchor's own validity
// dates, so a pinned certificate — which IS its own anchor — would otherwise never be checked for
// expiry at all.
func pinnedPeers(want []*x509.Certificate) func([][]byte, [][]*x509.Certificate) error {
	return func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
		if len(rawCerts) != 1 {
			return ErrNotPinned
		}
		var matched *x509.Certificate
		for _, cert := range want {
			if subtle.ConstantTimeCompare(rawCerts[0], cert.Raw) == 1 {
				matched = cert
			}
		}
		if matched == nil {
			return ErrNotPinned
		}
		now := time.Now()
		if now.Before(matched.NotBefore) || now.After(matched.NotAfter) {
			return ErrExpired
		}
		return nil
	}
}

// ServerConfig is the bridge's listener configuration: TLS 1.3, a client certificate required, and
// that certificate pinned to one of the phones on the list.
//
// # Why a list, when the bridge accepts one phone
//
// The peer list is what the trust store keeps on disk, and it is a list there for reasons of its
// own — a second phone should cost a screen and a call to Add, not a migration of a
// security-critical file. Taking a single certificate here would put the "exactly one" rule in two
// places and make the wiring between them a lie by one element. So this takes what the store hands
// out, and how many elements are in it is the store's business.
//
// ClientAuth is RequireAnyClientCert rather than RequireAndVerifyClientCert, and ClientCAs is
// deliberately empty. RequireAndVerify means "build a chain to something in ClientCAs", which is CA
// semantics — and a certificate minted by Mint cannot appear in that pool at all, because it is not a
// CA. Putting it there would mean marking the phone's certificate IsCA: a certificate permitted to
// sign others, which is precisely what this design says must not exist. The verification that
// matters happens in VerifyPeerCertificate, which is stricter than any chain check: one
// certificate, exact bytes, equal to one on the list.
//
// Rejection still happens during the handshake. A VerifyPeerCertificate error aborts it with a TLS
// alert, so an unauthenticated caller gets a handshake failure and never reaches a request parser, a
// handler, or a log line containing anything they chose. That is the fail-closed rule, and
// it is a property of where this runs rather than of anything a handler remembers to do.
func ServerConfig(own tls.Certificate, pinnedClients []*x509.Certificate) *tls.Config {
	return &tls.Config{
		Certificates:          []tls.Certificate{own},
		MinVersion:            tls.VersionTLS13,
		ClientAuth:            tls.RequireAnyClientCert,
		VerifyPeerCertificate: pinnedPeers(pinnedClients),
	}
}

// AnonymousServerConfig is the listener configuration for the one exchange that happens BEFORE
// there is a pinned client: enrolment.
//
// It serves own, asks for no client certificate, and verifies nothing about the caller — because at
// this point in the design there is nothing yet to verify. The phone's certificate is what enrolment
// EXISTS to deliver; requiring it here would be requiring the answer as the price of asking the
// question.
//
// # This is not a weakened ServerConfig, and it must never become reachable from where that one is
//
// Everything ServerConfig refuses, this accepts, so the only thing keeping the two apart is that a
// connection is on one or the other and cannot cross. That separation is internal/enroll's
// ServerConfigFor's job and it is made in the TLS handshake by ALPN, before a byte of application data exists: a
// connection served by this config negotiated `agterm/enroll-1`, which leads to the enrolment
// handler and to nothing else. Expressed instead as a path inside one authenticated stream, the same
// claim would rest on routing code — a far weaker thing to assert about a port deliberately exposed
// to the internet.
//
// Two properties are what make an anonymous branch acceptable at all, and neither is here:
//
//   - The branch is only OFFERED while the owner has an enrolment window open, which is seconds of
//     the bridge's life and requires a person at the Mac. See internal/enroll, ServerConfigFor.
//   - What it leads to is guarded by a 32-byte single-use token compared in constant time. See
//     [Window].
//
// So this config authenticates nobody, and it is honest about that rather than approximating it: no
// ClientCAs, no InsecureSkipVerify to explain, no VerifyPeerCertificate that would look like a
// check. ClientAuth is NoClientCert, so the certificate is not even requested — nothing arrives that
// a later edit could be tempted to trust.
//
// The server half is still pinned from the OTHER side. The phone read this certificate off the QR
// code, so an unpaired phone still knows which laptop it is talking to; what is anonymous here is
// the caller, in one direction, for one exchange.
func AnonymousServerConfig(own tls.Certificate) *tls.Config {
	return &tls.Config{
		Certificates: []tls.Certificate{own},
		MinVersion:   tls.VersionTLS13,
		// Not RequestClientCert. A requested-but-unverified certificate is a certificate sitting in
		// the connection state with nothing having checked it, which is the shape a later reader
		// mistakes for an identity. Nothing is asked for, so nothing is there.
		ClientAuth: tls.NoClientCert,
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
		InsecureSkipVerify:    true, //nolint:gosec // replaced by pinnedPeers, which is narrower
		VerifyPeerCertificate: pinnedPeers([]*x509.Certificate{pinnedServer}),
	}
}
