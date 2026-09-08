package api

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
	"github.com/isachivka/agterm-remote/bridge/internal/agterm/agtermtest"
)

// Whether a session HAS a second pane — REQ-0034, and the trap that shipped in v0.18.0.
//
// # What was published, and what the phone needed
//
// The bridge forwarded agterm's own `split` field. That field means BOTH PANES ARE VISIBLE. What the
// phone's toggle is gated on is whether a second pane EXISTS, and those come apart the moment the
// owner collapses a split on his Mac. Measured over the control socket on 2026-08-25, on throwaway
// sessions created and closed for the purpose:
//
//	state                     split   surfaces                  right pane reads?
//	no second pane ever        false   [left]                    no - "session has no split pane"
//	second pane, collapsed     false   [left right]              YES, perfectly
//	second pane, on screen     true    [left right]              yes
//
// # It is not the silent-wrong-pane defect, and the difference is the interesting part
//
// REQ-0032's bug sent the read and the keystroke to different panes. Nothing here disagrees with
// itself. What a collapsed split takes away is REACH: the phone's toggle is composed only when this
// field is true, so a session opened while the split was collapsed offers no route to the second pane
// at all — though the pane exists, is addressable, and reads perfectly.
//
// **The symptom first written here was wrong.** It said the toggle vanished mid-session, leaving the
// owner on the right pane with no way back. It cannot: the phone's session row is a snapshot taken
// when the session is opened and does not change while the screen is up, so the control cannot
// disappear underneath him. The defect is real and narrower. See entry 15 in
// docs/qa/instruments-that-lied.md — a failure mode derived from reading two gates without asking
// when the value they read can change.
//
// # Nothing tested this field before
//
// `split` shipped in v0.18.0 with no test in this package naming it. That is not incidental to how the
// wrong field went out: there was no place where somebody had to write down what the value meant.

// surfaces builds the `surfaces` array agterm puts on a session, from a list of pane roles.
func surfaces(kinds ...string) []any {
	out := make([]any, 0, len(kinds))
	for _, kind := range kinds {
		// `id`, `visible` and `active` really are on the wire and really are ignored. Included here so
		// the fixture is a session as agterm sends one, not as this package wishes it were.
		out = append(out, map[string]any{"id": "surface:" + kind, "kind": kind, "visible": true, "active": false})
	}
	return out
}

// treeWithSurfaces is the one-session tree of api_test.go, with panes and agterm's own `split` flag
// under the test's control.
func treeWithSurfaces(split bool, kinds ...string) any {
	return map[string]any{"tree": map[string]any{"workspaces": []any{
		map[string]any{"id": "W1", "name": "main", "active": true, "sessions": []any{
			map[string]any{
				"id": sessionA, "name": "agterm", "active": true,
				"split": split, "surfaces": surfaces(kinds...),
			},
		}},
	}}}
}

func splitPaneOf(t *testing.T, split bool, kinds ...string) bool {
	t.Helper()
	h, _ := handler(t, func(agtermtest.Request) any { return agtermtest.OK(treeWithSurfaces(split, kinds...)) })
	resp := h.Handle(context.Background(), Request{Verb: VerbSessions})
	if len(resp.Sessions) != 1 {
		t.Fatalf("expected one session, got %d", len(resp.Sessions))
	}
	return resp.Sessions[0].SplitPane
}

// **THE TEST THE DEFECT FAILS.** A pane collapsed on the Mac is a pane the phone can still reach.
//
// This is the whole correction in one assertion: agterm says `split: false` and the session has a
// right-hand pane anyway. Reverting [agterm.Session.HasSplitPane] to `s.Split` fails here and passes
// everywhere else in this file — which is exactly the shape the shipped bug had.
func TestACollapsedSplitStillHasASecondPane(t *testing.T) {
	if !splitPaneOf(t, false, "left", "right") {
		t.Fatal("a collapsed split was published as having no second pane; the phone composes no " +
			"toggle, so a pane that exists and reads perfectly is unreachable from it")
	}
}

func TestAVisibleSplitHasASecondPane(t *testing.T) {
	if !splitPaneOf(t, true, "left", "right") {
		t.Fatal("a session showing both panes was published as having no second pane")
	}
}

func TestASessionThatNeverHadASplitHasNoSecondPane(t *testing.T) {
	if splitPaneOf(t, false, "left") {
		t.Fatal("a session with one pane was published as having two; every press of the toggle would " +
			"come back `session has no split pane`")
	}
}

// **Why the predicate is not len(surfaces) > 1**, asserted rather than left to the comment.
//
// `scratch` is a surface. Measured 2026-08-25: turning a scratch terminal on takes a session from one
// surface to two, and turning it OFF leaves it at two — the surface stays in the tree for good. A
// count would hand the phone a pane toggle for a pane that does not exist, permanently, on any session
// the owner ever opened a scratch terminal in.
func TestAScratchTerminalIsNotASecondPane(t *testing.T) {
	if splitPaneOf(t, false, "left", "scratch") {
		t.Fatal("a scratch terminal was counted as a split pane")
	}
}

// And an overlay, the other surface kind that is not a pane. Unlike scratch this one is ephemeral —
// it leaves the tree when it closes — which makes it the more forgiving of the two and therefore the
// one a count would have got away with for longer.
func TestAnOverlayIsNotASecondPane(t *testing.T) {
	if splitPaneOf(t, false, "left", "overlay") {
		t.Fatal("an overlay terminal was counted as a split pane")
	}
}

// The bridge must not require agterm to send `surfaces` before it will answer at all. A tree without
// the field yields "no second pane", which is the safe direction: the toggle is absent rather than
// present and refusing on every press.
func TestASessionWithNoSurfacesFieldHasNoSecondPane(t *testing.T) {
	h, _ := handler(t, func(agtermtest.Request) any { return agtermtest.OK(tree()) })

	resp := h.Handle(context.Background(), Request{Verb: VerbSessions})

	if resp.Sessions[0].SplitPane {
		t.Fatal("a session with no surfaces at all was published as having a second pane")
	}
}

// **The wire key keeps its old spelling on purpose**, and this is where that decision is enforced.
//
// The MEANING changed; the name did not. There is no version handshake on this protocol and the owner
// installs the phone and the Mac app separately, so renaming the key would make a phone updated ahead
// of the Mac read no field and offer the toggle never — worse than the defect being fixed. Asserted on
// the encoded bytes rather than on the struct tag, because a tag is easy to "tidy" and this one is
// load-bearing for a version mixture nobody can test from here.
//
// **The fixture is the VISIBLE split, not the collapsed one, and that is deliberate.** Written against
// the collapsed state this test failed under the reverted predicate too — for the collapse, not for
// the key — and would have sent whoever hit it looking at a struct tag that was fine. A test about the
// spelling must not also depend on the predicate.
func TestTheWireKeyIsStillCalledSplit(t *testing.T) {
	h, _ := handler(t, func(agtermtest.Request) any { return agtermtest.OK(treeWithSurfaces(true, "left", "right")) })

	raw, err := json.Marshal(h.Handle(context.Background(), Request{Verb: VerbSessions}))
	if err != nil {
		t.Fatal(err)
	}
	body := string(raw)

	if !strings.Contains(body, `"split":true`) {
		t.Fatalf("the session did not go out under the key `split`, so a phone built against the "+
			"old bridge loses its toggle entirely: %s", body)
	}
}

// A direct unit on the predicate, so the reason it exists survives a rewrite of the handler above it.
func TestHasSplitPaneAsksTheRoleAndNotTheCount(t *testing.T) {
	for _, c := range []struct {
		name  string
		kinds []string
		want  bool
	}{
		{"nothing at all", nil, false},
		{"one pane", []string{"left"}, false},
		{"two panes", []string{"left", "right"}, true},
		{"a scratch that outlived its use", []string{"left", "scratch"}, false},
		{"an overlay", []string{"left", "overlay"}, false},
		{"a split and a scratch together", []string{"left", "right", "scratch"}, true},
		// agterm has never sent this and the predicate does not care: it asks whether a right-hand
		// pane is present, not how many surfaces there are or what order they arrive in.
		{"the right pane first", []string{"right", "left"}, true},
	} {
		t.Run(c.name, func(t *testing.T) {
			var session agterm.Session
			for _, kind := range c.kinds {
				session.Surfaces = append(session.Surfaces, agterm.Surface{Kind: kind})
			}
			if got := session.HasSplitPane(); got != c.want {
				t.Fatalf("surfaces %v: got %v, want %v", c.kinds, got, c.want)
			}
		})
	}
}
