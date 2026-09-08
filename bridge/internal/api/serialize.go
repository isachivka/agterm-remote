package api

import (
	"context"
	"sync"
)

// Serialization for the store, and **this lock is the price of a second caller.**
//
// # Do not remove it while looking at a single-caller trace
//
// Until the local control socket existed, the phone was the only caller in practice: one connection,
// requests serialized within it by the listener, and the store touched by one goroutine at a time by
// accident of deployment rather than by design. Reading this code with only the phone in mind makes
// every lock below look redundant. It is not — the palette command is an independent caller that can
// arrive at any instant, including in the middle of a seven-second calibration.
//
// What is at stake is not an inconsistent reply. It is `pending_restore`, the file holding the only
// record of where the owner's window came from, and a corruption here would be rare, unreproducible,
// and would cost them a window they cannot get back. The owner's instruction on the day this was
// written was: do not break it. This is the way it would have broken.
//
// # Two locks, because one would trade a rare bug for a visible one
//
// [Handler.opMu] serializes fit OPERATIONS: a calibration in flight and a restore from the palette
// cannot interleave, and the second one waits. It is held for the whole resize verb, which can be
// seven seconds of live window resizing.
//
// [Handler.stateMu] guards the flag every reply carries. A poll must not wait behind a calibration to
// learn a boolean it reads in nanoseconds — the phone would freeze for the length of the calibration,
// which is trading a rare corruption for a bug the owner sees every time.
//
// # Why the poll reads a published flag rather than the store
//
// The store's own fields are written inside `internal/resize`, which is FROZEN by the owner's ruling
// after the width feature was confirmed working. Locking inside `Store` would be the tidier design and
// it would mean touching that package; that is a conversation to have deliberately, not a thing to
// slip into a diff about a palette command.
//
// So instead: every mutation of the store happens inside an operation serialized by [Handler.opMu],
// and [Handler.publishFit] refreshes the flag at the end of every such operation while holding
// [Handler.stateMu]. Readers never touch the store at all. The flag therefore cannot drift — there is
// no path that changes `Active` without passing through the code that republishes it — and no reader
// blocks for longer than it takes to read a bool.

// serialized runs the dispatch, holding the operation lock for the one verb that can touch the store.
//
// Every other verb goes straight through. `sessions`, `screen`, `type` and `file` do not read or write
// the store, and making them queue behind a calibration would freeze the owner's screen to protect a
// file they are not touching.
func (h *Handler) serialized(ctx context.Context, req Request) Response {
	if req.Verb != VerbResize || h.store == nil {
		return h.dispatch(ctx, req)
	}

	h.opMu.Lock()
	defer h.opMu.Unlock()

	resp := h.dispatch(ctx, req)
	// **Republished before the lock is released**, so no caller can observe the store changed and the
	// flag not. This is the single line that keeps the two from ever disagreeing.
	h.publishFit()
	return resp
}

// publishFit copies the setting out of the store for readers that must not block.
//
// Called at construction and after every operation that could have changed it. Both callers hold
// [Handler.opMu] or run before the listener exists, which is what makes this a copy of a value nobody
// else is writing.
//
// **The flag and the count are published TOGETHER, under one lock, from one read of the store.** They
// are one fact - a setting and the column count it is in force at - and the same rule has already been
// applied twice to a window width and the count that width produced. Publishing the bool alone is what
// made every poll carry `fit_enabled: true` with no columns at all, so the phone's own
// `optInt` read zero and rendered it.
func (h *Handler) publishFit() {
	var (
		inForce bool
		columns int
	)
	if h.store != nil && h.store.Active != nil {
		inForce = true
		columns = h.store.Active.Columns
	}

	h.stateMu.Lock()
	h.fitInForce, h.fitColumns = inForce, columns
	// **A completed operation re-establishes the truth, so the old measurement is discarded rather
	// than left to argue with it.** Whatever the pty showed a moment ago described a window that has
	// just been resized; keeping it would let a stale reading suppress a fit that genuinely is in
	// force, which is this defect pointed the other way.
	h.measuredColumns = 0
	h.stateMu.Unlock()
}

// noteMeasuredColumns records what the pty last showed, so the reply can tell recall from measurement.
//
// Called from the screen path, which already has the text: this costs one pass over a string that was
// about to be hashed anyway, and no agterm round trip at all. Nothing here decides anything - see
// [fitOutgrown] for the judgement and [Handler.fitOnTheWire] for where it is applied.
func (h *Handler) noteMeasuredColumns(columns int) {
	h.stateMu.Lock()
	h.measuredColumns = columns
	h.stateMu.Unlock()
}

// fitOutgrown reports whether a screen PROVES the window is no longer at the width it was fitted to.
//
// # One-sided on purpose, and the asymmetry is the whole design
//
// The cheap measurement available on the poll path is the widest line the session drew, which is a
// FLOOR on the pane's width and not the width itself: a program that does not paint to the edge
// under-reports, and 2 of 27 real sessions did exactly that when this was measured for `internal/resize`.
//
// So the two directions are not equally knowable:
//
//   - `measured > fitColumns` — a line WIDER than the fit exists. It cannot have been drawn inside a
//     window still fitted to that count, whatever the program was doing. **Proof.**
//   - `measured < fitColumns` — either the owner narrowed the window or the program simply drew a
//     short screen. **Indistinguishable, so this function says nothing about it.**
//
// The owner's report is the provable direction: they press fit, then stretch the window by hand, and
// the phone goes on claiming the fit is on. Someone dragging their window NARROWER is not detected and
// this is not a gap to be closed by loosening the test - a false "the fit is off" is the same class of
// lie as the one being fixed, told in the other direction.
func fitOutgrown(measured, fitColumns int) bool {
	return fitColumns > 0 && measured > fitColumns
}

// fitNow is the guarded read behind [Handler.FitInForce] and the fields on every reply. It returns
// both, because a caller that took them in two calls could be interrupted between them and publish a
// pair that was never true at once.
func (h *Handler) fitNow() (inForce bool, columns int) {
	h.stateMu.RLock()
	defer h.stateMu.RUnlock()
	return h.fitInForce, h.fitColumns
}

// fitOnTheWire is what the PHONE is told: the setting, reduced by what the screen has since proved.
//
// # Why this is not simply fitNow
//
// [Handler.FitInForce] and this answer different questions, and collapsing them would break one of
// them. `FitInForce` backs the local `agtermfit` command, whose next move is to restore the owner's
// window from `pending_restore` — so it must stay tied to whether that restore point exists, which is
// exactly the invariant `serialize_test` asserts against `store.Active`. A window the owner dragged
// wider still has a restore point, and the local command must still be willing to use it.
//
// The phone is asking something else: *is my button telling the truth right now?* After a human drags
// the window, the setting is still recorded and the fit is not in force, and the button must say so.
//
// # The count is deliberately left alone
//
// Only the boolean is corrected. The phone renders a column count solely when the fit is ON
// (`AgtermScreen.kt`), so a count published alongside `false` is never shown — and the honest value
// available here is a floor rather than a width, so substituting it would trade one small lie for
// another in a field nobody reads.
func (h *Handler) fitOnTheWire() (inForce bool, columns int) {
	h.stateMu.RLock()
	defer h.stateMu.RUnlock()
	if fitOutgrown(h.measuredColumns, h.fitColumns) {
		return false, h.fitColumns
	}
	return h.fitInForce, h.fitColumns
}

// The two locks. Kept here rather than beside the struct so that the reasoning above travels with them.
type locks struct {
	// opMu serializes fit operations end to end. Held across a whole calibration.
	opMu sync.Mutex
	// stateMu guards the published pair below. Held for nanoseconds, never across agterm work.
	stateMu sync.RWMutex
	// fitInForce and fitColumns are the setting as of the last completed operation. One fact, two
	// fields, written together and read together - see publishFit.
	//
	// **"As of the last completed operation" is a remembered claim, and that is what made the phone
	// lie.** Nothing re-read the window after a human dragged its edge, so the owner pressed fit,
	// stretched the window on the Mac, and the phone went on reporting a fit that was no longer in
	// force. The pair below is still the SETTING and is still correct as a setting; what was missing
	// was anything measured to check it against.
	fitInForce bool
	fitColumns int
	// measuredColumns is what the pty last actually showed - the counterweight to the two above.
	// Zero means nothing has been measured since the last operation, which is "no evidence", never
	// "no columns". See noteMeasuredColumns and fitOutgrown.
	measuredColumns int
}
