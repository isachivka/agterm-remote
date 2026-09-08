package frontdoor

import (
	"bufio"
	"crypto/rand"
	"encoding/base64"
	"io"
	"log"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func start(t *testing.T) (*Listener, string) {
	t.Helper()
	log.SetOutput(io.Discard)
	inner, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	l := Listen(inner)
	t.Cleanup(func() { l.Close() })
	return l, inner.Addr().String()
}

func dial(t *testing.T, addr string) net.Conn {
	t.Helper()
	c, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))
	t.Cleanup(func() { c.Close() })
	return c
}

func readAll(t *testing.T, c net.Conn) []byte {
	t.Helper()
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	body, _ := io.ReadAll(c)
	return body
}

func key(t *testing.T) string {
	t.Helper()
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		t.Fatal(err)
	}
	return base64.StdEncoding.EncodeToString(raw)
}

func upgradeRequest(k string) string {
	return "GET /anything HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
		"Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: " + k + "\r\n\r\n"
}

// --- RULE 1: one response, identical by construction ---------------------------------------------

// Four different ways to be wrong, byte-identical answers.
//
// The point is not that they agree today. There is exactly one path that writes to a rejected
// connection, so a fifth failure mode added later cannot answer differently by accident — but a
// reviewer cannot see "exactly one path" in a diff, so it is asserted.
func TestEveryUnauthenticatedRequestGetsAByteIdenticalResponse(t *testing.T) {
	_, addr := start(t)

	k := key(t)
	requests := map[string]string{
		"bad path":         "GET /../../etc/passwd HTTP/1.1\r\nHost: x\r\n\r\n",
		"bad method":       "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n",
		"missing upgrade":  "GET / HTTP/1.1\r\nHost: x\r\nConnection: Upgrade\r\n\r\n",
		"malformed body":   "GET / HTTP/1.1\r\n\x00\x01\x02 not a header at all\r\n\r\n",
		"wrong ws version": strings.Replace(upgradeRequest(k), "Version: 13", "Version: 8", 1),
		// short one would be accepting a request the phone will never send.
		"short key": strings.Replace(upgradeRequest(k),
			"Sec-WebSocket-Key: "+k, "Sec-WebSocket-Key: c2hvcnQ=", 1),
	}

	var reference []byte
	var referenceName string
	for name, request := range requests {
		c := dial(t, addr)
		if _, err := c.Write([]byte(request)); err != nil {
			t.Fatalf("%s: write: %v", name, err)
		}
		got := readAll(t, c)
		if len(got) == 0 {
			t.Fatalf("%s: the front door said nothing at all", name)
		}
		if reference == nil {
			reference, referenceName = got, name
			continue
		}
		if string(got) != string(reference) {
			t.Fatalf("%s answered differently from %s:\n  %q\n  %q\n"+
				"every unauthenticated request must get the same status, headers and body - "+
				"a difference is a map", name, referenceName, got, reference)
		}
	}
	// And it says nothing about itself.
	lower := strings.ToLower(string(reference))
	for _, leak := range []string{"agterm", "bridge", "server:", "go", "date:"} {
		if strings.Contains(lower, leak) {
			t.Errorf("the refusal leaks %q: %q", leak, reference)
		}
	}
}

// The valid upgrade is answered differently, or the test above would pass on a door that refuses
// everything.
func TestAValidUpgradeIsAccepted(t *testing.T) {
	l, addr := start(t)
	c := dial(t, addr)

	if _, err := c.Write([]byte(upgradeRequest(key(t)))); err != nil {
		t.Fatal(err)
	}
	line, err := bufio.NewReader(c).ReadString('\n')
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(line, "HTTP/1.1 101 ") {
		t.Fatalf("expected a 101, got %q", line)
	}

	accepted := make(chan net.Conn, 1)
	go func() {
		conn, err := l.Accept()
		if err == nil {
			accepted <- conn
		}
	}()
	select {
	case <-accepted:
	case <-time.After(3 * time.Second):
		t.Fatal("an upgraded connection never reached Accept")
	}
}

// --- RULE 2: the semaphore is acquired before anything is parsed ----------------------------------

// Saturate with connections that send NOTHING, then confirm a further caller is dropped without a
// response.
//
// A caller that sends nothing cannot have been parsed, so if the door is saturated by silence the
// slot was taken before parsing. Acquiring after would leave these connections outside the bound and
// the extra caller would be answered.
func TestTheSemaphoreIsTakenBeforeAnythingIsParsed(t *testing.T) {
	_, addr := start(t)

	for i := 0; i < maxConcurrentUpgrades; i++ {
		dial(t, addr) // connect, send nothing, hold
	}
	time.Sleep(300 * time.Millisecond)

	extra := dial(t, addr)
	if _, err := extra.Write([]byte(upgradeRequest(key(t)))); err != nil {
		t.Fatal(err)
	}
	if body := readAll(t, extra); len(body) != 0 {
		t.Fatalf("a caller past the bound got %d bytes; silent connections must already hold the "+
			"slots, which is only true if the slot is taken before any parsing", len(body))
	}
}

func TestCapacityReturnsWhenTheTrafficStops(t *testing.T) {
	_, addr := start(t)

	var held []net.Conn
	for i := 0; i < maxConcurrentUpgrades; i++ {
		c, err := net.Dial("tcp", addr)
		if err != nil {
			t.Fatal(err)
		}
		held = append(held, c)
	}
	time.Sleep(300 * time.Millisecond)
	for _, c := range held {
		c.Close()
	}
	time.Sleep(300 * time.Millisecond)

	// No window to wait out: the bound has no memory.
	c := dial(t, addr)
	if _, err := c.Write([]byte(upgradeRequest(key(t)))); err != nil {
		t.Fatal(err)
	}
	line, err := bufio.NewReader(c).ReadString('\n')
	if err != nil || !strings.HasPrefix(line, "HTTP/1.1 101 ") {
		t.Fatalf("capacity must return with the traffic; got %q (%v)", line, err)
	}
}

// --- RULE 3: refusals are counted, never logged per request ---------------------------------------

// Fails if a per-request log line is ever reintroduced, rather than asserting that today there is
// none.
//
// Before the front door existed a caller had to survive a TLS handshake to make the bridge write
// anything. Now a well-formed HTTP request is enough, so one line per anonymous request would be an
// unbounded write against the owner's disk, reachable by anyone.
func TestAnonymousRequestsAreCountedNotLogged(t *testing.T) {
	_, addr := start(t)

	var written countingWriter
	log.SetOutput(&written)
	t.Cleanup(func() { log.SetOutput(io.Discard) })

	for i := 0; i < 30; i++ {
		c := dial(t, addr)
		_, _ = c.Write([]byte("GET / HTTP/1.1\r\nHost: x\r\n\r\n"))
		readAll(t, c)
	}
	time.Sleep(200 * time.Millisecond)

	if n := written.lines.Load(); n != 0 {
		t.Fatalf("30 refused requests wrote %d log line(s) inside the window; refusals must be "+
			"counted and reported once per window, not once per request", n)
	}
}

func TestRefusalsAreReportedInAggregate(t *testing.T) {
	c := newCounter()
	var written countingWriter
	log.SetOutput(&written)
	t.Cleanup(func() { log.SetOutput(io.Discard) })

	for i := 0; i < 50; i++ {
		c.record()
	}
	if n := written.lines.Load(); n != 0 {
		t.Fatalf("nothing may be written inside the window, got %d lines", n)
	}
	c.flush()
	if n := written.lines.Load(); n != 1 {
		t.Fatalf("the window must report exactly one line, got %d", n)
	}
}

type countingWriter struct{ lines atomic.Int32 }

func (c *countingWriter) Write(p []byte) (int, error) {
	c.lines.Add(1)
	return len(p), nil
}

// A client that OFFERS an extension or a subprotocol is upgraded, and neither is negotiated.
//
// OkHttp offers permessage-deflate on every WebSocket. Refusing that turned the only client this
// project has into a scanner as far as this door was concerned, and the app reported that the laptop
// was not answering about a laptop that had answered.
//
// Both are declined the RFC 6455 way - by echoing nothing back - so the connection stays unextended
// and without a subprotocol exactly as before. They are asserted together because refusing one while
// tolerating the other would let a caller tell two inputs apart.
func TestAnOfferedExtensionOrSubprotocolIsUpgradedAndNotNegotiated(t *testing.T) {
	_, addr := start(t)

	// All THREE, not the two that happened to disagree: a caller must not be able to tell an offer
	// from no offer either, or the map is simply drawn one input further out.
	var reference string
	var referenceName string
	for _, c := range []struct{ name, header string }{
		{"offers nothing", "Host: x"},
		{"offers an extension", "Sec-WebSocket-Extensions: permessage-deflate"},
		{"offers a subprotocol", "Sec-WebSocket-Protocol: chat"},
	} {
		k := key(t)
		conn := dial(t, addr)
		request := strings.Replace(upgradeRequest(k), "Host: x", c.header, 1)
		if _, err := conn.Write([]byte(request)); err != nil {
			t.Fatalf("%s: write: %v", c.name, err)
		}
		got := strings.ToLower(string(readAll(t, conn)))
		if !strings.Contains(got, "101 switching protocols") {
			t.Fatalf("%s must be upgraded, got %q", c.name, got)
		}
		if strings.Contains(got, "sec-websocket-extensions") || strings.Contains(got, "sec-websocket-protocol") {
			t.Fatalf("%s must not have anything negotiated back, got %q", c.name, got)
		}
		// The accept digest is derived from the caller's own key, so compare everything else.
		stripped := strings.Split(got, "sec-websocket-accept")[0]
		if reference == "" {
			reference, referenceName = stripped, c.name
			continue
		}
		if stripped != reference {
			t.Fatalf("%s answered differently from %s:\n  %q\n  %q", c.name, referenceName, stripped, reference)
		}
	}
}
