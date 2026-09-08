// Package resize makes the laptop's terminal the phone's width, so the programs re-render themselves.
//
// # Why this rather than reflowing on the phone
//
// The owner's idea, and it is better than anything a renderer can do: `vim`, `htop` and every
// table-printing tool already know how to lay out at forty columns. Guessing on their behalf is what
// we would be doing otherwise, and we would be worse at it.
//
// # The measurement problem, and how it is solved here
//
// Columns are not settable. agterm resizes a window in POINTS, and how many columns that produces
// depends on the font, the display and the chrome. So the relation has to be learned.
//
// **And the window's own report cannot be used to learn it.** Measured 2026-07-29: in full screen a
// window reported width 640 after 500 had been applied, and `zoomed` flipped underneath. So the
// applied value is trusted (we sent it) and the reported value is not (it lies).
//
// The column count is read from INSIDE the pty — the widest line agterm returns for the session,
// which is what the terminal actually laid out. On the owner's real sessions this is reliable because
// the programs draw full-width: measured across 27 live sessions, 25 had lines at exactly the pane
// width.
//
// # What is cached
//
// A straight line, per display: points = slope × columns + intercept. Two probes determine it, which
// is why calibration costs two resizes and not a search. Measured on the owner's machine: 9.03 points
// per column with 265 points of chrome, consistent at 1728px/162 columns and 500px/26 columns —
// including full screen, where the reported geometry was wrong and the applied value was not.
//
// The cache is keyed by display because the same points are a different number of columns on a
// different screen. It stores COLUMNS MEASURED FROM THE PTY, never geometry the window reported.
package resize

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
	"unicode"
)

// Terminal is the part of the agterm client this package needs.
//
// An interface so the search and the arithmetic are testable without a running agterm — the parts
// most likely to be wrong are the ones that do not need a Mac.
type Terminal interface {
	Windows(ctx context.Context) ([]Window, error)
	// ResizeWindow sets a window's frame. agterm requires a positive height as well as a positive
	// width, so the height is unavoidable on the wire - what is avoidable is sending a STALE one.
	// Every caller here reads the window's current height immediately before the call, so the height
	// it sends is the height the window already has.
	ResizeWindow(ctx context.Context, id string, width, height int) error
	// ZoomWindow TOGGLES zoom. Call only when the current state differs from the wanted one.
	ZoomWindow(ctx context.Context, id string) error
	Text(ctx context.Context, sessionID string, lines int) (string, error)

	// NewSession creates a session running command, returning its id. The ONLY id CloseSession may
	// be given.
	//
	// It also FOCUSES what it creates, so the owner's view jumps for the second or two calibration
	// takes. They accepted that, which is why there is no
	// session.select here putting it back. See the note in the agterm client.
	NewSession(ctx context.Context, command, name string) (string, error)
	CloseSession(ctx context.Context, id string) error

	// Laptop reads one window's sidebar and font from the tree. Never a session, never
	// focus: it is what makes the ordinary press free.
	Laptop(ctx context.Context, windowID string) (Laptop, error)
	// SetSidebarWidth sets one window's sidebar width in milli-points and returns what agterm
	// applied, which is clamped to 160...560 points. The second geometry knob, and the only one
	// with no floor the fit can hit.
	SetSidebarWidth(ctx context.Context, windowID string, milli int) (int, error)
}

// Window is the geometry this package needs. It mirrors agterm.Window rather than importing it, so
// the search and the arithmetic can be tested against a fake with no agterm anywhere.
type Window struct {
	ID string
	// Active is agterm's own flag for the window that is frontmost.
	//
	// **It was decoded from the socket and thrown away before reaching here**, which is how [active]
	// came to pick a window by list order. See that function for what it cost.
	Active     bool
	Fullscreen bool
	Zoomed     bool
	Display    int
	X, Y       int
	Width      int
	Height     int
}

// Fit is a calibration RESULT: two literal numbers and nothing to evaluate.
//
// **There is deliberately no method turning a column count into a width.** That method existed, as
// Calibration.Points over a slope and an intercept, and it is the design the owner rejected: a line is
// a thing you evaluate, and evaluating is recalculating. Every request re-derived the width, rounded,
// landed a column off, and searched their live window again. A type with nothing to evaluate cannot be.
type Fit struct {
	Display int `json:"display"`
	// BoxWidthDp is what the phone MEASURED its terminal box to be. Half the key - see fitKey.
	BoxWidthDp int `json:"box_width_dp"`
	// MarginDp is recorded so a human reading this file can see WHY a key changed rather than having
	// to infer it from a number that moved. It takes no part in any lookup.
	MarginDp int `json:"margin_dp"`
	// CharacterWidthMilliDp is how wide one character measured on the phone that asked, in thousandths
	// of a dp. **Part of the key, because it is half of what the answer was measured FROM.**
	//
	// A font-size change moves this and leaves the box width alone, so without it a stored fit would be
	// applied verbatim to a phone that now fits a different number of columns - a cached answer to a
	// question nobody re-asked, which is the failure this whole feature spent a day removing.
	CharacterWidthMilliDp int `json:"character_width_milli_dp"`
	// Points is the answer: the window width measured to render Columns columns into that box.
	Points  int `json:"points"`
	Columns int `json:"columns"`

	// ChromePoints and CellMilliPoints are what the LAPTOP looked like when this was measured.
	// **Evidence, not identity**: neither is part of [fitKey] and no decision reads them. A fit is
	// found by the phone's numbers exactly as before.
	//
	// They exist because of one measured failure. On 2026-08-06 a stored fit promised 45 columns
	// and the terminal rendered 59, and working out why took two live measurements and simultaneous
	// equations against two historical entries in this file. The search already computes both
	// numbers from its probes and used to throw them away; written down, the file can say it
	// itself.
	//
	// What they buy is the sentence a contradiction prints - see the verification in [To]: the chrome
	// then, the chrome now, and therefore what moved on the machine. What they do NOT buy is
	// detection: neither can be compared with the present without a measurement, so they explain a
	// contradiction rather than finding one.
	//
	// Zero means **this fit predates the record**, not "the chrome is zero". The only reader omits
	// its sentence rather than printing a diagnosis built on a missing number.
	ChromePoints    int `json:"chrome_points"`
	CellMilliPoints int `json:"cell_milli_points"`

	// SidebarWidthMilli, SidebarVisible and FontSize are the laptop as it was at calibration, read
	// from the tree - see laptopstate.go. Evidence like the two above, not identity: none is part of
	// [fitKey].
	//
	// **The sidebar width is APPLIED with the fit, the other two are COMPARED**. [To]
	// sets the sidebar to this width before it narrows the window, so the fit is applied in the
	// layout it was measured in; the font and the sidebar's visibility are not ours to set, so a
	// difference in either buys a measurement, which is what keeps an ordinary press free: one tree
	// read, no session, and only a difference costs more.
	//
	// Zero means this fit predates the record or the tree said nothing. Neither is a contradiction;
	// both mean the press proceeds exactly as it always did, sidebar untouched.
	SidebarWidthMilli int `json:"sidebar_width_milli"`
	FontSize          int `json:"font_size"`
	// SidebarVisible is whether the sidebar was showing when this was measured. A width
	// applied to a hidden sidebar costs the terminal nothing, so a fit measured with it showing does
	// not hold with it hidden, and the detector compares this where it used to compare the width.
	SidebarVisible bool `json:"sidebar_visible"`
}

// fitKey is (display, measured box width, measured character width). **A different measurement is a
// different question, so it gets a different key and a fresh calibration - and that invalidation is a
// FEATURE.**
//
// The character width joined the key when the terminal font moved one step down. The box width does
// not change when the font does, so the old key would have applied a fit of 41 columns to a phone that
// now fits more - the owner would get a window too narrow with no way to tell why. Their existing
// entries become UNREACHABLE rather than wrong, which is the same property that saved us when the box
// width went from 1706 to 440. Nothing is deleted: deleting is the operation that once destroyed the
// only record of their window geometry.
//
// The earlier design keyed on a column count DERIVED FROM OUR CONSTANTS by arithmetic, so shipping a
// layout change moved the key while the truth stayed the same and the owner's calibration was silently
// discarded. The fix is measuring rather than computing, not a coarser key: now that calibration
// measures the terminal truthfully, a changed box genuinely has a different right answer, and
// re-measuring is correct rather than wasteful. It costs one calibration, once, only when we ship a
// layout change.
//
// Keying on something coarser - the screen width, say - would keep the same key across a margin change
// and then apply a width computed for a different box. **Silently wrong is worse than nothing**, and
// silently wrong is what burned this feature twice.
//
// # The shape segment was dropped when the phone started maximizing, and it cost one calibration
//
// A fourth segment named the split geometry a fit was measured in, because the fit's target was a
// PANE'S SHARE of a window. The phone now maximizes the pane it shows, so there is no share: every
// session it points at is one pane filling the terminal area, and every measurement answers the same
// question.
//
// Entries written as `…/one` are therefore no longer looked up. They are UNREACHABLE, not deleted -
// the property that saved this file when the box width went from 1706 to 440 - and the cost is one
// calibration per geometry, once, on the next press.
//
// Keeping a vestigial `one` to spare him that would preserve a segment whose only remaining job is to
// record that the thing it distinguished no longer exists.
func fitKey(display, boxWidthDp, characterWidthMilliDp int) string {
	return fmt.Sprintf("%d/%d/%d", display, boxWidthDp, characterWidthMilliDp)
}

// Intent is what a fit request is FOR.
//
// # Why a closed set and not two booleans
//
// This was `recalibrate bool`, and the third case needed another flag beside it. Two booleans can
// represent `recalibrate && applyIfKnown`, which is nonsense — *measure this freshly, but only if you
// already measured it.* A pair of flags makes the caller responsible for never writing it down; a
// closed set makes it unwritable.
//
// The same reasoning that keeps `Paste` a field of its own rather than a flag on `Text`, and that makes
// `keys.Key` and `agterm.Pane` types rather than strings. **The wire stays two booleans** for
// compatibility and the boundary refuses the impossible pair — see `api.intentOf`.
type Intent int

const (
	// IntentApply is the owner's press: apply what is known, and measure if nothing is.
	IntentApply Intent = iota
	// IntentRecalibrate is the owner's long press: measure again whatever is recorded.
	IntentRecalibrate
	// IntentApplyIfKnown is the automatic re-apply, and **it must never start a calibration**.
	//
	// A calibration is a visible hunt across the owner's window. Having one begin because he tapped a
	// row in a list on his phone is a surprise arriving from a machine he is not looking at — so this
	// answers [ErrNotCalibrated] instead, and he is asked to press.
	IntentApplyIfKnown
)

// ErrNotCalibrated is the answer to [IntentApplyIfKnown] when nothing is recorded for the geometry.
//
// **An error here and NOT an error on the wire**, and the difference is deliberate. At this layer the
// caller asked for something and did not get it, which is what an error is for. At the boundary it
// becomes an ordinary reply saying *"press Fit"* — because dressing a legitimate answer as a failure is
// how the fit refusal came to replace the owner's terminal with a full-page error, which had to be
// undone.
var ErrNotCalibrated = errors.New("no fit is recorded for this geometry")

// Restore is what the window looked like before anything was resized.
//
// **Zoom as well as geometry.** Zoom changes underneath a resize — measured, not assumed — so putting
// the frame back without putting the zoom back leaves the owner's window in a state they did not
// choose and did not ask for.
type Restore struct {
	WindowID   string `json:"window_id"`
	Fullscreen bool   `json:"fullscreen"`
	Zoomed     bool   `json:"zoomed"`
	X          int    `json:"x"`
	Y          int    `json:"y"`
	Width      int    `json:"width"`
	Height     int    `json:"height"`
	// SidebarWidthMilli is the sidebar the owner had, in thousandths of a point. Zero for
	// a record written before the fit could move the sidebar, or by an agterm that does not report
	// it; nothing is sent for a zero.
	SidebarWidthMilli int `json:"sidebar_width_milli,omitempty"`
}

// Store is the on-disk cache. It lives beside the bridge's config, which is gitignored.
type Store struct {
	Fits map[string]Fit `json:"fits"`

	// Active IS the setting: the fit currently in force, or nil for off.
	//
	// **It is a Fit and not a bool-plus-a-number, and that is structural rather than careful.** It was
	// `Enabled bool` beside `Columns int`, and on 2026-07-30 the owner's file held enabled=true with
	// columns=162 and exactly one fit - for box_width_dp 1728, the LAPTOP's window width, measured by
	// a test for a different box on a different display. No fit for their phone existed at all. So
	// the setting was on, carrying a column count from somewhere else, and every press applied 162
	// columns to a window already 1728 wide: working perfectly, on the wrong number.
	//
	// Two fields that can be assigned separately are two fields that can disagree. One field cannot.
	// The columns in force are now, unavoidably, the columns of the fit in force.
	Active *Fit `json:"active,omitempty"`

	Pending *Restore `json:"pending_restore,omitempty"`
	// dir is where this store was loaded from, so it can write itself back at the one moment that
	// cannot wait for the caller - see persist. Unexported, so it is never serialised into its own
	// file, and empty for a store built directly in a test, which makes persist a no-op there.
	dir string
}

// MeasureColumns is the column count as the TERMINAL laid it out.
//
// The widest line agterm returns, with tabs expanded on eight-column stops and double-width
// characters counted as two — the same rules the phone's renderer uses, so the two agree about what a
// column is.
//
// **This is a lower bound.** If nothing on screen is full width, the answer is short. That is why the
// search below moves DOWNWARD from a known-wide window: shrinking until the measurement stops
// exceeding the target is sound, where growing until it reaches the target is not.
func MeasureColumns(screen string) int {
	widest := 0
	for _, line := range strings.Split(screen, "\n") {
		w := 0
		for _, r := range line {
			switch {
			case r == '\t':
				w += 8 - (w % 8)
			case unicode.Is(unicode.Mn, r):
				// Combining marks occupy no column of their own.
			case r >= 0x1100 && isWide(r):
				w += 2
			default:
				w++
			}
		}
		if w > widest {
			widest = w
		}
	}
	return widest
}

// awaitReportedColumns waits until the calibration session has PRINTED A NUMBER, then returns it.
//
// # What it waits for, and what it refuses to do
//
// It observes the thing it is waiting for. There is no duration in here chosen to make anything pass:
// it reads, and if what came back is not a column count it reads again, and if it never becomes one it
// says so. A shell takes a moment to start and prints a login banner on the way - measured
// 2026-07-30, where the first read of a fresh session returned
// `Last login: Thu Jul 30 14:43:30 on ttys035` - and that banner is not an answer.
//
// **The refusal is the point.** Every earlier failure in this feature returned a number that looked
// fine. This path returns either a number the terminal actually reported or an error naming what it
// saw instead, and those are the only two outcomes.
//
// # It waits for the number to SETTLE, not merely to appear
//
// **A stale number is a valid number, and taking the first one seen accepted it.** Measured 2026-08-26,
// in a window created and closed for the purpose: for about 0.3s after a resize the probe goes on
// printing the count from BEFORE it.
//
//	+0.00s -> 67   the pre-resize value
//	+0.14s -> (mid-clear, no number)
//	+0.28s -> 26   the truth
//
// [resizeAndSettle] waits for the WINDOW to settle, which it can do before the pty has re-rendered, so
// the read that followed it could land in that window and come back with the old figure. Nothing
// rejected it, because there is nothing wrong with it except when it was true.
//
// The shaped probe is what made this bite: splitting the probe and moving its divider immediately
// before the first measurement is a re-layout, and the first read after it returned the pane width
// from before the split. On the owner's machine that produced the same figure for both probe widths
// and the calibration refused, blaming a long line that was rendering perfectly.
//
// So a reading counts only once [stableReadings] consecutive reads agree. The span that requires is
// longer than the probe's own redraw period, which is a constant this package AUTHORS — see
// [CalibrationCommand] — rather than a duration guessed to make something pass.
func awaitReportedColumns(ctx context.Context, t Terminal, sessionID string) (int, error) {
	var last error
	agreed, previous := 0, -1
	for attempt := 0; attempt < settleAttempts; attempt++ {
		screen, err := t.Text(ctx, sessionID, probeLines)
		if err != nil {
			return 0, err
		}
		cols, err := ReportedColumns(screen)
		if err == nil {
			// **A number that has stopped moving, not a number that appeared.** A run is broken by a
			// different reading AND by a read with no number in it at all - the `clear` between
			// redraws produces exactly that, and counting through it would let two readings either
			// side of a redraw look consecutive when they are 0.4s apart.
			if cols == previous {
				agreed++
			} else {
				agreed, previous = 1, cols
			}
			if agreed >= stableReadings {
				return cols, nil
			}
			last = fmt.Errorf("the reported count was still moving; last saw %d", cols)
			select {
			case <-ctx.Done():
				return 0, ctx.Err()
			case <-time.After(settleInterval):
			}
			continue
		}
		agreed, previous = 0, -1
		last = err
		select {
		case <-ctx.Done():
			return 0, ctx.Err()
		case <-time.After(settleInterval):
		}
	}
	return 0, fmt.Errorf("the calibration session never reported a column count: %w", last)
}

// ReportedColumns reads the number the calibration session PRINTED.
//
// # Why the terminal is asked rather than measured
//
// The previous design printed a line longer than any plausible width and took the widest line on
// screen as the column count, on the theory that the terminal wraps at its own boundary. **Refuted by
// measurement on the real machine 2026-07-30**: `session.text` returns the LOGICAL line, so a
// 1000-character line comes back as 1000 characters at every window width. The wrap point - the exact
// thing that design depended on - is what the transport discards.
//
// So the session runs `tput cols` and prints the answer. The shell already knows its width; a short
// logical line survives the transport unchanged; there is nothing to infer and nothing to measure.
// The terminal stops being the thing we measure and becomes the thing that reports.
//
// **It fails loudly on anything that is not a number.** An empty screen, a shell error, a session that
// has not started yet - all of them produce an error rather than a plausible-looking count. Every
// previous failure in this feature produced a number that looked fine.
func ReportedColumns(screen string) (int, error) {
	for _, line := range strings.Split(screen, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		n, err := strconv.Atoi(line)
		if err != nil {
			return 0, fmt.Errorf("the calibration session printed %q, which is not a column count", line)
		}
		if n < minColumns || n > maxColumns {
			return 0, fmt.Errorf("the calibration session reported %d columns, outside %d-%d", n, minColumns, maxColumns)
		}
		return n, nil
	}
	return 0, errors.New("the calibration session printed nothing yet")
}

// isWide is the East Asian Wide/Fullwidth ranges a terminal renders in two cells.
func isWide(r rune) bool {
	switch {
	case r >= 0x1100 && r <= 0x115F, // Hangul Jamo
		r >= 0x2E80 && r <= 0xA4CF, // CJK radicals through Yi
		r >= 0xAC00 && r <= 0xD7A3, // Hangul syllables
		r >= 0xF900 && r <= 0xFAFF, // CJK compatibility
		r >= 0xFE30 && r <= 0xFE6F, // CJK compatibility forms
		r >= 0xFF00 && r <= 0xFF60, // Fullwidth forms
		r >= 0xFFE0 && r <= 0xFFE6,
		r >= 0x1F300 && r <= 0x1F64F, // emoji
		r >= 0x20000 && r <= 0x3FFFD:
		return true
	}
	return false
}

// LoadStore reads the cache, returning an empty one when there is none.
//
// A missing or unreadable cache is not an error: it costs a calibration, and a calibration is two
// resizes. Refusing to work because a cache file is corrupt would be worse than doing the work again.
func LoadStore(dir string) *Store {
	empty := func() *Store { return &Store{Fits: map[string]Fit{}, dir: dir} }
	raw, err := os.ReadFile(filepath.Join(dir, "resize-cache.json"))
	if err != nil {
		return empty()
	}
	s := empty()
	if err := json.Unmarshal(raw, s); err != nil {
		return empty()
	}
	if s.Fits == nil {
		s.Fits = map[string]Fit{}
	}
	s.dir = dir
	return s
}

// persist writes the store where it was loaded from, ignoring failure.
//
// **This exists for exactly one moment: the instant after the restore point is captured and before
// anything moves.** Everywhere else the caller decides when to save, and waiting is harmless. Here it
// is not - a restore point that lives only in memory protects the graceful path and nothing else, and
// the graceful path was never the one at risk. If the bridge dies between the first resize and the
// return, the owner is left with a narrow window and the record of what it was died with the process.
//
// Failure is ignored deliberately: this is best effort on top of a cache that is itself an
// optimisation, and refusing to resize because a file could not be written would be a worse outcome
// than the crash it guards against.
func (s *Store) persist() {
	if s.dir == "" {
		return
	}
	_ = s.Save(s.dir)
}

// Save writes the cache. Failure is reported but is never fatal to a resize.
func (s *Store) Save(dir string) error {
	raw, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dir, "resize-cache.json"), raw, 0o600)
}

// probeLines is how much screen to read when measuring. Enough to hold the wrapped long line without
// pulling scrollback.
const probeLines = 40

// Calibrate finds the window width that renders `columns` columns, measured inside a session the
// bridge created for the purpose, and returns it as a literal [Fit].
//
// **This is the only function that measures anything, and it runs once per display-and-box-width.**
// Everything after it reads the number it produced.
//
// The session is ours: see [withCalibrationSession]. Measuring the owner's working session is the
// defect this replaces - its content moves, so the answer moved with it.
func Calibrate(ctx context.Context, t Terminal, dir string, w Window, boxWidthDp, marginDp, characterWidthMilliDp, columns int) (Fit, error) {
	if columns < minColumns || columns > maxColumns {
		return Fit{}, fmt.Errorf("columns %d out of range %d-%d", columns, minColumns, maxColumns)
	}

	var fit Fit
	err := withCalibrationSession(ctx, t, dir, func(sessionID string) error {
		// settledAt records what the window ACTUALLY became for each probe. It was discarded before
		// discarding it is why the refusal below could blame the long line for a window that never
		// moved - measured: asking for 600 points yields 640, because there is a floor.
		settledAt := map[int]int{}
		measure := func(points int) (int, error) {
			// Settle first. Reading the terminal before the window has taken the new width measures
			// the OLD layout and looks exactly like a resize that did nothing.
			got, err := resizeAndSettle(ctx, t, w.ID, points)
			if err != nil {
				return 0, err
			}
			settledAt[points] = got
			cols, err := awaitReportedColumns(ctx, t, sessionID)
			if err != nil {
				return 0, err
			}
			log.Printf("width: at %d points (settled %d) the terminal reported %d columns",
				points, got, cols)
			return cols, nil
		}

		// Two probes give a points-per-column relation. **It is a local that seeds a search and is
		// then thrown away.** As a heuristic a line costs nothing, because whatever it predicts is
		// checked against the pty before it is believed; as a CACHED artefact it costs everything,
		// because then every application is a fresh prediction nobody checks.
		const wide, narrow = 1400, 600
		wideCols, err := measure(wide)
		if err != nil {
			return err
		}
		narrowCols, err := measure(narrow)
		if err != nil {
			return err
		}
		if wideCols == narrowCols {
			// **Say which of the two things went wrong**. This one sentence covered a
			// window that could not move and a probe that was not reporting, and it named neither.
			// The owner got it on 2026-08-26 for a probe that was rendering perfectly.
			if settledAt[wide] == settledAt[narrow] {
				return fmt.Errorf("the window could not be resized between the two probes: it settled "+
					"at %d points both times, so both measured %d columns",
					settledAt[wide], wideCols)
			}
			return fmt.Errorf("the window moved from %d to %d points and the terminal reported %d "+
				"columns either way; the calibration session is not reporting its width",
				settledAt[wide], settledAt[narrow], wideCols)
		}
		// **From the widths the window SETTLED at, not the ones it was asked for**. The
		// 600-point probe settles at 640 on a real Mac, and a line through the asked widths put the
		// cell 5% too wide; every step after inherited the error, including how far to widen the
		// sidebar. The columns were measured at the settled widths, so the line goes through them.
		slope := float64(settledAt[wide]-settledAt[narrow]) / float64(wideCols-narrowCols)
		intercept := float64(settledAt[wide]) - slope*float64(wideCols)
		guess := func(c int) int { return int(slope*float64(c) + intercept + 0.5) }
		// pinned reports whether a width was asked for and refused: the window settled WIDER than it
		// was asked to be, which is what its 640-point floor looks like from here. Below the floor
		// every probe measures the same thing, so a search that keeps going there learns nothing and
		// resizes the owner's window for each lesson it does not learn.
		pinned := func(p int) bool { return settledAt[p] > p }

		points := guess(columns)
		got, err := measure(points)
		if err != nil {
			return err
		}

		if got != columns && !(got > columns && pinned(points)) {
			lo, hi := guess(minColumns), guess(maxColumns)
			if got > columns {
				hi = points
			} else {
				lo = points
			}
			for i := 0; i < maxSearchSteps && lo < hi; i++ {
				mid := (lo + hi) / 2
				measured, err := measure(mid)
				if err != nil {
					return err
				}
				// **Moved together, always.** These are a PAIR - a width and the column count that
				// width was measured to produce. Updating one without the other is how a fit ends up
				// claiming a count some other width produced, and applied verbatim for ever that lie
				// has no expiry.
				points, got = mid, measured
				if got == columns {
					break
				}
				if got > columns && pinned(mid) {
					// Too many columns at the floor. No narrower window exists; the sidebar stage
					// below is the only thing left that can take width from the terminal.
					break
				}
				if got > columns {
					hi = mid - 1
				} else {
					lo = mid + 1
				}
			}
		}

		if got == 0 {
			return errors.New("calibration measured no columns at any width tried")
		}

		// **Overshooting by one column is a window a few points too wide, not an impossible fit.**
		//
		// Measured on the owner's phone 2026-07-30: a 440dp box asked for 41 columns, the search
		// settled on 42, and the refusal below fired. The refusal was right on its own terms — 42
		// columns at 10.7dp is 448dp and every line would scroll for ever — but a verdict is not an
		// outcome. One column is nine points, and the search had simply run out of steps above it.
		//
		// So walk down before giving up: subtract one column's worth of points, settle, measure, and
		// take the first result at or under what was asked. Bounded at [overshootSteps], because every
		// step resizes the owner's live window while they are watching it.
		perColumn := int(slope + 0.5)
		if perColumn < 1 {
			perColumn = 1
		}
		for step := 1; step <= overshootSteps && got > columns && !pinned(points); step++ {
			if points <= perColumn {
				break
			}
			// Moved together, always — a width and the count that width was measured to produce.
			points -= perColumn
			measured, err := measure(points)
			if err != nil {
				return err
			}
			// **The walk says what it did, with numbers.** If their terminal genuinely cannot land on
			// the asked count, the log shows three attempts rather than an explanation of why one
			// failed. Widths and counts only: nothing here knows a session name or a line of text.
			log.Printf("width: overshoot walk %d/%d - asked %d, had %d, retried at %d points, got %d",
				step, overshootSteps, columns, got, points, measured)
			got = measured
		}
		// **THE SEARCH MUST HAVE LANDED. A partial calibration stores NOTHING.**
		//
		// This used to build a fit from whatever the search last measured, with a comment reasoning
		// about the case where it stops SHORT - 38 columns where 40 was asked, a harmless sliver. It
		// silently permitted the opposite, and on 2026-07-30 the owner's file held
		// {box_width_dp: 448, points: 769, columns: 42}: forty-two columns at 11dp is 462dp, in a box
		// that holds 440. Every press afterwards faithfully applied it.
		//
		// **A cache that nothing invalidates automatically turns a partial result into a permanent
		// one**, so the only safe moment to persist is after the answer is known to be whole. Giving
		// up is an error and writes nothing; the next press calibrates again, which costs a second.
		// **THE SIDEBAR STAGE.** The window is at its floor and still too wide for the
		// phone: a fit that was refused, identically, every time before this. The terminal area is
		// the window minus the sidebar, and the sidebar has no floor the fit can hit - only a 560-point
		// ceiling - so when the window cannot get narrower the sidebar gets wider.
		//
		// Discussion #511, the case that asked for the command: a 220-point sidebar and a 640-point
		// window left 47 columns as the narrowest reachable, and the phone needed 45. Dragged to 271
		// by hand the same window reached them. This does the drag.
		//
		// **Second knob, never the first.** It runs only when the window is pinned, so a fit the
		// window can reach alone is measured exactly as it always was and touches nothing of his but
		// the window. The first step takes the whole surplus in one move - the cell is known by now -
		// and any step after it is one column's worth, the overshoot walk's own rule.
		//
		// **The echo is the clamp.** agterm answers ok whether it honoured the width or clamped it;
		// the applied width in the reply is the only way to tell, and a clamped step is the last step.
		sidebarMaxed := false
		if got > columns && pinned(points) {
			floor := settledAt[points]
			laptop, err := t.Laptop(ctx, w.ID)
			if err != nil {
				return fmt.Errorf("the window is at its %d-point floor with %d columns, and the sidebar "+
					"could not be read to widen it: %w", floor, got, err)
			}
			if laptop.SidebarWidthMilli <= 0 {
				return fmt.Errorf("the window is at its %d-point floor with %d columns, and this agterm "+
					"reports no sidebar width to widen", floor, got)
			}
			sidebar := laptop.SidebarWidthMilli
			for step := 1; step <= sidebarSteps && got > columns && !sidebarMaxed; step++ {
				delta := perColumn
				if step == 1 {
					delta = (got - columns) * perColumn
				}
				asked := sidebar + delta*1000
				applied, err := t.SetSidebarWidth(ctx, w.ID, asked)
				if err != nil {
					return fmt.Errorf("the window is at its %d-point floor with %d columns, and the "+
						"sidebar could not be widened: %w", floor, got, err)
				}
				sidebarMaxed = applied < asked
				// Widths and counts only. See the note at the top of internal/api/resize.go.
				log.Printf("width: sidebar stage %d/%d - the window is pinned at %d points with %d "+
					"columns, asked the sidebar for %.1f points (from %.1f), agterm applied %.1f%s",
					step, sidebarSteps, floor, got, float64(asked)/1000, float64(sidebar)/1000,
					float64(applied)/1000, map[bool]string{true: " - CLAMPED, its maximum", false: ""}[sidebarMaxed])
				sidebar = applied
				// The window is already at the floor; this settles and counts, and moves nothing.
				measured, err := measure(floor)
				if err != nil {
					return err
				}
				// Moved together, always - and the width on record is the floor the window is really
				// at, not the narrower one it was asked for and refused.
				points, got = floor, measured
			}
		}
		if err := refuseAnImpossibleFit(got, columns, boxWidthDp, characterWidthMilliDp, sidebarMaxed); err != nil {
			return err
		}
		fit = Fit{
			Display: w.Display, BoxWidthDp: boxWidthDp, MarginDp: marginDp,
			CharacterWidthMilliDp: characterWidthMilliDp,
			Points:                points, Columns: got,
			// The probes' own slope and intercept, recorded rather than discarded. The search does
			// not trust them - every width it tries is checked against the pty - and neither does
			// anything else: they are here so a future disagreement is readable off this file.
			ChromePoints:    int(intercept + 0.5),
			CellMilliPoints: int(slope*1000 + 0.5),
		}
		// What the laptop looked like while this was measured. Best effort: a state that could not be
		// read leaves the fields zero, and a zero means the detector says nothing about this fit
		// rather than that the sidebar is zero wide.
		if state, err := t.Laptop(ctx, w.ID); err == nil {
			fit.SidebarWidthMilli = state.SidebarWidthMilli
			fit.SidebarVisible = state.SidebarVisible
			fit.FontSize = state.FontSize
		}
		return nil
	})
	return fit, err
}

// To makes the laptop's terminal render at `columns`, and reports the count now in effect.
//
// **If a fit is stored for this display and this box width it is applied verbatim: one resize, to a
// number read off disk, with nothing measured and nothing recomputed.** That sentence is the feature,
// and the second return value says which of the two happened so the phone is never told a measurement
// ran when it did not.
//
// Not reasons to recalibrate: a reconnect, a new session, a rotation, a font change, a restart, a
// window that has moved. A different BOX WIDTH is - see fitKey, where that invalidation is argued for
// rather than tolerated.
func To(ctx context.Context, t Terminal, store *Store, dir string, boxWidthDp, marginDp, characterWidthMilliDp, columns int, intent Intent) (int, bool, error) {
	if columns < minColumns || columns > maxColumns {
		return 0, false, fmt.Errorf("columns %d out of range %d-%d", columns, minColumns, maxColumns)
	}
	if boxWidthDp <= 0 {
		return 0, false, errors.New("the phone did not say how wide its terminal box measured")
	}

	windows, err := t.Windows(ctx)
	if err != nil {
		return 0, false, err
	}
	w, err := active(windows)
	if err != nil {
		return 0, false, err
	}

	if store.Fits == nil {
		store.Fits = map[string]Fit{}
	}
	key := fitKey(w.Display, boxWidthDp, characterWidthMilliDp)
	// **The automatic re-apply stops here when nothing is recorded**.
	//
	// Above this line the machine has been READ and not touched: a window list, and a key made of
	// numbers. So an [IntentApplyIfKnown] that declines leaves the laptop exactly as it was found —
	// including the restore point below, which is a file this path must not write for a fit it is
	// about to refuse to apply.
	//
	// **That is why the ordering moved.** The restore point used to be written before the key existed,
	// so this check could not have run before it. It is still written before anything is RESIZED,
	// which is the property its own comment is about.
	if _, known := store.Fits[key]; !known && intent == IntentApplyIfKnown {
		return 0, false, ErrNotCalibrated
	}

	// Remember what to put back BEFORE touching anything, and on disk rather than in memory: a crash
	// between the first resize and the return would otherwise leave the owner's window narrow with
	// the record of its real size dead with the process.
	//
	// **A RESTORE POINT IS ONLY EVER WRITTEN WHEN THERE ISN'T ONE**, and this `if` is the whole of
	// that rule. It is not an optimisation and it is not idempotence for its own sake: an on-press
	// that found a record already present and overwrote it would record a window WE narrowed as where
	// the owner's window came from, and no press afterwards could ever put it back. The geometry in
	// this record is the one thing here that cannot be recomputed from anything else.
	//
	// Held by TestARestorePointIsOnlyWrittenWhenThereIsNone, because until that test existed this was
	// a property nobody could point at - true by the shape of an `if`, and one refactor from being
	// silently untrue.
	if store.Pending == nil {
		// The sidebar as well, since the fit may now move it. Read from the tree; an
		// agterm that reports none leaves a zero, and a zero is never sent back.
		sidebar := 0
		if laptop, err := t.Laptop(ctx, w.ID); err == nil {
			sidebar = laptop.SidebarWidthMilli
		}
		store.Pending = &Restore{
			WindowID: w.ID, Fullscreen: w.Fullscreen, Zoomed: w.Zoomed,
			X: w.X, Y: w.Y, Width: w.Width, Height: w.Height,
			SidebarWidthMilli: sidebar,
		}
		store.persist()
	}

	// **The window as it stands, before anything touches it**.
	//
	// The record used to jump from "asked 45 columns" to "45 columns now in effect" with no geometry
	// between them, so a press that resized nothing and a press that moved the window 300 points wrote
	// the same two lines. On 2026-08-26 the owner pressed four times in fifty seconds, got four
	// successes, and switched the feature off; nothing in the log distinguished those presses from
	// each other or from a press that worked.
	//
	// Points, not the owner's anything. See the note at the top of internal/api/resize.go.
	log.Printf("width: window %s is %dx%d points on display %d, fit entry %s",
		w.ID, w.Width, w.Height, w.Display,
		map[bool]string{true: "known", false: "MISSING - this press will calibrate"}[hasFit(store, key)])

	if fit, known := store.Fits[key]; known && intent != IntentRecalibrate {
		// **The sidebar first, at the width the fit was measured with**. A fit is a
		// window width AND a sidebar width; the column count is what the two produce together. The
		// sidebar used to be a term the detector watched for drift and a probe confirmed; it is now
		// simply set, and cannot drift. Before the window narrows, so the window is never narrow
		// around the wrong sidebar even for a moment.
		//
		// A fit that recorded no sidebar predates this or was measured on an agterm that reports
		// none; it is applied as it always was. A sidebar that cannot be set is a fit that would
		// deliver the wrong count and say nothing - refused, with the window untouched, because
		// silently wrong is the failure this whole feature was rebuilt around.
		if fit.SidebarWidthMilli > 0 {
			applied, err := t.SetSidebarWidth(ctx, w.ID, fit.SidebarWidthMilli)
			if err != nil {
				return 0, false, fmt.Errorf("the fit was measured with a %.1f-point sidebar and the "+
					"sidebar could not be set: %w", float64(fit.SidebarWidthMilli)/1000, err)
			}
			if applied != fit.SidebarWidthMilli {
				log.Printf("width: the sidebar was asked for %.1f points and agterm applied %.1f",
					float64(fit.SidebarWidthMilli)/1000, float64(applied)/1000)
			}
		}
		// No probe and no arithmetic: the stored width, applied. The height it has RIGHT NOW, not the
		// one captured when this operation began.
		height, err := currentHeight(ctx, t, w.ID)
		if err != nil {
			return 0, false, err
		}
		if err := t.ResizeWindow(ctx, w.ID, fit.Points, height); err != nil {
			return 0, false, err
		}
		// **What actually moved.** A press that changes nothing is the interesting case and it was
		// indistinguishable from every other press in the record.
		log.Printf("width: resized the window from %d to %d points (%+d) for a cached fit of %d columns",
			w.Width, fit.Points, fit.Points-w.Width, fit.Columns)

		// **AND THEN IT IS CHECKED - but only when something suggests it is worth checking.**
		//
		// On 2026-08-06 the owner narrowed their sidebar, the same 802-point window handed
		// 110 more points to the terminal, and an entry promising 45 columns started delivering 59 -
		// 577dp of line in a 440dp box, on every press, for ever. The key is made of phone-side facts
		// and the value it guards is a laptop-side answer; nothing in it could notice.
		//
		// **Confirming costs the owner their cursor.** Only a shell reports a terminal's width -
		// agterm publishes no grid size, and `session.text` returns the LOGICAL line, so the wrap
		// point is exactly what the transport discards. And `session.new` FOCUSES what it creates:
		// measured 2026-08-06, the selection moves to the probe and back again on close, so anything
		// typed in that second lands in it. Paying that on every press was refused, and rightly.
		//
		// So the cheap thing runs first. [readLaptopState] is one file read - no socket, no session,
		// no focus - and an ordinary press where nothing has moved is exactly as instant and as silent
		// as it was before any of this. Only a difference buys a measurement, and only a measurement
		// can throw anything away.
		measured, confirmed := 0, false
		var verifyErr error
		if current, err := t.Laptop(ctx, w.ID); err == nil && laptopMoved(fit, current) {
			// The window is already at fit.Points, so this measures and resizes nothing.
			measured, verifyErr = verifyColumns(ctx, t, dir)
			confirmed = true
		}

		contradicted := false
		switch {
		// Nothing suggested a change, so nothing was measured and nothing is concluded. This is the
		// ordinary press: one resize, no session, no jump.
		case !confirmed:
		// **SILENCE IS NOT A CONTRADICTION.** A link that dropped, an agterm that refused, a shell
		// that never printed a number - none of them is evidence that this entry is wrong, and
		// deleting a good fit because the laptop hiccupped would turn every stutter into a
		// seven-second recalibration of something that was already right. Written as a three-way
		// rather than `if measured != fit.Columns`, because that shape reads the zero that comes back
		// with an error as a disagreement.
		case verifyErr != nil:
			log.Printf("width: the cached fit could not be verified (%v); applying it unchanged", verifyErr)
		case measured != fit.Columns:
			log.Printf("width: the cached fit no longer holds - it promised %d columns at %d points "+
				"and the terminal reported %d.%s Recalibrating.",
				fit.Columns, fit.Points, measured, chromeMoved(fit, measured))
			// Deleted, and then the calibration below runs. The entry cannot be repaired here: what
			// is wrong with it is the width, and finding the right width is what calibration is.
			delete(store.Fits, key)
			store.persist()
			contradicted = true
		}

		if !contradicted {
			// **A confirmation that AGREED updates the detector's copy**, so the next press is silent
			// again. Without this, one sidebar drag that happens not to change the column count would
			// buy a session on every press for ever - the detector would keep disagreeing and the
			// measurement would keep saying it does not matter.
			//
			// Only after a measurement said so. Refreshing it on the strength of the file alone would
			// be recording that the fit still holds because something moved, which is backwards.
			if confirmed && verifyErr == nil {
				if state, err := t.Laptop(ctx, w.ID); err == nil && state.known() {
					fit.SidebarWidthMilli = state.SidebarWidthMilli
					fit.SidebarVisible = state.SidebarVisible
					fit.FontSize = state.FontSize
					store.Fits[key] = fit
					store.persist()
				}
			}

			// **The setting is the fit, assigned here and nowhere else.** Its column count therefore
			// cannot be a number from some other box - which is precisely how 162 columns measured
			// for a 1728dp laptop window ended up governing a phone.
			applied := fit
			store.Active = &applied
			store.persist()
			return fit.Columns, false, nil
		}
	}

	fit, err := Calibrate(ctx, t, dir, w, boxWidthDp, marginDp, characterWidthMilliDp, columns)
	if err != nil {
		// **A CALIBRATION THAT GIVES UP OWES THE OWNER THE STATE THEY HAD.**
		//
		// Refusing to STORE a fit used to leave the window at whatever width the search last tried, so
		// the owner got the extra column and the horizontal scroll they reported — and because nothing
		// was stored, the toggle read off. Two complaints, one cause: their window had been changed and
		// the only way back was a button we had told them was not pressed. The button was telling the
		// truth about our state; our state was wrong.
		//
		// Restored from `pending_restore`, which was written to disk before the first resize precisely
		// so that a run which does not finish still knows where the window came from.
		if restoreErr := RestoreWindow(ctx, t, store); restoreErr != nil {
			log.Printf("width: the window could not be put back after a failed calibration: %v", restoreErr)
		}
		// Truthful together, or not at all: the geometry they started with, no fit applied, and no
		// setting claiming otherwise.
		store.Active = nil
		store.persist()
		return 0, false, err
	}
	store.Fits[key] = fit
	// The search's answer, and what it cost the window.
	log.Printf("width: calibration settled on %d points for %d columns; the window began at %d points (%+d)",
		fit.Points, fit.Columns, w.Width, fit.Points-w.Width)
	applied := fit
	store.Active = &applied
	store.persist()
	return fit.Columns, true, nil
}

// hasFit reports whether the store already holds an entry for this key, for the log alone.
//
// A named helper rather than an inline lookup because it is read inside a `map[bool]string`, where a
// comma-ok would not fit and a second `if` would put the interesting fact two lines from the line that
// prints it.
func hasFit(store *Store, key string) bool {
	_, known := store.Fits[key]
	return known
}

// verifyColumns asks the terminal how wide it actually is, right now.
//
// The same machinery calibration uses, for the same reason: a shell is the only thing that reports a
// terminal's width. It creates one session, reads the number it prints, and closes it. **It resizes
// nothing** - the window is already at the width being checked.
//
// The error is as load-bearing as the number. Every caller must treat a failure as "not known",
// never as "does not match" - see the three-way in [To].
func verifyColumns(ctx context.Context, t Terminal, dir string) (int, error) {
	var measured int
	err := withCalibrationSession(ctx, t, dir, func(sessionID string) error {
		cols, err := awaitReportedColumns(ctx, t, sessionID)
		if err != nil {
			return err
		}
		measured = cols
		return nil
	})
	if err != nil {
		return 0, err
	}
	return measured, nil
}

// chromeMoved turns a contradiction into a diagnosis, when the fit is new enough to carry one.
//
// Chrome is the points of window width the terminal never gets - the sidebar, the padding, the
// divider. `points = chrome + columns * cell`, so with the cell this fit was measured under, the
// chrome implied by what was just measured falls out for free. On 2026-08-06 that pair read 439 then
// and 331 now: the owner had dragged their sidebar narrower, which is a sentence nobody had to
// derive at midnight.
//
// **Empty for a fit stored before those fields existed**, and empty if the numbers are not usable.
// A diagnosis built on a missing number is worse than no diagnosis: it reads as measured.
func chromeMoved(fit Fit, measured int) string {
	if fit.ChromePoints <= 0 || fit.CellMilliPoints <= 0 || measured <= 0 {
		return ""
	}
	// Rounded, not truncated: this number is read by a human comparing it with another one, and a
	// point lost to integer division reads as a real difference.
	nowChrome := fit.Points - (measured*fit.CellMilliPoints+500)/1000
	return fmt.Sprintf(" Chrome was %d points at calibration and is %d now, if the cell has not moved.",
		fit.ChromePoints, nowChrome)
}

// RestoreWindow puts the owner's window back: geometry AND zoom.
//
// Called on disconnect. Zoom is restored because it changes underneath a resize - measured, not
// assumed - so returning the frame alone leaves a state they did not choose.
func RestoreWindow(ctx context.Context, t Terminal, store *Store) error {
	r := store.Pending
	if r == nil {
		return nil
	}
	// Width only, so a restore puts back exactly what a fit changed and nothing else. The recorded
	// height stays in the file as a description of what the window WAS - it is never applied, because
	// we never moved it.
	// **THE HEIGHT IS THE OWNER'S AT ALL TIMES, so it is read now and never replayed.**
	//
	// This used to send `r.Height`, the height captured when the fit was switched on, and the
	// paragraph here argued for it: the record is the only thing that remembers the window they had.
	// That argument was wrong in a way the owner's report made obvious: the phone's fit mode
	// changed the height of his screen and left half of it idle, 2026-08-09, after carrying the
	// window to a 2560x1440 screen. The remembered 938 came back and half their display sat idle.
	//
	// **And it is the same defect pointed both ways.** They reported a height that shrank; the same
	// line would stamp a stale height over a window they had made TALLER while the fit was on, and
	// that would have arrived next week as "it keeps resizing my window". A rule survives both
	// directions: the fit owns the width, the height is theirs, and the restore has exactly one job.
	//
	// The 2026-07-31 observation the old paragraph rested on - the record held 992 while the window
	// measured 1084 - is still true and is now read the other way round. Something moved that height
	// and it was not us; replaying our copy over it is how a restore hands back a frame the owner
	// never had and calls it restored.
	//
	// Falls back to the current height only when the record has none, since agterm requires a
	// positive one and refusing to restore at all would be worse.
	// agterm requires a positive height on the wire, so one is unavoidable; what is avoidable is
	// sending a height that means anything. This is the one the window already has.
	height, err := currentHeight(ctx, t, r.WindowID)
	if err != nil {
		return err
	}
	err = t.ResizeWindow(ctx, r.WindowID, r.Width, height)

	// **Zoom needs no restoring, because it is DERIVED FROM WIDTH.** Measured by hand 2026-07-30,
	// with no zoom command issued at any point: at 1728 points `zoomed` reads true, at 900 it reads
	// false, and setting the width back to 1728 makes it read true again on its own.
	//
	// So an explicit toggle here would UNDO what the resize above already put right - it would fire
	// precisely when the width restore had already fixed the flag, and set it wrong. The earlier
	// "zoom flips underneath a resize" was this behaviour seen without the width that explains it.
	//
	// Restoring the width restores the zoom. There is nothing else to do.

	// **TWO DIFFERENT FAILURES LIVE ONE LINE APART HERE, AND THEIR CORRECT ANSWERS ARE OPPOSITE.**
	//
	// This used to clear the record unconditionally, reasoning that "a stale pending record would put
	// the window somewhere older still". That reasoning is about a record that is OUT OF DATE - the
	// window has moved on since, so putting it back would be wrong. It is not about the case below.
	//
	// A resize that ERRORED did not move anything. The likeliest cause is the bridge starting before
	// agterm is up, which happens at login. The window is still exactly where our resize left it, so
	// the record is not stale - **it is the only thing that can still put it back**, and discarding it
	// destroys the owner's original geometry permanently. The next on-press would then record the
	// NARROW window as where their window came from, and no press afterwards could ever undo it.
	//
	// So: keep it, and let the owner's next off press perform the restore once agterm is there. The
	// setting is left alone by the same reasoning - the window IS still narrowed, so a flag saying so
	// is true.
	if err != nil {
		return err
	}

	// **And the sidebar, after the window**. The record carries the width the owner had
	// when nothing is known to have moved it; a zero is a record that predates the fit moving it, or
	// an agterm that never reported one, and nothing is sent for it. Window first, so a sidebar that
	// cannot be put back leaves a wide window with a wide sidebar rather than a narrow window with a
	// narrow one - and the record stays either way, for the same reason it stays above: it is the
	// only thing that still remembers how wide his sidebar was.
	if r.SidebarWidthMilli > 0 {
		if _, err := t.SetSidebarWidth(ctx, r.WindowID, r.SidebarWidthMilli); err != nil {
			return fmt.Errorf("the window is back but the sidebar could not be put back to %.1f points: %w",
				float64(r.SidebarWidthMilli)/1000, err)
		}
	}

	// The window really is back. NOW the record is spent, and keeping it would put the window
	// somewhere older still on the next attempt - which is the failure the old comment described and
	// the only one it was right about.
	store.Pending = nil
	return nil
}

// **A detect-and-refuse used to sit here and it has been reverted. Do not put it back from memory.**
//
// It refused to calibrate when a window was fullscreen AND zoomed, on the evidence that three probe
// widths had all left the window at 1728 points and the terminal at 162 columns. That evidence was
// worthless: the probes were `agtermctl window resize W H --target ID`, and that command takes
// --width/--height with the id POSITIONAL, so every one of them exited with
// `Missing expected argument '--width'` into a stderr nothing read. The commands never ran.
//
// Shipping it would have been worse than the bug it was written against, because the owner's window
// sits in exactly that state normally - so the feature would have declined to work for its only user,
// citing a cause that does not exist. A refusal is self-justifying; nobody investigates a tool that
// says no for a plausible reason.
//
// Measured by hand afterwards, one command at a time: resize works, in fullscreen, with zoom on or
// off. 1728 points -> 162 columns, 900 -> 58, 500-asked (640 reported) -> 26.

// currentHeight is the window's height as it is at this instant.
//
// **Read immediately before each resize and handed straight back**, so the height we send is the
// height the window already has. agterm will not accept a resize without one, so "do not change their
// height" cannot be expressed by omission - it is expressed by reading it fresh every time. A height
// captured at the start of a multi-step operation and replayed into every probe is the thing the owner
// complained about, and it is invisible because the number looks like a no-op.
//
// # A NEGATIVE FINDING IS THE KIND A BROKEN INSTRUMENT PRODUCES
//
// The height parameter was deleted from this whole path on 2026-07-31, on a measurement that was
// false. A probe read `width` and `height` at the TOP LEVEL of window.list, where the geometry is
// nested under `geometry`, so an ordinary window looked like one reporting no size at all - and
// "fullscreen reports no size" became an architectural decision. Every resize then failed with
// `window.resize requires positive width and height`, and the owner's fit stopped working entirely.
//
// The lesson is not "read the right keys". It is that **absence is exactly what a misaimed instrument
// reports**: the field is missing, nothing carries escapes, the counter did not move. A negative
// finding needs a positive control - something that MUST appear if the probe is pointed correctly -
// before anything is built on it. Here that control costs one line: a window that is open has a
// positive height, so a zero means the probe is wrong rather than the window is strange.
func currentHeight(ctx context.Context, t Terminal, id string) (int, error) {
	windows, err := t.Windows(ctx)
	if err != nil {
		return 0, err
	}
	for _, w := range windows {
		if w.ID == id {
			if w.Height <= 0 {
				return 0, fmt.Errorf("the window reports a height of %d, which agterm will not accept", w.Height)
			}
			return w.Height, nil
		}
	}
	return 0, fmt.Errorf("the window this fit belongs to is no longer open")
}

// resizeAndSettle applies a width and waits until the window has actually taken it.
//
// # A resize is not effective when the command returns
//
// The owner said it first - try it slowly by hand before anything else - and by hand it is obvious:
// a person pauses without thinking about it and sees the window move. A script that resizes and
// reads the geometry back immediately can read the OLD value and conclude nothing happened, which
// is exactly the silence a rejected command produces. Two different causes, one indistinguishable
// reading.
//
// # Waiting for STABILITY, not for equality
//
// It deliberately does not wait for the reported width to equal what was asked. **agterm clamps.**
// Measured by hand 2026-07-30: asking for 500 points settles at 640, reproducibly, and the terminal
// really does render 26 columns there - which is the owner's own hand figure. Demanding equality would
// reject a correct outcome and loop until it timed out.
//
// So it polls until the reported width is the same twice running, and returns what it settled to. The
// caller measures columns AFTER that, against the width the window actually took.
//
// This is a mechanism, not a sleep tuned until a test passed. It has no magic duration in it: it
// observes the thing it is waiting for, and if the window never settles it says so rather than
// proceeding on an assumption.
func resizeAndSettle(ctx context.Context, t Terminal, id string, width int) (int, error) {
	// Read afresh on every probe. A calibration is a dozen resizes over several seconds, and a height
	// captured before the first one would be replayed into all of them - pinning the window to a
	// height that may have changed underneath, which is exactly what the owner reported.
	height, err := currentHeight(ctx, t, id)
	if err != nil {
		return 0, err
	}
	if err := t.ResizeWindow(ctx, id, width, height); err != nil {
		return 0, err
	}

	last := -1
	for attempt := 0; attempt < settleAttempts; attempt++ {
		select {
		case <-ctx.Done():
			return 0, ctx.Err()
		case <-time.After(settleInterval):
		}

		windows, err := t.Windows(ctx)
		if err != nil {
			return 0, err
		}
		for _, w := range windows {
			if w.ID != id {
				continue
			}
			if w.Width == last {
				return w.Width, nil
			}
			last = w.Width
		}
	}
	return 0, fmt.Errorf("the window never settled after being asked for %d points; it last reported %d",
		width, last)
}

const (
	// Two reads agreeing is the signal. The interval is short because the wait is bounded by the
	// observation and not by this number - a slower machine takes more attempts, not a wrong answer.
	settleInterval = 250 * time.Millisecond
	settleAttempts = 16
	// stableReadings is how many consecutive agreeing reads make a count believable.
	//
	// **Three, because the span has to exceed the probe's own redraw period.** [CalibrationCommand]
	// redraws every 200ms and this package writes that command, so the period is known rather than
	// assumed. Two reads 250ms apart can both land inside one stale frame; three span 500ms and
	// cannot. Measured staleness after a resize was ~280ms - see awaitReportedColumns.
	stableReadings = 3
)

// refuseAnImpossibleFit is the never-more rule, enforced AT THE WRITE.
//
// # Why here as well as at the target
//
// The target is computed to fit strictly, but the search returns what it MEASURED, and a search that
// gives up can measure more than it asked for. The guard therefore belongs where the number enters the
// file, so an impossible fit cannot be stored whatever computes it - the owner's cache held one, and a
// cache invalidated by nothing automatic makes it permanent.
//
// # The refusal states the arithmetic
//
// "42 columns at 11.0dp is 462dp in a 440dp box" is a bug report; "invalid fit" is a mystery. Same
// rule as internal/keys, which names the byte it saw rather than reporting that input was rejected.
//
// `sidebarMaxed` says the sidebar stage ran and hit its ceiling, so the sentence can say that no
// layout on this Mac is narrower rather than leaving a reader to wonder why the sidebar
// was not tried.
func refuseAnImpossibleFit(measured, target, boxWidthDp, characterWidthMilliDp int, sidebarMaxed bool) error {
	if measured > target {
		width := "unknown width"
		if characterWidthMilliDp > 0 {
			width = fmt.Sprintf("%d columns at %.1fdp is %.0fdp, in a box that holds %ddp",
				measured, float64(characterWidthMilliDp)/1000,
				float64(measured*characterWidthMilliDp)/1000, boxWidthDp)
		}
		if sidebarMaxed {
			width += fmt.Sprintf(", with the window at its floor and the sidebar at its maximum of "+
				"%d points, so no layout on this Mac is narrower", maxSidebarPoints)
		}
		return fmt.Errorf("calibration ended on %d columns having asked for %d, and storing it would "+
			"scroll every line for ever: %s", measured, target, width)
	}
	return nil
}

// active picks the window to act on, **by geometry we can verify and not by a flag we cannot.**
//
// This used to read `w.Fullscreen || w.Width > 0`, so a window claiming fullscreen was chosen even
// when its width said nothing. That flag has been caught reporting something which is not
// native fullscreen - the owner's window read `fullscreen: true` at 802 points on a 1496-point
// display, and again at 1496 on a 2560-wide one. Whatever it describes, it is not a window filling a
// screen, and a resize path that trusts it can select a window whose size it could not confirm.
//
// Today it changes nothing on their machine, because every window there reports the same thing. That
// is the definition of a latent bug rather than a safe one: it is one unusual reading away from
// resizing something on the strength of a claim.
//
// **The flag is still recorded** in the restore point, where a person reading the file can see what
// the window said it was. Nothing branches on it.
//
// # It did not pick the active window, and its name said it did
//
// Until 2026-08-26 this returned **the first window agterm listed with a usable size**. Nothing in it
// selected by activeness; the name asserted a property the body never implemented, and the doc above
// discusses *which* window to trust the size of without ever settling *which window*.
//
// With one window those are the same window and nobody could tell. With two, the mismatch is
// structural rather than a race: **the phone's session list is one window's and the resize was
// another's.** `Client.Tree` calls `tree` with no window argument, which agterm answers for the
// FRONTMOST window — so every session the phone can show belongs to that window, while the resize
// went to whichever window came first in `window.list`.
//
// So the owner could press Fit on a session in the window he is looking at and watch a different
// window change shape. It has never happened to him because he runs one window, which is the
// definition of latent rather than safe.
//
// **agterm has always said which window is active and this package threw it away** — `agterm.Window`
// decodes the field, and the adapter simply did not carry it across.
//
// # The fallback is the old behaviour, deliberately
//
// A window list with nothing marked active is not evidence about which window to pick, so it gets the
// answer it has always had rather than a refusal: this path exists to resize the owner's terminal, and
// declining to act because a flag is missing would take the feature away over a field that has never
// been absent.
func active(windows []Window) (Window, error) {
	var fallback *Window
	for i, w := range windows {
		if w.Width <= 0 || w.Height <= 0 {
			continue
		}
		if w.Active {
			return w, nil
		}
		if fallback == nil {
			fallback = &windows[i]
		}
	}
	if fallback != nil {
		return *fallback, nil
	}
	return Window{}, errors.New("agterm reported no window with a usable size to resize")
}

const (
	// The floor is what a phone can show; the ceiling is a sanity bound, not a measurement. Both are
	// ours, checked here, so a hostile or corrupt column count cannot ask for a one-pixel window.
	minColumns = 20
	maxColumns = 400
	// Each step is a resize plus a screen read on the owner's live terminal. Bounded because the
	// owner is watching their window change while this runs.
	maxSearchSteps = 8
	// How many single-column steps down the search may walk when it lands ABOVE what was asked. Three,
	// for the same reason as the bound above: each one moves a window the owner can see. If three
	// columns of slack still cannot reach the target, the fit really is out of reach and the refusal
	// below is the honest answer - with the window put back.
	overshootSteps = 3
	// sidebarSteps bounds the sidebar stage of a calibration for the same reason: each
	// step moves an edge the owner can see. The first step takes the whole surplus, so a second and
	// third exist only for rounding, and a fit three columns past that is out of reach.
	sidebarSteps = 3
	// maxSidebarPoints is agterm's own ceiling on the sidebar, the same one dragging has. Named for
	// the refusal's sentence; the clamp itself is agterm's and is read from the echo, never assumed.
	maxSidebarPoints = 560
)

// CalibrationCommand is what the calibration session runs. **The whole measurement is in this line.**
//
// # Why an over-long line instead of a ruler
//
// A terminal wraps at exactly its own boundary. Print something longer than any plausible width and
// the wrap point IS the column count - no arithmetic, no known-width string that has to be kept in
// step with a constant somewhere else, and exact rather than approximate.
//
// It also removes the moving target that broke the previous three attempts: the content cannot reflow
// narrower than the terminal, because it is longer than the terminal by construction.
//
// It redraws in a loop so that every resize probe is measured against a FRESH wrap. A single print
// would be measured once and then re-measured after resizes that may or may not have reflowed it.
//
// `zsh -lc` because session.new is argv-only and runs with the app's GUI PATH - a bare binary name
// exits 127 and leaves a dead pane. Documented caveat, not a guess.
const CalibrationCommand = `zsh -lc 'while :; do clear; tput cols; sleep 0.2; done'`

// CalibrationSessionName is what the owner sees in the sidebar for the second or two it exists.
//
// Named so that if it is ever orphaned by a crash, they can tell at a glance what made it and that it
// was not theirs.
const CalibrationSessionName = "bos-calibrate"

// strayFile records the calibration session's id while it exists.
//
// **Its own file, deliberately NOT resize-cache.json.** That file is a cache: it is named to invite
// deletion, and LoadStore already treats a parse failure as "start fresh". A record of something that
// must be cleaned up cannot live somewhere a tidy-up is expected to be harmless - see the 5b finding,
// where the restore point sharing a lifetime with the calibration nearly cost the owner their window.
const strayFile = "calibration-session.json"

type stray struct {
	SessionID string `json:"session_id"`
}

// rememberStray writes the id to disk. Called AFTER creation and BEFORE anything else happens.
//
// The ordering is the whole point and it is the same as the restore point's: in memory this record
// would cover only the path where the function returns, and that path was never the one at risk. If
// the bridge dies mid-calibration, the session is real, it is on the owner's screen, and the only
// thing that knows to remove it is this file.
func rememberStray(dir, id string) {
	if dir == "" {
		return
	}
	raw, err := json.Marshal(stray{SessionID: id})
	if err != nil {
		return
	}
	_ = os.WriteFile(filepath.Join(dir, strayFile), raw, 0o600)
}

func forgetStray(dir string) {
	if dir == "" {
		return
	}
	_ = os.Remove(filepath.Join(dir, strayFile))
}

// CloseStraySession removes a calibration session a previous run left behind. Call at startup.
//
// A stray session in the owner's sidebar is exactly the litter that erodes trust in a tool that
// touches their machine, and nothing on the phone would ever show it. This is the only path other
// than a live calibration that may call CloseSession, and it is fed from the file rather than from
// anything the owner or the phone supplied.
func CloseStraySession(ctx context.Context, t Terminal, dir string) error {
	if dir == "" {
		return nil
	}
	raw, err := os.ReadFile(filepath.Join(dir, strayFile))
	if err != nil {
		return nil
	}
	var st stray
	if err := json.Unmarshal(raw, &st); err != nil || st.SessionID == "" {
		forgetStray(dir)
		return nil
	}
	err = t.CloseSession(ctx, st.SessionID)
	// Forgotten either way: a session that cannot be closed will not become closeable by being
	// retried at every start, and a stale id is one that might later name something else.
	forgetStray(dir)
	return err
}

// withCalibrationSession creates a session the bridge owns, runs body against it, and removes it.
//
// # The provenance rule, expressed as structure rather than as a comment
//
// `created` is the ONLY id that reaches CloseSession, it is assigned once from NewSession's return,
// and the close sits in a defer so no error path can skip it. Nothing here reads tree, takes an id
// from a parameter, or accepts one over the wire - which is what makes "close the session we just
// created" impossible to turn into "close a session" by editing one line.
//
// The owner's view jumps to this session while it exists. They accepted that, so there is no
// session.select putting it back, and that absence is
// a decision recorded in the allowlist.
func withCalibrationSession(ctx context.Context, t Terminal, dir string, body func(sessionID string) error) error {
	created, err := t.NewSession(ctx, CalibrationCommand, CalibrationSessionName)
	if err != nil {
		return fmt.Errorf("creating the calibration session: %w", err)
	}
	// Before the first resize, before the first read, before anything that can fail.
	rememberStray(dir, created)

	defer func() {
		_ = t.CloseSession(ctx, created)
		forgetStray(dir)
	}()

	// **The probe is never split**. A session with one pane IS that pane, at the full width
	// of the terminal area, and that is the only geometry the phone ever puts his session into. There
	// is nothing left to shape the probe to.
	return body(created)
}
