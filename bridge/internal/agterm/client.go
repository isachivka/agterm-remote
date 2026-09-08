// Package agterm speaks agterm's control socket, and is the only thing in the bridge that does.
//
// Two properties of that socket shape everything here, both verified against agterm v0.18.0 and
// measured on the owner's machine:
//
//   - **One request per connection.** agterm reads a line, dispatches it, writes a response, and
//     closes (ControlServer.swift:236). There is no connection to pool and no session to keep.
//   - **A single serial accept loop**, with each request blocking agterm's main actor. Concurrency
//     here buys nothing and costs: eight sequential reads measured 3.5 ms, the same eight issued
//     concurrently measured 8.9 ms. Fanning out is 2.5x slower AND competes with the owner's own
//     agtermctl.
//
// So the client serialises. One mutex, one request at a time, no pool, no goroutine per call. That is
// not a simplification to revisit later — it is the shape the far end has.
package agterm

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// DefaultSocketPath is where agterm binds unless AGTERM_CONTROL_SOCKET or AGTERM_STATE_DIR say
// otherwise. The socket is mode 0600 and owned by the logged-in user, which is why running as the
// logged-in user is forced rather than preferred: a service account cannot read it.
func DefaultSocketPath() string {
	if p := os.Getenv("AGTERM_CONTROL_SOCKET"); p != "" {
		return p
	}
	if d := os.Getenv("AGTERM_STATE_DIR"); d != "" {
		return filepath.Join(d, "agterm.sock")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "agterm.sock"
	}
	return filepath.Join(home, "Library", "Application Support", "agterm", "agterm.sock")
}

// deadline bounds one whole request. agterm abandons a read at 10 s and a write at 10 s, so this sits
// INSIDE both: the bridge gives up first and reports a clean failure rather than inheriting a
// half-state it cannot describe.
const deadline = 8 * time.Second

// maxResponseBytes caps one response line. agterm caps REQUESTS at 1 MiB but leaves responses
// uncapped — a `session.text --all` reply can be multi-MB. The bridge never sends `all`, so a bounded
// `lines` read is far under this; the cap exists so a malfunctioning or replaced far end cannot make
// the bridge allocate without limit.
const maxResponseBytes = 8 << 20

// ErrUnavailable means agterm did not answer: not running, socket stale, or the app wedged.
//
// Distinguished from a command error on purpose: when the laptop is not answering the
// app says so and does not invent a network diagnosis. That copy is only possible if this layer keeps
// "agterm said no" and "agterm said nothing" apart.
var ErrUnavailable = errors.New("agterm is not answering")

// Client is a serialised connection factory for agterm's control socket. The zero value is not
// usable; use New.
type Client struct {
	path string
	mu   sync.Mutex
}

func New(socketPath string) *Client { return &Client{path: socketPath} }

// request is the wire shape. `target` is a TOP-LEVEL field, not an args key — putting it in args
// makes agterm silently ignore it and fall back to the ACTIVE session, which returns a plausible
// answer about the wrong thing. That cost two invalid measurements before it was noticed, and it is
// noted here because the failure is silent and the response still says ok.
type request struct {
	Cmd    string `json:"cmd"`
	Target string `json:"target,omitempty"`
	Args   *args  `json:"args,omitempty"`
}

// Pane is which half of a split session a call addresses, as a closed set.
//
// # Why this is a type and not a string
//
// The same reasoning as `keys.Key`: a bare string in this position is a value the far end will refuse,
// four layers away from anyone who can read the refusal. Here it is worse than that, because agterm
// does NOT refuse an absent pane — it picks one, and **`session.text` and `session.type` pick
// DIFFERENT ones.** Measured 2026-08-25 over this very socket: with no pane, a read returns the
// on-screen pane and a type lands in primary.
//
// So on a split session the phone was showing one pane and typing into the other, silently, into the
// terminal the owner was not looking at. A missing value that defaults is worse than one that errors,
// and this type is how the value stops being missable.
//
// `left` and `right` are agterm's own words for it — `--pane` accepts them, and the tree's surfaces
// carry `kind: left|right` — so nothing here translates a vocabulary.
type Pane string

const (
	// PaneLeft is agterm's `primary`/`left`/`top`: the pane every session has.
	PaneLeft Pane = "left"
	// PaneRight is agterm's `split`/`right`/`bottom`, and exists only while a split does.
	PaneRight Pane = "right"
)

// Valid reports whether this is a pane agterm will accept. Nothing may reach the socket without it.
func (p Pane) Valid() bool { return p == PaneLeft || p == PaneRight }

// ErrNoPane is returned rather than sending a request with no pane on it. **Not a default**: the whole
// defect this type exists for was a value that quietly became one thing here and another thing there.
var ErrNoPane = errors.New("no pane named for a call that must address one")

type args struct {
	Lines int `json:"lines,omitempty"`
	// Pane is which half of a split to address, and it is sent on EVERY session.text, session.type and
	// session.focus.
	//
	// **Never omitted**, which is why the callers refuse an invalid Pane rather than letting one
	// through: an absent pane is not "the obvious one", it is two different obvious ones. On
	// `session.focus` that is not a figure of speech: the documented default is `other`, a TOGGLE, and
	// an unrecognised argument name falls back to it. Measured 2026-08-29 — `position`, `role` and
	// `target_pane` all answered `ok:true` and all three toggled. **The reply cannot tell you the name
	// was wrong**; only sending the same value twice can.
	Pane string `json:"pane,omitempty"`
	// Window geometry, in points. Only `window.resize` sends these, and only the resize path
	// constructs them - the bridge is permitted exactly one write of this kind and this is it.
	Width  int `json:"width,omitempty"`
	Height int `json:"height,omitempty"`
	// Text is the keystroke bytes for `session.type`, and it is the only field in this struct whose
	// value originates with the caller. It arrives already checked by internal/keys - a named key
	// from a closed set of literals, or text proven to hold no control character - because the
	// checking belongs where it can be tested without a Mac, and because the check must exist in one
	// place rather than at each call site.
	Text string `json:"text,omitempty"`
	// Command and Name belong to session.new alone, and only the calibration path builds them.
	//
	// Command is argv-only and runs with the app's GUI PATH, so a non-default binary needs an
	// absolute path or a `zsh -lc` wrapper - otherwise it exits 127 and the session sits there dead.
	Command string `json:"command,omitempty"`
	// Name is the label for session.new, session.rename and workspace.rename. **It originates with the
	// owner** on the two rename paths, and it arrives already checked by keys.Label - non-empty after
	// trimming, at most 64 runes, no control character - for the same reason Text does: the checking
	// belongs where it can be tested without a Mac, and it must exist in one place rather than at each
	// call site.
	Name string `json:"name,omitempty"`
	// Mode is `session.split`'s on/off/toggle, and **the bridge sends `on` and `off`, never `toggle`**
	// — see [Client.OpenSplitPane], [Client.MaximizePane] and [splitModeOn].
	//
	// **Never omitted on that call, and the reason is measured.** `session.split` with no args at all
	// is a TOGGLE: sent to a session showing both panes it collapses them. So an omitted mode is not
	// "the obvious one", it is the opposite one half the time — the same failure shape as the absent
	// pane two fields above, which is why this is spelled out rather than left to a default.
	Mode string `json:"mode,omitempty"`
	// Workspace addresses the workspace a new session goes into. An id, never a name.
	//
	// **agterm offers a `workspaceName` + `createWorkspace` pair that this deliberately does not use.**
	// That form is idempotent BY NAME: it reuses an existing workspace whose label matches instead of
	// creating one. A collision would file the owner's new session into somebody else's group, which is
	// re-targeting - and it would require the bridge to invent a name, the one thing the owner ruled
	// out. An id cannot collide with anything.
	Workspace string `json:"workspace,omitempty"`
	// Window scopes `tree` and `sidebar.width` to one window. **In args, not in target**:
	// read off agtermctl itself on 2026-09-05, `--window W` becomes `"args":{"window":"W"}`. Omitted,
	// both commands act on the frontmost window, which is not necessarily the fit's window.
	Window string `json:"window,omitempty"`
	// SidebarWidth is `sidebar.width`'s one argument, in points, as a DOUBLE. The drag writes a
	// fractional cursor x, so an int could not express every width the sidebar can reach, and
	// agterm's author said so when he added the command (discussion #511).
	SidebarWidth float64 `json:"sidebarWidth,omitempty"`
}

type response struct {
	OK     bool    `json:"ok"`
	Error  string  `json:"error,omitempty"`
	Result *result `json:"result,omitempty"`
}

type result struct {
	// ID is what session.new answers with: the id of the session it just made. **It is the only
	// session id in this package that the bridge is allowed to close.**
	ID string `json:"id,omitempty"`
	// **There is no Name here, and that was established the expensive way.**
	//
	// A `name` field was added on 2026-07-31 in the belief that a create answers with the default label
	// agterm chose, so the phone's rename dialog could open on it. It does not. agterm's result object
	// carries exactly one of `id`, `text`, `exitCode`, `count`, `tree` or `windows` - which the
	// reference says in as many words, and which the live test caught on its first run against the real
	// socket while every fake-backed test stayed green.
	//
	// The mistake underneath it is worth more than the field: the name HAD been measured, but from the
	// tree after creating rather than from the create's own reply, and the client was then written as
	// though the two were the same observation.
	//
	// The phone reads the default name from the listing it refreshes anyway. That is one source rather
	// than two, and it cannot disagree with what the sidebar shows.
	Text    *string  `json:"text,omitempty"`
	Tree    *Tree    `json:"tree,omitempty"`
	Windows []Window `json:"windows,omitempty"`
	// SidebarWidth is what `sidebar.width` answers: the width AFTER clamping to 160...560 points.
	// A pointer, because 0 is not a width the sidebar can have and an absent echo must not read as one.
	SidebarWidth *float64 `json:"sidebarWidth,omitempty"`
	// Zmx is what `zmx.list` answers: where the bundled zmx and its sockets are, and which daemon
	// each pane claims. Read by the styled screen path only.
	Zmx *zmxResult `json:"zmx,omitempty"`
}

// zmxResult is the `zmx` object of a `zmx.list` reply, as agterm 0.26 shapes it. Only the parts the
// bridge reads are named; `restore`, `inventoryComplete` and the per-row window and workspace
// fields are left to the decoder to skip.
type zmxResult struct {
	Endpoint struct {
		Executable      string `json:"executable"`
		SocketDirectory string `json:"socketDirectory"`
	} `json:"endpoint"`
	Entries []ZmxEntry `json:"entries"`
}

// ZmxList is the bridge's view of `zmx.list`: the zmx binary agterm bundles, the socket directory
// its daemons live in, and one entry per pane that claims a daemon.
type ZmxList struct {
	Executable string
	SocketDir  string
	Entries    []ZmxEntry
}

// ZmxEntry is one pane's claim on a daemon.
type ZmxEntry struct {
	SessionID string `json:"sessionID"`
	// Pane is "left" or "right" — the same vocabulary the phone addresses reads with.
	Pane   string `json:"pane"`
	Daemon string `json:"daemon"`
	// Observation is what zmx itself reported when agterm took the inventory: "running" when the
	// daemon answered, "absent" when the pane claims one that is not there.
	Observation string `json:"observation"`
}

// Window is one agterm window, as `window.list` reports it.
//
// **Geometry here is what the window SAYS, and it is not a record of what was applied.** Measured
// 2026-07-29: in full screen a window reported width 640 after 500 had been applied, and `zoomed`
// flipped from true to false while `fullscreen` stayed true. It is read for one purpose only - to
// remember what to put back - and nothing derives a column count from it.
type Window struct {
	ID         string   `json:"id"`
	Active     bool     `json:"active"`
	Fullscreen bool     `json:"fullscreen"`
	Zoomed     bool     `json:"zoomed"`
	Geometry   Geometry `json:"geometry"`
}

// Geometry is a window's frame in points, plus which display it is on.
//
// [Display] is the cache key for the pixels-to-columns relation: the same pixels are a different
// number of columns on a different screen, so a cache without it is wrong the moment the laptop is
// plugged into a monitor.
type Geometry struct {
	Display int `json:"display"`
	X       int `json:"x"`
	Y       int `json:"y"`
	Width   int `json:"width"`
	Height  int `json:"height"`
}

// call sends one request and reads one response, holding the mutex for the whole round trip.
func (c *Client) call(ctx context.Context, req request) (*result, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	var d net.Dialer
	ctx, cancel := context.WithTimeout(ctx, deadline)
	defer cancel()

	conn, err := d.DialContext(ctx, "unix", c.path)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	defer conn.Close()
	if dl, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(dl)
	}

	line, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}
	if _, err := conn.Write(append(line, '\n')); err != nil {
		return nil, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}

	raw, err := readLine(conn, maxResponseBytes)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	var resp response
	if err := json.Unmarshal(raw, &resp); err != nil {
		return nil, fmt.Errorf("%w: malformed response", ErrUnavailable)
	}
	if !resp.OK {
		// agterm's own error text. Passed through because it is descriptive and carries no secret —
		// "session not realized", "no such session". It is never text the remote caller supplied.
		return nil, errors.New(resp.Error)
	}
	if resp.Result == nil {
		return &result{}, nil
	}
	return resp.Result, nil
}

// readLine reads to the first newline, refusing to grow past max.
func readLine(conn net.Conn, max int) ([]byte, error) {
	buf := make([]byte, 0, 4096)
	chunk := make([]byte, 4096)
	for {
		n, err := conn.Read(chunk)
		if n > 0 {
			for i := 0; i < n; i++ {
				if chunk[i] == '\n' {
					return append(buf, chunk[:i]...), nil
				}
			}
			buf = append(buf, chunk[:n]...)
			if len(buf) > max {
				return nil, errors.New("response too large")
			}
		}
		if err != nil {
			if len(buf) > 0 {
				return buf, nil // EOF with a trailing line and no newline
			}
			return nil, err
		}
	}
}

// Tree is the subset of agterm's `tree` response the bridge reads. Everything else agterm returns —
// surfaces, splits, overlays, watermarks, restore commands, geometry — is deliberately not decoded.
// The bridge does not forward what it does not understand.
type Tree struct {
	Workspaces []Workspace `json:"workspaces"`
	// SidebarWidth and SidebarVisible describe the sidebar of the window the tree was read for -
	// agterm 0.26, discussion #511. **This is the read-back**: `window.list` does not carry the width,
	// so a specific window's sidebar is read with [Client.TreeOf]. Zero means the host predates the
	// field.
	SidebarWidth   float64 `json:"sidebarWidth"`
	SidebarVisible bool    `json:"sidebarVisible"`
}

type Workspace struct {
	ID       string    `json:"id"`
	Name     string    `json:"name"`
	Active   bool      `json:"active"`
	Sessions []Session `json:"sessions"`
}

type Session struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Title string `json:"title"`
	// Surfaces is the session's panes, and it is the one field the narrowing above was widened for.
	//
	// **Not an oversight being corrected.** Dropping surfaces, splits, overlays, watermarks and
	// geometry was a deliberate rule: publish what a feature needs and nothing that merely happens to
	// be available. What changed is that a feature now needs this one. The others stay out.
	//
	// ### Why not agterm's `split`, which is right here and is a bool
	//
	// Because it answers a different question. Measured over this socket on 2026-08-25:
	//
	//	state                     split   surfaces
	//	no second pane ever        false   [left]
	//	second pane, collapsed     false   [left right]
	//	second pane, on screen     true    [left right]
	//
	// `split` means BOTH PANES ARE VISIBLE. It cannot tell a session that has no second pane from one
	// whose second pane is merely collapsed on the Mac — and the phone can read a collapsed pane
	// perfectly, so those two states must not look alike. See [Session.HasSplitPane].
	Surfaces []Surface `json:"surfaces"`
	// Split is agterm's own `isSplit`: **both panes are SHOWN**. This field was once removed
	// because it was the wrong thing to DECIDE on, and is back because it is the right thing to
	// REPORT. The two are not in tension.
	//
	// ### The distinction the whole diagnosis turns on
	//
	// agterm keeps them apart and this bridge had been conflating them:
	//
	//	isSplit  - shown as a split, both panes on screen
	//	hasSplit - has a split pane at all, including one hidden or squeezed out
	//
	// [Session.HasSplitPane] answers the second. This answers the first. A probe whose window is
	// driven down to the 640-point floor may keep its second pane while ceasing to draw it, and every
	// number logged while this field was absent was blind to the difference — which is why two
	// calibration failures on the owner's machine could not be told apart.
	//
	// **Nothing decides on this.** It is read for the calibration log and for nothing else, and the
	// gate that was moved off it stays off it.
	Split  bool `json:"split"`
	Active bool `json:"active"`
	// Status is what agterm's agent hooks last said about this session, and it is **absent when the
	// session is idle** — the encoder omits the first case rather than writing it.
	//
	// # The four values, and how they were established
	//
	// A live tree over 32 sessions on the owner's machine showed `status` absent on 28, `active` on 3
	// and `completed` on 1. `blocked` did not occur in that sample, **and a sample that lacks a value
	// is not evidence the value does not exist** — that inference is what broke the fit on their
	// machine on 2026-07-31 and it is not being repeated here.
	//
	// So the positive control, from agterm's own binary rather than from our reading of a reply: the
	// enum `idle · active · completed · blocked` sits in the string table beside `statusPane` and
	// `statusBlink`, and again in the shell-integration hook block that WRITES it — `agterm
	// agent-status`, `UserPromptSubmit`, `PermissionRequest`. Four cases, `idle` first, and `idle` is
	// the one that never appears on the wire.
	//
	// That also fixes the MEANING, which matters more than the spelling: the agent's own hooks set
	// this, so `blocked` is *this session is waiting on the person*. It is not an inference anyone
	// here is making about a session.
	//
	// omitempty is right on THIS struct and wrong on api.Session — see the note there. Here it merely
	// describes how agterm encodes; there it would destroy a state.
	Status string `json:"status,omitempty"`
	// FontSize is this session's terminal font in points. It moves the CELL, so a fit measured at one
	// size does not hold at another - see the detector in internal/resize.
	FontSize int `json:"fontSize,omitempty"`
}

// Surface is one pane of a session. Two fields are decoded; the id and the geometry are agterm's
// business and nothing here needs them.
type Surface struct {
	// Kind is the pane's ROLE, and the vocabulary is wider than the two panes this bridge addresses:
	// measured on 2026-08-25, a session can carry `left`, `right`, `scratch` and `overlay`.
	//
	// **Roles, not geometry.** A horizontally split session still reports `left` and `right` — the
	// same words a vertical one uses — so one predicate covers both axes and nothing here has to know
	// which way the divider runs.
	Kind string `json:"kind"`
	// Visible is whether this pane is currently ON SCREEN, and it is read for the fit LOG alone —
	// No decision anywhere depends on it.
	//
	// **Existence and visibility are different questions and this is the one that is not asked.**
	// [Session.HasSplitPane] deliberately ignores it: a pane collapsed on the Mac still reads
	// perfectly from the phone, which is the whole of that distinction. What this adds is the
	// ability for the log to say which of the two states the owner was in when a fit went wrong,
	// because "one pane" and "two panes, one hidden" produce very different column counts and
	// looked identical in the record.
	Visible bool `json:"visible"`
}

// HasSplitPane reports whether a second pane EXISTS — collapsed on the Mac or on screen, either way.
//
// # Why this is not len(Surfaces) > 1
//
// Because `scratch` and `overlay` are surfaces too. Measured 2026-08-25: turning a scratch terminal
// on takes a session with no split from one surface to two, and **turning it off leaves it at two** —
// the surface stays in the tree. A count would have offered the phone a pane toggle on a session with
// no second pane, and every press would have come back `session has no split pane`.
//
// So the question is asked of the ROLE, which is the only field that answers it: is one of these the
// right-hand pane. [PaneRight] supplies the word so the wire vocabulary is written once.
func (s Session) HasSplitPane() bool {
	for _, surface := range s.Surfaces {
		if surface.Kind == string(PaneRight) {
			return true
		}
	}
	return false
}

// Tree returns the workspace and session list.
func (c *Client) Tree(ctx context.Context) (*Tree, error) {
	res, err := c.call(ctx, request{Cmd: "tree"})
	if err != nil {
		return nil, err
	}
	if res.Tree == nil {
		return nil, fmt.Errorf("%w: tree missing from response", ErrUnavailable)
	}
	return res.Tree, nil
}

// TreeOf is [Client.Tree] scoped to one window, which is the only way to read THAT window's sidebar:
// the top-level sidebar fields describe whichever window the tree was asked for, and asking for none
// describes the frontmost.
func (c *Client) TreeOf(ctx context.Context, windowID string) (*Tree, error) {
	res, err := c.call(ctx, request{Cmd: "tree", Args: &args{Window: windowID}})
	if err != nil {
		return nil, err
	}
	if res.Tree == nil {
		return nil, fmt.Errorf("%w: tree missing from response", ErrUnavailable)
	}
	return res.Tree, nil
}

// SetSidebarWidth sets one window's sidebar width in points and returns the width agterm applied.
//
// # Geometry, like window.resize, and for the same reason it is permitted
//
// A window cannot go below 640 points, so with a narrow sidebar the fit could not reach a small column
// count at all (discussion #511: 47 columns was the narrowest, and the phone wanted 45). The terminal
// area is the window minus the sidebar, so when the window cannot get narrower the sidebar gets wider.
// This changes the SHAPE of the owner's terminal and carries nothing into it.
//
// **The echo is the clamp.** agterm clamps to the same 160...560 points as dragging and answers ok
// either way; the applied width in the reply is the only way to tell. Compare it numerically.
func (c *Client) SetSidebarWidth(ctx context.Context, windowID string, points float64) (float64, error) {
	res, err := c.call(ctx, request{
		Cmd:  "sidebar.width",
		Args: &args{SidebarWidth: points, Window: windowID},
	})
	if err != nil {
		return 0, err
	}
	if res.SidebarWidth == nil {
		return 0, fmt.Errorf("%w: sidebar.width answered ok without the applied width", ErrUnavailable)
	}
	return *res.SidebarWidth, nil
}

// Text returns the last `lines` lines of a session's buffer.
//
// `lines` is always sent and never omitted, because the phone decides how much it is willing to pull
// over a metered link and a default is not that decision.
//
// **The reason this comment used to give was wrong, and it is corrected rather than deleted.** It
// claimed agterm's default reads only the visible screen, so that a default read of a live Claude
// session "comes back nearly blank". Measured on 2026-07-31: a default read of a live Claude session
// returned 36 non-blank lines, identical to the full read. The default is not the narrow thing that
// paragraph described.
//
// It is left here as a correction because the claim was load-bearing — it was the stated reason
// `lines` is mandatory — and a future reader who found it false would reasonably conclude the
// mandatory argument was pointless too. It is not: the bound is the caller's to choose, and that
// reason survives the measurement that killed the other one.
//
// This does NOT select the session. agterm's readSessionText resolves the target and reads its
// surface; it never calls selectSession, unlike session.search on the adjacent line. That is what
// makes a read-only bridge genuinely read-only, and why the verb allowlist names commands
// individually rather than by whether they look harmless.
func (c *Client) Text(ctx context.Context, sessionID string, lines int, pane Pane) (string, error) {
	if !pane.Valid() {
		return "", ErrNoPane
	}
	res, err := c.call(ctx, request{
		Cmd:    "session.text",
		Target: sessionID,
		Args:   &args{Lines: lines, Pane: string(pane)},
	})
	if err != nil {
		return "", err
	}
	if res.Text == nil {
		// A genuinely blank screen reads ok with an empty string, so a nil text is a real absence.
		return "", nil
	}
	return *res.Text, nil
}

// ZmxList reads the daemon inventory behind agterm's Live sessions.
//
// Read-only: agterm joins its live panes against what zmx reports and answers; nothing is attached,
// pruned or killed. It is the one thing the styled screen path needs from agterm — the daemon name a
// pane maps to, and where to find zmx — and it is asked for on every styled read rather than cached,
// because a daemon can vanish between two polls and a stale name would read the wrong pane's past.
func (c *Client) ZmxList(ctx context.Context) (*ZmxList, error) {
	res, err := c.call(ctx, request{Cmd: "zmx.list"})
	if err != nil {
		return nil, err
	}
	if res.Zmx == nil {
		return nil, errors.New("zmx.list answered without a zmx object")
	}
	return &ZmxList{
		Executable: res.Zmx.Endpoint.Executable,
		SocketDir:  res.Zmx.Endpoint.SocketDirectory,
		Entries:    res.Zmx.Entries,
	}, nil
}

// Windows lists agterm's windows, so the resize path can remember what to restore.
//
// Read-only. It is here rather than in the resize package because this file is the only thing in the
// bridge that speaks agterm's socket, and that stays true.
func (c *Client) Windows(ctx context.Context) ([]Window, error) {
	res, err := c.call(ctx, request{Cmd: "window.list"})
	if err != nil {
		return nil, err
	}
	return res.Windows, nil
}

// ResizeWindow sets a window's frame, in points.
//
// **This is the one write this binary performs**, authorised by the owner on 2026-07-29. It changes
// the SHAPE of the owner's terminal and can carry nothing into it: no keystroke, no control
// character, no command. The command name is a literal here and is never assembled from anything a
// caller sent - the caller supplies a column count, and this package turns that into points.
// Type sends keystrokes to a session.
//
// **This is the second write this binary performs, and it is a different KIND from the first.**
// window.resize changes the shape of a container and can carry nothing into it. This puts bytes on a
// pty, which is to say it runs commands on the owner's laptop. The original ruling for this bridge
// named session.type as the example of what must never appear here; the owner reversed that on
// 2026-07-29 and the allowlist entry records it as their decision rather than as an inevitability.
//
// `text` is bytes, already validated by internal/keys. Nothing here inspects or transforms them: a
// second check in a second place is a second thing to get wrong, and the one that exists is pure and
// tested. What this file guarantees is only that the COMMAND is a literal, as it is for every other.
func (c *Client) Type(ctx context.Context, sessionID, text string, pane Pane) error {
	if !pane.Valid() {
		return ErrNoPane
	}
	_, err := c.call(ctx, request{
		Cmd:    "session.type",
		Target: sessionID,
		Args:   &args{Text: text, Pane: string(pane)},
	})
	return err
}

// ZoomWindow toggles a window's zoom. **A TOGGLE, not a setter** - agterm offers no "set zoom to
// false", so the caller must read the current state and call this only when it differs. Calling it
// blindly is how a restore turns into the second half of the bug it was fixing.
//
// Geometry only, exactly like ResizeWindow: it cannot deliver a keystroke, a control character or a
// command. It exists because zoom CHANGES UNDERNEATH A RESIZE - measured 2026-07-29 and again on
// 07-30 - so a restore that puts the frame back without the zoom leaves the owner's window in a state
// they did not choose, which is what RestoreWindow's comment claimed to prevent while not doing it.
func (c *Client) ZoomWindow(ctx context.Context, id string) error {
	_, err := c.call(ctx, request{Cmd: "window.zoom", Target: id})
	return err
}

// ResizeWindow sets a window's frame. **agterm requires BOTH a positive width and a positive height** -
// `window.resize requires positive width and height` - so a width-only request is not expressible at
// this protocol, whatever we would prefer.
//
// That was learned the hard way on 2026-07-31. The height parameter was deleted here on the strength
// of a measurement that was WRONG: a probe read `width` and `height` at the top level of window.list,
// where the geometry is actually nested under `geometry`, so a perfectly ordinary window looked like
// one reporting no size. Every resize then failed and the owner's fit stopped working entirely.
//
// **So the height is sent, and the caller's job is to send back the height the window ALREADY HAS,
// read immediately before the call.** See currentGeometry in internal/resize: a height read fresh and
// handed straight back is a no-op by construction, while a height captured at the start of a
// multi-step operation pins the window to whatever it was when we looked - which is the thing the
// owner actually complained about.
func (c *Client) ResizeWindow(ctx context.Context, id string, width, height int) error {
	_, err := c.call(ctx, request{
		Cmd:    "window.resize",
		Target: id,
		Args:   &args{Width: width, Height: height},
	})
	return err
}

// NewSession creates a session running `command`, and returns the id agterm assigns it.
//
// # This exists for calibration and nothing else
//
// Measuring the terminal's width against whatever happens to be in the owner's working session was
// the defect at the root of three failed attempts at the width feature: the content moves, so the
// measurement moves. The owner's ruling on 2026-07-30 was to stop doing that - calibration gets a
// session of its own, made over the socket - and to accept a second or two of waiting in exchange.
//
// `command` means the calibration never needs session.type. The session prints what it prints because
// of what it was started as, so the input verb stays out of this path entirely.
//
// **The returned id is the only id CloseSession may ever be given.** Hold it in a variable and close
// it in a defer; do not look it up again, and never take it from Tree.
func (c *Client) NewSession(ctx context.Context, command, name string) (string, error) {
	res, err := c.call(ctx, request{
		Cmd:  "session.new",
		Args: &args{Command: command, Name: name},
	})
	if err != nil {
		return "", err
	}
	if res.ID == "" {
		return "", fmt.Errorf("%w: session.new answered without an id", ErrUnavailable)
	}
	return res.ID, nil
}

// CloseSession closes one session. **It destroys the owner's work and there is no undo.**
//
// # Two callers, two different rules, and neither one is "anybody with an id"
//
// This used to say *only ever an id this bridge got back from [NewSession]*. That was the whole
// safety argument until 2026-07-31, when the owner asked for a button that closes a session or a
// workspace from their phone. So the rule is now stated per caller, because the two are not the
// same and collapsing them would lose what protects each.
//
//   - **Calibration.** Unchanged, and it does not relax by one word: it closes only the session it
//     made, in the same operation, held in a variable. `close(created)` is the feature;
//     `close(id)` where id came from Tree is the bridge deleting their work.
//
//   - **The owner's own press.** A full UUID that arrived over the wire, for a row the phone drew and
//     the owner long-pressed and then pressed Delete in. **Provenance is not what makes this safe** -
//     the id is theirs, not ours - the owner's deliberate gesture is, and this package's part is to
//     refuse anything that is not a canonical UUID so a partial target cannot resolve to `active` and
//     close whatever they are working in.
//
// What is still true: nothing may close a session the phone did not draw as a row the owner pressed,
// and no path here closes anything on its own initiative.
func (c *Client) CloseSession(ctx context.Context, id string) error {
	_, err := c.call(ctx, request{Cmd: "session.close", Target: id})
	return err
}

// The two values of `mode` this bridge sends. **`toggle` is agterm's and stays agterm's**: a toggle
// sent over a link with a 2-second poll behind it is a coin flip about a state that may have changed
// since it was read.
//
// `off` joined `on` later, and the sentence it replaced said hiding a pane was something
// "nothing the phone does should" do. That was never the owner's rule — see the requirement — and
// hiding one is now how a pane is shown at full width.
const (
	splitModeOn  = "on"
	splitModeOff = "off"
)

// OpenSplitPane makes sure the session has a right-hand pane and that it is on screen.
//
// # One verb, two effects, and agterm decides which
//
// Measured over the control socket on 2026-08-25, on throwaway sessions created and closed for the
// purpose:
//
//   - **no right pane has ever existed** — it CREATES one, running a login shell. A new process on
//     the owner's Mac, which is why nothing else in this package calls this and why the phone's
//     single tap is the only thing that reaches it.
//   - **a right pane exists but is collapsed** — it REVEALS that same pane. Same surface id, and a
//     marker typed into its shell survived, so nothing respawns.
//   - **a right pane is already on screen** — nothing. Same surface id, still two.
//
// So the caller does not choose between creating and revealing, and cannot: the two are
// indistinguishable in the reply, which carries only the session id either way. What the caller can
// know beforehand is [Session.HasSplitPane], which is exactly the distinction this client reports.
//
// # `mode` is sent explicitly and that is not decoration
//
// Measured the same day: `session.split` with no args is a TOGGLE. Sent twice to a session with no
// split it would create a pane and then collapse it. `on` sent twice is a no-op, which is what a
// button the owner may press twice requires.
func (c *Client) OpenSplitPane(ctx context.Context, id string) error {
	_, err := c.call(ctx, request{Cmd: "session.split", Target: id, Args: &args{Mode: splitModeOn}})
	return err
}

// MaximizePane shows one pane at the full width of the terminal area.
//
// # Two calls, and the order is the whole of it
//
// Focus the pane, then hide the split. Measured 2026-08-29 on throwaway sessions created with
// `noSelect` and closed afterwards:
//
//	focus left  + split off  ->  left visible,  right hidden
//	focus right + split off  ->  left hidden,   right visible
//
// Hiding first would collapse to whichever pane already had focus and then move focus into a pane
// nobody can see.
//
// # It refuses a session with no split, and the caller must not send one
//
// `session.focus` answers `ok:false, "session has no split"`. That is correct and this does not paper
// over it: a session with one pane is **already** showing that pane at full width, so there is nothing
// to do and a call that appeared to succeed would be reporting work it did not perform.
//
// # Why `off` is safe to send unconditionally
//
// Measured the same day, three times in a row: `split mode:off` on an already-collapsed session is a
// no-op. So this does not read the state first to decide whether to send it.
//
// The cheaper path exists and is deliberately not taken: on a session that is ALREADY collapsed, one
// `session.focus` swaps which pane shows, with no `off` needed. Skipping the second call would make
// this function's behaviour depend on a state read over a link with a poll behind it, to save one
// local socket round trip. **Two unconditional calls are a shape; one conditional call is a rule.**
func (c *Client) MaximizePane(ctx context.Context, id string, pane Pane) error {
	if !pane.Valid() {
		return ErrNoPane
	}
	if _, err := c.call(ctx, request{Cmd: "session.focus", Target: id, Args: &args{Pane: string(pane)}}); err != nil {
		return err
	}
	_, err := c.call(ctx, request{Cmd: "session.split", Target: id, Args: &args{Mode: splitModeOff}})
	return err
}

// DeleteWorkspace deletes one workspace **and every session inside it**.
//
// # The most destructive call in this binary, and the measurement that says so
//
// Measured against the live socket on 2026-07-31, on a workspace this work created holding two
// sessions it created: the delete succeeded, and **both sessions went with it**. agterm does not
// warn, does not refuse a non-empty workspace, and does not report what it took - it answers ok.
//
// That silence is why the phone's modal names the number of sessions going with it. agterm will not
// tell the owner, the list is behind the modal, and by the time they could count it is gone.
//
// **Not measured:** agterm's reference says the last workspace cannot be deleted and that trying
// errors. Establishing that would mean deleting every workspace the owner has, so it is taken from
// the reference rather than from observation, and the caller shows whatever sentence comes back
// instead of predicting one.
//
// Only ever a full UUID for a workspace the owner long-pressed. Never a prefix, never `active`, and
// never a workspace this bridge decided to tidy up. A half-created
// workspace is left for the owner to remove, because the delete verb exists for their hand, not ours.
func (c *Client) DeleteWorkspace(ctx context.Context, id string) error {
	_, err := c.call(ctx, request{Cmd: "workspace.delete", Target: id})
	return err
}

// NewWorkspace creates a workspace with agterm's own default name and returns its id and that name.
//
// # What it does on the owner's machine, measured rather than assumed
//
// On 2026-07-31, against the live socket: it returns an id, the workspace it makes holds **zero
// sessions**, and it does **not** move the owner's selection — their active workspace was unchanged
// across the call. That last fact is why this verb is cheaper than [NewSessionIn]: it creates state
// the owner asked for and re-targets nothing.
//
// The empty result is the reason api.Response publishes workspaces at all. The session list cannot
// express a workspace with no sessions, so without that field the owner would press + and be shown
// nothing.
//
// **No name is sent, and none comes back.** The owner's flow is create-then-rename, and a name
// invented here would be a label they did not choose sitting in their sidebar if the rename is
// cancelled. agterm answers with an id and nothing else - see [result].
func (c *Client) NewWorkspace(ctx context.Context) (string, error) {
	res, err := c.call(ctx, request{Cmd: "workspace.new"})
	if err != nil {
		return "", err
	}
	if res.ID == "" {
		return "", fmt.Errorf("%w: workspace.new answered without an id", ErrUnavailable)
	}
	return res.ID, nil
}

// NewSessionIn creates a session in one workspace, with agterm's default name, and returns both.
//
// # Why this is a second function and not a parameter on [NewSession]
//
// The two uses have opposite obligations and one function with a mode flag is how they get confused.
// [NewSession] belongs to calibration: it sends a command, and the session it makes is **ours**, held
// in a variable and closed in the same operation. This one sends no command, and the session it makes
// is **the owner's** — it is never closed by the bridge, and [CloseSession] must never be called with
// an id from here.
//
// Nothing about that distinction can be expressed in a type, so it is expressed in the call graph
// instead: two functions, and the one that creates the owner's sessions has no path to close.
//
// # It moves the owner's Mac, and that is measured
//
// agterm focuses what session.new creates, confirmed on 2026-07-31 — the new session came back
// `active`. There is no flag to suppress it. So pressing + on the phone changes what their laptop is
// showing, which is recorded here, in the allowlist, and in the PR body rather than being left for
// them to discover when the screen jumps. They accepted exactly this cost for calibration.
//
// **No command and no cwd are sent**, and the phone has no way to supply either. A command arriving
// from the wire is the thing the whole allowlist exists to prevent.
func (c *Client) NewSessionIn(ctx context.Context, workspaceID string) (string, error) {
	res, err := c.call(ctx, request{
		Cmd:  "session.new",
		Args: &args{Workspace: workspaceID},
	})
	if err != nil {
		return "", err
	}
	if res.ID == "" {
		return "", fmt.Errorf("%w: session.new answered without an id", ErrUnavailable)
	}
	return res.ID, nil
}

// RenameSession sets one session's sidebar label.
//
// The name must already have been through keys.Label. This does not re-check it: one checker, at the
// boundary where input arrives, rather than a second opinion here that could disagree with it.
//
// **The id matters more than the name.** An empty or partial target resolves to `active` on agterm's
// side, which would rename whatever the owner happens to be looking at — so the caller validates it as
// a canonical UUID first. That is the failure this verb actually has: not a bad label, a right label
// on the wrong thing.
func (c *Client) RenameSession(ctx context.Context, id, name string) error {
	_, err := c.call(ctx, request{
		Cmd:    "session.rename",
		Target: id,
		Args:   &args{Name: name},
	})
	return err
}

// RenameWorkspace sets one workspace's sidebar label. See [RenameSession] — same two rules, same
// reasons, and the `active` fallback is the same trap.
func (c *Client) RenameWorkspace(ctx context.Context, id, name string) error {
	_, err := c.call(ctx, request{
		Cmd:    "workspace.rename",
		Target: id,
		Args:   &args{Name: name},
	})
	return err
}

// session.select is DELIBERATELY ABSENT, and that is a decision rather than an oversight.
//
// It was ruled permitted for one purpose - session.new focuses what it creates, so calibration moves
// the owner's view, and restoring it looked like the courteous thing. The owner overruled that on
// 2026-07-30: the jump is fine.
//
// Their answer is strictly better than the design it replaced: less code, one fewer permission the
// bridge carries forever, and one fewer thing to go wrong on an error path. A narrow allowlist is the
// entire reason the allowlist mechanism exists, so widening it to solve a problem they did not have
// would have been paying its main cost for nothing.
//
// **Do not add it back thinking you are fixing an omission.**
