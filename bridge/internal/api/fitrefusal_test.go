package api

import (
	"context"
	"strings"
	"testing"

	"dev.isachivka.bewareofsugar/bridge/internal/agterm/agtermtest"
)

// A fit that will not measure is not the link breaking — REQ-0037.
//
// # What the owner saw
//
// On 2026-08-26 a calibration declined and his terminal, session list and input bar were replaced by a
// full-screen English error reading *"both probe widths measured 86 columns; the calibration session
// is not rendering the long line"* — a sentence addressed to us, lifted out of a log, with nothing in
// it he could act on. **REQ-0017 abolished exactly that shape** for a complaint about pasted text, and
// this path had grown it back.
//
// The marker is what decides which shape the phone draws: `RefusalContent` keeps the socket and the
// screen, a bare failure replaces both.

func refusalOf(t *testing.T, req Request) Response {
	t.Helper()
	h, _ := handler(t, func(agtermtest.Request) any {
		return map[string]any{"ok": false, "error": "agterm is not answering"}
	})
	return h.Handle(context.Background(), req)
}

// **THE TEST THE DEFECT FAILS.** Every no on this path is marked as content, so none of them takes his
// terminal away.
func TestAFitRefusalNeverLooksLikeABrokenConnection(t *testing.T) {
	for _, c := range []struct {
		name string
		req  Request
	}{
		{"the phone sent no character width", Request{Verb: VerbResize, Session: sessionA, BoxWidthDp: 440}},
		// **This case is the stretch, and it is named rather than hidden.** An agterm that will not
		// answer IS a laptop-side failure, which is the direction `RefusalContent`'s own doc warns
		// about: a small notice while the machine is actually down.
		//
		// It is accepted here because the fit is not how he finds out. The screen poll runs every two
		// seconds against the same agterm and has its own unreadable/refused handling, so a laptop
		// that has gone away is reported by the thing that is actually watching it. What the fit must
		// not do is be the one to announce it, by taking his terminal away over a button press.
		{"agterm will not answer", Request{
			Verb: VerbResize, Session: sessionA, BoxWidthDp: 440, CharacterWidthMilliDp: 9800,
		}},
	} {
		t.Run(c.name, func(t *testing.T) {
			resp := refusalOf(t, c.req)

			if resp.OK {
				t.Fatal("this was supposed to be a refusal")
			}
			if resp.Refusal != RefusalContent {
				t.Fatalf("refusal marked %q; unmarked, the phone drops a healthy socket and replaces "+
					"the terminal with a full-page error - REQ-0017, met again on the fit path",
					resp.Refusal)
			}
		})
	}
}

// The width-OFF path is not a refusal at all and must stay a plain success — turning everything on
// this path into a content refusal would have swept it up too.
func TestTurningTheFitOffIsStillJustSuccess(t *testing.T) {
	h, _ := handler(t, func(agtermtest.Request) any { return agtermtest.OK(map[string]any{"windows": []any{}}) })

	resp := h.Handle(context.Background(), Request{Verb: VerbResize, Session: sessionA})

	if !resp.OK && resp.Refusal != RefusalContent {
		t.Fatalf("turning the fit off produced an unmarked failure: %+v", resp)
	}
}

// **The arithmetic still reaches the log**, which is who it was addressed to. The phone gets a
// sentence; the file gets the numbers. Asserted on the response rather than on the log because what
// must not happen is the sentence reaching HIM as a screen.
func TestTheRefusalStillCarriesItsReasonForTheLog(t *testing.T) {
	resp := refusalOf(t, Request{Verb: VerbResize, Session: sessionA, BoxWidthDp: 440})

	if !strings.Contains(resp.Error, "character") {
		t.Fatalf("the refusal lost its reason: %q", resp.Error)
	}
}
