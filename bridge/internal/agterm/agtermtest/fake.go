// Package agtermtest is a stand-in for agterm's control socket.
//
// It reproduces the two properties of the real one that the bridge is built around, because a fake
// that is easier to talk to than the real thing would prove nothing: **one request per connection**,
// and **a serial accept loop** that handles connections inline. If the bridge ever grows a connection
// pool or starts fanning out, tests against this fake break in the same way they would against
// agterm.
package agtermtest

import (
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

// Request is the shape the real ControlServer decodes: `target` is a TOP-LEVEL field, and args is a
// separate object. The fake keeps them apart on purpose — putting `target` inside args is a silent
// no-op against the real agterm that falls back to the active session, and a fake that accepted it
// either way would hide exactly that bug.
type Request struct {
	Cmd    string          `json:"cmd"`
	Target string          `json:"target"`
	Args   json.RawMessage `json:"args"`
}

// Fake is a unix-socket server that answers with whatever Respond returns.
type Fake struct {
	Path string

	mu       sync.Mutex
	requests []Request
	respond  func(Request) any
	ln       net.Listener
}

// Start binds a socket in a temp directory and serves until the test ends.
func Start(t *testing.T, respond func(Request) any) *Fake {
	t.Helper()
	// The real socket path has a ~104-byte limit; a temp dir keeps this well inside it.
	dir, err := os.MkdirTemp("", "agtermtest")
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "agterm.sock")
	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	f := &Fake{Path: path, respond: respond, ln: ln}
	go f.serve()
	t.Cleanup(func() { ln.Close(); os.RemoveAll(dir) })
	return f
}

func (f *Fake) serve() {
	for {
		conn, err := f.ln.Accept()
		if err != nil {
			return
		}
		// Inline, not in a goroutine: the real accept loop is serial and each request blocks
		// agterm's main actor.
		f.handle(conn)
	}
}

func (f *Fake) handle(conn net.Conn) {
	// One request, one response, then close — the real ControlServer's contract.
	defer conn.Close()

	dec := json.NewDecoder(conn)
	var req Request
	if err := dec.Decode(&req); err != nil {
		return
	}
	f.mu.Lock()
	f.requests = append(f.requests, req)
	respond := f.respond
	f.mu.Unlock()

	out, err := json.Marshal(respond(req))
	if err != nil {
		return
	}
	_, _ = conn.Write(append(out, '\n'))
}

// Requests returns every request received so far.
func (f *Fake) Requests() []Request {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]Request(nil), f.requests...)
}

// OK builds a successful response carrying `result`.
func OK(result any) map[string]any {
	return map[string]any{"ok": true, "result": result}
}

// Err builds agterm's error shape.
func Err(msg string) map[string]any {
	return map[string]any{"ok": false, "error": msg}
}
