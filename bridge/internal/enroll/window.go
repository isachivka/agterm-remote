package enroll

import (
	"crypto/rand"
	"crypto/subtle"
	"errors"
	"sync"
	"time"
)

// maxAttempts is how many wrong tokens a window survives before it closes.
//
// This is not the defence against guessing. The token is 32 bytes from crypto/rand, and no number of
// attempts an attacker can make in five minutes moves that needle. What the counter is for is the
// window nobody is using: the owner opened a panel, something else started talking to the port, and
// the code is still on screen. Five is enough for a phone that retried a dropped connection, and
// small enough that a window under noise shuts instead of standing open until its expiry.
const maxAttempts = 5

// MaxTTL is the longest a window may be open, and [Open] will not exceed it whatever it is asked for.
//
// # Why the ceiling is here rather than in whoever calls Open
//
// **Every argument for this design's anonymous branch rests on "a window is seconds of the bridge's
// life and requires a person at the Mac".** It is the reason an unauthenticated caller may reach the
// bridge at all, the reason the enrolment handler's bounded log lines are acceptable, and the reason
// the memory an anonymous caller can hold is a residual rather than a hole. Nothing enforced it. Open
// took any duration, and the policy lived in a user interface nobody has written yet - so the
// property the whole surface is justified by was a habit, and the first `Open(24*time.Hour)` written
// by somebody debugging would have turned it into an all-day anonymous port with no code anywhere
// objecting.
//
// So the ceiling lives next to the argument it protects, in the type whose three properties are that
// argument. A caller cannot opt out of it, and a caller that wants a longer pairing session has to
// come here and change this line, which is exactly the conversation that should happen.
//
// Five minutes, because that is what the QR payload's own example carries, it is far more than the
// seconds a person needs to pick up a phone and scan a code they are looking at, and it is short
// enough that a window left open by mistake closes itself long before anybody notices the panel is
// still up. Re-pressing the button is what a person does when a code goes stale, and it costs them
// nothing.
const MaxTTL = 5 * time.Minute

// ErrRefused is every refusal Consume can return, and it is deliberately the only one that says
// anything.
//
// There are three underlying causes - no window, an expired window, the wrong bytes - and which one
// it was must not reach the caller. It tells an unauthenticated stranger whether a window is open at
// all and whether their guess had the right shape, and the shortest path to leaking it is the
// handler that writes http.Error(w, err.Error(), 403) without thinking. So the message every refusal
// carries is this one, and the causes below are unexported.
//
// That closes the ACCIDENTAL relay, which is the one that happens. It is not a wall: Unwrap() []error
// is reachable through a structural type assertion, so a caller determined to learn the cause can,
// and this package cannot stop it. The point is that no lazy path produces the leak - a handler has
// to write code that means to.
//
// errors.Is(err, ErrRefused) is how a caller asks "was this a refusal rather than a bug", and the
// causes remain distinguishable by errors.Is inside this package - see refused.Unwrap - which is
// where the decision about what an owner may be told belongs. When the Mac app needs to say WHY a
// code died, and it should, that wants a deliberate accessor on this package rather than an error
// string that travels to a stranger by default.
var ErrRefused = errors.New("enroll: enrolment refused")

// The causes. Unexported on purpose; see ErrRefused.
var (
	causeClosed  = errors.New("no enrolment window is open")
	causeExpired = errors.New("the enrolment window expired")
	causeToken   = errors.New("wrong enrolment token")
)

// refused is a refusal that knows why and will not say.
//
// Error() returns ErrRefused's text and nothing else - printing it, wrapping it in another message
// or sending it over the wire discloses only that the enrolment was refused. Unwrap returns both
// ErrRefused and the cause, so errors.Is answers for either without the cause ever being formatted.
type refused struct{ cause error }

func (refused) Error() string { return ErrRefused.Error() }

func (r refused) Unwrap() []error { return []error{ErrRefused, r.cause} }

// Window is the interval during which this Mac will accept a new phone.
//
// It is the only thing in the design that lets an unauthenticated caller reach the bridge at all, so
// its three properties are the whole of its security:
//
//   - It is single use. Consume succeeds at most once and closes the window on that success. A token
//     that works twice is a token an observer of the screen, the camera or the room can replay after
//     the owner has walked away.
//   - It is compared in constant time. subtle.ConstantTimeCompare, never == or bytes.Equal. The
//     caller here is one who can retry as fast as the network allows, which is exactly the caller a
//     comparison that returns early on the first wrong byte leaks the prefix to. No test in this
//     package holds that down and none can - a timing assertion over 32 bytes on a shared CI runner
//     is either loose enough to pass on bytes.Equal or flaky enough to be deleted. It is held by
//     scripts/check-constant-time-tokens.sh, which fails the build on a token compared with ==,
//     bytes.Equal, reflect.DeepEqual or strings.Compare anywhere in this package.
//   - It never touches disk. The token lives in this struct and nowhere else: not in a state file,
//     not in a cache, not in a log line. Losing it to a crash is the correct behaviour - the code on
//     the owner's screen is stale from that moment and the fix is to open a new one - whereas a
//     token that reached the filesystem outlives its window and is readable by anything that can
//     read the disk. A test walks the state directory and fails on the bytes appearing in any file.
//
// # Time is injected
//
// NewWindow takes the clock, and nothing in this file calls time.Now(). Expiry is then a property a
// test can assert in microseconds instead of a property a test can only approximate by sleeping -
// and a suite that sleeps for its timing assertions is a suite that gets those assertions loosened
// the first time CI is slow, until they assert nothing.
//
// The zero Window is not usable; NewWindow is the only constructor, because a nil clock would panic
// far from the mistake.
//
// All methods are safe for concurrent use. That is not decoration: the pairing handler runs in the
// server's goroutine per connection, and two connections racing on one token must produce exactly
// one enrolment. The mutex is held across the compare-and-close for that reason - a check followed
// by a separate write would hand the same window to both callers.
type Window struct {
	now func() time.Time

	mu       sync.Mutex
	open     bool
	token    [32]byte
	expiry   time.Time
	attempts int
}

// NewWindow returns a closed window that reads the time from now.
//
// Pass time.Now in production and a function over a variable in tests.
func NewWindow(now func() time.Time) *Window {
	return &Window{now: now}
}

// Open mints a fresh token, opens the window for ttl, and returns both the token and the instant it
// stops being accepted.
//
// Calling it while a window is already open replaces that window: the previous token becomes
// worthless immediately and the attempt count starts again. That is what the owner means by pressing
// the button a second time, and it is why the count cannot outlive the secret it was counting
// against.
//
// # ttl is CLAMPED to [MaxTTL], never exceeded
//
// A ttl over the ceiling is silently reduced to it rather than refused, and that is deliberate on a
// signature with no error to return. Clamping fails in the safe direction - the window is open for
// less time than was asked for, never more - and the caller is TOLD, because the expiry this returns
// is the one that will be enforced and is the one the QR payload carries. So a Mac app that asks for
// an hour gets a five-minute window and a code that says five minutes; the phone and the bridge agree,
// and the only thing lost is the request nobody should have made.
//
// Refusing instead would mean an error return, and an error return on this call means a pairing panel
// that can fail to open - a worse outcome for the owner than a shorter window, in service of the same
// bound.
//
// A ttl of zero or less produces a window that is already past its expiry, which [IsOpen] and
// [Consume] both report as closed. That is fail-closed and is left alone rather than clamped upward:
// nothing should be inventing a window duration on the caller's behalf.
//
// # Why the expiry is truncated
//
// The returned expiry is already Truncate(time.Second).UTC() - the exact form Payload.Canonical
// produces - because this value is about to become a payload field, and the wire carries whole
// seconds. Truncating here rather than letting Encode do it means the deadline this window enforces
// and the deadline the QR code advertises are the same instant, not two that differ by up to a
// second. Rounding DOWN is the safe direction of the two: the window is never open a moment longer
// than what the phone was told.
func (w *Window) Open(ttl time.Duration) ([32]byte, time.Time) {
	// The ceiling, before anything else happens, so no path below can be reached with a longer one.
	if ttl > MaxTTL {
		ttl = MaxTTL
	}

	var token [32]byte
	// As of Go 1.24 crypto/rand.Read never returns an error - it panics if the system source fails -
	// so this err is checked for the reader of this code rather than for the runtime. Panicking is
	// also the only honest response available: this function has no error to return, and continuing
	// with a token that is not random would mean a window anyone can walk through.
	if _, err := rand.Read(token[:]); err != nil {
		panic("enroll: the system random source is unavailable: " + err.Error())
	}

	w.mu.Lock()
	defer w.mu.Unlock()
	w.token = token
	w.expiry = w.now().Add(ttl).Truncate(time.Second).UTC()
	w.attempts = 0
	w.open = true
	return token, w.expiry
}

// Close shuts the window. It is idempotent, and it is what the Mac app calls when the owner closes
// the pairing panel.
func (w *Window) Close() {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.closeLocked()
}

// IsOpen reports whether a token would still be accepted right now.
//
// It consults the clock, so a window that has run out reports itself closed without anybody having
// tried the token - and, in passing, forgets it. A caller polling this to decide whether to keep
// drawing the QR code is the reason it is not merely a field read.
func (w *Window) IsOpen() bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.liveLocked()
}

// Consume spends the token: nil exactly once, for the right bytes, inside the window.
//
// Success closes the window. A wrong token counts against maxAttempts and closes it on the fifth.
// Either way, a caller that gets nil back is the only caller that will.
func (w *Window) Consume(token []byte) error {
	w.mu.Lock()
	defer w.mu.Unlock()

	if !w.open {
		return refused{causeClosed}
	}
	if !w.now().Before(w.expiry) {
		// Refused AT the expiry, not after it: the payload states that instant as when the offer
		// stops being accepted, and the phone will have stopped offering by then.
		w.closeLocked()
		return refused{causeExpired}
	}
	// ConstantTimeCompare, and its whole slice against the whole argument. It returns 0 on a length
	// mismatch as well, which is the wanted answer here - the length of the token is public, and a
	// caller sending the wrong number of bytes has not sent the token.
	if subtle.ConstantTimeCompare(w.token[:], token) != 1 {
		w.attempts++
		if w.attempts >= maxAttempts {
			w.closeLocked()
		}
		return refused{causeToken}
	}
	w.closeLocked()
	return nil
}

// liveLocked reports whether the window is open, closing it first if its time has passed.
func (w *Window) liveLocked() bool {
	if !w.open {
		return false
	}
	if !w.now().Before(w.expiry) {
		w.closeLocked()
		return false
	}
	return true
}

// closeLocked closes the window and forgets the token.
//
// The token is zeroed rather than merely marked unusable. It buys nothing against an attacker who
// can already read this process's memory, and it costs one assignment; what it does buy is that the
// secret is not sitting in a live heap object for the rest of the bridge's uptime, where a core
// dump, a crash reporter or a future debug handler would find it. The only copy that should outlive
// the window is the one on the owner's screen, which they can close.
func (w *Window) closeLocked() {
	w.open = false
	w.token = [32]byte{}
	w.expiry = time.Time{}
	w.attempts = 0
}
