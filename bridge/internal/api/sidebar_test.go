package api

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm/agtermtest"
)

// The adapter reads the laptop from the tree and sets the sidebar in points; the resize
// package sees milli-points and nothing of agterm.

func TestTheAdapterReadsTheLaptopFromTheTreeOfTheFitsWindow(t *testing.T) {
	h, fake := handler(t, func(agtermtest.Request) any {
		return agtermtest.OK(map[string]any{"tree": map[string]any{
			"sidebarWidth": 310.53125, "sidebarVisible": true,
			"workspaces": []any{
				map[string]any{"id": "ws1", "sessions": []any{
					map[string]any{"id": "a", "fontSize": 13}, map[string]any{"id": "b", "fontSize": 13},
				}},
				map[string]any{"id": "ws2", "sessions": []any{
					map[string]any{"id": "c", "fontSize": 20}, map[string]any{"id": "d"},
				}},
			},
		}})
	})

	laptop, err := (terminal{client: h.client}).Laptop(context.Background(), "W7")
	if err != nil {
		t.Fatal(err)
	}
	if laptop.SidebarWidthMilli != 310_531 || !laptop.SidebarVisible {
		t.Errorf("sidebar read as %d visible=%v; want 310531 milli-points, visible", laptop.SidebarWidthMilli, laptop.SidebarVisible)
	}
	if laptop.FontSize != 13 {
		t.Errorf("font %d; want the 13 most sessions use, not the 20 one session uses and not the 0 of one that says nothing", laptop.FontSize)
	}

	reqs := fake.Requests()
	var args struct {
		Window string `json:"window"`
	}
	if len(reqs) != 1 || reqs[0].Cmd != "tree" || json.Unmarshal(reqs[0].Args, &args) != nil || args.Window != "W7" {
		t.Fatalf("sent %+v; want one tree scoped to W7, since the frontmost window is not necessarily the fit's", reqs)
	}
}

func TestTheAdapterSetsTheSidebarInPointsAndAnswersInMilliPoints(t *testing.T) {
	h, fake := handler(t, func(agtermtest.Request) any {
		return agtermtest.OK(map[string]any{"sidebarWidth": 271.3})
	})

	applied, err := (terminal{client: h.client}).SetSidebarWidth(context.Background(), "W7", 271_300)
	if err != nil {
		t.Fatal(err)
	}
	if applied != 271_300 {
		t.Errorf("applied = %d milli-points, want 271300 - the echo must survive the round trip", applied)
	}

	reqs := fake.Requests()
	var args struct {
		SidebarWidth float64 `json:"sidebarWidth"`
		Window       string  `json:"window"`
	}
	if len(reqs) != 1 || reqs[0].Cmd != "sidebar.width" || json.Unmarshal(reqs[0].Args, &args) != nil {
		t.Fatalf("sent %+v; want one sidebar.width", reqs)
	}
	if args.SidebarWidth != 271.3 || args.Window != "W7" {
		t.Errorf("sent %v points to %q; want 271.3 to W7", args.SidebarWidth, args.Window)
	}
}

// The fit log's sidebar sentence comes from the tree now, not from agterm's private file.
func TestTheFitLogReportsTheSidebarFromTheTree(t *testing.T) {
	withSidebar := func(width float64, visible bool) any {
		reply := sessionShowing(false, surface("left", true)).(map[string]any)
		tree := reply["tree"].(map[string]any)
		tree["sidebarWidth"], tree["sidebarVisible"] = width, visible
		return reply
	}

	shown := shapeOf(t, withSidebar(258.7, true), sessionA)
	if !strings.Contains(shown, "258.7 points wide") {
		t.Errorf("the line does not carry the sidebar's width: %s", shown)
	}
	hidden := shapeOf(t, withSidebar(258.7, false), sessionA)
	if !strings.Contains(hidden, "sidebar is hidden") {
		t.Errorf("the line does not say the sidebar is hidden: %s", hidden)
	}
	if strings.Contains(shown, "state file") || strings.Contains(hidden, "state file") {
		t.Error("the line still talks about a state file nothing reads any more")
	}
}
