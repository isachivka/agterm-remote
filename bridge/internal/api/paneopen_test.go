package api

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm/agtermtest"
)

// The verb that opens a session's second pane.
//
// # What the owner asked for
//
// A button that shows the left terminal by default: if the session has one it is shown, and if it
// does not it is made first — one button, two states.
//
// # What agterm does with it, measured rather than read
//
// Measured over the control socket on 2026-08-25, on throwaway sessions created with `--no-select`,
// closed, and verified gone from the tree:
//
//	starting state                   `session.split` mode:on does
//	no right pane has ever existed   CREATES one, running a login shell
//	right pane exists, collapsed     REVEALS it - same surface id, its shell's scrollback intact
//	right pane already on screen     nothing - same surface id, still two
//
// One command covers all three and **the reply cannot tell them apart**: it carries the session id
// every time. That is why the phone asks what exists BEFORE calling rather than reading the answer.

// splitArgs pulls the args object off a recorded request.
func splitArgs(t *testing.T, req agtermtest.Request) map[string]any {
	t.Helper()
	if len(req.Args) == 0 {
		return nil
	}
	var got map[string]any
	if err := json.Unmarshal(req.Args, &got); err != nil {
		t.Fatalf("args are not an object: %v", err)
	}
	return got
}

// **THE TEST THAT PINS `mode`, and it is not decoration.**
//
// Measured on the socket: `session.split` with NO args is a TOGGLE. Sent to a session already showing
// both panes it collapses them — so a bridge that omitted the mode would tear down the owner's split
// on the second tap of a button whose whole job is to show him that pane.
//
// `on` sent twice is a no-op, measured, which is what a control he may press twice requires.
func TestOpeningAPaneAlwaysSaysOnAndNeverToggles(t *testing.T) {
	var seen []agtermtest.Request
	h, _ := handler(t, func(req agtermtest.Request) any {
		seen = append(seen, req)
		return agtermtest.OK(map[string]any{"id": sessionA})
	})

	resp := h.Handle(context.Background(), Request{Verb: VerbPaneOpen, Session: sessionA})

	if !resp.OK {
		t.Fatalf("pane.open failed: %s", resp.Error)
	}
	if len(seen) != 1 {
		t.Fatalf("expected exactly one agterm command, got %d", len(seen))
	}
	if seen[0].Cmd != "session.split" {
		t.Fatalf("emitted %q; agterm has no `session.split.visibility` - measured, it is refused as an "+
			"undecodable request", seen[0].Cmd)
	}
	if seen[0].Target != sessionA {
		t.Fatalf("target was %q; an absent target resolves to `active` and would open a pane in "+
			"whatever session the owner is working in", seen[0].Target)
	}
	if mode := splitArgs(t, seen[0])["mode"]; mode != "on" {
		t.Fatalf("mode was %v, not \"on\" - a missing mode is a TOGGLE, which would collapse the "+
			"owner's split on the second press", mode)
	}
}

// **A partial or absent id never reaches agterm**, for the same reason it does not on rename and
// close: agterm resolves a partial target to `active`, so a mistyped id would open a pane in whatever
// session the owner happens to be working in — starting a shell in it.
func TestOpeningAPaneRefusesAnythingButACanonicalUUID(t *testing.T) {
	for _, id := range []string{"", "1111", "not-a-uuid", sessionA + "x", "active"} {
		t.Run(id, func(t *testing.T) {
			var seen []agtermtest.Request
			h, _ := handler(t, func(req agtermtest.Request) any {
				seen = append(seen, req)
				return agtermtest.OK(map[string]any{"id": sessionA})
			})

			resp := h.Handle(context.Background(), Request{Verb: VerbPaneOpen, Session: id})

			if resp.OK {
				t.Fatal("a non-canonical session id was accepted")
			}
			if len(seen) != 0 {
				t.Fatalf("it reached agterm anyway as %+v", seen)
			}
		})
	}
}

// A refusal from agterm is reported as a refusal rather than swallowed. Measured on the live socket,
// an unknown session comes back `no such session: <id>` — a sentence the owner can act on, and the
// phone shows it rather than inventing one.
func TestARefusedPaneOpenIsReportedAndNotSwallowed(t *testing.T) {
	h, _ := handler(t, func(agtermtest.Request) any {
		return map[string]any{"ok": false, "error": "no such session: " + sessionA}
	})

	resp := h.Handle(context.Background(), Request{Verb: VerbPaneOpen, Session: sessionA})

	if resp.OK {
		t.Fatal("a refusal from agterm was reported as success - the phone would light the icon for " +
			"a pane that does not exist")
	}
	if resp.Error == "" {
		t.Fatal("a refusal arrived with no words in it")
	}
}

// **The verb spawns nothing on its own.** Nothing in this package opens a pane except a request that
// names this verb — the same structural claim the closed set makes about every other command, and
// worth its own assertion here because this one starts a process.
func TestNoOtherVerbOpensAPane(t *testing.T) {
	for _, verb := range []string{
		VerbSessions, VerbScreen, VerbResize, VerbType, VerbFile,
		VerbWorkspaceCreate, VerbSessionCreate, VerbWorkspaceRename, VerbSessionRename,
		VerbSessionClose, VerbWorkspaceDelete,
	} {
		t.Run(verb, func(t *testing.T) {
			var seen []agtermtest.Request
			h, _ := handler(t, func(req agtermtest.Request) any {
				seen = append(seen, req)
				return agtermtest.OK(map[string]any{"id": sessionA, "text": "x", "tree": map[string]any{}})
			})

			h.Handle(context.Background(), Request{Verb: verb, Session: sessionA, Workspace: sessionB, Label: "x"})

			for _, req := range seen {
				if req.Cmd == "session.split" {
					t.Fatalf("verb %q emitted session.split - only pane.open may start a shell", verb)
				}
			}
		})
	}
}
