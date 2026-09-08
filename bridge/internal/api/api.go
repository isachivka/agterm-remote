// Package api is the bridge's own protocol: a closed set of verbs, and no way to ask for one outside
// it.
//
// **It said "two verbs, and no way to ask for a third" and there are nine.** The count was written
// when there were two and was never a property anybody was holding — the invariant is the closed set,
// not its size, and a sentence that has to be edited every time the set grows is a sentence that ends
// up lying. It grew by amendment each time, every one of them recorded: window geometry, typing, a
// file drop, and on 2026-07-31 the four verbs of REQ-0011 that let the owner create and rename from
// their phone.
//
// The caller never supplies an agterm command. It picks a verb from a closed set, and this package
// constructs the agterm request itself from validated fields. That is what makes REQ-0008 ruling 3
// structural rather than a filter someone can later widen — there is no code path in this binary that
// builds `session.overlay.open`, so no input can produce one. The other sixty-odd control commands
// are unreachable because nothing here names them.
package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
	"github.com/isachivka/agterm-remote/bridge/internal/keys"
	"github.com/isachivka/agterm-remote/bridge/internal/limits"
	"github.com/isachivka/agterm-remote/bridge/internal/resize"
	"github.com/isachivka/agterm-remote/bridge/internal/styled"
)

// Bounds on `lines`. The floor is agterm's own (`--lines must be greater than 0`), re-checked here so
// a bad value never becomes a request. The ceiling is ours: a 500-line read of a 276-column terminal
// is already far more than a phone can show, and an unbounded value would let one authenticated
// request pull the entire scrollback of twenty sessions over a metered connection.
const (
	MinLines     = 1
	MaxLines     = 500
	DefaultLines = 50
)

// Request is what arrives from the phone.
type Request struct {
	Verb string `json:"verb"`
	// Session is an agterm session id and must be a UUID — see validateSessionID.
	Session string `json:"session,omitempty"`
	Lines   int    `json:"lines,omitempty"`
	// Digest is what the caller already holds for this session. When it matches, the response is
	// `unchanged` and carries no text.
	Digest string `json:"digest,omitempty"`
	// Text is literal text to type, for VerbType. Checked by internal/keys, which refuses control
	// characters rather than stripping them.
	Text string `json:"text,omitempty"`
	// Paste is text the owner PASTED, for VerbType. Checked by internal/keys, which wraps it in the
	// bracketed paste markers so the far end reads it as a paste rather than as typing.
	//
	// **A field of its own, mutually exclusive with Text and Key, and not a boolean beside Text.**
	// REQ-0017: a flag that changes how a field is validated is how two validations become one by
	// accident - the same structural argument that keeps a session name and a label apart. Here it
	// decides whether a newline is permitted, which is the difference between text landing in an
	// editor and a command running.
	Paste string `json:"paste,omitempty"`
	// Key is a named key to press, for VerbType. One of a closed set - see internal/keys.
	Key string `json:"key,omitempty"`
	// Name is the file's basename, for VerbFile. Checked by internal/dropoff, which refuses anything
	// that could be a path rather than repairing it. **The caller never sends a path.**
	Name string `json:"name,omitempty"`
	// Workspace is an agterm workspace id and must be a UUID — see validateWorkspaceID. It addresses
	// the workspace a new session goes into, and the workspace a rename applies to.
	Workspace string `json:"workspace,omitempty"`
	// Label is the new name for a rename, and it is a SEPARATE field from Name on purpose.
	//
	// Name is a file's basename, checked by internal/dropoff; this is a sidebar label, checked by
	// keys.Label. One field carrying both would be one field with two validators, and the first time
	// somebody validated the wrong one the bridge would accept a path as a name or a name as a path.
	// Two fields cannot be crossed.
	Label string `json:"label,omitempty"`
	// Content is the file, base64. Standard encoding with padding.
	Content string `json:"content,omitempty"`
	// Pane is which half of a split session this request addresses: "left", "right", or absent.
	//
	// **One field, read by BOTH the screen path and the type path through [paneFor].** That is the
	// whole point of it. agterm defaults an absent pane differently for the two commands - a read gets
	// the on-screen pane, a type gets primary - so on a split session the phone was showing one pane
	// and typing into the other, silently, into the terminal the owner was not looking at. REQ-0032.
	//
	// Absent means "left", for both, which is what a session with no split has always meant. The two
	// calls agreeing is not a convention anybody maintains; it is one function with one return value.
	Pane string `json:"pane,omitempty"`
	// Styled asks VerbScreen for the pane's text WITH its SGR colours, read through the zmx daemon
	// behind the pane rather than through agterm's plain `session.text` — see internal/styled. Off by
	// default, and a pane that has no daemon answers plain without complaint: the phone's toggle is
	// a preference, not a demand the laptop can fail.
	Styled bool `json:"styled,omitempty"`
	// Columns is the terminal width the phone wants, for VerbResize. Zero means turn the setting off
	// and put the window back.
	Columns int `json:"columns,omitempty"`
	// BoxWidthDp is what the phone MEASURED its terminal box to be, and it is half the cache key.
	// Measured from the layout that actually draws, never computed from a constant - computing it is
	// what made our own edits move the key while the truth stayed the same.
	BoxWidthDp int `json:"box_width_dp,omitempty"`
	// CharacterWidthMilliDp is one character's width in the font the phone actually draws with,
	// measured by it, in thousandths of a dp so the wire carries no float.
	//
	// **With BoxWidthDp this is everything needed to say how many columns fit, and it replaces a
	// constant of 10.84 that was never checked against the owner's screen.**
	//
	// It picks the target the search aims at, and **it is part of the cache key** - see
	// resize.fitKey, which is (display, box width, character width). This comment said the opposite
	// until 2026-08-06, and it was wrong from the day the character width joined the key. Corrected
	// rather than deleted because it was read during REQ-0016 while working out what the key was made
	// of, and a comment that describes a key which is not the key costs the next reader the same hour.
	CharacterWidthMilliDp int `json:"character_width_milli_dp,omitempty"`
	// MarginDp is recorded with the fit so a human can see why a key changed. Not part of any lookup.
	MarginDp int `json:"margin_dp,omitempty"`
	// Recalibrate forces a fresh measurement. **The only thing that does**, other than a box width or
	// display never seen before.
	Recalibrate bool `json:"recalibrate,omitempty"`
	// CachedOnly asks the bridge to apply a fit it ALREADY HAS and to measure nothing — REQ-0040.
	//
	// **The automatic re-apply sends this and the owner's press never does.** A calibration is a
	// visible hunt across his window; one starting because he tapped a row in a list on his phone is a
	// surprise from a machine he is not looking at. With this set, an unknown geometry comes back as an
	// ordinary reply saying so, and he is asked to press.
	//
	// **A bridge that predates it REFUSES the whole request**, because [Decode] disallows unknown
	// fields. That is the safe direction — no surprise calibration — and it is why the phone treats
	// every failure on the automatic path as *do nothing, say nothing*: against an older bridge, a
	// session switch behaves exactly as it did before this existed.
	//
	// Set with Recalibrate it is refused rather than resolved. See [intentOf].
	CachedOnly bool `json:"cached_only,omitempty"`
	// Fresh asks VerbLimits to bypass its cache — REQ-0045, the long press on the phone.
	//
	// **The phone sends this key only when it is true.** [Decode] disallows unknown fields, so a
	// routine poll carrying `"fresh": false` would be refused by an older bridge with `malformed
	// request` — a sentence about the request's shape rather than about the verb. Without the key an
	// older bridge says the ordinary `unknown verb`; the phone reads both as *update the bridge*.
	Fresh bool `json:"fresh,omitempty"`
}

// Response is what goes back. Fields are omitted rather than zeroed so a response says only what it
// has: an `unchanged` reply carries no text field at all, not an empty one.
//
// # The rule for adding a field here
//
// **Any field whose FALSE or ZERO value is MEANINGFUL must NOT be omitempty.** omitempty cannot tell
// "the value is false" from "there is no value", and a reader that treats absence as unknown will
// therefore never see false at all. That shipped once - see FitEnabled - and cost the owner three
// presses on a control that could not be enabled.
//
// omitempty is right for `text`, `digest` and `path`, where absent and empty mean the same thing. It
// is wrong for anything tri-state, and a pointer is how the third state is carried.
type Response struct {
	OK    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
	// Refusal says WHICH KIND of no this is, and exists because `ok:false` was one channel carrying
	// two unrelated ones - REQ-0017.
	//
	// *"agterm is not running"* means the laptop cannot serve this and the phone's screen is
	// genuinely gone. *"byte 75 is a newline"* means the laptop is fine and this one request will
	// never work as sent. The phone could not tell them apart, so it treated a complaint about the
	// owner's TEXT as the connection breaking: it dropped a healthy socket and replaced the terminal
	// with a full-screen error listing our internal key names to somebody who had pasted a message
	// from a chat app.
	//
	// A type that cannot express the difference is the bug. This is the difference.
	//
	// **Closed vocabulary, one member: [RefusalContent].** Absent means what it has always meant -
	// the laptop side, or a bridge too old to have an opinion - and a phone reading absence keeps the
	// old behaviour exactly.
	//
	// `omitempty` here does NOT break the rule about meaningful zeroes below: absence is not a third
	// state anybody has to distinguish from empty, it IS "no opinion", and it is what every older
	// bridge already sends.
	Refusal string `json:"refusal,omitempty"`
	// Detail is the far end's own words, kept for somebody who goes looking, when [Error] is a
	// sentence we wrote instead of a sentence agterm wrote.
	//
	// # Why both, rather than one or the other
	//
	// On 2026-08-12 the owner opened a session and their whole screen said *"failed to read surface
	// buffer"* over a Try again button. That is agterm's sentence about its own internals, relayed
	// verbatim, and it told them nothing they could act on. But deleting it entirely would be the
	// other mistake: when they report a problem, that string is the fastest route to what happened.
	//
	// So the screen reads what we wrote and this carries what agterm said. **The message is ours; the
	// evidence is still there.** Absent whenever [Error] is already the far end's own words.
	Detail   string    `json:"detail,omitempty"`
	Sessions []Session `json:"sessions,omitempty"`
	// Workspaces is every workspace agterm holds, in agterm's own order.
	//
	// # Why this exists at all, when Session already carries a workspace id and name
	//
	// **A workspace with no sessions cannot be said by the session list.** The phone rebuilds the
	// hierarchy from the sessions, so a workspace holding none produces no rows and simply is not
	// there. That was harmless until the owner could create one: `workspace.new` makes exactly that
	// empty workspace, so without this field they would press + and be shown nothing.
	//
	// The alternative was to create a workspace and its first session in one bridge operation, keeping
	// the flat list able to express the result. That is two socket calls with no transaction: if the
	// second fails, the bridge has made an empty workspace it cannot describe and may not delete
	// (workspace.delete is a forbidden verb and stays one), the owner sees nothing, presses again, and
	// makes a second invisible orphan. A failure that is invisible on the phone is the worst kind here.
	//
	// **Two things improve for free.** An empty workspace the owner made on their laptop becomes
	// visible. And workspace ORDER stops being approximated from "the order their first session
	// appears" — which is what the phone had to do — and becomes what agterm actually holds.
	//
	// omitempty is safe here, unlike on Session.Status: absent means a bridge too old to say, and an
	// EMPTY list cannot occur, because agterm's workspace.delete keeps at least one. So there is no
	// third state for the encoding to swallow.
	Workspaces []Workspace `json:"workspaces,omitempty"`
	// Created is the id of what a create verb just made, and only the id.
	//
	// **It carried a name until the live test ran.** agterm's create replies answer with an id and
	// nothing else, so the phone reads the default label from the listing it refreshes anyway — one
	// source rather than two, and it cannot disagree with what the owner's sidebar shows.
	Created   *Created `json:"created,omitempty"`
	Text      *string  `json:"text,omitempty"`
	Digest    string   `json:"digest,omitempty"`
	Unchanged bool     `json:"unchanged,omitempty"`
	// Styled says Text carries SGR sequences and came through zmx. Absent on a plain read, INCLUDING
	// the silent fallback from a styled request, so the phone parses escapes only when told there
	// are some. omitempty is right: false and absent both mean "plain".
	Styled bool `json:"styled,omitempty"`
	// Columns is the width now in effect. See Calibrated for whether anything was measured to get it.
	Columns int `json:"columns,omitempty"`
	// FitEnabled is the BRIDGE's setting and it is the truth: on, off, or - as nil - not answered.
	//
	// **A POINTER, and NOT omitempty, and both of those are the fix for a shipped bug.** It was
	// `bool` with `json:"fit_enabled,omitempty"`, and omitempty on a bool omits the field when it is
	// FALSE. So the bridge could transmit true, or silence, and never "off" - while off is the normal
	// starting state. The phone reads an absent field as "not answered yet" and disables the toggle,
	// so the control was dead exactly when the owner needed to press it. Three presses on a dead
	// button, 2026-07-30.
	//
	// The phone genuinely has THREE states and a bare bool can express two, so the type carries the
	// third rather than the encoding smuggling it.
	FitEnabled *bool `json:"fit_enabled"`
	// Calibrated says the width was MEASURED on this request rather than applied from the cache. A
	// cached apply reads nothing back from the pty, so calling it "measured" would invent an
	// observation.
	Calibrated bool `json:"calibrated,omitempty"`
	// NeedsFit says the geometry the phone asked about has never been measured — REQ-0040.
	//
	// **Only ever true in answer to `cached_only`**, and it is an ordinary reply rather than a refusal.
	// The phone asked "apply it if you know it"; not knowing is a legitimate answer to that question,
	// and dressing it as a failure would put it through the path that once replaced the owner's
	// terminal with a full-page error — REQ-0037.
	//
	// **Not omitempty, and the reason is the rule at the top of this struct**: a field whose zero value
	// is meaningful is never omitted. False here means *it applied*, which is a fact the phone acts on
	// by saying nothing. FitEnabled taught this the expensive way.
	//
	// Adjacent to [Calibrated] and the opposite of it in an important way: that one says a measurement
	// HAPPENED, this one says one WOULD HAVE HAD TO and was declined.
	NeedsFit bool `json:"needs_fit"`
	// Path is where the bridge put a sent file. The phone puts it in the draft; it does not run it.
	Path string `json:"path,omitempty"`
	// Limits is the answer to VerbLimits and nothing else — REQ-0045. Absent on every other verb,
	// which is not a third state anybody distinguishes, so omitempty keeps the rule at the top. What
	// is inside it has its own rule: `remaining_pct` is never omitted, see limits.Window.
	Limits *Limits `json:"limits,omitempty"`
}

// Workspace is one heading in the phone's list. Id and name only — the phone groups by the id and
// reads the name, exactly as it does from a Session, and nothing else about a workspace is the
// phone's business.
type Workspace struct {
	ID string `json:"id"`
	// Name may be empty. A workspace with no name is still a workspace with an identity, and the phone
	// writes its own visible label for that case rather than the bridge inventing one.
	Name string `json:"name"`
}

// Created is the id of a thing a create verb just made.
//
// A struct rather than a bare string because the phone needs to tell "nothing was created" from "an
// empty id", and because a create that later has more to report has somewhere to report it.
type Created struct {
	ID string `json:"id"`
}

// Session is one row of the phone's list. Deliberately not agterm's node: surfaces, splits, overlays,
// watermarks, restore commands and geometry are all dropped. The bridge publishes what the feature
// needs and nothing that merely happens to be available.
type Session struct {
	ID string `json:"id"`
	// WorkspaceID is which workspace this session is IN, and it is what the phone groups by.
	//
	// **Identity rather than name, and not omitempty.** Two workspaces can share a name, and grouping
	// by name would merge them into one heading - the owner would see a session under a workspace it
	// is not in, which is a wrong answer presented confidently. Grouping by ARRIVAL ORDER would be
	// worse still: correct only while nothing reorders the list, a property nobody is holding and
	// nobody would notice breaking.
	WorkspaceID string `json:"workspace_id"`
	// Workspace is the name, for display. A workspace with no name is still a workspace with an
	// identity, so the phone groups it by the id above and writes its own visible label.
	Workspace string `json:"workspace"`
	Name      string `json:"name"`
	Title     string `json:"title,omitempty"`
	Active    bool   `json:"active"`
	// Status is what agterm's agent hooks say the session is doing: `active`, `blocked`, `completed`,
	// or empty for idle. See [status] for why the bridge normalises it rather than passing it on.
	//
	// **Not omitempty, and the reason is a rule rather than this field's own needs.** Empty and absent
	// happen to mean the same thing here — idle — but that coincidence is not a property anyone is
	// holding, and the rule at the top of Response is that a field whose zero value is meaningful is
	// never omitted. FitEnabled taught this the expensive way: omitempty on a bool could transmit true
	// or silence and never "off", and the owner pressed a dead control three times.
	//
	// So the field is always present, and a reader can tell "this bridge said idle" from "this bridge
	// is too old to say anything" without inspecting a version.
	Status string `json:"status"`
	// SplitPane is whether this session has a second pane the phone can address — **whether or not it
	// is on screen.** REQ-0034.
	//
	// ### The key is still spelled `split`, and that is deliberate
	//
	// The field's MEANING changed and its wire name did not. There is no version handshake on this
	// protocol, and the owner installs the two halves separately, so a phone updated ahead of the Mac
	// app is a real state rather than a hypothetical one. Renaming the key would make that phone read
	// no field at all and offer the toggle NEVER — worse than the defect being fixed. Keeping it means
	// a new phone against an old bridge behaves exactly like the build the owner is running today, and
	// an old phone against a new bridge gets the fix for free. Both mixtures land somewhere no worse
	// than today and one of them improves.
	//
	// The Go and Kotlin names say what the field now means; the tag says what the wire has always
	// called it. That split is the point, not an oversight.
	//
	// **Not omitempty, for the same reason as Status.** False is meaningful - it is what makes the
	// phone's pane toggle ABSENT rather than disabled - and omitempty cannot tell "there is no split"
	// from "this bridge is too old to have an opinion". A reader treating absence as unknown would
	// never see false at all, which is the defect FitEnabled already cost the owner three presses on.
	SplitPane bool `json:"split"`
}

// status maps agterm's value onto the closed set this bridge publishes.
//
// **The bridge does not forward what it does not understand**, which is the same sentence that keeps
// surfaces, splits, overlays, watermarks and geometry out of agterm.Tree. A value outside the set
// agterm is known to produce is published as idle — not passed through, and emphatically not guessed
// into one of the other three.
//
// The phone closes the set again at its own boundary, and that is not redundant: this end guarantees
// only a known value leaves the laptop, and that end guarantees an unknown one cannot render a glyph
// nobody chose. Two decoders is two chances to drift, which is why both are tested against these same
// four strings.
func status(s string) string {
	switch s {
	case "active", "blocked", "completed":
		return s
	default:
		// Idle, and anything a later agterm invents. A dot the owner can read as "nothing to say" is
		// the honest rendering of a value we cannot interpret.
		return ""
	}
}

// Handler answers requests. It holds no per-caller state — the digest travels with the caller rather
// than being remembered here, so nothing accumulates per connection and a reconnect costs nothing.
type Handler struct {
	client *agterm.Client
	// store and stateDir are the resize cache. Nil when the bridge was built without one, in which
	// case the resize verb refuses rather than silently doing nothing.
	store    *resize.Store
	stateDir string
	// history runs `zmx history <daemon> --vt` for a styled read. A field so tests can stand in a
	// dump without a zmx binary; production is styled.History.
	history func(ctx context.Context, executable, socketDir, daemon string) (string, error)
	// The store is reachable by two independent callers - the phone and the local control socket -
	// so access to it is serialized. See serialize.go, which explains why removing this while
	// looking at a single-caller trace would be a mistake.
	locks
	// The limits cache — REQ-0045. Under a mutex of its own: `locks` serialises the resize store,
	// which the limits verb never reads, and a long press should not queue behind a calibration.
	// See limits.go for what each field is.
	limitsMu    sync.Mutex
	limitsFetch func(ctx context.Context) (claude, codex limits.Report)
	limitsHeld  *Limits
	limitsAt    time.Time
	limitsNow   func() time.Time
}

// New builds a handler. stateDir is where the resize calibration cache lives; empty disables resize.
//
// No limits reader is installed here: [Handler.UseLimits] does that, and main is its only production
// caller. A test that builds a handler and walks every verb therefore gets a refusal from `limits`
// rather than two HTTP requests to the providers.
func New(client *agterm.Client, stateDir string) *Handler {
	h := &Handler{client: client, stateDir: stateDir, history: styled.History, limitsNow: time.Now}
	if stateDir != "" {
		h.store = resize.LoadStore(stateDir)
	}
	// Published before anything can be served, so the first reply carries the setting the store was
	// loaded with rather than a zero value.
	h.publishFit()
	return h
}

// Verbs, as a closed set. A verb outside it is an error, and the error names no alternatives.
const (
	VerbSessions = "sessions"
	VerbScreen   = "screen"
	// VerbResize is the only verb that CHANGES anything on the laptop, and what it changes is the
	// width of a window. It cannot type, cannot run anything, and cannot reach a session's contents -
	// see the allowlist in internal/agterm, which fails the build if this grows a third capability.
	VerbResize = "resize"
	// VerbType is the only verb that puts bytes on a pty. Authorised by the owner on 2026-07-29,
	// reversing REQ-0008's original ruling that this bridge may never inject input.
	VerbType = "type"
	// VerbFile writes a file to the owner's filesystem, under a path the BRIDGE chooses. The third
	// kind of write and the largest - see internal/dropoff. Requested by the owner on 2026-07-30.
	VerbFile = "file"

	// The four verbs of REQ-0011, added 2026-07-31 so the owner can create and rename from the phone.
	//
	// **Dotted names, unlike the five above, and that is deliberate.** `create` and `rename` alone do
	// not say what they act on, and this set has two of each. The name carries the noun rather than
	// leaving it to the reader to infer from which field happens to be populated.
	//
	// None of them can carry a command, a cwd or a path. Creating sends a workspace id or nothing at
	// all; renaming sends an id and a label that keys.Label has already proven holds no control
	// character.
	VerbWorkspaceCreate = "workspace.create"
	VerbSessionCreate   = "session.create"
	VerbWorkspaceRename = "workspace.rename"
	VerbSessionRename   = "session.rename"

	// **The two verbs of REQ-0012 that DESTROY the owner's work**, added 2026-07-31 at their request.
	//
	// Every verb above this line either reads, or writes something that can be undone by writing
	// again. These cannot. A closed session and a deleted workspace do not come back, and a phone
	// that has been taken can now reach both - which is written here, in REQ-0012 and in the
	// allowlist, so nobody later reads this list and thinks they crept in.
	//
	// The guard is the owner's gesture, not this package: the phone offers Delete only inside a modal
	// that a long press opened. What this package guarantees is narrower and is all it can guarantee -
	// a canonical UUID, so a partial target cannot resolve to `active` and destroy whatever they are
	// working in.
	VerbSessionClose    = "session.close"
	VerbWorkspaceDelete = "workspace.delete"

	// **The verb that starts a process on the owner's Mac** — REQ-0035, added 2026-08-25.
	//
	// It is not in the destructive block above and it is not in the harmless block below it, so it is
	// stated here on its own: opening a pane that does not exist CREATES one, and a created pane runs
	// a login shell. Nothing else in this package spawns anything.
	//
	// `open` rather than `create`, because agterm's own verb does both and the caller cannot tell
	// which it got: a pane that already exists is revealed, a pane that does not is made. See
	// [agterm.Client.OpenSplitPane], where all three states are measured.
	//
	// **No confirmation, and that is the owner's ruling rather than an omission.** He specified one
	// tap doing the whole thing — *"если сессия есть мы её показываем, если её нет мы её создаём и
	// потом показываем"* — after the mis-tap risk was put to him. REQ-0035.
	VerbPaneOpen = "pane.open"

	// VerbPaneShow shows one pane at the full width of the terminal area — REQ-0042.
	//
	// **A new verb rather than a pane on [VerbPaneOpen], and the reason is version skew.**
	// `Request.Pane` already exists, so a bridge that predates this ACCEPTS a pane on a `pane.open`
	// and ignores it: a phone asking to maximize the LEFT pane would get a split created instead,
	// silently, and `DisallowUnknownFields` cannot catch it because the field is not new.
	//
	// An unknown verb is refused by name. That is loud, and it lands on the path that already knows
	// how to carry a refusal without taking his terminal away — the same reasoning that put
	// `cached_only` on its own field in REQ-0040.
	//
	// **It creates nothing.** Where [VerbPaneOpen] may start a shell, this only ever rearranges panes
	// that already exist, and refuses a session that has none.
	VerbPaneShow = "pane.show"

	// VerbLimits says how much of each subscription is left — REQ-0045, added 2026-09-06.
	//
	// **The first verb that never touches agterm.** It reads two credentials that are not the
	// bridge's own — Claude Code's OAuth token from the Keychain and Codex's from ~/.codex — for the
	// duration of one HTTP request each, and publishes percentages, reset times and an error category
	// from a closed vocabulary. The rule about the tokens is in internal/limits; the cache is in
	// limits.go beside this file. Nothing is created, typed, or moved on the Mac.
	VerbLimits = "limits"
)

// Handle dispatches one request.
//
// Every failure returns an ok:false response rather than an error, because by the time this runs the
// caller is authenticated and a description helps them. What is never returned is anything derived
// from an UNAUTHENTICATED caller — that case never reaches this package at all, which is the point of
// where the TLS handshake sits.
func (h *Handler) Handle(ctx context.Context, req Request) Response {
	resp := h.serialized(ctx, req)

	// **The width setting rides on EVERY reply, set here rather than by each verb.**
	//
	// It was set only on the responses to `resize`, and the phone gates its button on having seen it -
	// so to learn the state you had to press, and to press you had to know the state. A deadlock by
	// construction, and the owner pressed a control that was doing exactly what it was told for three
	// builds. Setting it in one place, on the way out, is what makes "some verb forgot to include it"
	// impossible rather than merely unlikely.
	//
	// It is the bridge's own state and costs a bool; there is no reason for it to be conditional on
	// the caller having already acted.
	// Read from the published pair, not from the store: this runs on the poll path, and a poll that
	// waited out a calibration to learn a boolean would freeze the owner's screen for seven seconds.
	//
	// **The COUNT rides along with the flag.** Columns is otherwise set in exactly one place - the
	// resize reply - so a poll would carry "fitted" with no count, the phone would read zero, and its
	// accessibility label would start announcing zero columns the moment the first poll landed after a
	// press. A value that drifts because one path publishes it and another does not is the defect this
	// entire feature has been about.
	if h.store != nil && resp.FitEnabled == nil {
		// fitOnTheWire, not fitNow: the phone is told the setting as CHECKED against the last thing
		// the pty showed, so a window the owner dragged wider stops being reported as fitted. The
		// local control socket keeps reading fitNow, because its next move is a restore and that is
		// a different question. See serialize.go.
		inForce, columns := h.fitOnTheWire()
		resp.FitEnabled = boolPtr(inForce)
		// Never overwrites a count a verb already established: the resize reply carries what the
		// laptop just MEASURED, which is the more specific truth about that request.
		if resp.Columns == 0 {
			resp.Columns = columns
		}
	}
	return resp
}

// paneFor is the ONE place a request's pane is decided, and both the screen path and the type path
// call it.
//
// # Why this is a function and not two defaults
//
// REQ-0032. agterm resolves an absent pane differently per command - measured over the socket on
// 2026-08-25 - so the bridge sending nothing meant a read went to the on-screen pane and a type went to
// primary. On a split session that is the phone showing one pane and typing into the other, with no
// error, into the terminal the owner was not looking at.
//
// **The fix is not a pane field. It is that there is exactly one answer and both callers get it.** A
// second default anywhere, for either verb, reintroduces the whole defect - which is why the client
// refuses an invalid pane rather than falling back, and why nothing below returns a zero value.
//
// # Why absent means left
//
// A session with no split has one pane, and that pane is primary. An older phone that names no pane
// therefore behaves exactly as it always did on the sessions it could already reach, and on a split
// session it reads and types into the same half instead of two different ones. Safe, and identical for
// both verbs, which is the property that matters.
func paneFor(req Request) (agterm.Pane, error) {
	switch req.Pane {
	case "":
		return agterm.PaneLeft, nil
	case string(agterm.PaneLeft):
		return agterm.PaneLeft, nil
	case string(agterm.PaneRight):
		return agterm.PaneRight, nil
	default:
		// Closed set, refused by name - the same discipline as keys.Key. A pane we do not know is not
		// passed through to see what the laptop makes of it.
		return "", fmt.Errorf("pane must be %q or %q", agterm.PaneLeft, agterm.PaneRight)
	}
}

func (h *Handler) dispatch(ctx context.Context, req Request) Response {
	switch req.Verb {
	case VerbSessions:
		return h.sessions(ctx)
	case VerbScreen:
		return h.screen(ctx, req)
	case VerbResize:
		return h.resize(ctx, req)
	case VerbType:
		return h.typing(ctx, req)
	case VerbFile:
		return h.file(ctx, req)
	case VerbWorkspaceCreate:
		return h.createWorkspace(ctx)
	case VerbSessionCreate:
		return h.createSession(ctx, req)
	case VerbWorkspaceRename:
		return h.renameWorkspace(ctx, req)
	case VerbSessionRename:
		return h.renameSession(ctx, req)
	case VerbSessionClose:
		return h.closeSession(ctx, req)
	case VerbWorkspaceDelete:
		return h.deleteWorkspace(ctx, req)
	case VerbPaneOpen:
		return h.openPane(ctx, req)
	case VerbPaneShow:
		return h.showPane(ctx, req)
	case VerbLimits:
		return h.limits(ctx, req)
	default:
		return fail("unknown verb")
	}
}

// createWorkspace makes an empty workspace with agterm's own default name.
//
// It takes nothing from the request — there is no field to get wrong, and no name to validate,
// because the owner names it afterwards by renaming it.
func (h *Handler) createWorkspace(ctx context.Context) Response {
	id, err := h.client.NewWorkspace(ctx)
	if err != nil {
		return fail(describe(err))
	}
	return Response{OK: true, Created: &Created{ID: id}}
}

// createSession makes a session in one workspace, with agterm's default name.
//
// **It moves the owner's laptop.** agterm focuses what session.new creates — measured 2026-07-31 — and
// offers no flag to suppress it, so pressing + on the phone changes what their Mac is showing. That is
// recorded here, in the agterm allowlist and in the PR body rather than left to be discovered.
func (h *Handler) createSession(ctx context.Context, req Request) Response {
	if err := validateWorkspaceID(req.Workspace); err != nil {
		return fail(err.Error())
	}
	id, err := h.client.NewSessionIn(ctx, req.Workspace)
	if err != nil {
		return fail(gone("workspace", describe(err)))
	}
	return Response{OK: true, Created: &Created{ID: id}}
}

// renameWorkspace sets one workspace's label.
//
// **The id is validated before the label**, and the order is the point: an invalid id is the failure
// that renames the wrong thing, and it must not be possible to reach agterm with a perfectly good name
// and a target that resolves to `active`.
func (h *Handler) renameWorkspace(ctx context.Context, req Request) Response {
	if err := validateWorkspaceID(req.Workspace); err != nil {
		return fail(err.Error())
	}
	label, err := keys.Label(req.Label)
	if err != nil {
		return fail(err.Error())
	}
	if err := h.client.RenameWorkspace(ctx, req.Workspace, label); err != nil {
		return fail(gone("workspace", describe(err)))
	}
	return Response{OK: true}
}

// closeSession closes one of the owner's sessions. **Destroys their work; there is no undo.**
//
// The id is theirs rather than ours, so provenance cannot be the guard here the way it is for
// calibration — see [agterm.Client.CloseSession], which states both rules. What this can guarantee is
// that a partial or absent target never reaches agterm, because `active` would close whatever they
// are working in.
func (h *Handler) closeSession(ctx context.Context, req Request) Response {
	if err := validateSessionID(req.Session); err != nil {
		return fail(err.Error())
	}
	if err := h.client.CloseSession(ctx, req.Session); err != nil {
		return fail(gone("session", describe(err)))
	}
	return Response{OK: true}
}

// openPane makes sure a session has a right-hand pane on screen, creating one if it has none.
//
// # What the phone is asking for, and what it is not
//
// It is asking for a pane to read. Whether that means creating one is agterm's decision, taken from
// the state of the session — see [agterm.Client.OpenSplitPane], where the three cases are measured.
// This handler chooses nothing and reports nothing about which happened, because the reply does not
// say and inventing an answer is the failure this repository keeps a log about.
//
// # It is a UUID or it is refused, for the usual reason
//
// A partial target resolves to `active` in agterm, which here would mean opening a pane in whatever
// session the owner happens to be working in. Measured on the socket: an unknown id comes back
// `no such session: <id>`, so a wrong-but-canonical id fails cleanly rather than hitting something.
func (h *Handler) openPane(ctx context.Context, req Request) Response {
	if err := validateSessionID(req.Session); err != nil {
		return fail(err.Error())
	}
	if err := h.client.OpenSplitPane(ctx, req.Session); err != nil {
		return fail(gone("session", describe(err)))
	}
	return Response{OK: true}
}

// showPane shows one pane at the full width of the terminal area — REQ-0042.
//
// # It refuses an unsplit session rather than succeeding at nothing
//
// agterm's `session.focus` answers `ok:false, "session has no split"`, measured 2026-08-29. That is
// passed through rather than swallowed. A session with one pane is ALREADY showing that pane at full
// width, so a bridge that answered ok here would be reporting work it did not do — and the phone
// decides what to show partly on whether this succeeded.
//
// # The pane comes from [paneFor], and this verb does NOT get a rule of its own
//
// It matters more here than anywhere: agterm resolves a missing pane on `session.focus` to `other`, a
// TOGGLE — so an absent value reaching the socket is whichever pane he is not looking at, half the
// time. That is exactly the REQ-0032 defect, in a third place.
//
// The fix is the one already in this file rather than a new one. [paneFor] is where a wire string
// becomes an [agterm.Pane], absent means left there for every verb, and **a third default added here
// would be the thing its own comment warns against.** Left is also the safe direction: a session with
// no split gets an honest refusal rather than a toggle into a pane nobody asked for.
func (h *Handler) showPane(ctx context.Context, req Request) Response {
	if err := validateSessionID(req.Session); err != nil {
		return fail(err.Error())
	}
	pane, err := paneFor(req)
	if err != nil {
		return fail(err.Error())
	}
	if err := h.client.MaximizePane(ctx, req.Session, pane); err != nil {
		return fail(gone("session", describe(err)))
	}
	return Response{OK: true}
}

// deleteWorkspace deletes one of the owner's workspaces **and every session in it**.
//
// Measured: agterm takes the sessions silently and answers ok. The phone's modal is where the owner
// learns how many go, because nothing on this side will tell them and the list is behind the modal.
func (h *Handler) deleteWorkspace(ctx context.Context, req Request) Response {
	if err := validateWorkspaceID(req.Workspace); err != nil {
		return fail(err.Error())
	}
	if err := h.client.DeleteWorkspace(ctx, req.Workspace); err != nil {
		return fail(gone("workspace", describe(err)))
	}
	return Response{OK: true}
}

// renameSession sets one session's label. Same two checks, same order, same reasons as
// [Handler.renameWorkspace].
func (h *Handler) renameSession(ctx context.Context, req Request) Response {
	if err := validateSessionID(req.Session); err != nil {
		return fail(err.Error())
	}
	label, err := keys.Label(req.Label)
	if err != nil {
		return fail(err.Error())
	}
	if err := h.client.RenameSession(ctx, req.Session, label); err != nil {
		return fail(gone("session", describe(err)))
	}
	return Response{OK: true}
}

func (h *Handler) sessions(ctx context.Context) Response {
	tree, err := h.client.Tree(ctx)
	if err != nil {
		return fail(describe(err))
	}
	out := make([]Session, 0, 16)
	// Published in agterm's order, and published even when a workspace holds nothing — that empty case
	// is the entire reason this field exists. See Response.Workspaces.
	spaces := make([]Workspace, 0, len(tree.Workspaces))
	for _, ws := range tree.Workspaces {
		spaces = append(spaces, Workspace{ID: ws.ID, Name: ws.Name})
		for _, s := range ws.Sessions {
			out = append(out, Session{
				ID: s.ID,
				// Both, and in this order for a reason: the id is what groups, the name is what is
				// read. Emitting only the name is what made the phone's list flat for a week.
				WorkspaceID: ws.ID,
				Workspace:   ws.Name,
				Name:        s.Name,
				Title:       s.Title,
				Active:      s.Active,
				Status:      status(s.Status),
				// **Derived, where this used to be straight through.** agterm's own `split` field
				// answers a different question — both panes VISIBLE — and the phone needs to know
				// whether a second pane EXISTS. A pane collapsed on the Mac still reads perfectly
				// from the phone, so the two states must not arrive here looking alike. REQ-0034.
				SplitPane: s.HasSplitPane(),
			})
		}
	}
	return Response{OK: true, Sessions: out, Workspaces: spaces}
}

func (h *Handler) screen(ctx context.Context, req Request) Response {
	if err := validateSessionID(req.Session); err != nil {
		return fail(err.Error())
	}
	lines := req.Lines
	if lines == 0 {
		lines = DefaultLines
	}
	if lines < MinLines || lines > MaxLines {
		return fail(fmt.Sprintf("lines must be between %d and %d", MinLines, MaxLines))
	}

	pane, err := paneFor(req)
	if err != nil {
		return refuseContent(err.Error())
	}

	// A styled read is tried first and abandoned silently: the phone said what it would PREFER, and
	// a pane without a daemon, a daemon that will not answer, or a zmx that fails to run are all
	// reasons to show the plain screen rather than none. Only the plain read's failure is an error.
	var text string
	styledRead := false
	if req.Styled {
		if vt, ok := h.styledText(ctx, req.Session, string(pane), lines); ok {
			text, styledRead = vt, true
		}
	}
	if !styledRead {
		plain, err := h.client.Text(ctx, req.Session, lines, pane)
		if err != nil {
			return unreadable(describe(err))
		}
		text = plain
	}

	// **The only place in the bridge that MEASURES the owner's window for free.**
	//
	// The fit reported on every reply used to be pure recall - the setting as of the last completed
	// operation - so dragging the window wider on the Mac left the phone claiming a fit that had
	// stopped being true. This is the counterweight: one pass over text that was about to be hashed
	// anyway, no agterm round trip, and no new failure mode, because a measurement that comes out
	// low is treated as no evidence rather than as a narrower window.
	//
	// Before the Unchanged branch below, deliberately. An unchanged screen is still a screen that was
	// just read, and skipping it here would make the freshness of this fact depend on whether the
	// owner's session happened to redraw.
	// An SGR occupies no cell, so a styled read is measured on its text alone.
	h.noteMeasuredColumns(resize.MeasureColumns(styled.Strip(text)))

	// Computed over exactly the bytes being returned, and nothing else. A digest over trimmed or
	// normalised text could match while the real content differed, which would show the owner a
	// stale screen and give them no way to tell.
	sum := sha256.Sum256([]byte(text))
	digest := hex.EncodeToString(sum[:])

	if req.Digest != "" && req.Digest == digest {
		// Measured on a live idle Claude session: 0 of 19 transitions changed over ten seconds at
		// 2 Hz, 105 KB moved to convey nothing. This is why the digest is in the bridge and not the
		// app — the transport has no output event, so polling is mandatory and only the far end can
		// make it cheap.
		return Response{OK: true, Unchanged: true, Digest: digest, Styled: styledRead}
	}
	return Response{OK: true, Text: &text, Digest: digest, Styled: styledRead}
}

// styledText reads one pane's screen with its colours through the zmx daemon behind it, cut to the
// last `lines` rows. False on any failure; the caller falls back to the plain read and the reason
// is not reported, because none of the reasons is something the phone can act on.
func (h *Handler) styledText(ctx context.Context, session, pane string, lines int) (string, bool) {
	inv, err := h.client.ZmxList(ctx)
	if err != nil {
		return "", false
	}
	daemon, ok := styled.Pick(inv.Entries, session, pane)
	if !ok || inv.Executable == "" || inv.SocketDir == "" {
		return "", false
	}
	dump, err := h.history(ctx, inv.Executable, inv.SocketDir, daemon)
	if err != nil {
		return "", false
	}
	return styled.Tail(styled.Clean(dump), lines), true
}

// validateSessionID requires a canonical UUID.
//
// Not defensive padding. agterm resolves a target by id, by unique prefix, or by the literal
// `active`, and an ABSENT target silently defaults to `active` — so a caller sending "active", a
// one-character prefix, or nothing at all would read a session the bridge never meant to address, and
// agterm would answer ok. Requiring a full UUID closes all of that with no extra round trip: a tree
// lookup would cost one more blocking hop through agterm's main actor on every single poll.
func validateSessionID(id string) error { return validateUUID("session", id) }

// validateWorkspaceID requires a canonical UUID, for exactly the reason above.
//
// **The trap is the same one and the consequence is worse.** agterm resolves a workspace target by id,
// by unique prefix, or by `active`, and an absent target defaults to `active` — so a rename that
// arrived without an id would rename whichever workspace the owner is currently in, with the label
// they meant for a different one, and agterm would answer ok.
//
// Measured 2026-07-31: all seven workspaces in the owner's live tree carry canonical UUIDs, so this
// costs nothing real.
func validateWorkspaceID(id string) error { return validateUUID("workspace", id) }

// validateUUID is the one loop both validators use.
//
// Two copies of a check is one copy that gets fixed, and the messages are parameterised rather than
// the logic being duplicated for the sake of a noun.
func validateUUID(kind, id string) error {
	if id == "" {
		return fmt.Errorf("%s is required", kind)
	}
	if len(id) != 36 {
		return fmt.Errorf("%s must be a %s id", kind, kind)
	}
	for i, c := range id {
		switch i {
		case 8, 13, 18, 23:
			if c != '-' {
				return fmt.Errorf("%s must be a %s id", kind, kind)
			}
		default:
			isHex := (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
			if !isHex {
				return fmt.Errorf("%s must be a %s id", kind, kind)
			}
		}
	}
	return nil
}

// gone rewrites agterm's "no such session/workspace" into something the owner can read.
//
// # Why this one message is translated when nothing else is
//
// The house rule is that the bridge quotes agterm rather than diagnosing it, and that rule is not
// being broken here — it is being applied. agterm's own words for this are
// `no such session: 00000000-1111-2222-3333-444444444444`, measured on 2026-07-31, and put in front
// of the owner on a full-screen refusal that reads as the app being broken rather than as something
// that happened to their session. It also puts a raw id in the UI, which belongs in no screen.
//
// **It is not a guess about a cause.** The bridge asked agterm about one specific id and agterm said
// it does not exist. That is a direct observation, and the sentence below says exactly it and nothing
// more — no theory about who closed it or when.
//
// The owner's real case: they long-press a row, and between the last poll and the press the session
// was closed on the Mac. The list is a photograph; this is what it looks like when it goes stale.
//
// **Matching a foreign system's error text is fragile, and the fragility is bounded.** If agterm ever
// rewords this, the prefix stops matching and the raw text is passed through — which is exactly the
// behaviour that exists today, so the worst case of this helper is no worse than not having it.
func gone(kind, message string) string {
	if !strings.HasPrefix(message, "no such "+kind) {
		return message
	}
	return "That " + kind + " is no longer on your laptop. Pull to refresh the list."
}

// unreadable turns any failure to READ a session into something written for the owner, keeping
// agterm's own words beside it rather than on the screen.
//
// # The bug this exists for
//
// 2026-08-12: the owner opened a session and the Terminal screen was empty except for
// *"failed to read surface buffer"* and a button. That sentence is agterm describing its internals to
// itself. Nothing about it tells a person what happened or what to do, and an otherwise blank screen
// makes it the whole message.
//
// # Measured, on 2026-08-12, over this same socket
//
// A throwaway session, created and closed to ask agterm directly rather than reasoning about it:
//
//	session exists, never rendered  ->  failed to read surface buffer
//	session closed                  ->  no such session: <uuid>
//	id that never existed           ->  no such session: <uuid>
//
// So the owner's session was NOT gone — it existed and had never been shown. Which is the ordinary
// state of every session after agterm restores them at launch, until somebody clicks one.
//
// # Why the FALLBACK is the load-bearing part, not the two translations
//
// PLAN-0008 predicted this exact case in July, drafted the sentence to show — *"agterm has not opened
// this session"* — and it was never wired. In the meantime agterm's wording for it CHANGED, from
// `session not realized` to what is matched below. A month, one rewording.
//
// The two matches handle what we know today. **The default handles the next rewording**, which is the
// one thing certain to happen: when a prefix stops matching, the owner reads our sentence rather than
// a new foreign one, and we are better off than before this function existed rather than worse.
func unreadable(message string) Response {
	switch {
	case strings.HasPrefix(message, "no such session"):
		// The rewrite that already exists, on the path that never called it. Five verbs did.
		return Response{OK: false, Error: gone("session", message)}
	case strings.Contains(message, "surface buffer"):
		// PLAN-0008's own words, finally used. `select` would fix it and mutates the owner's laptop,
		// so this says what is true and offers nothing that reaches over and changes their screen.
		return Response{
			OK:     false,
			Error:  "Your laptop has not opened this session yet. Open it on the Mac, then try here.",
			Detail: message,
		}
	case strings.Contains(message, "no split pane"):
		// **The pane went away underneath the phone**, which is a thing that changed on the laptop and
		// not a connection breaking - REQ-0027's shape. agterm names this one precisely, unlike the
		// surface-buffer case above, so there is no guessing about which fault it is.
		return Response{
			OK:     false,
			Error:  "That session has only one pane now. Showing the first one.",
			Detail: message,
		}
	case message == "agterm is not answering":
		// Already ours, already written for a person. Passing it through unchanged.
		return fail(message)
	default:
		return Response{
			OK:     false,
			Error:  "Your laptop refused to open this session.",
			Detail: message,
		}
	}
}

// describe turns a client error into something the phone can show.
//
// REQ-0008 §6: when the laptop is not answering the app says "your laptop is not answering" and does
// not invent a network diagnosis. That copy is only writable if this distinction survives to here.
func describe(err error) string {
	if errors.Is(err, agterm.ErrUnavailable) {
		return "agterm is not answering"
	}
	return err.Error()
}

func fail(msg string) Response { return Response{OK: false, Error: msg} }

// RefusalContent marks a no that is about WHAT WAS SENT rather than about the laptop.
//
// The test for using it, and it is narrow: **would this request fail again, identically, if the
// laptop were perfectly healthy and the caller retried?** Text with a newline in it, a name of a
// hundred characters, a key that is not in the allowlist - yes, every time, and no amount of
// reconnecting changes that. agterm being down, a socket that closed, a session that vanished - no,
// those are about the machine and they keep the old behaviour.
//
// Getting that test wrong in the generous direction is the dangerous one: a laptop-side failure
// mislabelled as content leaves the phone showing a small notice while the connection is actually
// dead, which is the opposite of the defect REQ-0017 fixes.
//
// # Widened once, deliberately, by REQ-0037
//
// The fit path now uses it for an OPERATION the laptop declined — a calibration whose search could not
// resolve the geometry. That is not "what was sent" in the narrow sense, and it belongs here anyway:
// the invariant this marker actually carries is **the bridge answered and the link is healthy, and
// retrying would produce the same answer**, which is exactly true of a geometry the search cannot fit.
// The dangerous direction stays closed, because reaching that line means a reply was composed.
const RefusalContent = "content"

// refuseContent is [fail] for the case above. Same message, one more fact about it.
func refuseContent(msg string) Response {
	return Response{OK: false, Error: msg, Refusal: RefusalContent}
}

// Decode parses one request line, refusing unknown fields.
//
// DisallowUnknownFields is deliberate. A field the bridge does not understand is either a client from
// a future it cannot serve or an attempt to smuggle something past the allowlist, and both deserve an
// error rather than a silent ignore. agterm's own ControlArgs ignores unknown keys, which is how
// `target` placed in the wrong object silently addressed the wrong session — the exact failure this
// setting prevents here.
func Decode(line []byte) (Request, error) {
	dec := json.NewDecoder(strings.NewReader(string(line)))
	dec.DisallowUnknownFields()
	var req Request
	if err := dec.Decode(&req); err != nil {
		return Request{}, errors.New("malformed request")
	}
	return req, nil
}
