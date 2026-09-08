package api

import (
	"context"
	"errors"
	"fmt"
	"log"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
	"github.com/isachivka/agterm-remote/bridge/internal/resize"
)

// # What these logs may and may not contain
//
// **Widths, column counts, cache hit or miss, and refusal reasons are NOT the owner's data.** They
// describe the shape of a window and the arithmetic of a decision, and recording them is what made
// today's failures diagnosable from a file rather than from reading source.
//
// **Screen content, session names and keystrokes are theirs and appear in NO log and NO persisted
// file, ours included.** That is unchanged by any of this and it is not a matter of degree: the line
// is not "avoid logging too much of the request", it is that the request's TEXT never enters a log at
// all. A future change that logs "the request" to save a line would carry the text with it, which is
// why this says so here rather than trusting the habit.
//
// terminal adapts the agterm client to what the resize package needs.
//
// The two Window types are deliberately separate rather than shared. `resize` is where the arithmetic
// and the search live, and keeping it free of agterm is what lets both be tested against a fake with a
// known right answer — the parts most likely to be wrong are the ones that do not need a Mac. The cost
// is this struct, which is the whole cost.
type terminal struct{ client *agterm.Client }

// OpenSplitPane gives one of the OWNER'S sessions a second pane, on his tap. It is no
// longer pointed at the calibration session: the shaped probe was deleted, so nothing this bridge
// creates is ever split.
//
// MaximizePane shows one pane at the full width of the terminal area, and is likewise his gesture.
func (t terminal) OpenSplitPane(ctx context.Context, id string) error {
	return t.client.OpenSplitPane(ctx, id)
}

func (t terminal) Windows(ctx context.Context) ([]resize.Window, error) {
	windows, err := t.client.Windows(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]resize.Window, 0, len(windows))
	for _, w := range windows {
		out = append(out, resize.Window{
			ID: w.ID,
			// **Carried across at last**. agterm has always sent this and this adapter
			// always dropped it, which is how the resize path came to choose a window by list order.
			Active:     w.Active,
			Fullscreen: w.Fullscreen,
			Zoomed:     w.Zoomed,
			Display:    w.Geometry.Display,
			X:          w.Geometry.X,
			Y:          w.Geometry.Y,
			Width:      w.Geometry.Width,
			Height:     w.Geometry.Height,
		})
	}
	return out, nil
}

func (t terminal) ResizeWindow(ctx context.Context, id string, width, height int) error {
	return t.client.ResizeWindow(ctx, id, width, height)
}

// NewSession and CloseSession exist for calibration alone. **CloseSession is never called from this
// file** - only from internal/resize, and only with an id NewSession just returned, because the
// safety of the whole feature is that provenance and nothing else. See the allowlist entries.
func (t terminal) NewSession(ctx context.Context, command, name string) (string, error) {
	return t.client.NewSession(ctx, command, name)
}

func (t terminal) CloseSession(ctx context.Context, id string) error {
	return t.client.CloseSession(ctx, id)
}

func (t terminal) ZoomWindow(ctx context.Context, id string) error {
	return t.client.ZoomWindow(ctx, id)
}

// Laptop reads one window's sidebar and font from the tree. This replaced a read of
// agterm's private per-window state file, the only thing the bridge ever read that was not the
// socket; discussion #511 put the sidebar on the socket and the file coupling went.
//
// The font is the one MOST sessions use. agterm keeps it per session, a calibration session takes
// the default, and one oddly-sized session is not a reason to call a whole fit into question.
func (t terminal) Laptop(ctx context.Context, windowID string) (resize.Laptop, error) {
	tree, err := t.client.TreeOf(ctx, windowID)
	if err != nil {
		return resize.Laptop{}, err
	}
	counts := map[int]int{}
	for _, ws := range tree.Workspaces {
		for _, s := range ws.Sessions {
			if s.FontSize > 0 {
				counts[s.FontSize]++
			}
		}
	}
	return resize.Laptop{
		SidebarWidthMilli: int(tree.SidebarWidth*1000 + 0.5),
		SidebarVisible:    tree.SidebarVisible,
		FontSize:          modeOf(counts),
	}, nil
}

// SetSidebarWidth is the milli-point face of the client's points call, so the resize package carries
// no float. The echo is converted the same way the tree is, so a width that went out comes back equal.
func (t terminal) SetSidebarWidth(ctx context.Context, windowID string, milli int) (int, error) {
	applied, err := t.client.SetSidebarWidth(ctx, windowID, float64(milli)/1000)
	if err != nil {
		return 0, err
	}
	return int(applied*1000 + 0.5), nil
}

// modeOf is the most common value, and the LOWEST of the most common when they tie, so the answer is
// the same on every read: a map walk is not, and a detector that flickered between 13 and 14 on the
// same tree would buy a measurement on every other press.
func modeOf(counts map[int]int) int {
	best, bestCount := 0, 0
	for v, n := range counts {
		if n > bestCount || (n == bestCount && v < best) {
			best, bestCount = v, n
		}
	}
	return best
}

func (t terminal) Text(ctx context.Context, sessionID string, lines int) (string, error) {
	// **Primary, explicitly.** The calibration session is one this bridge created and never splits, so
	// the pane is not a question here - but it is still named rather than defaulted, because an absent
	// pane is exactly the thing the wrong-pane fix removed from this codebase.
	return t.client.Text(ctx, sessionID, lines, agterm.PaneLeft)
}

// resizeTo makes the laptop's terminal the width the phone asked for, in COLUMNS.
//
// The owner's idea and it is better than reflowing on the phone: vim, htop and every table-printing
// tool already know how to lay out at forty columns, and guessing on their behalf is what a renderer
// would be doing.
//
// `columns: 0` means put the window back. One verb rather than two because there is one thing being
// controlled — the width of the owner's window — and a separate `restore` verb would be a second name
// for the same authority.
// refuse logs the reason and returns it, so no refusal on this path is silent.
//
// Two returns here used to log nothing at all: a phone that sent no character width, and a bridge
// built without a cache. Both produced `verb=resize ok=false` and not one word about why, which is
// indistinguishable in the record from every other way the verb can fail.
//
// The text goes to the owner as well as to the log - see BridgeRefused in the app. It is written to be
// read by the person at the keyboard, the same reason the impossible-fit refusal carries its
// arithmetic.
// refuse logs the arithmetic and hands the phone a no that does not look like a broken connection.
//
// # Why this is a CONTENT refusal
//
// It returned `fail`, which the phone renders as a full-screen error replacing the terminal, the
// session list and the input bar. On 2026-08-26 the owner got exactly that for a calibration that
// declined: a page-filling English sentence about *probe widths*, addressed to us, out of a log, with
// nothing in it he could act on.
//
// **That shape was abolished and this path had quietly re-grown it.** A fit that will not measure
// is not the link breaking: the bridge answered, the socket is healthy, and his session is fine. It
// costs one line on the notes surface beside the other fit notes.
//
// The category's own test is *"would this fail again, identically, on a perfectly healthy laptop?"*
// and a geometry the search cannot resolve fails again every time. The direction it warns about —
// a dead laptop mislabelled as content — cannot arise here, because reaching this line means a reply
// was composed.
//
// **The arithmetic still goes to the log, which is where it was always addressed.** That is the whole
// division: the file gets the numbers, the phone gets a sentence.
func refuse(format string, args ...any) Response {
	message := fmt.Sprintf(format, args...)
	log.Printf("%s", message)
	return refuseContent(message)
}

func (h *Handler) resize(ctx context.Context, req Request) Response {
	if h.store == nil {
		// **Every refusal on this path says why.** A verdict with no reason in the log cost an hour on
		// 2026-07-30 and would have cost more without the owner's log open: `ok=false` with nothing
		// above it names neither the cause nor which of several silent returns produced it.
		return refuse("resize is not configured")
	}
	term := terminal{client: h.client}

	// **Off is "the phone sent no measurement".** It used to be `columns == 0`, back when the phone
	// asserted a column count; it no longer sends one at all, so the box width is what says whether
	// this is a request to adapt or a request to stop.
	if req.BoxWidthDp == 0 {
		if err := resize.RestoreWindow(ctx, term, h.store); err != nil {
			// The restore is the path that fails when agterm refuses a resize outright - which is how
			// a width-only request looked on 2026-07-31: five ok=false lines with nothing above them.
			return refuse("width: the window could not be put back: %v", err)
		}
		// Off has to outlive this process, or the next start silently re-narrows a window the owner
		// deliberately gave back to themselves.
		log.Printf("width: off, restoring the window")
		// Off is the absence of a fit, not a flag beside one.
		h.store.Active = nil
		h.saveStore()
		return Response{OK: true, FitEnabled: boolPtr(false)}
	}

	// **The target is derived here, from two things the PHONE MEASURED**, and only to give the search
	// something to aim at. The answer that comes back is what the laptop's terminal really rendered.
	if req.CharacterWidthMilliDp <= 0 {
		return refuse("width: REFUSED - the phone did not say how wide one character measures on its screen")
	}
	columns := columnsThatStrictlyFit(req.BoxWidthDp, req.CharacterWidthMilliDp)

	log.Printf("width: asked %d columns from a %ddp box at %.1fdp per character%s",
		columns, req.BoxWidthDp, float64(req.CharacterWidthMilliDp)/1000,
		map[bool]string{true: " (recalibrate)", false: ""}[req.Recalibrate])

	// **What the fit is actually going to size, said out loud**.
	//
	// The owner pressed this four times in fifty seconds on 2026-08-26, each press logging
	// `calibrated, 45 columns now in effect`, and then switched the feature off. Every one of those
	// lines was true and none of them was about what he was looking at: **the fit sizes the WINDOW,
	// and he was reading a PANE.** Measured on the control socket the same day — one window, no
	// resize between the two readings — an unsplit session reported 45 columns and each half of a
	// split reported 21.
	//
	// So the log said "45 columns now in effect" while 21 columns were in front of him, and nothing in
	// the record could have told anyone that, because **nothing on this path knew the session had a
	// second pane.** The session id was on the request and was never read.
	//
	// This is the line that would have made it a five-minute diagnosis instead of a day.
	log.Printf("width: %s", h.paneShape(ctx, req.Session))

	intent, err := intentOf(req)
	if err != nil {
		return refuse("width: REFUSED - %v", err)
	}

	got, calibrated, err := resize.To(
		ctx, term, h.store, h.stateDir, req.BoxWidthDp, req.MarginDp, req.CharacterWidthMilliDp,
		columns, intent)
	if errors.Is(err, resize.ErrNotCalibrated) {
		// **An ordinary reply, not a refusal**. The phone asked to apply a fit if one was
		// known; not knowing is an answer to that question. It goes back as a fact the phone can act
		// on by putting one line where the fit notes live, and the owner presses when he wants it.
		log.Printf("width: nothing recorded for this geometry and the request asked not to measure")
		return Response{OK: true, NeedsFit: true, FitEnabled: boolPtr(h.store.Active != nil)}
	}
	if err != nil {
		// **The refusal, with its arithmetic, because that is what makes it a bug report.** The
		// messages from internal/resize spell out "42 columns at 11.0dp is 462dp in a box that holds
		// 440dp", and the LOG is who that is addressed to.
		//
		// **The phone gets a content refusal, not a failure**. It used to get `fail`, which
		// replaces his terminal with a full-page error carrying that same sentence about probe widths.
		// He cannot act on any of it and his session had done nothing wrong.
		log.Printf("width: REFUSED - %v", err)
		return refuseContent(describe(err))
	}
	log.Printf("width: %s, %d columns now in effect",
		map[bool]string{true: "calibrated", false: "applied from cache"}[calibrated], got)
	// The setting is set inside resize.To, by the only code that holds the fit it came from.
	// Saved after the work for the CALIBRATION only. The restore point does not wait for this and must
	// not: resize.To writes it to disk itself, before the first window change, because a crash between
	// that change and this line is precisely the case where the owner is left with a narrow window.
	// This save costs a re-calibration if it is missed, which is two resizes.
	h.saveStore()

	// The MEASURED count, not the requested one. The search is bounded, so it can stop short, and a
	// reply that echoed the request would tell the phone it had forty columns when it had thirty-eight
	// - which is exactly the lie that makes a wide line wrap where nobody expects it.
	// **The bridge's flag is the truth and it rides back on every reply.** The phone used to keep its
	// own copy, and a local copy of remote state is a second source of truth - the moment they
	// disagreed, the owner's press sent the opposite of what they intended and the feature looked
	// dead. Measured 2026-07-30: `enabled: false` on disk while they were pressing a button to turn
	// it on.
	return Response{OK: true, Columns: got, Calibrated: calibrated, FitEnabled: boolPtr(h.store.Active != nil)}
}

// paneShape describes what the fit is about to size, for the log and for nothing else.
//
// # Numbers and outcomes only
//
// Pane count, pane roles, and whether the split is on screen. **No session name and no screen text**,
// which is the owner's rule and does not bend for our convenience — see the note at the top of this
// file. A role is `left`/`right`/`scratch`/`overlay`, agterm's own closed vocabulary, and carries
// nothing of his.
//
// # It never fails the press
//
// Every path returns a sentence. A diagnostic that could refuse a fit would be a diagnostic that made
// the thing it was added to debug worse, and the tree read here is the one round trip it costs — the
// same call `To` already makes for the window list, on a path that then resizes a window.
func (h *Handler) paneShape(ctx context.Context, session string) string {
	if err := validateSessionID(session); err != nil {
		// Older phones send no session on this verb. Said plainly rather than left blank, because a
		// missing line and a line saying "unknown" look identical in a file and mean different things.
		return "the phone named no session, so the pane layout is unknown"
	}
	tree, err := h.client.Tree(ctx)
	if err != nil {
		return fmt.Sprintf("the pane layout could not be read: %v", err)
	}
	for _, ws := range tree.Workspaces {
		for _, s := range ws.Sessions {
			if s.ID != session {
				continue
			}
			roles := make([]string, 0, len(s.Surfaces))
			for _, surface := range s.Surfaces {
				shown := "hidden"
				if surface.Visible {
					shown = "on screen"
				}
				roles = append(roles, surface.Kind+":"+shown)
			}
			if !s.HasSplitPane() {
				return fmt.Sprintf("this session has one pane %v, so the window is the terminal minus "+
					"the sidebar. %s", roles, describeSplitGeometry(tree, s))
			}
			// **A second pane is no longer a problem; a second pane ON SCREEN is**.
			//
			// The owner named the two terms that stand between the window and his text: panes can
			// be resized and so can the sidebar, so both are of unknown width. The phone now
			// removes one of them by hiding the split, so a pane that exists but is not drawn costs
			// the fit nothing and this says so rather than warning about it.
			//
			// Two panes ON SCREEN under a phone that maximizes means the maximize did not take, and
			// the count below is a fraction of what he is reading. `describeSplitGeometry` says it
			// again from agterm's own file, which is a second opinion rather than a repetition.
			//
			// **agterm's own `isSplit`, not a count of visible surfaces.** Counting was written here
			// first and was wrong: a scratch terminal is a surface, so a session with a HIDDEN split
			// and an open scratch showed two visible surfaces and would have been reported as a failed
			// maximize. The same conflation the split gate had to undo one question over — asking
			// how many things are on screen is not asking whether the split is.
			if s.Split {
				return fmt.Sprintf(
					"this session is SHOWING BOTH PANES %v - the phone maximizes what it shows, so this "+
						"should not happen and the columns below are a fraction of his pane. %s",
					roles, describeSplitGeometry(tree, s))
			}
			return fmt.Sprintf(
				"this session has a second pane %v but is showing one, which is what the phone asks "+
					"for. %s", roles, describeSplitGeometry(tree, s))
		}
	}
	return "the session is not in the tree"
}

// describeSplitGeometry reports the sidebar, and whether the maximize took, read from the
// tree since the sidebar became a knob.
//
// # What is left after the divider went
//
// The divider and the axis used to be here, because the fit's target was a pane's SHARE of a window
// and the share was a number he could drag. The phone now maximizes the pane it shows, so the share is
// gone and so are both terms.
//
// **The sidebar is not a share and it stays.** It is chrome that eats window width before any pane
// sees it, he can collapse it, and it is not computed from anything — it is read from the tree, which
// since agterm 0.26 carries it at the top level. It is also the width the fit now SETS, so this line
// is the width the fit is about to override rather than a term it has to live with.
//
// **`Split` stays for a reason it did not have before.** A session the phone is watching should not be
// showing two panes: the phone hid the split when it showed the pane. One that still reports split is
// one where the width below is a fraction of what he is reading, and this line is the only place that
// can say so.
func describeSplitGeometry(tree *agterm.Tree, s agterm.Session) string {
	sidebar := "the sidebar is hidden"
	switch {
	case tree.SidebarVisible:
		sidebar = fmt.Sprintf("the sidebar is %.1f points wide", tree.SidebarWidth)
	case tree.SidebarWidth == 0:
		// An agterm older than 0.26 reports neither field, and that is a different fact from hidden.
		sidebar = "this agterm reports nothing about the sidebar"
	}
	if s.Split {
		return fmt.Sprintf("%s, and THIS SESSION IS STILL SHOWING TWO PANES - the maximize did not "+
			"take, so the width below is not the width he is reading", sidebar)
	}
	return sidebar
}

// intentOf turns the wire's two booleans into the closed set the resize package takes.
//
// # The pair that must not resolve
//
// `recalibrate` and `cached_only` together say *measure this freshly, but only if you already measured
// it.* There is no reading of that which is what somebody meant. **Refused rather than resolved**: a
// boundary that picked one would be inventing an intention, and every silent default in this feature's
// history has ended up in a requirement document.
//
// It cannot arise from this app's own phone, which sends one or neither. It is refused because
// "our client would not do that" is not a property anybody is holding.
func intentOf(req Request) (resize.Intent, error) {
	switch {
	case req.Recalibrate && req.CachedOnly:
		return 0, errors.New("a request cannot ask to measure again and to measure nothing")
	case req.Recalibrate:
		return resize.IntentRecalibrate, nil
	case req.CachedOnly:
		return resize.IntentApplyIfKnown, nil
	default:
		return resize.IntentApply, nil
	}
}

// columnsThatStrictlyFit is the largest column count whose text is NARROWER than the box.
//
// # Strictly narrower, never equal, and the two are not the same thing
//
// **The owner's report, on the first build of this that worked:** horizontal scroll, because one
// width unit was surplus for the phone screen. One column too many, so every line overflowed and
// the phone panned - which is the entire complaint this feature exists to remove.
//
// Measured on a Pixel 9 Pro XL: the box holds 440dp and a character is exactly 11.0dp, so a floor
// division gives 40 and 40 x 11.0 is EXACTLY 440. The last glyph's right edge lands precisely on the
// boundary, and any sub-pixel difference in how the terminal lays it out tips into a scrollbar.
//
// **`floor` admits the exact-fill case, and exact fill is not a fit - it is the boundary, and the
// boundary fails toward scrolling.** "It exactly fits" reads as correct to anyone who has not seen the
// scroll, which is why this is written out rather than left as a `-1` someone would later tidy away.
//
// # The asymmetry, which is the rule and not the correction
//
// One column FEWER costs a sliver of unused space nobody notices. One column MORE costs a horizontal
// scroll on every single line. **They are not equally wrong and must never be treated as symmetric
// error.** Anything that tunes this must keep the inequality strict and keep it pointing this way.
func columnsThatStrictlyFit(boxWidthDp, characterWidthMilliDp int) int {
	// Subtracting one thousandth before dividing is what makes an exact multiple come out one lower,
	// while changing nothing for a box that was never an exact multiple.
	return (boxWidthDp*1000 - 1) / characterWidthMilliDp
}

// boolPtr exists so a false can be SENT. See the note on Response.FitEnabled.
func boolPtr(b bool) *bool { return &b }

func (h *Handler) saveStore() {
	if h.stateDir == "" {
		return
	}
	// Failure to persist is ignored: what this save carries is the calibration, which is an
	// optimisation - missing it costs two probe resizes next time. Refusing to work because a cache
	// file could not be written would be worse. It makes no claim about the restore point, which was
	// already written by resize.To before the window moved.
	_ = h.store.Save(h.stateDir)
}

// CloseStrayCalibrationSession removes a calibration session a previous run left behind.
//
// Called at startup, beside RestorePending and for the same reason: if the bridge died mid-calibration
// the session is real, it is in the owner's sidebar, and nothing on the phone would ever show it. The
// id comes from a file this package wrote before the first resize - never from tree, never off the
// wire - which is what keeps session.close pointed only at something we made.
func (h *Handler) CloseStrayCalibrationSession(ctx context.Context) error {
	if h.stateDir == "" {
		return nil
	}
	if err := resize.CloseStraySession(ctx, terminal{client: h.client}, h.stateDir); err != nil {
		return fmt.Errorf("closing a calibration session a previous run left behind: %w", err)
	}
	return nil
}

// RestorePending puts the owner's window back if a previous run left it resized.
//
// Called at startup. **The one thing that cannot be left to the phone**: if the bridge dies mid-
// session, the pending restore is on disk and the phone may never reconnect to trigger it, so the
// owner is left with a narrow window and nothing to explain it.
func (h *Handler) RestorePending(ctx context.Context) error {
	if h.store == nil || h.store.Pending == nil {
		return nil
	}
	if err := resize.RestoreWindow(ctx, terminal{client: h.client}, h.store); err != nil {
		// The record survives a failed restore - see RestoreWindow - so the owner's off press can
		// still perform it once agterm is up. The setting is left alone for the same reason: the
		// window is still narrowed, so a flag saying it is fitted remains true.
		return fmt.Errorf("restoring the window a previous run resized: %w", err)
	}

	// **The window is back, so the setting must say so.** Without this the store claimed a fit was in
	// force for a window that had already been put back: every reply carried `fit_enabled: true` with
	// a column count, the phone's toggle read ON for a window nobody had narrowed, and the owner's
	// only way out was a press that moved nothing.
	//
	// Reached by an ordinary sequence, not a crash - press fit on, then any deploy, because the reload
	// agent kickstarts the bridge whenever main moves. Observed twice on 2026-07-30.
	h.store.Active = nil
	// Republished under the state lock, so the flag riding on every reply matches the store this
	// function just changed. Startup, so nothing is serving yet - but publishing here rather than
	// relying on that keeps the rule "Active never changes without republishing" true everywhere.
	h.publishFit()
	h.saveStore()
	return nil
}
