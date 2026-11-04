package api

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
	"github.com/isachivka/agterm-remote/bridge/internal/limits"
)

type fakeLimits struct{ forced []bool }

func (f *fakeLimits) Get(_ context.Context, force bool) []limits.Provider {
	f.forced = append(f.forced, force)
	return []limits.Provider{{Name: "claude", Plan: "Max 20x", Windows: []limits.Window{{Label: "5h", UsedPercent: 41}}}}
}

// The limits verb answers from the cache and passes `force` through - and needs no agterm at all.
func TestTheLimitsVerbAnswersAndPassesForceThrough(t *testing.T) {
	h := New(agterm.New(t.TempDir()+"/absent.sock"), "")
	fake := &fakeLimits{}
	h.limits = fake

	resp := h.Handle(context.Background(), Request{Verb: VerbLimits})
	if !resp.OK || len(resp.Limits) != 1 || resp.Limits[0].Windows[0].UsedPercent != 41 {
		t.Fatalf("got %+v", resp)
	}
	h.Handle(context.Background(), Request{Verb: VerbLimits, Force: true})
	if len(fake.forced) != 2 || fake.forced[0] || !fake.forced[1] {
		t.Errorf("force reached the cache as %v", fake.forced)
	}

	line, _ := json.Marshal(resp)
	if !strings.Contains(string(line), `"limits":[{"name":"claude","plan":"Max 20x","windows":[{"label":"5h","used_percent":41}]`) {
		t.Errorf("wire form: %s", line)
	}
}
