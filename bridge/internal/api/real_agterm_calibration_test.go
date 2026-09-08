package api

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
	"github.com/isachivka/agterm-remote/bridge/internal/resize"
)

// Runs the calibration against the REAL agterm on this machine. Skipped unless BOS_REAL_AGTERM=1.
//
// **Everything else here is tested against a fake with a known right answer - the exact condition
// under which two previous attempts at this feature passed and were wrong on the owner's screen.**
// This is the only test that can tell those two apart.
//
// It resizes the owner's window. Their geometry is recorded outside this code before it runs.
func TestRealAgtermCalibration(t *testing.T) {
	if os.Getenv("BOS_REAL_AGTERM") != "1" {
		t.Skip("set BOS_REAL_AGTERM=1 to run against the live agterm")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Second)
	defer cancel()

	client := agterm.New(agterm.DefaultSocketPath())
	term := terminal{client: client}
	// **Its own directory, and it is asserted rather than assumed.** This test used to be able to
	// write where the owner's running bridge reads, and a test that leaves state in production config
	// is a test that can break the user - on 2026-07-30 a fit measured here for a 1728dp laptop window
	// ended up governing their phone.
	dir := t.TempDir()
	live := os.ExpandEnv("$HOME/.config/agterm-bridge/resize-cache.json")
	beforeLive, _ := os.Stat(live)
	t.Cleanup(func() {
		afterLive, _ := os.Stat(live)
		switch {
		case beforeLive == nil && afterLive != nil:
			t.Errorf("this test created %s - the owner's bridge reads that file", live)
		case beforeLive != nil && afterLive != nil && !beforeLive.ModTime().Equal(afterLive.ModTime()):
			t.Errorf("this test modified %s - the owner's bridge reads that file", live)
		}
	})

	windows, err := term.Windows(ctx)
	if err != nil {
		t.Fatal(err)
	}
	before := windows[0]
	t.Logf("BEFORE window=%s %dx%d fullscreen=%v zoomed=%v",
		before.ID, before.Width, before.Height, before.Fullscreen, before.Zoomed)

	treeBefore, _ := client.Tree(ctx)
	countBefore := countSessions(treeBefore)

	// The sidebar is a knob now, so it is recorded and put back exactly as the window is.
	// The no-op first: sending the width back must echo it and move nothing, which is the positive
	// control on the wire shape before anything is trusted to move.
	laptop, err := term.Laptop(ctx, before.ID)
	if err != nil {
		t.Fatalf("LAPTOP could not be read from the tree: %v", err)
	}
	t.Logf("BEFORE sidebar=%.1f points visible=%v font=%d",
		float64(laptop.SidebarWidthMilli)/1000, laptop.SidebarVisible, laptop.FontSize)
	if laptop.SidebarWidthMilli > 0 {
		echoed, err := term.SetSidebarWidth(ctx, before.ID, laptop.SidebarWidthMilli)
		if err != nil || echoed != laptop.SidebarWidthMilli {
			t.Fatalf("NO-OP sidebar.width echoed %d for %d (err %v)", echoed, laptop.SidebarWidthMilli, err)
		}
		t.Cleanup(func() {
			if _, err := term.SetSidebarWidth(context.Background(), before.ID, laptop.SidebarWidthMilli); err != nil {
				t.Errorf("SIDEBAR RESTORE FAILED: %v", err)
			}
		})
	}

	// Compare the CODE against what my hands measured: 1728 -> 162, 900 -> 58, 500-asked -> 26.
	// 26 columns needs a window below the 640-point floor with any sidebar this Mac has had, so the
	// third target is the sidebar stage's live run. Measured 2026-09-05: 36 columns at the floor with
	// a 310.5-point sidebar, 27 at 390.5, 26 at 398.5 - two steps, the second for rounding.
	for _, target := range []int{162, 58, 26} {
		fit, err := resize.Calibrate(ctx, term, dir, before, 403, 4, 10840, target)
		if err != nil {
			t.Errorf("CODE target=%d FAILED: %v", target, err)
			continue
		}
		t.Logf("CODE target=%d -> settled at %d points with a %.1f-point sidebar, terminal rendered %d columns",
			target, fit.Points, float64(fit.SidebarWidthMilli)/1000, fit.Columns)
	}

	treeAfter, _ := client.Tree(ctx)
	t.Logf("SESSIONS before=%d after=%d (equal means the calibration session was removed)",
		countBefore, countSessions(treeAfter))

	// The width it had, and the height it had: agterm requires both, and handing back what was read
	// leaves the height untouched.
	if err := term.ResizeWindow(ctx, before.ID, before.Width, before.Height); err != nil {
		t.Errorf("RESTORE FAILED: %v", err)
	}
	time.Sleep(2 * time.Second)
	after, _ := term.Windows(ctx)
	t.Logf("AFTER  window=%s %dx%d fullscreen=%v zoomed=%v",
		after[0].ID, after[0].Width, after[0].Height, after[0].Fullscreen, after[0].Zoomed)
}

func countSessions(tr *agterm.Tree) int {
	if tr == nil {
		return -1
	}
	n := 0
	for _, ws := range tr.Workspaces {
		n += len(ws.Sessions)
	}
	return n
}
