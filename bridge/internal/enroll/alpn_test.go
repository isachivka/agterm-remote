package enroll_test

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/api"
	"github.com/isachivka/agterm-remote/bridge/internal/enroll"
	"github.com/isachivka/agterm-remote/bridge/internal/listener"
	"github.com/isachivka/agterm-remote/bridge/internal/pinning"
	"github.com/isachivka/agterm-remote/bridge/internal/trust"
)

// The tests in this file all run a REAL TLS 1.3 handshake over a real TCP connection.
//
// That is not thoroughness for its own sake. The claim this file exists to hold is "a connection with
// no client certificate cannot reach the API under any sequence of actions", and under ALPN that
// claim is a property of the handshake: which protocol is negotiated, and whether a certificate was
// asked for at all, are both decided before one byte of application data exists. A stub that called
// GetConfigForClient by hand and read the tls.Config it returned would assert what this code intends
// rather than what the TLS stack does with it - and every interesting case here (an ALPN with no
// overlap, a certificate that is never requested, an empty certificate chain) lives in the stack.

// attempt is one handshake as the SERVER saw it.
//
// The server's own view is what the invariant has to be asserted against. A client learns what it
// negotiated, but only the server knows whether it asked for a certificate and what arrived, and
// "negotiated the API without a certificate" is a statement about the server's connection.
type attempt struct {
	state tls.ConnectionState
	err   error
}

type testServer struct {
	addr string
	// cert is the bridge's own certificate, which every client below pins.
	cert *x509.Certificate

	mu       sync.Mutex
	attempts []attempt
}

func (s *testServer) record(state tls.ConnectionState, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.attempts = append(s.attempts, attempt{state: state, err: err})
}

func (s *testServer) completed() []tls.ConnectionState {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []tls.ConnectionState
	for _, a := range s.attempts {
		if a.err == nil {
			out = append(out, a.state)
		}
	}
	return out
}

func mint(t *testing.T, name string) (tls.Certificate, *x509.Certificate) {
	t.Helper()
	id, err := pinning.Mint(name, time.Hour)
	if err != nil {
		t.Fatalf("Mint(%q): %v", name, err)
	}
	own, err := pinning.LoadIdentity(id)
	if err != nil {
		t.Fatalf("LoadIdentity(%q): %v", name, err)
	}
	peer, err := pinning.LoadPeer(id.CertPEM)
	if err != nil {
		t.Fatalf("LoadPeer(%q): %v", name, err)
	}
	return own, peer
}

// mintIdentity is one certificate nobody pinned, for the stranger cases.
func mintIdentity(t *testing.T) tls.Certificate {
	t.Helper()
	own, _ := mint(t, "somebody else")
	return own
}

// testBridge stands up the real thing: a trust store on disk holding one paired phone, a closed
// enrolment window, and a TCP listener serving enroll.ServerConfigFor over both.
//
// The trust store is the real one rather than a slice, because half of what this task changes is
// that the certificates are read PER CONNECTION instead of once at startup, and only a store that
// can be written to while the listener is up can show that.
func testBridge(t *testing.T) (*testServer, *enroll.Window, tls.Certificate, *trust.Store) {
	return testBridgeAt(t, time.Now)
}

func testBridgeAt(t *testing.T, now func() time.Time) (*testServer, *enroll.Window, tls.Certificate, *trust.Store) {
	t.Helper()
	// The trust store logs a count when a stored certificate stops parsing, and the listener logs one
	// line per request. Neither is what these tests read.
	log.SetOutput(io.Discard)
	t.Cleanup(func() { log.SetOutput(io.Discard) })

	bridgeOwn, bridgeCert := mint(t, "agterm-remote bridge")
	phoneOwn, phoneCert := mint(t, "agterm-remote phone")

	store, err := trust.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Add(trust.Peer{
		Fingerprint:    pinning.Fingerprint(phoneCert),
		CertificateDER: phoneCert.Raw,
		PairedAt:       time.Unix(1, 0),
	}); err != nil {
		t.Fatal(err)
	}

	window := enroll.NewWindow(now)
	cfg := enroll.ServerConfigFor(bridgeOwn, enroll.NewPinnedClients(store).Certificates, window)

	tcp, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = tcp.Close() })

	srv := &testServer{addr: tcp.Addr().String(), cert: bridgeCert}
	go serveHandshakes(srv, tcp, cfg)

	return srv, window, phoneOwn, store
}

// serveHandshakes resolves the handshake, records what the server saw, and answers one byte.
//
// The byte matters. Under TLS 1.3 the client's certificate rides in its own last flight, so the
// client's handshake completes before the server has judged it and tls.Dial SUCCEEDS for a client the
// server is about to refuse - the refusal arrives as an alert on the first read. A test that stopped
// at Dial would pass vacuously for every server-side rejection in this file.
func serveHandshakes(srv *testServer, ln net.Listener, cfg *tls.Config) {
	for {
		raw, err := ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer raw.Close()
			_ = raw.SetDeadline(time.Now().Add(10 * time.Second))
			conn := tls.Server(raw, cfg)
			err := conn.HandshakeContext(context.Background())
			srv.record(conn.ConnectionState(), err)
			if err != nil {
				return
			}
			_, _ = conn.Write([]byte("y"))
		}()
	}
}

// clientConfig is the phone's side: the bridge's certificate pinned by exact bytes, an optional
// client identity, and the ALPN protocols on offer.
//
// It is built from pinning.ClientConfig so the server certificate is pinned exactly as the Android
// client pins it, including on the enrolment branch - a phone that has not paired yet still knows
// which laptop it is talking to, because it read that certificate off the QR code.
func clientConfig(pinnedServer *x509.Certificate, own *tls.Certificate, protos []string) *tls.Config {
	cfg := pinning.ClientConfig(tls.Certificate{}, pinnedServer)
	cfg.Certificates = nil
	if own != nil {
		cfg.Certificates = []tls.Certificate{*own}
	}
	cfg.NextProtos = protos
	return cfg
}

// dial completes a handshake and then makes the server speak, so that a server-side rejection is
// observed rather than assumed. A nil own means a client with no certificate at all.
func dial(t *testing.T, srv *testServer, own *tls.Certificate, protos []string) (tls.ConnectionState, error) {
	t.Helper()
	return dialWith(t, srv, clientConfig(srv.cert, own, protos))
}

func dialWith(t *testing.T, srv *testServer, cfg *tls.Config) (tls.ConnectionState, error) {
	t.Helper()
	conn, err := tls.Dial("tcp", srv.addr, cfg)
	if err != nil {
		return tls.ConnectionState{}, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))

	state := conn.ConnectionState()
	if _, err := conn.Write([]byte("x")); err != nil {
		return state, err
	}
	buf := make([]byte, 1)
	n, err := conn.Read(buf)
	if err != nil && !errors.Is(err, io.EOF) {
		return state, err
	}
	// An EOF with no byte is a refusal too: the server records its verdict and closes without
	// answering. Treating EOF as success would let a rejected connection report as an accepted one.
	if n != 1 {
		return state, errors.New("the server answered nothing")
	}
	return state, nil
}

// A certificate-less client that asks for the API is refused, and the refusal is the handshake's.
func TestCertlessClientReachesOnlyEnrolment(t *testing.T) {
	srv, window, _, _ := testBridge(t)
	window.Open(time.Minute)

	state, err := dial(t, srv, nil, []string{enroll.ProtoAPI})
	if err == nil {
		t.Fatalf("a certificate-less client negotiated %q", state.NegotiatedProtocol)
	}
}

// An open window is what lets an unauthenticated caller reach anything at all, so a closed one must
// leave `agterm/enroll-1` unoffered - and Go's ALPN then fails the handshake for a client that has
// nothing else to offer. The refusal is in the negotiation, not in a handler that decided to say no.
func TestCertlessClientIsRefusedWhenTheWindowIsClosed(t *testing.T) {
	srv, _, _, _ := testBridge(t)

	_, err := dial(t, srv, nil, []string{enroll.ProtoEnroll})
	if err == nil {
		t.Fatal("enrolment must not be offered while no window is open")
	}
	if !strings.Contains(err.Error(), "no application protocol") {
		t.Fatalf("the refusal must be the ALPN negotiation's, got %v", err)
	}
}

func TestPinnedClientReachesTheAPI(t *testing.T) {
	srv, _, phone, _ := testBridge(t)

	state, err := dial(t, srv, &phone, []string{enroll.ProtoAPI})
	if err != nil {
		t.Fatal(err)
	}
	if state.NegotiatedProtocol != enroll.ProtoAPI {
		t.Fatalf("want %q, got %q", enroll.ProtoAPI, state.NegotiatedProtocol)
	}
}

// The API branch is unchanged by an open window: a paired phone that asks for the API gets it while
// the owner has a pairing panel on screen.
func TestAnOpenWindowDoesNotDisturbTheAPI(t *testing.T) {
	srv, window, phone, _ := testBridge(t)
	window.Open(time.Minute)

	state, err := dial(t, srv, &phone, []string{enroll.ProtoAPI})
	if err != nil {
		t.Fatal(err)
	}
	if state.NegotiatedProtocol != enroll.ProtoAPI {
		t.Fatalf("want %q, got %q", enroll.ProtoAPI, state.NegotiatedProtocol)
	}
}

func TestUnpinnedClientCertificateIsRefused(t *testing.T) {
	srv, _, _, _ := testBridge(t)
	stranger := mintIdentity(t)

	if _, err := dial(t, srv, &stranger, []string{enroll.ProtoAPI}); err == nil {
		t.Fatal("a certificate that is not pinned must be refused")
	}
}

// An open window does not widen the API branch either: a stranger's certificate is still refused
// while the owner is pairing.
func TestUnpinnedClientCertificateIsRefusedWhileAWindowIsOpen(t *testing.T) {
	srv, window, _, _ := testBridge(t)
	window.Open(time.Minute)
	stranger := mintIdentity(t)

	if _, err := dial(t, srv, &stranger, []string{enroll.ProtoAPI}); err == nil {
		t.Fatal("an open enrolment window must not make an unpinned certificate acceptable")
	}
}

// The invariant, stated as a test rather than as a comment.
func TestNoCertificateCanEverMeanAPI(t *testing.T) {
	srv, window, _, _ := testBridge(t)
	window.Open(time.Minute)

	state, err := dial(t, srv, nil, []string{enroll.ProtoEnroll, enroll.ProtoAPI})
	if err != nil {
		t.Fatal(err)
	}
	if state.NegotiatedProtocol != enroll.ProtoEnroll {
		t.Fatalf("a certificate-less connection negotiated %q", state.NegotiatedProtocol)
	}
}

// The same offer in the other order, because ALPN preference is the server's and this is what says
// so. The branch returns a config carrying exactly one protocol, so the client's ordering has
// nothing to select from.
func TestNoCertificateCanEverMeanAPIInEitherOrder(t *testing.T) {
	srv, window, _, _ := testBridge(t)
	window.Open(time.Minute)

	state, err := dial(t, srv, nil, []string{enroll.ProtoAPI, enroll.ProtoEnroll})
	if err != nil {
		t.Fatal(err)
	}
	if state.NegotiatedProtocol != enroll.ProtoEnroll {
		t.Fatalf("a certificate-less connection negotiated %q", state.NegotiatedProtocol)
	}
}

// An empty certificate chain is not a certificate. The API branch requires one, and the pinned
// verifier requires exactly one, so a client that sends an empty list is refused by both.
func TestAnEmptyCertificateChainIsRefused(t *testing.T) {
	srv, window, _, _ := testBridge(t)
	window.Open(time.Minute)

	cfg := clientConfig(srv.cert, nil, []string{enroll.ProtoAPI})
	cfg.GetClientCertificate = func(*tls.CertificateRequestInfo) (*tls.Certificate, error) {
		return &tls.Certificate{}, nil
	}

	if _, err := dialWith(t, srv, cfg); err == nil {
		t.Fatal("an empty certificate chain must not reach the API")
	}
}

// A certificate that would fail verification, offered on the enrolment branch, does not become an
// API connection: the enrolment branch never asks for a certificate, so the bytes are never sent and
// there is nothing for a verifier to be talked out of.
func TestAFailingCertificateOnTheEnrolmentBranchStillOnlyEnrols(t *testing.T) {
	srv, window, _, _ := testBridge(t)
	window.Open(time.Minute)
	stranger := mintIdentity(t)

	state, err := dial(t, srv, &stranger, []string{enroll.ProtoEnroll, enroll.ProtoAPI})
	if err != nil {
		t.Fatal(err)
	}
	if state.NegotiatedProtocol != enroll.ProtoEnroll {
		t.Fatalf("want %q, got %q", enroll.ProtoEnroll, state.NegotiatedProtocol)
	}
	for _, got := range srv.completed() {
		if len(got.PeerCertificates) != 0 {
			t.Fatalf("the enrolment branch must not collect a client certificate, got %d",
				len(got.PeerCertificates))
		}
	}
}

// A client that offers no ALPN at all lands on the API branch, which is the strict one: with no
// certificate it is refused there.
func TestAClientOfferingNoProtocolIsHeldToTheAPIBranch(t *testing.T) {
	srv, window, _, _ := testBridge(t)
	window.Open(time.Minute)

	if _, err := dial(t, srv, nil, nil); err == nil {
		t.Fatal("a certificate-less client with no ALPN must not be served")
	}
}

// A protocol nobody serves is not a way in either, with or without a window.
func TestAnInventedProtocolIsRefused(t *testing.T) {
	srv, window, _, _ := testBridge(t)
	window.Open(time.Minute)

	if _, err := dial(t, srv, nil, []string{"agterm/enroll-2", "h2"}); err == nil {
		t.Fatal("an unknown protocol must not be negotiated")
	}
}

// The invariant over a matrix rather than over one case: whatever was offered, in whatever order,
// with whatever certificate, no COMPLETED server-side handshake may pair `agterm/api-1` with an
// anonymous peer.
//
// Asserted on the server's own connection state, and asserted over every attempt at once, so a case
// added to the table below is covered without a new assertion.
func TestTheServerNeverPairsTheAPIWithAnAnonymousPeer(t *testing.T) {
	srv, window, phone, _ := testBridge(t)
	stranger := mintIdentity(t)

	offers := [][]string{
		nil,
		{enroll.ProtoAPI},
		{enroll.ProtoEnroll},
		{enroll.ProtoAPI, enroll.ProtoEnroll},
		{enroll.ProtoEnroll, enroll.ProtoAPI},
		{enroll.ProtoEnroll, enroll.ProtoEnroll},
		{"h2", enroll.ProtoAPI},
		{"h2", enroll.ProtoEnroll},
	}
	identities := []*tls.Certificate{nil, &stranger, &phone}

	for _, open := range []bool{false, true} {
		if open {
			window.Open(time.Minute)
		} else {
			window.Close()
		}
		for _, offer := range offers {
			for _, own := range identities {
				// Every outcome is legal here except the one asserted below, so the error is
				// deliberately not checked: a refusal is as acceptable an answer as a negotiation.
				_, _ = dial(t, srv, own, offer)
			}
		}
	}

	completed := srv.completed()
	if len(completed) == 0 {
		t.Fatal("no handshake completed, so this test asserted nothing")
	}
	for _, state := range completed {
		if state.NegotiatedProtocol != enroll.ProtoAPI {
			continue
		}
		if len(state.PeerCertificates) != 1 {
			t.Fatalf("%q was negotiated with %d peer certificates",
				enroll.ProtoAPI, len(state.PeerCertificates))
		}
	}
}

// Closing the window MID-HANDSHAKE does not promote the connection.
//
// The hook is what makes this exact rather than approximate. The client's own pin verifier runs on the
// server's certificate, which the server sends in answer to the ClientHello - so it fires strictly
// AFTER the server chose its branch and strictly BEFORE either side has finished. Shutting the window
// from in there closes it in the middle of a handshake that has already been routed to enrolment.
//
// What the connection negotiated is fixed at the handshake, so the answer is that it stays on
// enrolment: there is no protocol to renegotiate into, and the certificate the API branch needs was
// never requested. The next caller is refused, because the offer is gone.
func TestClosingTheWindowMidConnectionDoesNotReachTheAPI(t *testing.T) {
	base := time.Now()
	var elapsed atomic.Int64
	clock := func() time.Time { return base.Add(time.Duration(elapsed.Load())) }

	srv, window, _, _ := testBridgeAt(t, clock)
	window.Open(time.Minute)

	cfg := clientConfig(srv.cert, nil, []string{enroll.ProtoEnroll, enroll.ProtoAPI})
	pin := cfg.VerifyPeerCertificate
	cfg.VerifyPeerCertificate = func(raw [][]byte, chains [][]*x509.Certificate) error {
		// Mid-handshake: the branch has been chosen and nothing has finished.
		elapsed.Store(int64(time.Hour))
		return pin(raw, chains)
	}

	state, err := dialWith(t, srv, cfg)
	if err != nil {
		t.Fatal(err)
	}
	if state.NegotiatedProtocol != enroll.ProtoEnroll {
		t.Fatalf("want %q, got %q", enroll.ProtoEnroll, state.NegotiatedProtocol)
	}
	if window.IsOpen() {
		t.Fatal("the window was supposed to have expired by now")
	}
	// And with the window gone, the same offer is refused.
	if _, err := dial(t, srv, nil, []string{enroll.ProtoEnroll}); err == nil {
		t.Fatal("enrolment must not be offered after the window has expired")
	}
}

// The reason `peers` is a function: a phone written to the trust store while the listener is up is
// accepted by the next handshake, with no restart. This is the property the startup snapshot could
// not have.
func TestAPhoneWrittenToTheStoreIsAcceptedWithoutARestart(t *testing.T) {
	srv, _, _, store := testBridge(t)
	second, secondCert := mint(t, "a second phone")

	if _, err := dial(t, srv, &second, []string{enroll.ProtoAPI}); err == nil {
		t.Fatal("a phone that is not in the store must be refused")
	}

	if err := store.Add(trust.Peer{
		Fingerprint:    pinning.Fingerprint(secondCert),
		CertificateDER: secondCert.Raw,
		PairedAt:       time.Unix(2, 0),
	}); err != nil {
		t.Fatal(err)
	}

	state, err := dial(t, srv, &second, []string{enroll.ProtoAPI})
	if err != nil {
		t.Fatalf("a phone added while the listener was up must be accepted: %v", err)
	}
	if state.NegotiatedProtocol != enroll.ProtoAPI {
		t.Fatalf("want %q, got %q", enroll.ProtoAPI, state.NegotiatedProtocol)
	}
}

// Unpairing works the same way round, which is the half that matters more: a certificate removed
// from the store stops being accepted by the next handshake rather than at the next restart.
func TestARemovedPhoneStopsBeingAcceptedWithoutARestart(t *testing.T) {
	srv, _, phone, store := testBridge(t)

	if _, err := dial(t, srv, &phone, []string{enroll.ProtoAPI}); err != nil {
		t.Fatal(err)
	}

	leaf, err := x509.ParseCertificate(phone.Certificate[0])
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Remove(pinning.Fingerprint(leaf)); err != nil {
		t.Fatal(err)
	}

	if _, err := dial(t, srv, &phone, []string{enroll.ProtoAPI}); err == nil {
		t.Fatal("an unpaired phone must be refused by the next handshake")
	}
}

// countingSource is a trust store's two read methods, counting how often each is called.
type countingSource struct {
	mu    sync.Mutex
	peers []trust.Peer
	certs []*x509.Certificate
	reads int
	parse int
}

func (c *countingSource) Peers() []trust.Peer {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.reads++
	return c.peers
}

func (c *countingSource) Certificates() []*x509.Certificate {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.parse++
	return c.certs
}

func (c *countingSource) set(peers []trust.Peer, certs []*x509.Certificate) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.peers = peers
	c.certs = certs
}

func (c *countingSource) counts() (reads, parse int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.reads, c.parse
}

func peerOf(cert *x509.Certificate) trust.Peer {
	return trust.Peer{Fingerprint: pinning.Fingerprint(cert), CertificateDER: cert.Raw}
}

// The parse happens once per version of the list, not once per handshake - and it happens again the
// moment the list changes, which is what makes the cache impossible to serve stale from.
//
// "Parsed once" is asserted through identity: a cached parse hands back the same *x509.Certificate
// every time, and a fresh one cannot. The store's own Certificates is asserted to be called ZERO
// times, because asking the store a second question about the same moment is the race that made this
// type wrong the first time.
func TestPinnedClientsParsesOncePerVersionOfTheList(t *testing.T) {
	_, first := mint(t, "first phone")
	_, second := mint(t, "second phone")

	src := &countingSource{}
	src.set([]trust.Peer{peerOf(first)}, []*x509.Certificate{first})
	cache := enroll.NewPinnedClients(src)

	var parsed *x509.Certificate
	for i := 0; i < 5; i++ {
		got := cache.Certificates()
		if len(got) != 1 || !bytesEqual(got[0].Raw, first.Raw) {
			t.Fatalf("handshake %d got the wrong list: %+v", i, got)
		}
		if i == 0 {
			parsed = got[0]
			continue
		}
		if got[0] != parsed {
			t.Fatalf("handshake %d re-parsed a list that had not changed", i)
		}
	}
	reads, parse := src.counts()
	if parse != 0 {
		t.Fatalf("the cache must never ask the store to parse, asked %d times", parse)
	}
	if reads != 5 {
		t.Fatalf("the store must be consulted per handshake, consulted %d times", reads)
	}

	// A phone enrols. The next handshake sees a list whose bytes differ from the cached one, so it
	// reparses - nothing had to remember to invalidate anything.
	src.set([]trust.Peer{peerOf(first), peerOf(second)}, []*x509.Certificate{first, second})
	got := cache.Certificates()
	if len(got) != 2 {
		t.Fatalf("a phone that enrolled is not in the list: %+v", got)
	}
	if got[0] == parsed {
		t.Fatal("a changed list must be parsed again")
	}
	if !bytesEqual(got[1].Raw, second.Raw) {
		t.Fatal("the phone that enrolled is not the one in the list")
	}

	// And unpairing everybody is a change like any other: the list the cache hands out is empty,
	// which accepts nobody.
	src.set(nil, nil)
	if got := cache.Certificates(); len(got) != 0 {
		t.Fatalf("an emptied store must hand out an empty list, got %+v", got)
	}
	if _, parse := src.counts(); parse != 0 {
		t.Fatalf("the cache must never ask the store to parse, asked %d times", parse)
	}
}

// A stored certificate that no longer parses is dropped rather than served as a nil, and the list
// keeps working for whoever is still valid.
func TestPinnedClientsDropsWhatNoLongerParses(t *testing.T) {
	_, first := mint(t, "first phone")

	src := &countingSource{}
	src.set([]trust.Peer{
		{Fingerprint: "broken", CertificateDER: []byte{0x30, 0x00, 0x01}},
		peerOf(first),
	}, nil)
	cache := enroll.NewPinnedClients(src)

	got := cache.Certificates()
	if len(got) != 1 || !bytesEqual(got[0].Raw, first.Raw) {
		t.Fatalf("want only the certificate that parses, got %d", len(got))
	}
}

// The cached slice is a copy, so a caller that appends to what it was given cannot grow what the next
// handshake trusts.
func TestPinnedClientsHandsOutACopyOfTheList(t *testing.T) {
	_, first := mint(t, "first phone")
	_, stranger := mint(t, "somebody else")

	src := &countingSource{}
	src.set([]trust.Peer{peerOf(first)}, []*x509.Certificate{first})
	cache := enroll.NewPinnedClients(src)

	got := cache.Certificates()
	got = append(got[:1:1], stranger)
	if len(got) != 2 {
		t.Fatalf("the test did not append: %+v", got)
	}
	if again := cache.Certificates(); len(again) != 1 || !bytesEqual(again[0].Raw, first.Raw) {
		t.Fatalf("the cached list was reachable from outside: %+v", again)
	}
}

// The cache is on the accept path, which is concurrent by nature, and the store can be written to
// from pairing at the same time. -race is what reads this one.
func TestPinnedClientsIsSafeUnderConcurrentUse(t *testing.T) {
	_, first := mint(t, "first phone")
	_, second := mint(t, "second phone")

	src := &countingSource{}
	src.set([]trust.Peer{peerOf(first)}, []*x509.Certificate{first})
	cache := enroll.NewPinnedClients(src)

	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 50; j++ {
				if got := cache.Certificates(); len(got) == 0 {
					t.Error("the cache handed out an empty list")
					return
				}
			}
		}()
	}
	wg.Add(1)
	go func() {
		defer wg.Done()
		for j := 0; j < 50; j++ {
			if j%2 == 0 {
				src.set([]trust.Peer{peerOf(first), peerOf(second)},
					[]*x509.Certificate{first, second})
			} else {
				src.set([]trust.Peer{peerOf(first)}, []*x509.Certificate{first})
			}
		}
	}()
	wg.Wait()
}

// recorder is the API handler, counting what reached it.
type recorder struct {
	mu    sync.Mutex
	calls int
}

func (r *recorder) Handle(context.Context, api.Request) api.Response {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.calls++
	return api.Response{OK: true}
}

func (r *recorder) count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.calls
}

// The config drops into the real listener, and the ALPN refusal travels the listener's ONE refusal
// path: a failed handshake, closed without the handler being reached.
//
// This is the wiring main.go has, end to end - listener.New over enroll.ServerConfigFor - so the two
// branches are exercised where they will actually run rather than against a bare tls.Server.
func TestTheConfigDropsIntoTheListener(t *testing.T) {
	log.SetOutput(io.Discard)

	bridgeOwn, bridgeCert := mint(t, "agterm-remote bridge")
	phoneOwn, phoneCert := mint(t, "agterm-remote phone")

	store, err := trust.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Add(peerOf(phoneCert)); err != nil {
		t.Fatal(err)
	}

	window := enroll.NewWindow(time.Now)
	cfg := enroll.ServerConfigFor(bridgeOwn, enroll.NewPinnedClients(store).Certificates, window)

	rec := &recorder{}
	srv := listener.New(cfg, rec, true)
	tcp, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	go func() { _ = srv.Serve(ctx, tcp) }()
	t.Cleanup(func() { cancel(); _ = tcp.Close() })

	addr := tcp.Addr().String()

	// A certificate-less caller with only enrolment to offer, and no window: refused in the
	// handshake, and the handler never learns it existed.
	anon, err := tls.Dial("tcp", addr, clientConfig(bridgeCert, nil, []string{enroll.ProtoEnroll}))
	if err == nil {
		_ = anon.SetDeadline(time.Now().Add(10 * time.Second))
		if _, err := anon.Write([]byte("{}\n")); err == nil {
			buf := make([]byte, 1)
			_, err = anon.Read(buf)
		}
		anon.Close()
		if err == nil {
			t.Fatal("a certificate-less caller was served by the listener")
		}
	}

	// And the paired phone's request goes through the API branch.
	conn, err := tls.Dial("tcp", addr, clientConfig(bridgeCert, &phoneOwn, []string{enroll.ProtoAPI}))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
	if state := conn.ConnectionState(); state.NegotiatedProtocol != enroll.ProtoAPI {
		t.Fatalf("want %q, got %q", enroll.ProtoAPI, state.NegotiatedProtocol)
	}
	if _, err := conn.Write([]byte(`{"verb":"sessions"}` + "\n")); err != nil {
		t.Fatal(err)
	}
	var resp api.Response
	if err := json.NewDecoder(conn).Decode(&resp); err != nil {
		t.Fatal(err)
	}
	if !resp.OK {
		t.Fatalf("the API branch refused a paired phone: %+v", resp)
	}
	if rec.count() != 1 {
		t.Fatalf("want exactly the paired phone's one request, got %d", rec.count())
	}
}

// A session ticket earned on the enrolment branch cannot be resumed into the API.
//
// This is the one route by which a completed anonymous handshake could plausibly turn into an
// authenticated one later: TLS 1.3 resumption restores the peer's certificates from the ticket
// instead of asking for them again, so a ticket carrying NO certificates, replayed against the
// branch that requires one, is the shape of the attack. It is exercised rather than reasoned about,
// because the answer is Go's and not this package's.
//
// Both offers are tried on the resumption: the API alone, and both protocols with the window shut.
func TestAnEnrolmentTicketCannotBeResumedIntoTheAPI(t *testing.T) {
	srv, window, _, _ := testBridge(t)
	window.Open(time.Minute)

	// One shared client configuration, so the ticket the enrolment handshake earns is offered by the
	// dials that follow.
	cfg := clientConfig(srv.cert, nil, []string{enroll.ProtoEnroll})
	cfg.ClientSessionCache = tls.NewLRUClientSessionCache(4)

	state, err := dialWith(t, srv, cfg)
	if err != nil {
		t.Fatal(err)
	}
	if state.NegotiatedProtocol != enroll.ProtoEnroll {
		t.Fatalf("want %q, got %q", enroll.ProtoEnroll, state.NegotiatedProtocol)
	}

	window.Close()

	for _, offer := range [][]string{
		{enroll.ProtoAPI},
		{enroll.ProtoAPI, enroll.ProtoEnroll},
	} {
		resume := cfg.Clone()
		resume.NextProtos = offer
		if _, err := dialWith(t, srv, resume); err == nil {
			t.Fatalf("a ticket with no certificate behind it was resumed into the API, offering %v", offer)
		}
	}

	// And the server agrees, on every handshake it completed: nothing reached the API anonymously,
	// resumed or fresh.
	for _, got := range srv.completed() {
		if got.NegotiatedProtocol == enroll.ProtoAPI && len(got.PeerCertificates) == 0 {
			t.Fatalf("%q was completed with no certificate (resumed: %t)",
				enroll.ProtoAPI, got.DidResume)
		}
	}
}

// An unpaired phone must not be able to resume its way back in.
//
// # The hole this was written for
//
// Session resumption skips the only check this project has. On a resumed TLS 1.3 handshake Go sets
// usingPSK, requestClientCert() is false, and VerifyPeerCertificate NEVER RUNS - the peer's
// certificate is restored from the ticket with only a NotAfter check. The ticket keys live on the
// long-lived outer config rather than on the one GetConfigForClient returns, so a ticket earned on
// one connection is offered on the next.
//
// Measured before the fix: pair a phone, connect, Remove it from the store, reconnect with the same
// ClientSessionCache - and the unpaired phone is SERVED, resumed=true, proto=agterm/api-1. The trust
// store said no and the handshake never asked it. Up to seven days of that, which is Go's default
// ticket lifetime.
//
// The whole suite stayed green through it because every other test in this file dials with a fresh
// session cache. The shared cache is the point of this test, and so is asserting DidResume: a
// refusal for the right reason and a refusal because resumption silently stopped working are
// different facts, and only one of them survives a future Go release.
func TestAnUnpairedPhoneCannotResumeItsWayBackIn(t *testing.T) {
	srv, _, phone, store := testBridge(t)

	cfg := clientConfig(srv.cert, &phone, []string{enroll.ProtoAPI})
	cfg.ClientSessionCache = tls.NewLRUClientSessionCache(4)

	state, err := dialWith(t, srv, cfg)
	if err != nil {
		t.Fatal(err)
	}
	if state.NegotiatedProtocol != enroll.ProtoAPI {
		t.Fatalf("want %q, got %q", enroll.ProtoAPI, state.NegotiatedProtocol)
	}

	leaf, err := x509.ParseCertificate(phone.Certificate[0])
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Remove(pinning.Fingerprint(leaf)); err != nil {
		t.Fatal(err)
	}

	// The same client, the same cache, one ticket in hand.
	resumed, err := dialWith(t, srv, cfg)
	if err == nil {
		t.Fatalf("an unpaired phone was served, resumed=%t proto=%q",
			resumed.DidResume, resumed.NegotiatedProtocol)
	}
	if resumed.DidResume {
		t.Fatalf("the handshake resumed, so the pinned verifier never ran: %v", err)
	}

	// And the owner's own reconnection still works, ticket or no ticket - what was given up is a
	// round trip, not the connection.
	second, secondCert := mint(t, "a phone that is still paired")
	if err := store.Add(trust.Peer{
		Fingerprint:    pinning.Fingerprint(secondCert),
		CertificateDER: secondCert.Raw,
		PairedAt:       time.Unix(3, 0),
	}); err != nil {
		t.Fatal(err)
	}
	live := clientConfig(srv.cert, &second, []string{enroll.ProtoAPI})
	live.ClientSessionCache = tls.NewLRUClientSessionCache(4)
	for i := 0; i < 3; i++ {
		state, err := dialWith(t, srv, live)
		if err != nil {
			t.Fatalf("reconnection %d: %v", i, err)
		}
		if state.DidResume {
			t.Fatalf("reconnection %d resumed, so the verifier was skipped", i)
		}
		if state.NegotiatedProtocol != enroll.ProtoAPI {
			t.Fatalf("reconnection %d negotiated %q", i, state.NegotiatedProtocol)
		}
	}
}

// The cache must never hold a certificate the store does not.
//
// # The interleaving this was written for
//
// The first shape of this cache read the key with Peers() and then the certificates with
// Certificates(), which is two reads of a store that can be written to between them. "Key first"
// stops an OLDER parse being filed under a NEWER key. It permits the mirror image: key read at [A],
// a write lands, certificates read as [A, B] - and the cache holds {key: [A], certs: [A, B]}. The
// owner then unpairs B, the store returns to [A], the key MATCHES, and every later handshake trusts
// B forever. An attacker who can enrol once and then hammer connections drives both sides of that
// race, and the prize is surviving an unpair.
//
// The fix is not a tighter comparison, it is ONE snapshot: the cache parses the DER that came back
// from Peers() and never asks the store a second question. This test drives the interleaving through
// the seam - Peers returns [A] and, on its way out, makes any later Certificates() call answer
// [A, B] - so it fails against a two-read cache and passes against a one-read one.
func TestTheCacheNeverHoldsWhatTheStoreDoesNot(t *testing.T) {
	_, first := mint(t, "the owner's phone")
	_, second := mint(t, "a phone that enrolled mid-read")

	src := &racingSource{}
	src.set([]trust.Peer{peerOf(first)}, []*x509.Certificate{first})
	// The write that lands between the two reads: whatever asks for certificates after Peers() has
	// answered gets the longer list.
	src.betweenReads = func() {
		src.certs = []*x509.Certificate{first, second}
	}

	cache := enroll.NewPinnedClients(src)
	// What this first call returns is not the point - the store genuinely did grow while it ran, so
	// either answer is defensible. What is filed under the key is.
	t.Logf("the handshake that raced the write saw %d certificates", len(cache.Certificates()))

	// The store is back to [A] - either because the second phone was unpaired, or because it was
	// never in the list this call keyed on. Either way the key matches and the cached parse is what
	// every later handshake gets.
	src.betweenReads = nil
	src.set([]trust.Peer{peerOf(first)}, []*x509.Certificate{first})

	got := cache.Certificates()
	if len(got) != 1 {
		t.Fatalf("the cache holds %d certificates for a store holding 1", len(got))
	}
	if !bytesEqual(got[0].Raw, first.Raw) {
		t.Fatal("the cache holds a certificate that is not the one in the store")
	}
}

// racingSource lets a test land a write between the cache's reads.
type racingSource struct {
	mu           sync.Mutex
	peers        []trust.Peer
	certs        []*x509.Certificate
	betweenReads func()
}

func (r *racingSource) Peers() []trust.Peer {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := r.peers
	if r.betweenReads != nil {
		r.betweenReads()
	}
	return out
}

func (r *racingSource) Certificates() []*x509.Certificate {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.certs
}

func (r *racingSource) set(peers []trust.Peer, certs []*x509.Certificate) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.peers = peers
	r.certs = certs
}

func bytesEqual(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
