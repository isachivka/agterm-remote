package zmxhold_test

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/ptysize"
	"github.com/isachivka/agterm-remote/bridge/internal/zmxhold"
)

// TestRealDaemonHold takes over a real zmx daemon that the person running the test set up for the
// purpose. It is NEVER run against agterm's own daemons: it resizes the pty behind the socket.
//
//	ZMX_DIR=/tmp/z zmx run probe -d true      # a scratch daemon with nothing in it
//	BOS_REAL_ZMX_SOCKET=/tmp/z/probe BOS_REAL_ZMX_PID=<pid from zmx list> go test ./internal/zmxhold -run Real -v
//
// The second half needs a leader to take over from: a real `zmx attach` on a pty of its own (with
// ZMX_SESSION emptied, as agterm does) that types one "x" into that pty when the file named by
// BOS_REAL_ZMX_TYPE_FILE appears. That is the Mac user pressing a key, and it is what proved that
// the daemon hands the size back to them and that a second claim takes it again.
//
// This is the test that found the 8-byte header: the fake daemon in zmxholdtest reads whatever
// this package writes, so only a real one could disagree.
func TestRealDaemonHold(t *testing.T) {
	socket := os.Getenv("BOS_REAL_ZMX_SOCKET")
	pidText := os.Getenv("BOS_REAL_ZMX_PID")
	if socket == "" || pidText == "" {
		t.Skip("set BOS_REAL_ZMX_SOCKET and BOS_REAL_ZMX_PID to a scratch daemon of your own")
	}
	pid, err := strconv.Atoi(pidText)
	if err != nil {
		t.Fatal(err)
	}
	read := func() zmxhold.Size {
		t.Helper()
		s, err := ptysize.Read(pid)
		if err != nil {
			t.Fatal(err)
		}
		return zmxhold.Size{Rows: s.Rows, Cols: s.Cols}
	}
	history := func() string {
		t.Helper()
		cmd := exec.Command("zmx", "history", filepath.Base(socket))
		cmd.Env = append(os.Environ(), "ZMX_DIR="+filepath.Dir(socket))
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("zmx history: %v: %s", err, out)
		}
		lines := strings.Split(strings.TrimRight(string(out), "\n"), "\n")
		if len(lines) > 3 {
			lines = lines[len(lines)-3:]
		}
		return strings.Join(lines, "\n")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	original := read()
	t.Logf("before: %+v\n%s", original, history())

	tall := zmxhold.Size{Rows: 200, Cols: 80}
	h, err := zmxhold.Open(ctx, socket, tall)
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(500 * time.Millisecond)
	if got := read(); got != tall {
		t.Fatalf("after the claim the pty is %+v, wanted %+v", got, tall)
	}
	t.Logf("held: %+v\n%s", read(), history())

	if err := h.Release(original); err != nil {
		t.Fatal(err)
	}
	time.Sleep(500 * time.Millisecond)
	if got := read(); got != original {
		t.Fatalf("after the release the pty is %+v, wanted %+v", got, original)
	}
	t.Logf("released: %+v", read())

	// The bridge dies with the hold live: the daemon clears its leader, the pty stays tall.
	h, err = zmxhold.Open(ctx, socket, tall)
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(500 * time.Millisecond)
	if got := read(); got != tall {
		t.Fatalf("second claim: pty is %+v", got)
	}
	_ = h.Close()
	time.Sleep(500 * time.Millisecond)
	t.Logf("after a dead hold: %+v", read())
	if err := zmxhold.Restore(ctx, socket, original); err != nil {
		t.Fatal(err)
	}
	time.Sleep(500 * time.Millisecond)
	if got := read(); got != original {
		t.Fatalf("after Restore the pty is %+v, wanted %+v", got, original)
	}
	t.Logf("restored: %+v\n%s", read(), history())

	// With a leader already there (the harness's own zmx attach, standing in for agterm), the
	// claim has to take leadership through the paste; a key typed on that client takes it back
	// and the size with it; a second claim takes it again.
	typeFile := os.Getenv("BOS_REAL_ZMX_TYPE_FILE")
	if typeFile == "" {
		t.Log("no BOS_REAL_ZMX_TYPE_FILE: the takeover from a live leader was not exercised")
		return
	}
	theirs := read()
	t.Logf("their size: %+v", theirs)
	h, err = zmxhold.Open(ctx, socket, tall)
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(500 * time.Millisecond)
	if got := read(); got != tall {
		t.Fatalf("takeover from a live leader: pty is %+v, wanted %+v", got, tall)
	}
	t.Logf("taken over: %+v", read())
	if err := os.WriteFile(typeFile, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	time.Sleep(1500 * time.Millisecond)
	if got := read(); got != theirs {
		t.Fatalf("after their keystroke the pty is %+v, expected them to take it back to %+v", got, theirs)
	}
	t.Logf("they typed, size back to theirs: %+v (hold err: %v)", read(), h.Err())
	if err := h.Claim(tall); err != nil {
		t.Fatal(err)
	}
	time.Sleep(500 * time.Millisecond)
	if got := read(); got != tall {
		t.Fatalf("re-claim: pty is %+v, wanted %+v", got, tall)
	}
	t.Logf("re-claimed: %+v", read())
	if err := h.Release(theirs); err != nil {
		t.Fatal(err)
	}
	time.Sleep(500 * time.Millisecond)
	if got := read(); got != theirs {
		t.Fatalf("release to theirs: pty is %+v", got)
	}
	t.Logf("released to theirs: %+v\n%s", read(), history())
}
