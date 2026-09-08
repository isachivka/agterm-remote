package api

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
	"github.com/isachivka/agterm-remote/bridge/internal/agterm/agtermtest"
)

// **The bytes that reach agterm are the bytes the owner typed, in their own language.**
//
// Written after the phone's input field was found declaring `KeyboardType.Ascii`, which hid Gboard's
// language switch and stopped anyone typing a non-Latin language into their own terminal. The fix
// was on the phone; this is the other half of the question — that nothing between the wire and
// agterm assumes ASCII either. Asserted rather than reasoned from "UTF-8 works because it should".
func TestTypedTextReachesAgtermAsTheSameUTF8(t *testing.T) {
	for _, text := range []string{
		"καλημέρα",
		"ηχω τεστ",
		"日本語",
		"café",
		"ls -la ~/Έγγραφα",
		"grep 'σφάλμα' log.txt",
	} {
		var got string
		fake := agtermtest.Start(t, func(req agtermtest.Request) any {
			if req.Cmd == "session.type" {
				// Decoded from the raw args rather than a typed field, so this reads exactly what went
				// on the socket - which is the thing being asserted.
				var args struct {
					Text string `json:"text"`
				}
				_ = json.Unmarshal(req.Args, &args)
				got = args.Text
			}
			return agtermtest.OK(map[string]any{})
		})
		h := New(agterm.New(fake.Path), t.TempDir())

		resp := h.Handle(context.Background(), Request{
			Verb:    VerbType,
			Session: "4C9B5C9B-C77F-4913-8BEA-9DF7513AC8BA",
			Text:    text,
		})

		if !resp.OK {
			t.Errorf("%q was refused: %s", text, resp.Error)
			continue
		}
		if got != text {
			t.Errorf("agterm received %q, the owner typed %q", got, text)
		}
	}
}

// **A pasted message arrives wrapped, and the same bytes typed do not.**
//
// The envelope is the whole difference between the owner's Telegram message landing in their editor
// and its lines running as commands, so this asserts what goes on the socket rather than that the
// call returned ok.
func TestAPasteReachesAgtermWrappedAndTypingDoesNot(t *testing.T) {
	const session = "4C9B5C9B-C77F-4913-8BEA-9DF7513AC8BA"

	var got string
	fake := agtermtest.Start(t, func(req agtermtest.Request) any {
		if req.Cmd == "session.type" {
			var args struct {
				Text string `json:"text"`
			}
			_ = json.Unmarshal(req.Args, &args)
			got = args.Text
		}
		return agtermtest.OK(map[string]any{})
	})
	h := New(agterm.New(fake.Path), t.TempDir())

	resp := h.Handle(context.Background(), Request{Verb: VerbType, Session: session, Paste: "alpha\nbeta"})
	if !resp.OK {
		t.Fatalf("a paste was refused: %s", resp.Error)
	}
	if got != "\x1b[200~alpha\nbeta\x1b[201~" {
		t.Errorf("agterm received %q; a paste must arrive between the markers", got)
	}

	// And the ordinary path is untouched: no envelope, and a newline still refused.
	got = ""
	if resp := h.Handle(context.Background(), Request{Verb: VerbType, Session: session, Text: "alpha"}); !resp.OK {
		t.Fatalf("ordinary text was refused: %s", resp.Error)
	}
	if got != "alpha" {
		t.Errorf("typed text arrived as %q; only a paste is wrapped", got)
	}
	if resp := h.Handle(context.Background(), Request{Verb: VerbType, Session: session, Text: "a\nb"}); resp.OK {
		t.Error("a newline was accepted as TYPING; that is a Return they did not press")
	}

	// Two of the three is a request nobody can serve, and it is about the request.
	both := h.Handle(context.Background(), Request{
		Verb: VerbType, Session: session, Text: "alpha", Paste: "beta",
	})
	if both.OK || both.Refusal != RefusalContent {
		t.Errorf("text and paste together: ok=%v refusal=%q", both.OK, both.Refusal)
	}
}

// **A refusal about the TEXT says so, and one about the laptop does not.**
//
// The owner pasted a Telegram message with line breaks in it and the phone replaced their whole
// screen with a full-page error, because `ok:false` was one channel carrying two unrelated kinds of
// no. This is the wire half of the fix: the reply now carries which kind it is, so the phone can
// leave them where they were for one and not for the other.
//
// Both directions in one test on purpose. The dangerous mistake is the generous one - marking a
// laptop-side failure as content - and a test that only checked the positive would not notice it.
func TestARefusalSaysWhetherItIsAboutTheRequestOrTheLaptop(t *testing.T) {
	const session = "4C9B5C9B-C77F-4913-8BEA-9DF7513AC8BA"

	t.Run("text with a line break is a content refusal", func(t *testing.T) {
		fake := agtermtest.Start(t, func(agtermtest.Request) any {
			t.Error("a text the bridge refuses must never reach agterm")
			return agtermtest.OK(map[string]any{})
		})
		h := New(agterm.New(fake.Path), t.TempDir())

		resp := h.Handle(context.Background(), Request{
			Verb: VerbType, Session: session, Text: "line one\nline two",
		})

		if resp.OK {
			t.Fatal("a newline was accepted; it would press Return on whatever was on their prompt")
		}
		if resp.Refusal != RefusalContent {
			t.Errorf("refusal is %q, want %q - the phone reads anything else as the connection dying",
				resp.Refusal, RefusalContent)
		}
	})

	t.Run("agterm being unreachable is NOT a content refusal", func(t *testing.T) {
		fake := agtermtest.Start(t, func(agtermtest.Request) any {
			return map[string]any{"ok": false, "error": "agterm is not running"}
		})
		h := New(agterm.New(fake.Path), t.TempDir())

		resp := h.Handle(context.Background(), Request{
			Verb: VerbType, Session: session, Text: "ls",
		})

		if resp.OK {
			t.Fatal("agterm refused and the bridge reported success")
		}
		if resp.Refusal != "" {
			t.Errorf("refusal is %q; a laptop-side failure marked as content would leave the phone "+
				"showing a small notice over a connection that is actually gone", resp.Refusal)
		}
	})
}
