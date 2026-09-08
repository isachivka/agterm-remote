package api

import (
	"context"
	"encoding/json"
	"sync"
	"testing"

	"dev.isachivka.bewareofsugar/bridge/internal/agterm"
	"dev.isachivka.bewareofsugar/bridge/internal/agterm/agtermtest"
	"dev.isachivka.bewareofsugar/bridge/internal/resize"
)

// **The race the control socket made reachable, run under -race.**
//
// Until the local door existed the phone was the only caller in practice, so the store was touched by
// one goroutine at a time by accident of deployment. The palette command is an independent caller that
// can arrive mid-calibration, and what the two of them share is the store: `Active`, the fits map, and
// the pending restore point — the file holding the only record of where the owner's window came from.
//
// This test does what the two callers do: polls that read the setting on every reply, against restores
// arriving at the same moment. Without the serialization in serialize.go it fails as a data race, not
// as a wrong answer, which is the kind that survives every functional test anyone writes.
func TestPollsAndRestoresDoNotRaceForTheStore(t *testing.T) {
	fake := agtermtest.Start(t, func(req agtermtest.Request) any {
		switch req.Cmd {
		case "tree":
			return agtermtest.OK(map[string]any{"workspaces": []any{}})
		case "window.list":
			return agtermtest.OK(map[string]any{"windows": []any{
				map[string]any{"id": "w1", "display": 0, "width": 1728, "height": 1084},
			}})
		default:
			return agtermtest.OK(map[string]any{})
		}
	})
	h := New(agterm.New(fake.Path), t.TempDir())

	ctx := context.Background()
	var wg sync.WaitGroup

	// The phone: sessions and screen polls, each of which reads the setting on the way out.
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for n := 0; n < 40; n++ {
				_ = h.Handle(ctx, Request{Verb: VerbSessions})
			}
		}()
	}

	// The palette command, and the phone's own off press, arriving at the same instant. A resize with
	// no box width is the restore path - the one that consumes `pending_restore`.
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for n := 0; n < 40; n++ {
				_ = h.RestoreFit(ctx)
				_ = h.FitInForce()
			}
		}()
	}

	wg.Wait()
}

// The published flag must agree with the store once everything has settled. A flag that could drift
// from what it mirrors would be a second source of truth inside one process, which is the bug this
// project spent a day removing between the phone and the bridge.
func TestThePublishedFlagAgreesWithTheStore(t *testing.T) {
	h := New(agterm.New(t.TempDir()+"/absent.sock"), t.TempDir())

	if h.FitInForce() != (h.store.Active != nil) {
		t.Fatalf("published %v, store holds %v", h.FitInForce(), h.store.Active != nil)
	}

	// And after an operation, still.
	_ = h.Handle(context.Background(), Request{Verb: VerbResize})
	if h.FitInForce() != (h.store.Active != nil) {
		t.Errorf("after a restore: published %v, store holds %v", h.FitInForce(), h.store.Active != nil)
	}
}

// **What a POLL reply actually carries when a fit is in force.**
//
// This is the test that would have caught the defect the serialization introduced: the flag was
// published and the count was not, so every poll said "fitted" with no columns field at all, the phone
// read zero from its own `optInt`, and its accessibility label would have announced zero columns from
// the first poll after a press.
//
// It asserts the JSON, not the struct, because the phone parses JSON and `Columns` is omitempty - a
// zero count is not a zero on the wire, it is an ABSENT field, and absent is what the phone turns
// into zero.
func TestAPollReplyCarriesTheCountAsWellAsTheFlag(t *testing.T) {
	// The shared tree() fixture, so this exercises a SUCCESSFUL poll. Written with a hand-rolled
	// response first, which produced `{"ok":false,"error":"agterm is not answering"}` - a test that
	// believed it was checking a poll while checking an error reply. Found by watching the mutation
	// output rather than the pass.
	fake := agtermtest.Start(t, func(agtermtest.Request) any { return agtermtest.OK(tree()) })
	h := New(agterm.New(fake.Path), t.TempDir())

	// A fit in force, as a completed calibration would leave it. Set through the store and published
	// the same way an operation publishes it.
	h.store.Active = &resize.Fit{Display: 0, BoxWidthDp: 440, MarginDp: 4, Points: 769, Columns: 41}
	h.publishFit()

	raw, err := json.Marshal(h.Handle(context.Background(), Request{Verb: VerbSessions}))
	if err != nil {
		t.Fatal(err)
	}

	var wire map[string]any
	if err := json.Unmarshal(raw, &wire); err != nil {
		t.Fatal(err)
	}
	if wire["ok"] != true {
		t.Fatalf("this is not a successful poll, so it is not the case under test: %s", raw)
	}
	if wire["fit_enabled"] != true {
		t.Errorf("fit_enabled is %v on a poll with a fit in force", wire["fit_enabled"])
	}
	columns, ok := wire["columns"]
	if !ok {
		t.Fatalf("no columns field at all: %s - the phone reads that as zero and renders it", raw)
	}
	if columns != float64(41) {
		t.Errorf("columns is %v, want the 41 the fit is in force at", columns)
	}
}

// Off carries no count, and that is right: an absent columns beside `fit_enabled: false` says the same
// thing twice rather than inventing a width for a window nobody narrowed.
func TestWithNoFitInForceThePollCarriesNoCount(t *testing.T) {
	fake := agtermtest.Start(t, func(agtermtest.Request) any { return agtermtest.OK(tree()) })
	h := New(agterm.New(fake.Path), t.TempDir())

	raw, _ := json.Marshal(h.Handle(context.Background(), Request{Verb: VerbSessions}))
	var wire map[string]any
	_ = json.Unmarshal(raw, &wire)

	if wire["ok"] != true {
		t.Fatalf("this is not a successful poll, so it is not the case under test: %s", raw)
	}
	if wire["fit_enabled"] != false {
		t.Errorf("fit_enabled is %v with nothing in force", wire["fit_enabled"])
	}
	if _, present := wire["columns"]; present {
		t.Errorf("a count rode along with an off setting: %s", raw)
	}
}

// **A restart that puts the window back must also turn the setting off.**
//
// Observed on the owner's machine 2026-07-30, twice, from an entirely ordinary sequence: press fit on,
// then any deploy - the reload agent kickstarts the bridge whenever main moves. RestorePending
// restored the window and cleared the restore point but left `Active` set, so every reply carried
// `fit_enabled: true` with a column count for a window nobody had narrowed, and the phone's toggle
// read ON with nothing to undo.
func TestARestartThatRestoresTheWindowTurnsTheSettingOff(t *testing.T) {
	// window.list as well as tree: a restore now reads the window it is putting back, because agterm
	// requires a positive height and the honest one comes from the window rather than from memory.
	fake := agtermtest.Start(t, func(req agtermtest.Request) any {
		if req.Cmd == "window.list" {
			return agtermtest.OK(map[string]any{"windows": []any{
				map[string]any{"id": "w1", "geometry": map[string]any{"width": 1728, "height": 1084, "display": 0}},
			}})
		}
		return agtermtest.OK(tree())
	})
	h := New(agterm.New(fake.Path), t.TempDir())

	// The state a deploy finds: a fit in force, and a window this bridge narrowed on a previous run.
	h.store.Active = &resize.Fit{Display: 0, BoxWidthDp: 440, MarginDp: 4, Points: 769, Columns: 41}
	h.store.Pending = &resize.Restore{WindowID: "w1", Width: 1728, Height: 1084}
	h.publishFit()

	if err := h.RestorePending(context.Background()); err != nil {
		t.Fatal(err)
	}

	if h.store.Active != nil {
		t.Errorf("the setting survived the restore: %+v", h.store.Active)
	}
	// And the published flag agrees, so the very first reply after a restart is truthful.
	if h.FitInForce() {
		t.Error("the flag on every reply still says a fit is in force")
	}
}
