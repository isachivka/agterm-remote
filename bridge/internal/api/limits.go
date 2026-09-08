package api

import (
	"context"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/limits"
)

// This file is VerbLimits — REQ-0045 — and its cache. The reading itself lives in internal/limits;
// what this file decides is WHEN to read.

// Limits is the verb's answer: both providers and the moment they were asked.
type Limits struct {
	// FetchedAt is the CACHE's timestamp, so the phone's "updated N min ago" is the truth about the
	// number rather than about the reply that carried it.
	FetchedAt string        `json:"fetched_at"`
	Claude    limits.Report `json:"claude"`
	Codex     limits.Report `json:"codex"`
}

// LimitsCacheFor is how long an answer is served without asking again. Thirty minutes is the owner's
// number — *"кэшировать на например 30 минут"* — and it is a constant rather than a setting.
const LimitsCacheFor = 30 * time.Minute

// limitsSettle is how recent a fetch has to be for a `fresh` request to reuse it. Two long presses in
// the same breath — or two phones — share one fetch rather than racing the provider twice, and a
// press that lands the moment a fetch finished gets the answer it would have got anyway.
const limitsSettle = 10 * time.Second

// UseLimits installs the reader. Without one the verb refuses, which is what tests get by default and
// what keeps every_reply_test off the network.
func (h *Handler) UseLimits(fetch func(ctx context.Context) (claude, codex limits.Report)) {
	h.limitsMu.Lock()
	defer h.limitsMu.Unlock()
	h.limitsFetch = fetch
}

// limits answers from the cache, or fetches.
//
// **The mutex is held across the fetch on purpose.** A second caller arriving during a fetch waits and
// then reads the cache that fetch just filled — the single-flight the spec asks for, with no channel
// and no second state. Nothing else in the handler waits on this lock, so a ten-second provider stalls
// only other limits requests, never a screen read.
func (h *Handler) limits(ctx context.Context, req Request) Response {
	h.limitsMu.Lock()
	defer h.limitsMu.Unlock()

	if h.limitsFetch == nil {
		return fail("this bridge was started without a limits reader")
	}
	now := h.limitsNow()
	if h.limitsHeld != nil {
		age := now.Sub(h.limitsAt)
		if (!req.Fresh && age < LimitsCacheFor) || age < limitsSettle {
			return Response{OK: true, Limits: h.limitsHeld}
		}
	}

	claude, codex := h.limitsFetch(ctx)
	// A failed provider REPLACES its cached windows rather than leaving the old ones in place: a
	// percentage from forty minutes ago labelled as five minutes old is the lie this feature exists
	// to prevent. The phone keeps its own last good numbers and says the laptop did not answer.
	h.limitsHeld = &Limits{FetchedAt: now.UTC().Format(time.RFC3339), Claude: claude, Codex: codex}
	h.limitsAt = now
	return Response{OK: true, Limits: h.limitsHeld}
}
