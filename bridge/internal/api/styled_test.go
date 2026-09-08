package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"testing"

	"dev.isachivka.bewareofsugar/bridge/internal/agterm/agtermtest"
)

// zmxInventory answers `zmx.list` with one running daemon behind sessionA's left pane, and
// `session.text` with plain "hello" for anything that falls back.
func zmxInventory(observation string) func(agtermtest.Request) any {
	return func(req agtermtest.Request) any {
		switch req.Cmd {
		case "zmx.list":
			return agtermtest.OK(map[string]any{"zmx": map[string]any{
				"endpoint": map[string]any{"executable": "/bundle/zmx", "socketDirectory": "/tmp/zmx-sock"},
				"entries": []map[string]any{
					{"sessionID": sessionA, "pane": "left", "daemon": "agterm-1", "observation": observation},
				},
			}})
		case "session.text":
			return agtermtest.OK(map[string]any{"text": "hello"})
		}
		return agtermtest.Err("unexpected " + req.Cmd)
	}
}

const rawDump = "\x1b[?1049h\x1b[?2004h\r\n" +
	"\x1b[0m\x1b[38;5;2m➜\x1b[0m main\r\n" +
	"\x1b[0m\x1b[1mbold\x1b[0m\r\n" +
	"\r\n\x1b[53;3H\x1b]7;file://host/x\x1b\\"

func TestStyledScreenReadsThePaneThroughZmx(t *testing.T) {
	h, fake := handler(t, zmxInventory("running"))
	var ran []string
	h.history = func(_ context.Context, exe, dir, daemon string) (string, error) {
		ran = []string{exe, dir, daemon}
		return rawDump, nil
	}

	resp := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: sessionA, Pane: "left", Styled: true})
	if !resp.OK {
		t.Fatalf("refused: %s", resp.Error)
	}
	if len(ran) != 3 || ran[0] != "/bundle/zmx" || ran[1] != "/tmp/zmx-sock" || ran[2] != "agterm-1" {
		t.Fatalf("zmx ran as %v", ran)
	}
	if !resp.Styled {
		t.Fatal("a screen read through zmx must say so")
	}
	want := "\n\x1b[0m\x1b[38;5;2m➜\x1b[0m main\n\x1b[0m\x1b[1mbold\x1b[0m"
	if resp.Text == nil || *resp.Text != want {
		t.Fatalf("text = %q, want %q", deref(resp.Text), want)
	}
	sum := sha256.Sum256([]byte(want))
	if resp.Digest != hex.EncodeToString(sum[:]) {
		t.Fatal("digest must be over exactly the bytes returned")
	}
	for _, r := range fake.Requests() {
		if r.Cmd == "session.text" {
			t.Fatal("a styled read must not also read the plain screen")
		}
	}
}

func TestStyledScreenCutsToTheRequestedLines(t *testing.T) {
	h, _ := handler(t, zmxInventory("running"))
	h.history = func(context.Context, string, string, string) (string, error) { return rawDump, nil }

	resp := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: sessionA, Pane: "left", Styled: true, Lines: 1})
	if !resp.OK || resp.Text == nil {
		t.Fatalf("refused: %s", resp.Error)
	}
	if *resp.Text != "\x1b[0m\x1b[1mbold\x1b[0m" {
		t.Fatalf("text = %q", *resp.Text)
	}
}

func TestStyledScreenFallsBackToPlainWhenThePaneHasNoDaemon(t *testing.T) {
	h, fake := handler(t, zmxInventory("absent"))
	h.history = func(context.Context, string, string, string) (string, error) {
		t.Fatal("zmx must not run for a pane without a running daemon")
		return "", nil
	}

	resp := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: sessionA, Pane: "left", Styled: true})
	if !resp.OK {
		t.Fatalf("refused: %s", resp.Error)
	}
	if resp.Styled {
		t.Fatal("a plain fallback must not claim to be styled")
	}
	if resp.Text == nil || *resp.Text != "hello" {
		t.Fatalf("text = %q, want the plain read", deref(resp.Text))
	}
	if fake.Requests()[len(fake.Requests())-1].Cmd != "session.text" {
		t.Fatal("the fallback must read through session.text")
	}
}

func TestStyledScreenFallsBackToPlainWhenZmxFails(t *testing.T) {
	h, _ := handler(t, zmxInventory("running"))
	h.history = func(context.Context, string, string, string) (string, error) {
		return "", errors.New("zmx history: session unresponsive")
	}

	resp := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: sessionA, Pane: "left", Styled: true})
	if !resp.OK || resp.Styled || resp.Text == nil || *resp.Text != "hello" {
		t.Fatalf("resp = %+v", resp)
	}
}

func TestStyledScreenAnswersUnchangedOnAMatchingDigest(t *testing.T) {
	h, _ := handler(t, zmxInventory("running"))
	h.history = func(context.Context, string, string, string) (string, error) { return rawDump, nil }

	first := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: sessionA, Pane: "left", Styled: true})
	second := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: sessionA, Pane: "left", Styled: true, Digest: first.Digest})
	if !second.Unchanged || second.Text != nil {
		t.Fatalf("second = %+v", second)
	}
	if !second.Styled {
		t.Fatal("an unchanged styled screen is still styled")
	}
}

func TestPlainScreenNeverAsksZmx(t *testing.T) {
	h, fake := handler(t, zmxInventory("running"))
	h.history = func(context.Context, string, string, string) (string, error) {
		t.Fatal("zmx must not run unless the phone asked for a styled read")
		return "", nil
	}

	resp := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: sessionA, Pane: "left"})
	if !resp.OK || resp.Styled {
		t.Fatalf("resp = %+v", resp)
	}
	for _, r := range fake.Requests() {
		if r.Cmd == "zmx.list" {
			t.Fatal("a plain read must not list daemons")
		}
	}
}

func deref(s *string) string {
	if s == nil {
		return "<nil>"
	}
	return *s
}
