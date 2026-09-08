package resize

import (
	"context"
	"strings"
	"testing"
)

// REQ-0043. The window has a floor of 640 points and the sidebar does not, so when the window cannot
// get narrower the sidebar gets wider. These tests hold the second knob to the same rules as the first:
// it is recorded before it moves, applied with the fit it was measured in, and put back on off.

// --- The restore point -----------------------------------------------------------------------------

func TestTheRestorePointRecordsTheSidebar(t *testing.T) {
	term, store, dir := fresh(t)

	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 40, IntentApply); err != nil {
		t.Fatal(err)
	}

	if store.Pending == nil || store.Pending.SidebarWidthMilli != 305_000 {
		t.Fatalf("the restore point is %+v; it must remember the 305-point sidebar the owner had", store.Pending)
	}
}

func TestTheRestorePutsTheSidebarBack(t *testing.T) {
	term, store, dir := fresh(t)
	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 40, IntentApply); err != nil {
		t.Fatal(err)
	}
	// As if the fit had widened it.
	term.sidebarMilli = 420_000
	term.sidebarSets, term.sidebarSetIDs = nil, nil

	if err := RestoreWindow(context.Background(), term, store); err != nil {
		t.Fatal(err)
	}

	if term.sidebarMilli != 305_000 {
		t.Errorf("the sidebar is %d milli-points after the restore, want the owner's 305000", term.sidebarMilli)
	}
	if len(term.sidebarSetIDs) != 1 || term.sidebarSetIDs[0] != "w1" {
		t.Errorf("the sidebar was set on %v; it must be set on the fit's window, never the frontmost", term.sidebarSetIDs)
	}
	if store.Pending != nil {
		t.Error("the restore point survived a restore that succeeded in full")
	}
}

// The window is back but the sidebar is not: the record stays, so the next off press can finish. A
// record spent on a half-restore would leave the owner's sidebar wide with nothing that remembers
// how wide it was.
func TestAFailedSidebarRestoreKeepsTheRestorePoint(t *testing.T) {
	term, store, dir := fresh(t)
	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 40, IntentApply); err != nil {
		t.Fatal(err)
	}
	term.sidebarMilli = 420_000
	term.failSidebar = true

	if err := RestoreWindow(context.Background(), term, store); err == nil {
		t.Fatal("a restore whose sidebar step failed reported success")
	}

	if term.window.Width != 1728 {
		t.Errorf("the window is %d; the width step comes first and must have happened", term.window.Width)
	}
	if store.Pending == nil {
		t.Fatal("the restore point was spent on a restore that did not finish")
	}
}

// A restore point written before REQ-0043 has no sidebar in it, and nothing is sent for it.
func TestARestorePointWithNoSidebarTouchesNoSidebar(t *testing.T) {
	term, store, _ := fresh(t)
	store.Pending = &Restore{WindowID: "w1", Width: 1728, Height: 1084}

	if err := RestoreWindow(context.Background(), term, store); err != nil {
		t.Fatal(err)
	}
	if len(term.sidebarSets) != 0 {
		t.Errorf("sent %v to the sidebar on the strength of a record that says nothing about it", term.sidebarSets)
	}
}

// --- Applying a cached fit --------------------------------------------------------------------------

// The fit is applied in the layout it was measured in. The sidebar used to be a thing the detector
// watched for drift; it is now a thing the fit sets, so it cannot drift.
func TestACachedFitPinsTheSidebarItWasMeasuredWith(t *testing.T) {
	term, store, dir := fresh(t)
	store.Fits[fitKey(0, boxDp, charMilli)] = Fit{
		Display: 0, BoxWidthDp: boxDp, MarginDp: marginDp, Points: 600, Columns: 37,
		SidebarWidthMilli: 309_480, SidebarVisible: true, FontSize: 13,
	}
	term.laptop(250_000, 13)

	got, calibrated, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply)
	if err != nil {
		t.Fatal(err)
	}

	if calibrated || len(term.created) != 0 {
		t.Errorf("calibrated=%v sessions=%v; a sidebar that differs is applied, not measured", calibrated, term.created)
	}
	if got != 37 {
		t.Errorf("got %d columns, want the fit's 37", got)
	}
	if len(term.sidebarSets) != 1 || term.sidebarSets[0] != 309_480 || term.sidebarSetIDs[0] != "w1" {
		t.Errorf("sidebar sets %v on %v; want exactly 309480 on w1", term.sidebarSets, term.sidebarSetIDs)
	}
	if len(term.events) < 2 || term.events[0] != "sidebar" || term.events[1] != "resize" {
		t.Errorf("order was %v; the sidebar is set before the window is narrowed", term.events)
	}
}

func TestAFitWithNoRecordedSidebarLeavesTheSidebarAlone(t *testing.T) {
	term, store, dir := fresh(t)
	store.Fits[fitKey(0, boxDp, charMilli)] = Fit{
		Display: 0, BoxWidthDp: boxDp, MarginDp: marginDp, Points: 600, Columns: 37,
	}

	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply); err != nil {
		t.Fatal(err)
	}
	if len(term.sidebarSets) != 0 {
		t.Errorf("sent %v to the sidebar for a fit that recorded none", term.sidebarSets)
	}
}

// A fit applied at the wrong sidebar width is a fit that delivers the wrong column count and says
// nothing. Silently wrong is worse than nothing, so it is refused, and refused before the window moves.
func TestASidebarThatCannotBeSetRefusesTheCachedFit(t *testing.T) {
	term, store, dir := fresh(t)
	store.Fits[fitKey(0, boxDp, charMilli)] = Fit{
		Display: 0, BoxWidthDp: boxDp, MarginDp: marginDp, Points: 600, Columns: 37,
		SidebarWidthMilli: 309_480, SidebarVisible: true, FontSize: 13,
	}
	term.failSidebar = true

	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply); err == nil {
		t.Fatal("the fit was applied at a sidebar width it was not measured with")
	}
	if len(term.applied) != 0 {
		t.Errorf("the window was resized to %v before the sidebar was known to be wrong", term.applied)
	}
	if store.Active != nil {
		t.Error("a fit that was not applied is recorded as in force")
	}
}

// --- The detector, after the sidebar became a control -----------------------------------------------

func TestASidebarDragNoLongerBuysAMeasurement(t *testing.T) {
	term, store, dir := fresh(t)
	store.Fits[fitKey(0, boxDp, charMilli)] = Fit{
		Display: 0, BoxWidthDp: boxDp, MarginDp: marginDp, Points: 600, Columns: 37,
		SidebarWidthMilli: 420_000, SidebarVisible: true, FontSize: 13,
	}
	term.laptop(309_480, 13)

	if _, calibrated, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply); err != nil || calibrated {
		t.Fatalf("calibrated=%v err=%v", calibrated, err)
	}
	if len(term.created) != 0 {
		t.Errorf("made %v sessions over a sidebar width the fit was about to set anyway", term.created)
	}
}

// A sidebar that is HIDDEN takes no width at all, whatever width it is set to, so a fit measured with
// it showing is a different question. Visibility is not ours to change; it buys a measurement.
func TestAHiddenSidebarBuysAMeasurement(t *testing.T) {
	term, store, dir := fresh(t)
	store.Fits[fitKey(0, boxDp, charMilli)] = Fit{
		Display: 0, BoxWidthDp: boxDp, MarginDp: marginDp, Points: 600, Columns: 37,
		SidebarWidthMilli: 305_000, SidebarVisible: true, FontSize: 14,
	}
	term.sidebarVisible = false

	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply); err != nil {
		t.Fatal(err)
	}
	if len(term.created) != 1 {
		t.Errorf("made %v sessions; a sidebar that went from showing to hidden should have been measured once", term.created)
	}
}

// --- Calibration: the sidebar stage -------------------------------------------------------------------

// 37 columns needs a 599-point window with the owner's chrome, and the window will not go below 640.
// Before REQ-0043 this press ended in "calibration ended on 41 columns having asked for 37" every time.
func TestAWindowPinnedAtItsFloorWidensTheSidebar(t *testing.T) {
	term, store, dir := fresh(t)
	term.floorPoints = 640

	got, calibrated, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply)
	if err != nil {
		t.Fatalf("a fit the sidebar could reach was refused: %v", err)
	}

	if !calibrated || got != 37 {
		t.Errorf("calibrated=%v got=%d; want a fresh fit of exactly 37 columns", calibrated, got)
	}
	if term.sidebarMilli <= 305_000 {
		t.Errorf("the sidebar is still %d; the window was pinned, so the sidebar had to give", term.sidebarMilli)
	}
	fit := store.Fits[fitKey(0, boxDp, charMilli)]
	if fit.SidebarWidthMilli != term.sidebarMilli {
		t.Errorf("the fit records a sidebar of %d while the Mac has %d; the fit must carry the layout it was measured in",
			fit.SidebarWidthMilli, term.sidebarMilli)
	}
	if store.Active == nil || store.Active.Columns != 37 {
		t.Errorf("the setting is %+v, want the 37-column fit", store.Active)
	}
}

func TestAPinnedWindowIsNotSearchedBelowItsFloor(t *testing.T) {
	term, store, dir := fresh(t)
	term.floorPoints = 640

	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply); err != nil {
		t.Fatal(err)
	}
	// Two probes, one guess that lands on the floor, and the re-measurement after the sidebar moved.
	// A search that kept bisecting below 640 would resize a dozen times and learn nothing.
	if len(term.applied) > 5 {
		t.Errorf("%d resizes (%v) for a window that reported the same width every time below 640", len(term.applied), term.applied)
	}
}

func TestTheSidebarIsNotTouchedWhenTheWindowCanShrink(t *testing.T) {
	term, store, dir := fresh(t)
	term.floorPoints = 640

	// 45 columns is 671 points here, above the floor, so this is the fit as it always was.
	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 45, IntentApply); err != nil {
		t.Fatal(err)
	}
	if len(term.sidebarSets) != 0 {
		t.Errorf("the sidebar was set to %v for a fit the window could reach alone", term.sidebarSets)
	}
}

// The sidebar stops at 560. Past that the fit is impossible, and the refusal says so with its numbers,
// the way the impossible-fit refusal always has - and the window AND the sidebar go back.
func TestASidebarAtItsMaximumRefusesWithTheArithmetic(t *testing.T) {
	term, store, dir := fresh(t)
	term.floorPoints = 640
	term.laptop(540_000, 14)

	_, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 30, IntentApply)
	if err == nil {
		t.Fatal("a fit that needs a 660-point sidebar was accepted")
	}

	if !strings.Contains(err.Error(), "560") {
		t.Errorf("the refusal does not say the sidebar is at its maximum: %v", err)
	}
	if term.sidebarMilli != 540_000 {
		t.Errorf("the sidebar was left at %d after a refusal; the owner's 540 must come back", term.sidebarMilli)
	}
	if term.window.Width != 1728 {
		t.Errorf("the window was left at %d after a refusal", term.window.Width)
	}
	if store.Active != nil || len(store.Fits) != 0 {
		t.Errorf("a refused fit left a setting (%+v) or an entry (%d) behind", store.Active, len(store.Fits))
	}
}

// The 600-point probe settles at 640 on a real Mac. A slope computed from the width that was ASKED
// puts the cell 5% too wide, and every step after it, including how far to widen the sidebar, inherits
// the error. The settled widths are the ones the columns were measured at.
func TestTheCellIsMeasuredFromWhereTheWindowSettled(t *testing.T) {
	term, store, dir := fresh(t)
	term.floorPoints = 640

	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 45, IntentApply); err != nil {
		t.Fatal(err)
	}
	cell := store.Fits[fitKey(0, boxDp, charMilli)].CellMilliPoints
	if cell < 8_900 || cell > 9_200 {
		t.Errorf("cell measured as %d milli-points; the fake's is 9030, and 9500 is what the asked widths give", cell)
	}
}
