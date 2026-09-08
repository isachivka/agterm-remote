package api

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"dev.isachivka.bewareofsugar/bridge/internal/agterm"
	"dev.isachivka.bewareofsugar/bridge/internal/agterm/agtermtest"
	"dev.isachivka.bewareofsugar/bridge/internal/resize"
)

// The fit the phone is told about, checked against what the pty actually shows — REQ-0030.
//
// # The defect these were written against
//
// The owner presses fit on the phone, then stretches the window on the Mac by hand, and the phone goes
// on saying the fit is on. `fitInForce` is documented as "the setting as of the last completed
// operation" — a remembered claim — and nothing re-read the window after a human dragged its edge, so
// the phone was told the truth as of the last time the BRIDGE acted, which is not the truth now.

// The judgement, on its own, including the direction it deliberately cannot see.
func TestFitOutgrown(t *testing.T) {
	for _, c := range []struct {
		name              string
		measured, fitCols int
		want              bool
	}{
		{"a line wider than the fit proves the window grew", 60, 41, true},
		{"one column wider is still proof", 42, 41, true},
		{"exactly the fitted width is the fit holding", 41, 41, false},
		// The half that is NOT knowable from a widest-line measurement. A program that does not paint
		// to the edge under-reports, so a short screen and a narrowed window look identical.
		{"narrower says nothing: the program may just have drawn short", 20, 41, false},
		{"nothing measured yet is no evidence, not a narrow window", 0, 41, false},
		{"no fit in force, nothing to outgrow", 900, 0, false},
	} {
		t.Run(c.name, func(t *testing.T) {
			if got := fitOutgrown(c.measured, c.fitCols); got != c.want {
				t.Errorf("fitOutgrown(%d, %d) = %v, want %v", c.measured, c.fitCols, got, c.want)
			}
		})
	}
}

// screenOf returns a fake whose session text is one line of exactly `columns` characters, so
// `resize.MeasureColumns` has something definite to measure.
func screenOf(t *testing.T, columns int) *agtermtest.Fake {
	t.Helper()
	line := strings.Repeat("x", columns)
	return agtermtest.Start(t, func(req agtermtest.Request) any {
		if req.Cmd == "session.text" {
			return agtermtest.OK(map[string]any{"text": line})
		}
		return agtermtest.OK(tree())
	})
}

// fitted puts a handler in the state a completed calibration leaves: a fit of `columns`, published.
func fitted(t *testing.T, fake *agtermtest.Fake, columns int) *Handler {
	t.Helper()
	h := New(agterm.New(fake.Path), t.TempDir())
	h.store.Active = &resize.Fit{Display: 0, BoxWidthDp: 440, MarginDp: 4, Points: 769, Columns: columns}
	h.publishFit()
	return h
}

func pollSaysFitted(t *testing.T, h *Handler) bool {
	t.Helper()
	raw, err := json.Marshal(h.Handle(context.Background(), Request{Verb: VerbSessions}))
	if err != nil {
		t.Fatal(err)
	}
	var wire map[string]any
	if err := json.Unmarshal(raw, &wire); err != nil {
		t.Fatal(err)
	}
	if wire["ok"] != true {
		t.Fatalf("not a successful poll, so not the case under test: %s", raw)
	}
	return wire["fit_enabled"] == true
}

// **The owner's report, as a test.** Fit at 41; the window is dragged wider and the session now draws
// 60 columns; the next poll must stop claiming the fit is on.
func TestAWindowDraggedWiderStopsBeingReportedAsFitted(t *testing.T) {
	h := fitted(t, screenOf(t, 60), 41)

	if !pollSaysFitted(t, h) {
		t.Fatal("the fit is not reported before the screen is read, so this test proves nothing")
	}

	// One ordinary screen poll: the same request the phone makes constantly.
	resp := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: "11111111-1111-4111-8111-111111111111"})
	if !resp.OK {
		t.Fatalf("the screen read failed, so nothing was measured: %v", resp.Error)
	}

	if pollSaysFitted(t, h) {
		t.Error("the phone is still told the fit is on after the window outgrew it")
	}
}

// A window still at its fitted width keeps reporting the fit. Without this the test above passes for a
// handler that simply never reports a fit at all.
func TestAWindowStillAtItsFittedWidthStaysFitted(t *testing.T) {
	h := fitted(t, screenOf(t, 41), 41)

	resp := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: "11111111-1111-4111-8111-111111111111"})
	if !resp.OK {
		t.Fatalf("the screen read failed, so nothing was measured: %v", resp.Error)
	}

	if !pollSaysFitted(t, h) {
		t.Error("a window at exactly its fitted width is being reported as no longer fitted")
	}
}

// **The local command is deliberately NOT affected, and this is the line between the two questions.**
//
// `FitInForce` backs `agtermfit`, whose next move is to restore the owner's window from the recorded
// restore point. That point still exists after a human drags the window, so the local door must still
// be willing to use it — and `TestThePublishedFlagAgreesWithTheStore` asserts that flag against
// `store.Active`, an invariant this change must not break.
func TestTheLocalDoorStillSeesTheFitItCanRestore(t *testing.T) {
	h := fitted(t, screenOf(t, 60), 41)

	_ = h.Handle(context.Background(), Request{Verb: VerbScreen, Session: "11111111-1111-4111-8111-111111111111"})

	if !h.FitInForce() {
		t.Error("the local restore path lost sight of a fit whose restore point still exists")
	}
	if h.FitInForce() != (h.store.Active != nil) {
		t.Errorf("published %v, store holds %v", h.FitInForce(), h.store.Active != nil)
	}
}

// A completed operation re-establishes the truth, so the stale measurement must not outlive it —
// otherwise the first fit after a manual drag would be reported as already broken.
func TestAFreshOperationDiscardsTheOldMeasurement(t *testing.T) {
	h := fitted(t, screenOf(t, 60), 41)

	_ = h.Handle(context.Background(), Request{Verb: VerbScreen, Session: "11111111-1111-4111-8111-111111111111"})
	if pollSaysFitted(t, h) {
		t.Fatal("the measurement did not take, so the reset below proves nothing")
	}

	// As a new calibration would leave it: a fit at the width the window now has.
	h.store.Active = &resize.Fit{Display: 0, BoxWidthDp: 440, MarginDp: 4, Points: 900, Columns: 60}
	h.publishFit()

	if !pollSaysFitted(t, h) {
		t.Error("a freshly applied fit is reported as broken by a measurement taken before it")
	}
}
