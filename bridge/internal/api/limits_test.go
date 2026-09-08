package api

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"testing"
	"time"

	"dev.isachivka.bewareofsugar/bridge/internal/agterm"
	"dev.isachivka.bewareofsugar/bridge/internal/agterm/agtermtest"
	"dev.isachivka.bewareofsugar/bridge/internal/limits"
)

// A handler with a counting reader and a clock the test moves. The agterm socket is absent on
// purpose: this verb must not need one.
func limitsHandler(t *testing.T) (*Handler, *int, *time.Time) {
	t.Helper()
	h := New(agterm.New(t.TempDir()+"/absent.sock"), t.TempDir())
	now := time.Date(2026, 9, 6, 10, 0, 0, 0, time.UTC)
	h.limitsNow = func() time.Time { return now }
	calls := 0
	h.UseLimits(func(context.Context) (limits.Report, limits.Report) {
		calls++
		return limits.Report{Provider: limits.Claude, Windows: []limits.Window{{Kind: "5h", RemainingPct: 89}}},
			limits.Report{Provider: limits.Codex, Error: limits.ErrExpired}
	})
	return h, &calls, &now
}

func TestLimitsAnswersBothProvidersAndTouchesNoAgterm(t *testing.T) {
	h, fake := handler(t, func(agtermtest.Request) any { return agtermtest.OK(tree()) })
	h.UseLimits(func(context.Context) (limits.Report, limits.Report) {
		return limits.Report{Windows: []limits.Window{{Kind: "7d", RemainingPct: 0}}}, limits.Report{Error: limits.ErrNoCredential}
	})

	resp := h.Handle(context.Background(), Request{Verb: VerbLimits})

	if !resp.OK || resp.Limits == nil {
		t.Fatalf("refused: %s", resp.Error)
	}
	if len(fake.Requests()) != 0 {
		t.Fatal("the limits verb reached agterm")
	}
	raw, _ := json.Marshal(resp)
	for _, want := range []string{
		`"claude":{"windows":[{"kind":"7d","remaining_pct":0}]}`,
		`"codex":{"error":"no_credential"}`,
		`"fetched_at":"`,
	} {
		if !strings.Contains(string(raw), want) {
			t.Errorf("wire %s lacks %s", raw, want)
		}
	}
}

func TestLimitsAreServedFromTheCacheForThirtyMinutes(t *testing.T) {
	h, calls, now := limitsHandler(t)

	h.Handle(context.Background(), Request{Verb: VerbLimits})
	*now = now.Add(29 * time.Minute)
	second := h.Handle(context.Background(), Request{Verb: VerbLimits})
	if *calls != 1 {
		t.Fatalf("fetched %d times inside the window", *calls)
	}
	if second.Limits.FetchedAt != "2026-09-06T10:00:00Z" {
		t.Errorf("fetched_at = %s, want the cache's own time", second.Limits.FetchedAt)
	}

	*now = now.Add(2 * time.Minute)
	h.Handle(context.Background(), Request{Verb: VerbLimits})
	if *calls != 2 {
		t.Fatalf("an expired cache was served: %d fetches", *calls)
	}
}

func TestFreshBypassesTheCache(t *testing.T) {
	h, calls, now := limitsHandler(t)

	h.Handle(context.Background(), Request{Verb: VerbLimits})
	*now = now.Add(time.Minute)
	h.Handle(context.Background(), Request{Verb: VerbLimits, Fresh: true})
	if *calls != 2 {
		t.Fatalf("fresh did not fetch: %d", *calls)
	}
}

func TestTwoPressesInTheSameBreathShareOneFetch(t *testing.T) {
	h, calls, now := limitsHandler(t)

	h.Handle(context.Background(), Request{Verb: VerbLimits, Fresh: true})
	*now = now.Add(3 * time.Second)
	h.Handle(context.Background(), Request{Verb: VerbLimits, Fresh: true})
	if *calls != 1 {
		t.Fatalf("a fresh request seconds after a fetch fetched again: %d", *calls)
	}
}

func TestAProviderErrorReplacesItsCachedWindows(t *testing.T) {
	h := New(agterm.New(t.TempDir()+"/absent.sock"), t.TempDir())
	now := time.Date(2026, 9, 6, 10, 0, 0, 0, time.UTC)
	h.limitsNow = func() time.Time { return now }
	var mu sync.Mutex
	fail := false
	h.UseLimits(func(context.Context) (limits.Report, limits.Report) {
		mu.Lock()
		defer mu.Unlock()
		if fail {
			return limits.Report{Error: limits.ErrUnreachable}, limits.Report{Error: limits.ErrUnreachable}
		}
		return limits.Report{Windows: []limits.Window{{Kind: "5h", RemainingPct: 50}}},
			limits.Report{Windows: []limits.Window{{Kind: "7d", RemainingPct: 50}}}
	})

	h.Handle(context.Background(), Request{Verb: VerbLimits})
	mu.Lock()
	fail = true
	mu.Unlock()
	now = now.Add(time.Minute)
	resp := h.Handle(context.Background(), Request{Verb: VerbLimits, Fresh: true})

	if resp.Limits.Claude.Error != limits.ErrUnreachable || len(resp.Limits.Claude.Windows) != 0 {
		t.Errorf("a stale percentage survived a failed fetch: %+v", resp.Limits.Claude)
	}
}

func TestLimitsWithoutAReaderIsARefusalNotAPanic(t *testing.T) {
	h := New(agterm.New(t.TempDir()+"/absent.sock"), t.TempDir())
	resp := h.Handle(context.Background(), Request{Verb: VerbLimits})
	if resp.OK || resp.Error == "" {
		t.Fatalf("got %+v", resp)
	}
}

// The single-flight, exercised for real: ten callers during one slow fetch produce one fetch.
func TestConcurrentCallersShareOneFetch(t *testing.T) {
	h := New(agterm.New(t.TempDir()+"/absent.sock"), t.TempDir())
	var mu sync.Mutex
	calls := 0
	h.UseLimits(func(context.Context) (limits.Report, limits.Report) {
		mu.Lock()
		calls++
		mu.Unlock()
		time.Sleep(50 * time.Millisecond)
		return limits.Report{Windows: []limits.Window{{Kind: "5h", RemainingPct: 1}}}, limits.Report{Error: limits.ErrExpired}
	})

	var wg sync.WaitGroup
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if resp := h.Handle(context.Background(), Request{Verb: VerbLimits, Fresh: true}); !resp.OK {
				t.Errorf("refused: %s", resp.Error)
			}
		}()
	}
	wg.Wait()

	if calls != 1 {
		t.Fatalf("%d fetches for ten simultaneous callers", calls)
	}
}
