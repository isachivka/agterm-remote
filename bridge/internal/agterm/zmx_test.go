package agterm

import (
	"context"
	"testing"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm/agtermtest"
)

func TestZmxListReadsTheEndpointAndEveryPaneRow(t *testing.T) {
	fake := agtermtest.Start(t, func(req agtermtest.Request) any {
		if req.Cmd != "zmx.list" {
			return agtermtest.Err("unexpected " + req.Cmd)
		}
		return agtermtest.OK(map[string]any{
			"zmx": map[string]any{
				"endpoint": map[string]any{
					"executable":      "/Applications/agterm.app/Contents/MacOS/zmx",
					"socketDirectory": "/tmp/agterm-zmx-abc",
				},
				"restore":           map[string]any{"active": "live"},
				"inventoryComplete": true,
				"entries": []map[string]any{
					{"sessionID": "S1", "pane": "left", "daemon": "agterm-1", "observation": "running"},
					{"sessionID": "S1", "pane": "right", "daemon": "agterm-2", "observation": "absent"},
				},
			},
		})
	})

	got, err := New(fake.Path).ZmxList(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if got.Executable != "/Applications/agterm.app/Contents/MacOS/zmx" || got.SocketDir != "/tmp/agterm-zmx-abc" {
		t.Fatalf("endpoint = %+v", got)
	}
	if len(got.Entries) != 2 || got.Entries[0].Daemon != "agterm-1" || got.Entries[1].Observation != "absent" {
		t.Fatalf("entries = %+v", got.Entries)
	}
}
