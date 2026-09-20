// Package palette keeps the owner's way out of a tall fit in agterm's command palette.
//
// # Why the bridge writes into the owner's keymap
//
// A tall fit leaves the desktop pane squeezed until it is undone, and the phone is not always in the
// owner's hand. The old width-only bridge solved this with a palette command the owner pasted into
// keymap.conf himself; this one installs it, because the line names the bridge's own binary and that
// binary lives inside an app bundle whose path moves with every install. A line the owner wrote once
// would point at a binary that is no longer there.
//
// # What it may touch, and what it may not
//
// One fenced block, and nothing outside it. The owner's keymap holds his own commands - including an
// older "Undo phone width fit" for the previous app - and a tool that rewrote the file around them
// would be editing a file that is his. So: the block is found by its fences and replaced whole, or
// appended when absent, and every other byte of the file comes out exactly as it went in. A file
// whose fences are broken is refused rather than repaired, because repairing means guessing where
// his text ends and ours begins.
//
// The write is a temp file renamed over the original, mode preserved, so agterm's reload never
// reads half a file.
package palette

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// Name is the command as the palette lists it. The old app's line reads "Undo phone width fit";
// this one is not that one, and the owner's file may hold both.
const Name = "Undo phone fit"

// The fences. Anything between them is ours to rewrite; the lines themselves are how the block is
// found, so they are matched exactly and never trimmed.
const (
	fenceOpen  = "# >>> agterm-remote >>>"
	fenceClose = "# <<< agterm-remote <<<"
)

// keymapClient is what this package needs from agterm: where the keymap is and what it currently
// holds, and a way to make an edit live.
type keymapClient interface {
	KeymapList(ctx context.Context) (path string, commands []string, err error)
	KeymapReload(ctx context.Context) error
}

// Line is the palette entry: agterm's `command "<name>" <shell...>` form, with the executable and
// the state directory quoted because both live under paths with spaces in them on a real Mac.
// --notify, because agterm runs the command with no terminal and stdout goes nowhere.
func Line(executable, stateDir string) string {
	return fmt.Sprintf(`command %q %q undo-fit --state-dir %q --notify`, Name, executable, stateDir)
}

// Ensure makes the keymap hold exactly the current line inside the fences, and reloads agterm when
// it changed anything. It reports whether the file was written.
//
// A file that already holds the block is left alone - but agterm is still asked to reload if its
// live list does not know the command, which is what a previous run's failed reload looks like.
func Ensure(ctx context.Context, c keymapClient, executable, stateDir string) (bool, error) {
	if executable == "" || stateDir == "" {
		return false, errors.New("the palette line needs the bridge's executable and its state directory")
	}
	path, commands, err := c.KeymapList(ctx)
	if err != nil {
		return false, fmt.Errorf("keymap.list: %w", err)
	}
	if path == "" {
		return false, errors.New("keymap.list named no keymap file")
	}

	mode := fs.FileMode(0o644)
	raw, err := os.ReadFile(path)
	switch {
	case err == nil:
		if info, serr := os.Stat(path); serr == nil {
			mode = info.Mode().Perm()
		}
	case errors.Is(err, fs.ErrNotExist):
		raw = nil
	default:
		return false, fmt.Errorf("reading %s: %w", path, err)
	}

	block := fenceOpen + "\n" + Line(executable, stateDir) + "\n" + fenceClose
	current := string(raw)
	if strings.Contains("\n"+current+"\n", "\n"+block+"\n") {
		if listed(commands, Name) {
			return false, nil
		}
		// The file is right and agterm has not read it: reload, and report the file untouched.
		if err := c.KeymapReload(ctx); err != nil {
			return false, fmt.Errorf("keymap.reload: %w", err)
		}
		return false, nil
	}

	next, err := splice(current, block)
	if err != nil {
		return false, fmt.Errorf("%s: %w", path, err)
	}
	if err := writeAtomically(path, []byte(next), mode); err != nil {
		return false, err
	}
	if err := c.KeymapReload(ctx); err != nil {
		return true, fmt.Errorf("the keymap was written but keymap.reload failed: %w", err)
	}
	return true, nil
}

func listed(commands []string, name string) bool {
	for _, c := range commands {
		if c == name {
			return true
		}
	}
	return false
}

// splice replaces the fenced block in current with block, or appends one. Everything outside the
// fences is returned byte for byte.
func splice(current, block string) (string, error) {
	lines := strings.Split(current, "\n")
	open, close := -1, -1
	for i, l := range lines {
		switch l {
		case fenceOpen:
			if open >= 0 {
				return "", errors.New("the agterm-remote block opens twice; fix the fences by hand")
			}
			open = i
		case fenceClose:
			if close >= 0 {
				return "", errors.New("the agterm-remote block closes twice; fix the fences by hand")
			}
			close = i
		}
	}
	switch {
	case open < 0 && close < 0:
		// An EMPTY file gets the block alone. A file holding only whitespace is not empty: those
		// bytes are outside the fences and come out as they went in, like every other byte.
		if current == "" {
			return block + "\n", nil
		}
		if !strings.HasSuffix(current, "\n") {
			current += "\n"
		}
		// A blank line before the fence, so the block reads as a block in a file the owner edits.
		return current + "\n" + block + "\n", nil
	case open < 0 || close < 0 || close < open:
		return "", errors.New("the agterm-remote block has a fence missing or out of order; fix the fences by hand")
	}
	out := append([]string{}, lines[:open]...)
	out = append(out, block)
	out = append(out, lines[close+1:]...)
	return strings.Join(out, "\n"), nil
}

// writeAtomically writes content beside path and renames it into place, so a reader sees the old
// file or the new one and never a partial. Same directory, because a rename across filesystems is
// a copy. The mode is set explicitly: CreateTemp's 0600 would silently tighten a 0644 keymap.
func writeAtomically(path string, content []byte, mode fs.FileMode) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	f, err := os.CreateTemp(dir, "."+filepath.Base(path)+".tmp-*")
	if err != nil {
		return err
	}
	tmp := f.Name()
	fail := func(err error) error {
		_ = f.Close()
		_ = os.Remove(tmp)
		return fmt.Errorf("writing %s: %w", path, err)
	}
	if err := f.Chmod(mode); err != nil {
		return fail(err)
	}
	if _, err := f.Write(content); err != nil {
		return fail(err)
	}
	if err := f.Sync(); err != nil {
		return fail(err)
	}
	if err := f.Close(); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("writing %s: %w", path, err)
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("writing %s: %w", path, err)
	}
	return nil
}
