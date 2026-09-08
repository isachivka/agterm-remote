package listener

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
	"sync/atomic"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/api"
	"github.com/isachivka/agterm-remote/bridge/internal/pinning"
)

// recorder answers requests and, more importantly, records that it was reached at all.
type recorder struct {
	calls atomic.Int32
	seen  atomic.Value // last api.Request
}

func (r *recorder) Handle(_ context.Context, req api.Request) api.Response {
	r.calls.Add(1)
	r.seen.Store(req)
	return api.Response{OK: true}
}

type harness struct {
	addr       string
	srv        *Server
	rec        *recorder
	phoneOwn   tls.Certificate
	bridgeCert *x509.Certificate
	phoneCert  *x509.Certificate
}

func start(t *testing.T) *harness {
	t.Helper()
	log.SetOutput(io.Discard)

	bridgeID, err := pinning.Mint("bridge", time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	phoneID, err := pinning.Mint("phone", time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	bridgeOwn, err := pinning.LoadIdentity(bridgeID)
	if err != nil {
		t.Fatal(err)
	}
	phoneOwn, err := pinning.LoadIdentity(phoneID)
	if err != nil {
		t.Fatal(err)
	}
	bridgeCert, err := pinning.LoadPeer(bridgeID.CertPEM)
	if err != nil {
		t.Fatal(err)
	}
	phoneCert, err := pinning.LoadPeer(phoneID.CertPEM)
	if err != nil {
		t.Fatal(err)
	}

	rec := &recorder{}
	srv := New(pinning.ServerConfig(bridgeOwn, phoneCert), rec, true)

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	go func() { _ = srv.Serve(ctx, ln) }()
	t.Cleanup(func() { cancel(); ln.Close() })

	return &harness{addr: ln.Addr().String(), srv: srv, rec: rec,
		phoneOwn: phoneOwn, bridgeCert: bridgeCert, phoneCert: phoneCert}
}

// --- The invariant -------------------------------------------------------------------------------

// The write half of the invariant, tested over PLAIN TCP with no TLS at all.
//
// This shape was arrived at the hard way. The first version did the whole thing through tls.Dial and
// treated a dial failure as success — but a banner written before the handshake makes the CLIENT's
// dial fail too, because its TLS layer reads the greeting as a malformed record. So the test passed
// with a banner inserted: vacuous in precisely the way it was written to prevent.
//
// Plain TCP removes the ambiguity. Connect, send nothing, and read. A bridge that holds the invariant
// says nothing at all until it has seen a ClientHello and verified what followed, so the read must
// time out empty. Any byte here is a banner, a greeting, or an error message, and any of those is the
// harmless-looking edit this test exists to catch.
func TestNothingIsWrittenBeforeTheHandshake(t *testing.T) {
	h := start(t)

	conn, err := net.Dial("tcp", h.addr)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()

	// Comfortably longer than a local server needs to say something, comfortably shorter than the
	// bridge's own handshake timeout, so a clean run is a timeout rather than a disconnect.
	_ = conn.SetReadDeadline(time.Now().Add(750 * time.Millisecond))
	buf := make([]byte, 256)
	n, readErr := conn.Read(buf)

	if n > 0 {
		t.Fatalf("the bridge wrote %d byte(s) before any handshake: %q\n"+
			"nothing may be written to a connection before HandshakeContext returns nil", n, buf[:n])
	}
	var netErr net.Error
	if !errors.As(readErr, &netErr) || !netErr.Timeout() {
		t.Fatalf("expected a silent connection to time out, got %v", readErr)
	}
}

// The read half: an unverified caller's application data must never reach the handler.
//
// Under TLS 1.3 client authentication rides in the client's own last flight, so a caller's
// certificate and its first application data can arrive together and an unverified peer's bytes can
// be sitting in the socket before the verifier runs. Go does not hand them over — this asserts that
// the bridge does not either, and would fail if the accept path ever read before verifying.
func TestUnverifiedApplicationDataNeverReachesTheHandler(t *testing.T) {
	h := start(t)
	_, strangerOwn, _ := mintPeer(t, "somebody else")

	conn, err := tls.Dial("tcp", h.addr, &tls.Config{
		Certificates:       []tls.Certificate{strangerOwn},
		MinVersion:         tls.VersionTLS13,
		InsecureSkipVerify: true, //nolint:gosec // the test IS the attacker
	})
	if err == nil {
		// TLS 1.3 lets the client finish first, so this is the normal path: write immediately.
		_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
		_, _ = conn.Write([]byte(`{"verb":"sessions"}` + "\n"))
		_, _ = conn.Read(make([]byte, 1))
		conn.Close()
	}
	// Give the server's goroutine time to have done the wrong thing, if it were going to.
	time.Sleep(200 * time.Millisecond)

	if got := h.rec.calls.Load(); got != 0 {
		t.Fatalf("an unverified caller's request reached the handler %d time(s)", got)
	}
}

// The pinned phone does get through, so the test above is not passing because nothing works.
func TestPinnedCallerIsServed(t *testing.T) {
	h := start(t)

	conn := dialPinned(t, h)
	defer conn.Close()

	resp := roundTrip(t, conn, `{"verb":"sessions"}`)
	if !resp.OK {
		t.Fatalf("pinned caller was refused: %s", resp.Error)
	}
	if h.rec.calls.Load() != 1 {
		t.Fatalf("expected exactly one handler call, got %d", h.rec.calls.Load())
	}
}

// The connection is persistent: a full TLS handshake per poll would cost two round trips over a
// mobile network on every screen read.
func TestConnectionServesManyRequests(t *testing.T) {
	h := start(t)

	conn := dialPinned(t, h)
	defer conn.Close()

	for i := 0; i < 5; i++ {
		if resp := roundTrip(t, conn, `{"verb":"sessions"}`); !resp.OK {
			t.Fatalf("request %d refused: %s", i, resp.Error)
		}
	}
	if got := h.rec.calls.Load(); got != 5 {
		t.Fatalf("expected 5 handler calls on one connection, got %d", got)
	}
}

// A malformed request is answered, not fatal — the caller is authenticated by this point.
func TestMalformedRequestIsAnsweredWithoutReachingTheHandler(t *testing.T) {
	h := start(t)

	conn := dialPinned(t, h)
	defer conn.Close()

	resp := roundTrip(t, conn, `{"verb":"sessions","surprise":1}`)
	if resp.OK {
		t.Fatal("an unknown field must be refused, not ignored")
	}
	if h.rec.calls.Load() != 0 {
		t.Fatal("a request that failed to decode must not reach the handler")
	}
}

// --- Failed handshakes cost nothing that accumulates ---------------------------------------------

// One line per failed handshake would give an anonymous caller an unbounded write primitive against
// the owner's disk. This asserts the aggregate: many failures, at most one line.
func TestFailedHandshakesAreCountedNotLogged(t *testing.T) {
	h := start(t)
	_, strangerOwn, _ := mintPeer(t, "stranger")

	var written countingWriter
	log.SetOutput(&written)
	t.Cleanup(func() { log.SetOutput(io.Discard) })

	for i := 0; i < 20; i++ {
		conn, err := tls.Dial("tcp", h.addr, &tls.Config{
			Certificates:       []tls.Certificate{strangerOwn},
			MinVersion:         tls.VersionTLS13,
			InsecureSkipVerify: true, //nolint:gosec // the test IS the attacker
		})
		if err == nil {
			_ = conn.SetDeadline(time.Now().Add(time.Second))
			_, _ = conn.Read(make([]byte, 1))
			conn.Close()
		}
	}
	// Let the server's goroutines record their failures.
	time.Sleep(200 * time.Millisecond)

	if n := written.lines.Load(); n != 0 {
		t.Fatalf("20 failed handshakes wrote %d log line(s) within the window; "+
			"failures must be counted and reported once per window, not once per attempt", n)
	}
}

// The per-source counter stops answering a source that keeps failing, and it does so before the
// handshake — the expensive part.
func TestRepeatedFailuresFromOneSourceStopBeingAnswered(t *testing.T) {
	f := newFailureCounter()
	const source = "203.0.113.9"

	for i := 0; i < failuresPerSource; i++ {
		if f.blocked(source) {
			t.Fatalf("blocked after only %d failures", i)
		}
		f.record(source, true)
	}
	if !f.blocked(source) {
		t.Fatalf("a source must stop being answered after %d failures", failuresPerSource)
	}
}

// A caller with many addresses must not be able to grow the table without limit, and must not be able
// to use its size to lock the owner out either.
func TestPerSourceTableIsBounded(t *testing.T) {
	f := newFailureCounter()

	for i := 0; i < trackedSources*3; i++ {
		f.record(net.IPv4(10, byte(i>>16), byte(i>>8), byte(i)).String(), true)
	}

	f.mu.Lock()
	size := len(f.perSource)
	total := f.total
	f.mu.Unlock()

	if size > trackedSources {
		t.Fatalf("per-source table grew to %d, above the %d cap", size, trackedSources)
	}
	if total != trackedSources*3 {
		t.Fatalf("the aggregate must count every failure, got %d", total)
	}
}

// --- helpers -------------------------------------------------------------------------------------

func mintPeer(t *testing.T, name string) (pinning.Identity, tls.Certificate, *x509.Certificate) {
	t.Helper()
	id, err := pinning.Mint(name, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	own, err := pinning.LoadIdentity(id)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := pinning.LoadPeer(id.CertPEM)
	if err != nil {
		t.Fatal(err)
	}
	return id, own, cert
}

func dialPinned(t *testing.T, h *harness) *tls.Conn {
	t.Helper()
	conn, err := tls.Dial("tcp", h.addr, pinning.ClientConfig(h.phoneOwn, h.bridgeCert))
	if err != nil {
		t.Fatalf("the pinned phone must reach the bridge: %v", err)
	}
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	return conn
}

func roundTrip(t *testing.T, conn net.Conn, request string) api.Response {
	t.Helper()
	if _, err := conn.Write([]byte(request + "\n")); err != nil {
		t.Fatalf("write: %v", err)
	}
	var resp api.Response
	if err := json.NewDecoder(io.LimitReader(conn, 1<<20)).Decode(&resp); err != nil {
		t.Fatalf("read response: %v", err)
	}
	return resp
}

type countingWriter struct{ lines atomic.Int32 }

func (c *countingWriter) Write(p []byte) (int, error) {
	c.lines.Add(1)
	return len(p), nil
}

// --- No anonymous caller may cause a legitimate one to be refused --------------------------------

// The regression test for the blocking finding.
//
// An earlier version held a cumulative ceiling: past N failed handshakes in a window every source was
// turned away, which handed anyone on the internet a way to lock the owner out for the rest of the
// window at the cost of about three connections a second. The owner is on a rotating mobile address,
// so they would never have been the spared source either.
//
// Asserted on the counter directly rather than through a socket, because on loopback the attacker and
// the legitimate caller share an address and the per-source rule — which is meant to fire — would
// mask the property under test.
func TestFailuresFromOtherSourcesNeverBlockAnybodyElse(t *testing.T) {
	f := newFailureCounter()

	// Far more than any cumulative ceiling would have tolerated, from many addresses.
	for i := 0; i < 20000; i++ {
		f.record(net.IPv4(198, 51, byte(i>>8), byte(i)).String(), true)
	}

	if f.blocked("203.0.113.7") {
		t.Fatal("a source that has never failed must never be refused because other sources have; " +
			"a global condition here is what locked the owner out")
	}
}

// The other half: the source actually misbehaving is still punished.
func TestTheMisbehavingSourceIsStillBlocked(t *testing.T) {
	f := newFailureCounter()
	const bad = "203.0.113.9"

	for i := 0; i < failuresPerSource; i++ {
		f.record(bad, true)
	}
	if !f.blocked(bad) {
		t.Fatal("the address that failed must stop being answered")
	}
	if f.blocked("203.0.113.10") {
		t.Fatal("its neighbour must not be")
	}
}

// Saturation bounds work and has no memory: it lasts exactly as long as the traffic causing it.
//
// White-box, on the semaphore itself, and that is not a shortcut. On loopback the stalling
// connections and the legitimate caller share 127.0.0.1, so filling twelve slots trips the
// per-source rule at five and the legitimate caller is refused — correctly, by the OTHER mechanism.
// A socket-level assertion here would measure the per-source counter while claiming to measure the
// semaphore. (That is worth knowing in its own right: an attacker sharing the owner's NAT can trip
// the per-source rule. It costs the owner one window, and the alternative is not punishing a
// misbehaving source at all.)
//
// Slots are held only for the handshake, so connections that never send a ClientHello occupy them
// until closed — and closing them must restore capacity immediately, with no window to wait out.
func TestSaturationDoesNotOutliveTheTraffic(t *testing.T) {
	h := start(t)

	var stalled []net.Conn
	for i := 0; i < maxConcurrentHandshakes; i++ {
		c, err := net.Dial("tcp", h.addr)
		if err != nil {
			t.Fatalf("dial %d: %v", i, err)
		}
		stalled = append(stalled, c)
	}
	if !eventually(func() bool { return len(h.srv.slots) == maxConcurrentHandshakes }) {
		t.Fatalf("expected all %d slots taken, got %d", maxConcurrentHandshakes, len(h.srv.slots))
	}

	// One more while saturated: refused instantly rather than queued, and it takes no slot.
	extra, err := net.Dial("tcp", h.addr)
	if err == nil {
		extra.Close()
	}
	if got := len(h.srv.slots); got > maxConcurrentHandshakes {
		t.Fatalf("the semaphore must be a hard ceiling, got %d in flight", got)
	}

	for _, c := range stalled {
		c.Close()
	}

	// The property: capacity returns with the traffic, not on a timer. Nothing to wait out.
	if !eventually(func() bool { return len(h.srv.slots) == 0 }) {
		t.Fatalf("capacity must return the instant load drops; %d slots still held", len(h.srv.slots))
	}
}

func eventually(cond func() bool) bool {
	for i := 0; i < 100; i++ {
		if cond() {
			return true
		}
		time.Sleep(20 * time.Millisecond)
	}
	return cond()
}

// --- A caller who never claimed to be anybody is not a failed authentication ----------------------

// The app's own laptop-reachability probe is a bare TCP connect: it asks whether a packet reaches the
// laptop, and deliberately speaks no TLS. Counting that as a failed authentication would have the
// phone lock itself out of the bridge with its own liveness check — REQ-0006 and REQ-0007 run the
// reachability round on every foreground and on pull-to-refresh, so five in a minute is ordinary use.
func TestABareTCPConnectIsNotCountedAsAFailedAuthentication(t *testing.T) {
	h := start(t)

	for i := 0; i < failuresPerSource*3; i++ {
		conn, err := net.Dial("tcp", h.addr)
		if err != nil {
			t.Fatalf("dial %d: %v", i, err)
		}
		conn.Close()
	}
	if !eventually(func() bool { return len(h.srv.slots) == 0 }) {
		t.Fatal("slots must be released")
	}

	h.srv.failures.mu.Lock()
	recorded := h.srv.failures.total
	h.srv.failures.mu.Unlock()

	if recorded != 0 {
		t.Fatalf("%d bare TCP connects were counted as failed authentications; "+
			"a caller that never sent a ClientHello never claimed to be anybody", recorded)
	}
	if h.srv.failures.blocked("127.0.0.1") {
		t.Fatal("a liveness probe must not lock its own source out")
	}
}

// The other arm, and the one the tempting implementation gets right by accident: a ClientHello that
// arrives and then fails verification IS a failed authentication.
func TestAFailedVerificationIsCounted(t *testing.T) {
	h := start(t)
	_, strangerOwn, _ := mintPeer(t, "stranger")

	conn, err := tls.Dial("tcp", h.addr, &tls.Config{
		Certificates:       []tls.Certificate{strangerOwn},
		MinVersion:         tls.VersionTLS13,
		InsecureSkipVerify: true, //nolint:gosec // the test IS the attacker
	})
	if err == nil {
		_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
		_, _ = conn.Read(make([]byte, 1))
		conn.Close()
	}

	if !eventually(func() bool {
		h.srv.failures.mu.Lock()
		defer h.srv.failures.mu.Unlock()
		return h.srv.failures.total == 1
	}) {
		h.srv.failures.mu.Lock()
		got := h.srv.failures.total
		h.srv.failures.mu.Unlock()
		t.Fatalf("a certificate that failed verification must be counted, got total=%d", got)
	}
}

// --- The aggregate must survive a transport where sources are not keyed ---------------------------

// Gating the whole `record` call on peerIsCaller killed this, silently.
//
// The once-per-window line is the owner's only signal that somebody arrived with a certificate and
// was refused — a far more interesting event than a refused upgrade, which the front door counts
// separately. When it became unreachable it stayed in the source looking correct and always printed
// zero times, which is indistinguishable from nothing having happened.
func TestTheAggregateIsCountedEvenWhenSourcesAreNotKeyed(t *testing.T) {
	f := newFailureCounter()

	for i := 0; i < 12; i++ {
		f.record("192.168.1.1", false) // proxied: one peer for everybody, not keyed
	}

	f.mu.Lock()
	total, sources := f.total, len(f.perSource)
	f.mu.Unlock()

	if total != 12 {
		t.Fatalf("the aggregate must count on every transport, got %d", total)
	}
	if sources != 0 {
		t.Fatalf("an unkeyed source must not enter the table, got %d entries", sources)
	}
	if f.blocked("192.168.1.1") {
		t.Fatal("counting the aggregate must not block anybody")
	}
}

// "from 0 sources" reads as a measurement of zero rather than an absence of one.
func TestTheWindowLineDoesNotReportAMisleadingSourceCount(t *testing.T) {
	var written strings.Builder
	log.SetOutput(&written)
	log.SetFlags(0)
	t.Cleanup(func() { log.SetOutput(io.Discard) })

	f := newFailureCounter()
	for i := 0; i < 7; i++ {
		f.record("192.168.1.1", false)
	}
	f.flush()

	line := written.String()
	if !strings.Contains(line, "7") {
		t.Fatalf("the count must be reported, got %q", line)
	}
	if strings.Contains(line, "0 sources") {
		t.Fatalf("a source count of zero is misleading when none were keyed: %q", line)
	}
}

// **The caller still cannot tell why it was refused, and that has to survive the split.**
//
// `pinnedPeer` now returns ErrExpired separately from ErrNotPinned, because those mean different
// things to the OWNER: one machine is not the paired one, the other needs a new identity. The original
// reason for one error was different and is unchanged — a REJECTED CALLER must learn that it failed
// and nothing else.
//
// Those two live together only as long as the distinction stays on the laptop. This asserts it does:
// a stranger and an expired phone are refused identically as far as either can observe.
func TestARefusedCallerCannotTellExpiryFromBeingAStranger(t *testing.T) {
	h := start(t)

	// A phone the bridge has never pinned.
	strangerID, err := pinning.Mint("stranger", time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	stranger, err := pinning.LoadIdentity(strangerID)
	if err != nil {
		t.Fatal(err)
	}

	// The pinned phone, whose certificate has lapsed. `--lifetime` is what makes this expressible.
	expiredID, err := pinning.Mint("expired phone", 300*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	expired, err := pinning.LoadIdentity(expiredID)
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(400 * time.Millisecond)

	strangerSaw := whatTheCallerSees(t, h, stranger)
	expiredSaw := whatTheCallerSees(t, h, expired)

	if strangerSaw != expiredSaw {
		t.Fatalf("a caller could tell the two refusals apart:\n  stranger: %s\n  expired:  %s",
			strangerSaw, expiredSaw)
	}
	if strangerSaw == "" {
		t.Fatal("neither was refused at all, so this proved nothing")
	}
}

// whatTheCallerSees returns the client-visible failure, which is all a refused peer ever gets.
func whatTheCallerSees(t *testing.T, h *harness, own tls.Certificate) string {
	t.Helper()
	conn, err := tls.Dial("tcp", h.addr, pinning.ClientConfig(own, h.bridgeCert))
	if err != nil {
		return err.Error()
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	// TLS 1.3 finishes the client's handshake before the server has judged it, so the refusal
	// arrives on the first READ rather than from Dial.
	if _, err := conn.Write([]byte(`{"verb":"sessions"}` + "\n")); err != nil {
		return err.Error()
	}
	if _, err := conn.Read(make([]byte, 1)); err != nil {
		return err.Error()
	}
	return ""
}
