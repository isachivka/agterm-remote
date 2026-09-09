package enroll

import (
	"bytes"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"sync"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/keys"
	"github.com/isachivka/agterm-remote/bridge/internal/pinning"
	"github.com/isachivka/agterm-remote/bridge/internal/trust"
)

// VerbEnroll is the only verb this handler answers, and it is here so the wire word exists once.
//
// A verb field on a protocol with one verb looks redundant and is not: it is the room a second
// exchange would need - "what is your name", "here is a new certificate" - without a new ALPN
// protocol or a positional format. Anything else is refused exactly like a wrong token, which is
// deliberate: an unauthenticated caller learns "no" and never which of its guesses was structural.
const VerbEnroll = "enroll"

// maxRequestBytes is the ceiling on the one line this handler reads.
//
// A real request is about 800 bytes: a P-256 certificate is roughly 500 in DER, a third more in
// base64, plus 44 for the token and a short name. 64 KiB is far above that and far below anything
// that costs this laptop something, and it is a CEILING ON AN ALLOCATION MADE FOR A STRANGER - the
// caller on the other end of this read has presented no certificate and spent no token. The reader
// stops at the cap rather than buffering to the newline, so a caller that sends a gigabyte is
// holding one connection and 64 KiB, not memory of their choosing.
const maxRequestBytes = 64 << 10

// exchangeTimeout bounds the whole exchange - the read, the reply, and everything between.
//
// One deadline for both directions rather than a read deadline, because the connection is anonymous:
// a caller that sends a partial line and then stalls, and a caller that never reads the reply, cost
// exactly the same thing and must both be released on the same clock.
//
// # THIS IS THE KNOB THAT BOUNDS AN ANONYMOUS BRANCH, and it is not the read ceiling
//
// While a window is open, anybody can hold an enrolment connection. Each one costs a goroutine, a
// descriptor and a read buffer, and **the number that can be alive at once is a RATE TIMES THIS
// DEADLINE** - nothing else in the design caps it, because the listener's concurrency semaphore is
// released before this branch is dispatched and must be, or twelve stalling strangers would refuse
// the owner's own handshake.
//
// That product was measured rather than reasoned about. At about 5,400 completed handshakes a second
// on loopback, ten seconds implied roughly 54,000 live connections at about 19 KB each - a gigabyte,
// plus 54,000 goroutines and descriptors. Two seconds implies about 10,800 and 200 MB. **No phone
// needs ten seconds to write one line onto a connection whose handshake has already completed** - the
// whole request is under a kilobyte - so the four fifths bought nothing at all.
//
// The read ceiling looks like the same lever and is not, which is worth writing down because it is
// the one somebody reaches for first. Measured: a silent holder costs 19,188 bytes, while a caller
// that sends 60 KiB with no newline costs 155,074. So maxRequestBytes is an eight-fold lever against
// a caller who SENDS, and does nothing whatever about the silent holder - which is the shape that
// actually arrives, because it is free. The deadline is the only limit that bounds both.
//
// A concurrency cap on this branch is the other obvious answer and is deliberately refused: it would
// be shared with the only caller who NEEDS this branch, so any cap, however generous, is a pairing
// lockout available on demand - the second invariant internal/listener exists to forbid.
const exchangeTimeout = 2 * time.Second

// refusalText is the ONE thing a refused caller is ever told, and the whole point is that it says
// nothing else.
//
// [Window.Consume] returns a single [ErrRefused] over three unexported causes - no window, an
// expired window, the wrong bytes - precisely so that a handler cannot relay window state to a
// stranger. This handler adds four more failures of its own (an oversized line, a request that does
// not parse, a verb it does not serve, a certificate it cannot read) and every one of them produces
// this same string. A caller cannot learn whether the owner has a pairing panel open, whether their
// token had the right shape, or how far into the exchange they got.
//
// The cause is not lost - it is logged locally, under the rule in [Serve] - it just never crosses
// the wire.
const refusalText = "enrolment refused"

// Request is what the phone sends: exactly one line of JSON, and then it waits.
//
// Token and Certificate are STANDARD, PADDED base64 - the same alphabet as the QR payload, for the
// same reason, which is that the Android side decodes with java.util.Base64.getDecoder() and that
// decoder refuses both the URL-safe alphabet and missing padding.
type Request struct {
	Verb string `json:"verb"`
	// Token is the 32 bytes off the QR code. It is worthless a moment after it works.
	Token string `json:"token"`
	// Certificate is the phone's own certificate, in DER. It is public - it is the thing the bridge
	// is being asked to pin - so nothing confidential travels on this anonymous connection.
	Certificate string `json:"certificate"`
	// Name is what the owner will see beside this phone in the menu. Optional, bounded, and checked
	// by keys.Label; see [enrol].
	Name string `json:"name"`
}

// Reply is the one line back, and then the connection closes.
type Reply struct {
	OK bool `json:"ok"`
	// Certificate is the BRIDGE's own certificate, in DER, and it is why a successful enrolment is
	// worth a reply at all. The phone arrived holding a fingerprint off the QR code - enough to
	// recognise this laptop, not enough to pin it the way [pinning.ClientConfig] pins, which is by
	// exact bytes. This is those bytes, delivered over the connection that fingerprint just
	// authenticated.
	Certificate string `json:"certificate,omitempty"`
	// Fingerprint is the fingerprint of the certificate that was just PINNED - the caller's own, not
	// the one in the field above. **It is the field the two screens compare**, and that is what it is
	// for; the neighbouring field invites the other reading, which is why it is spelled out here.
	//
	// The phone can compute either digest for itself, so neither is informative as data. What it
	// cannot know is what the bridge actually STORED, and this is that: the exact string the owner's
	// menu will show for this phone. A phone whose certificate arrived truncated or re-encoded sees a
	// digest that is not its own here, rather than discovering it on the next connection as a failed
	// handshake it cannot explain.
	Fingerprint string `json:"fingerprint,omitempty"`
	// Name is the name AS STORED, which is not always the name that was sent: keys.Label trims
	// surrounding whitespace, so a phone that sent " my phone " is pinned as "my phone".
	//
	// Echoed for the same reason Fingerprint is. Without it the phone displays what it sent and the
	// owner's menu displays what was kept, and the two screens the pairing flow asks a person to
	// compare would disagree over a difference neither of them can see.
	Name string `json:"name,omitempty"`
	// Error is refusalText or nothing. It is never a description of what went wrong.
	Error string `json:"error,omitempty"`
}

// Handler is one bridge's enrolment side: the window a token came from, the store a phone is pinned
// into, this bridge's own certificate, and the callback the Mac app's menu listens on.
//
// # Why this is a type rather than a function taking five arguments
//
// It was a function, with the refusal counter as a package variable. That is wrong in a way no test
// caught, because every assertion about the counter was "no more than one line" and a shared counter
// satisfies that too: **two bridges in one process shared one count and one interval**, so a hundred
// refusals across two of them printed nothing at all, and each would have been silently rate-limiting
// the other's diagnostics. Process-wide state for a per-bridge decision is a bug that only shows up
// as an equality, so the tests now assert equalities.
//
// One Handler per bridge, and the counter is a field. Nothing about enrolment is process-global.
type Handler struct {
	window *Window
	store  *trust.Store
	own    *x509.Certificate
	paired func(trust.Peer)

	// refusals is the rate limit over the ONE thing an anonymous caller can make this write - see
	// [refusalRate]. Per handler, deliberately.
	refusals *refusalRate
}

// NewHandler builds the enrolment side for one bridge. Its Serve method is the argument
// listener.New takes.
//
// paired may be nil. own is this bridge's own certificate, which a successful enrolment returns so
// the phone can pin the bytes behind the fingerprint it read off the QR code.
//
// # The clock comes from the window
//
// [refusalRate] needs one, and taking a fifth argument for it would let a caller hand the two halves
// of this package two different opinions about what time it is. window.now is read once here and
// never written after [NewWindow], so this shares it: a test that injects a clock into the window
// gets the same clock in the rate limiter for free, and production passes time.Now once.
func NewHandler(window *Window, store *trust.Store, own *x509.Certificate, paired func(trust.Peer)) *Handler {
	return &Handler{
		window:   window,
		store:    store,
		own:      own,
		paired:   paired,
		refusals: &refusalRate{now: window.now},
	}
}

// Flush reports whatever the rate limiter is still holding, and is what a bridge calls on the way
// out.
//
// Without it a burst that STOPS is never reported: the aggregate is written by the next refusal after
// the interval, so five thousand held refusals followed by silence produce nothing, and a bridge that
// exits takes its count with it. internal/listener's failureCounter has exactly this problem and
// exactly this answer - `defer s.failures.flush()` in Serve - and this file cites that counter as its
// model, so it owes the same hook.
func (h *Handler) Flush() { h.refusals.flush() }

// Serve runs one enrolment exchange on an already-handshaked connection and closes it.
//
// # Where this is reached from, which is the whole of its threat model
//
// A connection arrives here only if it negotiated [ProtoEnroll], which under [ServerConfigFor] is
// offered ONLY while the owner has a window open, and which the listener dispatches to this
// function and to nothing else. So the caller presented no certificate and this handler must assume
// it is a stranger - but a stranger who is talking to a laptop with a pairing panel visibly open on
// it, which is what makes an anonymous branch acceptable at all.
//
// # One line in, one line out, and no state between two calls
//
// There is no session here, no retry within a connection and nothing kept. A caller that gets it
// wrong reconnects, which costs them a TLS handshake and costs this process a bounded slot in the
// listener's semaphore. That is deliberately worse for them than a loop inside one connection would
// be, and it means the entire exchange is: read at most 64 KiB up to a newline, write one line,
// close.
//
// # What a refused caller costs, stated as a bound
//
//   - No disk write. Nothing reaches the trust store before [Window.Consume] has returned nil.
//   - No agterm round trip, no request parser beyond this file's own, no handler.
//   - Bounded memory and bounded time: 64 KiB and ten seconds.
//   - **A log line only for a refusal that actually cost the caller something. Everything else is
//     counted and reported as an aggregate at most once per [refusalReportEvery].**
//
// # The bound this comment used to claim, and did not have
//
// It said "at most maxAttempts + 1 lines for the life of a window", reasoning that only a spent
// attempt is logged and that five spend the window. **That was false, and it was false in the
// direction that matters.** Two of Consume's three causes burn no attempt - no window is open, and
// the window expired - so the counter never moved and the bound never applied. Measured: hold
// connections that negotiated `agterm/enroll-1` while a window was open, let the window go away
// (five wrong tokens, the owner closing the panel, or plain expiry), then flush them - 200
// connections produced 200 log lines against one window, with no attempt spent. The listener's
// twelve-slot semaphore is the only brake and it is released before this branch is dispatched.
//
// That is an anonymous write primitive against the owner's disk, which is exactly what
// internal/listener refuses to hand out and the reason it COUNTS failed handshakes instead of
// logging them. This file now does the same thing, and the split is by WHAT THE REFUSAL COST THE
// CALLER rather than by where in the exchange it happened:
//
//   - **Nothing at all** - it never reached the gate (an oversized line, a request that will not
//     parse, a verb this does not serve, a token that is not base64), or it reached a gate that was
//     already shut (no window, an expired window). Repeatable without limit, so: counted, never
//     written per attempt. See [refusalRate].
//   - **An attempt** - the window was live and the token was wrong. Bounded at maxAttempts for the
//     life of that window, and the window only exists while the owner is at their Mac. Logged, with
//     the cause, because this is the one they can act on.
//   - **The window itself** - Consume returned nil and something after it failed. At most one per
//     window, because Consume succeeds at most once. Logged, with the cause.
//
// # The cause has to be TRANSLATED, not printed
//
// [ErrRefused] hides the cause from Error() on purpose, so `log.Printf("%v", err)` on a refusal
// prints `enroll: enrolment refused` and tells the owner nothing. The first version of this file did
// exactly that while claiming to give them the diagnosis. [causeText] is the deliberate accessor
// [Window] anticipated: it lives inside this package, where the decision about what an owner may be
// told belongs, and it is reached from the logging path only - never from anything that builds a
// [Reply].
//
// The cause is logged, never the caller's bytes: no name, no certificate, no fingerprint, no
// validity dates. A fingerprint identifies the owner's phone and this log is a file on a laptop.
func (h *Handler) Serve(conn net.Conn) {
	defer conn.Close()
	// Both directions, before anything is read. A connection that stalls mid-line and one that never
	// reads its answer are the same anonymous cost.
	_ = conn.SetDeadline(time.Now().Add(exchangeTimeout))

	reply, cause := h.enrol(conn)
	switch {
	case cause == nil:
		// Either it worked, or it failed on a path that costs the caller nothing AND tells the owner
		// nothing - enrol returns nil for those and they are not even counted, because a caller that
		// cannot reach the gate cannot be described to the owner as an attempt to pair.
	case errors.Is(cause, causeClosed), errors.Is(cause, causeExpired):
		// Reached the gate and found it shut. Costs the caller nothing and is therefore repeatable
		// without limit, so it is COUNTED and reported as an aggregate. The owner still learns that
		// somebody is knocking, which is worth knowing; they do not learn it once per knock.
		h.refusals.record()
	default:
		// A spent attempt, or a spent window. Bounded by the window, which a person opened.
		log.Printf("enrolment refused: %s", causeText(cause))
	}
	// The reply is written on every path, including the ones that failed before anything was
	// understood. A caller that is refused must be refused in words rather than by a dropped
	// connection: the phone can then say "that code did not work" instead of "the laptop is
	// unreachable", which are different problems for the person holding it. It is one line of fixed
	// size, so it is not a write primitive.
	_ = json.NewEncoder(conn).Encode(reply)
}

// refusal is the reply every failure produces. Identical bytes on every path, by construction
// rather than by four call sites agreeing.
func refusal() Reply { return Reply{OK: false, Error: refusalText} }

// refusalReportEvery is how often the count of cost-nothing refusals is written, at most.
//
// The same interval internal/listener uses for failed handshakes, and for the same reason: it is the
// resolution at which "somebody is trying to pair and cannot" is useful to a person, and any finer
// resolution is a write whose rate a caller chooses.
const refusalReportEvery = time.Minute

// refusalRate counts the refusals that cost their caller nothing and writes one line per interval,
// however many there were.
//
// # Why this is package state rather than a field on something
//
// It has to be shared by every enrolment connection in the process, and [Serve] is handed a
// connection, a window, a store and a certificate - all of which are the right lifetime for an
// exchange and the wrong one for a log-rate decision. The [Window] would survive long enough, but a
// counter about the LOG has no business inside the type whose three security properties are the whole
// of this package's argument, and which two separate guards read.
//
// So: one counter, for the whole process, next to the only function that writes to the log. The log
// itself is process-global, so a rate limit over it is as well. It holds no secret, nothing about who
// called, and nothing that survives a restart.
//
// Safe for concurrent use. Enrolment connections are served one goroutine each.
type refusalRate struct {
	// now is injected, like [Window]'s. Nothing in this file calls time.Now() - the elapsed span this
	// reports is a value a test has to be able to assert in microseconds rather than approximate by
	// sleeping, and the first version had its own test poking at windowEnd to get around that.
	now func() time.Time

	mu    sync.Mutex
	start time.Time
	count int
}

// record counts one refusal, and reports the total when the interval has rolled.
//
// Reported on the NEXT refusal after the interval rather than on a timer, which is what
// internal/listener's failureCounter does and is the shape that needs no goroutine and no shutdown
// hook. Its cost is that a burst which stops has its tail reported by whatever knocks next, or not at
// all - acceptable for a diagnostic, and the alternative is a ticker running for the life of a bridge
// that will usually count nothing.
//
// **The count is the only thing written.** Not the address, not the cause, not which of the two
// shut-gate causes it was: a caller must not learn what the owner's window is doing, and the owner
// does not need a stranger's bytes in a file to know somebody is knocking.
//
// The span reported is the ELAPSED time since the last report, not the interval. Those are not the
// same number and the first version printed the interval: 201 refusals spread over an hour were
// reported as "in the last 1m0s", because the aggregate is written by the next refusal after the
// interval and that refusal can arrive whenever it likes. A count over a stated span that is not the
// span it covers is worse than no span.
func (r *refusalRate) record() {
	line, elapsed, count := r.tally()
	if !line {
		return
	}
	// Outside the mutex. Writing to the log holds a lock of its own and may reach a file, and the
	// enrolment connections behind this counter are one goroutine each.
	report(count, elapsed)
}

// tally counts one refusal and reports whether the caller should now write a line, with the numbers
// to write. Under the mutex; the writing is not.
func (r *refusalRate) tally() (report bool, elapsed time.Duration, count int) {
	r.mu.Lock()
	defer r.mu.Unlock()

	now := r.now()
	r.count++
	if r.start.IsZero() {
		r.start = now
		return false, 0, 0
	}
	if now.Sub(r.start) < refusalReportEvery {
		return false, 0, 0
	}
	elapsed, count = now.Sub(r.start), r.count
	r.count = 0
	r.start = now
	return true, elapsed, count
}

// flush reports whatever is held, whatever the interval says, and forgets it. Idempotent: a second
// call with nothing counted writes nothing.
func (r *refusalRate) flush() {
	r.mu.Lock()
	count := r.count
	var elapsed time.Duration
	if !r.start.IsZero() {
		elapsed = r.now().Sub(r.start)
	}
	r.count = 0
	r.start = time.Time{}
	r.mu.Unlock()

	if count == 0 {
		return
	}
	report(count, elapsed)
}

// report is the one line, written from one place so that record and flush cannot drift into saying
// different things about the same event.
func report(count int, elapsed time.Duration) {
	log.Printf("enrolment: %d attempts in the last %s met a window that was not open",
		count, elapsed.Round(time.Second))
}

// causeText is what the OWNER is told, in their own log, about a refusal they can act on.
//
// It exists because [ErrRefused] deliberately makes Error() say nothing: printing a refusal gives
// `enroll: enrolment refused`, which is the correct answer for the wire and useless in a log. This is
// the "deliberate accessor on this package" the note on [ErrRefused] calls for - the decision about
// what an owner may be told, made inside the package that knows the causes.
//
// **Never reachable from a [Reply].** The only caller is [Serve]'s logging path, which is what keeps
// the split between "the owner may know why" and "the caller may not" a property of who calls this
// rather than of anybody remembering not to.
func causeText(err error) string {
	switch {
	case errors.Is(err, causeToken):
		return "the token did not match the one on screen"
	case errors.Is(err, causeExpired):
		return "the window had already expired"
	case errors.Is(err, causeClosed):
		return "no window was open"
	}
	// A failure of this handler's own rather than the window's. Most of these are sentences written in
	// this file; **one of them is not, and the claim that none of them is was wrong.**
	//
	// The certificate-parse failure wraps x509's error, and some of x509's errors quote what they were
	// given - `x509: cannot parse URI %q` puts a caller-chosen string into the owner's log. That was
	// measured, not theorised.
	//
	// It is kept, with the claim corrected instead of the mechanism, because the reason to have it is
	// good and the exposure is small on four counts that all have to hold: %q percent-escapes, so a
	// newline cannot be forged and the line cannot be split; the string is a URI x509 already refused
	// to parse, so it is bounded by the certificate that carried it; **it takes a SPENT TOKEN to get
	// here at all**; and it is capped at one line per window, because Consume succeeds at most once.
	// Take any one of those away - a caller reaching this without the token, or an unescaped format -
	// and the fallback has to go.
	return err.Error()
}

// enrol is the exchange itself. It returns the reply to send, and the cause to log LOCALLY - nil for
// every failure a caller can repeat without spending an attempt.
//
// The order of the steps is the security of this file:
//
//  1. Read a bounded line.
//  2. Parse it, strictly.
//  3. Consume the token. **Nothing below this line runs for a caller who does not hold it, and
//     nothing above it writes anything, anywhere.**
//  4. Only then look at what they sent, and only then write to the trust store.
func (h *Handler) enrol(conn net.Conn) (Reply, error) {
	line, err := readLine(conn, maxRequestBytes)
	if err != nil {
		// Includes the oversized line and the caller that hung up mid-request. Repeatable, so it is
		// not logged and - because the read stopped at the cap - it was never buffered either.
		return refusal(), nil
	}

	dec := json.NewDecoder(bytes.NewReader(line))
	// Refusing unknown fields, exactly as api.Decode does. A field this bridge does not understand
	// is either a client from a future it cannot serve or something being smuggled past the reader,
	// and both deserve a refusal rather than a silent ignore. The ALPN identifier is where a real
	// protocol change is announced - see [ProtoEnroll] - so a new field is never how a newer phone
	// talks to an older bridge.
	dec.DisallowUnknownFields()
	var req Request
	if err := dec.Decode(&req); err != nil {
		return refusal(), nil
	}
	if req.Verb != VerbEnroll {
		return refusal(), nil
	}

	// The token is decoded but NOT inspected: no length check, no emptiness check, no comparison of
	// any kind. Consume owns that decision and makes it in constant time, and a length check here
	// would be a second, variable-time answer about the same secret. Base64 that will not decode is
	// not an attempt at the token, it is a malformed field, so it stops here without touching the
	// counter.
	given, err := base64.StdEncoding.DecodeString(req.Token)
	if err != nil {
		return refusal(), nil
	}

	// **THE GATE.** One call, constant time, single use, and the only thing in this file that can
	// spend an attempt. Everything above is repeatable and free; nothing below happens without it
	// having returned nil.
	if err := h.window.Consume(given); err != nil {
		// The cause travels no further than this return. ErrRefused's message is what the caller
		// gets, and the unexported cause behind it is what the owner's log gets - which is the split
		// the error type exists for.
		return refusal(), err
	}

	// From here the window is SPENT. A failure below is not a caller who guessed wrong, it is a
	// caller who held the token and sent something unusable, so each of these logs - bounded at one
	// line, because Consume succeeds at most once per window - and the owner has to open a new one.
	der, err := base64.StdEncoding.DecodeString(req.Certificate)
	if err != nil {
		return refusal(), errors.New("the certificate field is not standard base64")
	}
	// Parsed BEFORE anything is stored, and stored only if it parsed. The trust store keeps whatever
	// bytes it is given, so this is the only place that can refuse a certificate the TLS layer would
	// later have to drop - see enroll.parseAll, which is what would silently ignore it.
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		return refusal(), fmt.Errorf("the certificate does not parse: %w", err)
	}
	// **Parsing is not usability, and `ok:true` on an unusable certificate is a lie with a cost.**
	//
	// This is the only place in the design that can say no. Everything downstream keeps bytes or
	// compares them: the trust store stores what it is given, parseAll drops what will not parse, and
	// pinnedPeers compares the exact DER. So a certificate that parses but can never complete a
	// handshake gets pinned, the phone is told it paired, it cannot connect - and because pairing is
	// Replace, **the owner's real phone has already been evicted** by the one that cannot work. That
	// is the worst outcome available here, and it was reachable: an already-expired certificate and one
	// whose public-key algorithm this build does not implement were both accepted.
	//
	// Two checks, both cheap, both about whether this certificate can ever authenticate anybody:
	if cert.PublicKey == nil {
		// x509 leaves PublicKey nil for a public-key algorithm it does not implement, and returns no
		// error for it. There is nothing here to prove possession of, so no handshake can ever
		// succeed.
		return refusal(), errors.New("the certificate carries a public key of an algorithm this build cannot use")
	}
	now := time.Now()
	if now.Before(cert.NotBefore) || now.After(cert.NotAfter) {
		// Checked at the moment of enrolment, against the certificate the caller is asking to have
		// pinned. pinnedPeers checks this again on every handshake - it has to, because time passes -
		// but a certificate that is ALREADY outside its window can never be accepted by it, so pinning
		// it means promising something that is false before the reply is even written.
		//
		// The dates are not logged. They are the caller's, they are bounded, and they are also not
		// something the owner needs in order to act.
		return refusal(), errors.New("the certificate is outside its own validity window")
	}

	name, err := peerName(req.Name)
	if err != nil {
		return refusal(), fmt.Errorf("the name: %w", err)
	}

	peer := trust.Peer{
		Fingerprint: pinning.Fingerprint(cert),
		// cert.Raw rather than der: identical bytes today, and the one of the two that is defined as
		// "what this certificate actually is" if x509 ever normalises anything. It is what the
		// pinning verifier compares against, so it is what gets stored.
		CertificateDER: cert.Raw,
		Name:           name,
		PairedAt:       time.Now().UTC(),
	}
	// **Replace, not Add, and the choice is the product decision rather than a detail.**
	//
	// This bridge accepts one phone, and trust.Store puts that rule in which method pairing calls -
	// Replace keeps exactly one peer, Add keeps every one and replaces by fingerprint. Replace is
	// what "pair my phone" means for v1: the owner scanned a code on their laptop, and what they
	// expect afterwards is that this phone reaches it. Add would quietly build a list nothing in
	// this milestone can show them or unpair from, so an old phone - or a phone they enrolled once
	// while testing, or one lost since - would keep working with no screen anywhere that says so.
	// Allowing a second phone is then a screen plus this one word, which is the whole reason the
	// store is a list.
	//
	// Written before the callback and before the reply, so the caller is told nothing that the disk
	// does not already hold: trust.Store adopts a list only once its write returned nil, so an error
	// here means nothing was pinned.
	if err := h.store.Replace(peer); err != nil {
		return refusal(), fmt.Errorf("the trust store could not be written: %w", err)
	}
	if h.paired != nil {
		// The Mac app's menu. Called with the peer as stored, after the write, so what the menu
		// shows is what the bridge will accept and not what it was asked to accept.
		h.paired(peer)
	}

	return Reply{
		OK: true,
		// The bridge's own certificate. There is nothing to guard here - it is public, it is what
		// this connection already proved possession of, and the phone is about to pin it.
		Certificate: base64.StdEncoding.EncodeToString(h.own.Raw),
		Fingerprint: peer.Fingerprint,
		// From the peer rather than from the request: keys.Label may have trimmed it, and what the
		// phone should show is what the menu will show.
		Name: peer.Name,
	}, nil
}

// peerName checks the label the owner will see, and it is keys.Label doing it rather than a second
// copy of the same rules.
//
// This string is written to the trust store and read back by a menu, so it is exactly the "a name a
// person will see" case that validator exists for: valid UTF-8, no control character, at most 64
// runes, trimmed. A control character in a name would be a caller writing escape sequences into a
// file the owner's tools will print.
//
// Empty is allowed and means "no name", because Name is optional and a phone that sends none must
// still be able to pair - the menu falls back to the fingerprint. keys.Label refuses empty, which is
// right for a session label and wrong here, so absence is answered before it is called rather than
// by loosening it for both callers.
func peerName(name string) (string, error) {
	if name == "" {
		return "", nil
	}
	return keys.Label(name)
}

// readLine reads one newline-terminated line, and stops at max rather than at the newline.
//
// The distinction is the whole function. A reader that buffers until the newline and checks the
// length afterwards has already allocated whatever the caller sent; this one can never hold more
// than max plus one chunk, whatever arrives. The newline itself is not returned, and nothing after
// it is read - there is exactly one request per connection.
//
// **The chunk that carries the newline is returned without the cap being consulted**, because the
// scan for the newline runs before the length check. So a returned line can be up to max + 1023
// bytes - 64 KiB and change - rather than exactly max. This is inherited from internal/listener's
// reader of the same shape and is harmless: the overshoot is one fixed chunk, it cannot be made to
// repeat, and every caller here treats the result as one JSON line whose own parse is the real
// bound. Documented rather than tightened, so the next reader does not take max literally and does
// not "fix" a reader that two packages share the shape of.
func readLine(conn net.Conn, max int) ([]byte, error) {
	buf := make([]byte, 0, 1024)
	chunk := make([]byte, 1024)
	for {
		n, err := conn.Read(chunk)
		for i := 0; i < n; i++ {
			if chunk[i] == '\n' {
				return append(buf, chunk[:i]...), nil
			}
		}
		if n > 0 {
			buf = append(buf, chunk[:n]...)
			if len(buf) > max {
				return nil, errors.New("enroll: the request is over the ceiling")
			}
		}
		if err != nil {
			return nil, err
		}
	}
}
