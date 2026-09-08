package agterm

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm/agtermtest"
)

// The wire shape was read off agtermctl itself on 2026-09-05, by pointing `agtermctl sidebar width
// 300 --window W` at a socket that printed what arrived:
//
//	{"cmd":"sidebar.width","args":{"sidebarWidth":300,"window":"W"}}
//
// and the reply is `{"ok":true,"result":{"sidebarWidth":300.0}}` — the width AFTER clamping, which is
// how a clamped request is told from an honoured one, since both answer ok.
func TestSidebarWidthIsSentInPointsForOneWindow(t *testing.T) {
	fake := agtermtest.Start(t, func(agtermtest.Request) any {
		return agtermtest.OK(map[string]any{"sidebarWidth": 560.0})
	})

	applied, err := New(fake.Path).SetSidebarWidth(context.Background(), "W1", 612.25)
	if err != nil {
		t.Fatal(err)
	}
	if applied != 560 {
		t.Errorf("applied = %v, want the echoed 560 - the clamp is only visible in the echo", applied)
	}

	reqs := fake.Requests()
	if len(reqs) != 1 || reqs[0].Cmd != "sidebar.width" {
		t.Fatalf("sent %+v, want exactly one sidebar.width", reqs)
	}
	var args struct {
		SidebarWidth float64 `json:"sidebarWidth"`
		Window       string  `json:"window"`
	}
	if err := json.Unmarshal(reqs[0].Args, &args); err != nil {
		t.Fatal(err)
	}
	if args.SidebarWidth != 612.25 {
		t.Errorf("sent %v points, want 612.25 - a fractional width must survive the trip", args.SidebarWidth)
	}
	if args.Window != "W1" {
		t.Errorf("sent window %q, want W1 - the frontmost default is not the fit's window", args.Window)
	}
	if reqs[0].Target != "" {
		t.Errorf("target was %q; the window goes in args, not in target", reqs[0].Target)
	}
}

func TestASidebarReplyWithoutAWidthIsAnError(t *testing.T) {
	fake := agtermtest.Start(t, func(agtermtest.Request) any { return agtermtest.OK(map[string]any{}) })

	if _, err := New(fake.Path).SetSidebarWidth(context.Background(), "W1", 300); err == nil {
		t.Fatal("an ok with no width in it was taken as an applied width")
	}
}

// `tree --window W` sends `{"cmd":"tree","args":{"window":"W"}}`, and the tree it answers with carries
// the sidebar at its top level - the read-back the discussion asked for.
func TestTheTreeCanBeReadForOneWindow(t *testing.T) {
	fake := agtermtest.Start(t, func(agtermtest.Request) any {
		return agtermtest.OK(map[string]any{"tree": map[string]any{
			"sidebarWidth": 310.53125, "sidebarVisible": true,
			"workspaces": []any{map[string]any{"id": "ws", "sessions": []any{
				map[string]any{"id": "s1", "fontSize": 14, "split": true},
			}}},
		}})
	})

	tree, err := New(fake.Path).TreeOf(context.Background(), "W1")
	if err != nil {
		t.Fatal(err)
	}
	if tree.SidebarWidth != 310.53125 || !tree.SidebarVisible {
		t.Errorf("sidebar read back as %v visible=%v", tree.SidebarWidth, tree.SidebarVisible)
	}
	if got := tree.Workspaces[0].Sessions[0].FontSize; got != 14 {
		t.Errorf("font size %d, want 14", got)
	}

	reqs := fake.Requests()
	var args struct {
		Window string `json:"window"`
	}
	if err := json.Unmarshal(reqs[0].Args, &args); err != nil {
		t.Fatal(err)
	}
	if reqs[0].Cmd != "tree" || args.Window != "W1" {
		t.Errorf("sent %s with window %q, want tree for W1", reqs[0].Cmd, args.Window)
	}
}
