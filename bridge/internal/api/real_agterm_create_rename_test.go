package api

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
)

// liveName is what this test renames its workspace to, and it is deliberately not a plausible name.
//
// The workspace this test creates **cannot be removed by the bridge** — workspace.delete is a
// forbidden verb and stays one — so a run leaves it behind on the owner's laptop. Labelling it makes
// the leftover self-identifying rather than something they find later and wonder about.
const liveName = "bos-live-check (safe to delete)"

// Runs the create and rename verbs against the REAL agterm on this machine. Skipped unless
// BOS_REAL_AGTERM=1.
//
// # What only this test can tell us
//
// Everything else about these verbs is asserted against a fake that answers however the test says. A
// fake cannot tell us that `workspace` is the argument key agterm reads, that a target belongs at the
// top level, or that `workspace.new` really does produce a workspace holding nothing. Those were
// measured by hand on 2026-07-31; this is the same measurement made by the code that ships, which is
// the only version of it that keeps being true.
//
// # What it does to the owner's machine, stated because it is not nothing
//
//   - It CREATES one workspace and one session. The session is closed here, by id, in the same
//     operation — the same provenance rule Client.CloseSession documents.
//   - **The workspace is left behind**, because nothing in this binary may delete one.
//   - Creating a session FOCUSES it, so the owner's selection moves. That is agterm's behaviour and
//     there is no flag for it; restoring their selection is done outside this test, by hand, and only
//     when they have not already moved somewhere else themselves.
//   - It renames only what it created. It never renames, closes, types into or selects anything that
//     was already there.
func TestRealAgtermCreateAndRename(t *testing.T) {
	if os.Getenv("BOS_REAL_AGTERM") != "1" {
		t.Skip("set BOS_REAL_AGTERM=1 to run against the live agterm")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	// **Its own directory, asserted rather than assumed** - the same guard the calibration test carries,
	// for the same reason: a test that writes where the owner's running bridge reads can break them.
	h := New(agterm.New(agterm.DefaultSocketPath()), t.TempDir())
	live := os.ExpandEnv("$HOME/.config/agterm-bridge/resize-cache.json")
	beforeLive, _ := os.Stat(live)
	t.Cleanup(func() {
		afterLive, _ := os.Stat(live)
		switch {
		case beforeLive == nil && afterLive != nil:
			t.Errorf("this test created %s - the owner's bridge reads that file", live)
		case beforeLive != nil && afterLive != nil && !beforeLive.ModTime().Equal(afterLive.ModTime()):
			t.Errorf("this test modified %s - the owner's bridge reads that file", live)
		}
	})

	baseline := listing(ctx, t, h)

	// --- create a workspace ------------------------------------------------------------------
	created := h.Handle(ctx, Request{Verb: VerbWorkspaceCreate})
	if !created.OK {
		t.Fatalf("workspace.create failed against the live agterm: %s", created.Error)
	}
	if created.Created == nil || created.Created.ID == "" {
		t.Fatal("workspace.create answered without an id")
	}
	workspace := created.Created.ID
	// **The default name is read from the LISTING, and this is the assertion that proves it has to be.**
	//
	// A create answers with an id and nothing else. The first version of this feature had the client
	// reading a `name` off the create reply - measured, but from the tree AFTER creating rather than
	// from the reply itself, and then written up as though those were one observation. Every
	// fake-backed test agreed, because the fake answered however it was told to. This is the assertion
	// that disagreed.
	fresh := listing(ctx, t, h)
	if got := findWorkspace(fresh.Workspaces, workspace); got == nil {
		t.Fatal("the workspace just created is not in the listing at all")
	} else if got.Name == "" {
		t.Error("the created workspace has no name in the listing either, so the phone's rename " +
			"dialog has nothing to open on")
	}

	// Label it, so a run that dies halfway still leaves something the owner can identify.
	if resp := h.Handle(ctx, Request{
		Verb: VerbWorkspaceRename, Workspace: workspace, Label: liveName,
	}); !resp.OK {
		t.Fatalf("workspace.rename failed against the live agterm: %s", resp.Error)
	}

	// **The assertion the whole wire decision rests on**, made against real agterm rather than a
	// fixture: a workspace with no sessions is published, and contributes no session rows.
	after := listing(ctx, t, h)
	if len(after.Sessions) != len(baseline.Sessions) {
		t.Errorf("creating an empty workspace changed the session count by %d, want 0",
			len(after.Sessions)-len(baseline.Sessions))
	}
	if len(after.Workspaces) != len(baseline.Workspaces)+1 {
		t.Fatalf("workspaces went from %d to %d, want exactly one more - an empty workspace must still "+
			"be published or the owner presses + and is shown nothing",
			len(baseline.Workspaces), len(after.Workspaces))
	}
	if got := findWorkspace(after.Workspaces, workspace); got == nil {
		t.Fatal("the workspace just created is not in the listing at all")
	} else if got.Name != liveName {
		t.Errorf("the rename did not take: the workspace's published name is not the one we set")
	}

	// --- create a session in it --------------------------------------------------------------
	made := h.Handle(ctx, Request{Verb: VerbSessionCreate, Workspace: workspace})
	if !made.OK {
		t.Fatalf("session.create failed against the live agterm: %s", made.Error)
	}
	if made.Created == nil || made.Created.ID == "" {
		t.Fatal("session.create answered without an id")
	}
	session := made.Created.ID

	// **Closed by id, in the same operation, from a variable** - the provenance rule, and the only
	// circumstance under which anything here may close a session at all.
	defer func() {
		if err := agterm.New(agterm.DefaultSocketPath()).CloseSession(context.Background(), session); err != nil {
			t.Errorf("could not close the session this test created: %v", err)
		}
	}()

	withSession := listing(ctx, t, h)
	row := findSession(withSession.Sessions, session)
	if row == nil {
		t.Fatal("the session just created is not in the listing")
	}
	if row.WorkspaceID != workspace {
		t.Error("the session was created in a different workspace than the one addressed")
	}

	// --- rename the session ------------------------------------------------------------------
	if resp := h.Handle(ctx, Request{
		Verb: VerbSessionRename, Session: session, Label: liveName,
	}); !resp.OK {
		t.Fatalf("session.rename failed against the live agterm: %s", resp.Error)
	}
	renamed := listing(ctx, t, h)
	if row := findSession(renamed.Sessions, session); row == nil {
		t.Error("the session disappeared after being renamed")
	} else if row.Name != liveName {
		t.Error("the rename did not take: the session's published name is not the one we set")
	}

	// --- a bad target is refused by the LIVE path too -----------------------------------------
	//
	// The fake proves the bridge does not send it. This proves the bridge would have had somewhere
	// dangerous to send it to: `active` is a real target agterm resolves, and the owner is sitting on
	// it. Nothing is sent, so nothing is renamed.
	if resp := h.Handle(ctx, Request{
		Verb: VerbSessionRename, Session: "active", Label: liveName,
	}); resp.OK {
		t.Fatal("a rename addressed to `active` was accepted against the live agterm")
	}
}

func listing(ctx context.Context, t *testing.T, h *Handler) Response {
	t.Helper()
	resp := h.Handle(ctx, Request{Verb: VerbSessions})
	if !resp.OK {
		t.Fatalf("listing failed against the live agterm: %s", resp.Error)
	}
	return resp
}

// The finders return the row rather than a bool so a failure can assert on a field without any test
// here printing a session name, a workspace name or the owner's tree.
func findWorkspace(in []Workspace, id string) *Workspace {
	for i := range in {
		if in[i].ID == id {
			return &in[i]
		}
	}
	return nil
}

func findSession(in []Session, id string) *Session {
	for i := range in {
		if in[i].ID == id {
			return &in[i]
		}
	}
	return nil
}
