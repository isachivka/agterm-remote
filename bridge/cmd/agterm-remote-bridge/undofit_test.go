package main

import (
	"bufio"
	"bytes"
	"net"
	"os"
	"strings"
	"testing"

	"github.com/isachivka/agterm-remote/bridge/internal/control"
)

// The first argument decides, before the daemon's flags are looked at. `--listen` and friends are
// the daemon's and must go on parsing exactly as they always did.
func TestOnlyABareUndoFitFirstArgumentIsTheSubcommand(t *testing.T) {
	for _, args := range [][]string{
		nil,
		{"--listen", "127.0.0.1:0", "--state-dir", "/tmp/x"},
		{"--undo-fit"},
		{"undo-fit-later"},
	} {
		if handled, _ := subcommand(args, os.Stdout, os.Stderr, nil); handled {
			t.Errorf("%q was taken for the subcommand", args)
		}
	}
	var out, errOut bytes.Buffer
	handled, code := subcommand([]string{"undo-fit"}, &out, &errOut, nil)
	if !handled || code != 2 || !strings.Contains(errOut.String(), "--state-dir") {
		t.Fatalf("handled=%v code=%d stderr=%q; undo-fit with no state dir must be refused at the command line", handled, code, errOut.String())
	}
}

// fakeControl serves the bridge's control socket in dir, answering `restore` with reply and
// recording what was asked.
func fakeControl(t *testing.T, reply string) (dir string, asked *bytes.Buffer) {
	t.Helper()
	// A short path: the socket lives at dir/control.sock and macOS caps the whole thing at 104 bytes.
	dir, err := os.MkdirTemp("", "undofit")
	if err != nil {
		t.Fatal(err)
	}
	ln, err := net.Listen("unix", control.SocketPath(dir))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close(); os.RemoveAll(dir) })
	asked = &bytes.Buffer{}
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		line, _ := bufio.NewReader(conn).ReadString('\n')
		asked.WriteString(line)
		_, _ = conn.Write([]byte(reply + "\n"))
	}()
	return dir, asked
}

func TestTheBridgesSentenceIsPrintedAndNotifiedAndBothOutcomesExitZero(t *testing.T) {
	for _, c := range []struct{ reply, want string }{
		{`{"ok":true,"restored":true,"message":"the window is back the way it was"}`, "the window is back the way it was"},
		// Nothing to undo is the honest outcome of asking, not a failure to dress up as one.
		{`{"ok":true,"restored":false,"message":"no fit is in force"}`, "no fit is in force"},
	} {
		t.Run(c.want, func(t *testing.T) {
			dir, asked := fakeControl(t, c.reply)
			var out, errOut bytes.Buffer
			var said []string

			code := undoFit([]string{"--state-dir", dir, "--notify"}, &out, &errOut, func(m string) { said = append(said, m) })

			if code != 0 {
				t.Fatalf("exit %d, stderr %q", code, errOut.String())
			}
			if strings.TrimSpace(asked.String()) != `{"verb":"restore"}` {
				t.Fatalf("the bridge was asked %q", asked.String())
			}
			if out.String() != c.want+"\n" {
				t.Fatalf("stdout = %q", out.String())
			}
			if len(said) != 1 || said[0] != c.want {
				t.Fatalf("notified %q; the notification must carry the same sentence", said)
			}
		})
	}
}

func TestWithoutNotifyNothingIsPosted(t *testing.T) {
	dir, _ := fakeControl(t, `{"ok":true,"restored":true,"message":"the window is back the way it was"}`)
	var out bytes.Buffer
	code := undoFit([]string{"--state-dir", dir}, &out, os.Stderr, func(string) { t.Fatal("notified without --notify") })
	if code != 0 || !strings.Contains(out.String(), "back the way it was") {
		t.Fatalf("exit %d, stdout %q", code, out.String())
	}
}

func TestABridgeErrorIsPrintedNotifiedAndExitsOne(t *testing.T) {
	dir, _ := fakeControl(t, `{"error":"the window could not be put back: agterm is not answering"}`)
	var out bytes.Buffer
	var said []string

	code := undoFit([]string{"--state-dir", dir, "--notify"}, &out, os.Stderr, func(m string) { said = append(said, m) })

	if code != 1 {
		t.Fatalf("exit %d", code)
	}
	if !strings.Contains(out.String(), "agterm is not answering") || len(said) != 1 || said[0] != strings.TrimSpace(out.String()) {
		t.Fatalf("stdout %q, notified %q", out.String(), said)
	}
}

func TestNoBridgeListeningExitsOneAndSaysSo(t *testing.T) {
	dir := t.TempDir()
	var out bytes.Buffer
	code := undoFit([]string{"--state-dir", dir}, &out, os.Stderr, nil)
	if code != 1 || !strings.Contains(out.String(), "no bridge is listening") {
		t.Fatalf("exit %d, stdout %q", code, out.String())
	}
}

func TestTheNotificationTextIsQuotedForAppleScript(t *testing.T) {
	if got := appleScriptString(`a "quoted" \ thing`); got != `"a \"quoted\" \\ thing"` {
		t.Fatalf("quoted as %s", got)
	}
}
