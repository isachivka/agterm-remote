package limits

import (
	"context"
	"testing"
	"time"
)

// The shape the endpoint answered with on 2026-11-04, trimmed: no session window active, the
// weekly window, and a per-model weekly cap that only the `limits` array carries.
const claudeSample = `{
  "five_hour": null,
  "seven_day": {"utilization": 14.0, "resets_at": "2026-11-09T15:59:59.891684+00:00"},
  "limits": [
    {"kind": "weekly_all", "percent": 14, "resets_at": "2026-11-09T15:59:59.891684+00:00", "scope": null},
    {"kind": "weekly_scoped", "percent": 0, "resets_at": "2026-11-09T16:00:00+00:00",
     "scope": {"model": {"id": null, "display_name": "Fable"}, "surface": null}},
    {"kind": "session", "percent": 41.5, "resets_at": "2026-11-04T15:00:00Z"}
  ]
}`

func TestParseClaudeReadsTheLimitsArray(t *testing.T) {
	ws, err := parseClaude([]byte(claudeSample))
	if err != nil {
		t.Fatal(err)
	}
	want := []Window{
		{"Week", 14, time.Date(2026, 11, 9, 15, 59, 59, 0, time.UTC).Unix()},
		{"Fable week", 0, time.Date(2026, 11, 9, 16, 0, 0, 0, time.UTC).Unix()},
		{"5h", 41.5, time.Date(2026, 11, 4, 15, 0, 0, 0, time.UTC).Unix()},
	}
	if len(ws) != len(want) {
		t.Fatalf("got %+v", ws)
	}
	for i := range want {
		if ws[i] != want[i] {
			t.Errorf("window %d: got %+v, want %+v", i, ws[i], want[i])
		}
	}
}

func TestParseClaudeFallsBackToTheOlderWindows(t *testing.T) {
	ws, err := parseClaude([]byte(`{"five_hour": {"utilization": 7, "resets_at": null},
		"seven_day": {"utilization": 30, "resets_at": "2026-11-09T16:00:00Z"}}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(ws) != 2 || ws[0].Label != "5h" || ws[0].UsedPercent != 7 || ws[0].ResetsAt != 0 || ws[1].Label != "Week" {
		t.Errorf("got %+v", ws)
	}
}

func TestParseClaudeRefusesAnUnknownShape(t *testing.T) {
	if _, err := parseClaude([]byte(`{"something": "else"}`)); err == nil {
		t.Error("an answer with no window parsed as if it had some")
	}
}

func TestClaudeCredentialsGiveThePlan(t *testing.T) {
	c, ok := parseClaudeCredentials([]byte(`{"claudeAiOauth": {"accessToken": "t", "expiresAt": 1793789234853,
		"subscriptionType": "max", "rateLimitTier": "default_claude_max_20x"}}`))
	if !ok || c.plan != "Max 20x" || c.token != "t" || c.expiresAt.UnixMilli() != 1793789234853 {
		t.Errorf("got %+v", c)
	}
	if _, ok := parseClaudeCredentials([]byte(`{}`)); ok {
		t.Error("credentials without a token were accepted")
	}
}

func TestParseCodex(t *testing.T) {
	now := time.Unix(1_794_000_000, 0)
	plan, ws, err := parseCodex([]byte(`{"plan_type": "prolite", "rate_limit": {
		"primary_window": {"used_percent": 12, "limit_window_seconds": 18000, "reset_after_seconds": 600},
		"secondary_window": {"used_percent": 3, "limit_window_seconds": 604800, "reset_at": 1794380474}}}`), now)
	if err != nil {
		t.Fatal(err)
	}
	if plan != "Pro Lite" {
		t.Errorf("plan %q", plan)
	}
	want := []Window{{"5h", 12, now.Unix() + 600}, {"Week", 3, 1794380474}}
	for i := range want {
		if ws[i] != want[i] {
			t.Errorf("window %d: got %+v, want %+v", i, ws[i], want[i])
		}
	}
}

// ---- the cache ----

type clock struct{ t time.Time }

func (c *clock) now() time.Time { return c.t }

type scripted struct {
	calls   int
	answers []result
}

func (s *scripted) fetch(context.Context) result {
	r := s.answers[min(s.calls, len(s.answers)-1)]
	s.calls++
	return r
}

func ok(percent float64) result {
	return result{outcome: outcomeOK, plan: "Max", windows: []Window{{Label: "5h", UsedPercent: percent}}}
}

func TestTheCacheAnswersFromMemoryUntilMaxAge(t *testing.T) {
	clk := &clock{time.Unix(1_000_000, 0)}
	s := &scripted{answers: []result{ok(10), ok(20)}}
	c := newCache("", clk.now, fetcher{"claude", s.fetch})

	if got := c.Get(context.Background(), false); got[0].Windows[0].UsedPercent != 10 {
		t.Fatalf("got %+v", got)
	}
	clk.t = clk.t.Add(MaxAge - time.Second)
	if got := c.Get(context.Background(), false); got[0].Windows[0].UsedPercent != 10 || s.calls != 1 {
		t.Fatalf("asked again inside MaxAge: calls=%d", s.calls)
	}
	clk.t = clk.t.Add(2 * time.Second)
	if got := c.Get(context.Background(), false); got[0].Windows[0].UsedPercent != 20 || s.calls != 2 {
		t.Fatalf("did not ask again after MaxAge: calls=%d", s.calls)
	}
}

func TestForceSkipsTheCacheButNotTheFloor(t *testing.T) {
	clk := &clock{time.Unix(1_000_000, 0)}
	s := &scripted{answers: []result{ok(10), ok(20), ok(30)}}
	c := newCache("", clk.now, fetcher{"claude", s.fetch})
	c.Get(context.Background(), false)

	c.Get(context.Background(), true)
	if s.calls != 1 {
		t.Fatalf("a force inside the floor reached the endpoint: calls=%d", s.calls)
	}
	clk.t = clk.t.Add(ForceFloor)
	if got := c.Get(context.Background(), true); got[0].Windows[0].UsedPercent != 20 || s.calls != 2 {
		t.Fatalf("a force after the floor did not refresh: calls=%d", s.calls)
	}
}

func TestAnOutageKeepsTheNumbersAndBacksOff(t *testing.T) {
	clk := &clock{time.Unix(1_000_000, 0)}
	s := &scripted{answers: []result{ok(10), {outcome: outcomeUnavailable, message: "rate limited", retryAfter: time.Hour}, ok(30)}}
	c := newCache("", clk.now, fetcher{"claude", s.fetch})
	c.Get(context.Background(), false)

	clk.t = clk.t.Add(MaxAge)
	got := c.Get(context.Background(), false)
	if got[0].Error != "rate limited" || got[0].Windows[0].UsedPercent != 10 {
		t.Fatalf("an outage hid the numbers or said nothing: %+v", got)
	}
	clk.t = clk.t.Add(MaxAge)
	c.Get(context.Background(), false)
	if s.calls != 2 {
		t.Fatalf("asked again while backing off: calls=%d", s.calls)
	}
	// The owner asked, so the backoff gives way.
	if got := c.Get(context.Background(), true); got[0].Windows[0].UsedPercent != 30 || got[0].Error != "" {
		t.Fatalf("a forced refresh after the outage: %+v", got)
	}
}

func TestANotSignedInProviderIsNotShown(t *testing.T) {
	clk := &clock{time.Unix(1_000_000, 0)}
	claude := &scripted{answers: []result{ok(10)}}
	codex := &scripted{answers: []result{{outcome: outcomeNotConfigured}}}
	c := newCache("", clk.now, fetcher{"claude", claude.fetch}, fetcher{"codex", codex.fetch})

	got := c.Get(context.Background(), false)
	if len(got) != 1 || got[0].Name != "claude" {
		t.Errorf("got %+v", got)
	}
}

func TestTheLastNumbersSurviveARestart(t *testing.T) {
	dir := t.TempDir()
	clk := &clock{time.Unix(1_000_000, 0)}
	first := &scripted{answers: []result{ok(10)}}
	newCache(dir, clk.now, fetcher{"claude", first.fetch}).Get(context.Background(), false)

	clk.t = clk.t.Add(time.Minute)
	second := &scripted{answers: []result{ok(99)}}
	got := newCache(dir, clk.now, fetcher{"claude", second.fetch}).Get(context.Background(), false)
	if second.calls != 0 || got[0].Windows[0].UsedPercent != 10 || got[0].Plan != "Max" {
		t.Errorf("a restart inside MaxAge asked again or lost the numbers: calls=%d %+v", second.calls, got)
	}
}
