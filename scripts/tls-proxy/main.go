// A stand-in for a router that publishes a Mac by PROXYING it, for scripts/enrol-end-to-end.sh.
//
// # Why this exists rather than a note saying the proxied route was reasoned about
//
// Two regressions shipped because the proxied deployment was argued about instead of run. The
// payload had no scheme, so the phone could only ever dial one way; and the bridge's on-link TLS was
// removed on a security argument that was true and beside the point. Both were invisible to every
// test in the repository, because every test used the direct route - which is the substitution that
// let them through.
//
// # Where it differs from the real thing, stated rather than glossed
//
// It does not run on a router, does no NAT and no hairpin, and terminates one connection rather than
// serving a whole name. What it reproduces is the only property that broke: something terminates TLS
// at its edge and opens a SECOND TLS connection to the bridge.
//
// It terminates TLS at its own edge with a certificate signed by a CA it also mints, and opens a
// SECOND TLS connection to the bridge, validating nothing - which is what such a router does and
// cannot help doing, since there is no name it could check the bridge's certificate against.
//
// Stdlib only. It exists to carry bytes, and every byte it carries is a TLS record it cannot read.
package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"flag"
	"io"
	"log"
	"math/big"
	"net"
	"os"
	"time"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:9443", "where the phone dials")
	backend := flag.String("backend", "127.0.0.1:8459", "the bridge")
	caOut := flag.String("ca-out", "ca.pem", "the CA the phone must trust; reused when it is already there")
	caKeyOut := flag.String("ca-key", "ca-key.pem", "the CA key, beside it")
	flag.Parse()

	caCert, caKey := loadOrMintCA(*caOut, *caKeyOut)
	leaf, leafKey := mint(caCert, caKey, "127.0.0.1", false, []net.IP{net.ParseIP("127.0.0.1")})

	ln, err := tls.Listen("tcp", *listen, &tls.Config{
		Certificates: []tls.Certificate{{Certificate: [][]byte{leaf.Raw, caCert.Raw}, PrivateKey: leafKey}},
		MinVersion:   tls.VersionTLS12,
	})
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("proxy: HTTPS on %s, HTTPS to %s", *listen, *backend)

	for {
		front, err := ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer front.Close()
			// The second hop, and the whole point: TLS to the backend, validating nothing.
			back, err := tls.Dial("tcp", *backend, &tls.Config{InsecureSkipVerify: true})
			if err != nil {
				log.Printf("proxy: the backend refused a TLS connection: %v", err)
				return
			}
			defer back.Close()
			go func() { _, _ = io.Copy(back, front) }()
			_, _ = io.Copy(front, back)
		}()
	}
}

// loadOrMintCA keeps ONE CA across runs, because the phone has it installed and a fresh one every
// start would be a certificate it has never trusted - which looks exactly like the failure under
// test and is not it.
func loadOrMintCA(certPath, keyPath string) (*x509.Certificate, *ecdsa.PrivateKey) {
	if certPEM, err := os.ReadFile(certPath); err == nil {
		if keyPEM, err := os.ReadFile(keyPath); err == nil {
			cb, _ := pem.Decode(certPEM)
			kb, _ := pem.Decode(keyPEM)
			if cb != nil && kb != nil {
				cert, err1 := x509.ParseCertificate(cb.Bytes)
				key, err2 := x509.ParseECPrivateKey(kb.Bytes)
				if err1 == nil && err2 == nil {
					log.Printf("proxy: reusing the CA in %s", certPath)
					return cert, key
				}
			}
		}
	}
	cert, key := mint(nil, nil, "agterm-remote test proxy CA", true, nil)
	der, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		log.Fatal(err)
	}
	if err := os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cert.Raw}), 0o644); err != nil {
		log.Fatal(err)
	}
	if err := os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: der}), 0o600); err != nil {
		log.Fatal(err)
	}
	log.Printf("proxy: minted a CA into %s", certPath)
	return cert, key
}

func mint(parent *x509.Certificate, parentKey *ecdsa.PrivateKey, cn string, ca bool, ips []net.IP) (*x509.Certificate, *ecdsa.PrivateKey) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		log.Fatal(err)
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	tmpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: cn},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(24 * time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		BasicConstraintsValid: true,
		IsCA:                  ca,
		IPAddresses:           ips,
	}
	if !ca {
		tmpl.ExtKeyUsage = []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}
		tmpl.KeyUsage = x509.KeyUsageDigitalSignature
	}
	signer, signerKey := tmpl, key
	if parent != nil {
		signer, signerKey = parent, parentKey
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, signer, &key.PublicKey, signerKey)
	if err != nil {
		log.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		log.Fatal(err)
	}
	return cert, key
}
