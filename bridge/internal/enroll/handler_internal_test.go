package enroll

import (
	"io"
	"log"
	"strings"
	"testing"
	"time"
)

// The aggregate is bounded, and it is still a diagnostic: the count DOES reach the owner's log once
// the interval has rolled.
//
// Internal to the package, because what it has to reach is the counter's own interval. The
// alternative - waiting a minute - is a test that gets deleted.
//
// This is the other half of TestRefusalsThatCostNothingAreCountedNotLogged. That one proves the
// ceiling does not move with the number of callers; this one proves the ceiling is not simply
// silence, which would be a different way of getting the same test green and would leave the owner
// with no signal that somebody is knocking at all.
func TestTheAggregateReachesTheOwnerOnceTheIntervalRolls(t *testing.T) {
	var written strings.Builder
	log.SetOutput(&written)
	log.SetFlags(0)
	t.Cleanup(func() { log.SetOutput(io.Discard); log.SetFlags(log.LstdFlags) })

	r := &refusalRate{}
	for i := 0; i < 200; i++ {
		r.record()
	}
	if got := written.String(); got != "" {
		t.Fatalf("200 refusals inside one interval wrote %q", got)
	}

	// The interval passes, and the next knock carries the total.
	r.mu.Lock()
	r.windowEnd = time.Now().Add(-time.Second)
	r.mu.Unlock()
	r.record()

	got := written.String()
	if !strings.Contains(got, "201") {
		t.Fatalf("the count must reach the owner, got %q", got)
	}
	if strings.Count(got, "\n") != 1 {
		t.Fatalf("the whole interval is one line, got %q", got)
	}

	// And the counter starts again, so the next interval reports its own traffic rather than the sum
	// of everything since the bridge started.
	r.mu.Lock()
	count := r.count
	r.mu.Unlock()
	if count != 0 {
		t.Fatalf("the count was not reset, it is %d", count)
	}
}

// What the aggregate line may say: a number, and nothing about who or which cause.
//
// Two shut-gate causes reach this counter - no window, and an expired window - and a caller must not
// learn which. The owner does not need it either: the action is the same, which is to open a window.
func TestTheAggregateLineNamesNobody(t *testing.T) {
	var written strings.Builder
	log.SetOutput(&written)
	log.SetFlags(0)
	t.Cleanup(func() { log.SetOutput(io.Discard); log.SetFlags(log.LstdFlags) })

	r := &refusalRate{}
	r.record()
	r.mu.Lock()
	r.windowEnd = time.Now().Add(-time.Second)
	r.mu.Unlock()
	r.record()

	got := written.String()
	for _, forbidden := range []string{"127.0.0.1", "expired", "token"} {
		if strings.Contains(got, forbidden) {
			t.Fatalf("the aggregate line carries %q: %q", forbidden, got)
		}
	}
}
