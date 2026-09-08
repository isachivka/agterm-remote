package api

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
	"github.com/isachivka/agterm-remote/bridge/internal/agterm/agtermtest"
)

const sessionA = "F2F9559C-BB15-4E8F-AA66-381FA0CDE9B9"

// A second id, for the case where two workspaces share a name and only the identity tells them apart.
const sessionB = "4C9B5C9B-C77F-4913-8BEA-9DF7513AC8BA"

func handler(t *testing.T, respond func(agtermtest.Request) any) (*Handler, *agtermtest.Fake) {
	t.Helper()
	fake := agtermtest.Start(t, respond)
	return New(agterm.New(fake.Path), t.TempDir()), fake
}

func tree() any {
	return map[string]any{"tree": map[string]any{"workspaces": []any{
		map[string]any{"id": "W1", "name": "main", "active": true, "sessions": []any{
			map[string]any{"id": sessionA, "name": "agterm", "title": "Create backup script", "active": true},
		}},
	}}}
}

// --- The allowlist, structurally ------------------------------------------------------------------

// The caller never supplies an agterm command. It names a verb from a closed set, and this package
// builds the agterm request itself — the closed set as a property rather than as a filter.
//
// The inputs below are agterm commands this bridge does **not** implement, including the one that
// runs a program of the caller's choosing and the one that selects a session behind the owner's back.
// Sending any of them must emit nothing at all.
//
// # Why `session.close` was removed from this list on 2026-07-31, and why that is not a weakening
//
// It used to sit here, and it belonged here: it named an agterm command that this bridge had no verb
// for, so sending it had to fall through to "unknown verb". The destructive verbs gave the bridge its own
// `session.close` verb, and the two names coincide — so sending it now emits `session.close`
// **because the bridge implements it**, not because a caller's string reached agterm.
//
// That distinction is the whole point of this test, so the input moved rather than the assertion
// being loosened. The property being defended is unchanged and is checked below: a verb the bridge
// does not implement produces NO agterm command whatsoever.
func TestNoInputCanProduceAnyOtherAgtermCommand(t *testing.T) {
	h, fake := handler(t, func(agtermtest.Request) any { return agtermtest.OK(tree()) })

	for _, verb := range []string{
		"session.overlay.open", "session.search", "session.split", "session.move",
		"restore.clear", "restore.run", "window.close", "workspace.select",
		"", "SESSIONS", "screen ", "session.text", "workspace.rename ",
	} {
		h.Handle(context.Background(), Request{Verb: verb, Session: sessionA, Workspace: workspaceA})
	}

	if got := fake.Requests(); len(got) != 0 {
		t.Fatalf("a verb this bridge does not implement reached agterm as %q", got[0].Cmd)
	}
}

// The control for the test above, and it exists because that one passes for free if the handler
// stopped emitting anything at all — a refactor that broke every verb would leave it green.
//
// So: a verb the bridge DOES implement must reach agterm. Same fake, same shape, opposite expectation.
func TestAVerbTheBridgeImplementsDoesReachAgterm(t *testing.T) {
	h, fake := handler(t, func(agtermtest.Request) any { return agtermtest.OK(tree()) })

	h.Handle(context.Background(), Request{Verb: VerbSessions})

	if got := fake.Requests(); len(got) != 1 || got[0].Cmd != "tree" {
		t.Fatalf("the sessions verb emitted %+v; the test above is guarding nothing", got)
	}
}

func TestUnknownVerbIsRefused(t *testing.T) {
	h, fake := handler(t, func(agtermtest.Request) any { return agtermtest.OK(tree()) })

	resp := h.Handle(context.Background(), Request{Verb: "screens"})
	if resp.OK {
		t.Fatal("an unknown verb must be refused")
	}
	if len(fake.Requests()) != 0 {
		t.Fatal("an unknown verb must not reach agterm at all")
	}
}

// --- sessions -------------------------------------------------------------------------------------

func TestSessionsFlattensTheTree(t *testing.T) {
	h, _ := handler(t, func(agtermtest.Request) any { return agtermtest.OK(tree()) })

	resp := h.Handle(context.Background(), Request{Verb: VerbSessions})
	if !resp.OK {
		t.Fatalf("refused: %s", resp.Error)
	}
	if len(resp.Sessions) != 1 {
		t.Fatalf("expected 1 session, got %d", len(resp.Sessions))
	}
	got := resp.Sessions[0]
	if got.ID != sessionA || got.Workspace != "main" || got.Name != "agterm" || !got.Active {
		t.Fatalf("unexpected session: %+v", got)
	}
}

// --- screen ---------------------------------------------------------------------------------------

// `target` must be top level and `lines` must be sent. Both are load-bearing against the real agterm:
// a target inside args is silently ignored and falls back to the ACTIVE session, and an omitted
// `lines` reads the visible screen, which on a live Claude session is nearly blank because the
// transcript lives in scrollback.
func TestScreenAddressesTheRequestedSessionAndAsksForLines(t *testing.T) {
	h, fake := handler(t, func(agtermtest.Request) any {
		return agtermtest.OK(map[string]any{"text": "hello"})
	})

	resp := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: sessionA})
	if !resp.OK {
		t.Fatalf("refused: %s", resp.Error)
	}

	reqs := fake.Requests()
	if len(reqs) != 1 {
		t.Fatalf("expected 1 agterm request, got %d", len(reqs))
	}
	if reqs[0].Cmd != "session.text" {
		t.Fatalf("cmd = %q", reqs[0].Cmd)
	}
	if reqs[0].Target != sessionA {
		t.Fatalf("target must be the requested session at the TOP level, got %q", reqs[0].Target)
	}
	var args struct {
		Lines int `json:"lines"`
	}
	if err := json.Unmarshal(reqs[0].Args, &args); err != nil {
		t.Fatalf("args: %v", err)
	}
	if args.Lines != DefaultLines {
		t.Fatalf("lines must always be sent, got %d", args.Lines)
	}
}

// agterm resolves a target by id, by unique prefix, or by the literal `active` — and an ABSENT target
// silently defaults to `active`. Requiring a full UUID closes all of that without the extra blocking
// round trip a tree lookup would cost on every poll.
func TestScreenRefusesAnythingThatIsNotASessionID(t *testing.T) {
	h, fake := handler(t, func(agtermtest.Request) any {
		return agtermtest.OK(map[string]any{"text": "x"})
	})

	for _, bad := range []string{"", "active", "F2F9559C", "next", "../../etc/passwd",
		strings.Repeat("a", 36), "F2F9559C-BB15-4E8F-AA66-381FA0CDE9B!"} {
		if resp := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: bad}); resp.OK {
			t.Errorf("session %q must be refused", bad)
		}
	}
	if len(fake.Requests()) != 0 {
		t.Fatal("a rejected session id must not become an agterm request")
	}
}

func TestScreenBoundsLines(t *testing.T) {
	h, _ := handler(t, func(agtermtest.Request) any {
		return agtermtest.OK(map[string]any{"text": "x"})
	})

	for _, lines := range []int{-1, MaxLines + 1, 100000} {
		resp := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: sessionA, Lines: lines})
		if resp.OK {
			t.Errorf("lines=%d must be refused", lines)
		}
	}
	if resp := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: sessionA, Lines: MaxLines}); !resp.OK {
		t.Errorf("lines=%d is the ceiling and must be allowed: %s", MaxLines, resp.Error)
	}
}

// --- the digest -----------------------------------------------------------------------------------

// Measured on a live idle Claude session: 0 of 19 transitions changed over ten seconds at 2 Hz,
// 105 KB moved to convey nothing. The transport has no output event, so polling is mandatory and only
// the far end can make it cheap.
func TestMatchingDigestReturnsUnchangedWithoutText(t *testing.T) {
	h, _ := handler(t, func(agtermtest.Request) any {
		return agtermtest.OK(map[string]any{"text": "steady state"})
	})

	first := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: sessionA})
	if first.Text == nil || first.Digest == "" {
		t.Fatal("the first read must carry text and a digest")
	}

	second := h.Handle(context.Background(),
		Request{Verb: VerbScreen, Session: sessionA, Digest: first.Digest})
	if !second.Unchanged {
		t.Fatal("an unchanged screen must answer unchanged")
	}
	if second.Text != nil {
		t.Fatal("an unchanged answer must carry no text")
	}
	if second.Digest != first.Digest {
		t.Fatal("the digest must still be returned so the caller can keep polling with it")
	}
}

func TestChangedScreenReturnsTextEvenWhenADigestIsSupplied(t *testing.T) {
	text := "before"
	h, _ := handler(t, func(agtermtest.Request) any {
		return agtermtest.OK(map[string]any{"text": text})
	})

	first := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: sessionA})
	text = "after"
	second := h.Handle(context.Background(),
		Request{Verb: VerbScreen, Session: sessionA, Digest: first.Digest})

	if second.Unchanged {
		t.Fatal("a changed screen must not answer unchanged")
	}
	if second.Text == nil || *second.Text != "after" {
		t.Fatalf("expected the new text, got %v", second.Text)
	}
}

// The digest is over exactly the bytes returned. A digest over trimmed or normalised text could match
// while the real content differed, showing the owner a stale screen with no way to tell.
func TestDigestCoversExactlyTheReturnedBytes(t *testing.T) {
	text := "line\n"
	h, _ := handler(t, func(agtermtest.Request) any {
		return agtermtest.OK(map[string]any{"text": text})
	})
	first := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: sessionA})

	// Whitespace-only difference: trimming anywhere in the pipeline would make this match.
	text = "line"
	second := h.Handle(context.Background(),
		Request{Verb: VerbScreen, Session: sessionA, Digest: first.Digest})

	if second.Unchanged {
		t.Fatal("a trailing-newline change is a change")
	}
}

// --- failure -------------------------------------------------------------------------------------

// When the laptop is not answering the app says so and does not invent a network
// diagnosis. That copy is only writable if this distinction survives to the response.
func TestAgtermNotRunningIsReportedAsSuch(t *testing.T) {
	h := New(agterm.New("/nonexistent/agterm.sock"), t.TempDir())

	resp := h.Handle(context.Background(), Request{Verb: VerbSessions})
	if resp.OK {
		t.Fatal("expected a failure")
	}
	if resp.Error != "agterm is not answering" {
		t.Fatalf("the phone must be able to say the laptop is not answering, got %q", resp.Error)
	}
}

// **This test used to assert the opposite, and the reversal is the fix.**
//
// It read: *"agterm's own description should survive"*, and it was right about every verb except this
// one. On 2026-08-12 the owner opened a session and their entire screen was
// `failed to read surface buffer` over a button — agterm describing its internals, relayed to a
// person, on an otherwise empty screen.
//
// The words are ours now; agterm's survive in `detail`, which is where somebody debugging looks and
// nobody reading their phone has to.
func TestAFailedReadIsExplainedInOurOwnWords(t *testing.T) {
	// Measured against the live agterm on 2026-08-12, over the same socket the bridge uses, with a
	// throwaway session created and closed for the purpose. These are not invented strings.
	cases := []struct {
		name       string
		agtermSays string
		wantError  string
		wantDetail string
	}{{
		name:       "a session that exists but was never rendered",
		agtermSays: "failed to read surface buffer",
		wantError:  "Your laptop has not opened this session yet. Open it on the Mac, then try here.",
		wantDetail: "failed to read surface buffer",
	}, {
		name:       "a session that has been closed",
		agtermSays: "no such session: " + sessionA,
		// The rewrite that already existed for close and rename, on the path that never called it.
		// No detail: the raw text is a UUID, which belongs in no screen and helps no one.
		wantError:  "That session is no longer on your laptop. Pull to refresh the list.",
		wantDetail: "",
	}, {
		// **The load-bearing case.** July's wording for the unrendered session was
		// `session not realized`; today's is `failed to read surface buffer`. It changed underneath
		// us in a month, and the next change is the one this row is for.
		name:       "a sentence we have never seen",
		agtermSays: "session not realized",
		wantError:  "Your laptop refused to open this session.",
		wantDetail: "session not realized",
	}}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			h, _ := handler(t, func(agtermtest.Request) any { return agtermtest.Err(c.agtermSays) })

			resp := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: sessionA})

			if resp.OK {
				t.Fatal("expected a failure")
			}
			if resp.Error != c.wantError {
				t.Errorf("the owner reads\n  got  %q\n  want %q", resp.Error, c.wantError)
			}
			if resp.Detail != c.wantDetail {
				t.Errorf("the evidence kept for us\n  got  %q\n  want %q", resp.Detail, c.wantDetail)
			}
			// Whatever we show, agterm's sentence must not BE the message.
			if resp.Error == c.agtermSays {
				t.Errorf("the far end's own words reached the screen: %q", resp.Error)
			}
		})
	}
}

// The one sentence on this path that was already written for a person stays exactly as it was: it is
// ours, it is about the laptop rather than the session, and wrapping it would say less.
func TestNotAnsweringIsUnchangedAndCarriesNoDetail(t *testing.T) {
	// A socket that is not there, the same way TestAgtermNotRunningIsReportedAsSuch does it.
	h := New(agterm.New("/nonexistent/agterm.sock"), t.TempDir())

	resp := h.Handle(context.Background(), Request{Verb: VerbScreen, Session: sessionA})

	if resp.Error != "agterm is not answering" {
		t.Fatalf("got %q", resp.Error)
	}
	if resp.Detail != "" {
		t.Fatalf("nothing to keep: %q", resp.Detail)
	}
}

// --- decoding -------------------------------------------------------------------------------------

// agterm's own ControlArgs ignores unknown keys, which is how a `target` placed in the wrong object
// silently addressed the wrong session. This bridge refuses them instead.
func TestDecodeRefusesUnknownFields(t *testing.T) {
	if _, err := Decode([]byte(`{"verb":"screen","cmd":"session.overlay.open"}`)); err == nil {
		t.Fatal("an unknown field must be refused, not ignored")
	}
	if _, err := Decode([]byte(`{"verb":"screen","session":"x"}`)); err != nil {
		t.Fatalf("a well-formed request must decode: %v", err)
	}
}

// **The hierarchy agterm holds, carried rather than flattened away.**
//
// The owner: their session list on the phone is flat while agterm's own tree is not. The tree is one
// level - workspaces hold sessions - and the bridge was already walking it, stamping each session with
// the workspace NAME and dropping the identity.
//
// The id is what the phone groups by. Two workspaces can share a name, and grouping by name merges
// them into one heading, which shows a session under a workspace it is not in.
//
// No name of anything appears in this test: the fixture's ids are what is asserted, and the names are
// only checked for being carried through, never for their value.
func TestTheWorkspaceIdentityRidesWithEverySession(t *testing.T) {
	fake := agtermtest.Start(t, func(agtermtest.Request) any {
		return agtermtest.OK(map[string]any{"tree": map[string]any{"workspaces": []any{
			// Two workspaces with the SAME name and different ids - the case grouping by name gets
			// wrong, and the reason the id is on the wire at all.
			map[string]any{"id": "W1", "name": "same", "sessions": []any{
				map[string]any{"id": sessionA, "name": "one"},
			}},
			map[string]any{"id": "W2", "name": "same", "sessions": []any{
				map[string]any{"id": sessionB, "name": "two"},
			}},
		}}})
	})
	h := New(agterm.New(fake.Path), t.TempDir())

	resp := h.Handle(context.Background(), Request{Verb: VerbSessions})
	if !resp.OK || len(resp.Sessions) != 2 {
		t.Fatalf("got %+v", resp)
	}
	if resp.Sessions[0].WorkspaceID != "W1" || resp.Sessions[1].WorkspaceID != "W2" {
		t.Errorf("workspace identities lost: %q and %q",
			resp.Sessions[0].WorkspaceID, resp.Sessions[1].WorkspaceID)
	}
	// And ORDER is agterm's, workspaces and sessions both: the phone renders the structure that
	// exists rather than sorting it into something we think is nicer.
	if resp.Sessions[0].ID != sessionA || resp.Sessions[1].ID != sessionB {
		t.Errorf("the order changed on the way through")
	}
}

// The field must ride even when it is empty, or "this session's workspace is unknown" and "there is
// no workspace" become the same thing on the wire - the omitempty lesson, applied before it bites.
func TestTheWorkspaceIdentityIsNotOmittedWhenEmpty(t *testing.T) {
	raw, err := json.Marshal(Session{ID: sessionA})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(raw), `"workspace_id"`) {
		t.Errorf("an empty workspace id vanished from the wire: %s", raw)
	}
}

// --- status -----------------------------------------------------------------------------------------

// **The four states agterm has, and the fifth thing that is not a state.**
//
// The design draws a dot per session: running, waiting on the person, finished, idle. agterm already
// holds all four - its agent hooks write them - and this bridge decoded four fields and dropped the
// one that says which.
//
// The values were established from agterm's own binary rather than from a sample: the enum
// `idle · active · completed · blocked` sits in its string table beside `statusPane`, and again in the
// hook block that writes it. A live tree of 32 sessions showed 28 absent, 3 `active` and 1 `completed`
// and no `blocked` at all - **which is why the binary was read**. A sample that lacks a value is not
// evidence the value does not exist, and treating it as such is what broke the fit on the owner's
// machine on 2026-07-31.
//
// The last row is the whole point of `status`: an unknown value is published as idle. It is not passed
// through, and it is never guessed into one of the other three.
//
// Session and workspace names here are invented. Status is a SHAPE and may appear in a test; what
// agterm's sessions are called may not.
func TestEveryKnownStatusIsPublishedAndEverythingElseIsIdle(t *testing.T) {
	for _, c := range []struct {
		sent string
		want string
	}{
		{"active", "active"},
		{"blocked", "blocked"},
		{"completed", "completed"},
		{"idle", ""},    // agterm never sends this - it omits instead - but it is in the enum
		{"", ""},        // what an idle session actually looks like on the wire
		{"Active", ""},  // case is not normalised; a near-miss is not a state
		{"waiting", ""}, // a value a later agterm might invent
		{"active blocked", ""},
	} {
		session := map[string]any{"id": sessionA, "name": "one"}
		if c.sent != "" {
			session["status"] = c.sent
		}
		fake := agtermtest.Start(t, func(agtermtest.Request) any {
			return agtermtest.OK(map[string]any{"tree": map[string]any{"workspaces": []any{
				map[string]any{"id": "W1", "name": "first", "sessions": []any{session}},
			}}})
		})
		h := New(agterm.New(fake.Path), t.TempDir())

		resp := h.Handle(context.Background(), Request{Verb: VerbSessions})
		// The positive control, and it is here because its absence has cost this project a day: a
		// test that asserts a field is empty passes just as well when the reply was a refusal and
		// there is no session at all.
		if !resp.OK || len(resp.Sessions) != 1 {
			t.Fatalf("status %q: the fixture itself did not answer: %+v", c.sent, resp)
		}
		if got := resp.Sessions[0].Status; got != c.want {
			t.Errorf("agterm said %q, the bridge published %q, want %q", c.sent, got, c.want)
		}
	}
}

// Always on the wire, exactly like the workspace id, and for a rule rather than for this field's own
// needs: a field whose zero value is meaningful is never omitempty. Empty and absent both mean idle
// today; that coincidence is not a property anyone is holding, and FitEnabled cost the owner three
// presses on a dead button to establish what happens when an encoding cannot say a state.
func TestStatusIsNotOmittedWhenTheSessionIsIdle(t *testing.T) {
	raw, err := json.Marshal(Session{ID: sessionA})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(raw), `"status"`) {
		t.Errorf("an idle session said nothing at all about its status: %s", raw)
	}
}
