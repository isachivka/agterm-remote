// Package zmxholdtest is a stand-in for a zmx daemon's socket.
//
// It records every frame it is sent, can send frames back, and closes a client that sends Detach -
// the one behaviour of the real daemon that a client's release sequence depends on. It classifies
// nothing and elects no leader: what a test asserts is the BYTES the client put on the wire and
// their order, because that is the contract with a daemon whose source is pinned by agterm's build.
package zmxholdtest

import (
	"encoding/binary"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

// Frame is one message as the daemon received it. Conn is which connection it came on, counting
// from zero in accept order, so a test can tell a Restore's fresh connection from a Hold's.
type Frame struct {
	Conn    int
	Tag     byte
	Payload []byte
}

// Daemon is a unix-socket server that records what it is sent.
type Daemon struct {
	// Path is the socket: a temp directory plus a daemon-style name, kept short because macOS caps
	// a socket path at 104 bytes and a test's own temp directory can run past that.
	Path string

	mu     sync.Mutex
	frames []Frame
	// dropOnInput makes the daemon hang up as soon as a client types - after the Init, before it
	// can state a size. What a daemon that dies partway through a claim looks like.
	dropOnInput bool
	conns       []net.Conn
	closed      []bool
	ln          net.Listener
}

// Start binds a socket and serves until the test ends.
func Start(t *testing.T) *Daemon {
	t.Helper()
	dir, err := os.MkdirTemp("", "zmxd")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	return StartAt(t, filepath.Join(dir, "agterm-1"))
}

// StartAt serves at a path of the test's choosing - the path an earlier daemon had, for a daemon
// that comes back after the socket went away.
func StartAt(t *testing.T, path string) *Daemon {
	t.Helper()
	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	d := &Daemon{Path: path, ln: ln}
	go d.serve()
	t.Cleanup(func() { ln.Close() })
	return d
}

func (d *Daemon) serve() {
	for {
		conn, err := d.ln.Accept()
		if err != nil {
			return
		}
		d.mu.Lock()
		i := len(d.conns)
		d.conns = append(d.conns, conn)
		d.closed = append(d.closed, false)
		d.mu.Unlock()
		go d.read(i, conn)
	}
}

func (d *Daemon) read(i int, conn net.Conn) {
	defer func() {
		conn.Close()
		d.mu.Lock()
		d.closed[i] = true
		d.mu.Unlock()
	}()
	header := make([]byte, 8) // @sizeOf(ipc.Header): a packed u8+u32 is a u40, which Zig sizes at 8
	for {
		if _, err := io.ReadFull(conn, header); err != nil {
			return
		}
		n := binary.LittleEndian.Uint32(header[1:5])
		payload := make([]byte, n)
		if _, err := io.ReadFull(conn, payload); err != nil {
			return
		}
		d.mu.Lock()
		d.frames = append(d.frames, Frame{Conn: i, Tag: header[0], Payload: payload})
		drop := d.dropOnInput && header[0] == 0
		d.mu.Unlock()
		// Detach: the real daemon closes the client. Done here so a Release that waits for the
		// daemon to hang up sees what it would see in production.
		if header[0] == 3 || drop {
			return
		}
	}
}

// Frames returns every frame received so far, in arrival order.
func (d *Daemon) Frames() []Frame {
	d.mu.Lock()
	defer d.mu.Unlock()
	return append([]Frame(nil), d.frames...)
}

// AwaitFrames waits until at least n frames have arrived and returns them, or fails the test. Frames
// arrive on another goroutine, so a test that read the list immediately would race its own subject.
func (d *Daemon) AwaitFrames(t *testing.T, n int) []Frame {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		if got := d.Frames(); len(got) >= n {
			return got
		}
		if time.Now().After(deadline) {
			t.Fatalf("waited for %d frames and have %d: %+v", n, len(d.Frames()), d.Frames())
		}
		time.Sleep(5 * time.Millisecond)
	}
}

// Send writes one frame to connection i, as the daemon would.
func (d *Daemon) Send(t *testing.T, i int, tag byte, payload []byte) {
	t.Helper()
	d.mu.Lock()
	if i >= len(d.conns) {
		d.mu.Unlock()
		t.Fatalf("no connection %d", i)
	}
	conn := d.conns[i]
	d.mu.Unlock()
	b := make([]byte, 8, 8+len(payload))
	b[0] = tag
	binary.LittleEndian.PutUint32(b[1:5], uint32(len(payload)))
	if _, err := conn.Write(append(b, payload...)); err != nil {
		t.Fatalf("send to connection %d: %v", i, err)
	}
}

// DropOnInput makes every connection hang up on the first Input it receives.
func (d *Daemon) DropOnInput(on bool) {
	d.mu.Lock()
	d.dropOnInput = on
	d.mu.Unlock()
}

// Drop closes connection i from the daemon's side without a Detach, as a daemon that detached
// all its clients or died does.
func (d *Daemon) Drop(t *testing.T, i int) {
	t.Helper()
	d.mu.Lock()
	if i >= len(d.conns) {
		d.mu.Unlock()
		t.Fatalf("no connection %d", i)
	}
	conn := d.conns[i]
	d.mu.Unlock()
	_ = conn.Close()
}

// AwaitClosed waits until connection i has hung up, or fails the test.
func (d *Daemon) AwaitClosed(t *testing.T, i int) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		d.mu.Lock()
		closed := i < len(d.closed) && d.closed[i]
		d.mu.Unlock()
		if closed {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("connection %d never closed", i)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

// Connections is how many clients have connected so far.
func (d *Daemon) Connections() int {
	d.mu.Lock()
	defer d.mu.Unlock()
	return len(d.conns)
}
