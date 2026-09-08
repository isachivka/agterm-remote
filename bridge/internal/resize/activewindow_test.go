package resize

import (
	"context"
	"testing"
)

// Which window the fit acts on.
//
// # The bug this file exists for
//
// `active` returned **the first window agterm listed with a usable size**. Nothing in it selected by
// activeness; the name asserted a property the body never implemented.
//
// With one window those are the same window. With two the mismatch is structural: `Client.Tree` asks
// agterm for the FRONTMOST window's tree, so every session the phone can show belongs to that window —
// while the resize went to whichever window came first in `window.list`. **The owner could press Fit on
// a session in front of him and watch a different window change shape.**
//
// It never happened to him because he runs one window. That is latent, not safe.
//
// agterm has always sent the flag; `agterm.Window` decodes it; the adapter dropped it on the floor.

func window(id string, active bool, width int) Window {
	return Window{ID: id, Active: active, Width: width, Height: 900, Display: 0}
}

// **THE TEST THE DEFECT FAILS.** The active window is second in the list, so list order and activeness
// disagree and only one of them is the right answer.
func TestTheActiveWindowIsChosenOverTheFirstOne(t *testing.T) {
	got, err := active([]Window{window("first", false, 1200), window("frontmost", true, 1400)})
	if err != nil {
		t.Fatal(err)
	}

	if got.ID != "frontmost" {
		t.Fatalf("chose %q; the phone's session list is the FRONTMOST window's, so resizing any other "+
			"window changes something the owner is not looking at", got.ID)
	}
}

// **The fallback is the old behaviour, and it is deliberate.** A list with nothing marked active is not
// evidence about which window to pick, so it gets the answer it has always had. Declining would take
// the feature away over a field that has never been absent.
func TestWithNothingMarkedActiveTheFirstUsableWindowIsStillChosen(t *testing.T) {
	got, err := active([]Window{window("first", false, 1200), window("second", false, 1400)})
	if err != nil {
		t.Fatal(err)
	}

	if got.ID != "first" {
		t.Fatalf("chose %q, changing behaviour for a list that says nothing about activeness", got.ID)
	}
}

// **A window whose size we cannot confirm is skipped even when it claims to be active**, which this
// must not undo. The rule exists because a window reported `fullscreen: true` at
// 802 points on a 1496-point display: a flag is not a geometry, and a resize path may not act on one.
func TestAnActiveWindowWithNoUsableSizeIsNotChosen(t *testing.T) {
	got, err := active([]Window{window("sizeless", true, 0), window("real", false, 1400)})
	if err != nil {
		t.Fatal(err)
	}

	if got.ID != "real" {
		t.Fatalf("chose %q - a window with no usable size is not a window we can resize, whatever it "+
			"says about itself", got.ID)
	}
}

func TestNoUsableWindowIsStillAnError(t *testing.T) {
	if _, err := active([]Window{window("sizeless", true, 0)}); err == nil {
		t.Fatal("a list with nothing resizable in it must be an error, not a zero Window")
	}
}

// **End to end: the fit resizes the ACTIVE window.** The unit above pins the chooser; this pins that
// the chooser is what the resize path actually uses, which is the half a renamed helper could break
// while every test above stayed green.
func TestTheFitResizesTheActiveWindowAndNotTheFirstListed(t *testing.T) {
	term := owners()
	term.window.Active = true
	term.otherWindows = []Window{window("somebody-elses", false, 1200)}

	if _, _, err := To(context.Background(), term, LoadStore(t.TempDir()), t.TempDir(),
		440, 12, 9800, 37, IntentApply); err != nil {
		t.Fatal(err)
	}

	if len(term.resizedIDs) == 0 {
		t.Fatal("nothing was resized")
	}
	for _, id := range term.resizedIDs {
		if id != term.window.ID {
			t.Fatalf("resized %q; the owner's session list belongs to %q", id, term.window.ID)
		}
	}
}
