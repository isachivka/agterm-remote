// Package limits reads the Claude Code and Codex subscription limits of the Mac the bridge runs on,
// so the phone can show them under the session list.
//
// # Where the numbers come from
//
// Nothing to configure: the logins the two CLIs already keep on this Mac are reused.
//
//   - Claude Code: the Keychain item `Claude Code-credentials`, read through /usr/bin/security (it is
//     on the item's access list, so nobody is prompted), or `~/.claude/.credentials.json` where the
//     CLI keeps a file instead. Asked of `GET https://api.anthropic.com/api/oauth/usage`.
//   - Codex: `$CODEX_HOME/auth.json` (default `~/.codex/auth.json`). Asked of
//     `GET https://chatgpt.com/backend-api/wham/usage`.
//
// Both endpoints are undocumented internals of the official clients and may change without notice;
// a shape this package does not recognise is reported as such rather than guessed at.
//
// # Read-only, deliberately
//
// **A token is never refreshed here.** Refreshing rotates the refresh token, and the CLI holding the
// old one would be logged out. An expired token is reported ("run claude once"), and the next time
// the owner uses the CLI it renews itself and this picks it up.
//
// # Why a cache, and why fifteen minutes
//
// The usage endpoints are rate limited per account, and every Claude Code session on the Mac draws
// on that same budget. So the bridge answers from memory for [MaxAge], backs off after a 429, 5xx or
// an outage, and asks again only when the owner forces it — and even then not twice in
// [ForceFloor]. The last good numbers are kept on disk so a bridge restart does not ask again.
package limits

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Window is one rate-limit window: "5h", "Week", "Fable week".
type Window struct {
	Label string `json:"label"`
	// UsedPercent is 0..100.
	UsedPercent float64 `json:"used_percent"`
	// ResetsAt is unix seconds, zero when the provider did not say.
	ResetsAt int64 `json:"resets_at,omitempty"`
}

// Provider is what the phone is told about one CLI.
type Provider struct {
	// Name is "claude" or "codex".
	Name string `json:"name"`
	Plan string `json:"plan,omitempty"`
	// Windows are the last good numbers; they survive a failed attempt.
	Windows []Window `json:"windows,omitempty"`
	// FetchedAt is when Windows were read, unix seconds; zero when they never were.
	FetchedAt int64 `json:"fetched_at,omitempty"`
	// Error says why the last attempt did not produce fresh numbers. Windows, when present, are the
	// older ones.
	Error string `json:"error,omitempty"`
}

const (
	// MaxAge is how long an answer is served from memory.
	MaxAge = 15 * time.Minute
	// ForceFloor stops a forced refresh from reaching the endpoint twice in quick succession: a
	// double tap, or two phones.
	ForceFloor = 20 * time.Second
	minBackoff = 2 * time.Minute
	maxBackoff = 30 * time.Minute
	timeout    = 15 * time.Second
	cacheFile  = "limits-cache.json"
)

type outcome int

const (
	outcomeOK outcome = iota
	// The CLI is not signed in on this Mac; the provider is not shown.
	outcomeNotConfigured
	// The request was answered and the answer was no: signed out, an unexpected shape.
	outcomeFailed
	// Worth retrying later and says nothing about the numbers: 429, 5xx, offline.
	outcomeUnavailable
)

type result struct {
	outcome    outcome
	plan       string
	windows    []Window
	message    string
	retryAfter time.Duration
}

type fetcher struct {
	name  string
	fetch func(ctx context.Context) result
}

type entry struct {
	notConfigured bool
	plan          string
	windows       []Window
	fetchedAt     time.Time
	lastAttempt   time.Time
	message       string
	pausedUntil   time.Time
	backoff       time.Duration
}

// Cache answers the limits verb.
type Cache struct {
	fetchers []fetcher
	now      func() time.Time
	path     string

	// fetching serialises refreshes, so concurrent requests for the same stale numbers make one
	// round trip and the second caller reads what the first fetched.
	fetching sync.Mutex
	mu       sync.Mutex
	entries  map[string]*entry
}

// New returns a cache over the real Claude and Codex endpoints, persisted under stateDir (or not at
// all when it is empty).
func New(stateDir string) *Cache {
	client := &http.Client{Timeout: timeout}
	c := newCache(stateDir, time.Now,
		fetcher{"claude", func(ctx context.Context) result { return fetchClaude(ctx, client) }},
		fetcher{"codex", func(ctx context.Context) result { return fetchCodex(ctx, client) }},
	)
	return c
}

func newCache(stateDir string, now func() time.Time, fetchers ...fetcher) *Cache {
	c := &Cache{fetchers: fetchers, now: now, entries: map[string]*entry{}}
	if stateDir != "" {
		c.path = filepath.Join(stateDir, cacheFile)
		c.load()
	}
	return c
}

// Get returns every provider signed in on this Mac, refreshing those whose numbers are older than
// [MaxAge] - or all of them when force is set, unless they were asked within [ForceFloor].
func (c *Cache) Get(ctx context.Context, force bool) []Provider {
	c.fetching.Lock()
	defer c.fetching.Unlock()

	now := c.now()
	var due []fetcher
	c.mu.Lock()
	for _, f := range c.fetchers {
		e := c.entries[f.name]
		if e == nil || c.isDue(e, now, force) {
			due = append(due, f)
		}
	}
	c.mu.Unlock()

	if len(due) > 0 {
		results := make([]result, len(due))
		var wg sync.WaitGroup
		for i, f := range due {
			wg.Add(1)
			go func() {
				defer wg.Done()
				fctx, cancel := context.WithTimeout(ctx, timeout)
				defer cancel()
				results[i] = f.fetch(fctx)
			}()
		}
		wg.Wait()
		c.mu.Lock()
		for i, f := range due {
			c.apply(f.name, results[i], now)
		}
		c.mu.Unlock()
		c.save()
	}

	c.mu.Lock()
	defer c.mu.Unlock()
	out := []Provider{}
	for _, f := range c.fetchers {
		e := c.entries[f.name]
		if e == nil || e.notConfigured {
			continue
		}
		p := Provider{Name: f.name, Plan: e.plan, Windows: e.windows, Error: e.message}
		if !e.fetchedAt.IsZero() {
			p.FetchedAt = e.fetchedAt.Unix()
		}
		out = append(out, p)
	}
	return out
}

func (c *Cache) isDue(e *entry, now time.Time, force bool) bool {
	if force {
		return now.Sub(e.lastAttempt) >= ForceFloor
	}
	if now.Before(e.pausedUntil) {
		return false
	}
	return now.Sub(e.lastAttempt) >= MaxAge
}

func (c *Cache) apply(name string, r result, now time.Time) {
	e := c.entries[name]
	if e == nil {
		e = &entry{}
		c.entries[name] = e
	}
	e.lastAttempt = now
	switch r.outcome {
	case outcomeOK:
		*e = entry{plan: r.plan, windows: r.windows, fetchedAt: now, lastAttempt: now}
	case outcomeNotConfigured:
		*e = entry{notConfigured: true, lastAttempt: now}
	case outcomeFailed:
		e.notConfigured = false
		e.message = r.message
	case outcomeUnavailable:
		e.notConfigured = false
		e.message = r.message
		next := max(r.retryAfter, max(e.backoff*2, minBackoff))
		e.backoff = min(next, maxBackoff)
		e.pausedUntil = now.Add(e.backoff)
	}
}

// persisted is the file form: the last good numbers and when they were read, nothing else. No token
// is ever written.
type persisted struct {
	Plan      string   `json:"plan,omitempty"`
	Windows   []Window `json:"windows"`
	FetchedAt int64    `json:"fetched_at"`
}

func (c *Cache) load() {
	data, err := os.ReadFile(c.path)
	if err != nil {
		return
	}
	var saved map[string]persisted
	if json.Unmarshal(data, &saved) != nil {
		return
	}
	for name, p := range saved {
		at := time.Unix(p.FetchedAt, 0)
		// The read time doubles as the last attempt, so a restart inside MaxAge does not ask again.
		c.entries[name] = &entry{plan: p.Plan, windows: p.Windows, fetchedAt: at, lastAttempt: at}
	}
}

func (c *Cache) save() {
	if c.path == "" {
		return
	}
	c.mu.Lock()
	saved := map[string]persisted{}
	for name, e := range c.entries {
		if e.notConfigured || e.fetchedAt.IsZero() {
			continue
		}
		saved[name] = persisted{Plan: e.plan, Windows: e.windows, FetchedAt: e.fetchedAt.Unix()}
	}
	c.mu.Unlock()
	data, err := json.Marshal(saved)
	if err != nil {
		return
	}
	tmp := c.path + ".tmp"
	if os.WriteFile(tmp, data, 0o600) == nil {
		_ = os.Rename(tmp, c.path)
	}
}

// ---- HTTP ----

var errBadResponse = errors.New("unexpected response")

type httpStatusError struct {
	code       int
	retryAfter time.Duration
}

func (e *httpStatusError) Error() string { return fmt.Sprintf("HTTP %d", e.code) }

// httpDoer is the one method of http.Client used here, so tests can answer without a network.
type httpDoer interface {
	Do(*http.Request) (*http.Response, error)
}

func get(ctx context.Context, client httpDoer, url string, headers map[string]string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		e := &httpStatusError{code: resp.StatusCode}
		if s, err := strconv.Atoi(resp.Header.Get("Retry-After")); err == nil && s > 0 {
			e.retryAfter = time.Duration(s) * time.Second
		}
		return nil, e
	}
	var body json.RawMessage
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&body); err != nil {
		return nil, errBadResponse
	}
	return body, nil
}

// failure turns a request error into what the phone is told, in the CLI's own terms.
func failure(err error, cli string) result {
	var status *httpStatusError
	switch {
	case errors.As(err, &status) && status.code == http.StatusTooManyRequests:
		return result{outcome: outcomeUnavailable, message: "rate limited", retryAfter: status.retryAfter}
	case errors.As(err, &status) && status.code >= 500:
		return result{outcome: outcomeUnavailable, message: "service unavailable", retryAfter: status.retryAfter}
	case errors.As(err, &status) && (status.code == http.StatusUnauthorized || status.code == http.StatusForbidden):
		return result{outcome: outcomeFailed, message: "signed out - run " + cli + " once"}
	case errors.As(err, &status):
		return result{outcome: outcomeFailed, message: status.Error()}
	case errors.Is(err, errBadResponse):
		return result{outcome: outcomeFailed, message: "unexpected response"}
	default:
		return result{outcome: outcomeUnavailable, message: "offline"}
	}
}

// ---- dates and labels ----

func parseISO(s string) int64 {
	if s == "" {
		return 0
	}
	if t, err := time.Parse(time.RFC3339Nano, s); err == nil {
		return t.Unix()
	}
	return 0
}

func number(v any) (float64, bool) {
	f, ok := v.(float64)
	if !ok || math.IsNaN(f) || math.IsInf(f, 0) {
		return 0, false
	}
	return f, true
}

func itoa(n int) string { return strconv.Itoa(n) }

func capitalize(s string) string {
	if s == "" {
		return s
	}
	return strings.ToUpper(s[:1]) + s[1:]
}
