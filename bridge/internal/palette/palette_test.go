package palette

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// fakeAgterm answers keymap.list with a path of the test's choosing and counts reloads.
type fakeAgterm struct {
	path     string
	commands []string
	listErr  error
	reloads  int
}

func (f *fakeAgterm) KeymapList(context.Context) (string, []string, error) {
	return f.path, f.commands, f.listErr
}

func (f *fakeAgterm) KeymapReload(context.Context) error {
	f.reloads++
	return nil
}

const (
	exe   = "/tmp/x/Agterm Remote.app/Contents/Resources/agterm-remote-bridge"
	state = "/tmp/x/state"
)

// The owner's file, as it stands: his own commands, one of them the OLD app's undo line, which is
// not ours and must survive untouched.
const owners = `# keymap.conf
command "tab: home"                  /tmp/x/bin/agt-tab home
command "Undo phone width fit"       /tmp/x/agtermfit --notify
command "Annotate" cmd+ctrl+a>k ~/.local/bin/annotate-pane.py
`

func keymapIn(t *testing.T, content string) (*fakeAgterm, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "keymap.conf")
	if content != "" {
		if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return &fakeAgterm{path: path}, path
}

func read(t *testing.T, path string) string {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(raw)
}

var block = fenceOpen + "\n" + Line(exe, state) + "\n" + fenceClose + "\n"

func TestTheLineIsAgtermsCommandFormWithBothPathsQuoted(t *testing.T) {
	want := `command "Undo phone fit" "/tmp/x/Agterm Remote.app/Contents/Resources/agterm-remote-bridge" undo-fit --state-dir "/tmp/x/state" --notify`
	if got := Line(exe, state); got != want {
		t.Fatalf("Line =\n%s\nwant\n%s", got, want)
	}
}

func TestAMissingKeymapIsCreatedWithJustTheBlock(t *testing.T) {
	c, path := keymapIn(t, "")

	changed, err := Ensure(context.Background(), c, exe, state)
	if err != nil {
		t.Fatal(err)
	}
	if !changed || c.reloads != 1 {
		t.Fatalf("changed=%v reloads=%d", changed, c.reloads)
	}
	if got := read(t, path); got != block {
		t.Fatalf("file =\n%s", got)
	}
}

func TestTheOwnersOwnLinesComeOutExactlyAsTheyWentIn(t *testing.T) {
	c, path := keymapIn(t, owners)
	if err := os.Chmod(path, 0o600); err != nil {
		t.Fatal(err)
	}

	changed, err := Ensure(context.Background(), c, exe, state)
	if err != nil {
		t.Fatal(err)
	}
	if !changed {
		t.Fatal("nothing was written")
	}
	got := read(t, path)
	if !strings.HasPrefix(got, owners) {
		t.Fatalf("the owner's lines were changed:\n%s", got)
	}
	if got != owners+"\n"+block {
		t.Fatalf("file =\n%s", got)
	}
	// The old app's line is his, not ours, and it stays.
	if !strings.Contains(got, `command "Undo phone width fit"`) {
		t.Fatal("the previous app's undo line was removed")
	}
	if info, _ := os.Stat(path); info.Mode().Perm() != 0o600 {
		t.Fatalf("mode became %v; the rename must keep the owner's", info.Mode().Perm())
	}
}

func TestAStaleExecutableIsRewrittenInPlace(t *testing.T) {
	stale := fenceOpen + "\n" + Line("/tmp/x/old/agterm-remote-bridge", state) + "\n" + fenceClose + "\n"
	trailer := `command "Open Pets" /tmp/x/bin/agt-open /tmp/x/pets` + "\n"
	c, path := keymapIn(t, owners+"\n"+stale+trailer)

	changed, err := Ensure(context.Background(), c, exe, state)
	if err != nil {
		t.Fatal(err)
	}
	if !changed || c.reloads != 1 {
		t.Fatalf("changed=%v reloads=%d", changed, c.reloads)
	}
	got := read(t, path)
	if got != owners+"\n"+block+trailer {
		t.Fatalf("file =\n%s", got)
	}
	if strings.Contains(got, "/old/") {
		t.Fatal("the stale path survived")
	}
}

func TestASecondRunChangesNothingAndReloadsNothing(t *testing.T) {
	c, path := keymapIn(t, owners)
	if _, err := Ensure(context.Background(), c, exe, state); err != nil {
		t.Fatal(err)
	}
	before := read(t, path)
	// agterm has loaded the file, so its list carries the command.
	c.commands = []string{"tab: home", "Undo phone width fit", Name}

	changed, err := Ensure(context.Background(), c, exe, state)
	if err != nil {
		t.Fatal(err)
	}
	if changed {
		t.Fatal("an identical block was rewritten")
	}
	if c.reloads != 1 {
		t.Fatalf("reloads=%d; a reload without a write is a reload of nothing", c.reloads)
	}
	if read(t, path) != before {
		t.Fatal("the file moved under a no-op")
	}
}

func TestAFileThatIsRightButNotLoadedIsReloadedWithoutAWrite(t *testing.T) {
	c, path := keymapIn(t, owners+"\n"+block)
	c.commands = []string{"tab: home"} // a previous run wrote the file and its reload never took
	info, _ := os.Stat(path)

	changed, err := Ensure(context.Background(), c, exe, state)
	if err != nil {
		t.Fatal(err)
	}
	if changed || c.reloads != 1 {
		t.Fatalf("changed=%v reloads=%d", changed, c.reloads)
	}
	if after, _ := os.Stat(path); after.ModTime() != info.ModTime() {
		t.Fatal("the file was rewritten when only agterm needed telling")
	}
}

func TestBrokenFencesAreRefusedAndTheFileLeftAlone(t *testing.T) {
	broken := owners + "\n" + fenceOpen + "\n" + `command "something" /tmp/x/bin/x` + "\n"
	c, path := keymapIn(t, broken)

	changed, err := Ensure(context.Background(), c, exe, state)
	if err == nil {
		t.Fatal("a block with no closing fence was spliced by guesswork")
	}
	if changed || c.reloads != 0 {
		t.Fatalf("changed=%v reloads=%d", changed, c.reloads)
	}
	if read(t, path) != broken {
		t.Fatal("the file was touched on a refusal")
	}
}

func TestNoKeymapNoWrite(t *testing.T) {
	c, path := keymapIn(t, owners)
	c.listErr = errors.New("agterm is not answering")

	if _, err := Ensure(context.Background(), c, exe, state); err == nil {
		t.Fatal("a failed keymap.list still proceeded")
	}
	if c.reloads != 0 || read(t, path) != owners {
		t.Fatal("something was done without knowing where the keymap is")
	}
	if _, err := Ensure(context.Background(), &fakeAgterm{path: path}, "", state); err == nil {
		t.Fatal("a line with no executable was written")
	}
}

// Whitespace is bytes outside the fences, and bytes outside the fences come out as they went in.
func TestAWhitespaceOnlyKeymapKeepsItsBytesAndGetsTheBlock(t *testing.T) {
	c, path := keymapIn(t, "\n  \n")

	changed, err := Ensure(context.Background(), c, exe, state)
	if err != nil {
		t.Fatal(err)
	}
	if !changed {
		t.Fatal("nothing was written")
	}
	if got := read(t, path); got != "\n  \n\n"+block {
		t.Fatalf("file = %q", got)
	}
}
