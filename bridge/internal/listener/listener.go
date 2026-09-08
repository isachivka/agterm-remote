// Package listener is the bridge's front door: a TLS listener that answers nobody without the pinned
// client certificate.
//
// # The invariant this package exists to hold
//
// **Nothing is written to a connection, and no application data is read from it, before
// HandshakeContext returns nil.**
//
// This is not a style rule. Under TLS 1.3 client authentication rides in the client's OWN last
// flight, so a caller's certificate and its first application data can arrive together — bytes from
// an unverified peer can already be sitting in the socket buffer before the verifier has run. Go does
// not hand them over (Handshake returns the VerifyPeerCertificate error and Read never yields them),
// so a naive listener is safe by inheritance rather than by design. The edit that breaks it is a
// banner, a greeting, or a log line that touches the connection before the handshake resolves, and
// every one of those looks harmless in a diff.
//
// So the accept path resolves the handshake first and touches the connection for nothing else until
// it has, and HandshakeIsFirst asserts both halves: the server observes no bytes, and the rejected
// caller observes none either. Asserting only the first would keep passing after somebody adds a
// banner, because a banner is a write and a test that never reads cannot see it.
//
// # What a rejected caller costs
//
// Nothing that accumulates: no disk, no unbounded memory, no agterm round trip. In particular failed
// handshakes are COUNTED, not logged one line each. One line per attempt would hand an anonymous
// caller — no certificate, no handshake — an unbounded write primitive against the owner's disk from
// the open internet. That is the same rule as "no 404 page": an unauthenticated caller must not be
// able to make the bridge do anything at all, and writing a line to a file is doing something.
//
// # The second invariant, and the bug that produced it
//
// **No anonymous caller may cause a legitimate one to be refused.**
//
// An earlier version held a cumulative ceiling: past N failed handshakes in a window, every source
// was turned away. That locked the owner out — which is the one failure this whole feature exists to
// prevent. Roughly three connections a second from anywhere, with no certificate and no cost, would
// drop the owner's phone before its handshake for the rest of the window, and an attacker need only
// keep going. The owner is on a rotating mobile address, so they would never be the spared source
// either. The same paragraph that reasoned the per-source table must not "lock the owner out" sat
// three lines below a global limit that did precisely that.
//
// The distinction that fixes it is **bound work, do not refuse service**. A cumulative counter cannot
// tell a busy minute from an attack and its only lever is the door. A concurrency semaphore caps
// goroutines, descriptors and CPU exactly, and — the property that matters — **it has no memory.**
// Load drops, capacity returns in the same instant. There is no state an attacker can put the bridge
// into that outlives their traffic.
//
// The per-source counter stays, because it is targeted: it punishes the address actually
// misbehaving and does not generalise to everyone.
//
// # The precondition that targeting rests on, which is not always true
//
// **Per-source blocking is only meaningful while the connection's peer address IS the caller's
// address.** That was assumed rather than written down, and it stopped being true the moment the
// transport became a proxied one: a TLS-terminating proxy terminates TLS at its own end and
// connects over the LAN, so every connection's peer is the proxy and sourceAddr returns the same
// host for everybody.
//
// A per-source counter over a single collapsed source **is a global ceiling wearing a per-source
// costume** - five failed handshakes and every caller is refused, the owner included, for the rest of
// the window. That is the bug the paragraphs above describe, restored not by editing this file but by
// changing what arrives beneath it.
//
// So peerIsCaller is a required argument rather than a default: whoever constructs this has to say
// which it is. When false the counter is not consulted and nobody is blocked - the safe direction,
// the same one the table cap already takes past its limit. The semaphore keeps working, and it was
// the control doing the real work anyway: **bound work, do not refuse service.**
//
// **X-Forwarded-For is not the answer and must never be read here.** It is a header, so it is
// caller-influenced, and trusting it hands an attacker a lockout primitive and a table-flooding
// primitive at once. Not read, not logged.
//
// The same distinction settles what the counter may count. **The two controls have different jobs.**
// The semaphore bounds WORK, so it is what defends against a caller opening connections and sending
// nothing. The counter punishes a failed AUTHENTICATION — and a bare TCP connect that never sends a
// ClientHello is not an authentication attempt at all, it is a caller who never claimed to be
// anybody. Counting it would conflate the two controls, which is exactly what let an anonymous caller
// lock the owner out. So a connection that closes before any bytes arrive is not counted; a
// ClientHello that arrives and then fails verification is. The distinction is made on bytes actually
// read from the wire, not on the shape of the error, because the tempting implementation counts
// everything that is not nil.
//
// Anyone adding a global limit here should read this first.
package listener

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"log"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/api"
	"github.com/isachivka/agterm-remote/bridge/internal/enroll"
)

const (
	// handshakeTimeout bounds a caller who connects and then stalls. Without it a slow-loris holds a
	// goroutine and a file descriptor for free, with no certificate.
	handshakeTimeout = 10 * time.Second
	// idleTimeout closes an authenticated connection that stops asking.
	idleTimeout = 5 * time.Minute
	// maxRequestBytes caps one request line, and the number is api's rather than this package's.
	//
	// It was 64 KB here with the note "far above any real request", which stopped being true when a
	// request could carry a file. api derives it from the file bound so the two cannot drift - see
	// api.MaxRequestBytes, where what the increase gave up is written down.
	maxRequestBytes = api.MaxRequestBytes
	// failureWindow is how often the aggregate handshake-failure count is reported and reset.
	failureWindow = time.Minute
	// failuresPerSource before a source stops being answered within a window. Targeted at the
	// address actually misbehaving. Residual and accepted: a legitimate phone that fails five
	// handshakes in a minute - most likely its certificate was re-minted and not re-pinned - locks
	// itself out until the window rolls. That is a minute, it is self-inflicted, and the alternative
	// is not punishing a misbehaving source at all.
	failuresPerSource = 5
	// trackedSources caps the per-source table so a caller with many addresses cannot grow it
	// without limit. Beyond the cap new sources are simply not tracked, which means they are never
	// blocked - the safe direction. Filling the table must not become a way to get everyone else
	// refused, which is the mistake the package comment describes.
	trackedSources = 1024
	// maxConcurrentHandshakes bounds WORK rather than refusing SERVICE. It caps goroutines, file
	// descriptors and CPU spent on unverified peers at a fixed ceiling, and excess connections are
	// closed instantly rather than queued. Crucially it has no memory: the moment load drops,
	// capacity is back. A dozen is far above anything one phone does and far below anything that
	// costs this laptop something.
	maxConcurrentHandshakes = 12
	// requestsPerConnection bounds an authenticated caller. This is not protection from the owner's
	// phone; it bounds a phone that has been taken, and it keeps a runaway poll loop from saturating
	// agterm's single serial accept loop.
	requestsPerConnection = 600
)

// Requests is the half of api.Handler this package needs. An interface so a test can supply a
// recorder and prove that an unverified caller's bytes never arrive here.
type Requests interface {
	Handle(ctx context.Context, req api.Request) api.Response
}

// Enrolment serves a connection that negotiated enroll.ProtoEnroll, and it is the ONLY thing such a
// connection ever reaches.
//
// A function rather than an interface because there is nothing to ask it: it is handed an
// already-handshaked connection, it owns that connection, and it closes it. In the bridge it is
// enroll.Serve with the window, the trust store and the bridge's certificate closed over.
//
// **nil is a legitimate value and means "this listener serves no enrolment".** A connection that
// negotiated the enrolment protocol is then closed unserved, which is the same answer the dispatch
// gives to a protocol nobody claimed - see accept. That is what lets a caller that has no pairing
// flow (the front door's tests, a bridge built before this argument existed) leave it out and be
// safer for it rather than accidentally more permissive.
type Enrolment func(conn net.Conn)

// Server accepts pinned-mTLS connections and serves the two verbs.
type Server struct {
	tls      *tls.Config
	handler  Requests
	enrol    Enrolment
	failures *failureCounter
	// peerIsCaller reports whether a connection's peer address identifies the caller. False on any
	// transport where something else dials on the caller's behalf - see the package comment.
	peerIsCaller bool
	// slots is the concurrency semaphore. A buffered channel rather than a counter, so acquiring is
	// a non-blocking select and a full one is answered by closing the connection rather than by
	// holding it.
	slots chan struct{}
}

// New builds a listener.
//
// enrolment is what a connection that negotiated `agterm/enroll-1` reaches, and nil means none is
// served - see [Enrolment]. It is a REQUIRED ARGUMENT rather than a setter for the same reason
// peerIsCaller is: whoever stands this up has to have thought about it. A setter that can be
// forgotten, on a type whose default would then be "serve enrolment connections as API connections",
// is the exact shape of the bug this dispatch exists to close.
//
// peerIsCaller must be false whenever connections arrive via anything that dials on the caller's
// behalf, because per-source blocking is meaningless then and actively harmful - see the package
// comment.
func New(tlsConfig *tls.Config, handler Requests, enrolment Enrolment, peerIsCaller bool) *Server {
	return &Server{
		tls:          tlsConfig,
		handler:      handler,
		enrol:        enrolment,
		failures:     newFailureCounter(),
		peerIsCaller: peerIsCaller,
		slots:        make(chan struct{}, maxConcurrentHandshakes),
	}
}

// Serve accepts until ln is closed.
func (s *Server) Serve(ctx context.Context, ln net.Listener) error {
	defer s.failures.flush()
	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, net.ErrClosed) {
				return nil
			}
			return err
		}
		go s.accept(ctx, conn)
	}
}

// accept resolves the handshake before the connection is touched for anything else.
func (s *Server) accept(ctx context.Context, raw net.Conn) {
	defer raw.Close()

	source := sourceAddr(raw)
	// Checked BEFORE the handshake, because the handshake is the expensive part and a blocked source
	// must not be able to make the bridge do asymmetric work. A source address is not attacker-
	// supplied application data — it is the peer of a completed TCP handshake — so consulting it here
	// does not breach the invariant above.
	// Only consulted when the peer address is the caller's. Otherwise every caller shares one source
	// and blocking any of them blocks all of them, the owner included.
	if s.peerIsCaller && s.failures.blocked(source) {
		return
	}

	// Bound the work, do not refuse the service. A full semaphore closes this connection now and is
	// empty again the instant the in-flight handshakes finish, so saturation lasts exactly as long
	// as the traffic causing it and leaves nothing behind. Held only for the handshake: an
	// authenticated connection may live for minutes and must not occupy a slot for that time.
	select {
	case s.slots <- struct{}{}:
	default:
		return
	}
	released := false
	release := func() {
		if !released {
			released = true
			<-s.slots
		}
	}
	defer release()

	// Wrapped so the handshake outcome can be told apart from a caller who never spoke. See the
	// package comment: only a handshake that actually began is a failed authentication.
	observed := &observedConn{Conn: raw}
	tlsConn := tls.Server(observed, s.tls)
	hsCtx, cancel := context.WithTimeout(ctx, handshakeTimeout)
	defer cancel()

	// THE INVARIANT. Nothing above this line reads or writes the connection, and nothing below it
	// runs unless this returned nil. A rejected caller reaches no request parser, no handler, no
	// agterm round trip, and no log line containing anything they chose.
	if err := tlsConn.HandshakeContext(hsCtx); err != nil {
		if observed.read.Load() > 0 {
			// Always counted, never always source-keyed. The aggregate blocks nobody and is the
			// owner's only signal that certificates are being presented and refused; the per-source
			// table is the part whose meaning depends on the precondition.
			s.failures.record(source, s.peerIsCaller)
		}
		return
	}
	release()

	// **THE DISPATCH, and it is what makes this package's headline claim true rather than merely
	// intended.**
	//
	// Until this switch existed, every completed handshake went to s.serve whatever it had
	// negotiated. That was safe only by accident: nothing in the repository could open an enrolment
	// window, so the anonymous branch of enroll.ServerConfigFor was unreachable and every connection
	// that got here had presented the pinned certificate. With a window open it was a hole, and it
	// was measured as one - a caller with no client certificate, offering `agterm/enroll-1`, was
	// answered `ok=true` with the owner's session list.
	//
	// Three properties, in the order they matter:
	//
	//   - **ProtoEnroll reaches enrolment and NOTHING ELSE.** The anonymous configuration and the API
	//     handler are now in different branches of one switch, and the only way to move a connection
	//     between them is to renegotiate ALPN, which TLS settles once in the ClientHello exchange and
	//     never re-opens.
	//   - **ProtoAPI reaches the API**, exactly as before, and it got here only by presenting a
	//     certificate byte-identical to a pinned one.
	//   - **Anything else is CLOSED, not served.** That is the fail-closed direction and it is
	//     deliberately strict: a caller that offered no ALPN at all negotiates the empty string, and
	//     the empty string is not the API protocol. Such a caller has still satisfied the pinned
	//     verifier - the API config is what the handshake ran under - so serving it would not be
	//     unsafe today. It is refused anyway, because "the default branch is the API" is the property
	//     that made the original bug possible, and a dispatch whose unknown case is a request rather
	//     than a refusal will be wrong the first time a third protocol exists. The bridge's own
	//     phone offers exactly one protocol per connection, by design.
	//
	// Nothing here writes to the connection or logs. A protocol that leads nowhere is closed the same
	// silent way a failed handshake is, so an unauthenticated caller still cannot make this process
	// do anything at all.
	//
	// The handshake slot was released above, BEFORE either branch, and that is deliberate on the
	// enrolment side too. Holding it across the exchange would cap concurrent enrolments at twelve -
	// which sounds like the right kind of bound and is the wrong one here, because the semaphore is
	// shared: twelve anonymous callers stalling inside enrolment would refuse the OWNER's handshake,
	// which is the second invariant this package holds. What bounds an anonymous exchange instead is
	// its own deadline, inside enroll.Serve, plus a bounded read - a goroutine and 64 KiB for ten
	// seconds, and nothing that outlives the traffic.
	switch tlsConn.ConnectionState().NegotiatedProtocol {
	case enroll.ProtoAPI:
		s.serve(ctx, tlsConn)
	case enroll.ProtoEnroll:
		if s.enrol != nil {
			s.enrol(tlsConn)
		}
	}
}

// serve reads newline-delimited JSON requests until the caller stops, goes idle, or exhausts its
// budget.
//
// Newline-delimited JSON rather than HTTP, and the connection is persistent. Two reasons, both
// measured elsewhere in this milestone: there is no HTTP parser to reach, so "no 404 page" is true by
// construction rather than by configuration; and a full TLS handshake per poll would cost two round
// trips over a mobile network on every screen read, which is the one thing this feature cannot
// afford. agterm's own socket is one-request-per-connection; this one is not, and the difference is
// deliberate.
func (s *Server) serve(ctx context.Context, conn net.Conn) {
	enc := json.NewEncoder(conn)
	budget := requestsPerConnection

	for budget > 0 {
		budget--
		if err := conn.SetDeadline(time.Now().Add(idleTimeout)); err != nil {
			return
		}
		line, err := readLine(conn, maxRequestBytes)
		if err != nil {
			return
		}

		var resp api.Response
		req, decodeErr := api.Decode(line)
		if decodeErr != nil {
			resp = api.Response{OK: false, Error: decodeErr.Error()}
		} else {
			resp = s.handler.Handle(ctx, req)
		}

		// One line per request, and never the screen text — a log carrying content would be a
		// transcript of the owner's work and their code sitting in a file.
		log.Printf("verb=%s session=%s ok=%t", safeVerb(req.Verb), safeVerb(req.Session), resp.OK)

		if err := enc.Encode(resp); err != nil {
			return
		}
	}
}

// safeVerb bounds what a caller can put in the log. Even authenticated, a request field is caller-
// supplied, and an unbounded one would let a compromised phone write arbitrary length into the
// owner's log file.
func safeVerb(s string) string {
	const max = 40
	if len(s) > max {
		return s[:max]
	}
	return s
}

func readLine(conn net.Conn, max int) ([]byte, error) {
	buf := make([]byte, 0, 512)
	chunk := make([]byte, 512)
	for {
		n, err := conn.Read(chunk)
		for i := 0; i < n; i++ {
			if chunk[i] == '\n' {
				return append(buf, chunk[:i]...), nil
			}
		}
		if n > 0 {
			buf = append(buf, chunk[:n]...)
			if len(buf) > max {
				return nil, errors.New("request too large")
			}
		}
		if err != nil {
			return nil, err
		}
	}
}

// observedConn records whether the peer ever sent anything.
//
// Zero bytes read means the connection was opened and closed without a ClientHello — a liveness
// probe, a port scan, a health check by something that does not speak TLS. None of those is a failed
// authentication, and the app's own laptop-reachability probe is deliberately one of them: it would
// otherwise lock the owner's phone out of the bridge with its own liveness check.
type observedConn struct {
	net.Conn
	read atomic.Int64
}

func (c *observedConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	if n > 0 {
		c.read.Add(int64(n))
	}
	return n, err
}

func sourceAddr(conn net.Conn) string {
	host, _, err := net.SplitHostPort(conn.RemoteAddr().String())
	if err != nil {
		return conn.RemoteAddr().String()
	}
	return host
}

// failureCounter counts failed handshakes per source and in aggregate, and reports the aggregate once
// per window rather than once per attempt.
type failureCounter struct {
	mu        sync.Mutex
	windowEnd time.Time
	perSource map[string]int
	total     int
}

func newFailureCounter() *failureCounter {
	return &failureCounter{perSource: make(map[string]int), windowEnd: time.Now().Add(failureWindow)}
}

// roll reports and resets when the window has passed. Called with the mutex held.
func (f *failureCounter) roll() {
	if time.Now().Before(f.windowEnd) {
		return
	}
	if f.total > 0 {
		// The ONE thing an unauthenticated caller can cause to be written, bounded at one line per
		// window however many attempts there were.
		//
		// The source count is appended only when sources were actually keyed. On a proxied transport
		// the table is empty by design, and "from 0 sources" would read as a measurement of zero
		// rather than as an absence of one - a misleading number is worse than no number.
		if len(f.perSource) > 0 {
			log.Printf("handshake failures in the last %s: %d from %d sources",
				failureWindow, f.total, len(f.perSource))
		} else {
			log.Printf("handshake failures in the last %s: %d", failureWindow, f.total)
		}
	}
	f.total = 0
	f.perSource = make(map[string]int)
	f.windowEnd = time.Now().Add(failureWindow)
}

func (f *failureCounter) blocked(source string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.roll()
	// Per-source only. A global condition here is what locked the owner out, and the package comment
	// explains why it must not come back: no anonymous caller may cause a legitimate one to be
	// refused. `total` survives for the aggregate log line and for nothing else.
	return f.perSource[source] >= failuresPerSource
}

// record counts a failed handshake. `keyed` says whether the source is meaningful enough to key on.
//
// The two halves are deliberately separated. `total` is not source-keyed and blocks nobody, so it is
// counted on every transport — it is the only signal the owner has that somebody arrived with a
// certificate and was refused, which is a far more interesting event than a refused upgrade. Gating
// it along with the table made that line unreachable and always zero, while leaving it in place
// looking true.
func (f *failureCounter) record(source string, keyed bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.roll()
	f.total++
	if !keyed {
		return
	}
	// Only grow the table while it is under the cap. Past it, new sources are simply not tracked and
	// so are never blocked - the safe direction. Filling the table must not become a way to get
	// everyone else refused.
	if _, known := f.perSource[source]; known || len(f.perSource) < trackedSources {
		f.perSource[source]++
	}
}

func (f *failureCounter) flush() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.windowEnd = time.Time{}
	f.roll()
}
