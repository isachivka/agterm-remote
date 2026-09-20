package api

import (
	"context"
	"fmt"
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
// # The record and the hold are two things, and every exit keeps them agreeing
//
// The hold is a live connection in [heightState]; the record is the height fields on
// [resize.Store.Active], which every reply is stamped from and which a restart reads to undo a hold
// that died with the old process. `resize.To` replaces Active wholesale on every press, so the
// record is written back by [Handler.holdHeight] alone - and therefore EVERY path out of it that does
// not end holding the pty must release the live hold, or the pty stays tall while every reply says
// it is not and nothing on disk can put it back. That was the shape of the first review's major
// finding, and it is why the exits below all go through the same two calls.
//
// # What the log may say here
//
// Row counts, column counts, pty sizes and the outcome of a claim. Not the session id, not the
// daemon's name, not the socket path - the first is his, the other two would let a reader of the log
// dial his pty. zmxhold strips the socket address from the errors it returns, so `%v` on one of them
// is safe; agterm's errors go through [describe] for the same reason. See the note at the top of
// resize.go.

// heightState is the live hold and its pacing. Its own mutex rather than opMu, because the hold is
// checked on the SCREEN path, which must not queue behind a calibration - and released on the
// resize path, which is under opMu. Lock order is opMu, then height.mu, everywhere; nothing that
// holds height.mu takes opMu.
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

// holdCheckEvery is the least time between two checks of the pty against the hold, and so between
// two re-claims.
//
// The phone itself polls every two seconds (POLL_INTERVAL_MS), so for the one phone this bridge
// serves today the gate is nearly a no-op. It is not there for that phone. It bounds what any
// caller can make this path do: a phone polling faster, two phones on one pane, or a burst of reads
// after a reconnect, none of which may turn into `ps` and a claim per read. And it bounds the rate
// the pane on the Mac can flip between sizes while the owner types in it - each keystroke hands the
// pty to agterm, each check takes it back - so however fast the reads come, the pane changes size at
// most once every two seconds until he runs "Undo phone fit".
const holdCheckEvery = 2 * time.Second

// tallPlan is what the resize handler learns about the pane BEFORE the width fit moves anything.
//
// It exists for one number: the pty's size before the fit. holdHeight runs after resize.To, and by
// then the window has been narrowed and the pty with it, so a size read there is the FITTED width -
// 56 by 41 - and a release that put that back would leave the pane narrow with nothing on record
// to say it ever was wider. So the pane is looked up and its pty read first, while it still has the
// size the owner had, and the answer travels into holdHeight. The lookup's failures travel too, so
// holdHeight can still log them and release whatever was held.
type tallPlan struct {
	// reason is why no height can be held, or empty. Written for the log.
	reason     string
	entry      agterm.ZmxEntry
	socketPath string
	// original is the pty as it was before the fit, read only when nothing else already knows it:
	// a live hold on this daemon, the record of one, or a pending put-back all take precedence,
	// because a pty under a hold reports the hold. Nil when the read failed; readErr says why.
	original *zmxhold.Size
	readErr  error
}

// planHeight runs before resize.To. Nil when the press asks for no height.
func (h *Handler) planHeight(ctx context.Context, req Request, previous *resize.Fit) *tallPlan {
	if req.Rows == 0 {
		return nil
	}
	plan := &tallPlan{}
	pane, err := paneFor(req)
	if err != nil {
		plan.reason = fmt.Sprintf("REFUSED - %v", err)
		return plan
	}
	inv, err := h.client.ZmxList(ctx)
	if err != nil {
		plan.reason = fmt.Sprintf("the daemon inventory could not be read (%v)", describe(err))
		return plan
	}
	entry, ok := styled.Entry(inv.Entries, req.Session, string(pane))
	if !ok || inv.SocketDir == "" {
		plan.reason = "no daemon behind this pane"
		return plan
	}
	plan.entry, plan.socketPath = entry, filepath.Join(inv.SocketDir, entry.Daemon)
	if h.originalKnown(entry.Daemon, previous) {
		return plan
	}
	read, err := h.ptySize(int(entry.LeaderPID))
	if err != nil {
		plan.readErr = err
		return plan
	}
	plan.original = &zmxhold.Size{Rows: read.Rows, Cols: read.Cols}
	return plan
}

// originalKnown says whether something already records this daemon's pty as it was before any
// hold, in which case the pty must NOT be read now: it is reporting a hold, or may be.
func (h *Handler) originalKnown(daemon string, previous *resize.Fit) bool {
	h.height.mu.Lock()
	defer h.height.mu.Unlock()
	if h.height.held != nil && h.height.held.daemon == daemon {
		return true
	}
	if recordNames(previous, daemon) {
		return true
	}
	return h.store != nil && h.store.PendingHeight != nil && h.store.PendingHeight.Daemon == daemon
}

// hasRecord says whether fit carries a complete height record; recordNames, one for this daemon.
func hasRecord(fit *resize.Fit) bool {
	return fit != nil && fit.Rows > 0 && fit.Daemon != "" && fit.SocketPath != "" &&
		fit.OriginalRows > 0 && fit.OriginalCols > 0
}

func recordNames(fit *resize.Fit, daemon string) bool {
	return hasRecord(fit) && fit.Daemon == daemon
}

// holdHeight is the tall half of a fit press, after the width has been applied at `columns`.
//
// It answers the rows to report: req.Rows when the pty is held, zero when it is not - and zero is
// an ordinary answer rather than a refusal, because the width is already in force and a pane with
// no daemon behind it is the normal state of a session created before Live mode was on.
//
// `previous` is the fit that was in force before this press, if any. It matters twice: a press
// that ends without a height lets a previously held one go, and a press on the same daemon keeps the
// original size that press recorded rather than reading a pty that is currently reporting the hold.
// `plan` is what planHeight learned before the width fit; nil when no height was asked for.
func (h *Handler) holdHeight(ctx context.Context, req Request, columns int, previous *resize.Fit, plan *tallPlan) int {
	if plan == nil {
		// Width only. If a height was held it is let go now: the phone's zmx setting went off, or
		// this is a phone that never asked for one, and either way it is no longer asked for.
		h.giveUpHeight(ctx, previous)
		return 0
	}
	// Every "width only" exit from here on releases as well: the record has just been replaced by a
	// fresh width, so a hold that survived one of them would be a hold nothing remembers.
	if plan.reason != "" {
		log.Printf("height: %s; width only", plan.reason)
		h.giveUpHeight(ctx, previous)
		return 0
	}
	entry, socketPath := plan.entry, plan.socketPath

	// The way out, installed before the thing it undoes. Best effort: a keymap that cannot be
	// written is not a reason to refuse the fit the phone is holding the other way out of.
	h.InstallPalette(ctx)

	size := zmxhold.Size{Rows: req.Rows, Cols: columns}

	h.height.mu.Lock()
	defer h.height.mu.Unlock()

	// A hold on some OTHER pane - the phone moved to another session - is let go first, at that
	// pane's original size, before this press overwrites the record of it. One pane is held at a
	// time, because one pane is being read. A put-back that fails is parked rather than forgotten.
	if (h.height.held != nil && h.height.held.daemon != entry.Daemon) ||
		(hasRecord(previous) && previous.Daemon != entry.Daemon) {
		if back, lost := h.letGoLocked(ctx, previous); !back {
			h.park(lost, "the record must give way to a fit on another pane")
		}
	}

	var original zmxhold.Size
	switch {
	case h.height.held != nil:
		original = h.height.held.original
	case recordNames(previous, entry.Daemon):
		// No live hold, but the store remembers one on this daemon: this process restarted while the
		// fit was on and the startup restore did not reach the daemon. The pty may still be at the
		// held size, so the recorded original is the truth and a fresh read of the pty is not.
		original = zmxhold.Size{Rows: previous.OriginalRows, Cols: previous.OriginalCols}
	case h.store.PendingHeight != nil && h.store.PendingHeight.Daemon == entry.Daemon:
		// A put-back that failed on this very daemon: this hold adopts what it owed, and will owe it
		// itself. The pending record is spent by that adoption.
		original = zmxhold.Size{Rows: h.store.PendingHeight.Rows, Cols: h.store.PendingHeight.Cols}
		h.store.PendingHeight = nil
	case plan.original != nil:
		original = *plan.original
	default:
		log.Printf("height: the pane's pty size could not be read (%v); width only", plan.readErr)
		h.giveUpLocked(ctx, previous)
		return 0
	}

	// **On disk before the pty moves.** The record is what a restart, or "Undo phone fit" after one,
	// uses to put the pty back; written after the claim it would protect only the path that was
	// never at risk. resize.To makes the same argument for the window's restore point, and the
	// height is held to it too. Cleared again below if the claim does not happen.
	h.recordHeight(req, entry, socketPath, size, original)
	h.saveStore()

	if h.height.held != nil {
		if h.height.held.hold.Err() == nil {
			// A press on a pane already held - a re-press, or the automatic re-apply on a session
			// switch. The pty is read first: a pty already at this size needs no claim, and a claim it
			// does not need is a paste into the owner's program and two round trips for nothing.
			if read, err := h.ptySize(int(entry.LeaderPID)); err == nil &&
				read.Rows == size.Rows && read.Cols == size.Cols {
				h.height.checkAfter = h.now().Add(holdCheckEvery)
				log.Printf("height: the pane is already held at %d rows by %d columns", size.Rows, size.Cols)
				return req.Rows
			}
			err := h.height.held.hold.Claim(size)
			if err == nil {
				h.height.checkAfter = h.now().Add(holdCheckEvery)
				log.Printf("height: re-claimed %d rows by %d columns on the held pane", size.Rows, size.Cols)
				return req.Rows
			}
			// A live connection that would not take the claim is let go properly - at its original,
			// so the pty is known to be where it was - and a fresh one opened below with the same
			// original. Nothing is parked here: the fresh hold owes the same put-back.
			log.Printf("height: the re-claim failed (%v); opening a fresh hold", err)
			h.letGoLocked(ctx, nil)
		} else {
			// The daemon dropped the connection. There is nothing to release on it; the original is
			// kept for the fresh connection below.
			_ = h.height.held.hold.Close()
			h.height.held = nil
		}
	}

	hold, err := zmxhold.Open(ctx, socketPath, size)
	if err != nil {
		log.Printf("height: the daemon would not take the hold (%v); width only", err)
		// The record written above promised a height that is not held. Taken back, on disk.
		clearHeight(h.store.Active)
		h.saveStore()
		return 0
	}
	h.height.held = &heightHold{hold: hold, daemon: entry.Daemon, socketPath: socketPath, original: original}
	// Just claimed, so the first check is due one interval from now, not on the next poll.
	h.height.checkAfter = h.now().Add(holdCheckEvery)
	log.Printf("height: holding the pane at %d rows by %d columns; its pty was %d by %d",
		size.Rows, size.Cols, original.Rows, original.Cols)
	return req.Rows
}

// giveUpHeight is the width-only exit: whatever was held is let go, and a put-back that fails is
// parked on the store so a later undo, the next tall press or the next start can dial it.
func (h *Handler) giveUpHeight(ctx context.Context, previous *resize.Fit) {
	h.height.mu.Lock()
	defer h.height.mu.Unlock()
	h.giveUpLocked(ctx, previous)
}

func (h *Handler) giveUpLocked(ctx context.Context, previous *resize.Fit) {
	if back, lost := h.letGoLocked(ctx, previous); !back {
		h.park(lost, "the fit went on without a height")
		h.saveStore()
	}
}

// park keeps the record of a pty that could not be put back, apart from the fit that is ending.
// The caller saves the store. See resize.Store.PendingHeight.
func (h *Handler) park(lost *resize.HeightRestore, why string) {
	if lost == nil || h.store == nil {
		return
	}
	if h.store.PendingHeight != nil && *h.store.PendingHeight != *lost {
		log.Printf("height: a pty of %d by %d was still waiting to be put back and is now forgotten for a newer one",
			h.store.PendingHeight.Rows, h.store.PendingHeight.Cols)
	}
	h.store.PendingHeight = lost
	log.Printf("height: %s and the pty could not be put back to %d by %d; kept on record for the next undo",
		why, lost.Rows, lost.Cols)
}

// retryPendingHeight dials a parked put-back again. It reports whether the store changed.
func (h *Handler) retryPendingHeight(ctx context.Context) bool {
	if h.store == nil || h.store.PendingHeight == nil {
		return false
	}
	p := h.store.PendingHeight
	if err := zmxhold.Restore(ctx, p.SocketPath, zmxhold.Size{Rows: p.Rows, Cols: p.Cols}); err != nil {
		log.Printf("height: a pty of %d by %d is still waiting to be put back (%v)", p.Rows, p.Cols, err)
		return false
	}
	log.Printf("height: put a pty back to %d by %d that an earlier release could not", p.Rows, p.Cols)
	h.store.PendingHeight = nil
	return true
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

// clearHeight takes the height record off a fit, leaving its width exactly as it was.
func clearHeight(fit *resize.Fit) {
	if fit == nil {
		return
	}
	fit.Rows, fit.Session, fit.Pane = 0, "", ""
	fit.Daemon, fit.SocketPath = "", ""
	fit.OriginalRows, fit.OriginalCols = 0, 0
}

// InstallPalette puts "Undo phone fit" in agterm's command palette, naming this binary where it is,
// logging rather than failing. Called once at startup and before every tall claim - the line names
// a path inside the app bundle, and the bundle moves with every install, which is why the bridge
// writes the line rather than the owner.
func (h *Handler) InstallPalette(ctx context.Context) {
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

// keepHeight is the hold check, run from the screen path for the pane that is held. It reports
// whether the hold has been LOST - the pane is no longer behind the daemon that was held - which is
// the caller's cue to drop the height under opMu, since this runs outside it.
//
// # Why a hold has to be kept
//
// Leadership is the last client that typed. When the owner types in the pane on the Mac, agterm's
// client becomes leader and its size is applied, and the pty shrinks back to the pane. Nothing
// tells this process. So the phone's own poll of that pane - the thing that wants the rows - reads
// the pty's size through the shell's tty and, when it is not the held size, claims again.
//
// # Paced
//
// At most once per [holdCheckEvery], whatever the read rate; see that constant for what the pacing
// protects. A check that could not read the pty, or could not re-claim, is retried at the next
// interval and recovers by itself once the socket does.
func (h *Handler) keepHeight(ctx context.Context, session, pane string, inv *agterm.ZmxList) (lost bool) {
	h.height.mu.Lock()
	defer h.height.mu.Unlock()
	if h.height.held == nil {
		return false
	}
	now := h.now()
	if now.Before(h.height.checkAfter) {
		return false
	}
	h.height.checkAfter = now.Add(holdCheckEvery)

	entry, ok := styled.Entry(inv.Entries, session, pane)
	if !ok || entry.Daemon != h.height.held.daemon {
		// The pane is not behind the daemon that was held: it went away, or was replaced. Nothing
		// this check can do will hold that pane, so the height must stop being reported.
		log.Printf("height: the held pane is no longer behind the daemon that was held")
		return true
	}
	read, err := h.ptySize(int(entry.LeaderPID))
	if err != nil {
		if msg := err.Error(); msg != h.height.lastKeepErr {
			log.Printf("height: the held pane's pty could not be read (%v)", err)
			h.height.lastKeepErr = msg
		}
		return false
	}
	h.height.lastKeepErr = ""
	want := h.height.held.hold.Held()
	if read.Rows == want.Rows && read.Cols == want.Cols {
		return false
	}

	if h.height.held.hold.Err() != nil {
		// The daemon dropped the connection - it restarted, or agterm detached everything. A
		// Claim on a dead socket cannot help; a fresh connection with the same original can.
		fresh, err := zmxhold.Open(ctx, h.height.held.socketPath, want)
		if err != nil {
			log.Printf("height: the pty is %d by %d, the hold's connection is gone and a new one "+
				"could not be opened (%v)", read.Rows, read.Cols, err)
			return false
		}
		_ = h.height.held.hold.Close()
		h.height.held.hold = fresh
		log.Printf("height: the pty was %d by %d and the hold's connection was gone; re-opened at %d by %d",
			read.Rows, read.Cols, want.Rows, want.Cols)
		return false
	}
	if err := h.height.held.hold.Claim(want); err != nil {
		log.Printf("height: the pty is %d by %d and the re-claim failed (%v)", read.Rows, read.Cols, err)
		return false
	}
	log.Printf("height: the pty was %d by %d; re-claimed %d by %d", read.Rows, read.Cols, want.Rows, want.Cols)
	return false
}

// dropHeight lets the height go from the SCREEN path, which is the one caller outside opMu.
//
// Two things bring it here: a plain read of the held pane, which says the phone no longer reads
// that pane with colours through zmx and so no longer wants a height (the setting is the only thing
// that asks for one, and the phone sends no press when it is switched off); and [keepHeight]
// reporting the hold lost. Both change the store, and the store is changed only under opMu - see
// serialize.go - so this takes it, the way the resize verb does, and republishes before letting go.
// A screen read waits behind a calibration here at most once: after it, the pane is not held and
// this is not reached.
//
// A put-back that fails is parked, not retried from here: retrying on every poll would dial the
// daemon every two seconds and log every time. The next undo, tall press or start retries it.
func (h *Handler) dropHeight(ctx context.Context, why string) {
	h.opMu.Lock()
	defer h.opMu.Unlock()
	// Re-read under the lock: an off press, or another read, may have let it go already.
	if h.store == nil || h.store.Active == nil || h.store.Active.Rows == 0 {
		return
	}
	log.Printf("height: %s; letting the height go", why)
	if back, lost := h.releaseHeight(ctx, h.store.Active); !back {
		h.park(lost, why)
	}
	clearHeight(h.store.Active)
	h.saveStore()
	h.publishFit()
}

// releaseHeight lets a held height go, BEFORE whatever window restore follows it. See letGoLocked
// for what it does and what it answers.
func (h *Handler) releaseHeight(ctx context.Context, fit *resize.Fit) (back bool, lost *resize.HeightRestore) {
	h.height.mu.Lock()
	defer h.height.mu.Unlock()
	return h.letGoLocked(ctx, fit)
}

// letGoLocked puts the held pty back and says whether it is known to be back. The caller holds
// height.mu.
//
// A live hold is released at the original size it was opened with. If that fails - the daemon
// dropped our socket but is alive, its pty still tall - the daemon is dialled afresh with what the
// hold knew, which is what [zmxhold.Restore] exists for. With no live hold, the record on `fit` says
// which daemon and which size, and it is dialled the same way: this process restarted while the fit
// was on.
//
// **The record is cleared only by a put-back that succeeded.** On failure `fit` keeps its fields
// and `lost` describes what is still owed, for the caller to keep - on the fit, or parked on the
// store when the fit is ending. Errors are logged and never returned: a daemon that has gone away
// has taken its pty with it, and the window restore that follows must not wait on it.
func (h *Handler) letGoLocked(ctx context.Context, fit *resize.Fit) (back bool, lost *resize.HeightRestore) {
	if held := h.height.held; held != nil {
		h.height.held = nil
		owed := &resize.HeightRestore{Daemon: held.daemon, SocketPath: held.socketPath,
			Rows: held.original.Rows, Cols: held.original.Cols}
		if err := held.hold.Release(held.original); err == nil {
			log.Printf("height: released the pty to %d by %d", held.original.Rows, held.original.Cols)
			clearHeight(fit)
			return true, nil
		} else {
			log.Printf("height: releasing the pty to %d by %d failed (%v); dialling the daemon afresh",
				held.original.Rows, held.original.Cols, err)
		}
		if err := zmxhold.Restore(ctx, held.socketPath, held.original); err != nil {
			log.Printf("height: the pty could not be put back to %d by %d (%v)", held.original.Rows, held.original.Cols, err)
			return false, owed
		}
		log.Printf("height: put the pty back to %d by %d on a fresh connection", held.original.Rows, held.original.Cols)
		clearHeight(fit)
		return true, nil
	}
	if !hasRecord(fit) {
		return true, nil
	}
	original := zmxhold.Size{Rows: fit.OriginalRows, Cols: fit.OriginalCols}
	owed := &resize.HeightRestore{Daemon: fit.Daemon, SocketPath: fit.SocketPath, Rows: original.Rows, Cols: original.Cols}
	if err := zmxhold.Restore(ctx, fit.SocketPath, original); err != nil {
		log.Printf("height: the pty could not be put back to %d by %d (%v)", original.Rows, original.Cols, err)
		return false, owed
	}
	log.Printf("height: put the pty back to %d by %d from the record of a previous run", original.Rows, original.Cols)
	clearHeight(fit)
	return true, nil
}
