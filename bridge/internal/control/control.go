// Package control is the LOCAL door: a unix socket in the bridge's own config directory, serving one
// verb to a person sitting at this Mac.
//
// # Why this exists at all
//
// The owner asked for an undo they can run from the computer, as an agterm palette command, for the
// times the phone is not in their hand. The width setting lives in the bridge's store, and the window
// belongs to the machine they are sitting at.
//
// # Why it is not a second writer of the store
//
// **A separate process editing resize-cache.json races the running bridge and can lose the restore
// point** — the file that once held the only record of the owner's window geometry. So this asks the
// bridge to restore rather than doing it behind its back: the request goes through the same handler
// the phone's own "off" press goes through, so there is one writer, one code path, and one place where
// the restore point is consumed.
//
// The phone then corrects itself for free. Its toggle renders whatever the bridge reports, and the
// poll re-reads that on every reply, so a restore performed from the laptop unpresses the button on
// the phone within one poll without either end being told about the other.
//
// # Why a unix socket is safe here, in terms of who can open it
//
// **Filesystem permission IS the authentication, and there is nothing else to authenticate.**
//
//   - The socket lives inside the bridge's config directory, which is mode 0700, and the socket itself
//     is 0600. To connect you must be able to traverse that directory and open that file, which on this
//     machine means being the uid that owns it — or root, who has already won.
//   - **Nothing listens on a port.** A unix socket has no address off this machine: it cannot be
//     reached from the LAN, from the router, or from the internet, whatever the network is doing.
//   - The pinned mTLS front door is untouched. This adds no TLS, no certificate, no trust decision,
//     and no path by which a remote caller reaches anything. It is not a second front door; it is a
//     door with no outside.
//
// So there is no credential to check, and inventing one would be worse than useless: it would be a
// secret on the same disk, readable by exactly the people who can already open the socket.
//
// # The one verb
//
// Restore, and nothing else. It cannot resize to a width, cannot calibrate, cannot read a session and
// cannot type — the whole point is an UNDO, and a local door that could do the other things would be a
// second way to reach capabilities the pinned door spends a great deal of care rationing.
package control

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"os"
	"path/filepath"
	"time"
)

// SocketName is the file inside the bridge's config directory. Named for what speaks it.
const SocketName = "control.sock"

// SocketPath is where the socket lives for a given bridge directory. Exported so the command-line
// client and the bridge cannot disagree about it — a client looking in the wrong place would report
// "the bridge is not running" about a bridge that is.
func SocketPath(dir string) string { return filepath.Join(dir, SocketName) }

// Fit is the part of the bridge this door can reach. **Deliberately two methods and no more**: it can
// ask whether a fit is in force, and it can ask for the window back. There is no way to express
// anything else through this interface, which is a stronger statement than a switch that happens to
// handle one verb today.
type Fit interface {
	// FitInForce reports whether the bridge currently holds a fit.
	FitInForce() bool
	// RestoreFit puts the owner's window back and turns the setting off, through the same path the
	// phone's own off press takes.
	RestoreFit(ctx context.Context) error
}

// request is the whole protocol. One field, one accepted value.
type request struct {
	Verb string `json:"verb"`
}

// response says what happened, including when nothing did.
type response struct {
	OK bool `json:"ok"`
	// Restored is false when there was nothing to undo. **That is not a failure**, and the client
	// prints it as the ordinary outcome it is - see the note on doing nothing honestly.
	Restored bool   `json:"restored"`
	Message  string `json:"message,omitempty"`
	Error    string `json:"error,omitempty"`
}

// VerbRestore is the only verb this door accepts.
const VerbRestore = "restore"

// Listen starts the control socket and serves it until ctx is done.
//
// It refuses to start if something is already answering on that path, rather than unlinking it: a
// stale socket from a crashed run and a live socket from a second bridge look identical on disk, and
// clobbering the second one would leave the owner with a bridge whose local door silently belongs to
// another process.
func Listen(ctx context.Context, dir string, fit Fit) (net.Listener, error) {
	path := SocketPath(dir)

	// **A unix socket path has a hard length limit** - 104 bytes on macOS, in the kernel's sockaddr -
	// and exceeding it fails as `bind: invalid argument`, which names neither the path nor the length.
	// Said plainly here because the alternative is a puzzling line in the owner's log; the real
	// directory is nowhere near it, and a test's temp directory was.
	if len(path) >= maxSocketPath {
		return nil, fmt.Errorf("%s is %d bytes, over the %d-byte limit for a unix socket path",
			path, len(path), maxSocketPath)
	}

	if conn, err := net.DialTimeout("unix", path, dialProbe); err == nil {
		_ = conn.Close()
		return nil, fmt.Errorf("%s is already being served; another bridge is running against this directory", path)
	}
	// Nothing answered, so anything at that path is a leftover. Removing it is what makes a bridge
	// restartable after a crash without a manual cleanup step.
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}

	ln, err := net.Listen("unix", path)
	if err != nil {
		return nil, err
	}

	// **THE OUTGOING PROCESS MUST NOT UNLINK THE INCOMING PROCESS'S SOCKET.**
	//
	// Go's UnixListener removes its path on Close by default, and it does NOT check whether the file
	// there is still the one it created. So on a restart - which happens on every merge, because the
	// reload agent kickstarts the bridge when main moves - the sequence is:
	//
	//	  new instance: remove the stale entry, bind a fresh inode at the same path
	//	  old instance: Close() -> unlink(path) -> removes the NEW instance's socket
	//
	// The result is a listener nobody can reach: the fd is open, the process is healthy, this file
	// logs success, and the client correctly reports "no bridge is listening" about a bridge that is.
	// Observed 2026-07-30 with two starts thirty seconds apart, and it cost the owner their first
	// attempt at the palette command.
	//
	// # Why this rather than bind-then-rename
	//
	// Renaming a socket bound at a temporary path over the target is the atomic version, and it does
	// not fix this. The old instance unlinks `path` regardless of which inode is sitting there, so a
	// renamed-in socket is removed exactly as a freshly bound one is. Atomicity is not the property
	// that was missing - **ownership is**: a process removing a file it no longer owns.
	//
	// Turning the unlink off is therefore the whole fix, and the cleanup it removes is already done at
	// the other end: the startup path above probes and then removes a stale entry, which is the only
	// moment when it is knowable whether the file belongs to anyone.
	//
	// The cost, stated: a clean shutdown now leaves a socket file behind. It is 0 bytes, it is replaced
	// on the next start, and a client that dials it gets a refusal rather than a wrong answer.
	if unix, ok := ln.(*net.UnixListener); ok {
		unix.SetUnlinkOnClose(false)
	}
	// **Set explicitly rather than left to the umask.** A umask of 0 would otherwise produce a
	// world-writable socket, and "the permissions are usually right" is not a security property.
	if err := os.Chmod(path, 0o600); err != nil {
		_ = ln.Close()
		return nil, err
	}

	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()

	go serve(ctx, ln, fit)

	// **Checked from the outside, because that is the only thing a client can do.**
	//
	// Everything above proves this process bound a socket. It proves nothing about what a client
	// dialling that PATH will find, and those two came apart for a whole afternoon: the bind
	// succeeded, the log said so, and the entry had been unlinked by someone else. A caller of this
	// function should be able to log "the door is open" and be right about the door rather than about
	// the bind, so the check is a real connection to the real path.
	if conn, err := net.DialTimeout("unix", path, dialProbe); err != nil {
		_ = ln.Close()
		return nil, fmt.Errorf("%s was bound but cannot be reached: %w", path, err)
	} else {
		_ = conn.Close()
	}

	return ln, nil
}

func serve(ctx context.Context, ln net.Listener, fit Fit) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			// The listener closing is how this loop is meant to end.
			return
		}
		go handle(ctx, conn, fit)
	}
}

func handle(ctx context.Context, conn net.Conn, fit Fit) {
	defer conn.Close()
	// Bounded, like every other wait in this bridge: a local caller that connects and says nothing
	// must not hold the goroutine for ever.
	_ = conn.SetDeadline(time.Now().Add(callTimeout))

	line, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil && line == "" {
		return
	}

	var req request
	if err := json.Unmarshal([]byte(line), &req); err != nil {
		reply(conn, response{Error: "that is not a control request"})
		return
	}
	if req.Verb != VerbRestore {
		// Names no alternatives. The set is one verb long and a caller guessing at others learns
		// nothing from us.
		reply(conn, response{Error: fmt.Sprintf("unknown verb %q", req.Verb)})
		return
	}

	// **Nothing in force means nothing to undo, and that is an ordinary answer.** Resizing anyway
	// would be acting on a guess about a window the owner is sitting in front of.
	if !fit.FitInForce() {
		reply(conn, response{OK: true, Restored: false, Message: "no fit is in force"})
		return
	}

	if err := fit.RestoreFit(ctx); err != nil {
		log.Printf("control: the window could not be restored: %v", err)
		reply(conn, response{Error: err.Error()})
		return
	}
	log.Printf("control: restored the window at the owner's request from this machine")
	reply(conn, response{OK: true, Restored: true, Message: "the window is back the way it was"})
}

func reply(conn net.Conn, resp response) {
	raw, err := json.Marshal(resp)
	if err != nil {
		return
	}
	_, _ = conn.Write(append(raw, '\n'))
}

const (
	// dialProbe only has to answer "is anything listening", which is instant for a unix socket.
	dialProbe = 200 * time.Millisecond
	// callTimeout covers a restore, which is one resize on a live window.
	callTimeout = 30 * time.Second
	// maxSocketPath is the smallest of the platform limits (macOS sun_path is 104). Checked rather
	// than discovered, so the failure names itself.
	maxSocketPath = 104
)
