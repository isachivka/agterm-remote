package api

import (
	"context"
	"encoding/json"
	"testing"

	"dev.isachivka.bewareofsugar/bridge/internal/agterm"
	"dev.isachivka.bewareofsugar/bridge/internal/agterm/agtermtest"
)

// The pane a request addresses — REQ-0032, and the silent defect it was written for.
//
// # What was wrong
//
// agterm resolves an absent `--pane` DIFFERENTLY per command. Measured over the control socket on
// 2026-08-25, on a throwaway split session created and closed for the purpose:
//
//	session.text with no pane  -> the ON-SCREEN pane
//	session.type with no pane  -> PRIMARY
//
// The bridge sent no pane on either. So on a split session with the right pane on screen, the phone
// showed one pane and typed into the other, with no error, into the terminal the owner was not looking
// at. These tests are that not happening again.

// paneOf returns the pane a recorded agterm request carried, or "" if it carried none.
func paneOf(t *testing.T, req agtermtest.Request) string {
	t.Helper()
	if len(req.Args) == 0 {
		return ""
	}
	var got struct {
		Pane string `json:"pane"`
	}
	if err := json.Unmarshal(req.Args, &got); err != nil {
		t.Fatalf("args are not an object: %v", err)
	}
	return got.Pane
}

// **THE TEST THE DEFECT WOULD HAVE FAILED.** One request naming a pane, and BOTH commands must address
// that pane — not one of them, and not each according to its own idea of a default.
func TestTheScreenAndTheKeystrokeGoToTheSamePane(t *testing.T) {
	for _, pane := range []string{"left", "right"} {
		t.Run(pane, func(t *testing.T) {
			var seen []agtermtest.Request
			fake := agtermtest.Start(t, func(req agtermtest.Request) any {
				seen = append(seen, req)
				return agtermtest.OK(map[string]any{"text": "x"})
			})
			h := New(agterm.New(fake.Path), t.TempDir())
			ctx := context.Background()
			session := "11111111-1111-4111-8111-111111111111"

			if resp := h.Handle(ctx, Request{Verb: VerbScreen, Session: session, Pane: pane}); !resp.OK {
				t.Fatalf("screen refused: %v", resp.Error)
			}
			if resp := h.Handle(ctx, Request{Verb: VerbType, Session: session, Text: "ls", Pane: pane}); !resp.OK {
				t.Fatalf("type refused: %v", resp.Error)
			}

			var read, typed string
			for _, r := range seen {
				switch r.Cmd {
				case "session.text":
					read = paneOf(t, r)
				case "session.type":
					typed = paneOf(t, r)
				}
			}

			if read != pane || typed != pane {
				t.Fatalf("read addressed %q and typing addressed %q, both should be %q - "+
					"this is the phone showing one pane and typing into another", read, typed, pane)
			}
		})
	}
}

// **Neither command may reach the socket without a pane on it.** An absent pane is not "the obvious
// one" — it is two different obvious ones, which is the whole defect.
func TestNeitherCommandIsEverSentWithoutAPane(t *testing.T) {
	var seen []agtermtest.Request
	fake := agtermtest.Start(t, func(req agtermtest.Request) any {
		seen = append(seen, req)
		return agtermtest.OK(map[string]any{"text": "x"})
	})
	h := New(agterm.New(fake.Path), t.TempDir())
	ctx := context.Background()
	session := "11111111-1111-4111-8111-111111111111"

	// No Pane on the request at all: the old phone, and the old bug's starting conditions.
	h.Handle(ctx, Request{Verb: VerbScreen, Session: session})
	h.Handle(ctx, Request{Verb: VerbType, Session: session, Text: "ls"})

	for _, r := range seen {
		if r.Cmd != "session.text" && r.Cmd != "session.type" {
			continue
		}
		if got := paneOf(t, r); got == "" {
			t.Errorf("%s went to the socket with no pane - agterm will pick one, and it picks a "+
				"different one for each command", r.Cmd)
		}
	}
}

// And when the phone names nothing, both fall to the SAME pane. Agreeing matters more than which one.
func TestAnUnnamedPaneIsTheSameOneForBothCommands(t *testing.T) {
	var seen []agtermtest.Request
	fake := agtermtest.Start(t, func(req agtermtest.Request) any {
		seen = append(seen, req)
		return agtermtest.OK(map[string]any{"text": "x"})
	})
	h := New(agterm.New(fake.Path), t.TempDir())
	ctx := context.Background()
	session := "11111111-1111-4111-8111-111111111111"

	h.Handle(ctx, Request{Verb: VerbScreen, Session: session})
	h.Handle(ctx, Request{Verb: VerbType, Session: session, Text: "ls"})

	var read, typed string
	for _, r := range seen {
		switch r.Cmd {
		case "session.text":
			read = paneOf(t, r)
		case "session.type":
			typed = paneOf(t, r)
		}
	}
	if read != typed {
		t.Fatalf("with no pane named, the read went to %q and the keystroke to %q", read, typed)
	}
	if read != string(agterm.PaneLeft) {
		t.Errorf("an unnamed pane should be the one every session has, got %q", read)
	}
}

// A pane outside the closed set is refused by name rather than handed to the laptop to interpret —
// the same discipline as keys.Key.
func TestAnUnknownPaneIsRefusedRatherThanForwarded(t *testing.T) {
	var seen []agtermtest.Request
	fake := agtermtest.Start(t, func(req agtermtest.Request) any {
		seen = append(seen, req)
		return agtermtest.OK(map[string]any{"text": "x"})
	})
	h := New(agterm.New(fake.Path), t.TempDir())
	session := "11111111-1111-4111-8111-111111111111"

	for _, verb := range []string{VerbScreen, VerbType} {
		resp := h.Handle(context.Background(), Request{Verb: verb, Session: session, Text: "ls", Pane: "middle"})
		if resp.OK {
			t.Errorf("%s accepted a pane that does not exist", verb)
		}
		if resp.Refusal != RefusalContent {
			t.Errorf("%s: refusal is %q, want %q - this is about what was SENT, not about the laptop",
				verb, resp.Refusal, RefusalContent)
		}
	}
	for _, r := range seen {
		if r.Cmd == "session.text" || r.Cmd == "session.type" {
			t.Errorf("an unknown pane reached the socket as %s", r.Cmd)
		}
	}
}

// The client itself refuses, so the refusal does not depend on every caller remembering to check.
func TestTheClientWillNotSendACallWithNoPane(t *testing.T) {
	fake := agtermtest.Start(t, func(agtermtest.Request) any { return agtermtest.OK(map[string]any{}) })
	c := agterm.New(fake.Path)

	if _, err := c.Text(context.Background(), "11111111-1111-4111-8111-111111111111", 10, ""); err == nil {
		t.Error("Text sent a request with no pane")
	}
	if err := c.Type(context.Background(), "11111111-1111-4111-8111-111111111111", "ls", ""); err == nil {
		t.Error("Type sent a request with no pane")
	}
}

// **The pane vanished on the laptop.** agterm names this one precisely, so the phone can tell it from a
// connection breaking - REQ-0027's distinction, and not the shape of `failed to read surface buffer`.
func TestAVanishedPaneIsSaidInOurOwnWords(t *testing.T) {
	fake := agtermtest.Start(t, func(req agtermtest.Request) any {
		if req.Cmd == "session.text" || req.Cmd == "session.type" {
			return agtermtest.Err("session has no split pane")
		}
		return agtermtest.OK(map[string]any{})
	})
	h := New(agterm.New(fake.Path), t.TempDir())
	session := "11111111-1111-4111-8111-111111111111"

	for _, verb := range []string{VerbScreen, VerbType} {
		resp := h.Handle(context.Background(), Request{Verb: verb, Session: session, Text: "ls", Pane: "right"})
		if resp.OK {
			t.Fatalf("%s reported success against a pane that is gone", verb)
		}
		if resp.Error == "session has no split pane" {
			t.Errorf("%s passed agterm's own words through; the owner reads this", verb)
		}
		if resp.Detail != "session has no split pane" {
			t.Errorf("%s lost the underlying reason: detail is %q", verb, resp.Detail)
		}
	}
}
