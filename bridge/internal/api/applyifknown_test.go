package api

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm/agtermtest"
	"github.com/isachivka/agterm-remote/bridge/internal/resize"
)

// The boundary between the wire's two booleans and the closed set behind it.
//
// # Approved rather than assumed
//
// This adds a field to the protocol, which was flagged to the PM before it was built and **confirmed
// by him explicitly**. What decided it: having the phone remember which geometries are already
// calibrated is a second source of truth for something only the bridge can know, which is the precise
// failure `FitState` exists to prevent. Better to add a field than to re-create the bug the protocol
// was shaped to avoid.

// **The pair that must not resolve.** `recalibrate` and `cached_only` together say *measure this
// freshly, but only if you already measured it.* There is no reading of that which is what somebody
// meant, so it is refused rather than resolved — a boundary that picked one would be inventing an
// intention, and every silent default in this feature's history ended up in a requirement document.
func TestAskingToMeasureAndNotToMeasureIsRefused(t *testing.T) {
	if _, err := intentOf(Request{Recalibrate: true, CachedOnly: true}); err == nil {
		t.Fatal("the impossible pair resolved to an intent instead of being refused")
	}
}

func TestTheThreeIntentsComeFromTheTwoFlags(t *testing.T) {
	for _, c := range []struct {
		name string
		req  Request
		want resize.Intent
	}{
		{"a press", Request{}, resize.IntentApply},
		{"a long press", Request{Recalibrate: true}, resize.IntentRecalibrate},
		{"the automatic re-apply", Request{CachedOnly: true}, resize.IntentApplyIfKnown},
	} {
		t.Run(c.name, func(t *testing.T) {
			got, err := intentOf(c.req)
			if err != nil {
				t.Fatal(err)
			}
			if got != c.want {
				t.Fatalf("got %v, want %v", got, c.want)
			}
		})
	}
}

// **THE SHAPE THAT MATTERS: not knowing is an ANSWER, not a failure.**
//
// The phone asked "apply it if you know it". Not knowing is a legitimate reply to that question, and
// dressing it as a refusal would put it through the path that once replaced the owner's terminal with
// a full-page error about probe widths, which this must not undo one requirement later.
func TestAnUnknownGeometryIsAnOrdinaryReply(t *testing.T) {
	h, _ := handler(t, func(agtermtest.Request) any {
		return agtermtest.OK(map[string]any{"windows": []any{
			map[string]any{"id": "w1", "active": true,
				"geometry": map[string]any{"display": 0, "width": 1400, "height": 900}},
		}})
	})

	resp := h.Handle(context.Background(), Request{
		Verb: VerbResize, Session: sessionA, BoxWidthDp: 440,
		CharacterWidthMilliDp: 9800, CachedOnly: true,
	})

	if !resp.OK {
		t.Fatalf("an unknown geometry came back as a failure: %+v", resp)
	}
	if !resp.NeedsFit {
		t.Fatal("the reply did not say the geometry needs a fit, so the phone has nothing to tell him")
	}
	if resp.Refusal != "" {
		t.Fatalf("it was marked as a refusal (%q); the phone draws those differently", resp.Refusal)
	}
}

// **`needs_fit` is on the wire even when false**, per the rule at the top of Response: a field whose
// zero value is meaningful is never omitted. False means *it applied*, which is what the phone acts on
// by saying nothing at all. FitEnabled taught this the expensive way — omitempty on a bool could
// transmit true or silence and never "off".
func TestNeedsFitIsSentEvenWhenFalse(t *testing.T) {
	raw, err := json.Marshal(Response{OK: true, NeedsFit: false})
	if err != nil {
		t.Fatal(err)
	}

	if !strings.Contains(string(raw), `"needs_fit":false`) {
		t.Fatalf("an applied fit serialised as %s - the phone cannot tell that from an old bridge", raw)
	}
}
