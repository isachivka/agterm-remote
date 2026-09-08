package api

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"dev.isachivka.bewareofsugar/bridge/internal/agterm/agtermtest"
)

// The four verbs of REQ-0011, and the property that matters most about three of them: **a request that
// should be refused never reaches agterm at all.**
//
// The fake records every request it is sent, so "the bridge did not call it" is asserted directly
// rather than inferred from an error message that could have come from anywhere.

const workspaceA = "3C1D5E7A-9B2F-4D6C-8E1A-5F7B9C3D1E4A"

// A workspace id that is a PREFIX of a real one. agterm resolves a unique prefix, so this is the
// value that would silently work against the real socket while addressing something the bridge never
// named.
const workspacePrefix = "3C1D5E7A"

func rejects(t *testing.T, req Request, wantErrorAbout string) {
	t.Helper()
	h, fake := handler(t, func(agtermtest.Request) any {
		t.Error("the bridge sent a request to agterm for input it should have refused")
		return agtermtest.Err("this should never have been called")
	})
	resp := h.Handle(context.Background(), req)

	if resp.OK {
		t.Fatalf("%q with bad input answered ok", req.Verb)
	}
	if !strings.Contains(resp.Error, wantErrorAbout) {
		t.Errorf("the error was %q, which does not mention %q - so the wrong check refused it",
			resp.Error, wantErrorAbout)
	}
	if got := fake.Requests(); len(got) != 0 {
		t.Errorf("%d request(s) reached agterm; a refusal must be decided before the socket", len(got))
	}
}

// **The failure this validation exists for is not a bad name, it is a right name on the wrong thing.**
//
// An absent or partial target resolves to `active` on agterm's side, so a rename that arrived without
// a full id would rename whatever the owner happens to be looking at, and agterm would answer ok.
func TestARenameWithABadTargetNeverReachesAgterm(t *testing.T) {
	for _, bad := range []string{"", "active", workspacePrefix, "not-a-uuid", workspaceA + "extra"} {
		rejects(t, Request{Verb: VerbWorkspaceRename, Workspace: bad, Label: "notes"}, "workspace")
	}
	for _, bad := range []string{"", "active", sessionA[:8], "not-a-uuid"} {
		rejects(t, Request{Verb: VerbSessionRename, Session: bad, Label: "notes"}, "session")
	}
}

// The same for creating a session: the workspace is a target too, and `active` is the same trap.
func TestCreatingASessionWithABadWorkspaceNeverReachesAgterm(t *testing.T) {
	for _, bad := range []string{"", "active", workspacePrefix, "not-a-uuid"} {
		rejects(t, Request{Verb: VerbSessionCreate, Workspace: bad}, "workspace")
	}
}

func TestARenameWithABadNameNeverReachesAgterm(t *testing.T) {
	for _, bad := range []string{"", "   ", "two\nlines", "esc\x1b[31m", strings.Repeat("a", 65)} {
		rejects(t, Request{Verb: VerbWorkspaceRename, Workspace: workspaceA, Label: bad}, "name")
		rejects(t, Request{Verb: VerbSessionRename, Session: sessionA, Label: bad}, "name")
	}
}

// **The id is checked BEFORE the name, and the order is not cosmetic.**
//
// Both are wrong here. If the name were checked first the owner would fix their name, resend, and only
// then discover the target problem - having in the meantime been told nothing about the dangerous half
// of the request.
func TestTheTargetIsCheckedBeforeTheName(t *testing.T) {
	h, _ := handler(t, func(agtermtest.Request) any {
		t.Error("neither check refused a request that was wrong twice over")
		return agtermtest.Err("unreachable")
	})
	resp := h.Handle(context.Background(), Request{Verb: VerbWorkspaceRename, Workspace: "active", Label: ""})

	if !strings.Contains(resp.Error, "workspace") {
		t.Errorf("the error was %q; the target is the more dangerous of the two failures and is named first",
			resp.Error)
	}
}

// **A name is never logged, never persisted, and never put in an error message.**
//
// keys.Label holds this at its own boundary; this holds it at the one the phone actually talks to,
// because a wrapper that helpfully added the value would defeat it without touching that package.
func TestARejectedNameIsNeverEchoedBackToTheCaller(t *testing.T) {
	secret := strings.Repeat("хозяйская", 9)
	h, _ := handler(t, func(agtermtest.Request) any {
		t.Error("a name that should have been refused reached agterm")
		return agtermtest.Err("unreachable")
	})
	resp := h.Handle(context.Background(), Request{Verb: VerbSessionRename, Session: sessionA, Label: secret})

	if strings.Contains(resp.Error, secret) || strings.Contains(resp.Error, "хозяйская") {
		t.Errorf("the reply contains the rejected name: %q", resp.Error)
	}
}

// The wire shape, which the fake's own comment warns about: `target` is a TOP-LEVEL field. Putting it
// inside args is a silent no-op against the real agterm that falls back to the active session — the
// exact bug this package validates ids to prevent, arriving by a different route.
func TestARenameSendsTheTargetAtTopLevelAndTheNameInArgs(t *testing.T) {
	h, fake := handler(t, func(agtermtest.Request) any { return agtermtest.OK(map[string]any{}) })

	resp := h.Handle(context.Background(), Request{
		Verb: VerbWorkspaceRename, Workspace: workspaceA, Label: "  release notes  ",
	})
	if !resp.OK {
		t.Fatalf("a valid rename failed: %s", resp.Error)
	}

	got := fake.Requests()
	if len(got) != 1 {
		t.Fatalf("expected exactly one agterm request, got %d", len(got))
	}
	if got[0].Cmd != "workspace.rename" {
		t.Errorf("sent %q", got[0].Cmd)
	}
	if got[0].Target != workspaceA {
		t.Errorf("target was %q, want it at the top level and equal to the id we validated", got[0].Target)
	}
	var args struct {
		Name    string `json:"name"`
		Command string `json:"command"`
		Target  string `json:"target"`
	}
	if err := json.Unmarshal(got[0].Args, &args); err != nil {
		t.Fatal(err)
	}
	// Trimmed by keys.Label on the way through, so the laptop gets the name the owner meant rather
	// than the whitespace their phone keyboard added.
	if args.Name != "release notes" {
		t.Errorf("args.name was %q, want the trimmed label", args.Name)
	}
	if args.Target != "" {
		t.Error("target was ALSO placed inside args, which silently addresses the active session")
	}
	if args.Command != "" {
		t.Error("a rename carried a command")
	}
}

// **Creating a session sends a workspace and nothing else.**
//
// No command and no cwd, and the phone has no field to supply either. A command arriving from the wire
// is the thing the whole allowlist exists to prevent, so it is asserted at the point the request is
// built rather than trusted to the absence of a Request field.
func TestCreatingASessionSendsAWorkspaceAndNothingElse(t *testing.T) {
	// **The fake answers exactly as the real agterm does: an id and nothing else.** It used to answer
	// with a name too, which is how a client that read one passed here and failed against the live
	// socket. A fake that is more generous than the thing it stands in for tests nothing.
	h, fake := handler(t, func(agtermtest.Request) any {
		return agtermtest.OK(map[string]any{"id": sessionB})
	})

	resp := h.Handle(context.Background(), Request{Verb: VerbSessionCreate, Workspace: workspaceA})
	if !resp.OK {
		t.Fatalf("creating a session failed: %s", resp.Error)
	}
	if resp.Created == nil || resp.Created.ID != sessionB {
		t.Fatalf("the reply did not carry the created id: %+v", resp.Created)
	}

	got := fake.Requests()
	if len(got) != 1 || got[0].Cmd != "session.new" {
		t.Fatalf("expected one session.new, got %+v", got)
	}
	var args map[string]any
	if err := json.Unmarshal(got[0].Args, &args); err != nil {
		t.Fatal(err)
	}
	if args["workspace"] != workspaceA {
		t.Errorf("args.workspace was %v", args["workspace"])
	}
	for _, forbidden := range []string{"command", "cwd", "name", "workspaceName", "createWorkspace"} {
		if _, ok := args[forbidden]; ok {
			t.Errorf("session.new carried %q; it sends a workspace id and nothing else", forbidden)
		}
	}
}

// Creating a workspace sends no name at all — the owner names it by renaming it, and a default
// invented here would sit in their sidebar if they cancelled the dialog.
func TestCreatingAWorkspaceSendsNoName(t *testing.T) {
	h, fake := handler(t, func(agtermtest.Request) any {
		return agtermtest.OK(map[string]any{"id": workspaceA})
	})

	resp := h.Handle(context.Background(), Request{Verb: VerbWorkspaceCreate})
	if !resp.OK {
		t.Fatalf("creating a workspace failed: %s", resp.Error)
	}
	if resp.Created == nil || resp.Created.ID != workspaceA {
		t.Fatalf("the reply did not carry what agterm made: %+v", resp.Created)
	}

	got := fake.Requests()
	if len(got) != 1 || got[0].Cmd != "workspace.new" {
		t.Fatalf("expected one workspace.new, got %+v", got)
	}
	if len(got[0].Args) != 0 && string(got[0].Args) != "null" {
		var args map[string]any
		if err := json.Unmarshal(got[0].Args, &args); err != nil {
			t.Fatal(err)
		}
		if len(args) != 0 {
			t.Errorf("workspace.new carried arguments: %v", args)
		}
	}
}

// **The destructive verbs, REQ-0012.** A close or a delete aimed at anything that is not a canonical
// UUID never reaches agterm — because `active` is a target agterm resolves, and the owner is sitting
// on it. This is the one validation in this package where being wrong costs work that does not come
// back.
func TestADestructiveVerbWithABadTargetNeverReachesAgterm(t *testing.T) {
	for _, bad := range []string{"", "active", sessionA[:8], "not-a-uuid", sessionA + "x"} {
		rejects(t, Request{Verb: VerbSessionClose, Session: bad}, "session")
	}
	for _, bad := range []string{"", "active", workspacePrefix, "not-a-uuid"} {
		rejects(t, Request{Verb: VerbWorkspaceDelete, Workspace: bad}, "workspace")
	}
}

// The wire shape, for the two calls where sending it wrong destroys something. `target` is top-level;
// inside args it is a silent no-op that falls back to the ACTIVE session — which here would mean
// closing whatever the owner is working in rather than the row they pressed.
func TestADestructiveVerbSendsItsTargetAtTheTopLevel(t *testing.T) {
	for _, c := range []struct {
		verb, cmd string
		req       Request
	}{
		{VerbSessionClose, "session.close", Request{Verb: VerbSessionClose, Session: sessionA}},
		{VerbWorkspaceDelete, "workspace.delete", Request{Verb: VerbWorkspaceDelete, Workspace: workspaceA}},
	} {
		h, fake := handler(t, func(agtermtest.Request) any { return agtermtest.OK(map[string]any{}) })

		resp := h.Handle(context.Background(), c.req)
		if !resp.OK {
			t.Fatalf("%s failed: %s", c.verb, resp.Error)
		}
		got := fake.Requests()
		if len(got) != 1 || got[0].Cmd != c.cmd {
			t.Fatalf("expected one %s, got %+v", c.cmd, got)
		}
		want := c.req.Session
		if want == "" {
			want = c.req.Workspace
		}
		if got[0].Target != want {
			t.Errorf("%s sent target %q at the top level, want %q", c.cmd, got[0].Target, want)
		}
		if len(got[0].Args) != 0 && string(got[0].Args) != "null" {
			var args map[string]any
			if err := json.Unmarshal(got[0].Args, &args); err != nil {
				t.Fatal(err)
			}
			if _, ok := args["target"]; ok {
				t.Errorf("%s put target inside args, which silently addresses the active session", c.cmd)
			}
		}
	}
}

// A refusal from agterm — the last workspace, a target that vanished — is a sentence the owner reads.
// Nothing here turns a refusal into a success, which for a destructive verb would tell them their
// session is gone when it is not.
func TestARefusedDeleteIsReportedRatherThanSwallowed(t *testing.T) {
	h, _ := handler(t, func(agtermtest.Request) any {
		return agtermtest.Err("cannot delete the last workspace")
	})

	resp := h.Handle(context.Background(), Request{Verb: VerbWorkspaceDelete, Workspace: workspaceA})

	if resp.OK {
		t.Fatal("a refused delete answered ok; the owner would think their workspace was gone")
	}
	if resp.Error != "cannot delete the last workspace" {
		t.Errorf("the bridge edited agterm's refusal: %q", resp.Error)
	}
}

// **A target that vanished reads as something that happened to the session, not as a broken app.**
//
// The owner's real case: they long-press a row, and between the last poll and the press the session
// was closed on the Mac. The list is a photograph and this is what a stale one looks like.
//
// The error text here is agterm's own, measured against the live socket on 2026-07-31 — including the
// raw id, which is half the problem: it lands on a full-screen refusal, and `no such session:
// 00000000-…` reads as an internal fault.
func TestATargetThatVanishedReadsAsAStaleList(t *testing.T) {
	const agtermSays = "no such session: 00000000-1111-2222-3333-444444444444"
	h, _ := handler(t, func(agtermtest.Request) any { return agtermtest.Err(agtermSays) })

	resp := h.Handle(context.Background(), Request{
		Verb: VerbSessionRename, Session: sessionA, Label: "notes",
	})

	if resp.OK {
		t.Fatal("a rename of a session that does not exist answered ok")
	}
	if strings.Contains(resp.Error, "00000000") {
		t.Errorf("the refusal puts a raw id on the owner's screen: %q", resp.Error)
	}
	if strings.Contains(resp.Error, "no such") {
		t.Errorf("the refusal is still agterm's internal wording: %q", resp.Error)
	}
	if !strings.Contains(resp.Error, "no longer on your laptop") {
		t.Errorf("the refusal does not say what happened: %q", resp.Error)
	}
}

// **Everything else agterm says is still passed through untouched**, which is the house rule this
// bridge has had all along: quote the far end, never diagnose it.
//
// This is also the control for the test above. If the rewrite ever widened to catch more than the one
// measured message, that test would keep passing while the bridge quietly started editing agterm's
// words - so the boundary is asserted from the other side.
func TestEveryOtherRefusalIsPassedThroughWordForWord(t *testing.T) {
	const agtermSays = "the window is not open"
	h, _ := handler(t, func(agtermtest.Request) any { return agtermtest.Err(agtermSays) })

	resp := h.Handle(context.Background(), Request{
		Verb: VerbSessionRename, Session: sessionA, Label: "notes",
	})

	if resp.Error != agtermSays {
		t.Errorf("the bridge edited agterm's words: got %q, want %q", resp.Error, agtermSays)
	}
}

// **The reason Response.Workspaces exists**, stated as a test rather than only as a comment.
//
// A workspace holding no sessions produces no rows in the session list, so before this field the owner
// would press + and be shown nothing at all. Measured against the live socket on 2026-07-31:
// `workspace.new` returns exactly this — a workspace with zero sessions.
func TestAnEmptyWorkspaceIsPublishedEvenThoughItHasNoSessionRows(t *testing.T) {
	h, _ := handler(t, func(agtermtest.Request) any {
		return agtermtest.OK(map[string]any{"tree": map[string]any{"workspaces": []any{
			map[string]any{"id": "W1", "name": "main", "sessions": []any{
				map[string]any{"id": sessionA, "name": "agterm"},
			}},
			// The one that could not be said before.
			map[string]any{"id": "W2", "name": "brand new", "sessions": []any{}},
		}}})
	})

	resp := h.Handle(context.Background(), Request{Verb: VerbSessions})
	if !resp.OK {
		t.Fatalf("listing failed: %s", resp.Error)
	}
	if len(resp.Sessions) != 1 {
		t.Errorf("an empty workspace contributed %d session rows, want 0 extra", len(resp.Sessions)-1)
	}
	if len(resp.Workspaces) != 2 {
		t.Fatalf("workspaces published: %d, want both including the empty one", len(resp.Workspaces))
	}
	// **Order is agterm's**, not the order a first session happened to appear in - which is what the
	// phone had to approximate while this field did not exist.
	if resp.Workspaces[0].ID != "W1" || resp.Workspaces[1].ID != "W2" {
		t.Errorf("workspaces came back in the wrong order: %+v", resp.Workspaces)
	}
	if resp.Workspaces[1].Name != "brand new" {
		t.Errorf("the empty workspace lost its name: %q", resp.Workspaces[1].Name)
	}
}
