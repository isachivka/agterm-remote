package api

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
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
	"github.com/isachivka/agterm-remote/bridge/internal/zmxhold"
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
	// failWindowList and failZmxList make the next agterm answers fail, standing for agterm
	// restarting under a press. daemonName is what zmx.list names behind the pane; a test moves
	// it to stand for the daemon being replaced.
	failWindowList, failZmxList bool
	daemonName                  string
}

// tallFit builds the fixture. `live` says whether the pane has a running daemon behind it.
func tallFit(t *testing.T, live bool) *tallFixture {
	t.Helper()
	f := &tallFixture{pty: ptysize.Size{Rows: 56, Cols: 164}, clock: time.Unix(1_700_000_000, 0)}
	f.daemon = zmxholdtest.Start(t)
	f.daemonName = filepath.Base(f.daemon.Path)
	f.agterm = agtermtest.Start(t, func(req agtermtest.Request) any {
		f.mu.Lock()
		failWindowList, failZmxList, daemonName := f.failWindowList, f.failZmxList, f.daemonName
		f.mu.Unlock()
		entries := []map[string]any{}
		if live {
			entries = append(entries, map[string]any{
				"sessionID": sessionA, "pane": "left", "daemon": daemonName,
				"observation": "running", "leaderPID": shellPID,
			})
		}
		switch req.Cmd {
		case "tree":
			return agtermtest.OK(tree())
		case "window.list":
			if failWindowList {
				return agtermtest.Err("agterm is restarting")
			}
			return agtermtest.OK(map[string]any{"windows": []any{
				map[string]any{"id": "w1", "active": true,
					"geometry": map[string]any{"display": 0, "width": 1728, "height": 1084}},
			}})
		case "window.resize":
			// The pty follows the window, as it does on a real Mac: the fit's width narrows it to the
			// fitted columns, the restore widens it back. A hold that read the pty AFTER the width
			// fit would record the narrow size as the original - the second review's finding.
			var args struct {
				Width int `json:"width"`
			}
			_ = json.Unmarshal(req.Args, &args)
			f.mu.Lock()
			f.detachBeforeWindowRestore = hasTag(f.daemon.Frames(), 3)
			switch args.Width {
			case 769:
				f.pty.Cols = cachedColumns
			case 1728:
				f.pty.Cols = 164
			}
			f.mu.Unlock()
			return agtermtest.OK(map[string]any{})
		case "zmx.list":
			if failZmxList {
				return agtermtest.Err("agterm is restarting")
			}
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
	// The styled read's zmx history, so a styled screen read of the held pane stays styled rather
	// than falling back to plain - which would read as the phone giving the height up.
	f.h.history = func(context.Context, string, string, string) (string, error) { return "hello\r\n", nil }
	return f
}

func (f *tallFixture) set(fn func()) {
	f.mu.Lock()
	defer f.mu.Unlock()
	fn()
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

// screen is the phone's poll of a pane WITH colours through zmx, which is how it reads while it
// wants a height. plainScreen is the same read without them.
func (f *tallFixture) screen(t *testing.T, session string) Response {
	t.Helper()
	resp := f.h.Handle(context.Background(), Request{Verb: VerbScreen, Session: session, Pane: "left", Styled: true})
	if !resp.OK {
		t.Fatalf("screen: %s", resp.Error)
	}
	return resp
}

func (f *tallFixture) plainScreen(t *testing.T, session string) Response {
	t.Helper()
	resp := f.h.Handle(context.Background(), Request{Verb: VerbScreen, Session: session, Pane: "left"})
	if !resp.OK {
		t.Fatalf("screen: %s", resp.Error)
	}
	return resp
}

// expectRelease asserts frames[at:] is one release: 56 rows by `cols` in both forms, then Detach,
// and nothing typed. The columns are the test's to say: 164 (pre-fit) when the window restore
// follows, 41 (the fit in force) when only the height is let go.
func expectRelease(t *testing.T, frames []zmxholdtest.Frame, at, cols int) {
	t.Helper()
	if len(frames) < at+3 {
		t.Fatalf("no release at %d in %+v", at, frames)
	}
	for i := at; i < at+2; i++ {
		if frames[i].Tag != 2 {
			t.Fatalf("frame %d is tag %d, want Resize", i, frames[i].Tag)
		}
		if r, c := sizeOf(t, frames[i]); r != 56 || c != cols {
			t.Fatalf("released to %dx%d, want 56x%d", r, c, cols)
		}
	}
	if frames[at+2].Tag != 3 || len(frames[at+2].Payload) != 0 {
		t.Fatalf("frame %d is %+v, want Detach", at+2, frames[at+2])
	}
	if hasTag(frames[at:at+3], 0) {
		t.Fatal("the release typed into the pty")
	}
}

// rowsOnDisk reads the height record back from the store file, as a restart would.
func rowsOnDisk(t *testing.T, h *Handler) int {
	t.Helper()
	loaded := resize.LoadStore(h.stateDir)
	if loaded.Active == nil {
		return 0
	}
	return loaded.Active.Rows
}

// dropHoldConnection has the daemon hang up on the hold without a Detach, as a daemon that
// detached all its clients does, and waits until the hold has noticed.
func (f *tallFixture) dropHoldConnection(t *testing.T) {
	t.Helper()
	f.daemon.Drop(t, 0)
	deadline := time.Now().Add(5 * time.Second)
	for {
		f.h.height.mu.Lock()
		dead := f.h.height.held != nil && f.h.height.held.hold.Err() != nil
		f.h.height.mu.Unlock()
		if dead {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("the hold never noticed the daemon hanging up")
		}
		time.Sleep(5 * time.Millisecond)
	}
}

// expectRestore asserts frames[at:] is one fresh put-back: Init at 56x164 in both forms, then
// Detach, and nothing typed.
func expectRestore(t *testing.T, frames []zmxholdtest.Frame, at int) {
	t.Helper()
	if len(frames) < at+3 {
		t.Fatalf("no restore at %d in %+v", at, frames)
	}
	for i := at; i < at+2; i++ {
		if frames[i].Tag != 7 {
			t.Fatalf("frame %d is tag %d, want Init", i, frames[i].Tag)
		}
		if r, c := sizeOf(t, frames[i]); r != 56 || c != 164 {
			t.Fatalf("restored to %dx%d, not the original 56x164", r, c)
		}
	}
	if frames[at+2].Tag != 3 || hasTag(frames[at:at+3], 0) {
		t.Fatalf("frames = %+v", frames[at:])
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

	// A plain read of a pane that is not held: neither the styled path nor the hold check wants
	// the inventory, and the plain read must not be taken for the phone giving THIS hold up.
	f.plainScreen(t, sessionB)

	for _, r := range f.agterm.Requests()[before:] {
		if r.Cmd == "zmx.list" {
			t.Fatal("a plain read of a pane nothing holds listed the daemons")
		}
	}
	if n := len(f.daemon.Frames()); n != 5 {
		t.Fatalf("%d frames after reading another pane", n)
	}
	if poll := f.h.Handle(context.Background(), Request{Verb: VerbSessions}); poll.Rows != tallRows {
		t.Fatalf("reading another pane plain dropped the height: poll says %d rows", poll.Rows)
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
	// The width fit is still in force at 41 columns and nothing will correct the pty until the
	// owner types, so the release says 56x41 - the pre-fit 164 would wrap every line in the pane.
	expectRelease(t, f.daemon.AwaitFrames(t, 8), 5, cachedColumns)
	if a := f.h.store.Active; a == nil || a.Rows != 0 || a.Columns != cachedColumns {
		t.Fatalf("active = %+v", a)
	}
	if poll := f.h.Handle(context.Background(), Request{Verb: VerbSessions}); poll.Rows != 0 {
		t.Fatalf("a poll still reports %d rows", poll.Rows)
	}
}

func TestASecondTallPressReadsThePtyAndClaimsOnlyWhenItMoved(t *testing.T) {
	f := tallFit(t, true)
	f.press(t, tallRows)
	f.daemon.AwaitFrames(t, 5)

	// The pty is where the hold put it: a re-press has nothing to claim, and claims nothing.
	f.ptyReports(ptysize.Size{Rows: tallRows, Cols: cachedColumns})
	resp := f.press(t, tallRows)
	if resp.Rows != tallRows {
		t.Fatalf("reply = %+v", resp)
	}
	if n := len(f.daemon.Frames()); n != 5 {
		t.Fatalf("%d frames; a pty already at the held size was claimed again", n)
	}
	// And the re-press restarted the check timer: a poll straight after it checks nothing.
	f.ptyReports(ptysize.Size{Rows: 56, Cols: 164})
	f.advance(time.Second)
	f.screen(t, sessionA)
	if n := len(f.daemon.Frames()); n != 5 {
		t.Fatalf("%d frames; the check ran inside the interval after a re-press", n)
	}

	// The pty moved: the re-press claims, on the same connection, keeping the original.
	f.press(t, tallRows)
	expectClaim(t, f.daemon.AwaitFrames(t, 8), 5, tallRows, cachedColumns)
	if f.daemon.Connections() != 1 {
		t.Fatalf("%d connections; a re-press must not open a second hold", f.daemon.Connections())
	}
	if a := f.h.store.Active; a.OriginalRows != 56 || a.OriginalCols != 164 {
		t.Fatalf("the original moved to %dx%d under a re-press", a.OriginalRows, a.OriginalCols)
	}
}

// **The record is on disk before the pty is touched, and a claim that does not happen takes it
// back.** With the daemon's socket gone the open fails; the store must then say no height, on disk
// as well as in memory, or a restart would dial a daemon for a hold that never was.
func TestAFailedClaimLeavesNoHeightOnDisk(t *testing.T) {
	f := tallFit(t, true)
	if err := os.Remove(f.daemon.Path); err != nil {
		t.Fatal(err)
	}

	resp := f.press(t, tallRows)

	if resp.Rows != 0 || resp.Columns != cachedColumns {
		t.Fatalf("reply = %+v", resp)
	}
	if a := f.h.store.Active; a == nil || a.Rows != 0 || a.SocketPath != "" {
		t.Fatalf("active = %+v", a)
	}
	if rowsOnDisk(t, f.h) != 0 {
		t.Fatal("the store on disk promises a height nothing holds")
	}
}

func TestAHeldHeightIsOnDiskWithWhatARestartNeeds(t *testing.T) {
	f := tallFit(t, true)
	f.press(t, tallRows)

	loaded := resize.LoadStore(f.h.stateDir)
	a := loaded.Active
	if a == nil || a.Rows != tallRows || a.SocketPath != f.daemon.Path || a.OriginalRows != 56 || a.OriginalCols != 164 {
		t.Fatalf("on disk: %+v", a)
	}
}

// **A plain read of the held pane is the phone giving the height up.** Colours through zmx is the
// only thing that asks for a height, the phone reads styled exactly while it is on, and switching it
// off sends no press - so this read is the only sign, and the bridge acts on it.
func TestAPlainReadOfTheHeldPaneLetsTheHeightGo(t *testing.T) {
	f := tallFit(t, true)
	f.press(t, tallRows)
	f.daemon.AwaitFrames(t, 5)

	resp := f.plainScreen(t, sessionA)

	// Height only: the window is still fitted to 41 columns, so that is what the pty goes back to.
	expectRelease(t, f.daemon.AwaitFrames(t, 8), 5, cachedColumns)
	f.daemon.AwaitClosed(t, 0)
	if resp.Rows != 0 {
		t.Fatalf("the reply to the read that let the height go still says %d rows", resp.Rows)
	}
	if a := f.h.store.Active; a == nil || a.Rows != 0 || a.Columns != cachedColumns {
		t.Fatalf("active = %+v; the width stays, the height goes", a)
	}
	if rowsOnDisk(t, f.h) != 0 {
		t.Fatal("the record on disk still holds a height")
	}
	if poll := f.h.Handle(context.Background(), Request{Verb: VerbSessions}); poll.Rows != 0 {
		t.Fatalf("a poll still reports %d rows", poll.Rows)
	}
	// And a styled read of the pane afterwards keeps nothing: the hold is gone, not paused.
	f.ptyReports(ptysize.Size{Rows: 56, Cols: 164})
	f.advance(3 * time.Second)
	f.screen(t, sessionA)
	if n := len(f.daemon.Frames()); n != 8 {
		t.Fatalf("%d frames; a dropped hold was kept", n)
	}
}

// resize.To returns with Active INTACT when the window cannot be read: the height must be released
// AND taken off the record, or every reply goes on saying "200 rows" about a pty just put back.
func TestAWidthFitThatFailsWithAHeightHeldReleasesItAndClearsTheRecord(t *testing.T) {
	f := tallFit(t, true)
	f.press(t, tallRows)
	f.daemon.AwaitFrames(t, 5)

	f.set(func() { f.failWindowList = true })
	resp := f.h.Handle(context.Background(), Request{
		Verb: VerbResize, Session: sessionA, Pane: "left",
		BoxWidthDp: 440, CharacterWidthMilliDp: 9800, Rows: tallRows,
	})
	if resp.OK {
		t.Fatal("the press was supposed to be refused")
	}

	// Active is intact, so the window is still at its 41 columns and the pty goes back to them.
	expectRelease(t, f.daemon.AwaitFrames(t, 8), 5, cachedColumns)
	if a := f.h.store.Active; a == nil || a.Rows != 0 || a.Daemon != "" {
		t.Fatalf("active = %+v", a)
	}
	if rowsOnDisk(t, f.h) != 0 {
		t.Fatal("the record on disk still holds a height")
	}
	if poll := f.h.Handle(context.Background(), Request{Verb: VerbSessions}); poll.Rows != 0 {
		t.Fatalf("a poll still reports %d rows", poll.Rows)
	}
}

// The early exits of holdHeight - here, an inventory that could not be read - release like every
// other width-only exit. Before this a hiccup in zmx.list on the automatic re-apply left the pty
// held at 200 rows with a record that said nothing, so no reply admitted to it and no restart could
// put it back.
func TestATallPressWhoseInventoryFailsReleasesTheHoldItCannotRecord(t *testing.T) {
	f := tallFit(t, true)
	f.press(t, tallRows)
	f.daemon.AwaitFrames(t, 5)

	f.set(func() { f.failZmxList = true })
	resp := f.press(t, tallRows)

	if resp.Rows != 0 || resp.Columns != cachedColumns {
		t.Fatalf("reply = %+v", resp)
	}
	// The width was re-applied at 41 columns before the height gave up, so the pty goes to 56x41.
	expectRelease(t, f.daemon.AwaitFrames(t, 8), 5, cachedColumns)
	f.daemon.AwaitClosed(t, 0)
	if a := f.h.store.Active; a == nil || a.Rows != 0 {
		t.Fatalf("active = %+v", a)
	}
}

// A pane whose daemon was replaced cannot be held by the connection to the old one. Once the check
// has seen that, replies stop claiming a height rather than going on saying 200 rows for a pane
// nothing holds.
func TestALostDaemonStopsTheHeightBeingReported(t *testing.T) {
	f := tallFit(t, true)
	f.press(t, tallRows)
	f.daemon.AwaitFrames(t, 5)

	f.set(func() { f.daemonName = "agterm-replacement" })
	f.advance(3 * time.Second)
	resp := f.screen(t, sessionA)

	if resp.Rows != 0 {
		t.Fatalf("the read that found the daemon gone still says %d rows", resp.Rows)
	}
	if a := f.h.store.Active; a == nil || a.Rows != 0 {
		t.Fatalf("active = %+v", a)
	}
	// The old connection was let go at the fit's columns, which is all that can be done for it.
	expectRelease(t, f.daemon.AwaitFrames(t, 8), 5, cachedColumns)
	if poll := f.h.Handle(context.Background(), Request{Verb: VerbSessions}); poll.Rows != 0 {
		t.Fatalf("a poll still reports %d rows", poll.Rows)
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

// **The original is the pty BEFORE the width fit.** The fit narrows the window and the pty with
// it, and holdHeight runs after; a size read there is 56x41 and a release that put it back would
// leave the pane narrow. So the pty is read before resize.To, and the release says 56x164.
func TestTheOriginalIsReadBeforeTheWidthFitNarrowsThePty(t *testing.T) {
	f := tallFit(t, true)

	f.press(t, tallRows)

	if a := f.h.store.Active; a.OriginalRows != 56 || a.OriginalCols != 164 {
		t.Fatalf("original recorded as %dx%d; the pty was read after the fit narrowed it", a.OriginalRows, a.OriginalCols)
	}
	if f.pty.Cols != cachedColumns {
		t.Fatalf("the fixture's pty did not follow the window (%dx%d); this test is not testing the order", f.pty.Rows, f.pty.Cols)
	}
	f.daemon.AwaitFrames(t, 5)
	off := f.h.Handle(context.Background(), Request{Verb: VerbResize})
	if !off.OK {
		t.Fatal(off.Error)
	}
	expectRelease(t, f.daemon.AwaitFrames(t, 8), 5, 164)
}

// **A failed release keeps the record until a put-back succeeds.** The daemon dropped the hold's
// connection but is alive and its pty is still tall: the release fails, the daemon is dialled
// afresh with the original, and only that clears the record.
func TestAReleaseOnADeadConnectionDialsTheDaemonAfresh(t *testing.T) {
	f := tallFit(t, true)
	f.press(t, tallRows)
	f.daemon.AwaitFrames(t, 5)
	f.dropHoldConnection(t)

	off := f.h.Handle(context.Background(), Request{Verb: VerbResize})
	if !off.OK {
		t.Fatal(off.Error)
	}

	frames := f.daemon.AwaitFrames(t, 8)
	if frames[5].Conn != 1 {
		t.Fatalf("the put-back did not come on a fresh connection: %+v", frames[5:])
	}
	expectRestore(t, frames, 5)
	if f.h.store.Active != nil || len(f.h.store.PendingHeights) != 0 || f.h.FitInForce() {
		t.Fatalf("active=%+v pending=%+v; a put-back that succeeded must clear the record",
			f.h.store.Active, f.h.store.PendingHeights)
	}
}

// And when the fresh dial fails too, the record stays - on disk, apart from the fit that ended -
// so a later undo can dial it, and does once the daemon answers again.
func TestAPutBackThatFailsIsKeptOnDiskUntilALaterUndoSucceeds(t *testing.T) {
	f := tallFit(t, true)
	f.press(t, tallRows)
	f.daemon.AwaitFrames(t, 5)
	f.dropHoldConnection(t)
	if err := os.Remove(f.daemon.Path); err != nil {
		t.Fatal(err)
	}

	off := f.h.Handle(context.Background(), Request{Verb: VerbResize})
	if !off.OK || off.FitEnabled == nil || *off.FitEnabled || off.Rows != 0 {
		t.Fatalf("off = %+v", off)
	}

	loaded := resize.LoadStore(f.h.stateDir)
	if loaded.Active != nil {
		t.Fatalf("the fit survived off: %+v", loaded.Active)
	}
	if len(loaded.PendingHeights) != 1 {
		t.Fatalf("pending heights on disk = %+v", loaded.PendingHeights)
	}
	if p := loaded.PendingHeights[0]; p.SocketPath != f.daemon.Path || p.Rows != 56 || p.Cols != 164 {
		t.Fatalf("pending height on disk = %+v", p)
	}
	// The palette's undo still has something to do, and the phone's button does not.
	if !f.h.FitInForce() {
		t.Fatal("the control socket would answer 'no fit is in force' with a pty still tall")
	}
	if poll := f.h.Handle(context.Background(), Request{Verb: VerbSessions}); poll.FitEnabled == nil || *poll.FitEnabled || poll.Rows != 0 {
		t.Fatalf("poll = %+v", poll)
	}

	// The daemon answers again; the next undo puts the pty back and the record is spent.
	back := zmxholdtest.StartAt(t, f.daemon.Path)
	if err := f.h.RestoreFit(context.Background()); err != nil {
		t.Fatal(err)
	}
	expectRestore(t, back.AwaitFrames(t, 3), 0)
	if len(f.h.store.PendingHeights) != 0 || f.h.FitInForce() || rowsOnDisk(t, f.h) != 0 {
		t.Fatal("the record outlived the put-back")
	}
	if len(resize.LoadStore(f.h.stateDir).PendingHeights) != 0 {
		t.Fatal("the record outlived the put-back on disk")
	}
}

// The next start retries a pty still waiting, before anything else.
func TestAStartRetriesAPtyStillWaitingToBePutBack(t *testing.T) {
	f := tallFit(t, true)
	f.h.store.PendingHeights = []resize.HeightRestore{{
		Daemon: filepath.Base(f.daemon.Path), SocketPath: f.daemon.Path, Rows: 56, Cols: 164,
	}}
	f.h.publishFit()
	if !f.h.FitInForce() {
		t.Fatal("a pending put-back does not count for the control socket")
	}

	if err := f.h.RestorePending(context.Background()); err != nil {
		t.Fatal(err)
	}

	expectRestore(t, f.daemon.AwaitFrames(t, 3), 0)
	if len(f.h.store.PendingHeights) != 0 || f.h.FitInForce() || len(resize.LoadStore(f.h.stateDir).PendingHeights) != 0 {
		t.Fatal("the record outlived the put-back")
	}
}

// A refused press with the fit intact keeps the height record on it when the put-back fails, so
// the undo that follows can dial it - and does.
func TestARefusedPressKeepsTheRecordWhenThePutBackFails(t *testing.T) {
	f := tallFit(t, true)
	f.press(t, tallRows)
	f.daemon.AwaitFrames(t, 5)
	f.dropHoldConnection(t)
	if err := os.Remove(f.daemon.Path); err != nil {
		t.Fatal(err)
	}

	f.set(func() { f.failWindowList = true })
	resp := f.h.Handle(context.Background(), Request{
		Verb: VerbResize, Session: sessionA, Pane: "left",
		BoxWidthDp: 440, CharacterWidthMilliDp: 9800, Rows: tallRows,
	})
	if resp.OK {
		t.Fatal("the press was supposed to be refused")
	}
	a := f.h.store.Active
	if a == nil || a.Rows != tallRows || a.SocketPath != f.daemon.Path || a.OriginalCols != 164 {
		t.Fatalf("active = %+v; a put-back that failed must leave the record", a)
	}
	if rowsOnDisk(t, f.h) != tallRows {
		t.Fatal("the record is not on disk")
	}

	f.set(func() { f.failWindowList = false })
	back := zmxholdtest.StartAt(t, f.daemon.Path)
	if err := f.h.RestoreFit(context.Background()); err != nil {
		t.Fatal(err)
	}
	expectRestore(t, back.AwaitFrames(t, 3), 0)
	if f.h.store.Active != nil || len(f.h.store.PendingHeights) != 0 {
		t.Fatal("the record outlived the put-back")
	}
}

// expectRestoreTo is expectRestore at a size of the test's choosing.
func expectRestoreTo(t *testing.T, frames []zmxholdtest.Frame, at, rows, cols int) {
	t.Helper()
	if len(frames) < at+3 {
		t.Fatalf("no restore at %d in %+v", at, frames)
	}
	for i := at; i < at+2; i++ {
		if frames[i].Tag != 7 {
			t.Fatalf("frame %d is tag %d, want Init", i, frames[i].Tag)
		}
		if r, c := sizeOf(t, frames[i]); r != rows || c != cols {
			t.Fatalf("restored to %dx%d, want %dx%d", r, c, rows, cols)
		}
	}
	if frames[at+2].Tag != 3 || hasTag(frames[at:at+3], 0) {
		t.Fatalf("frames = %+v", frames[at:])
	}
}

// **An Open that fails is not proof the pty never moved.** The daemon takes the Init and the claim
// and drops the connection before the size lands: the pty is leaderless and may be tall. The record
// is parked with the size the release would have stated - 56 by the 41 columns in force - and the
// next undo puts it back.
func TestAClaimTheDaemonDropsPartwayIsParkedNotForgotten(t *testing.T) {
	f := tallFit(t, true)
	f.daemon.DropOnInput(true)
	// zmxhold.Open's three writes land before the daemon can hang up, so the failure it models - a
	// write that fails because the daemon has gone - is made deterministic here: wait for the
	// hang-up the daemon promised and answer as a failed write would.
	f.h.openHold = func(ctx context.Context, socketPath string, size zmxhold.Size) (*zmxhold.Hold, error) {
		h, err := zmxhold.Open(ctx, socketPath, size)
		if err != nil {
			return nil, err
		}
		// The daemon registers a connection on its own goroutine, so wait for the claim to have
		// arrived - Init twice, then the Input it hangs up on - and for that connection to be gone.
		frames := f.daemon.AwaitFrames(t, 3)
		f.daemon.AwaitClosed(t, frames[len(frames)-1].Conn)
		_ = h.Close()
		return nil, errors.New("zmx daemon: write: broken pipe")
	}

	resp := f.press(t, tallRows)

	if resp.Rows != 0 || resp.Columns != cachedColumns {
		t.Fatalf("reply = %+v", resp)
	}
	if a := f.h.store.Active; a == nil || a.Rows != 0 {
		t.Fatalf("active = %+v", a)
	}
	loaded := resize.LoadStore(f.h.stateDir)
	if len(loaded.PendingHeights) != 1 {
		t.Fatalf("pending on disk = %+v; the record of a claim that may have moved the pty was forgotten", loaded.PendingHeights)
	}
	if p := loaded.PendingHeights[0]; p.SocketPath != f.daemon.Path || p.Rows != 56 || p.Cols != cachedColumns {
		t.Fatalf("parked as %+v, want the pre-fit rows and the columns in force", p)
	}
	if !f.h.FitInForce() {
		t.Fatal("the control socket would answer 'no fit is in force' with a put-back still owed")
	}

	// A later off press retries it: a fresh connection carries Init at the parked size.
	f.daemon.DropOnInput(false)
	f.h.openHold = zmxhold.Open
	before := f.daemon.Connections()
	off := f.h.Handle(context.Background(), Request{Verb: VerbResize})
	if !off.OK {
		t.Fatal(off.Error)
	}
	// The dropped connection recorded its Init and the claim only; the put-back is the three frames
	// on the connection after it.
	frames := f.daemon.AwaitFrames(t, 6)
	var fresh []zmxholdtest.Frame
	for _, fr := range frames {
		if fr.Conn >= before {
			fresh = append(fresh, fr)
		}
	}
	expectRestoreTo(t, fresh, 0, 56, cachedColumns)
	if len(f.h.store.PendingHeights) != 0 || len(resize.LoadStore(f.h.stateDir).PendingHeights) != 0 {
		t.Fatal("the record outlived the put-back")
	}
}

// Two failed put-backs on two daemons are two debts. Both are kept, both are retried on the off
// press and at start, and a second failure on a daemon already owed replaces its entry alone.
func TestEveryParkedPutBackIsKeptAndRetried(t *testing.T) {
	f := tallFit(t, true)
	other := zmxholdtest.Start(t)
	f.h.park(&resize.HeightRestore{Daemon: "agterm-a", SocketPath: f.daemon.Path, Rows: 56, Cols: 41}, "test")
	f.h.park(&resize.HeightRestore{Daemon: "agterm-b", SocketPath: other.Path, Rows: 60, Cols: 120}, "test")
	f.h.park(&resize.HeightRestore{Daemon: "agterm-a", SocketPath: f.daemon.Path, Rows: 56, Cols: 164}, "test")
	f.h.saveStore()
	f.h.publishFit()

	loaded := resize.LoadStore(f.h.stateDir)
	if len(loaded.PendingHeights) != 2 {
		t.Fatalf("pending on disk = %+v; want one entry per daemon", loaded.PendingHeights)
	}
	if loaded.PendingHeights[0].Cols != 164 || loaded.PendingHeights[1].Daemon != "agterm-b" {
		t.Fatalf("pending on disk = %+v; a park on a daemon already owed must replace that entry only", loaded.PendingHeights)
	}
	if !f.h.FitInForce() {
		t.Fatal("two put-backs owed and the control socket says nothing is in force")
	}

	// The off press retries both.
	if err := f.h.RestoreFit(context.Background()); err != nil {
		t.Fatal(err)
	}
	expectRestoreTo(t, f.daemon.AwaitFrames(t, 3), 0, 56, 164)
	expectRestoreTo(t, other.AwaitFrames(t, 3), 0, 60, 120)
	if len(f.h.store.PendingHeights) != 0 || f.h.FitInForce() {
		t.Fatalf("pending = %+v after both were put back", f.h.store.PendingHeights)
	}

	// The start retries whatever a previous run left, both again.
	f.h.store.PendingHeights = []resize.HeightRestore{
		{Daemon: "agterm-a", SocketPath: f.daemon.Path, Rows: 56, Cols: 164},
		{Daemon: "agterm-b", SocketPath: other.Path, Rows: 60, Cols: 120},
	}
	if err := f.h.RestorePending(context.Background()); err != nil {
		t.Fatal(err)
	}
	expectRestoreTo(t, f.daemon.AwaitFrames(t, 6), 3, 56, 164)
	expectRestoreTo(t, other.AwaitFrames(t, 6), 3, 60, 120)
	if len(f.h.store.PendingHeights) != 0 {
		t.Fatalf("pending = %+v after the start", f.h.store.PendingHeights)
	}
}

// A tall press retries every put-back owed BEFORE it claims - on other daemons too, not only the
// one it is about to hold - and one that still fails stays on record.
func TestATallPressRetriesEveryParkedPutBackBeforeItClaims(t *testing.T) {
	f := tallFit(t, true)
	other := zmxholdtest.Start(t)
	gone := filepath.Join(filepath.Dir(other.Path), "agterm-gone")
	f.h.store.PendingHeights = []resize.HeightRestore{
		{Daemon: "agterm-b", SocketPath: other.Path, Rows: 60, Cols: 120},
		{Daemon: "agterm-gone", SocketPath: gone, Rows: 50, Cols: 100},
	}

	resp := f.press(t, tallRows)

	if resp.Rows != tallRows {
		t.Fatalf("reply = %+v", resp)
	}
	// The other daemon was put back before this pane's claim went out...
	expectRestoreTo(t, other.AwaitFrames(t, 3), 0, 60, 120)
	f.daemon.AwaitFrames(t, 5)
	// ...and the one that cannot be reached is still owed, on disk.
	loaded := resize.LoadStore(f.h.stateDir)
	if len(loaded.PendingHeights) != 1 || loaded.PendingHeights[0].Daemon != "agterm-gone" {
		t.Fatalf("pending on disk = %+v", loaded.PendingHeights)
	}
}

// A put-back owed on the very daemon a press wants to hold, and still failing, keeps the press to
// the width: its pty may be tall, so it must not be read as an original, and it is kept on record.
func TestAPressOnADaemonStillOwedIsWidthOnlyAndKeepsTheDebt(t *testing.T) {
	f := tallFit(t, true)
	if err := os.Remove(f.daemon.Path); err != nil {
		t.Fatal(err)
	}
	f.h.store.PendingHeights = []resize.HeightRestore{
		{Daemon: filepath.Base(f.daemon.Path), SocketPath: f.daemon.Path, Rows: 56, Cols: 164},
	}

	resp := f.press(t, tallRows)

	if resp.Rows != 0 || resp.Columns != cachedColumns {
		t.Fatalf("reply = %+v", resp)
	}
	loaded := resize.LoadStore(f.h.stateDir)
	if len(loaded.PendingHeights) != 1 || loaded.PendingHeights[0].Cols != 164 {
		t.Fatalf("pending on disk = %+v", loaded.PendingHeights)
	}
	if a := f.h.store.Active; a == nil || a.Rows != 0 {
		t.Fatalf("active = %+v", a)
	}
}
