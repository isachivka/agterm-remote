package api

import (
	"context"
	"log"
	"path/filepath"
	"sync"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
	"github.com/isachivka/agterm-remote/bridge/internal/palette"
	"github.com/isachivka/agterm-remote/bridge/internal/resize"
	"github.com/isachivka/agterm-remote/bridge/internal/styled"
	"github.com/isachivka/agterm-remote/bridge/internal/zmxhold"
)

// The tall fit: the HEIGHT half of a fit, held through the zmx daemon behind the pane.
//
// # Why the height is not the window's
//
// The width fit resizes the owner's window, and the programs in it lay themselves out at the phone's
// width. The height cannot be done the same way: agterm clamps `window.resize` to the screen, so a
// window tall enough for two hundred rows does not exist. But a Live pane's pty belongs to a zmx
// daemon, and the daemon sizes it to whatever its leader client says. A second client that claims
// leadership and states 200 rows gets a 200-row pty, Claude Code renders 200 rows, and the phone reads
// them all. The desktop pane shows the same stream squeezed into its viewport until the fit is undone
// - accepted by the owner; "Undo phone fit" in the palette is the way out.
//
// # What the log may say here
//
// Row counts, column counts, pty sizes and the outcome of a claim. Not the session id, not the
// daemon's name, not the socket path - the first is his, the other two would let a reader of the log
// dial his pty. See the note at the top of resize.go.

// heightState is the live hold and its pacing. Its own mutex rather than opMu, because the hold is
// checked on the SCREEN path, which must not queue behind a calibration - and released on the
// resize path, which is under opMu. The two meet only here.
type heightState struct {
	mu sync.Mutex
	// held is the connection holding the pty, or nil. Nil after a restart even when the store says
	// a height is in force: that hold died with the old process and only Restore can undo it.
	held *heightHold
	// checkAfter is when the hold may next be checked against the pty. See keepHeight.
	checkAfter time.Time
	// lastKeepErr is the last reason a check could not read the pty, so a persistent failure is
	// logged once rather than on every poll.
	lastKeepErr string
}

// heightHold is one hold and what it needs to be released or re-opened.
type heightHold struct {
	hold       *zmxhold.Hold
	daemon     string
	socketPath string
	// original is the pty's size before the first claim. It is the size Release puts back and it
	// is never re-read while the hold is live: a pty read under a hold reports the hold.
	original zmxhold.Size
}

// holdCheckEvery is how often the screen path may compare the pty with the hold. The phone polls at
// 2 Hz; a check every poll would run `ps` twice a second for a size that moves only when the owner
// types on the Mac, and a re-claim more often than this would fight him for the pane while he is
// typing in it.
const holdCheckEvery = 2 * time.Second

// holdHeight is the tall half of a fit press, after the width has been applied at `columns`.
//
// It answers the rows to report: req.Rows when the pty is held, zero when it is not - and zero is
// an ordinary answer rather than a refusal, because the width is already in force and a pane with
// no daemon behind it is the normal state of a session created before Live mode was on.
//
// `previous` is the fit that was in force before this press, if any. It matters twice: a press
// with no rows lets a previously held height go, and a press on the same daemon keeps the original
// size that press recorded rather than reading a pty that is currently reporting the hold.
func (h *Handler) holdHeight(ctx context.Context, req Request, columns int, previous *resize.Fit) int {
	if req.Rows == 0 {
		// Width only. If a height was held it is let go now: the phone's zmx setting went off, or
		// this is a phone that never asked for one, and either way it is no longer asked for.
		h.releaseHeight(ctx, previous)
		return 0
	}

	pane, err := paneFor(req)
	if err != nil {
		log.Printf("height: REFUSED - %v; width only", err)
		return 0
	}
	inv, err := h.client.ZmxList(ctx)
	if err != nil {
		log.Printf("height: the daemon inventory could not be read (%v); width only", describe(err))
		return 0
	}
	entry, ok := styled.Entry(inv.Entries, req.Session, string(pane))
	if !ok || inv.SocketDir == "" {
		log.Printf("height: no daemon behind this pane; width only")
		h.releaseHeight(ctx, previous)
		return 0
	}
	socketPath := filepath.Join(inv.SocketDir, entry.Daemon)

	// The way out, installed before the thing it undoes. Best effort: a keymap that cannot be
	// written is not a reason to refuse the fit the phone is holding the other way out of.
	h.ensurePalette(ctx)

	size := zmxhold.Size{Rows: req.Rows, Cols: columns}

	h.height.mu.Lock()
	defer h.height.mu.Unlock()

	// A hold on some OTHER daemon - the phone moved to another session - is released first, at
	// that pane's original size. One pane is held at a time, because one pane is being read.
	if h.height.held != nil && h.height.held.daemon != entry.Daemon {
		h.releaseLocked(nil)
	}

	var original zmxhold.Size
	switch {
	case h.height.held != nil:
		original = h.height.held.original
		if h.height.held.hold.Err() == nil {
			if err := h.height.held.hold.Claim(size); err == nil {
				log.Printf("height: re-claimed %d rows by %d columns on the held pane", size.Rows, size.Cols)
				h.recordHeight(req, entry, socketPath, size, original)
				return req.Rows
			}
		}
		// The connection is gone or would not take the claim: open a fresh one below, keeping the
		// original size this hold was opened with.
		_ = h.height.held.hold.Close()
		h.height.held = nil
	case previous != nil && previous.Rows > 0 && previous.Daemon == entry.Daemon &&
		previous.OriginalRows > 0 && previous.OriginalCols > 0:
		// No live hold, but the store remembers one on this daemon: this process restarted while the
		// fit was on and the startup restore did not reach the daemon. The pty may still be at the
		// held size, so the recorded original is the truth and a fresh read of the pty is not.
		original = zmxhold.Size{Rows: previous.OriginalRows, Cols: previous.OriginalCols}
	default:
		read, err := h.ptySize(int(entry.LeaderPID))
		if err != nil {
			log.Printf("height: the pane's pty size could not be read (%v); width only", err)
			return 0
		}
		original = zmxhold.Size{Rows: read.Rows, Cols: read.Cols}
	}

	hold, err := zmxhold.Open(ctx, socketPath, size)
	if err != nil {
		log.Printf("height: the daemon would not take the hold (%v); width only", err)
		return 0
	}
	h.height.held = &heightHold{hold: hold, daemon: entry.Daemon, socketPath: socketPath, original: original}
	// Just claimed, so the first check is due one interval from now, not on the next poll.
	h.height.checkAfter = h.now().Add(holdCheckEvery)
	log.Printf("height: holding the pane at %d rows by %d columns; its pty was %d by %d",
		size.Rows, size.Cols, original.Rows, original.Cols)
	h.recordHeight(req, entry, socketPath, size, original)
	return req.Rows
}

// recordHeight writes the hold into the fit in force, so every reply can say it and a restart can
// undo it. The caller saves the store; this only sets the fields.
func (h *Handler) recordHeight(req Request, entry agterm.ZmxEntry, socketPath string, size, original zmxhold.Size) {
	if h.store == nil || h.store.Active == nil {
		return
	}
	a := h.store.Active
	a.Rows, a.Session, a.Pane = size.Rows, req.Session, req.Pane
	if a.Pane == "" {
		a.Pane = string(agterm.PaneLeft)
	}
	a.Daemon, a.SocketPath = entry.Daemon, socketPath
	a.OriginalRows, a.OriginalCols = original.Rows, original.Cols
}

// ensurePalette installs the "Undo phone fit" line, logging rather than failing.
func (h *Handler) ensurePalette(ctx context.Context) {
	if h.executable == "" || h.stateDir == "" {
		log.Printf("palette: this binary does not know its own path, so the undo command was not installed")
		return
	}
	changed, err := palette.Ensure(ctx, h.client, h.executable, h.stateDir)
	switch {
	case err != nil:
		log.Printf("palette: %v", err)
	case changed:
		log.Printf("palette: installed %q in agterm's keymap", palette.Name)
	}
}

// keepHeight is the hold check, run from the screen path for the pane that is held.
//
// # Why a hold has to be kept
//
// Leadership is the last client that typed. When the owner types in the pane on the Mac, agterm's
// client becomes leader and its size is applied, and the pty shrinks back to the pane. Nothing
// tells this process. So the phone's own poll of that pane - the thing that wants the rows - reads
// the pty's size through the shell's tty and, when it is not the held size, claims again.
//
// # Paced, and the pacing is the courtesy
//
// At most once per [holdCheckEvery]. The owner typing on the Mac and the phone re-claiming are in
// competition for the pane, and the design accepts that the pane flips between sizes until he runs
// "Undo phone fit" - but flipping twice a second would make the Mac unusable for the seconds it
// takes him to reach the palette. Once every two seconds is a pane he can still read.
func (h *Handler) keepHeight(ctx context.Context, session, pane string, inv *agterm.ZmxList) {
	h.height.mu.Lock()
	defer h.height.mu.Unlock()
	if h.height.held == nil {
		return
	}
	now := h.now()
	if now.Before(h.height.checkAfter) {
		return
	}
	h.height.checkAfter = now.Add(holdCheckEvery)

	entry, ok := styled.Entry(inv.Entries, session, pane)
	if !ok || entry.Daemon != h.height.held.daemon {
		// The pane's daemon is not the one held: it went away, or was replaced. The hold is stale
		// and the next fit press replaces it; there is nothing to keep here.
		return
	}
	read, err := h.ptySize(int(entry.LeaderPID))
	if err != nil {
		if msg := err.Error(); msg != h.height.lastKeepErr {
			log.Printf("height: the held pane's pty could not be read (%v)", err)
			h.height.lastKeepErr = msg
		}
		return
	}
	h.height.lastKeepErr = ""
	want := h.height.held.hold.Held()
	if read.Rows == want.Rows && read.Cols == want.Cols {
		return
	}

	if h.height.held.hold.Err() != nil {
		// The daemon dropped the connection - it restarted, or agterm detached everything. A
		// Claim on a dead socket cannot help; a fresh connection with the same original can.
		fresh, err := zmxhold.Open(ctx, h.height.held.socketPath, want)
		if err != nil {
			log.Printf("height: the pty is %d by %d, the hold's connection is gone and a new one "+
				"could not be opened (%v)", read.Rows, read.Cols, err)
			return
		}
		_ = h.height.held.hold.Close()
		h.height.held.hold = fresh
		log.Printf("height: the pty was %d by %d and the hold's connection was gone; re-opened at %d by %d",
			read.Rows, read.Cols, want.Rows, want.Cols)
		return
	}
	if err := h.height.held.hold.Claim(want); err != nil {
		log.Printf("height: the pty is %d by %d and the re-claim failed (%v)", read.Rows, read.Cols, err)
		return
	}
	log.Printf("height: the pty was %d by %d; re-claimed %d by %d", read.Rows, read.Cols, want.Rows, want.Cols)
}

// releaseHeight lets a held height go, BEFORE whatever window restore follows it.
//
// The live hold is released at the original size it was opened with. When none is live - this
// process restarted while the fit was on - the fit on disk says which daemon and which size, and
// [zmxhold.Restore] dials it fresh. Errors are logged and never returned: a daemon that has gone
// away has taken its pty with it, and the window restore that follows must not wait on it.
func (h *Handler) releaseHeight(ctx context.Context, fit *resize.Fit) {
	h.height.mu.Lock()
	defer h.height.mu.Unlock()
	h.releaseLocked(fit)
	if fit != nil && fit.Rows > 0 && fit.SocketPath != "" && fit.OriginalRows > 0 && fit.OriginalCols > 0 {
		// Only when nothing live was released above: releaseLocked clears the hold it releases, and
		// a fit that names a daemon with no hold in this process is exactly the restart case.
		original := zmxhold.Size{Rows: fit.OriginalRows, Cols: fit.OriginalCols}
		if err := zmxhold.Restore(ctx, fit.SocketPath, original); err != nil {
			log.Printf("height: the pty could not be put back to %d by %d (%v)", original.Rows, original.Cols, err)
			return
		}
		log.Printf("height: put the pty back to %d by %d from the record of a previous run", original.Rows, original.Cols)
		fit.Rows = 0
	}
}

// releaseLocked releases the live hold, if there is one, and clears it. The caller holds height.mu.
// When it releases something, fit is cleared of its rows too, so the caller's Restore path does not
// then dial the same daemon a second time.
func (h *Handler) releaseLocked(fit *resize.Fit) {
	held := h.height.held
	if held == nil {
		return
	}
	h.height.held = nil
	if err := held.hold.Release(held.original); err != nil {
		log.Printf("height: releasing the pty to %d by %d: %v", held.original.Rows, held.original.Cols, err)
	} else {
		log.Printf("height: released the pty to %d by %d", held.original.Rows, held.original.Cols)
	}
	if fit != nil {
		fit.Rows = 0
	}
}
