package agterm

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// concurrentFake accepts connections in PARALLEL — deliberately unlike the real agterm — so that a
// client which fanned out would be visible. Against the real serial accept loop the overlap would be
// invisible: agterm would queue the connections and the client would look well-behaved while actually
// holding several sockets open and competing with the owner's own agtermctl.
type concurrentFake struct {
	path       string
	inFlight   atomic.Int32
	maxFlight  atomic.Int32
	handled    atomic.Int32
	hold       time.Duration
	ln         net.Listener
	closeOnce  sync.Once
	respondRaw string
}

func startConcurrentFake(t *testing.T, hold time.Duration, respond string) *concurrentFake {
	t.Helper()
	dir, err := os.MkdirTemp("", "agtermconc")
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "agterm.sock")
	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	f := &concurrentFake{path: path, hold: hold, ln: ln, respondRaw: respond}
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go f.handle(conn) // parallel on purpose
		}
	}()
	t.Cleanup(func() { f.closeOnce.Do(func() { ln.Close(); os.RemoveAll(dir) }) })
	return f
}

// handle measures the window that actually means something: between a request being fully read and
// its response being written.
//
// The obvious window — the whole connection lifetime — is racy, and it failed on CI while passing
// locally. The client releases its mutex as soon as it has READ the response, so the next call can
// dial while this goroutine has not yet run its own teardown. That counts two connections without the
// client ever having had two requests in flight, which is the property under test. Incrementing after
// the decode and decrementing before the write closes it: the client cannot start another request
// until it has read a response that is written after the decrement.
func (f *concurrentFake) handle(conn net.Conn) {
	defer conn.Close()

	var req request
	if err := json.NewDecoder(conn).Decode(&req); err != nil {
		return
	}

	now := f.inFlight.Add(1)
	for {
		max := f.maxFlight.Load()
		if now <= max || f.maxFlight.CompareAndSwap(max, now) {
			break
		}
	}
	time.Sleep(f.hold)
	f.handled.Add(1)
	f.inFlight.Add(-1)

	_, _ = conn.Write([]byte(f.respondRaw + "\n"))
}

// The measurement that justifies this: eight sequential reads took 3.5 ms, the same eight issued
// concurrently took 8.9 ms. agterm's accept loop is serial and each request blocks its main actor, so
// fanning out is 2.5x slower AND competes with the owner's own agtermctl.
func TestClientSerialisesItsCalls(t *testing.T) {
	fake := startConcurrentFake(t, 40*time.Millisecond, `{"ok":true,"result":{"text":"x"}}`)
	c := New(fake.path)

	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = c.Text(context.Background(), "F2F9559C-BB15-4E8F-AA66-381FA0CDE9B9", 50, PaneLeft)
		}()
	}
	wg.Wait()

	if got := fake.handled.Load(); got != 8 {
		t.Fatalf("expected 8 completed calls, got %d", got)
	}
	if got := fake.maxFlight.Load(); got != 1 {
		t.Fatalf("the client held %d connections at once; agterm's accept loop is serial and the "+
			"client must be too", got)
	}
}

// One request per connection is agterm's contract, so there is nothing to pool. This asserts the
// client opens a fresh connection each time rather than trying to reuse one — which against the real
// agterm would simply hang, since it closes after responding.
func TestEachCallUsesItsOwnConnection(t *testing.T) {
	fake := startConcurrentFake(t, 0, `{"ok":true,"result":{"text":"x"}}`)
	c := New(fake.path)

	for i := 0; i < 3; i++ {
		if _, err := c.Text(context.Background(), "F2F9559C-BB15-4E8F-AA66-381FA0CDE9B9", 50, PaneLeft); err != nil {
			t.Fatalf("call %d: %v", i, err)
		}
	}
	if got := fake.handled.Load(); got != 3 {
		t.Fatalf("expected 3 connections each carrying one request, got %d", got)
	}
}

// A sleeping or absent laptop must be distinguishable from a command that failed, or the
// app cannot say "your laptop is not answering" without inventing a diagnosis.
func TestMissingSocketIsUnavailableNotAnError(t *testing.T) {
	c := New("/nonexistent/agterm.sock")

	_, err := c.Tree(context.Background())
	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("expected ErrUnavailable, got %v", err)
	}
}

// A far end that accepts and then says nothing must not park the bridge. The client's deadline sits
// inside agterm's own 10 s, so the bridge gives up first and reports a clean failure rather than
// inheriting a half-state it cannot describe.
func TestASilentFarEndTimesOut(t *testing.T) {
	dir, err := os.MkdirTemp("", "agtermsilent")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	path := filepath.Join(dir, "agterm.sock")
	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			// Accept and never answer.
			_ = conn
		}
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	start := time.Now()
	_, err = New(path).Tree(ctx)

	if err == nil {
		t.Fatal("a silent far end must not look like success")
	}
	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("expected ErrUnavailable, got %v", err)
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Fatalf("took %v; the caller's context must bound the call", elapsed)
	}
}

// A response larger than the cap is refused rather than allocated. agterm caps requests at 1 MiB but
// leaves responses uncapped, so this bounds what a malfunctioning or replaced far end can make the
// bridge hold.
func TestOversizedResponseIsRefused(t *testing.T) {
	dir, err := os.MkdirTemp("", "agtermbig")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	path := filepath.Join(dir, "agterm.sock")
	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		_ = json.NewDecoder(conn).Decode(&request{})
		// A response that never ends.
		blob := make([]byte, 1<<20)
		for i := range blob {
			blob[i] = 'a'
		}
		for i := 0; i < 16; i++ {
			if _, err := conn.Write(blob); err != nil {
				return
			}
		}
	}()

	_, err = New(path).Tree(context.Background())
	if err == nil {
		t.Fatal("an unbounded response must be refused")
	}
	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("expected ErrUnavailable, got %v", err)
	}
}

// --- Running unattended ---------------------------------------------------------------------------

// A socket FILE that nothing is listening on. agterm unlinks its socket on a clean exit, but a
// force-quit leaves one behind — agterm's own start path unlinks a stale socket for exactly this
// reason. The bridge must report it as "not answering" rather than as a command failure, or the app
// cannot say "your laptop is not answering" without inventing a diagnosis.
func TestAStaleSocketFileIsUnavailable(t *testing.T) {
	dir, err := os.MkdirTemp("", "agtermstale")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	path := filepath.Join(dir, "agterm.sock")

	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	// Close the listener but leave the file, which is what a force-quit leaves behind.
	ln.(*net.UnixListener).SetUnlinkOnClose(false)
	ln.Close()
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("the stale socket file should still exist: %v", err)
	}

	if _, err := New(path).Tree(context.Background()); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("expected ErrUnavailable for a stale socket, got %v", err)
	}
}

// The bridge outlives agterm restarts. Nothing is cached and every call dials fresh, so recovery is
// a property of the design rather than of a reconnect loop — but it is worth a test, because a
// future connection cache would break it silently and the owner would see a bridge that needs
// restarting whenever they restart agterm.
func TestTheClientRecoversWhenAgtermComesBack(t *testing.T) {
	dir, err := os.MkdirTemp("", "agtermrecover")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	path := filepath.Join(dir, "agterm.sock")
	c := New(path)

	if _, err := c.Tree(context.Background()); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("with no agterm, expected ErrUnavailable, got %v", err)
	}

	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			_ = json.NewDecoder(conn).Decode(&request{})
			_, _ = conn.Write([]byte(`{"ok":true,"result":{"tree":{"workspaces":[]}}}` + "\n"))
			conn.Close()
		}
	}()

	if _, err := c.Tree(context.Background()); err != nil {
		t.Fatalf("the bridge must recover when agterm returns, without restarting: %v", err)
	}
}

// **The resize carries the height the window already has, and agterm requires one.**
//
// The height was deleted from this call on 2026-07-31 and every resize immediately failed:
// `window.resize requires positive width and height`. A width-only request is not expressible at this
// protocol, so the guarantee the owner asked for cannot be "send no height" - it has to be "send the
// height it already has", read immediately before the call.
//
// This test asserts the WIRE carries both, because that is what agterm acts on and what its refusal
// was about.
func TestWindowResizeCarriesBothDimensions(t *testing.T) {
	dir, err := os.MkdirTemp("", "agtermheight")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })

	path := filepath.Join(dir, "agterm.sock")
	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })

	var mu sync.Mutex
	var seen []byte
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			raw := make([]byte, 4096)
			n, _ := conn.Read(raw)
			mu.Lock()
			seen = append([]byte(nil), raw[:n]...)
			mu.Unlock()
			_, _ = conn.Write([]byte(`{"ok":true,"result":{}}` + "\n"))
			conn.Close()
		}
	}()

	if err := New(path).ResizeWindow(context.Background(), "W1", 769, 1084); err != nil {
		t.Fatal(err)
	}

	mu.Lock()
	raw := seen
	mu.Unlock()

	var sent struct {
		Cmd  string         `json:"cmd"`
		Args map[string]any `json:"args"`
	}
	if err := json.Unmarshal(raw, &sent); err != nil {
		t.Fatalf("unparseable request %q: %v", raw, err)
	}
	if sent.Cmd != "window.resize" {
		t.Fatalf("sent %q, want window.resize", sent.Cmd)
	}
	// Both, and both positive: agterm refuses the request otherwise, which is how the fit broke.
	if sent.Args["width"] != float64(769) {
		t.Errorf("window.resize carried width %v: %s", sent.Args["width"], raw)
	}
	if sent.Args["height"] != float64(1084) {
		t.Errorf("window.resize carried height %v: %s - agterm refuses a resize without one",
			sent.Args["height"], raw)
	}
}
