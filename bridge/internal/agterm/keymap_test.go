package agterm

import (
	"context"
	"testing"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm/agtermtest"
)

// The reply shape read off the live socket on 2026-09-20: `keymap.path` and `keymap.commands[].name`,
// beside `actions`, `menu` and `diagnostics`, which are not decoded.
func TestKeymapListReadsThePathAndTheCommandNames(t *testing.T) {
	fake := agtermtest.Start(t, func(req agtermtest.Request) any {
		if req.Cmd != "keymap.list" {
			return agtermtest.Err("unexpected " + req.Cmd)
		}
		return agtermtest.OK(map[string]any{"keymap": map[string]any{
			"path":        "/tmp/x/keymap.conf",
			"actions":     []map[string]any{{"chord": "cmd+n", "action": "new_session"}},
			"menu":        []map[string]any{{"menu": "Agterm", "title": "Settings"}},
			"diagnostics": []any{},
			"commands": []map[string]any{
				{"name": "Undo phone width fit"},
				{"name": "Annotate", "shortcut": "cmd+ctrl+a>k"},
			},
		}})
	})

	path, names, err := New(fake.Path).KeymapList(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if path != "/tmp/x/keymap.conf" {
		t.Errorf("path = %q", path)
	}
	if len(names) != 2 || names[0] != "Undo phone width fit" || names[1] != "Annotate" {
		t.Errorf("commands = %q", names)
	}
}

func TestKeymapReloadIsOneCommandAndReadsNothingBack(t *testing.T) {
	fake := agtermtest.Start(t, func(req agtermtest.Request) any {
		return agtermtest.OK(map[string]any{"diagnostics": 0})
	})
	if err := New(fake.Path).KeymapReload(context.Background()); err != nil {
		t.Fatal(err)
	}
	if got := fake.Requests(); len(got) != 1 || got[0].Cmd != "keymap.reload" || got[0].Target != "" {
		t.Fatalf("sent %+v", got)
	}
}
