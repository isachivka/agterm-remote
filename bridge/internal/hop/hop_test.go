package hop

import (
	"bufio"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"math/big"
	"net"
	"testing"
	"time"
)

func serverConfig(t *testing.T) *tls.Config {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "hop"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}, MinVersion: tls.VersionTLS12}
}

// start serves every accepted connection with one line: what it read back, prefixed by how it came.
func start(t *testing.T) (*Listener, string) {
	t.Helper()
	inner, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	l := Listen(inner, serverConfig(t))
	t.Cleanup(func() { _ = l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				line, err := bufio.NewReader(c).ReadString('\n')
				if err != nil {
					return
				}
				dress := "plain:"
				if _, ok := c.(*tls.Conn); ok {
					dress = "tls:"
				}
				_, _ = c.Write([]byte(dress + line))
			}()
		}
	}()
	return l, l.Addr().String()
}

func TestAPlainCallerAndATLSCallerAreBothServedOnTheOnePort(t *testing.T) {
	_, addr := start(t)

	plain, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer plain.Close()
	_, _ = plain.Write([]byte("hello\n"))
	got, _ := bufio.NewReader(plain).ReadString('\n')
	if got != "plain:hello\n" {
		t.Fatalf("plain caller got %q", got)
	}

	secure, err := tls.Dial("tcp", addr, &tls.Config{InsecureSkipVerify: true}) //nolint:gosec // opportunistic hop, nothing to verify against
	if err != nil {
		t.Fatal(err)
	}
	defer secure.Close()
	_, _ = secure.Write([]byte("hello\n"))
	got, _ = bufio.NewReader(secure).ReadString('\n')
	if got != "tls:hello\n" {
		t.Fatalf("tls caller got %q", got)
	}
}

func TestASilentCallerDoesNotStallTheNextOne(t *testing.T) {
	_, addr := start(t)

	silent, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer silent.Close()

	next, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer next.Close()
	_ = next.SetDeadline(time.Now().Add(3 * time.Second))
	_, _ = next.Write([]byte("after\n"))
	got, err := bufio.NewReader(next).ReadString('\n')
	if err != nil || got != "plain:after\n" {
		t.Fatalf("a silent caller stalled the next one: %q, %v", got, err)
	}
}

func TestCloseUnblocksAccept(t *testing.T) {
	inner, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	l := Listen(inner, serverConfig(t))
	done := make(chan error, 1)
	go func() { _, err := l.Accept(); done <- err }()
	_ = l.Close()
	select {
	case err := <-done:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatalf("Accept after Close returned %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Accept did not return after Close")
	}
}
