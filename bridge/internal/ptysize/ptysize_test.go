package ptysize

import (
	"context"
	"errors"
	"os"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
)

// stub swaps the two steps for the test and puts them back after it. Package-level values, so
// these tests must not run in parallel with each other.
func stub(t *testing.T, tty func(int) (string, error), size func(string) (Size, error)) {
	t.Helper()
	savedTTY, savedSize := ttyOf, winsize
	ttyOf, winsize = tty, size
	t.Cleanup(func() { ttyOf, winsize = savedTTY, savedSize })
}

func TestReadNamesTheShellsTerminalAndAsksItForItsSize(t *testing.T) {
	var asked string
	stub(t,
		func(pid int) (string, error) {
			if pid != 4242 {
				t.Errorf("asked ps about pid %d", pid)
			}
			return "/dev/ttys008", nil
		},
		func(device string) (Size, error) {
			asked = device
			return Size{Rows: 56, Cols: 164}, nil
		})

	got, err := Read(4242)
	if err != nil {
		t.Fatal(err)
	}
	if got != (Size{Rows: 56, Cols: 164}) {
		t.Errorf("read %+v", got)
	}
	if asked != "/dev/ttys008" {
		t.Errorf("the size was read from %q, not the device ps named", asked)
	}
}

func TestAProcessWithoutATerminalIsAnError(t *testing.T) {
	stub(t,
		func(int) (string, error) { return "", errors.New("pid 7 has no controlling terminal") },
		func(string) (Size, error) {
			t.Fatal("nothing should be opened when ps names no terminal")
			return Size{}, nil
		})
	if _, err := Read(7); err == nil {
		t.Fatal("a pid with no terminal produced a size")
	}
	// And a pid that cannot be a process is refused before ps is even run.
	if _, err := Read(0); err == nil {
		t.Fatal("pid 0 produced a size")
	}
}

// The real ps step, against this test's own process, which has no terminal under `go test`. What it
// proves is the parse of ps's output: `??` and blank both mean none, and neither becomes /dev/??.
func TestTheRealPsStepRefusesAProcessWithNoTerminal(t *testing.T) {
	if _, err := os.Stat("/bin/ps"); err != nil {
		t.Skip("no /bin/ps here")
	}
	device, err := ttyOf(os.Getpid())
	if err == nil {
		// Run from a terminal by hand, the test process HAS one; that is a different machine state
		// and not a failure of the parse.
		if device == "/dev/??" || device == "/dev/" {
			t.Fatalf("ps's 'none' was turned into the device %q", device)
		}
		t.Logf("this process has a terminal (%s); the none case was not exercised", device)
	}
}

// Against the live agterm and its zmx daemons, like the other BOS_REAL_AGTERM tests. Read-only: it
// finds one running pane, asks ps for its shell's terminal and reads the size off it.
func TestRealPtySizeOfALivePane(t *testing.T) {
	if os.Getenv("BOS_REAL_AGTERM") != "1" {
		t.Skip("set BOS_REAL_AGTERM=1 to run against the live agterm")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	inv, err := agterm.New(agterm.DefaultSocketPath()).ZmxList(ctx)
	if err != nil {
		t.Fatalf("zmx.list: %v", err)
	}
	for _, e := range inv.Entries {
		if e.Observation != "running" || e.LeaderPID <= 0 {
			continue
		}
		got, err := Read(int(e.LeaderPID))
		if err != nil {
			t.Fatalf("pid %d: %v", e.LeaderPID, err)
		}
		if got.Rows <= 0 || got.Cols <= 0 {
			t.Fatalf("pid %d: %+v", e.LeaderPID, got)
		}
		t.Logf("a live pane's pty is %d rows by %d columns", got.Rows, got.Cols)
		return
	}
	t.Skip("no running Live pane with a shell pid to read")
}
