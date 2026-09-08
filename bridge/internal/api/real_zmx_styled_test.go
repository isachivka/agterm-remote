package api

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"

	"dev.isachivka.bewareofsugar/bridge/internal/agterm"
	"dev.isachivka.bewareofsugar/bridge/internal/styled"
)

// Reads one live pane with its colours through the REAL agterm and the REAL zmx on this machine.
// Skipped unless BOS_REAL_AGTERM=1, like the other live tests. Read-only: it lists daemons, asks
// one for its history, and changes nothing.
//
// What it proves that the fakes cannot: that agterm 0.26's `zmx.list` still has the shape the
// client decodes, that the bundled zmx accepts `history <daemon> --vt` with ZMX_DIR pointing at
// agterm's socket directory, and that the dump reduces to text plus SGR and nothing else.
func TestRealZmxStyledScreen(t *testing.T) {
	if os.Getenv("BOS_REAL_AGTERM") != "1" {
		t.Skip("set BOS_REAL_AGTERM=1 to run against the live agterm and zmx")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	client := agterm.New(agterm.DefaultSocketPath())
	inv, err := client.ZmxList(ctx)
	if err != nil {
		t.Fatalf("zmx.list: %v", err)
	}
	var session, pane string
	for _, e := range inv.Entries {
		if e.Observation == "running" {
			session, pane = e.SessionID, e.Pane
			break
		}
	}
	if session == "" {
		t.Skip("no pane is running under zmx - is agterm in Live sessions mode?")
	}

	h := New(client, t.TempDir())
	resp := h.Handle(ctx, Request{Verb: VerbScreen, Session: session, Pane: pane, Styled: true, Lines: 50})
	if !resp.OK {
		t.Fatalf("screen refused: %s", resp.Error)
	}
	if !resp.Styled {
		t.Fatal("a running daemon must answer styled, not fall back")
	}
	if resp.Text == nil || !strings.Contains(*resp.Text, "\x1b[") {
		t.Fatal("a styled read must carry SGR")
	}
	text := *resp.Text
	if strings.Contains(text, "\r") || strings.Contains(text, "\x1b]") || strings.Contains(text, "\x1b[?") {
		t.Fatalf("non-SGR residue survived Clean: %q", text[:min(200, len(text))])
	}
	if n := strings.Count(text, "\n"); n >= 50 {
		t.Fatalf("asked for 50 lines, got %d newlines", n)
	}
	t.Logf("styled %d bytes, plain %d bytes, %d rows", len(text), len(styled.Strip(text)), strings.Count(text, "\n")+1)

	again := h.Handle(ctx, Request{Verb: VerbScreen, Session: session, Pane: pane, Styled: true, Lines: 50, Digest: resp.Digest})
	if !again.OK {
		t.Fatalf("second read refused: %s", again.Error)
	}
	if again.Unchanged && !again.Styled {
		t.Fatal("an unchanged styled screen must still say styled")
	}
}
