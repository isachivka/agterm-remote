package control

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"testing"
	"time"
)

// fakeFit is the bridge as this door sees it: a flag and a restore that records being called.
type fakeFit struct {
	inForce  bool
	restores int
	fail     bool
}

func (f *fakeFit) FitInForce() bool { return f.inForce }

func (f *fakeFit) RestoreFit(context.Context) error {
	if f.fail {
		return errors.New("agterm would not resize the window")
	}
	f.restores++
	f.inForce = false
	return nil
}

// shortDir is NOT t.TempDir(): that embeds the test's name in the path, and a unix socket path is
// capped at 104 bytes - long test names push it over and the failure reads as `bind: invalid
// argument`. Measured here first, which is why Listen now checks the length and says so.
func shortDir(t *testing.T) string {
	t.Helper()
	dir, err := os.MkdirTemp("", "bos")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	return dir
}

func serving(t *testing.T, fit Fit) string {
	t.Helper()
	dir := shortDir(t)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)

	ln, err := Listen(ctx, dir, fit)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	return SocketPath(dir)
}

func ask(t *testing.T, path, verb string) response {
	t.Helper()
	conn, err := net.DialTimeout("unix", path, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))

	if _, err := io.WriteString(conn, fmt.Sprintf("{\"verb\":%q}\n", verb)); err != nil {
		t.Fatal(err)
	}
	line, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil && line == "" {
		t.Fatal(err)
	}
	var resp response
	if err := json.Unmarshal([]byte(line), &resp); err != nil {
		t.Fatalf("unparseable reply %q: %v", line, err)
	}
	return resp
}

func TestRestoreAsksTheBridgeAndReportsIt(t *testing.T) {
	fit := &fakeFit{inForce: true}
	resp := ask(t, serving(t, fit), VerbRestore)

	if !resp.OK || !resp.Restored {
		t.Fatalf("got %+v, want a restore", resp)
	}
	if fit.restores != 1 {
		t.Errorf("the bridge was asked %d times, want once", fit.restores)
	}
}

// **Nothing in force is an ordinary answer, not a failure.** The owner may run this when the fit is
// already off, and a command that resized their window anyway would be acting on a guess about a
// window they are sitting in front of.
func TestWithNoFitInForceItDoesNothingAndSaysSo(t *testing.T) {
	fit := &fakeFit{inForce: false}
	resp := ask(t, serving(t, fit), VerbRestore)

	if !resp.OK {
		t.Errorf("doing nothing was reported as a failure: %+v", resp)
	}
	if resp.Restored {
		t.Error("claimed to restore a window with no fit in force")
	}
	if fit.restores != 0 {
		t.Errorf("resized %d times with nothing in force", fit.restores)
	}
}

// The set is one verb long. Anything else is refused, and the refusal names no alternatives.
func TestTheOnlyVerbIsRestore(t *testing.T) {
	fit := &fakeFit{inForce: true}
	path := serving(t, fit)

	for _, verb := range []string{"resize", "sessions", "screen", "type", "calibrate", ""} {
		resp := ask(t, path, verb)
		if resp.OK {
			t.Errorf("verb %q was accepted by the local door", verb)
		}
		if fit.restores != 0 {
			t.Fatalf("verb %q reached the bridge", verb)
		}
	}
}

// **The permission IS the authentication**, so it is asserted rather than assumed. A umask of 0 would
// otherwise produce a socket anyone on the machine could open.
func TestTheSocketIsOwnerOnly(t *testing.T) {
	path := serving(t, &fakeFit{})

	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Errorf("socket mode is %o, want 600 - anyone on this machine could undo the owner's window", perm)
	}
}

// A failed restore is reported as one. The bridge's own words are passed through, because they name
// what went wrong with the window and that is what the person at the keyboard can act on.
func TestAFailedRestoreIsNotReportedAsSuccess(t *testing.T) {
	fit := &fakeFit{inForce: true, fail: true}
	resp := ask(t, serving(t, fit), VerbRestore)

	if resp.OK || resp.Restored {
		t.Fatalf("a failed restore came back as %+v", resp)
	}
	if resp.Error == "" {
		t.Error("the refusal said nothing about what went wrong")
	}
}

// A second bridge against the same directory must not silently take the door from the first: a stale
// socket and a live one look identical on disk, and clobbering the live one would leave the owner's
// running bridge unreachable from their palette command with nothing to point at.
func TestItRefusesToTakeOverASocketSomethingElseIsServing(t *testing.T) {
	dir := shortDir(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	first, err := Listen(ctx, dir, &fakeFit{})
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()

	if _, err := Listen(ctx, dir, &fakeFit{}); err == nil {
		t.Fatal("a second listener took over a socket that was already being served")
	}
}

// **After a restart, a client can CONNECT.** The property, stated as the client experiences it.
//
// Not "the second listener exists" - that was true all afternoon while the owner's palette command
// reported no bridge listening. Go's UnixListener unlinks its path on Close without checking whether
// the file there is still its own, so the outgoing instance deleted the incoming instance's socket and
// left a listener nobody could reach: fd open, process healthy, log claiming success.
//
// The sequence below is what a restart does, in order. It fails without SetUnlinkOnClose(false) - and
// it fails on the DIAL, which is the only assertion that could have caught this.
func TestAfterARestartAClientCanStillConnect(t *testing.T) {
	dir := shortDir(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// The outgoing instance, serving.
	outgoing, err := Listen(ctx, dir, &fakeFit{})
	if err != nil {
		t.Fatal(err)
	}

	// The incoming instance. A restarting bridge finds the path free - the old process is on its way
	// out - removes anything stale, and binds a fresh inode at the same path.
	if err := os.Remove(SocketPath(dir)); err != nil {
		t.Fatal(err)
	}
	incoming, err := Listen(ctx, dir, &fakeFit{inForce: true})
	if err != nil {
		t.Fatal(err)
	}
	defer incoming.Close()

	// The outgoing instance finishes shutting down. This is the line that used to delete the socket
	// the incoming instance had just bound.
	if err := outgoing.Close(); err != nil {
		t.Fatal(err)
	}

	// THE DIAL. Everything above can succeed while this fails, which is exactly what happened.
	conn, err := net.DialTimeout("unix", SocketPath(dir), time.Second)
	if err != nil {
		t.Fatalf("a client cannot reach the bridge after a restart: %v", err)
	}
	defer conn.Close()

	// And it is the INCOMING instance answering, not a leftover: this one has a fit in force.
	resp := ask(t, SocketPath(dir), VerbRestore)
	if !resp.OK || !resp.Restored {
		t.Errorf("the surviving socket is not the new instance's: %+v", resp)
	}
}
