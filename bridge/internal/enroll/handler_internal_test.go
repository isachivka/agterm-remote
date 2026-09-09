package enroll

import (
	"io"
	"log"
	"strings"
	"testing"
	"time"
)

// atClock is a clock a test moves by hand. The whole point of injecting it is that the SPAN this
// counter reports is a number to be asserted rather than approximated by sleeping.
type atClock struct{ at time.Time }

func (c *atClock) now() time.Time { return c.at }

func (c *atClock) advance(d time.Duration) { c.at = c.at.Add(d) }

func capture(t *testing.T) *strings.Builder {
	t.Helper()
	var written strings.Builder
	log.SetOutput(&written)
	log.SetFlags(0)
	t.Cleanup(func() { log.SetOutput(io.Discard); log.SetFlags(log.LstdFlags) })
	return &written
}

// The aggregate is bounded, and it is still a diagnostic: the count DOES reach the owner's log once
// the interval has rolled.
//
// This is the other half of TestRefusalsThatCostNothingAreCountedNotLogged. That one proves the
// ceiling does not move with the number of callers; this one proves the ceiling is not simply
// silence, which would be a different way of getting the same test green and would leave the owner
// with no signal that anybody is knocking at all.
func TestTheAggregateReachesTheOwnerOnceTheIntervalRolls(t *testing.T) {
	written := capture(t)
	clock := &atClock{at: time.Unix(1_000_000, 0)}
	r := &refusalRate{now: clock.now}

	for i := 0; i < 200; i++ {
		r.record()
	}
	if got := written.String(); got != "" {
		t.Fatalf("200 refusals inside one interval wrote %q", got)
	}

	clock.advance(refusalReportEvery + time.Second)
	r.record()

	got := written.String()
	if !strings.Contains(got, "201") {
		t.Fatalf("the count must reach the owner, got %q", got)
	}
	if !strings.Contains(got, "1m1s") {
		t.Fatalf("the span must be the elapsed time, got %q", got)
	}
	if strings.Count(got, "\n") != 1 {
		t.Fatalf("the whole interval is one line, got %q", got)
	}

	r.mu.Lock()
	count := r.count
	r.mu.Unlock()
	if count != 0 {
		t.Fatalf("the count was not reset, it is %d", count)
	}
}

// **The span reported is the ELAPSED time, not the interval.**
//
// The aggregate is written by the next refusal after the interval has passed, and that refusal can
// arrive whenever it likes - an hour later, if nobody knocked in between. The first version printed
// the interval regardless, so 201 refusals spread over an hour were reported as "in the last 1m0s". A
// count over a stated span that is not the span it covers is worse than no span at all.
func TestTheAggregateReportsTheSpanItActuallyCovers(t *testing.T) {
	written := capture(t)
	clock := &atClock{at: time.Unix(1_000_000, 0)}
	r := &refusalRate{now: clock.now}

	// 200 knocks inside the first interval, then nothing for an hour, then one more. The report rides
	// on that last one, and what it covers is the hour.
	for i := 0; i < 200; i++ {
		r.record()
	}
	clock.advance(time.Hour)
	r.record()

	got := written.String()
	if strings.Count(got, "\n") != 1 {
		t.Fatalf("one report, got %q", got)
	}
	if !strings.Contains(got, "201") {
		t.Fatalf("the count must be the whole burst, got %q", got)
	}
	if !strings.Contains(got, "1h0m0s") {
		t.Fatalf("the span must be the one the count covers, got %q", got)
	}
	if strings.Contains(got, refusalReportEvery.String()) {
		t.Fatalf("the interval was printed instead of the elapsed span: %q", got)
	}
}

// **A burst that STOPS must still be reported**, and nothing else in this file makes that happen: the
// aggregate rides on the next refusal, so five thousand held refusals followed by silence produce no
// line, and a bridge that exits takes the count with it.
//
// internal/listener's failureCounter - which this counter cites as its model - has exactly this hook,
// `defer s.failures.flush()`. This is the same one, reached from Handler.Flush and deferred in main.
func TestFlushReportsABurstThatStopped(t *testing.T) {
	written := capture(t)
	clock := &atClock{at: time.Unix(1_000_000, 0)}
	r := &refusalRate{now: clock.now}

	for i := 0; i < 5000; i++ {
		r.record()
	}
	clock.advance(37 * time.Second) // and then nothing at all, inside the interval
	if got := written.String(); got != "" {
		t.Fatalf("the interval has not passed, so nothing should be written yet: %q", got)
	}

	r.flush()

	got := written.String()
	if !strings.Contains(got, "5000") {
		t.Fatalf("a burst that stopped was never reported: %q", got)
	}
	if !strings.Contains(got, "37s") {
		t.Fatalf("the flush must report the span it covers, got %q", got)
	}
	if strings.Count(got, "\n") != 1 {
		t.Fatalf("one line, got %q", got)
	}

	// Idempotent, so a bridge that flushes on the way out and is then flushed again by a caller does
	// not report the same traffic twice or an empty count.
	r.flush()
	if strings.Count(written.String(), "\n") != 1 {
		t.Fatalf("a second flush wrote something: %q", written.String())
	}
}

// What the aggregate line may say: a number and a span, and nothing about who called or which cause
// it was.
//
// Two shut-gate causes reach this counter - no window, and an expired window - and a caller must not
// learn which. The owner does not need it either: the action is the same, which is to open a window.
func TestTheAggregateLineNamesNobody(t *testing.T) {
	written := capture(t)
	clock := &atClock{at: time.Unix(1_000_000, 0)}
	r := &refusalRate{now: clock.now}

	r.record()
	clock.advance(refusalReportEvery + time.Second)
	r.record()

	got := written.String()
	for _, forbidden := range []string{"127.0.0.1", "expired", "token", "certificate"} {
		if strings.Contains(got, forbidden) {
			t.Fatalf("the aggregate line carries %q: %q", forbidden, got)
		}
	}
}

// The handler takes its clock from the window rather than from a fifth argument, so the two halves of
// this package cannot be handed two different opinions about what time it is.
func TestTheHandlerSharesTheWindowsClock(t *testing.T) {
	clock := &atClock{at: time.Unix(1_000_000, 0)}
	window := NewWindow(clock.now)
	h := NewHandler(window, nil, nil, nil)

	if got := h.refusals.now(); !got.Equal(clock.at) {
		t.Fatalf("the rate limiter reads %s, the window reads %s", got, clock.at)
	}
	clock.advance(time.Hour)
	if got := h.refusals.now(); !got.Equal(clock.at) {
		t.Fatal("the rate limiter kept its own copy of the clock rather than the window's")
	}
}
