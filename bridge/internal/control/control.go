// Package control is the LOCAL door: a unix socket in the bridge's own config directory, serving a
// short closed set of verbs to a person sitting at this Mac.
//
// # Why this exists at all
//
// Two reasons, and the second one arrived later and is now the larger.
//
// The first: the owner asked for an undo they can run from the computer, as an agterm palette
// command, for the times the phone is not in their hand. The width setting lives in the bridge's
// store, and the window belongs to the machine they are sitting at.
//
// The second: **this is the seam between the two halves of the product.** The Mac app owns the
// address, the onboarding and the QR panel; the bridge owns the identity, the trust store and the
// enrolment window. Neither can do the other's job, and they meet here - `status` for what to draw,
// `pair-open` for the code to draw, `pair-close` when the panel goes away, `unpair` when the owner
// says so.
//
// # Why it is not a second writer of the store
//
// **A separate process editing resize-cache.json races the running bridge and can lose the restore
// point** — the file that once held the only record of the owner's window geometry. So this asks the
// bridge to restore rather than doing it behind its back: the request goes through the same handler
// the phone's own "off" press goes through, so there is one writer, one code path, and one place where
// the restore point is consumed.
//
// The phone then corrects itself for free. Its toggle renders whatever the bridge reports, and the
// poll re-reads that on every reply, so a restore performed from the laptop unpresses the button on
// the phone within one poll without either end being told about the other.
//
// # Why a unix socket is safe here, in terms of who can open it
//
// **Filesystem permission IS the authentication, and there is nothing else to authenticate.**
//
//   - The socket lives inside the bridge's config directory, which is mode 0700, and the socket itself
//     is 0600. To connect you must be able to traverse that directory and open that file, which on this
//     machine means being the uid that owns it — or root, who has already won.
//   - **Nothing listens on a port.** A unix socket has no address off this machine: it cannot be
//     reached from the LAN, from the router, or from the internet, whatever the network is doing.
//   - The pinned mTLS front door is untouched. This adds no TLS, no certificate, no trust decision,
//     and no path by which a remote caller reaches anything. It is not a second front door; it is a
//     door with no outside.
//
// So there is no credential to check, and inventing one would be worse than useless: it would be a
// secret on the same disk, readable by exactly the people who can already open the socket.
//
// # That permission now carries more weight than it did when the paragraph above was written
//
// **`pair-open` opens the enrolment window, and nothing else in the repository can.** That window is
// the single moment this bridge will talk to a phone it has never met — see internal/enroll, whose
// entire argument for having an anonymous branch at all is "a window is seconds of the bridge's life
// and requires a person at the Mac". enroll.MaxTTL caps the seconds. **THIS SOCKET IS WHAT MAKES THE
// SECOND CLAUSE TRUE**, and it is the only thing that does.
//
// So a widened mode here is not a convenience regression. It hands anybody with an account on this
// machine the ability to open an enrolment window while the owner is away from the screen, and the
// pairing panel they would otherwise have had to walk past does not exist to stop them. The mode is
// set explicitly rather than left to the umask, and it is read back off the disk by
// TestTheDoorThatOpensAnEnrolmentWindowIsOwnerOnly rather than assumed.
//
// **The state directory's 0700 is load-bearing for this socket too**, not only for the key material
// beside it. Between net.Listen creating the socket and the os.Chmod below, the file briefly carries
// whatever the umask allowed — under `umask 0` that is 0777, for as long as those two lines take. The
// gap cannot be closed from inside this package, because a unix socket has no mode argument on the
// call that creates it; what closes it is that the containing directory is 0700, so nobody else can
// traverse to the file during the window or at any other time. main chmods that directory explicitly,
// for exactly this reason among others.
//
// # One request per connection
//
// **Connect, write one line, read one line, close.** This door does not pipeline: whatever follows the
// first newline on a connection is never read, and the connection is closed as soon as the reply is
// written. A caller that sends three verbs gets one answer and the other two are discarded in
// silence — pinned by TestOnlyTheFirstRequestOnAConnectionIsServed so that whoever writes the Mac
// panel finds it here rather than in a debugger.
//
// That is deliberate rather than a limitation, and it is the convention the far end already follows:
// agterm's own control socket is one request per connection, and this is a local socket where a
// connection costs a syscall pair and no handshake. The pinned front door is the opposite — it keeps
// a connection and reads requests in a loop — because there a reconnect costs a TLS handshake over a
// mobile network, which is the one thing that feature cannot afford. Neither answer is right for both.
//
// # The verbs
//
// Five, as a closed set, and the set is the interface: there is no dispatch table a future verb can be
// added to from outside this file.
//
//   - `restore` — the undo. It cannot resize to a width, cannot calibrate, cannot read a session and
//     cannot type. A local door that could do those things would be a second way to reach capabilities
//     the pinned door spends a great deal of care rationing.
//   - `status` — what the menu bar is drawn from: the bound address, the paired phones, whether agterm
//     is answering, and what the enrolment window is doing.
//   - `pair-open` — mint a code. **The only thing anywhere that can open an enrolment window.**
//   - `pair-close` — shut it, which is what closing the panel means.
//   - `unpair` — drop a paired phone.
//
// The last two are the destructive ones, and both say what they DID rather than `ok`: unpairing is
// total in v1, because trust.Store.Replace keeps exactly one peer, so `ok` alone would let an owner
// believe they had removed one of several.
//
// # What this door does NOT get to do
//
// It cannot enrol a phone. `pair-open` opens a window and hands back a code; the phone still has to
// arrive over the pinned listener, negotiate `agterm/enroll-1`, and spend the token against
// enroll.Window.Consume. Nothing here writes to the trust store except `unpair`, and nothing here can
// add to it at all.
//
// It also has no opinion about the ADDRESS. It is handed the one the bridge is bound to and puts it in
// the code unexamined — see [Pairing.Listening], where the reason a validator there would be actively
// harmful is written down.
package control

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/enroll"
	"github.com/isachivka/agterm-remote/bridge/internal/trust"
)

// SocketName is the file inside the bridge's config directory. Named for what speaks it.
const SocketName = "control.sock"

// SocketPath is where the socket lives for a given bridge directory. Exported so the command-line
// client and the bridge cannot disagree about it — a client looking in the wrong place would report
// "the bridge is not running" about a bridge that is.
func SocketPath(dir string) string { return filepath.Join(dir, SocketName) }

// Fit is the part of the bridge this door can reach. **Deliberately two methods and no more**: it can
// ask whether a fit is in force, and it can ask for the window back. There is no way to express
// anything else through this interface, which is a stronger statement than a switch that happens to
// handle one verb today.
type Fit interface {
	// FitInForce reports whether the bridge currently holds a fit.
	FitInForce() bool
	// RestoreFit puts the owner's window back and turns the setting off, through the same path the
	// phone's own off press takes.
	RestoreFit(ctx context.Context) error
}

// request is the whole protocol: a verb and the two arguments the five verbs between them take.
//
// Unknown fields are REFUSED rather than ignored - see [handle] - so a field this build does not
// understand is a refusal instead of a silently dropped argument. That is the same reading
// api.Decode and the enrolment handler take of their own inputs, and it matters most here: a
// `pair-open` carrying a misspelt ttl would otherwise open a window for a duration nobody chose.
type request struct {
	Verb string `json:"verb"`
	// TTLSeconds is how long `pair-open` should hold the window. Clamped to enroll.MaxTTL by the
	// window itself, and the expiry that comes back is the clamped one.
	TTLSeconds int `json:"ttl_seconds,omitempty"`
	// Fingerprint names the phone `unpair` drops. Never a wildcard and never optional.
	Fingerprint string `json:"fingerprint,omitempty"`
}

// response says what happened to a `restore`, including when nothing did.
type response struct {
	OK bool `json:"ok"`
	// Restored is false when there was nothing to undo. **That is not a failure**, and the client
	// prints it as the ordinary outcome it is - see the note on doing nothing honestly.
	Restored bool   `json:"restored"`
	Message  string `json:"message,omitempty"`
	Error    string `json:"error,omitempty"`
}

// errorReply is every refusal that happens before a verb was understood, and the refusal for a verb
// this bridge cannot serve.
//
// Its own type rather than a `response` with the other fields zeroed, because `restored:false` on a
// failed `pair-open` reads as an answer about a window rather than as the absence of one.
type errorReply struct {
	Error string `json:"error"`
}

// statusReply is what the menu bar is drawn from.
type statusReply struct {
	// Listening is the address the bridge was told to serve on - the Mac app's own answer, handed
	// back so the panel and the bridge cannot disagree about where a phone should dial.
	Listening string `json:"listening"`
	// Paired is the phones this bridge will talk to. Never null: an unpaired bridge is the ordinary
	// first state and a menu that iterates over this should not have to special-case it.
	Paired []pairedPeer `json:"paired"`
	// Agterm reports whether agterm is answering. A bridge whose agterm has gone away is running,
	// reachable and useless, and that is a different thing to tell an owner than "not running".
	Agterm bool `json:"agterm"`
	// Window is what the pairing panel needs in order to say why a code stopped working.
	Window windowReply `json:"window"`
}

// pairedPeer is one phone, as the owner sees it. **The certificate is not here.** It is what
// authenticates the phone, it is large, and no menu has anything to do with it.
type pairedPeer struct {
	Fingerprint string `json:"fingerprint"`
	Name        string `json:"name"`
	// PairedAt is Unix seconds, like every other instant on this socket and in the QR payload.
	PairedAt int64 `json:"paired_at"`
}

// windowReply is the enrolment window, and it exists so the Mac app can say WHY a code stopped
// working.
//
// "Expired" and "closed after five wrong attempts" are different things to tell an owner and lead to
// different next moves, and the panel cannot guess. See enroll.Ending, which is where the decision
// about what an owner may be told lives - the anonymous caller on the other side of the enrolment
// handler is told none of this.
type windowReply struct {
	Open bool `json:"open"`
	// ExpiresAt is Unix seconds, and it is the instant the window will ACTUALLY enforce. Absent
	// when nothing is open.
	ExpiresAt int64 `json:"expires_at,omitempty"`
	// AttemptsLeft is how many more wrong tokens this window survives, out of enroll.MaxAttempts.
	// **Only a token actually spent against the window moves it** - a dropped connection or a
	// retried handshake does not - so five is looser than it reads.
	AttemptsLeft int `json:"attempts_left"`
	// Ended is why the last window stopped, and it is absent while one is open and before the first
	// one has ever been opened. See enroll.Ending for the values.
	Ended string `json:"ended,omitempty"`
}

// pairOpenReply is the code, when it dies, and whether it cost somebody else theirs.
type pairOpenReply struct {
	// Payload is the text the QR code carries: standard padded base64 of an enroll.Payload. The Mac
	// app draws it and does not parse it.
	Payload string `json:"payload"`
	// ExpiresAt is Unix seconds, and it is the same instant the payload carries - both come from
	// what enroll.Window.Open returned. See [openWindow].
	ExpiresAt int64 `json:"expires_at"`
	// Replaced is true when a window was ALREADY OPEN and this call took it.
	//
	// enroll.Window.Open replaces rather than refuses: the previous token becomes worthless
	// immediately and the attempt count starts again. That is what an owner means by pressing the
	// button a second time, and it is right. What is not right is doing it SILENTLY - a second panel
	// window kills the first one's code with nothing anywhere saying so, and the person looking at
	// the dead QR scans it, is refused, and has no way to tell that from a broken bridge.
	//
	// So it is reported, and always present rather than omitted when false, because the panel's shape
	// should not depend on the answer. What the Mac app does with it is the Mac app's business - the
	// honest minimum is that the OTHER panel can stop showing a code that no longer works.
	Replaced bool `json:"replaced"`
}

// pairCloseReply says whether there was anything to close. Nothing open is an ordinary answer rather
// than a failure, exactly as a `restore` with no fit in force is.
type pairCloseReply struct {
	OK     bool `json:"ok"`
	Closed bool `json:"closed"`
}

// unpairReply says what it did rather than `ok`.
//
// Remaining is the count of phones still paired, and it is there because **unpairing is total in
// v1**: trust.Store.Replace keeps exactly one peer, so a successful unpair always leaves zero. An
// owner who reads `ok` and assumes they removed one of several is reading a number this reply
// actually gives them.
type unpairReply struct {
	OK        bool   `json:"ok"`
	Unpaired  bool   `json:"unpaired"`
	Remaining int    `json:"remaining"`
	Error     string `json:"error,omitempty"`
}

// The verbs, as a closed set. See the package comment for what each is for.
const (
	VerbRestore   = "restore"
	VerbStatus    = "status"
	VerbPairOpen  = "pair-open"
	VerbPairClose = "pair-close"
	VerbUnpair    = "unpair"
)

// Pairing is the pairing half of the bridge, as this door reaches it.
//
// # Why a struct of the real things rather than an interface
//
// [Fit] is an interface because what it names is a CAPABILITY - two methods, and the narrowness is
// the security argument. This is not that. Every field here is a specific object with one instance
// per bridge, and the seven-method interface that would describe them would be satisfied in
// production by exactly one type and in tests by a fake whose answers are whatever the test wants.
//
// **That fake is what makes the interface the wrong shape here.** Two of the properties this seam has
// to hold are that the QR payload carries the SHA-256 of the certificate the bridge is actually
// serving, and that its expiry is the one the window will actually enforce. Against a fake window
// that returns whatever expiry it is told to, neither is testable at all - the test would assert that
// this package agrees with its own stub. So the tests stand up a real window, a real trust store on a
// real disk and a real minted certificate, and that is only worth doing if these fields are the real
// types.
//
// A nil *Pairing is legitimate and means **this door serves no pairing**: the four verbs are refused
// rather than crashing on a nil window. That is the same reading internal/listener takes of a nil
// Enrolment, and for the same reason - the absent case has to be the safer one.
type Pairing struct {
	// Listening is the address the listener is BOUND to, and it is what a phone dials.
	//
	// The bound address rather than the --listen argument that produced it. They are the same string
	// in this binary - net.Listen has already returned by the time this is built, so a failed bind
	// cannot reach here - and the bound one is the truthful value of the two: it is what the socket
	// is, not what somebody asked for. It costs nothing to be right by construction instead of by
	// argument.
	//
	// It belongs to the owner and is never a value this repository chooses. Nothing here validates it
	// either - see [dialTarget] for why a host guard would break the emulator's own pairing path.
	Listening string
	// Window is the enrolment window. **This socket is the only thing that can open it.**
	Window *enroll.Window
	// Peers is the trust store. Read by `status` and written by `unpair`, and by nothing else here.
	Peers *trust.Store
	// Certificate is this bridge's own certificate. The QR payload carries the SHA-256 of its DER,
	// read from the bridge's own identity rather than derived a second time somewhere else: it is
	// what the phone checks the TLS server certificate against, and a mismatch fails every pairing
	// with a message about a wrong certificate.
	Certificate *x509.Certificate
	// Agterm reports whether agterm is answering. A function rather than a client, so this package
	// does not grow a second opinion about what "reachable" means - production passes the API
	// handler's own probe, which is the ordinary sessions request and not a new code path.
	Agterm func(ctx context.Context) bool
}

// ready reports whether the pairing half is usable. A partly-filled Pairing is a wiring mistake in
// main rather than something a caller did, and it is answered the same way nil is: refused, not
// dereferenced.
func (p *Pairing) ready() bool {
	return p != nil && p.Window != nil && p.Peers != nil && p.Certificate != nil
}

// Listen starts the control socket and serves it until ctx is done.
//
// It refuses to start if something is already answering on that path, rather than unlinking it: a
// stale socket from a crashed run and a live socket from a second bridge look identical on disk, and
// clobbering the second one would leave the owner with a bridge whose local door silently belongs to
// another process.
// pairing may be nil, and a nil one means this door serves no pairing verbs - see [Pairing].
func Listen(ctx context.Context, dir string, fit Fit, pairing *Pairing) (net.Listener, error) {
	path := SocketPath(dir)

	// **A unix socket path has a hard length limit** - 104 bytes on macOS, in the kernel's sockaddr -
	// and exceeding it fails as `bind: invalid argument`, which names neither the path nor the length.
	// Said plainly here because the alternative is a puzzling line in the owner's log; the real
	// directory is nowhere near it, and a test's temp directory was.
	if len(path) >= maxSocketPath {
		return nil, fmt.Errorf("%s is %d bytes, over the %d-byte limit for a unix socket path",
			path, len(path), maxSocketPath)
	}

	if conn, err := net.DialTimeout("unix", path, dialProbe); err == nil {
		_ = conn.Close()
		return nil, fmt.Errorf("%s is already being served; another bridge is running against this directory", path)
	}
	// Nothing answered, so anything at that path is a leftover. Removing it is what makes a bridge
	// restartable after a crash without a manual cleanup step.
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}

	ln, err := net.Listen("unix", path)
	if err != nil {
		return nil, err
	}

	// **THE OUTGOING PROCESS MUST NOT UNLINK THE INCOMING PROCESS'S SOCKET.**
	//
	// Go's UnixListener removes its path on Close by default, and it does NOT check whether the file
	// there is still the one it created. So on a restart - which happens on every merge, because the
	// reload agent kickstarts the bridge when main moves - the sequence is:
	//
	//	  new instance: remove the stale entry, bind a fresh inode at the same path
	//	  old instance: Close() -> unlink(path) -> removes the NEW instance's socket
	//
	// The result is a listener nobody can reach: the fd is open, the process is healthy, this file
	// logs success, and the client correctly reports "no bridge is listening" about a bridge that is.
	// Observed 2026-07-30 with two starts thirty seconds apart, and it cost the owner their first
	// attempt at the palette command.
	//
	// # Why this rather than bind-then-rename
	//
	// Renaming a socket bound at a temporary path over the target is the atomic version, and it does
	// not fix this. The old instance unlinks `path` regardless of which inode is sitting there, so a
	// renamed-in socket is removed exactly as a freshly bound one is. Atomicity is not the property
	// that was missing - **ownership is**: a process removing a file it no longer owns.
	//
	// Turning the unlink off is therefore the whole fix, and the cleanup it removes is already done at
	// the other end: the startup path above probes and then removes a stale entry, which is the only
	// moment when it is knowable whether the file belongs to anyone.
	//
	// The cost, stated: a clean shutdown now leaves a socket file behind. It is 0 bytes, it is replaced
	// on the next start, and a client that dials it gets a refusal rather than a wrong answer.
	if unix, ok := ln.(*net.UnixListener); ok {
		unix.SetUnlinkOnClose(false)
	}
	// **Set explicitly rather than left to the umask.** A umask of 0 would otherwise produce a
	// world-writable socket, and "the permissions are usually right" is not a security property.
	//
	// **There is a gap above this line, and it is closed by the DIRECTORY rather than by anything
	// here.** net.Listen creates the socket with whatever the umask allows - under `umask 0` that is
	// 0777 - and it carries that mode until this call returns. It cannot be done in one step: a unix
	// socket has no mode argument on the call that creates it, and neither does Go expose one. What
	// makes the gap unreachable is that the containing directory is 0700, so nothing else on the
	// machine can traverse to the file during those two lines or at any other time. main chmods that
	// directory explicitly on every start, because MkdirAll applies a mode only when it CREATES -
	// which makes that chmod load-bearing for this socket and not only for the key material beside
	// it.
	if err := os.Chmod(path, 0o600); err != nil {
		_ = ln.Close()
		return nil, err
	}

	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()

	go serve(ctx, ln, fit, pairing)

	// **Checked from the outside, because that is the only thing a client can do.**
	//
	// Everything above proves this process bound a socket. It proves nothing about what a client
	// dialling that PATH will find, and those two came apart for a whole afternoon: the bind
	// succeeded, the log said so, and the entry had been unlinked by someone else. A caller of this
	// function should be able to log "the door is open" and be right about the door rather than about
	// the bind, so the check is a real connection to the real path.
	if conn, err := net.DialTimeout("unix", path, dialProbe); err != nil {
		_ = ln.Close()
		return nil, fmt.Errorf("%s was bound but cannot be reached: %w", path, err)
	} else {
		_ = conn.Close()
	}

	return ln, nil
}

func serve(ctx context.Context, ln net.Listener, fit Fit, pairing *Pairing) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			// The listener closing is how this loop is meant to end.
			return
		}
		go handle(ctx, conn, fit, pairing)
	}
}

// handle serves ONE request and closes the connection. See the package comment: this door does not
// pipeline, and whatever follows the first newline is never read.
func handle(ctx context.Context, conn net.Conn, fit Fit, pairing *Pairing) {
	defer conn.Close()
	// Bounded, like every other wait in this bridge: a local caller that connects and says nothing
	// must not hold the goroutine for ever.
	_ = conn.SetDeadline(time.Now().Add(callTimeout))

	// **Bounded, and it stops at the cap rather than buffering to a newline that may never come.**
	//
	// The permission on this socket says the caller is the owner's own app. That is an argument
	// about WHO, not about what a bug in them costs: an unbounded read turns a runaway loop in the
	// Mac app - or anything else running as the owner - into this process's memory, and the process
	// it takes down is the one holding the owner's phone connection. It was a ReadString, which is
	// unbounded, from when the whole protocol was one verb with no arguments.
	line, err := readLine(conn, maxRequestBytes)
	if err != nil {
		if errors.Is(err, errTooLarge) {
			reply(conn, errorReply{Error: "that request is too large to be a control request"})
		}
		return
	}

	// Unknown fields refused rather than ignored - see [request]. A misspelt argument must not
	// become a default.
	dec := json.NewDecoder(bytes.NewReader(line))
	dec.DisallowUnknownFields()
	var req request
	if err := dec.Decode(&req); err != nil {
		reply(conn, errorReply{Error: "that is not a control request"})
		return
	}
	// **A second token after the object is a different message, not slack.**
	//
	// Decode stops at the end of the first JSON value and says nothing about what follows, so
	// `{"verb":"status"} junk` decoded cleanly and was answered as an ordinary status - which also
	// means DisallowUnknownFields above is defeated by anything written outside the braces rather
	// than inside them. enroll.Decode refuses a trailing byte for the same reason and says why: it is
	// the tail of a second message, or somebody probing for a parser that ignores what it does not
	// understand.
	if dec.More() {
		reply(conn, errorReply{Error: "that is not a control request"})
		return
	}

	switch req.Verb {
	case VerbRestore:
		restore(ctx, conn, fit)
	case VerbStatus:
		status(ctx, conn, pairing)
	case VerbPairOpen:
		openWindow(conn, pairing, req.TTLSeconds)
	case VerbPairClose:
		closeWindow(conn, pairing)
	case VerbUnpair:
		unpair(conn, pairing, req.Fingerprint)
	default:
		// Names no alternatives. The set is closed and a caller guessing at others learns nothing
		// from us.
		reply(conn, errorReply{Error: fmt.Sprintf("unknown verb %q", req.Verb)})
	}
}

// restore is the undo, and it is the verb this door was built for.
func restore(ctx context.Context, conn net.Conn, fit Fit) {
	// **Nothing in force means nothing to undo, and that is an ordinary answer.** Resizing anyway
	// would be acting on a guess about a window the owner is sitting in front of.
	if !fit.FitInForce() {
		reply(conn, response{OK: true, Restored: false, Message: "no fit is in force"})
		return
	}

	if err := fit.RestoreFit(ctx); err != nil {
		log.Printf("control: the window could not be restored: %v", err)
		reply(conn, response{Error: err.Error()})
		return
	}
	log.Printf("control: restored the window at the owner's request from this machine")
	reply(conn, response{OK: true, Restored: true, Message: "the window is back the way it was"})
}

// status is everything the menu bar draws, in one line.
//
// One verb rather than four, because the panel wants a consistent picture: the paired list and the
// window state read separately are two moments, and a phone that enrolled between them would show up
// as a paired phone against a window that was never open.
func status(ctx context.Context, conn net.Conn, p *Pairing) {
	if !p.ready() {
		reply(conn, errorReply{Error: "this bridge was built without a pairing half"})
		return
	}

	peers := p.Peers.Peers()
	// Never null. An unpaired bridge is the ordinary first state.
	out := make([]pairedPeer, 0, len(peers))
	for _, peer := range peers {
		out = append(out, pairedPeer{
			Fingerprint: peer.Fingerprint,
			Name:        peer.Name,
			PairedAt:    peer.PairedAt.Unix(),
		})
	}

	// Whether agterm answers. Absent probe means "we were not given one", which is reported as not
	// reachable rather than as reachable: the fail-closed direction of a question whose wrong
	// optimistic answer sends the owner looking at their phone.
	up := false
	if p.Agterm != nil {
		up = p.Agterm(ctx)
	}

	state := p.Window.State()
	w := windowReply{Open: state.Open, AttemptsLeft: state.AttemptsLeft, Ended: string(state.Ended)}
	if state.Open {
		w.ExpiresAt = state.Expiry.Unix()
	}

	reply(conn, statusReply{Listening: p.Listening, Paired: out, Agterm: up, Window: w})
}

// openWindow mints a code, and it is the only thing anywhere that can do so.
//
// # The payload is built from what Open RETURNED, and that is the whole point of this function
//
// enroll.Window.Open clamps the ttl to enroll.MaxTTL and hands back the expiry it will actually
// enforce. Nothing in the type system forces a payload minter to carry THAT value rather than the one
// it asked for, and the failure is quiet: a code advertising an hour against a five-minute window
// strands somebody mid-pairing, and the phone cannot tell a closed window from an unreachable laptop.
// So the token and the expiry both come out of this one call, and
// TestPairOpenAdvertisesTheExpiryTheWindowWillEnforce is what keeps it that way.
func openWindow(conn net.Conn, p *Pairing, ttlSeconds int) {
	if !p.ready() {
		reply(conn, errorReply{Error: "this bridge was built without a pairing half"})
		return
	}
	// A ttl of zero or less produces a window that is already past its expiry - enroll.Window says
	// so, and calls it fail-closed, which it is. What it would produce HERE is a QR code on the
	// owner's screen that cannot work and nothing anywhere saying why, so it is refused instead.
	if ttlSeconds <= 0 {
		reply(conn, errorReply{Error: fmt.Sprintf(
			"ttl_seconds must be positive; the ceiling is %d", int(enroll.MaxTTL/time.Second))})
		return
	}
	host, port, err := dialTarget(p.Listening)
	if err != nil {
		reply(conn, errorReply{Error: err.Error()})
		return
	}
	// **A warning, not a refusal, and the difference is the emulator.**
	//
	// A wildcard host is a fine thing to be bound to and is not an address anything can connect to,
	// so a code carrying one looks perfect and fails on the phone - the worst shape a failure can
	// take here, because there is nothing on either screen to look at. This used to be a refusal
	// naming the flag, and the refusal had to go: it caught `0.0.0.0` and `::` and passed
	// `localhost`, `127.0.0.1` and `[::1]`, and the repair that would have caught those breaks the
	// `adb reverse` path, where the phone connects to 127.0.0.1 and that is exactly right.
	//
	// So the diagnostic comes back without the policy. The code is still minted, nothing about which
	// addresses are reachable is decided here, and the owner's log says the one thing this process
	// can actually be sure of: it is bound to every interface, so the code names no particular one.
	if isWildcard(host) {
		log.Printf("control: the pairing code names %q, which is every interface rather than one "+
			"address; a phone dialling it will fail unless something translates it. Start the bridge "+
			"with the address the phone should use.", host)
	}

	// Asked BEFORE the call, because Open is what destroys the answer. A window already open here
	// means this call is about to make somebody's code stop working - see [pairOpenReply.Replaced].
	replaced := p.Window.IsOpen()

	// ONE call. Both the token and the expiry come from it.
	token, expiry := p.Window.Open(time.Duration(ttlSeconds) * time.Second)

	text, err := enroll.EncodeToText(enroll.Payload{
		Host: host,
		Port: port,
		// The bridge's OWN certificate, hashed here rather than carried around pre-computed, so what
		// the code fingerprints is the certificate this process is serving.
		Fingerprint: sha256.Sum256(p.Certificate.Raw),
		Token:       token,
		Expiry:      expiry,
	})
	if err != nil {
		// Nothing usable was produced, so the window that was just opened is shut again rather than
		// left standing with a token nobody can reach. An open window with no code is the anonymous
		// branch of the front door open for five minutes for no reason at all.
		p.Window.Close()
		reply(conn, errorReply{Error: fmt.Sprintf("the pairing code could not be built: %v", err)})
		return
	}

	// The duration and nothing else. **Not the token, not the fingerprint, not the address**: this
	// log is a file anything able to read the disk can read, and the token is the one secret in this
	// package - enroll.Window keeps it off disk deliberately, and a line here would put it there.
	log.Printf("control: an enrolment window is open for %s at the owner's request from this machine (replaced an open one: %t)",
		time.Duration(ttlSeconds)*time.Second, replaced)
	reply(conn, pairOpenReply{Payload: text, ExpiresAt: expiry.Unix(), Replaced: replaced})
}

// closeWindow is the owner closing the pairing panel.
//
// Idempotent, and it says whether there was anything to close. Nothing open is an ordinary answer
// rather than a failure - the same reading `restore` takes of a window with no fit in force.
func closeWindow(conn net.Conn, p *Pairing) {
	if !p.ready() {
		reply(conn, errorReply{Error: "this bridge was built without a pairing half"})
		return
	}
	was := p.Window.IsOpen()
	p.Window.Close()
	if was {
		log.Print("control: the enrolment window was closed at the owner's request from this machine")
	}
	reply(conn, pairCloseReply{OK: true, Closed: was})
}

// unpair drops a paired phone.
//
// # Why this checks the list first, when the store does not
//
// trust.Store.Remove is deliberately silent about a fingerprint it does not hold: the caller asked
// for it to be gone and it is gone, and an owner tapping Unpair on a phone another window already
// removed should see nothing happen rather than a failure. **That reading is right for the store and
// wrong here.** The Mac app got its list from `status`, so a fingerprint it did not find is a typo, a
// stale panel, or a second window that already acted - and answering `ok` leaves the owner believing
// they unpaired a phone that is still paired and still able to drive their terminal.
//
// So the membership check lives at this end, where the caller is a user interface that will report
// what it is told, rather than in the store, where the silence is the correct behaviour for its own
// callers.
func unpair(conn net.Conn, p *Pairing, fingerprint string) {
	if !p.ready() {
		reply(conn, errorReply{Error: "this bridge was built without a pairing half"})
		return
	}
	// The empty string is not an identifier, and this verb is destructive. Refused rather than read
	// as "unpair everything".
	if fingerprint == "" {
		reply(conn, unpairReply{Remaining: len(p.Peers.Peers()), Error: "unpair needs the fingerprint of the phone to drop"})
		return
	}

	before := p.Peers.Peers()
	known := false
	for _, peer := range before {
		if peer.Fingerprint == fingerprint {
			known = true
			break
		}
	}
	if !known {
		reply(conn, unpairReply{Remaining: len(before), Error: "no phone is paired with that fingerprint"})
		return
	}

	if err := p.Peers.Remove(fingerprint); err != nil {
		reply(conn, unpairReply{Remaining: len(before), Error: err.Error()})
		return
	}
	remaining := len(p.Peers.Peers())
	// A count, never the fingerprint. It identifies the owner's phone and this log is a file - the
	// same rule the startup banner and the enrolment callback already follow.
	log.Printf("control: a phone was unpaired at the owner's request from this machine; %d remain", remaining)
	reply(conn, unpairReply{OK: true, Unpaired: true, Remaining: remaining})
}

// dialTarget splits the bound address into the two fields the QR payload carries. **It applies no
// policy to the host, and a guard here would be a bug rather than a missing feature.**
//
// The first version of this refused a wildcard - `:8443`, `0.0.0.0:8443` - on the reasoning that
// those are fine things to listen on and are not addresses anything can connect to. It caught one
// undialable spelling and passed three: `localhost`, `127.0.0.1` and `[::1]` all minted codes
// happily. The obvious repair is to refuse loopback as well, and **that repair breaks the one
// end-to-end path this project has**: the Android emulator reaches the bridge through `adb reverse`,
// where the phone connects to `127.0.0.1` and that is exactly correct. A loopback refusal would turn
// the emulator's pairing into a support question in service of a rule nothing asked for.
//
// So the host is whatever the bridge is bound to, unexamined. Which address a phone can reach is a
// question about the owner's network, the router, and whether a tunnel is in front - none of which
// this process can see, and all of which the Mac app either knows or can ask. The only refusals left
// are structural: a string that is not a host and a port, and a port that is not a port.
// isWildcard reports whether a host names every interface rather than one address.
//
// Used for a LOG LINE and for nothing else. It is deliberately not exhaustive and does not need to
// be: an address it fails to recognise costs a warning that was not printed, not a code that was
// wrongly refused, and the whole reason the refusal was removed is that a list like this cannot be
// made complete without breaking hosts that work.
func isWildcard(host string) bool {
	return host == "" || host == "0.0.0.0" || host == "::"
}

func dialTarget(listen string) (string, int, error) {
	host, portText, err := net.SplitHostPort(listen)
	if err != nil {
		return "", 0, fmt.Errorf("the bridge is listening on %q, which is not a host and a port", listen)
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port <= 0 || port > 0xffff {
		return "", 0, fmt.Errorf("the bridge is listening on %q, whose port is not a port", listen)
	}
	return host, port, nil
}

// reply writes one JSON line. It takes any of the reply types above, because each verb answers in its
// own shape and a single struct carrying every field would make every reply a description of the
// other four.
func reply(conn net.Conn, v any) {
	raw, err := json.Marshal(v)
	if err != nil {
		return
	}
	_, _ = conn.Write(append(raw, '\n'))
}

// errTooLarge is a line over the ceiling, told apart from a caller that simply hung up so that the
// first can be answered in words and the second cannot be answered at all.
var errTooLarge = errors.New("control: the request is over the ceiling")

// readLine reads one newline-terminated line and STOPS at max rather than at the newline.
//
// The same shape as the enrolment handler's reader, and for a version of the same reason: a caller
// that sends a gigabyte with no newline is holding one connection and maxRequestBytes, not memory of
// their choosing.
func readLine(conn net.Conn, max int) ([]byte, error) {
	buf := make([]byte, 0, 512)
	chunk := make([]byte, 512)
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
				return nil, errTooLarge
			}
		}
		if err != nil {
			if len(buf) > 0 {
				// A caller that wrote a whole request and closed without the newline. Answered
				// rather than dropped, which is what the ReadString this replaced also did.
				return buf, nil
			}
			return nil, err
		}
	}
}

const (
	// dialProbe only has to answer "is anything listening", which is instant for a unix socket.
	dialProbe = 200 * time.Millisecond
	// callTimeout covers a restore, which is one resize on a live window.
	callTimeout = 30 * time.Second
	// maxSocketPath is the smallest of the platform limits (macOS sun_path is 104). Checked rather
	// than discovered, so the failure names itself.
	maxSocketPath = 104
	// maxRequestBytes caps one control request. The largest legitimate one is an `unpair` carrying a
	// grouped SHA-256 fingerprint - under 120 bytes - so 8 KiB is far above anything real and far
	// below anything that costs this process something.
	maxRequestBytes = 8 << 10
)
