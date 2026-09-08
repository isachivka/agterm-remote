package api

import (
	"context"
	"testing"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
)

// **Every reply carries the width setting.** It used to ride only on responses to `resize`, and the
// phone gates its button on having seen it - so learning the state required pressing, and pressing
// required knowing the state. A deadlock by construction, and it cost the owner three builds.
//
// This asserts the property for every verb rather than for the two that happened to have it, because
// the defect was a verb that forgot.
func TestEveryReplyCarriesTheWidthSetting(t *testing.T) {
	// A client pointed at a socket that does not exist: every verb then FAILS, which is the harder
	// case - the setting must ride even on a failure reply, or a phone that cannot reach agterm can
	// never learn the state either.
	h := New(agterm.New(t.TempDir()+"/absent.sock"), t.TempDir())

	// The list is every verb the package defines, and it grows with them — the four creating and
	// renaming verbs are here for the same reason the first five are: the defect this guards was one verb that forgot.
	for _, verb := range []string{
		VerbSessions, VerbScreen, VerbResize, VerbType, VerbFile,
		VerbWorkspaceCreate, VerbSessionCreate, VerbWorkspaceRename, VerbSessionRename,
		VerbSessionClose, VerbWorkspaceDelete,
		"nonsense",
	} {
		resp := h.Handle(context.Background(), Request{Verb: verb})

		if resp.FitEnabled == nil {
			t.Errorf("the %q reply carries no width setting; a phone that has not pressed the button "+
				"can never learn the state, and the button is gated on knowing it", verb)
		}
	}
}
