package resize

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// fakeTerminal is a terminal whose column count really is a linear function of the applied width, so
// the search can be tested against a world with a known right answer.
type fakeTerminal struct {
	slope, intercept float64
	window           Window
	applied          []int
	// split and ratiosSet record what the probe was SHAPED into. Recorded rather than
	// ignored because "the calibration measured the right geometry" is the whole of that requirement,
	// and a fake that accepted the calls silently would let a probe that never split still pass.
	// staleReads is how many reads after each resize answer with the count from BEFORE it, and
	// staleLeft/staleValue are the working state. See Text.
	staleReads int
	staleLeft  int
	staleValue int
	split      []string
	// collapseBelow is the window width under which a split stops being SHOWN while still existing —
	// splitStateErr makes the diagnostic itself fail, which must never fail a calibration.
	collapseBelow int
	splitStateErr error
	// otherWindows are listed BEFORE f.window, so a chooser that takes the first one gets the wrong
	// answer and a chooser that reads `Active` gets the right one. resizedIDs is which
	// window each resize was aimed at, which the fake used to discard: a fake that ignores the id is
	// one in which resizing the wrong window is unobservable.
	otherWindows []Window
	resizedIDs   []string
	// ratiosSet is every ratio asked for. What comes BACK is ratioClamp applied to it, so a test can
	// model agterm's 0.05..0.95 clamp and prove the fit records the answer rather than the request.
	ratiosSet  []float64
	ratioClamp float64
	// heights is every height handed to ResizeWindow, and heightAtCall is what the window actually
	// held at that moment. A captured height replayed into a later call shows up as the two
	// disagreeing - see TestAResizeNeverChangesTheHeight.
	heights      []int
	heightAtCall []int
	// heightChangesAfter/To move the height underneath an operation, as a window leaving fullscreen
	// does. Zero means it never moves.
	heightChangesAfter int
	heightChangesTo    int
	// contentWidth caps what MeasureColumns can see. Zero means the screen always has a full-width
	// line; a positive value simulates a session whose content is narrower than the pane.
	contentWidth int
	// columnStep models a terminal that cannot render every column count - the reported figure is
	// rounded UP to a multiple of this. It is why the owner's search landed on 42 having asked for 41,
	// and a fake that can hit any integer exactly cannot reproduce that.
	columnStep int
	// floorColumns is a count the terminal will never report below, however narrow the window gets.
	// It stands in for a target that is genuinely out of reach, which is the only case where refusing
	// is still the right answer.
	floorColumns int
	resizes      int
	// failResizeAfter makes the Nth resize fail, standing in for the bridge dying mid-resize.
	failResizeAfter int

	// Calibration-session bookkeeping. `closed` and `created` are what the provenance tests read:
	// the safety of this whole feature is that those two lists hold the same ids and nothing else.
	created    []string
	closed     []string
	nextID     int
	failCreate bool
	// failText makes reading a session fail, which is what a dropped link, a busy agterm or a
	// cancelled context all look like from here. It exists for one assertion: a verification that
	// could not be MADE must not delete a fit - see TestAFitSurvivesAVerificationThatCouldNotBeMade.
	failText    bool
	zoomToggles int

	// The sidebar, in milli-points. `sidebarOrigin` is the sidebar the intercept above was
	// measured with, so widening the sidebar by N points costs the terminal exactly N points, as it
	// does on the Mac. `sidebarVisible` and `fontSize` are what the tree reports beside it.
	sidebarMilli, sidebarOrigin int
	sidebarVisible              bool
	fontSize                    int
	// sidebarSets is every width asked for, in order, and sidebarSetIDs the window each was sent to.
	sidebarSets   []int
	sidebarSetIDs []string
	failSidebar   bool
	failLaptop    bool
	// floorPoints is the narrowest the window will go, however narrow it is asked to be. agterm's is
	// 640; zero means no floor, which is how every test written before the floor mattered sees it.
	floorPoints int
	// events is the order things happened in, for the tests that care which came first.
	events []string
}

func (f *fakeTerminal) Laptop(context.Context, string) (Laptop, error) {
	if f.failLaptop {
		return Laptop{}, errors.New("the tree could not be read")
	}
	return Laptop{SidebarWidthMilli: f.sidebarMilli, SidebarVisible: f.sidebarVisible, FontSize: f.fontSize}, nil
}

func (f *fakeTerminal) SetSidebarWidth(_ context.Context, id string, milli int) (int, error) {
	if f.failSidebar {
		return 0, errors.New("sidebar.width is not supported by this control host")
	}
	f.sidebarSets = append(f.sidebarSets, milli)
	f.sidebarSetIDs = append(f.sidebarSetIDs, id)
	f.events = append(f.events, "sidebar")
	if milli < 160_000 {
		milli = 160_000
	}
	if milli > 560_000 {
		milli = 560_000
	}
	f.sidebarMilli = milli
	return milli, nil
}

// laptop puts the fake's Mac in a given state: the sidebar THIS wide, at THIS font, with the column
// model unchanged - it replaces the state file the tests used to write.
func (f *fakeTerminal) laptop(sidebarMilli, fontSize int) {
	f.sidebarMilli, f.sidebarOrigin, f.fontSize = sidebarMilli, sidebarMilli, fontSize
}

func (f *fakeTerminal) Windows(context.Context) ([]Window, error) {
	// **A list, because the owner's machine has one window and the bug needed two**. A fake
	// that can only ever report one window is a fake in which choosing the wrong one is unobservable,
	// which is how `active` shipped picking by list order.
	if len(f.otherWindows) > 0 {
		return append(append([]Window{}, f.otherWindows...), f.window), nil
	}
	return []Window{f.window}, nil
}

func (f *fakeTerminal) ResizeWindow(_ context.Context, id string, width, height int) error {
	if f.failResizeAfter > 0 && f.resizes+1 >= f.failResizeAfter {
		return errors.New("the terminal went away")
	}
	f.resizedIDs = append(f.resizedIDs, id)
	f.applied = append(f.applied, width)
	f.events = append(f.events, "resize")
	if f.floorPoints > 0 && width < f.floorPoints {
		width = f.floorPoints
	}
	// **Arm the staleness BEFORE the width moves**, so staleValue is genuinely the count from the
	// window as it was - which is what the real pty goes on printing for ~0.3s.
	f.staleLeft = f.staleReads
	f.window.Width = width
	// **Records the height it was handed, so a test can assert we never CHANGE it.** agterm requires a
	// positive height, so the question is not whether one is sent but whether it is the one the window
	// already has - see TestAResizeNeverChangesTheHeight.
	f.heightAtCall = append(f.heightAtCall, f.window.Height)
	f.heights = append(f.heights, height)
	if height > 0 {
		f.window.Height = height
	}
	// Something other than us moves the height - the shape of a window leaving fullscreen mid-run.
	if f.heightChangesAfter > 0 && len(f.heights) == f.heightChangesAfter {
		f.window.Height = f.heightChangesTo
	}
	// Zoom follows width, as measured on the real machine - see TestTheRestoreLeavesZoomToFollowTheWidth.
	f.window.Zoomed = width >= 1728
	f.resizes++
	return nil
}

// Text stands in for the calibration session's over-long line: the fake wraps at the width the window
// was set to, which is exactly what a real terminal does to a line longer than itself.
func (f *fakeTerminal) Text(context.Context, string, int) (string, error) {
	if f.failText {
		return "", errors.New("the laptop did not answer")
	}
	// **Staleness, because the real pty has it and a fake without it cannot catch the bug** —
	// Measured on the machine: for ~0.3s after a resize the probe goes on printing the
	// PREVIOUS count, and a stale number is a perfectly valid number.
	//
	// `staleReads` is how many reads after each resize still answer with the old figure. Zero is the
	// old fake, which is why the old fake could not fail when the reader took the first number it saw.
	if f.staleLeft > 0 {
		f.staleLeft--
		return fmt.Sprintf("%d\n", f.staleValue), nil
	}
	intercept := f.intercept + float64(f.sidebarMilli-f.sidebarOrigin)/1000
	cols := int((float64(f.window.Width) - intercept) / f.slope)
	if cols < 0 {
		cols = 0
	}
	if f.contentWidth > 0 && cols > f.contentWidth {
		cols = f.contentWidth
	}
	if f.columnStep > 1 {
		cols = ((cols + f.columnStep - 1) / f.columnStep) * f.columnStep
	}
	if f.floorColumns > 0 && cols < f.floorColumns {
		cols = f.floorColumns
	}
	// Prints the column count, exactly as the real calibration session's `tput cols` does. It used to
	// return a line of that many x's, modelling the long-line trick that measurement refuted - a fake
	// still shaped like a design nobody uses is a test that guards the wrong thing.
	f.staleValue = cols
	return fmt.Sprintf("%d\n", cols), nil
}

// zoomToggles counts calls, so a test can tell "restored the zoom" from "toggled it blindly".
func (f *fakeTerminal) ZoomWindow(_ context.Context, _ string) error {
	f.window.Zoomed = !f.window.Zoomed
	f.zoomToggles++
	return nil
}

func (f *fakeTerminal) CloseSession(_ context.Context, id string) error {
	f.closed = append(f.closed, id)
	return nil
}

func (f *fakeTerminal) NewSession(_ context.Context, _, _ string) (string, error) {
	if f.failCreate {
		return "", errors.New("agterm would not make a session")
	}
	f.nextID++
	id := fmt.Sprintf("calib-%d", f.nextID)
	f.created = append(f.created, id)
	return id, nil
}
func TestCloseIsOnlyEverGivenAnIdWeCreated(t *testing.T) {
	term := owners()

	err := withCalibrationSession(context.Background(), term, t.TempDir(), func(string) error { return nil })
	if err != nil {
		t.Fatal(err)
	}

	if len(term.created) != 1 {
		t.Fatalf("created %v, want exactly one calibration session", term.created)
	}
	if len(term.closed) != 1 || term.closed[0] != term.created[0] {
		t.Errorf("closed %v, created %v - close was given an id it did not make", term.closed, term.created)
	}
}

// The control: an id that is NOT ours must never reach close. The owner's sessions are in the tree
// and the calibration path must not touch a single one of them.
func TestNoSessionOtherThanOursIsEverClosed(t *testing.T) {
	term := owners()
	owned := []string{"owner-A", "owner-B", "4C9B5C9B-C77F-4913-8BEA-9DF7513AC8BA"}

	_ = withCalibrationSession(context.Background(), term, t.TempDir(), func(string) error {
		return errors.New("calibration failed half way")
	})

	for _, id := range owned {
		for _, c := range term.closed {
			if c == id {
				t.Fatalf("the bridge closed %q, which is the owner's work", id)
			}
		}
	}
}

// **A failure must still remove the session.** A calibration that errors and also leaves litter in
// their sidebar is worse than one that simply fails.
func TestAFailedCalibrationStillClosesTheSession(t *testing.T) {
	term := owners()

	err := withCalibrationSession(context.Background(), term, t.TempDir(), func(string) error {
		return errors.New("the search blew up")
	})

	if err == nil {
		t.Fatal("the body was supposed to fail; this test is not exercising what it claims")
	}
	if len(term.closed) != 1 || term.closed[0] != term.created[0] {
		t.Errorf("closed %v after a failure, created %v", term.closed, term.created)
	}
}

// The id must be ON DISK before anything that can fail, or a crash orphans the session with nothing
// anywhere knowing it exists. Same ordering, and the same reasoning, as the restore point.
func TestTheCreatedIdIsOnDiskBeforeTheBodyRuns(t *testing.T) {
	dir := t.TempDir()
	term := owners()
	var onDiskDuring string

	_ = withCalibrationSession(context.Background(), term, dir, func(id string) error {
		raw, err := os.ReadFile(filepath.Join(dir, strayFile))
		if err != nil {
			t.Fatalf("nothing on disk while the session existed: %v", err)
		}
		var st stray
		_ = json.Unmarshal(raw, &st)
		onDiskDuring = st.SessionID
		return nil
	})

	if onDiskDuring == "" || onDiskDuring != term.created[0] {
		t.Errorf("disk held %q while the session was %q", onDiskDuring, term.created[0])
	}
	if _, err := os.Stat(filepath.Join(dir, strayFile)); err == nil {
		t.Error("the record survived a clean run, so the next start would close a session that is gone")
	}
}

// A session orphaned by a crash is closed at the next start, from the file rather than from anything
// the owner or the phone supplied.
func TestAStraySessionIsClosedAtStartup(t *testing.T) {
	dir := t.TempDir()
	term := owners()
	rememberStray(dir, "calib-orphan")

	if err := CloseStraySession(context.Background(), term, dir); err != nil {
		t.Fatal(err)
	}

	if len(term.closed) != 1 || term.closed[0] != "calib-orphan" {
		t.Errorf("closed %v, want the orphan", term.closed)
	}
	if _, err := os.Stat(filepath.Join(dir, strayFile)); err == nil {
		t.Error("the record survived, so every start would retry a close that already happened")
	}
}

// Nothing on disk means nothing to close - and emphatically not "close something".
func TestStartupWithNoRecordClosesNothing(t *testing.T) {
	term := owners()

	if err := CloseStraySession(context.Background(), term, t.TempDir()); err != nil {
		t.Fatal(err)
	}
	if len(term.closed) != 0 {
		t.Errorf("closed %v with no record on disk", term.closed)
	}
}

func owners() *fakeTerminal {
	// The owner's own numbers: 9.03 points per column, 265 points of chrome.
	return &fakeTerminal{
		slope:     9.03,
		intercept: 265,
		window:    Window{ID: "w1", Display: 0, Width: 1728, Height: 1084},
		// And the owner's own sidebar and font on 2026-09-05, as the tree reports them.
		sidebarMilli: 305_000, sidebarOrigin: 305_000, sidebarVisible: true, fontSize: 14,
	}
}

const boxDp, marginDp, charMilli = 403, 4, 10840

func fresh(t *testing.T) (*fakeTerminal, *Store, string) {
	t.Helper()
	dir := t.TempDir()
	return owners(), LoadStore(dir), dir
}

// **The invalidation is a FEATURE, and this is it stated as a test.**
//
// A different measured box width is a different question with a different right answer, so it gets its
// own key and a fresh calibration. The earlier design keyed on a column count derived from our own
// constants, so our edits moved the key while the truth stayed put and the owner's calibration was
// silently discarded. Measuring rather than computing is the fix; a coarser key would only have hidden
// it, applying a width computed for a box that no longer exists.
func TestADifferentBoxWidthCalibratesAfresh(t *testing.T) {
	term, store, dir := fresh(t)

	if _, calibrated, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply); err != nil || !calibrated {
		t.Fatalf("first call: calibrated=%v err=%v", calibrated, err)
	}
	// A margin change makes the box wider. Different question, so it must measure again.
	_, calibrated, err := To(context.Background(), term, store, dir, boxDp+16, 12, charMilli, 39, IntentApply)
	if err != nil {
		t.Fatal(err)
	}

	if !calibrated {
		t.Error("a changed box width reused a fit measured for a different box - silently wrong")
	}
	if len(store.Fits) != 2 {
		t.Errorf("%d fits stored; the old one should survive alongside the new", len(store.Fits))
	}
}

// **The same box width never recalibrates, whatever else moves.**
//
// Not a reconnect, not a new session, not a rotation, not a font change, not a bridge restart. There is
// no code path consulting any of them, so this asserts the absence of one - modelled as what all of
// those look like from here: the same box asked for again through a store reloaded from disk.
func TestTheSameBoxWidthNeverRecalibrates(t *testing.T) {
	term, store, dir := fresh(t)

	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply); err != nil {
		t.Fatal(err)
	}
	created := len(term.created)
	if created == 0 {
		t.Fatal("the first call made no calibration session, so this test cannot tell one from a cached apply")
	}

	for _, what := range []string{"reconnect", "new session", "rotation", "font change", "restart"} {
		// `applied` too, now that a cached apply verifies: this loop asserts on the RESIZES, and a list that
		// accumulates across iterations would report the first calibration's probes for ever.
		term.created, term.closed, term.resizes = nil, nil, 0
		term.applied = nil
		reloaded := LoadStore(dir)

		got, calibrated, err := To(context.Background(), term, reloaded, dir, boxDp, marginDp, charMilli, 37, IntentApply)
		if err != nil {
			t.Fatalf("%s: %v", what, err)
		}
		// **One resize, to the stored width.** A cached apply also VERIFIES, which
		// costs one session and one read - so the discriminator between an apply and a search is no
		// longer "did it make a session" but "did it search". A search applies its probe widths and
		// then a walk; an apply touches the window exactly once.
		if calibrated || len(term.applied) != 1 {
			t.Errorf("%s recalibrated: calibrated=%v resizes=%v", what, calibrated, term.applied)
		}
		if len(term.created) != 0 {
			t.Errorf("%s made %d sessions; nothing about the laptop moved, so nothing should be measured",
				what, len(term.created))
		}
		if got == 0 {
			t.Errorf("%s: reported no columns", what)
		}
	}
}

// The fit records the margin it was measured under, so a human reading the file can see WHY a key
// changed instead of inferring it from a number that moved.
func TestTheFitRecordsTheMarginItWasMeasuredUnder(t *testing.T) {
	term, store, dir := fresh(t)

	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply); err != nil {
		t.Fatal(err)
	}

	fit := store.Fits[fitKey(0, boxDp, charMilli)]
	if fit.MarginDp != marginDp || fit.BoxWidthDp != boxDp {
		t.Errorf("fit records box=%d margin=%d, measured under box=%d margin=%d",
			fit.BoxWidthDp, fit.MarginDp, boxDp, marginDp)
	}
	if fit.Points == 0 || fit.Columns == 0 {
		t.Errorf("fit is not a measurement: %+v", fit)
	}
}

// **The ordinary press is one resize and NOTHING else - no session, no probe, no jump.**
//
// This is the guarantee that verifying a cached fit nearly traded away and then bought back.
// Confirming a fit costs a
// calibration session, and `session.new` FOCUSES what it creates: measured on the owner's Mac, the
// selection moves to the probe and returns when it closes, so anything typed in that second lands in
// it. Paying that on every press was refused.
//
// So a press where the laptop looks the same as it did at calibration must create nothing at all.
// `term.created` being empty is that promise.
func TestAStoredFitIsAppliedVerbatim(t *testing.T) {
	term, store, dir := fresh(t)
	store.Fits[fitKey(0, boxDp, charMilli)] = Fit{
		Display: 0, BoxWidthDp: boxDp, MarginDp: marginDp, Points: 600, Columns: 37,
		SidebarWidthMilli: 309_480, SidebarVisible: true, FontSize: 13,
	}
	// The laptop looks exactly as it did when this was measured.
	term.laptop(309_480, 13)

	got, calibrated, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply)
	if err != nil {
		t.Fatal(err)
	}

	if calibrated {
		t.Error("reported as calibrated with a fit on disk to apply")
	}
	if len(term.created) != 0 {
		t.Errorf("made %v sessions applying an unchanged fit; the owner's cursor moves for each one", term.created)
	}
	if got != 37 || len(term.applied) != 1 || term.applied[0] != 600 {
		t.Errorf("got %d columns, applied %v; want one resize to the stored 600", got, term.applied)
	}
}

// **A fit the terminal contradicts is DELETED, and the press that found it corrects itself.**
//
// This is the 2026-08-06 failure as one test. The stored entry says 37 columns at 626 points; this fake really
// renders 39 there, which is the shape of what happened on the owner's machine on 2026-08-06 - a
// sidebar dragged narrower, 110 points handed to the terminal, and an entry promising 45 delivering
// 59 on every press for ever.
func TestAContradictedFitIsDeletedAndRecalibrated(t *testing.T) {
	term, store, dir := fresh(t)
	key := fitKey(0, boxDp, charMilli)
	// The font has moved since this was measured - 12 then, 13 now - so the detector escalates and
	// the measurement gets its chance to disagree. It used to be the sidebar that moved here; since
	// the sidebar became a knob it is APPLIED with the fit and cannot have drifted underneath it.
	store.Fits[key] = Fit{
		Display: 0, BoxWidthDp: boxDp, MarginDp: marginDp, Points: 626, Columns: 37,
		SidebarWidthMilli: 309_480, SidebarVisible: true, FontSize: 12,
	}
	term.laptop(309_480, 13)

	got, calibrated, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply)
	if err != nil {
		t.Fatal(err)
	}

	if !calibrated {
		t.Error("a contradicted fit was applied as if it still held")
	}
	if got != 37 {
		t.Errorf("recalibration produced %d columns, asked for 37", got)
	}
	if store.Fits[key].Points == 626 {
		t.Error("the contradicted width is still on record; the press cannot correct it")
	}
	// And the correction outlives the process, or the next start serves the same lie back.
	if LoadStore(dir).Fits[key].Points == 626 {
		t.Error("the contradicted width survived on disk")
	}
}

// **A verification that could not be MADE is not a contradiction.**
//
// The link dropped, agterm was busy, the shell never printed - none of them is evidence that the
// entry is wrong. Deleting a good fit because the laptop hiccupped would turn every stutter into a
// seven-second re-search of a window that was already the right width, and would eventually throw
// away a correct calibration while the owner watched.
func TestAFitSurvivesAVerificationThatCouldNotBeMade(t *testing.T) {
	term, store, dir := fresh(t)
	key := fitKey(0, boxDp, charMilli)
	store.Fits[key] = Fit{
		Display: 0, BoxWidthDp: boxDp, MarginDp: marginDp, Points: 600, Columns: 37,
		SidebarWidthMilli: 309_480, SidebarVisible: true, FontSize: 12,
	}
	// The detector escalates - something really did move - and then the measurement cannot be made.
	term.laptop(309_480, 13)
	term.failText = true

	got, calibrated, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply)
	if err != nil {
		t.Fatalf("a failed verification became a failed fit: %v", err)
	}

	if calibrated {
		t.Error("re-searched the owner's window because a read failed")
	}
	if got != 37 || len(term.applied) != 1 || term.applied[0] != 600 {
		t.Errorf("got %d columns, applied %v; the stored fit should have been applied unchanged", got, term.applied)
	}
	if store.Fits[key].Points != 600 {
		t.Error("a good fit was deleted on silence rather than on a contradiction")
	}
}

// **A laptop that moved but did not matter costs ONE session, not one per press.**
//
// The sidebar drifted and the column count survived it. The measurement says so, and the fit's copy
// of the laptop is refreshed from what was just read - otherwise the detector would disagree for
// ever and buy a focus-stealing probe on every press until the next calibration.
func TestAConfirmationThatAgreedStopsTheDetectorRepeating(t *testing.T) {
	term, store, dir := fresh(t)
	key := fitKey(0, boxDp, charMilli)
	store.Fits[key] = Fit{
		Display: 0, BoxWidthDp: boxDp, MarginDp: marginDp, Points: 600, Columns: 37,
		SidebarWidthMilli: 309_480, SidebarVisible: true, FontSize: 12,
	}
	term.laptop(309_480, 13)

	if _, calibrated, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply); err != nil || calibrated {
		t.Fatalf("first press: calibrated=%v err=%v; the measurement agreed, so nothing should be re-searched", calibrated, err)
	}
	if len(term.created) != 1 {
		t.Errorf("first press made %v sessions; the detector disagreed, so it should have measured exactly once", term.created)
	}
	if store.Fits[key].FontSize != 13 {
		t.Errorf("the fit still records font %d; a confirmation that agreed must adopt what it saw",
			store.Fits[key].FontSize)
	}

	term.created = nil
	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply); err != nil {
		t.Fatal(err)
	}
	if len(term.created) != 0 {
		t.Errorf("second press made %v sessions; the detector should have gone quiet", term.created)
	}
}

// **A file agterm no longer writes the way we read it is SILENCE, not a suspicion.**
//
// This is the failure mode of the whole coupling: an undocumented private format changes, the reader
// stops recognising it, and detection goes quiet. Quiet must mean the press behaves exactly as it did
// before any of this - one resize, no session - and the stale fit waits for the long press. A
// detector that escalated when it could not see would cost the owner their cursor on every press the
// first time agterm renamed a field.
func TestAnUnreadableLaptopStateEscalatesNothing(t *testing.T) {
	term, store, dir := fresh(t)
	key := fitKey(0, boxDp, charMilli)
	store.Fits[key] = Fit{
		Display: 0, BoxWidthDp: boxDp, MarginDp: marginDp, Points: 600, Columns: 37,
		SidebarWidthMilli: 309_480, SidebarVisible: true, FontSize: 12,
	}
	term.failLaptop = true

	got, calibrated, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply)
	if err != nil {
		t.Fatal(err)
	}

	if calibrated || len(term.created) != 0 {
		t.Errorf("a tree it could not read made it measure: calibrated=%v sessions=%v", calibrated, term.created)
	}
	if got != 37 || store.Fits[key].Points != 600 {
		t.Errorf("got %d columns and the stored fit is %+v; both should be untouched", got, store.Fits[key])
	}
}

// An agterm older than 0.26 reports no sidebar and no font: the laptop is not KNOWN, and not knowing
// is silence, exactly as a missing state file was before the sidebar came over the socket.
func TestAMissingLaptopStateEscalatesNothing(t *testing.T) {
	term, store, dir := fresh(t)
	key := fitKey(0, boxDp, charMilli)
	store.Fits[key] = Fit{
		Display: 0, BoxWidthDp: boxDp, MarginDp: marginDp, Points: 600, Columns: 37,
		SidebarWidthMilli: 309_480, SidebarVisible: true, FontSize: 12,
	}
	term.laptop(0, 0)

	if _, calibrated, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply); err != nil || calibrated {
		t.Fatalf("calibrated=%v err=%v; a tree that says nothing is not evidence of anything", calibrated, err)
	}
	if len(term.created) != 0 {
		t.Errorf("made %v sessions on the strength of a tree that says nothing", term.created)
	}
}

func TestAFitWithNoRecordedLaptopStateEscalatesNothing(t *testing.T) {
	term, store, dir := fresh(t)
	store.Fits[fitKey(0, boxDp, charMilli)] = Fit{
		Display: 0, BoxWidthDp: boxDp, MarginDp: marginDp, Points: 600, Columns: 37,
	}
	term.laptop(309_480, 13)

	if _, calibrated, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply); err != nil || calibrated {
		t.Fatalf("calibrated=%v err=%v", calibrated, err)
	}
	if len(term.created) != 0 {
		t.Errorf("made %v sessions comparing against a record that does not exist", term.created)
	}
}

// The reader itself: what it takes, and every way it declines to guess.
func TestChromeMovedExplainsOnlyWhatItCanMeasure(t *testing.T) {
	withRecord := Fit{Points: 802, Columns: 45, ChromePoints: 439, CellMilliPoints: 8050}
	got := chromeMoved(withRecord, 59)
	if !strings.Contains(got, "439") || !strings.Contains(got, "327") {
		t.Errorf("chromeMoved(%+v, 59) = %q; want the chrome then and the chrome now", withRecord, got)
	}
	// A fit stored before those fields existed says nothing rather than reasoning from a zero.
	if old := chromeMoved(Fit{Points: 802, Columns: 45}, 59); old != "" {
		t.Errorf("invented a diagnosis for a fit that carries no record: %q", old)
	}
}

// **THE HEIGHT IS THE OWNER'S, AND THE RESTORE MUST NOT PUT BACK A REMEMBERED ONE.**
//
// Their report, 2026-08-09: *"фит-режим телефона изменил высоту экрана… половина экрана по высоте
// простаивает"*. Every resize on the search path already sends the height read immediately before it
// — `TestAResizeNeverChangesTheHeight` holds that. The restore did not: it wrote back `r.Height`, the
// height captured when the fit was switched ON, which is the one place in this package that CHOOSES
// a height instead of passing one through.
//
// So anything that moved the window between on and off — dragging its edge, or carrying it to a
// display of a different size, which is what they had done — was undone by a press that was only ever
// asked to give the width back.
//
// The fake moves the height underneath exactly as the machine did.
func TestTheRestorePutsBackTheWidthAndLeavesTheHeightAlone(t *testing.T) {
	term, store, dir := fresh(t)
	full := term.window.Width

	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply); err != nil {
		t.Fatal(err)
	}

	// The owner makes the window taller while the fit is on - or moves it to a bigger screen, which
	// looks the same from here.
	term.window.Height = 1400

	if err := RestoreWindow(context.Background(), term, store); err != nil {
		t.Fatal(err)
	}

	if term.window.Width != full {
		t.Errorf("width came back as %d, want %d; the restore's actual job is the width", term.window.Width, full)
	}
	if term.window.Height != 1400 {
		t.Errorf("height came back as %d, want 1400 - a height captured at fit-on was replayed over "+
			"the one the owner had", term.window.Height)
	}
}

// **A window is chosen by geometry we can verify, never by the fullscreen flag.**
//
// That flag has been caught describing something other than native fullscreen: the owner's window
// read `fullscreen: true` at 802 points on a 1496-point display. A selection rule that trusted it
// would take a window whose width says nothing - which is exactly the window a resize should not act
// on.
func TestTheWindowIsChosenByItsGeometryNotByAClaim(t *testing.T) {
	claimsFullscreenNoSize := Window{ID: "liar", Fullscreen: true, Width: 0, Height: 0}
	real := Window{ID: "real", Width: 1200, Height: 800}

	got, err := active([]Window{claimsFullscreenNoSize, real})
	if err != nil {
		t.Fatal(err)
	}
	if got.ID != "real" {
		t.Errorf("chose %q; a fullscreen claim with no size is not a window to resize", got.ID)
	}

	// And nothing usable at all is an error rather than a guess.
	if _, err := active([]Window{claimsFullscreenNoSize}); err == nil {
		t.Error("a window with no usable size was accepted on the strength of its flag")
	}
}

// **Zoom is derived from width, so the restore must NOT touch it.**
//
// Measured by hand on the owner's machine 2026-07-30, no zoom command issued at any point: 1728 points
// reads zoomed=true, 900 reads false, and setting 1728 back reads true again. An explicit toggle would
// therefore fire exactly when the width restore had already put the flag right, and set it wrong.
//
// The fake mirrors that: its zoom follows the width it is set to.
func TestTheRestoreLeavesZoomToFollowTheWidth(t *testing.T) {
	term, store, dir := fresh(t)
	term.window.Zoomed = true
	full := term.window.Width

	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply); err != nil {
		t.Fatal(err)
	}
	if err := RestoreWindow(context.Background(), term, store); err != nil {
		t.Fatal(err)
	}

	if term.window.Width != full {
		t.Errorf("width came back as %d, want %d", term.window.Width, full)
	}
	if term.zoomToggles != 0 {
		t.Errorf("zoom was toggled %d times; it follows the width and toggling it undoes the restore",
			term.zoomToggles)
	}
}

// **The owner's impossible fit, as the case that must be REFUSED.**
//
// Their file held {box_width_dp: 448, points: 769, columns: 42} after a search gave up and stored its
// last measurement. Forty-two columns at 11.0dp is 462dp, in a box that holds 440 - so every line
// scrolled, and because nothing invalidates this cache automatically, every press afterwards applied
// it faithfully. The 448 is not a valid box width to test with; it is the bug.
func TestASearchThatEndedAboveItsTargetIsRefusedAndNothingIsStored(t *testing.T) {
	err := refuseAnImpossibleFit(42, 40, 440, 11000, false)

	if err == nil {
		t.Fatal("42 columns was accepted against a target of 40; it would scroll every line for ever")
	}
	// The refusal states the arithmetic, so it reads as a bug report rather than a mystery.
	for _, want := range []string{"42", "40", "11.0dp", "462dp", "440dp"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("the refusal does not mention %q: %s", want, err)
		}
	}
}

// The control: landing ON the target, or SHORT of it, is fine. Short costs a sliver nobody notices.
func TestLandingOnOrUnderTheTargetIsAccepted(t *testing.T) {
	for _, measured := range []int{40, 39, 20} {
		if err := refuseAnImpossibleFit(measured, 40, 440, 11000, false); err != nil {
			t.Errorf("%d columns against a target of 40 was refused: %v", measured, err)
		}
	}
}

// **The setting is the fit, so its column count cannot come from another box.**
//
// The owner was stuck with enabled=true and columns=162 while the only stored fit was for
// box_width_dp 1728 - the LAPTOP window width, measured by a test for a different box. No fit for
// their phone existed. Every press applied 162 columns to a window already 1728 wide.
//
// Two fields that can be assigned separately can disagree. One cannot.
func TestTheSettingsColumnsAreAlwaysTheAppliedFitsColumns(t *testing.T) {
	term, store, dir := fresh(t)
	// A fit for someone else entirely, sitting in the file the way the owner's was.
	store.Fits[fitKey(0, 1728, charMilli)] = Fit{Display: 0, BoxWidthDp: 1728, MarginDp: 4, Points: 1728, Columns: 162}

	got, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply)
	if err != nil {
		t.Fatal(err)
	}

	if store.Active == nil {
		t.Fatal("nothing was made active")
	}
	if store.Active.Columns != got || store.Active.BoxWidthDp != boxDp {
		t.Errorf("the setting is %+v after serving a request for box %d that got %d columns",
			store.Active, boxDp, got)
	}
	if store.Active.Columns == 162 {
		t.Error("the setting took its column count from another box's fit")
	}
}

// TestARefusalPutsTheWindowBack holds the first half of the 2026-07-30 ruling: a calibration that
// gives up owes the owner the state they had.
//
// **Both of the owner's complaints that evening were this one defect.** The search asked for 41
// columns, landed on 42, and the refusal fired correctly — 42 columns at 10.7dp is 448dp in a 440dp
// box. But refusing to STORE a fit left the window at the width the search last tried, so they got the
// extra column and the horizontal scroll; and because nothing was stored the toggle read off, so the
// only way back was a button we had told them was not pressed.
//
// The window here can never reach 41 columns, so the walk exhausts and the refusal is genuinely right.
// What is asserted is what happens afterwards.
func TestARefusalPutsTheWindowBack(t *testing.T) {
	term, store, dir := fresh(t)
	// Never fewer than 42 columns however narrow the window goes: a target truly out of reach.
	term.floorColumns = 42
	before := term.window

	_, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 41, IntentApply)
	if err == nil {
		t.Fatal("expected a refusal: 42 columns cannot fit a box sized for 41")
	}

	// The geometry they started with, not the width the search happened to stop at.
	if term.window.Width != before.Width || term.window.Height != before.Height {
		t.Errorf("window left at %dx%d, expected it put back to %dx%d",
			term.window.Width, term.window.Height, before.Width, before.Height)
	}
	// Truthful together: no setting claiming a fit is in force, and no stale restore point.
	if store.Active != nil {
		t.Errorf("a refusal left the setting on: %+v", store.Active)
	}
	if store.Pending != nil {
		t.Error("the restore point survived the restore that consumed it")
	}
	if len(store.Fits) != 0 {
		t.Errorf("a refusal stored a fit: %+v", store.Fits)
	}
}

// TestTheWalkTakesTheFirstCountAtOrUnderWhatWasAsked holds the second half: overshooting by one column
// is a window a few points too wide, not an impossible fit.
//
// The terminal here can only render EVEN column counts, which is what makes 41 unreachable and 42 the
// natural landing place — the same shape as the owner's real machine, where the search ran out of
// steps one column above the target. The walk must step down and settle at 40 rather than refuse.
func TestTheWalkTakesTheFirstCountAtOrUnderWhatWasAsked(t *testing.T) {
	term, store, dir := fresh(t)
	term.columnStep = 2

	got, calibrated, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 41, IntentApply)
	if err != nil {
		t.Fatalf("the walk refused a fit that was one column away: %v", err)
	}
	if !calibrated {
		t.Error("expected a calibration, not a cached fit")
	}
	if got > 41 {
		t.Errorf("settled on %d columns having asked for 41; the walk must end at or under the target", got)
	}
	// And the fit that was stored is the one that was measured, not the one that was asked for.
	fit, ok := store.Fits[fitKey(term.window.Display, boxDp, charMilli)]
	if !ok {
		t.Fatal("no fit stored under the box width that was calibrated")
	}
	if fit.Columns != got {
		t.Errorf("stored %d columns but reported %d", fit.Columns, got)
	}
	if store.Active == nil || store.Active.Columns != got {
		t.Errorf("the setting does not carry the columns of the fit in force: %+v", store.Active)
	}
}

// **A RESTORE POINT IS ONLY EVER WRITTEN WHEN THERE ISN'T ONE.**
//
// The invariant the whole feature's safety rests on. An on-press that found a record already present
// and overwrote it would record a window WE narrowed as where the owner's window came from, and no
// press afterwards could ever put it back - the geometry in that record is the one value here that
// cannot be recomputed from anything else.
//
// The property held before this test existed, by the shape of an `if`. It is written down now because
// a property nobody can point at is one refactor from being silently untrue.
func TestARestorePointIsOnlyWrittenWhenThereIsNone(t *testing.T) {
	term, store, dir := fresh(t)

	// The owner's real window, recorded by the first press.
	_, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 40, IntentApply)
	if err != nil {
		t.Fatal(err)
	}
	original := *store.Pending
	if original.Width != 1728 {
		t.Fatalf("the restore point is %dpt, expected the window's own 1728", original.Width)
	}

	// More presses, against a window that is now NARROW because the first press narrowed it.
	for i := 0; i < 3; i++ {
		if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 40, IntentApply); err != nil {
			t.Fatal(err)
		}
	}

	if *store.Pending != original {
		t.Errorf("the restore point was overwritten: %+v, was %+v - the owner's original window is gone",
			*store.Pending, original)
	}
}

// **A restore that FAILED did not move the window, so the record is the only thing that can still put
// it back.** Keeping it is what stops a failed startup restore - the bridge starting before agterm is
// up, which happens at login - from destroying the owner's original geometry permanently.
//
// The opposite branch, one line away, is about a record that is OUT OF DATE, and its correct answer is
// the reverse. See RestoreWindow.
func TestAFailedRestoreKeepsTheRestorePoint(t *testing.T) {
	term, store, dir := fresh(t)

	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 40, IntentApply); err != nil {
		t.Fatal(err)
	}
	original := *store.Pending

	// agterm goes away - the window is untouched by the attempt that follows.
	term.failResizeAfter = 1

	if err := RestoreWindow(context.Background(), term, store); err == nil {
		t.Fatal("the restore was supposed to fail; this test is not exercising what it claims")
	}
	if store.Pending == nil {
		t.Fatal("a failed restore discarded the only record of the owner's window geometry")
	}
	if *store.Pending != original {
		t.Errorf("the record changed: %+v, was %+v", *store.Pending, original)
	}
}

// And a restore that SUCCEEDED spends the record: keeping it would put the window somewhere older
// still on the next attempt.
func TestASuccessfulRestoreSpendsTheRestorePoint(t *testing.T) {
	term, store, dir := fresh(t)

	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 40, IntentApply); err != nil {
		t.Fatal(err)
	}
	if err := RestoreWindow(context.Background(), term, store); err != nil {
		t.Fatal(err)
	}
	if store.Pending != nil {
		t.Errorf("the record survived a restore that worked: %+v", *store.Pending)
	}
	if term.window.Width != 1728 {
		t.Errorf("the window is %dpt, expected it back at 1728", term.window.Width)
	}
}

// **A font change is a different question, so it gets a different key.**
//
// The owner asked for the terminal font one step smaller and hoped the fit would not break. It would
// have: the box width does not change when the font does, so the stored 41-column answer would have
// been applied verbatim to a phone that now fits more, and they would have got a window too narrow
// with no way to tell why - a cached answer to a question nobody re-asked.
func TestADifferentCharacterWidthIsADifferentKey(t *testing.T) {
	term, store, dir := fresh(t)

	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply); err != nil {
		t.Fatal(err)
	}
	first := len(store.Fits)

	// The same phone, the same box, a smaller font: one character is narrower now.
	smaller := charMilli - 600
	_, calibrated, err := To(context.Background(), term, store, dir, boxDp, marginDp, smaller, 39, IntentApply)
	if err != nil {
		t.Fatal(err)
	}

	if !calibrated {
		t.Error("the smaller font reused a fit measured with the larger one")
	}
	if len(store.Fits) != first+1 {
		t.Errorf("fits held %d entries, want %d - the new measurement replaced the old one", len(store.Fits), first+1)
	}
	// **Unreachable, not deleted.** The old entry survives; nothing can reach it with today's font,
	// and deleting is the operation that once destroyed the only record of their window geometry.
	if _, ok := store.Fits[fitKey(0, boxDp, charMilli)]; !ok {
		t.Error("the entry measured with the old font was deleted rather than left unreachable")
	}
}

// **THE HEIGHT SENT IS THE HEIGHT READ, ON EVERY RESIZE.**
//
// agterm will not accept a resize without a positive height, so "we do not change their height" cannot
// be kept by sending none - that was tried on 2026-07-31 and broke the feature outright. It is kept by
// reading the window's height immediately before each call and handing it straight back.
//
// The failure this is aimed at is a height CAPTURED at the start of an operation and replayed into
// every probe. A calibration is a dozen resizes over several seconds; a captured height pins the
// window to whatever it was when we looked, and it is invisible because the number looks like a no-op.
//
// The fake moves the height underneath the calibration, exactly as a window leaving fullscreen would.
// A captured value would then be replayed and would drag the height back; a freshly read one follows.
func TestAResizeNeverChangesTheHeight(t *testing.T) {
	term, store, dir := fresh(t)

	// The window's height changes after the first probe, from something other than us.
	term.heightChangesAfter = 1
	term.heightChangesTo = 640

	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply); err != nil {
		t.Fatal(err)
	}

	if len(term.heights) == 0 {
		t.Fatal("no resize happened; this test is not exercising what it claims")
	}
	// Every height handed to a resize was the height the window had at that moment. The fake records
	// what it was told and what it held, so a replayed capture shows up as a height that disagrees
	// with the window it was sent to.
	for i, sent := range term.heights {
		want := term.heightAtCall[i]
		if sent != want {
			t.Errorf("resize %d sent height %d while the window was %d - a captured height was replayed",
				i, sent, want)
		}
	}
}

// **And the recalibrate flag DOES force one — the escape hatch, measured rather than assumed.**
//
// The counterpart of [TestTheSameBoxWidthNeverRecalibrates], and the reason it is worth its own test:
// that one asserts the cache holds, which is also what a `recalibrate` flag wired to nothing would
// look like. A hatch that quietly does nothing is the shape this whole feature was reported for —
// the owner reached for it on 2026-08-25 when his fit was stuck and could not tell it had fired.
//
// The discriminator is the same one that test established: an apply touches the window once, a search
// applies probe widths and then walks. So a forced recalibration must make a session and resize more
// than once, from a store that already holds the answer.
func TestTheRecalibrateFlagForcesAFreshSearch(t *testing.T) {
	term, store, dir := fresh(t)

	if _, _, err := To(context.Background(), term, store, dir, boxDp, marginDp, charMilli, 37, IntentApply); err != nil {
		t.Fatal(err)
	}
	if len(store.Fits) == 0 {
		t.Fatal("nothing was cached, so there is no cache for the flag to override")
	}

	term.created, term.closed, term.resizes = nil, nil, 0
	term.applied = nil
	reloaded := LoadStore(dir)

	got, calibrated, err := To(context.Background(), term, reloaded, dir, boxDp, marginDp, charMilli, 37, IntentRecalibrate)
	if err != nil {
		t.Fatalf("a forced recalibration failed: %v", err)
	}

	if !calibrated {
		t.Error("the flag was set and nothing was recalibrated - the hatch is wired to nothing")
	}
	if len(term.created) == 0 {
		t.Error("no calibration session was made; a search cannot happen without one")
	}
	if len(term.applied) <= 1 {
		t.Errorf("the window was touched %d times; a search probes and then walks, an apply touches once",
			len(term.applied))
	}
	if got == 0 {
		t.Error("a forced recalibration reported no columns, so the phone has nothing to show")
	}
}
