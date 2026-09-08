package logfile

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func open(t *testing.T, maxBytes int64, keep int) (*Writer, string) {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "agterm-bridge.log")
	w, err := Open(path, maxBytes, keep)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { w.Close() })
	return w, path
}

func TestWritesAreAppended(t *testing.T) {
	w, path := open(t, 1<<20, 3)

	for i := 0; i < 3; i++ {
		if _, err := fmt.Fprintf(w, "line %d\n", i); err != nil {
			t.Fatal(err)
		}
	}
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if got := strings.Count(string(body), "\n"); got != 3 {
		t.Fatalf("expected 3 lines, got %d", got)
	}
}

// The property that matters: total disk is bounded no matter how long the bridge runs.
func TestTotalDiskIsBounded(t *testing.T) {
	const maxBytes, keep = 256, 2
	w, path := open(t, maxBytes, keep)

	// Far more than the bound, in records that do not divide it evenly.
	for i := 0; i < 500; i++ {
		if _, err := fmt.Fprintf(w, "a record that is a reasonable length, number %04d\n", i); err != nil {
			t.Fatal(err)
		}
	}

	var total int64
	entries, err := os.ReadDir(filepath.Dir(path))
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		info, err := e.Info()
		if err != nil {
			t.Fatal(err)
		}
		total += info.Size()
	}

	// keep + 1 generations, each allowed to overshoot by at most one record.
	ceiling := int64(maxBytes+64) * int64(keep+1)
	if total > ceiling {
		t.Fatalf("log grew to %d bytes, above the %d bound; a log that grows without limit on the "+
			"owner's laptop is what this exists to prevent", total, ceiling)
	}
	if len(entries) > keep+1 {
		t.Fatalf("expected at most %d files, got %d", keep+1, len(entries))
	}
}

func TestRotationKeepsTheMostRecentGenerations(t *testing.T) {
	w, path := open(t, 64, 2)

	for i := 0; i < 20; i++ {
		if _, err := fmt.Fprintf(w, "record %02d ---------------------------------\n", i); err != nil {
			t.Fatal(err)
		}
	}

	current, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(current), "record 19") {
		t.Fatalf("the newest record must be in the current file, got %q", current)
	}
	if _, err := os.Stat(path + ".1"); err != nil {
		t.Fatalf("the previous generation must be kept: %v", err)
	}
	if _, err := os.Stat(path + ".3"); !os.IsNotExist(err) {
		t.Fatal("only `keep` generations may survive")
	}
}

// Reopening must continue the existing file rather than truncating it: a bridge restart, which
// launchd will do, must not silently discard the record of what happened before it.
func TestReopeningAppendsRatherThanTruncates(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agterm-bridge.log")

	first, err := Open(path, 1<<20, 2)
	if err != nil {
		t.Fatal(err)
	}
	fmt.Fprintln(first, "before the restart")
	first.Close()

	second, err := Open(path, 1<<20, 2)
	if err != nil {
		t.Fatal(err)
	}
	fmt.Fprintln(second, "after the restart")
	second.Close()

	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(body), "before the restart") {
		t.Fatal("a restart must not discard what was already recorded")
	}
	if !strings.Contains(string(body), "after the restart") {
		t.Fatal("a restart must continue the same log")
	}
}

// The log names the owner's sessions. Not a secret, but nobody else's business either.
func TestLogIsNotWorldReadable(t *testing.T) {
	_, path := open(t, 1<<20, 2)

	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Fatalf("log mode is %04o, want 0600", perm)
	}
}
