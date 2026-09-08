package resize

import (
	"context"
	"errors"
	"testing"
)

// Apply what is known, and measure nothing.
//
// # Why the automatic path may not calibrate
//
// A calibration is a **visible hunt across the owner's window**: a dozen resizes over several seconds,
// while he may be looking at something else entirely. Having one begin because he tapped a row in a
// list on his phone is a surprise arriving from a machine he is not watching.
//
// So the re-apply asks for what is recorded and takes no for an answer. One press is a smaller cost
// than a window that moves on its own.

func store(t *testing.T) *Store { return LoadStore(t.TempDir()) }

// **THE TEST THE FEATURE IS FOR.** An unknown geometry answers, and does not hunt.
func TestApplyIfKnownDoesNotCalibrate(t *testing.T) {
	term := owners()

	_, _, err := To(context.Background(), term, store(t), t.TempDir(), 440, 12, 9800, 37,
		IntentApplyIfKnown)

	if !errors.Is(err, ErrNotCalibrated) {
		t.Fatalf("got %v, want ErrNotCalibrated", err)
	}
	if len(term.created) != 0 {
		t.Fatalf("it created %d calibration sessions - the owner's view jumps for each one", len(term.created))
	}
	if len(term.applied) != 0 {
		t.Fatalf("it resized the window %d times for a fit it declined to apply", len(term.applied))
	}
}

// **And it leaves the laptop exactly as it found it.** The restore point is a file, and writing one for
// a fit that was never applied would leave a record of a geometry nobody departed from.
func TestADeclinedApplyWritesNoRestorePoint(t *testing.T) {
	term := owners()
	s := store(t)

	_, _, _ = To(context.Background(), term, s, t.TempDir(), 440, 12, 9800, 37,
		IntentApplyIfKnown)

	if s.Pending != nil {
		t.Fatal("a declined apply wrote a restore point; nothing had moved for it to restore")
	}
	if s.Active != nil {
		t.Fatal("a declined apply recorded a fit as being in force")
	}
}

// A geometry that IS known applies, silently and without measuring. He answered that question already
// and asking again would be asking twice.
func TestApplyIfKnownAppliesWhatIsRecorded(t *testing.T) {
	term := owners()
	s := store(t)
	dir := t.TempDir()

	if _, _, err := To(context.Background(), term, s, dir, 440, 12, 9800, 37,
		IntentApply); err != nil {
		t.Fatal(err)
	}
	sessionsAfterFirst := len(term.created)
	resizesAfterFirst := len(term.applied)

	got, calibrated, err := To(context.Background(), term, s, dir, 440, 12, 9800, 37,
		IntentApplyIfKnown)

	if err != nil {
		t.Fatalf("a recorded geometry was declined: %v", err)
	}
	if calibrated {
		t.Fatal("it measured again for a geometry it already had")
	}
	if got == 0 {
		t.Fatal("it applied nothing")
	}
	if len(term.created) != sessionsAfterFirst {
		t.Fatal("it opened a calibration session for a cached apply")
	}
	if len(term.applied) <= resizesAfterFirst {
		t.Fatal("it did not resize the window, so nothing was applied")
	}
}

// **The owner's own press is unchanged**, which is the half a refactor of the intent could break while
// every test above stayed green.
func TestThePressStillCalibratesWhenNothingIsKnown(t *testing.T) {
	term := owners()

	got, calibrated, err := To(context.Background(), term, store(t), t.TempDir(), 440, 12, 9800, 37,
		IntentApply)

	if err != nil {
		t.Fatal(err)
	}
	if !calibrated || got == 0 {
		t.Fatalf("a press on an unknown geometry did not measure: columns=%d calibrated=%v", got, calibrated)
	}
}
