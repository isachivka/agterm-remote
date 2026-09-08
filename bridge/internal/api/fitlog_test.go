package api

import (
	"context"
	"strings"
	"testing"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm/agtermtest"
)

// What the fit log may and may not contain.
//
// # Why there is a log at all
//
// The owner, on v0.20.0: *"а вот фит — опять нет. нажимаю и ничего, хотя нотификация есть… может логов
// добавим?"* His bridge log for 2026-08-26 03:01 shows four holds in fifty seconds, each answering
// `calibrated, 45 columns now in effect`, then `off`. Every line was true and none was about what he
// was looking at: the fit sizes the WINDOW and he was reading a PANE.
//
// # The line that must never be crossed
//
// Numbers and outcomes are the shape of a decision and are not his. **Session names, titles and screen
// text are his**, and they appear in no log and no persisted file. That is his own narrow rule and it
// does not widen because a diagnosis would be easier with them.

// noisySession is a session whose every free-text field is a distinctive string, so a leak into the log
// is unmistakable rather than something a reader has to notice.
func noisySession(surfaces ...map[string]any) any { return sessionShowing(false, surfaces...) }

// sessionShowing is noisySession with agterm's own `split` flag set.
//
// **Both panes SHOWN is a different question from both panes EXISTING**, and the log answers it from
// this flag rather than by counting visible surfaces. The two fixtures are kept apart so a test has to
// say which state it means.
func sessionShowing(split bool, surfaces ...map[string]any) any {
	list := make([]any, 0, len(surfaces))
	for _, s := range surfaces {
		list = append(list, s)
	}
	return map[string]any{"tree": map[string]any{"workspaces": []any{
		map[string]any{"id": "W1", "name": "WORKSPACENAME-7731", "active": true, "sessions": []any{
			map[string]any{
				"id": sessionA, "active": true,
				"name":     "SESSIONNAME-4417",
				"title":    "TITLE-9928-refactor the parser",
				"cwd":      "/Users/somebody/PRIVATEDIR-5502",
				"split":    split,
				"surfaces": list,
			},
		}},
	}}}
}

func surface(kind string, visible bool) map[string]any {
	return map[string]any{"id": "surface:" + kind, "kind": kind, "visible": visible}
}

func shapeOf(t *testing.T, reply any, session string) string {
	t.Helper()
	h, _ := handler(t, func(agtermtest.Request) any { return agtermtest.OK(reply) })
	return h.paneShape(context.Background(), session)
}

// **THE GUARD.** Nothing the owner wrote reaches the log line.
func TestTheFitLogNamesNothingOfHis(t *testing.T) {
	for _, c := range []struct {
		name  string
		panes []map[string]any
	}{
		{"one pane", []map[string]any{surface("left", true)}},
		{"a split", []map[string]any{surface("left", true), surface("right", true)}},
		{"a collapsed split", []map[string]any{surface("left", true), surface("right", false)}},
		{"a scratch terminal", []map[string]any{surface("left", true), surface("scratch", false)}},
	} {
		t.Run(c.name, func(t *testing.T) {
			line := shapeOf(t, noisySession(c.panes...), sessionA)

			for _, his := range []string{
				"SESSIONNAME-4417", "TITLE-9928", "WORKSPACENAME-7731", "PRIVATEDIR-5502",
			} {
				if strings.Contains(line, his) {
					t.Fatalf("the fit log carried the owner's own words (%q): %s", his, line)
				}
			}
			if line == "" {
				t.Fatal("the diagnostic said nothing at all, which is the state it was added to end")
			}
		})
	}
}

// **The distinction the log exists to draw.** One pane and two panes must not read alike, because they
// produce very different column counts and looked identical in the record for a day.
func TestTheFitLogSaysWhetherThereIsASecondPane(t *testing.T) {
	one := shapeOf(t, noisySession(surface("left", true)), sessionA)
	two := shapeOf(t, sessionShowing(true, surface("left", true), surface("right", true)), sessionA)

	if !strings.Contains(one, "one pane") {
		t.Fatalf("an unsplit session did not say so: %s", one)
	}
	if !strings.Contains(two, "second pane") && !strings.Contains(two, "SHOWING BOTH") {
		t.Fatalf("a split session did not say so: %s", two)
	}
	if one == two {
		t.Fatal("a split and an unsplit session logged the same sentence")
	}
}

// A pane that exists but is collapsed is still a second pane, and the log says which — the two states
// give very different column counts, and they are different states.
//
// **The maximize reversed which of them is the alarming one.** Both panes on screen is now the failure;
// one on screen is the phone getting what it asked for. The distinction is the same distinction and
// this still asserts it in both directions, which is the point of keeping the test rather than
// rewriting it around the new wording.
func TestTheFitLogTellsCollapsedFromOnScreen(t *testing.T) {
	shown := shapeOf(t, sessionShowing(true, surface("left", true), surface("right", true)), sessionA)
	hidden := shapeOf(t, noisySession(surface("left", true), surface("right", false)), sessionA)

	if !strings.Contains(shown, "SHOWING BOTH PANES") {
		t.Fatalf("a visible split did not report both panes on screen: %s", shown)
	}
	if !strings.Contains(hidden, "showing one") {
		t.Fatalf("a collapsed split did not report one pane on screen: %s", hidden)
	}
	if shown == hidden {
		t.Fatal("a collapsed split and a visible one logged the same sentence")
	}
}

// **A scratch terminal is not a second pane**, the same distinction the split gate turns on, held here so the
// log cannot start reporting a split that is not there.
//
// It asserts what the line DOES say as well as what it does not. Checking only for the absence of a
// phrase would have turned into a test that cannot fail the moment the phrase changed — which
// is what happened when this file was first edited for it.
func TestTheFitLogDoesNotCallAScratchTerminalASecondPane(t *testing.T) {
	line := shapeOf(t, noisySession(surface("left", true), surface("scratch", false)), sessionA)

	if !strings.Contains(line, "one pane") {
		t.Fatalf("a session whose only extra surface is a scratch is a one-pane session: %s", line)
	}
	if strings.Contains(line, "second pane") || strings.Contains(line, "SHOWING BOTH") {
		t.Fatalf("a scratch terminal was reported as a split: %s", line)
	}
}

// **The diagnostic can never fail the press.** Every path returns a sentence — a diagnostic that
// refused a fit would make worse the thing it was added to debug.
func TestTheFitLogAlwaysSaysSomething(t *testing.T) {
	t.Run("no session on the request", func(t *testing.T) {
		if line := shapeOf(t, noisySession(surface("left", true)), ""); !strings.Contains(line, "no session") {
			t.Fatalf("an older phone's request produced %q", line)
		}
	})
	t.Run("a session that is not in the tree", func(t *testing.T) {
		if line := shapeOf(t, noisySession(surface("left", true)), sessionB); !strings.Contains(line, "not in the tree") {
			t.Fatalf("an unknown session produced %q", line)
		}
	})
	t.Run("agterm refuses the tree", func(t *testing.T) {
		h, _ := handler(t, func(agtermtest.Request) any {
			return map[string]any{"ok": false, "error": "agterm is not running"}
		})
		if line := h.paneShape(context.Background(), sessionA); !strings.Contains(line, "could not be read") {
			t.Fatalf("a refused tree produced %q", line)
		}
	})
}

// --- the maximize ----------------------------------------------------------------------------------

// **Two panes ON SCREEN is now the finding, and one hidden pane is not.**
//
// The phone hides the split when it shows a pane, so a pane that exists but is not drawn costs the fit
// nothing. A session still SHOWING two under a phone that maximizes means the maximize did not take,
// and the column count about to be logged is a fraction of what he is reading.
//
// The old line warned on `hasSplit` and would now cry wolf on every session the phone has ever touched.
func TestAHiddenSecondPaneIsNotReportedAsAProblem(t *testing.T) {
	line := shapeOf(t, noisySession(surface("left", true), surface("right", false)), sessionA)

	if strings.Contains(line, "should not happen") {
		t.Fatalf("a maximized pane was reported as a failure: %s", line)
	}
	if !strings.Contains(line, "showing one") {
		t.Fatalf("the line does not say the phone got what it asked for: %s", line)
	}
}

// The failure it now exists to catch, and it must be loud in the file rather than merely accurate.
func TestTwoPanesOnScreenSayTheMaximizeDidNotTake(t *testing.T) {
	line := shapeOf(t, sessionShowing(true, surface("left", true), surface("right", true)), sessionA)

	if !strings.Contains(line, "should not happen") {
		t.Fatalf("a session showing two panes was logged as ordinary: %s", line)
	}
}

// **THE FALSE ALARM THIS LINE WOULD HAVE RAISED ON HIS EVERY OTHER SESSION.**
//
// The first version counted VISIBLE SURFACES and warned when there were more than one. A scratch
// terminal is a surface. So a session with a hidden split and an open scratch — two visible surfaces,
// one visible pane — would have been logged as a maximize that did not take, on a machine where the
// maximize worked perfectly.
//
// It is the same conflation one question over: how many things are on screen is not whether the
// SPLIT is on screen. agterm answers the second directly with `isSplit` and the log now asks it.
func TestAnOpenScratchIsNotAFailedMaximize(t *testing.T) {
	line := shapeOf(
		t,
		sessionShowing(false, surface("left", true), surface("right", false), surface("scratch", true)),
		sessionA,
	)

	if strings.Contains(line, "should not happen") {
		t.Fatalf("an open scratch beside a maximized pane was reported as a failed maximize: %s", line)
	}
	if !strings.Contains(line, "showing one") {
		t.Fatalf("the line does not say the phone got what it asked for: %s", line)
	}
}

// **The same rule as before, held by a test rather than by care.** The line describes panes, and a
// session has a name and a cwd, and neither may reach the file.
func TestTheMaximizeLineNamesNothingOfHis(t *testing.T) {
	line := shapeOf(t, sessionShowing(true, surface("left", true), surface("right", true)), sessionA)

	for _, his := range []string{"SESSIONNAME-4417", "TITLE-9928", "WORKSPACENAME-7731", "PRIVATEDIR-5502"} {
		if strings.Contains(line, his) {
			t.Fatalf("the line carried the owner's own words (%q): %s", his, line)
		}
	}
}

// --- which window the fit acts on -----------------------------------------------------------------

// **THE FIELD THAT WAS DROPPED ON THE FLOOR.** agterm has always reported which window is active and
// `agterm.Window` has always decoded it; this adapter is where it stopped, so `resize.active` could
// only choose by list order.
//
// The chooser has its own unit test. This is the wiring, and it is a separate test because reverting
// the adapter alone left every test in the resize package green — a chooser that reads a field nothing
// fills is a correct function given wrong input.
func TestTheAdapterCarriesWhichWindowIsActive(t *testing.T) {
	h, _ := handler(t, func(agtermtest.Request) any {
		return agtermtest.OK(map[string]any{"windows": []any{
			map[string]any{
				"id": "first", "active": false,
				"geometry": map[string]any{"display": 0, "width": 1200, "height": 900},
			},
			map[string]any{
				"id": "frontmost", "active": true,
				"geometry": map[string]any{"display": 0, "width": 1400, "height": 900},
			},
		}})
	})

	windows, err := (terminal{client: h.client}).Windows(context.Background())
	if err != nil {
		t.Fatal(err)
	}

	if len(windows) != 2 {
		t.Fatalf("expected two windows, got %d", len(windows))
	}
	if windows[0].Active {
		t.Error("a window agterm did not mark active arrived marked active")
	}
	if !windows[1].Active {
		t.Fatal("the active window arrived unmarked - the resize path then picks by list order, and " +
			"the owner watches a window he is not looking at change shape")
	}
}
