package api

import (
	"context"
	"strings"
	"testing"

	"dev.isachivka.bewareofsugar/bridge/internal/agterm/agtermtest"
)

// The verb that shows one pane at the full width of the terminal area — REQ-0042.
//
// # What the owner asked for
//
// *"при переключении мы держим их фуллскрин"* — showing a pane maximizes it on his Mac, both
// directions, and nothing is restored afterwards.
//
// # What agterm does, measured rather than read
//
// Measured over the control socket on 2026-08-29, on throwaway sessions created with `noSelect`,
// closed, and verified gone from the tree:
//
//	call                                   result
//	focus left  then split mode:off        left visible, right hidden
//	focus right then split mode:off        left hidden,  right visible
//	focus <pane> while already collapsed   swaps which pane shows
//	focus on a session with no split       ok:false, "session has no split"
//	split mode:off three times over        idempotent, same state each time

// **THE TEST THAT PINS THE ORDER, and the order is the whole mechanism.**
//
// Focus, then hide. Hiding first collapses to whichever pane already had focus and then moves focus
// into a pane nobody can see — so a bridge that sent these the other way round would answer ok while
// showing him the wrong terminal.
func TestShowingAPaneFocusesItBeforeHidingTheSplit(t *testing.T) {
	var seen []agtermtest.Request
	h, _ := handler(t, func(req agtermtest.Request) any {
		seen = append(seen, req)
		return agtermtest.OK(map[string]any{"id": sessionA})
	})

	resp := h.Handle(context.Background(), Request{Verb: VerbPaneShow, Session: sessionA, Pane: "right"})

	if !resp.OK {
		t.Fatalf("showing a pane was refused: %+v", resp)
	}
	if len(seen) != 2 {
		t.Fatalf("sent %d calls, want exactly two: %+v", len(seen), seen)
	}
	if seen[0].Cmd != "session.focus" {
		t.Fatalf("the first call was %q, want session.focus - hiding first shows the wrong pane", seen[0].Cmd)
	}
	if seen[1].Cmd != "session.split" {
		t.Fatalf("the second call was %q, want session.split", seen[1].Cmd)
	}
}

// **THE TEST THAT PINS THE PANE ARGUMENT.**
//
// agterm's default for `session.focus` is `other` — a TOGGLE. Measured 2026-08-29: `position`, `role`
// and `target_pane` all answered `ok:true` and all three toggled, because an unrecognised argument
// name falls back to that default. **The reply cannot tell you the name was wrong.**
//
// So a bridge that sent the wrong key, or omitted it, would show him whichever pane he was not
// looking at, half the time, and every call would come back ok.
func TestShowingAPaneNamesTheKeyAgtermActuallyReads(t *testing.T) {
	var seen []agtermtest.Request
	h, _ := handler(t, func(req agtermtest.Request) any {
		seen = append(seen, req)
		return agtermtest.OK(map[string]any{"id": sessionA})
	})

	h.Handle(context.Background(), Request{Verb: VerbPaneShow, Session: sessionA, Pane: "right"})

	args := splitArgs(t, seen[0])
	if args["pane"] != "right" {
		t.Fatalf("session.focus args were %v, want pane:right - any other key silently toggles", args)
	}
}

// `off`, explicitly, for the same reason `on` is explicit on the opening verb: `session.split` with no
// args is a toggle, and a toggle sent over a link with a 2-second poll behind it is a coin flip.
func TestShowingAPaneHidesTheSplitExplicitlyAndNeverToggles(t *testing.T) {
	var seen []agtermtest.Request
	h, _ := handler(t, func(req agtermtest.Request) any {
		seen = append(seen, req)
		return agtermtest.OK(map[string]any{"id": sessionA})
	})

	h.Handle(context.Background(), Request{Verb: VerbPaneShow, Session: sessionA, Pane: "left"})

	args := splitArgs(t, seen[1])
	if args["mode"] != "off" {
		t.Fatalf("session.split args were %v, want mode:off", args)
	}
}

// **A session with no split is refused, not quietly succeeded.**
//
// It is ALREADY showing its one pane at full width, so there is nothing to do — but the phone decides
// what to show partly on whether this worked, and a bridge answering ok here would be reporting work
// it did not perform. agterm's own sentence is what reaches him.
func TestShowingAPaneInAnUnsplitSessionIsRefusedWithAgtermsOwnWords(t *testing.T) {
	h, _ := handler(t, func(req agtermtest.Request) any {
		if req.Cmd == "session.focus" {
			return map[string]any{"ok": false, "error": "session has no split"}
		}
		return agtermtest.OK(map[string]any{"id": sessionA})
	})

	resp := h.Handle(context.Background(), Request{Verb: VerbPaneShow, Session: sessionA, Pane: "right"})

	if resp.OK {
		t.Fatal("a session with no split answered ok to being maximized")
	}
	if !strings.Contains(resp.Error, "no split") {
		t.Fatalf("agterm's own sentence did not reach the phone: %q", resp.Error)
	}
}

// **The split is not hidden when the focus failed.** Two calls, and the second must not run on its
// own: hiding the split of a session whose focus we could not move is a change he did not ask for,
// made on the strength of a call that failed.
func TestAFailedFocusDoesNotHideTheSplitAnyway(t *testing.T) {
	var seen []agtermtest.Request
	h, _ := handler(t, func(req agtermtest.Request) any {
		seen = append(seen, req)
		if req.Cmd == "session.focus" {
			return map[string]any{"ok": false, "error": "session has no split"}
		}
		return agtermtest.OK(map[string]any{"id": sessionA})
	})

	h.Handle(context.Background(), Request{Verb: VerbPaneShow, Session: sessionA, Pane: "right"})

	for _, req := range seen {
		if req.Cmd == "session.split" {
			t.Fatal("the split was hidden after the focus was refused")
		}
	}
}

// A partial or invented id must not resolve to `active` in agterm, which here would mean rearranging
// the panes of whatever session he happens to be working in. The same check every other verb makes.
func TestShowingAPaneRefusesAnIdThatIsNotAUUID(t *testing.T) {
	var seen []agtermtest.Request
	h, _ := handler(t, func(req agtermtest.Request) any {
		seen = append(seen, req)
		return agtermtest.OK(map[string]any{"id": sessionA})
	})

	resp := h.Handle(context.Background(), Request{Verb: VerbPaneShow, Session: "F2F9", Pane: "left"})

	if resp.OK {
		t.Fatal("a partial id was accepted")
	}
	if len(seen) != 0 {
		t.Fatalf("a refused id still reached the socket: %+v", seen)
	}
}

// An unknown pane is refused before anything is sent — [paneFor]'s rule, and this verb does not get one
// of its own. A third default here is the REQ-0032 defect in a third place.
func TestShowingAnUnknownPaneReachesNothing(t *testing.T) {
	var seen []agtermtest.Request
	h, _ := handler(t, func(req agtermtest.Request) any {
		seen = append(seen, req)
		return agtermtest.OK(map[string]any{"id": sessionA})
	})

	resp := h.Handle(context.Background(), Request{Verb: VerbPaneShow, Session: sessionA, Pane: "middle"})

	if resp.OK {
		t.Fatal("an unknown pane was accepted")
	}
	if len(seen) != 0 {
		t.Fatalf("an unknown pane still reached the socket: %+v", seen)
	}
}

// **It is a separate verb from `pane.open`, and this is the test that says why.**
//
// `Request.Pane` already existed, so a bridge that predates REQ-0042 accepts a pane on a `pane.open`
// and ignores it — a phone asking to maximize the LEFT pane would get a split CREATED instead,
// silently. Separating the verbs is what makes an old bridge refuse by name instead.
//
// Held here rather than in a comment: the two verbs must not become one, and the difference that
// matters is that one of them can start a shell and the other cannot.
func TestShowingAPaneNeverCreatesOne(t *testing.T) {
	var seen []agtermtest.Request
	h, _ := handler(t, func(req agtermtest.Request) any {
		seen = append(seen, req)
		return agtermtest.OK(map[string]any{"id": sessionA})
	})

	h.Handle(context.Background(), Request{Verb: VerbPaneShow, Session: sessionA, Pane: "right"})

	for _, req := range seen {
		if args := splitArgs(t, req); args["mode"] == "on" {
			t.Fatalf("pane.show sent the creating mode: %+v", req)
		}
	}
}
