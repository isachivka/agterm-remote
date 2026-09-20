package api

import (
	"context"
	"encoding/binary"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
	"github.com/isachivka/agterm-remote/bridge/internal/agterm/agtermtest"
	"github.com/isachivka/agterm-remote/bridge/internal/ptysize"
	"github.com/isachivka/agterm-remote/bridge/internal/resize"
	"github.com/isachivka/agterm-remote/bridge/internal/zmxhold/zmxholdtest"
)

// The tall fit, end to end against the fakes: a fake agterm holding a cached width fit, and a fake
// zmx daemon behind sessionA's left pane that records every frame the bridge sends it. The pty is a
// stub: what it reports is what the test says the daemon last applied.

const (
	shellPID = 4242
	tallRows = 200
	// The cached fit's columns, and the key resize.To finds it under: display 0, the box and the
	// character width the request below carries.
	cachedColumns = 41
	cachedKey     = "0/440/9800"
)

type tallFixture struct {
	h      *Handler
	agterm *agtermtest.Fake
	daemon *zmxholdtest.Daemon

	mu sync.Mutex
	// pty is what the pane's pty reports; the test moves it to stand for agterm taking the pty back.
	pty ptysize.Size
	// clock is the handler's clock.
	clock time.Time
	// keymap is the path keymap.list answers with; empty makes keymap.list fail, so no test writes
	// a keymap it did not ask for.
	keymap string
	// detachBeforeWindowRestore records whether the daemon had the Detach when window.resize
	// arrived - the ordering the off path promises.
	detachBeforeWindowRestore bool
}

// tallFit builds the fixture. `live` says whether the pane has a running daemon behind it.
func tallFit(t *testing.T, live bool) *tallFixture {
	t.Helper()
	f := &tallFixture{pty: ptysize.Size{Rows: 56, Cols: 164}, clock: time.Unix(1_700_000_000, 0)}
	f.daemon = zmxholdtest.Start(t)
	entries := []map[string]any{}
	if live {
		entries = append(entries, map[string]any{
			"sessionID": sessionA, "pane": "left", "daemon": filepath.Base(f.daemon.Path),
			"observation": "running", "leaderPID": shellPID,
		})
	}
	f.agterm = agtermtest.Start(t, func(req agtermtest.Request) any {
		switch req.Cmd {
		case "tree":
			return agtermtest.OK(tree())
		case "window.list":
			return agtermtest.OK(map[string]any{"windows": []any{
				map[string]any{"id": "w1", "active": true,
					"geometry": map[string]any{"display": 0, "width": 1728, "height": 1084}},
			}})
		case "window.resize":
			f.mu.Lock()
			f.detachBeforeWindowRestore = hasTag(f.daemon.Frames(), 3)
			f.mu.Unlock()
			return agtermtest.OK(map[string]any{})
		case "zmx.list":
			return agtermtest.OK(map[string]any{"zmx": map[string]any{
				"endpoint": map[string]any{"executable": "/bundle/zmx", "socketDirectory": filepath.Dir(f.daemon.Path)},
				"entries":  entries,
			}})
		case "keymap.list":
			f.mu.Lock()
			path := f.keymap
			f.mu.Unlock()
			if path == "" {
				return agtermtest.Err("no keymap in this test")
			}
			return agtermtest.OK(map[string]any{"keymap": map[string]any{"path": path, "commands": []any{}}})
		case "keymap.reload":
			return agtermtest.OK(map[string]any{})
		case "session.text":
			return agtermtest.OK(map[string]any{"text": "hello"})
		}
		return agtermtest.Err("unexpected " + req.Cmd)
	})
	f.h = New(agterm.New(f.agterm.Path), t.TempDir())
	f.h.store.Fits[cachedKey] = resize.Fit{Display: 0, BoxWidthDp: 440, CharacterWidthMilliDp: 9800, Points: 769, Columns: cachedColumns}
	f.h.ptySize = func(pid int) (ptysize.Size, error) {
		if pid != shellPID {
			t.Errorf("the pty was read through pid %d, not the pane's shell", pid)
		}
		f.mu.Lock()
		defer f.mu.Unlock()
		return f.pty, nil
	}
	f.h.now = func() time.Time {
		f.mu.Lock()
		defer f.mu.Unlock()
		return f.clock
	}
	return f
}

func (f *tallFixture) press(t *testing.T, rows int) Response {
	t.Helper()
	resp := f.h.Handle(context.Background(), Request{
		Verb: VerbResize, Session: sessionA, Pane: "left",
		BoxWidthDp: 440, CharacterWidthMilliDp: 9800, MarginDp: 4, Rows: rows,
	})
	if !resp.OK {
		t.Fatalf("the press was refused: %s", resp.Error)
	}
	return resp
}

func (f *tallFixture) advance(d time.Duration) {
	f.mu.Lock()
	f.clock = f.clock.Add(d)
	f.mu.Unlock()
}

func (f *tallFixture) ptyReports(s ptysize.Size) {
	f.mu.Lock()
	f.pty = s
	f.mu.Unlock()
}

func (f *tallFixture) screen(t *testing.T, session string) {
	t.Helper()
	resp := f.h.Handle(context.Background(), Request{Verb: VerbScreen, Session: session, Pane: "left"})
	if !resp.OK {
		t.Fatalf("screen: %s", resp.Error)
	}
}

func hasTag(frames []zmxholdtest.Frame, tag byte) bool {
	for _, fr := range frames {
		if fr.Tag == tag {
			return true
		}
	}
	return false
}

func sizeOf(t *testing.T, fr zmxholdtest.Frame) (rows, cols int) {
	t.Helper()
	if len(fr.Payload) < 4 {
		t.Fatalf("frame %+v carries no size", fr)
	}
	return int(binary.LittleEndian.Uint16(fr.Payload[0:2])), int(binary.LittleEndian.Uint16(fr.Payload[2:4]))
}

// expectClaim asserts frames[at:] is one claim: the paste, then the size in both forms.
func expectClaim(t *testing.T, frames []zmxholdtest.Frame, at, rows, cols int) {
	t.Helper()
	if len(frames) < at+3 {
		t.Fatalf("no claim at %d in %+v", at, frames)
	}
	if frames[at].Tag != 0 || string(frames[at].Payload) != "\x1b[200~\x1b[201~" {
		t.Fatalf("frame %d is % x, not the claim paste", at, frames[at].Payload)
	}
	for i := at + 1; i < at+3; i++ {
		if frames[i].Tag != 2 {
			t.Fatalf("frame %d is tag %d, not Resize", i, frames[i].Tag)
		}
		if r, c := sizeOf(t, frames[i]); r != rows || c != cols {
			t.Fatalf("frame %d resizes to %dx%d, want %dx%d", i, r, c, rows, cols)
		}
	}
}

func TestATallFitHoldsThePtyAndReportsRows(t *testing.T) {
	f := tallFit(t, true)

	resp := f.press(t, tallRows)

	if resp.Columns != cachedColumns || resp.Rows != tallRows {
		t.Fatalf("reply says %d columns, %d rows; want %d and %d", resp.Columns, resp.Rows, cachedColumns, tallRows)
	}
	frames := f.daemon.AwaitFrames(t, 5)
	// Init at the held size, both forms, then the claim at the columns the WIDTH FIT produced.
	if frames[0].Tag != 7 || frames[1].Tag != 7 {
		t.Fatalf("the hold did not open with Init: %+v", frames[:2])
	}
	if r, c := sizeOf(t, frames[0]); r != tallRows || c != cachedColumns {
		t.Fatalf("Init at %dx%d", r, c)
	}
	expectClaim(t, frames, 2, tallRows, cachedColumns)

	// The record, so a restart can undo it and every reply can say it.
	a := f.h.store.Active
	if a == nil || a.Rows != tallRows || a.Session != sessionA || a.Pane != "left" {
		t.Fatalf("active = %+v", a)
	}
	if a.Daemon != filepath.Base(f.daemon.Path) || a.SocketPath != f.daemon.Path {
		t.Errorf("the record names daemon %q at %q", a.Daemon, a.SocketPath)
	}
	if a.OriginalRows != 56 || a.OriginalCols != 164 {
		t.Errorf("the original size was recorded as %dx%d, not what the pty reported", a.OriginalRows, a.OriginalCols)
	}
	// And the width fit's own record is untouched: a height is a claim, not a calibration.
	if cached := f.h.store.Fits[cachedKey]; cached.Rows != 0 || cached.Daemon != "" {
		t.Errorf("the height leaked into the fits map: %+v", cached)
	}
	// A poll carries it.
	poll := f.h.Handle(context.Background(), Request{Verb: VerbSessions})
	if poll.Rows != tallRows || poll.Columns != cachedColumns || poll.FitEnabled == nil || !*poll.FitEnabled {
		t.Fatalf("poll = %+v", poll)
	}
}

func TestNoDaemonBehindThePaneIsWidthOnly(t *testing.T) {
	f := tallFit(t, false)

	resp := f.press(t, tallRows)

	if resp.Columns != cachedColumns {
		t.Fatalf("the width was not applied: %+v", resp)
	}
	if resp.Rows != 0 {
		t.Fatalf("rows %d reported with no daemon to hold them", resp.Rows)
	}
	if f.daemon.Connections() != 0 {
		t.Fatal("something dialled a daemon the pane does not have")
	}
	if a := f.h.store.Active; a == nil || a.Rows != 0 {
		t.Fatalf("active = %+v", a)
	}
}

func TestAPtyThatMovedIsReclaimedOnceAndNotTwiceWithinTwoSeconds(t *testing.T) {
	f := tallFit(t, true)
	f.press(t, tallRows)
	f.daemon.AwaitFrames(t, 5)

	// The owner typed on the Mac: agterm took the pty back to the pane's size.
	f.ptyReports(ptysize.Size{Rows: 56, Cols: 164})

	// Too soon after the claim: nothing.
	f.advance(time.Second)
	f.screen(t, sessionA)
	if n := len(f.daemon.Frames()); n != 5 {
		t.Fatalf("%d frames one second after the claim; nothing should have been sent", n)
	}

	f.advance(2 * time.Second)
	f.screen(t, sessionA)
	frames := f.daemon.AwaitFrames(t, 8)
	expectClaim(t, frames, 5, tallRows, cachedColumns)

	// Within two seconds of that: still 8, however many reads land.
	f.advance(500 * time.Millisecond)
	f.screen(t, sessionA)
	f.screen(t, sessionA)
	if n := len(f.daemon.Frames()); n != 8 {
		t.Fatalf("%d frames; the re-claim ran twice inside two seconds", n)
	}

	// And after them, once more.
	f.advance(2 * time.Second)
	f.screen(t, sessionA)
	expectClaim(t, f.daemon.AwaitFrames(t, 11), 8, tallRows, cachedColumns)

	// A pty at the held size is left alone.
	f.ptyReports(ptysize.Size{Rows: tallRows, Cols: cachedColumns})
	f.advance(3 * time.Second)
	f.screen(t, sessionA)
	if n := len(f.daemon.Frames()); n != 11 {
		t.Fatalf("%d frames; a pty already at the held size was re-claimed", n)
	}
}

func TestAReadOfAnotherPaneDoesNotCheckTheHold(t *testing.T) {
	f := tallFit(t, true)
	f.press(t, tallRows)
	f.daemon.AwaitFrames(t, 5)
	f.ptyReports(ptysize.Size{Rows: 56, Cols: 164})
	f.advance(3 * time.Second)
	before := len(f.agterm.Requests())

	f.screen(t, sessionB)

	for _, r := range f.agterm.Requests()[before:] {
		if r.Cmd == "zmx.list" {
			t.Fatal("a plain read of a pane nothing holds listed the daemons")
		}
	}
	if n := len(f.daemon.Frames()); n != 5 {
		t.Fatalf("%d frames after reading another pane", n)
	}
}

func TestOffReleasesTheHeightAtTheOriginalSizeThenRestoresTheWindow(t *testing.T) {
	f := tallFit(t, true)
	f.press(t, tallRows)
	f.daemon.AwaitFrames(t, 5)

	off := f.h.Handle(context.Background(), Request{Verb: VerbResize})
	if !off.OK {
		t.Fatalf("off: %s", off.Error)
	}

	frames := f.daemon.AwaitFrames(t, 8)
	for i := 5; i < 7; i++ {
		if frames[i].Tag != 2 {
			t.Fatalf("frame %d is tag %d, want Resize", i, frames[i].Tag)
		}
		if r, c := sizeOf(t, frames[i]); r != 56 || c != 164 {
			t.Fatalf("released to %dx%d, not the pty's original 56x164", r, c)
		}
	}
	if frames[7].Tag != 3 || len(frames[7].Payload) != 0 {
		t.Fatalf("frame 7 is %+v, want Detach", frames[7])
	}
	if hasTag(frames[5:], 0) {
		t.Fatal("the release typed into the pty")
	}
	f.daemon.AwaitClosed(t, 0)

	f.mu.Lock()
	ordered := f.detachBeforeWindowRestore
	f.mu.Unlock()
	if !ordered {
		t.Fatal("the window was restored before the pty was let go")
	}
	if off.Rows != 0 || off.FitEnabled == nil || *off.FitEnabled {
		t.Fatalf("off replied %+v", off)
	}
	if f.h.store.Active != nil {
		t.Fatalf("active survived off: %+v", f.h.store.Active)
	}
}

func TestAWidthOnlyPressLetsAHeldHeightGo(t *testing.T) {
	f := tallFit(t, true)
	f.press(t, tallRows)
	f.daemon.AwaitFrames(t, 5)

	// The phone's zmx setting went off; its next press asks for width alone.
	resp := f.press(t, 0)

	if resp.Rows != 0 || resp.Columns != cachedColumns {
		t.Fatalf("reply = %+v", resp)
	}
	frames := f.daemon.AwaitFrames(t, 8)
	if r, c := sizeOf(t, frames[5]); r != 56 || c != 164 || frames[7].Tag != 3 {
		t.Fatalf("frames after the press: %+v", frames[5:])
	}
	if a := f.h.store.Active; a == nil || a.Rows != 0 {
		t.Fatalf("active = %+v", a)
	}
	if poll := f.h.Handle(context.Background(), Request{Verb: VerbSessions}); poll.Rows != 0 {
		t.Fatalf("a poll still reports %d rows", poll.Rows)
	}
}

func TestASecondTallPressReclaimsOnTheSameConnection(t *testing.T) {
	f := tallFit(t, true)
	f.press(t, tallRows)
	f.daemon.AwaitFrames(t, 5)

	f.press(t, tallRows)

	expectClaim(t, f.daemon.AwaitFrames(t, 8), 5, tallRows, cachedColumns)
	if f.daemon.Connections() != 1 {
		t.Fatalf("%d connections; a re-press must not open a second hold", f.daemon.Connections())
	}
	if a := f.h.store.Active; a.OriginalRows != 56 || a.OriginalCols != 164 {
		t.Fatalf("the original moved to %dx%d under a re-press", a.OriginalRows, a.OriginalCols)
	}
}

func TestStartupRestorePutsThePtyBackBeforeTheWindow(t *testing.T) {
	f := tallFit(t, true)
	// What a restart finds on disk: a tall fit in force and a narrowed window. No hold is live in
	// this process - the connection died with the last one.
	f.h.store.Active = &resize.Fit{
		Display: 0, BoxWidthDp: 440, CharacterWidthMilliDp: 9800, Points: 769, Columns: cachedColumns,
		Rows: tallRows, Session: sessionA, Pane: "left",
		Daemon: filepath.Base(f.daemon.Path), SocketPath: f.daemon.Path,
		OriginalRows: 56, OriginalCols: 164,
	}
	f.h.store.Pending = &resize.Restore{WindowID: "w1", Width: 1728, Height: 1084}
	f.h.publishFit()

	if err := f.h.RestorePending(context.Background()); err != nil {
		t.Fatal(err)
	}

	frames := f.daemon.AwaitFrames(t, 3)
	// Init at the original, both forms, then Detach - and no paste, because a restore must take
	// leadership only when nobody has it.
	for i := 0; i < 2; i++ {
		if frames[i].Tag != 7 {
			t.Fatalf("frame %d is tag %d, want Init", i, frames[i].Tag)
		}
		if r, c := sizeOf(t, frames[i]); r != 56 || c != 164 {
			t.Fatalf("restored to %dx%d", r, c)
		}
	}
	if frames[2].Tag != 3 || hasTag(frames, 0) {
		t.Fatalf("frames = %+v", frames)
	}
	f.mu.Lock()
	ordered := f.detachBeforeWindowRestore
	f.mu.Unlock()
	if !ordered {
		t.Fatal("the window was restored before the pty")
	}
	if f.h.store.Active != nil || f.h.FitInForce() {
		t.Fatal("the setting survived the restore")
	}
}

func TestRowsOutsideTheBoundsAreRefusedBeforeAnythingMoves(t *testing.T) {
	f := tallFit(t, true)
	for _, rows := range []int{resize.MaxRows + 1, -1, 100000} {
		resp := f.h.Handle(context.Background(), Request{
			Verb: VerbResize, Session: sessionA, Pane: "left",
			BoxWidthDp: 440, CharacterWidthMilliDp: 9800, Rows: rows,
		})
		if resp.OK || resp.Refusal != RefusalContent {
			t.Fatalf("rows %d: %+v", rows, resp)
		}
	}
	for _, r := range f.agterm.Requests() {
		if r.Cmd == "window.resize" {
			t.Fatal("a refused row count still resized the window")
		}
	}
	if f.daemon.Connections() != 0 {
		t.Fatal("a refused row count still dialled the daemon")
	}
}

func TestATallPressInstallsTheUndoCommand(t *testing.T) {
	f := tallFit(t, true)
	keymap := filepath.Join(t.TempDir(), "keymap.conf")
	if err := os.WriteFile(keymap, []byte("command \"Open Pets\" /tmp/x/bin/agt-open /tmp/x/pets\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	f.mu.Lock()
	f.keymap = keymap
	f.mu.Unlock()

	f.press(t, tallRows)

	raw, err := os.ReadFile(keymap)
	if err != nil {
		t.Fatal(err)
	}
	got := string(raw)
	if !strings.HasPrefix(got, "command \"Open Pets\"") {
		t.Fatalf("the owner's line moved:\n%s", got)
	}
	if !strings.Contains(got, `command "Undo phone fit" `) || !strings.Contains(got, ` undo-fit --state-dir `) {
		t.Fatalf("the undo command is not in the keymap:\n%s", got)
	}
	reloaded := false
	for _, r := range f.agterm.Requests() {
		if r.Cmd == "keymap.reload" {
			reloaded = true
		}
	}
	if !reloaded {
		t.Fatal("the keymap was written and agterm was not told")
	}
}
