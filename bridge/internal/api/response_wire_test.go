package api

import (
	"encoding/json"
	"strings"
	"testing"
)

// **A false must reach the wire.** This is the bug that cost the owner three presses on a dead button.
//
// FitEnabled was `bool` with `json:"fit_enabled,omitempty"`, and omitempty on a bool omits the field
// when it is FALSE - so the bridge could send true or silence and never "off", while off is the normal
// starting state. The phone reads absence as "not answered yet" and disables the toggle, so the
// control was dead exactly when it was needed.
//
// Mutation-verified: reverting the field to a bare bool serialises {"ok":true} and fails this.
func TestAnOffSettingIsActuallySentRatherThanOmitted(t *testing.T) {
	off := false
	raw, err := json.Marshal(Response{OK: true, FitEnabled: &off})
	if err != nil {
		t.Fatal(err)
	}

	if !strings.Contains(string(raw), `"fit_enabled":false`) {
		t.Errorf("an off setting serialised as %s - the phone cannot tell that from no answer", raw)
	}
}

// All three states must be distinct on the wire, because the phone has three and a bool has two.
func TestTheThreeStatesAreDistinctOnTheWire(t *testing.T) {
	on := true
	off := false
	for _, c := range []struct {
		what string
		in   *bool
		want string
	}{
		{"on", &on, `"fit_enabled":true`},
		{"off", &off, `"fit_enabled":false`},
		{"not answered", nil, `"fit_enabled":null`},
	} {
		raw, _ := json.Marshal(Response{OK: true, FitEnabled: c.in})
		if !strings.Contains(string(raw), c.want) {
			t.Errorf("%s serialised as %s, want %s", c.what, raw, c.want)
		}
	}
}
