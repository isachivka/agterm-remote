package styled

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
)

// The shapes below are the ones a real `zmx history --vt` dump carried on 2026-09-05, read off a live
// agterm pane running Claude Code: private-mode sets up front, OSC 7 with the cwd, a charset select,
// a cursor move, the kitty keyboard flags, and `ESC[>4;2m` — which ends in `m` and is NOT an SGR.

func TestCleanKeepsSGRAndDropsEveryOtherEscape(t *testing.T) {
	in := "\x1b[?1000h\x1b[?1049h\x1b[?2004h\r\n" +
		"\x1b[0m\x1b[38;2;255;255;255mhello\x1b[0m \x1b[1mbold\x1b[0m\r\n" +
		"plain\x1b[0m\x1b[=5;1u\x1b(B\x1b[53;3H\x1b[>4;2m\x1b]7;file://host/example/dir\x1b\\"
	got := Clean(in)
	want := "\n\x1b[0m\x1b[38;2;255;255;255mhello\x1b[0m \x1b[1mbold\x1b[0m\nplain\x1b[0m"
	if got != want {
		t.Fatalf("Clean:\n got %q\nwant %q", got, want)
	}
}

func TestCleanDropsTrailingBlankRows(t *testing.T) {
	got := Clean("a\r\n\x1b[0m   \r\n\r\n\x1b[53;3H")
	if got != "a" {
		t.Fatalf("Clean = %q, want %q", got, "a")
	}
}

func TestStripLeavesOnlyText(t *testing.T) {
	got := Strip("\x1b[0m\x1b[38;5;4m(main)\x1b[0m ok")
	if got != "(main) ok" {
		t.Fatalf("Strip = %q", got)
	}
}

func TestTailReturnsEverythingWhenShort(t *testing.T) {
	got := Tail("a\nb", 5)
	if got != "a\nb" {
		t.Fatalf("Tail = %q", got)
	}
}

func TestTailCarriesTheStyleInEffectAtTheCut(t *testing.T) {
	// Line 2 opens bold red and never closes it, so line 3 is still bold red. A cut before line 3
	// must restate that, or the phone renders the first kept line in the wrong style.
	in := "a\n\x1b[1m\x1b[38;5;1mb\nc"
	got := Tail(in, 1)
	want := "\x1b[0m\x1b[1m\x1b[38;5;1mc"
	if got != want {
		t.Fatalf("Tail:\n got %q\nwant %q", got, want)
	}
}

func TestTailAddsNothingAfterAReset(t *testing.T) {
	in := "\x1b[1ma\x1b[0m\nb\nc"
	if got := Tail(in, 1); got != "c" {
		t.Fatalf("Tail = %q, want %q", got, "c")
	}
}

func TestPickWantsARunningDaemonForThatSessionAndPane(t *testing.T) {
	entries := []agterm.ZmxEntry{
		{SessionID: "S1", Pane: "left", Daemon: "d-left", Observation: "running"},
		{SessionID: "S1", Pane: "right", Daemon: "d-right", Observation: "absent"},
		{SessionID: "S2", Pane: "left", Daemon: "d-other", Observation: "running"},
	}
	if d, ok := Pick(entries, "S1", "left"); !ok || d != "d-left" {
		t.Fatalf("Pick left = %q, %v", d, ok)
	}
	if _, ok := Pick(entries, "S1", "right"); ok {
		t.Fatal("an absent daemon must not be picked")
	}
	if _, ok := Pick(entries, "S3", "left"); ok {
		t.Fatal("an unknown session must not be picked")
	}
}

func TestHistoryRunsTheBundledZmxAgainstTheSocketDir(t *testing.T) {
	dir := t.TempDir()
	exe := filepath.Join(dir, "zmx")
	script := "#!/bin/sh\nprintf '%s|%s|%s|%s' \"$ZMX_DIR\" \"$1\" \"$2\" \"$3\"\n"
	if err := os.WriteFile(exe, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	got, err := History(context.Background(), exe, "/tmp/sock", "agterm-abc")
	if err != nil {
		t.Fatal(err)
	}
	if got != "/tmp/sock|history|agterm-abc|--vt" {
		t.Fatalf("History ran %q", got)
	}
}

func TestHistoryReportsAFailedRun(t *testing.T) {
	dir := t.TempDir()
	exe := filepath.Join(dir, "zmx")
	if err := os.WriteFile(exe, []byte("#!/bin/sh\necho 'no such session' >&2\nexit 1\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	_, err := History(context.Background(), exe, "/tmp/sock", "agterm-abc")
	if err == nil || !strings.Contains(err.Error(), "no such session") {
		t.Fatalf("History err = %v, want the daemon's stderr", err)
	}
}
