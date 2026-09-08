package enroll_test

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"io"
	"log"
	"net"
	"runtime"
	"sync/atomic"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
	"github.com/isachivka/agterm-remote/bridge/internal/agterm/agtermtest"
	"github.com/isachivka/agterm-remote/bridge/internal/api"
	"github.com/isachivka/agterm-remote/bridge/internal/enroll"
	"github.com/isachivka/agterm-remote/bridge/internal/listener"
	"github.com/isachivka/agterm-remote/bridge/internal/pinning"
	"github.com/isachivka/agterm-remote/bridge/internal/trust"
)

// The whole bridge, on a real port: enrolment and the API, split by ALPN, dispatched by the listener.
//
// This is the wiring main.go has - listener.New over enroll.ServerConfigFor, with enroll.Serve on the
// enrolment branch and the api handler on the other - so both halves are exercised where they will
// actually run. The fake agterm behind it is the real one's contract: one request per connection, a
// serial accept loop.
type bridge struct {
	addr    string
	cert    *x509.Certificate
	store   *trust.Store
	window  *enroll.Window
	agterm  *agtermtest.Fake
	handler *countingHandler
	paired  chan trust.Peer
}

// countingHandler is the API, and it counts. What it is FOR is the boundary test: an anonymous
// caller reaching any verb has to be visible as something other than a refusal on the wire, because
// a refusal is what a broken dispatch and a working one would both eventually produce if the API
// happened to fail for its own reasons.
type countingHandler struct {
	calls chan api.Request
	inner *api.Handler
}

func (h *countingHandler) Handle(ctx context.Context, req api.Request) api.Response {
	select {
	case h.calls <- req:
	default:
	}
	return h.inner.Handle(ctx, req)
}

func startBridge(t *testing.T) *bridge {
	t.Helper()
	log.SetOutput(io.Discard)
	t.Cleanup(func() { log.SetOutput(io.Discard) })

	bridgeOwn, bridgeCert := mint(t, "agterm-remote bridge")
	store, err := trust.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	window := enroll.NewWindow(time.Now)

	fake := agtermtest.Start(t, func(agtermtest.Request) any { return agtermtest.OK(sessionTree()) })
	handler := &countingHandler{
		calls: make(chan api.Request, 64),
		inner: api.New(agterm.New(fake.Path), t.TempDir()),
	}

	paired := make(chan trust.Peer, 4)
	srv := listener.New(
		enroll.ServerConfigFor(bridgeOwn, enroll.NewPinnedClients(store).Certificates, window),
		handler,
		func(conn net.Conn) {
			enroll.Serve(conn, window, store, bridgeCert, func(p trust.Peer) { paired <- p })
		},
		true)

	tcp, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	go func() { _ = srv.Serve(ctx, tcp) }()
	t.Cleanup(func() { cancel(); _ = tcp.Close() })

	return &bridge{addr: tcp.Addr().String(), cert: bridgeCert, store: store, window: window,
		agterm: fake, handler: handler, paired: paired}
}

func sessionTree() any {
	return map[string]any{"tree": map[string]any{"workspaces": []any{
		map[string]any{"id": "W1", "name": "main", "active": true, "sessions": []any{
			map[string]any{"id": "F2F9559C-BB15-4E8F-AA66-381FA0CDE9B9", "name": "agterm", "active": true},
		}},
	}}}
}

// dialBridge is the phone: the bridge's certificate pinned by exact bytes - which is what the QR
// code's fingerprint buys - an optional client identity, and one ALPN protocol on offer.
func dialBridge(t *testing.T, b *bridge, own *tls.Certificate, proto string) *tls.Conn {
	t.Helper()
	conn, err := tls.Dial("tcp", b.addr, clientConfig(b.cert, own, []string{proto}))
	if err != nil {
		t.Fatalf("dial %s: %v", proto, err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
	return conn
}

// --- The end-to-end pairing -----------------------------------------------------------------------

// **One scan, and then the phone is on the API.**
//
// A client with NO certificate dials, negotiates the enrolment protocol, spends the token, sends the
// certificate it minted for itself - and then reconnects with that same certificate on the API
// protocol and reads a session list off a fake agterm. Nothing between the two connections invalidates
// a cache, restarts the process, or touches the listener.
//
// That last part is the thing this test is really holding. The pinned-certificate cache takes one
// snapshot keyed by the store's own DER, so a phone that enrolled a moment ago is picked up by the
// very next handshake with no invalidation call anywhere - a property that was ARGUED in
// internal/enroll and is measured here, through a real TLS handshake against a store that was written
// to while the listener was up.
func TestAPhoneEnrolsAndThenReachesTheAPI(t *testing.T) {
	b := startBridge(t)
	code, _ := b.window.Open(time.Minute)
	phoneOwn, phoneCert := mint(t, "the owner's phone")

	// --- The enrolment connection: no client certificate at all.
	enrolConn := dialBridge(t, b, nil, enroll.ProtoEnroll)
	if got := enrolConn.ConnectionState(); got.NegotiatedProtocol != enroll.ProtoEnroll {
		t.Fatalf("want %q, got %q", enroll.ProtoEnroll, got.NegotiatedProtocol)
	}

	request, err := json.Marshal(enroll.Request{
		Verb:        enroll.VerbEnroll,
		Token:       base64.StdEncoding.EncodeToString(code[:]),
		Certificate: base64.StdEncoding.EncodeToString(phoneCert.Raw),
		Name:        "the owner's phone",
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := enrolConn.Write(append(request, '\n')); err != nil {
		t.Fatalf("sending the enrolment: %v", err)
	}
	var reply enroll.Reply
	if err := json.NewDecoder(enrolConn).Decode(&reply); err != nil {
		t.Fatalf("reading the reply: %v", err)
	}
	if !reply.OK {
		t.Fatalf("enrolment refused: %s", reply.Error)
	}

	// The bridge's own certificate came back, and it is the one the phone will pin from here on.
	returned, err := base64.StdEncoding.DecodeString(reply.Certificate)
	if err != nil {
		t.Fatalf("the returned certificate is not standard base64: %v", err)
	}
	if !bytesEqual(returned, b.cert.Raw) {
		t.Fatal("the certificate returned is not the bridge's own")
	}
	if reply.Fingerprint != pinning.Fingerprint(phoneCert) {
		t.Fatalf("the receipt names %q, not the certificate that was pinned", reply.Fingerprint)
	}
	select {
	case p := <-b.paired:
		if p.Name != "the owner's phone" {
			t.Fatalf("the menu was told %+v", p)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("the Mac app was never told a phone paired")
	}

	// --- The API connection, with the certificate that was just enrolled. No restart, no
	// invalidation, no second window.
	pinned, err := x509.ParseCertificate(returned)
	if err != nil {
		t.Fatal(err)
	}
	apiConn := dialBridge(t, b, &phoneOwn, enroll.ProtoAPI)
	if got := apiConn.ConnectionState(); got.NegotiatedProtocol != enroll.ProtoAPI {
		t.Fatalf("want %q, got %q", enroll.ProtoAPI, got.NegotiatedProtocol)
	}
	if served := apiConn.ConnectionState().PeerCertificates; len(served) != 1 ||
		!bytesEqual(served[0].Raw, pinned.Raw) {
		t.Fatal("the laptop that served the API is not the one whose certificate enrolment returned")
	}
	if _, err := apiConn.Write([]byte(`{"verb":"sessions"}` + "\n")); err != nil {
		t.Fatal(err)
	}
	var resp api.Response
	if err := json.NewDecoder(apiConn).Decode(&resp); err != nil {
		t.Fatalf("reading the session list: %v", err)
	}
	if !resp.OK {
		t.Fatalf("the phone that just enrolled was refused by the API: %+v", resp)
	}
	if len(resp.Sessions) != 1 || resp.Sessions[0].Name != "agterm" {
		t.Fatalf("want the fake agterm's one session, got %+v", resp.Sessions)
	}

	// And the token is spent: the window that let the phone in is shut behind it.
	if b.window.IsOpen() {
		t.Fatal("the window is still open after a successful enrolment")
	}
}

// --- The boundary --------------------------------------------------------------------------------

// **An anonymous enrolment connection cannot reach one API verb.**
//
// This is the test to point at when somebody asks whether the boundary holds, and it exists because
// the boundary did not hold. Until the dispatch below it, listener.accept handed every completed
// handshake to the API handler whatever it had negotiated - so with an enrolment window open, a
// caller with NO CLIENT CERTIFICATE that offered `agterm/enroll-1` reached the whole API. It was
// measured: `{"verb":"sessions"}` came back ok=true. Nothing had shipped that could open a window,
// which is the only reason it had never mattered.
//
// So the window here is OPEN, the caller presents no certificate, and it tries every verb the api
// package defines - including the two that destroy the owner's work and the one that starts a
// process on their Mac. What it must get back is the enrolment handler's single refusal, and what the
// API handler must see is nothing at all.
func TestAnAnonymousEnrolmentConnectionReachesNoAPIVerb(t *testing.T) {
	b := startBridge(t)
	b.window.Open(time.Minute)

	// Every verb in the closed set, named explicitly rather than derived, so a verb ADDED to api
	// shows up here as a line somebody had to write rather than as coverage that silently did not
	// grow.
	verbs := []string{
		api.VerbSessions, api.VerbScreen, api.VerbResize, api.VerbType, api.VerbFile,
		api.VerbWorkspaceCreate, api.VerbSessionCreate, api.VerbWorkspaceRename, api.VerbSessionRename,
		api.VerbSessionClose, api.VerbWorkspaceDelete, api.VerbPaneOpen, api.VerbPaneShow,
	}

	for _, verb := range verbs {
		// A fresh connection per verb: the enrolment handler reads exactly one line and closes, so a
		// caller trying many things has to reconnect for each - which is also the shape of the attack.
		conn := dialBridge(t, b, nil, enroll.ProtoEnroll)
		// The caller offered no certificate and the enrolment config never asks for one, so
		// negotiating this protocol IS the statement that nobody authenticated - see ServerConfigFor,
		// and the server-side assertion of it in alpn_test.go.
		if got := conn.ConnectionState().NegotiatedProtocol; got != enroll.ProtoEnroll {
			t.Fatalf("%s: negotiated %q, so this is not the anonymous branch", verb, got)
		}
		line, err := json.Marshal(api.Request{Verb: verb, Session: "F2F9559C-BB15-4E8F-AA66-381FA0CDE9B9"})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := conn.Write(append(line, '\n')); err != nil {
			// A dispatch that closed the connection outright is a refusal too.
			continue
		}

		// Decoded into a map rather than into either reply type, because what is being asserted is
		// what came back on the wire and not that it fitted a struct.
		var got map[string]any
		if err := json.NewDecoder(conn).Decode(&got); err != nil {
			continue
		}
		if ok, _ := got["ok"].(bool); ok {
			t.Fatalf("%s: an anonymous enrolment connection was answered ok=true by the API: %v",
				verb, got)
		}
		if _, present := got["sessions"]; present {
			t.Fatalf("%s: an anonymous caller was handed a session list: %v", verb, got)
		}
		if got["error"] != "enrolment refused" {
			t.Fatalf("%s: answered by something other than the enrolment handler: %v", verb, got)
		}
	}

	// The API handler never saw any of it - not a refused request, not a decode error, nothing.
	select {
	case req := <-b.handler.calls:
		t.Fatalf("an anonymous caller reached the API handler with %+v", req)
	default:
	}
	// And nothing was pinned, and the owner's window is exactly as they left it: thirteen verbs is
	// more than maxAttempts, so a dispatch that fed them to Consume would have shut it.
	if len(b.store.Peers()) != 0 {
		t.Fatal("an anonymous caller pinned something")
	}
	if !b.window.IsOpen() {
		t.Fatal("attempts that never reached the gate closed the owner's window")
	}
	// The fake agterm is the last word: no verb produced a control command on the owner's laptop.
	if got := b.agterm.Requests(); len(got) != 0 {
		t.Fatalf("an anonymous caller reached agterm as %+v", got)
	}
}

// The other direction, so the test above cannot pass because the API is simply broken: the same
// verb, on the same bridge, from a phone that HAS enrolled, is served.
func TestThePairedPhoneStillReachesTheSameVerb(t *testing.T) {
	b := startBridge(t)
	phoneOwn, phoneCert := mint(t, "the owner's phone")
	if err := b.store.Replace(trust.Peer{
		Fingerprint:    pinning.Fingerprint(phoneCert),
		CertificateDER: phoneCert.Raw,
		PairedAt:       time.Unix(1, 0),
	}); err != nil {
		t.Fatal(err)
	}

	conn := dialBridge(t, b, &phoneOwn, enroll.ProtoAPI)
	if _, err := conn.Write([]byte(`{"verb":"sessions"}` + "\n")); err != nil {
		t.Fatal(err)
	}
	var resp api.Response
	if err := json.NewDecoder(conn).Decode(&resp); err != nil {
		t.Fatal(err)
	}
	if !resp.OK || len(resp.Sessions) != 1 {
		t.Fatalf("the paired phone was refused, so the boundary test proves nothing: %+v", resp)
	}
}

// **The held-connection flush, on a real port: the exact shape that made the log bound false.**
//
// The unit test of this property drives the handler directly against a window that is already shut.
// This one is the reviewer's own shape and is worth the sockets: connections that negotiate
// `agterm/enroll-1` WHILE the window is open, held, and then flushed after the window has gone. Each
// one has already passed the ALPN gate - the configuration was chosen at its handshake - so it reaches
// enroll.Serve, reaches Window.Consume, and is refused for a cause that spends nothing.
//
// Before the fix this wrote one line per connection: 200 connections, 200 lines, one window, no
// attempt spent, and the listener's twelve-slot semaphore released before the branch so nothing
// bounded it. It is now counted.
//
// It also measures the memory side of the same trick, which the log fix does NOT address: held
// connections each cost a goroutine and a read buffer for as long as the exchange deadline. Reported
// rather than asserted - a heap assertion across a real TLS stack is a flake - and bounded by that
// deadline rather than by anything here.
func TestHeldEnrolmentConnectionsFlushedAfterTheWindowAreCountedNotLogged(t *testing.T) {
	const held = 200

	b := startBridge(t)
	b.window.Open(time.Minute)

	var before runtime.MemStats
	runtime.GC()
	runtime.ReadMemStats(&before)

	// Every one of these completes a handshake on the anonymous branch while the window is open.
	conns := make([]*tls.Conn, 0, held)
	for i := 0; i < held; i++ {
		conn, err := tls.Dial("tcp", b.addr, clientConfig(b.cert, nil, []string{enroll.ProtoEnroll}))
		if err != nil {
			t.Fatalf("holding connection %d: %v", i, err)
		}
		t.Cleanup(func() { _ = conn.Close() })
		_ = conn.SetDeadline(time.Now().Add(30 * time.Second))
		if got := conn.ConnectionState().NegotiatedProtocol; got != enroll.ProtoEnroll {
			t.Fatalf("connection %d negotiated %q", i, got)
		}
		conns = append(conns, conn)
	}

	var after runtime.MemStats
	runtime.ReadMemStats(&after)
	t.Logf("%d held anonymous enrolment connections: heap %+d KiB (%d B each)",
		held, (int64(after.HeapAlloc)-int64(before.HeapAlloc))/1024,
		(int64(after.HeapAlloc)-int64(before.HeapAlloc))/held)

	// The window goes away with every one of them still open and still unspent.
	b.window.Close()

	var lines atomic.Int32
	log.SetOutput(writerFunc(func(p []byte) (int, error) { lines.Add(1); return len(p), nil }))
	t.Cleanup(func() { log.SetOutput(io.Discard) })

	for i, conn := range conns {
		if _, err := conn.Write([]byte(`{"verb":"enroll","token":"` +
			base64.StdEncoding.EncodeToString(make([]byte, 32)) + `"}` + "\n")); err != nil {
			t.Fatalf("flushing connection %d: %v", i, err)
		}
		var reply enroll.Reply
		if err := json.NewDecoder(conn).Decode(&reply); err != nil {
			t.Fatalf("connection %d got no answer: %v", i, err)
		}
		if reply.OK {
			t.Fatalf("connection %d enrolled with a wrong token against a closed window", i)
		}
	}

	n := lines.Load()
	t.Logf("%d held connections flushed after the window closed: %d log lines", held, n)
	if n > 1 {
		t.Fatalf("%d held connections wrote %d log lines against one window; that is an anonymous "+
			"write primitive against the owner's disk, which is what internal/listener refuses to "+
			"hand out", held, n)
	}
	if len(b.store.Peers()) != 0 {
		t.Fatal("something was pinned")
	}
}
