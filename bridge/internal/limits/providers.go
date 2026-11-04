package limits

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// ---- Claude Code ----

const (
	claudeUsageURL       = "https://api.anthropic.com/api/oauth/usage"
	claudeKeychainItem   = "Claude Code-credentials"
	claudeOAuthBetaValue = "oauth-2025-04-20"
)

type claudeCredentials struct {
	token     string
	plan      string
	expiresAt time.Time
}

func fetchClaude(ctx context.Context, client httpDoer) result {
	creds, ok := loadClaudeCredentials(ctx)
	if !ok {
		return result{outcome: outcomeNotConfigured}
	}
	if !creds.expiresAt.IsZero() && creds.expiresAt.Before(time.Now()) {
		return result{outcome: outcomeFailed, message: "token expired - run claude once"}
	}
	body, err := get(ctx, client, claudeUsageURL, map[string]string{
		"Authorization":  "Bearer " + creds.token,
		"anthropic-beta": claudeOAuthBetaValue,
	})
	if err != nil {
		return failure(err, "claude")
	}
	windows, err := parseClaude(body)
	if err != nil {
		return result{outcome: outcomeFailed, message: "unexpected response"}
	}
	return result{outcome: outcomeOK, plan: creds.plan, windows: windows}
}

// loadClaudeCredentials reads the Keychain item first and the credentials file second - the CLI
// keeps one or the other depending on how it was installed.
func loadClaudeCredentials(ctx context.Context) (claudeCredentials, bool) {
	var raw []byte
	if out, err := exec.CommandContext(ctx, "/usr/bin/security",
		"find-generic-password", "-s", claudeKeychainItem, "-w").Output(); err == nil {
		raw = out
	} else {
		dir := os.Getenv("CLAUDE_CONFIG_DIR")
		if dir == "" {
			home, _ := os.UserHomeDir()
			dir = filepath.Join(home, ".claude")
		}
		raw, _ = os.ReadFile(filepath.Join(dir, ".credentials.json"))
	}
	return parseClaudeCredentials(raw)
}

func parseClaudeCredentials(raw []byte) (claudeCredentials, bool) {
	var doc struct {
		OAuth struct {
			AccessToken      string  `json:"accessToken"`
			ExpiresAt        float64 `json:"expiresAt"`
			SubscriptionType string  `json:"subscriptionType"`
			RateLimitTier    string  `json:"rateLimitTier"`
		} `json:"claudeAiOauth"`
	}
	if json.Unmarshal(raw, &doc) != nil || doc.OAuth.AccessToken == "" {
		return claudeCredentials{}, false
	}
	c := claudeCredentials{
		token: doc.OAuth.AccessToken,
		plan:  claudePlan(doc.OAuth.SubscriptionType, doc.OAuth.RateLimitTier),
	}
	if doc.OAuth.ExpiresAt > 0 {
		c.expiresAt = time.UnixMilli(int64(doc.OAuth.ExpiresAt))
	}
	return c, true
}

var tierMultiplier = regexp.MustCompile(`_(\d+x)$`)

// claudePlan turns `max` + `default_claude_max_20x` into "Max 20x".
func claudePlan(subscription, tier string) string {
	if subscription == "" {
		return ""
	}
	name := capitalize(subscription)
	if m := tierMultiplier.FindStringSubmatch(tier); m != nil {
		name += " " + m[1]
	}
	return name
}

// parseClaude reads the `limits` array - the only place per-model weekly caps appear - and falls back
// to the older top-level windows when the array is absent.
func parseClaude(body []byte) ([]Window, error) {
	var root map[string]any
	if err := json.Unmarshal(body, &root); err != nil {
		return nil, errBadResponse
	}
	var windows []Window
	if limits, ok := root["limits"].([]any); ok {
		for _, item := range limits {
			l, ok := item.(map[string]any)
			if !ok {
				continue
			}
			kind, _ := l["kind"].(string)
			percent, ok := number(l["percent"])
			if kind == "" || !ok {
				continue
			}
			resets, _ := l["resets_at"].(string)
			windows = append(windows, Window{Label: claudeLabel(kind, l["scope"]), UsedPercent: percent, ResetsAt: parseISO(resets)})
		}
	}
	if len(windows) == 0 {
		for _, k := range []struct{ key, label string }{
			{"five_hour", "5h"}, {"seven_day", "Week"},
			{"seven_day_opus", "Opus week"}, {"seven_day_sonnet", "Sonnet week"},
		} {
			w, ok := root[k.key].(map[string]any)
			if !ok {
				continue
			}
			used, ok := number(w["utilization"])
			if !ok {
				continue
			}
			resets, _ := w["resets_at"].(string)
			windows = append(windows, Window{Label: k.label, UsedPercent: used, ResetsAt: parseISO(resets)})
		}
	}
	if len(windows) == 0 {
		return nil, errBadResponse
	}
	return windows, nil
}

func claudeLabel(kind string, scope any) string {
	switch kind {
	case "session":
		return "5h"
	case "weekly_all":
		return "Week"
	case "weekly_scoped":
		name := "Scoped"
		if s, ok := scope.(map[string]any); ok {
			if m, ok := s["model"].(map[string]any); ok {
				if d, ok := m["display_name"].(string); ok && d != "" {
					name = d
				}
			} else if surface, ok := s["surface"].(string); ok && surface != "" {
				name = surface
			}
		}
		return name + " week"
	default:
		return capitalize(strings.ReplaceAll(kind, "_", " "))
	}
}

// ---- Codex ----

const codexUsageURL = "https://chatgpt.com/backend-api/wham/usage"

func fetchCodex(ctx context.Context, client httpDoer) result {
	home := os.Getenv("CODEX_HOME")
	if home == "" {
		h, _ := os.UserHomeDir()
		home = filepath.Join(h, ".codex")
	}
	raw, err := os.ReadFile(filepath.Join(home, "auth.json"))
	if err != nil {
		return result{outcome: outcomeNotConfigured}
	}
	var auth struct {
		Tokens struct {
			AccessToken string `json:"access_token"`
			AccountID   string `json:"account_id"`
		} `json:"tokens"`
	}
	if json.Unmarshal(raw, &auth) != nil || auth.Tokens.AccessToken == "" {
		return result{outcome: outcomeNotConfigured}
	}
	headers := map[string]string{
		"Authorization": "Bearer " + auth.Tokens.AccessToken,
		"User-Agent":    "codex-cli",
	}
	if auth.Tokens.AccountID != "" {
		headers["ChatGPT-Account-Id"] = auth.Tokens.AccountID
	}
	body, err := get(ctx, client, codexUsageURL, headers)
	if err != nil {
		return failure(err, "codex")
	}
	plan, windows, err := parseCodex(body, time.Now())
	if err != nil {
		return result{outcome: outcomeFailed, message: "unexpected response"}
	}
	return result{outcome: outcomeOK, plan: plan, windows: windows}
}

func parseCodex(body []byte, now time.Time) (string, []Window, error) {
	var root map[string]any
	if err := json.Unmarshal(body, &root); err != nil {
		return "", nil, errBadResponse
	}
	limits, ok := root["rate_limit"].(map[string]any)
	if !ok {
		return "", nil, errBadResponse
	}
	var windows []Window
	for _, key := range []string{"primary_window", "secondary_window"} {
		w, ok := limits[key].(map[string]any)
		if !ok {
			continue
		}
		used, ok := number(w["used_percent"])
		if !ok {
			continue
		}
		seconds, _ := number(w["limit_window_seconds"])
		var resets int64
		if at, ok := number(w["reset_at"]); ok && at > 0 {
			resets = int64(at)
		} else if after, ok := number(w["reset_after_seconds"]); ok {
			resets = now.Add(time.Duration(after) * time.Second).Unix()
		}
		windows = append(windows, Window{Label: codexLabel(int(seconds)), UsedPercent: used, ResetsAt: resets})
	}
	if len(windows) == 0 {
		return "", nil, errBadResponse
	}
	plan, _ := root["plan_type"].(string)
	return codexPlan(plan), windows, nil
}

func codexLabel(seconds int) string {
	switch {
	case seconds == 604800:
		return "Week"
	case seconds > 0 && seconds%86400 == 0:
		return itoa(seconds/86400) + "d"
	case seconds > 0 && seconds%3600 == 0:
		return itoa(seconds/3600) + "h"
	case seconds > 0:
		return itoa(seconds/60) + "m"
	default:
		return "Limit"
	}
}

func codexPlan(raw string) string {
	switch strings.ToLower(raw) {
	case "":
		return ""
	case "prolite":
		return "Pro Lite"
	default:
		return capitalize(raw)
	}
}
