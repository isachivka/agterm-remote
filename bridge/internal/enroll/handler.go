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
// One deadline for both directions rather than a read deadline, because the connection is
// anonymous: a caller that sends a partial line and then stalls, and a caller that never reads the
// reply, cost exactly the same thing and must both be released on the same clock.
const exchangeTimeout = 10 * time.Second

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
	// the one in the field above.
	//
	// That is the useful one of the two and it is worth saying why, because the neighbouring field
	// invites the other reading. The phone can compute either digest for itself; what it cannot know
	// is what the bridge actually stored. This is the receipt: the exact string the owner's menu
	// will show for this phone, so the two screens can be compared, and a phone whose certificate
	// arrived truncated or re-encoded sees a digest that is not its own instead of discovering it on
	// the next connection as a failed handshake.
	Fingerprint string `json:"fingerprint,omitempty"`
	// Error is refusalText or nothing. It is never a description of what went wrong.
	Error string `json:"error,omitempty"`
}

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
//   - **At most one log line per SPENT ATTEMPT, and none at all on the paths a stranger can repeat.**
//
// That last one took the most care and is the reason the logging is not simply "log every refusal".
// A line per attempt would hand whoever can reach the port an unbounded write against the owner's
// disk - the rule internal/listener holds for failed handshakes, for the same reason. The paths that
// fail BEFORE [Window.Consume] (an oversized line, a request that will not parse, a verb this does
// not serve, a token that is not base64) are repeatable without limit, because they never touch the
// attempt counter, so none of them writes anything. The paths that fail at or after Consume are
// bounded by the window itself: five wrong tokens close it, a right one closes it too, and the
// window only exists while the owner is standing at their Mac having opened it. So the owner gets
// the diagnosis for exactly the events they can act on - "the code was wrong", "the code had
// expired", "the phone sent a certificate I cannot read" - and a stranger gets no leverage.
//
// The cause is logged, never the caller's bytes: no name, no certificate, no fingerprint. A
// fingerprint identifies the owner's phone and this log is a file on a laptop.
func Serve(conn net.Conn, window *Window, store *trust.Store, own *x509.Certificate, paired func(trust.Peer)) {
	defer conn.Close()
	// Both directions, before anything is read. A connection that stalls mid-line and one that never
	// reads its answer are the same anonymous cost.
	_ = conn.SetDeadline(time.Now().Add(exchangeTimeout))

	reply, cause := enrol(conn, window, store, own, paired)
	if cause != nil {
		// Bounded at maxAttempts + 1 lines for the life of a window, and a window is seconds that a
		// person opened. See the note above; this is why enrol returns nil for everything a stranger
		// can repeat.
		log.Printf("enrolment refused: %v", cause)
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
func enrol(conn net.Conn, window *Window, store *trust.Store, own *x509.Certificate, paired func(trust.Peer)) (Reply, error) {
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
	if err := window.Consume(given); err != nil {
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
	if err := store.Replace(peer); err != nil {
		return refusal(), fmt.Errorf("the trust store could not be written: %w", err)
	}
	if paired != nil {
		// The Mac app's menu. Called with the peer as stored, after the write, so what the menu
		// shows is what the bridge will accept and not what it was asked to accept.
		paired(peer)
	}

	return Reply{
		OK: true,
		// The bridge's own certificate. There is nothing to guard here - it is public, it is what
		// this connection already proved possession of, and the phone is about to pin it.
		Certificate: base64.StdEncoding.EncodeToString(own.Raw),
		Fingerprint: peer.Fingerprint,
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
