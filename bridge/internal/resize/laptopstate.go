package resize

// Laptop is the part of the Mac that decides how many columns a window width produces, as the
// control socket reports it for one window.
//
// # Why this exists
//
// A stored fit promised 45 columns and the terminal rendered 59 because the owner dragged
// their sidebar 110 points narrower. Confirming that costs a calibration session, and **a session
// steals the selection**: measured 2026-08-06, `session.new` moves the owner's Mac to the session it
// creates and back again when it closes, so anything typed in that second lands in the probe. Paying
// that on every press was refused, and rightly.
//
// So this is the DETECTOR's input. It is cheap enough to read on every press - one `tree` call, no
// session, no focus - and it decides only one thing: whether the expensive confirmation is worth
// running at all.
//
// # It may only ever escalate
//
// **Nothing here can delete a fit.** A difference means "go and measure"; only a measurement that
// contradicts the entry may throw it away. A tree that could not be read, a tree that says nothing,
// or numbers that have not moved are all SILENCE, and silence applies the fit exactly as before.
//
// # The file this replaced
//
// Until agterm 0.26 the socket published no sidebar width, so this was read from agterm's own
// per-window state file under Application Support: an undocumented private format with no
// compatibility promise, and the only thing the bridge ever read that was not the socket. Discussion
// #511 put the width on the socket, as `tree --window W`'s top-level `sidebarWidth`, and the file
// coupling went with it. An agterm older than that reports no sidebar, which reads as not known, which
// is silence - the position the owner was in before any of this was recorded, and the worst case of
// the change.
//
// # What is compared, and what is applied instead
//
// The sidebar WIDTH is no longer a term the detector watches. The fit records it and [To] SETS it
// before every apply, so it cannot have drifted; a value we just wrote is not evidence of anything.
// What remains to watch is what the fit cannot set: the font, which moves the cell, and whether the
// sidebar is showing at all, which decides whether its width costs the terminal anything.
type Laptop struct {
	// SidebarWidthMilli is thousandths of a point, so the fit carries no float. Recorded, applied,
	// and not compared - see above.
	SidebarWidthMilli int
	// SidebarVisible is whether the sidebar takes any width at all. Not ours to change: hiding or
	// showing it is his layout, so a change buys a measurement rather than a command.
	SidebarVisible bool
	// FontSize moves the CELL rather than the chrome. It is the size MOST of the window's sessions
	// are at, not one session's: agterm keeps this per session, a calibration session takes the
	// default, and one oddly-sized session is not a reason to call the whole fit into question.
	FontSize int
}

// known says whether this state can be compared with anything. A zero of either half means the tree
// did not say, and a comparison against a value nobody read is not evidence.
func (l Laptop) known() bool { return l.SidebarWidthMilli > 0 && l.FontSize > 0 }

// laptopMoved says whether the machine looks different from when this fit was measured.
//
// **The only thing it decides is whether to spend a measurement.** It is not consulted about what to
// apply, it cannot delete anything, and a true answer is a suspicion rather than a verdict - the
// confirmation that follows is free to disagree with it, and often will.
//
// False on every kind of not-knowing, and each one is deliberate:
//
//   - the fit predates the record, so there is nothing to compare against;
//   - the tree could not be read or says nothing, which is what an older agterm looks like from here;
//   - the numbers are the same, which is the ordinary press and the whole reason this exists.
//
// In every one of those cases the press proceeds exactly as it did before the laptop was recorded
// at all: one resize, no session, no jump.
func laptopMoved(fit Fit, current Laptop) bool {
	recorded := Laptop{SidebarWidthMilli: fit.SidebarWidthMilli, SidebarVisible: fit.SidebarVisible, FontSize: fit.FontSize}
	if !recorded.known() || !current.known() {
		return false
	}
	// The width is deliberately not compared: [To] sets it before this is asked, so it is what the fit
	// recorded by construction, and a drag the owner made since the last press has already been undone.
	return current.FontSize != recorded.FontSize || current.SidebarVisible != recorded.SidebarVisible
}
