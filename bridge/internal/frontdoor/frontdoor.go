// Package frontdoor turns a proxied HTTP connection into the byte stream the mTLS listener expects.
//
// A TLS-terminating proxy proxies rather than forwards: it terminates its own TLS and connects to
// the laptop over the LAN, so a client certificate cannot survive the trip. mTLS therefore runs INSIDE the
// proxied stream, and this package is the only thing that knows a proxy exists.
//
//	phone → TLS to the proxy → proxied on-link → HTTP Upgrade → [this package]
//	                                                           → pinned mTLS → NDJSON
//
// # The listener is untouched, and that is structural
//
// This type implements net.Listener. Its Accept returns upgraded byte streams, so the existing
// listener runs against it unchanged and never learns that a proxy, an HTTP request or a WebSocket
// frame exists. Pinning is not weakened to fit the proxy; the proxy moves bytes it cannot read.
//
// # What this costs, stated narrowly
//
// The fail-closed rule was that an anonymous caller gets a TLS handshake failure and nothing else.
// Completing an upgrade means answering an unauthenticated request, so that rule is amended here
// rather than left to rot.
//
// The loss is smaller than "the endpoint is now discoverable": a TLS-terminating proxy of the kind
// this runs behind was measured answering 200 with its own panel for ANY unmapped subdomain on the
// same name, so a scanner already learns something listens. What is actually given up is that an anonymous caller can now make the
// BRIDGE do bounded work rather than only the router. That is not an excuse — it is why the three
// rules below are held by tests rather than by comments.
//
// # The three rules, and where each is enforced
//
//  1. ONE response to every unauthenticated request. Not four call sites that agree today: there is
//     exactly one refusal path, [refuse], and everything that is not a valid upgrade goes through it.
//  2. The semaphore is acquired BEFORE anything is parsed. Acquiring it after would be a bound on
//     work already done.
//  3. No log line per anonymous request. Aggregate counts per window only. Before this, a caller had
//     to survive a handshake to make the bridge write anything; now a well-formed request is enough,
//     so the disk-write primitive gets sharper here, not softer.
//
// Each has a test that goes red if it is undone. A sentence in a comment is a statement whose expiry
// nobody notices.
//
// # An accepted difference: saturation is observable
//
// A caller past the semaphore gets silence; a caller inside the bound gets the refusal. That is a
// difference an attacker can observe, so it is a saturation oracle - and it is accepted rather than
// overlooked.
//
// It is derived from the bridge”'s own state rather than from anything the caller sent, so it maps
// nothing about paths, methods or headers. It matches what the mTLS listener already does past its
// own bound. And the alternative - blocking until a slot frees - would let a caller hold connections
// open to make everyone else wait, which is worse than telling them the door is busy.
package frontdoor

import (
	"bufio"
	"context"
	"crypto/sha1"
	"encoding/base64"
	"errors"
	"log"
	"net"
	"strings"
	"sync"
	"time"
)

const (
	// upgradeTimeout bounds a caller who connects and then says nothing.
	upgradeTimeout = 10 * time.Second
	// maxRequestBytes caps the whole request head. Far above any real upgrade.
	maxRequestBytes = 8 << 10
	maxHeaderLines  = 64
	// maxConcurrentUpgrades bounds work rather than refusing service, the same shape as the
	// listener's handshake bound and for the same reason — it has no memory, so capacity returns
	// with the traffic. The upgrade is now the cheapest thing an anonymous caller can reach.
	maxConcurrentUpgrades = 12
	// failureWindow is how often the aggregate refusal count is reported and reset.
	failureWindow = time.Minute
	// wsMagic is RFC 6455's fixed GUID for the accept digest.
	wsMagic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
)

// refusalResponse is THE response an unauthenticated caller gets. One value, one writer.
//
// Identical for a bad path, a bad method, a missing upgrade header and a malformed body — a wall with
// one brick pattern rather than a map. No banner, no version, no date, and nothing derived from what
// the caller sent. `Connection: close` because there is no second chance on this connection.
var refusalResponse = []byte("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")

// Listener accepts proxied HTTP connections and yields upgraded byte streams.
type Listener struct {
	inner    net.Listener
	upgraded chan net.Conn
	slots    chan struct{}
	refusals *counter
	closeOne sync.Once
	done     chan struct{}
}

// Listen wraps a listener. Accept returns only successfully upgraded connections.
func Listen(inner net.Listener) *Listener {
	l := &Listener{
		inner:    inner,
		upgraded: make(chan net.Conn),
		slots:    make(chan struct{}, maxConcurrentUpgrades),
		refusals: newCounter(),
		done:     make(chan struct{}),
	}
	go l.accept()
	return l
}

func (l *Listener) accept() {
	defer l.refusals.flush()
	for {
		conn, err := l.inner.Accept()
		if err != nil {
			return
		}
		go l.handle(conn)
	}
}

// handle is where rules 1 and 2 live.
func (l *Listener) handle(raw net.Conn) {
	// RULE 2. Before the deadline, before the reader, before a single byte is read or parsed.
	// Acquiring after any of that would bound work already done.
	select {
	case l.slots <- struct{}{}:
	default:
		raw.Close()
		return
	}
	released := false
	release := func() {
		if !released {
			released = true
			<-l.slots
		}
	}

	_ = raw.SetDeadline(time.Now().Add(upgradeTimeout))
	key, ok := readUpgrade(bufio.NewReader(raw))
	if !ok {
		refuse(raw, l.refusals)
		release()
		return
	}

	if _, err := raw.Write(acceptResponse(key)); err != nil {
		raw.Close()
		release()
		return
	}
	// The slot covers the upgrade, not the session: an authenticated connection may live for minutes
	// and must not hold capacity for that time.
	release()
	_ = raw.SetDeadline(time.Time{})

	select {
	case l.upgraded <- newConn(raw):
	case <-l.done:
		raw.Close()
	}
}

// refuse is the ONLY path by which an unauthenticated caller gets a response.
//
// RULE 1, by construction rather than by agreement: there is nowhere else that writes to a rejected
// connection, so a fifth failure mode added later cannot accidentally answer differently.
//
// RULE 3: the refusal is counted, never logged. One line per anonymous request would hand a caller an
// unbounded write against the owner's disk, and a well-formed HTTP request is now enough to reach it.
func refuse(conn net.Conn, refusals *counter) {
	_, _ = conn.Write(refusalResponse)
	conn.Close()
	refusals.record()
}

func (l *Listener) Accept() (net.Conn, error) {
	select {
	case c := <-l.upgraded:
		return c, nil
	case <-l.done:
		return nil, net.ErrClosed
	}
}

func (l *Listener) Close() error {
	l.closeOne.Do(func() { close(l.done) })
	return l.inner.Close()
}

func (l *Listener) Addr() net.Addr { return l.inner.Addr() }

// Wait blocks until ctx is done, then closes. Convenience for a caller that owns the context.
func (l *Listener) Wait(ctx context.Context) { <-ctx.Done(); _ = l.Close() }

// readUpgrade parses just enough to decide, and returns the key only for a valid binary upgrade.
//
// Hand-rolled rather than net/http, and that is rule 1 rather than taste: net/http answers malformed
// requests with its own responses, which differ by input. A caller could tell a bad method from a bad
// body by the reply, which is the map this design refuses to draw.
//
// Everything the phone will not send is refused: no subprotocol negotiation, no extensions, no
// compression, no version other than 13. Refusing the rest is the feature.
func readUpgrade(r *bufio.Reader) (key string, ok bool) {
	read := 0
	line, err := readLine(r, &read)
	if err != nil {
		return "", false
	}
	// Method and version only. The path is deliberately not checked: a caller must not be able to
	// learn a correct path from a different answer, and the upgrade itself is the authorisation
	// boundary - or rather, the mTLS behind it is.
	parts := strings.Fields(line)
	if len(parts) != 3 || parts[0] != "GET" || !strings.HasPrefix(parts[2], "HTTP/1.1") {
		return "", false
	}

	headers := map[string]string{}
	for i := 0; ; i++ {
		if i > maxHeaderLines {
			return "", false
		}
		h, err := readLine(r, &read)
		if err != nil {
			return "", false
		}
		if h == "" {
			break
		}
		colon := strings.IndexByte(h, ':')
		if colon <= 0 {
			return "", false
		}
		headers[strings.ToLower(strings.TrimSpace(h[:colon]))] = strings.TrimSpace(h[colon+1:])
	}

	// An OFFERED extension is not a refusal, and treating it as one cost a working feature.
	//
	// RFC 6455 §9.1: a client lists the extensions it would like; the server declines by simply not
	// echoing any back. OkHttp offers `permessage-deflate` on every WebSocket by default, so this
	// door refused the only client this project has — with the same opaque 400 it gives a scanner —
	// while the app reported "your laptop is not answering" about a laptop that had answered.
	//
	// **Nothing is negotiated by allowing this.** [acceptResponse] echoes no extension header at all,
	// so the connection stays unextended and uncompressed; a client that used deflate anyway would be
	// violating the protocol and its frames would fail to parse.
	//
	// A SUBPROTOCOL is treated the same way and for a second reason. RFC 6455 declines one by omission
	// too - but more importantly, refusing a subprotocol while tolerating an extension would let an
	// anonymous caller tell two inputs apart, which is the map this door exists not to draw. The rule
	// is now simply: a well-formed RFC 6455 upgrade is accepted and nothing is negotiated. Refusals
	// are reserved for requests that are actually malformed, and they remain byte-identical.
	//
	// This widens what is ACCEPTED and not what is authenticated: the upgrade was never the boundary,
	// the pinned mTLS inside it is, and the fail-closed rule was already amended to say an anonymous
	// caller can complete one.
	//
	// It was invisible to every test because the tests speak to this door with a client written in
	// this repository, which offers nothing. The client that matters is OkHttp.
	if !strings.EqualFold(headers["upgrade"], "websocket") ||
		!strings.Contains(strings.ToLower(headers["connection"]), "upgrade") ||
		headers["sec-websocket-version"] != "13" {
		return "", false
	}
	raw, err := base64.StdEncoding.DecodeString(headers["sec-websocket-key"])
	if err != nil || len(raw) != 16 {
		return "", false
	}
	return headers["sec-websocket-key"], true
}

func readLine(r *bufio.Reader, read *int) (string, error) {
	var b strings.Builder
	for {
		c, err := r.ReadByte()
		if err != nil {
			return "", err
		}
		*read++
		if *read > maxRequestBytes {
			return "", errors.New("request head too large")
		}
		if c == '\n' {
			return strings.TrimSuffix(b.String(), "\r"), nil
		}
		b.WriteByte(c)
	}
}

func acceptResponse(key string) []byte {
	sum := sha1.Sum([]byte(key + wsMagic)) //nolint:gosec // RFC 6455 fixes SHA-1 here; not a digest of anything secret
	return []byte("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
		"Sec-WebSocket-Accept: " + base64.StdEncoding.EncodeToString(sum[:]) + "\r\n\r\n")
}

// counter reports refusals once per window rather than once per request.
type counter struct {
	mu        sync.Mutex
	windowEnd time.Time
	total     int
}

func newCounter() *counter { return &counter{windowEnd: time.Now().Add(failureWindow)} }

func (c *counter) record() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.roll()
	c.total++
}

func (c *counter) roll() {
	if time.Now().Before(c.windowEnd) {
		return
	}
	if c.total > 0 {
		// The ONE thing an anonymous caller can cause to be written, bounded at a line per window
		// however many of them there were.
		log.Printf("front door refused %d request(s) in the last %s", c.total, failureWindow)
	}
	c.total = 0
	c.windowEnd = time.Now().Add(failureWindow)
}

func (c *counter) flush() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.windowEnd = time.Time{}
	c.roll()
}
