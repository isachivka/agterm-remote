package main

import (
	"encoding/json"
	"path/filepath"
	"sync"
	"testing"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
	"github.com/isachivka/agterm-remote/bridge/internal/agterm/agtermtest"
	"github.com/isachivka/agterm-remote/bridge/internal/api"
	"github.com/isachivka/agterm-remote/bridge/internal/resize"
)

// stop()'s promise - "a window this bridge resized is put back the way it was found" - is kept on
// the way out of run, through the phone's own off path. Without this an ordinary Quit left the
// window narrow and, since the tall fit, the pane's pty at 200 rows with nothing listening for
// "Undo phone fit". A store with a fit in force and a restore point is what a running bridge has
// on disk; the fake agterm records the resize the exit performs.
func TestExitPutsTheFitBack(t *testing.T) {
	dir := t.TempDir()
	store := &resize.Store{
		Active:  &resize.Fit{Display: 0, BoxWidthDp: 440, CharacterWidthMilliDp: 9800, Points: 769, Columns: 41},
		Pending: &resize.Restore{WindowID: "w1", Width: 1728, Height: 1084},
	}
	if err := store.Save(dir); err != nil {
		t.Fatal(err)
	}

	var mu sync.Mutex
	var widths []int
	fake := agtermtest.Start(t, func(req agtermtest.Request) any {
		switch req.Cmd {
		case "window.list":
			return agtermtest.OK(map[string]any{"windows": []any{
				map[string]any{"id": "w1", "active": true,
					"geometry": map[string]any{"display": 0, "width": 769, "height": 1084}},
			}})
		case "window.resize":
			var args struct {
				Width int `json:"width"`
			}
			_ = json.Unmarshal(req.Args, &args)
			mu.Lock()
			widths = append(widths, args.Width)
			mu.Unlock()
			return agtermtest.OK(map[string]any{})
		}
		return agtermtest.OK(map[string]any{})
	})

	handler := api.New(agterm.New(fake.Path), dir)
	if !handler.FitInForce() {
		t.Fatal("the store on disk did not load as a fit in force")
	}
	putBackOnExit(handler)

	mu.Lock()
	defer mu.Unlock()
	if len(widths) == 0 || widths[len(widths)-1] != 1728 {
		t.Fatalf("window.resize widths on exit = %v; want the window put back to 1728", widths)
	}
	if handler.FitInForce() {
		t.Fatal("the fit is still in force after the exit put it back")
	}
	if resize.LoadStore(dir).Active != nil {
		t.Fatalf("the store on disk still holds a fit; file: %s", filepath.Join(dir, "resize-cache.json"))
	}
}

// Nothing in force means nothing to put back, and in particular no agterm round trip on the way
// out: a bridge exiting because agterm is gone must not wait on it.
func TestExitWithNoFitTouchesNothing(t *testing.T) {
	dir := t.TempDir()
	called := false
	fake := agtermtest.Start(t, func(req agtermtest.Request) any {
		called = true
		return agtermtest.OK(map[string]any{})
	})
	putBackOnExit(api.New(agterm.New(fake.Path), dir))
	if called {
		t.Fatal("agterm was asked something with no fit in force")
	}
}
