package enroll

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"log"
	"slices"
	"sync"

	"github.com/isachivka/agterm-remote/bridge/internal/pinning"
	"github.com/isachivka/agterm-remote/bridge/internal/trust"
)

// The two protocols this bridge speaks, and the split between them is the structural heart of the
// whole design.
//
// # The claim, and why it is made here rather than in a handler
//
// **A connection with no client certificate cannot reach the API under any sequence of actions.**
//
// Under ALPN that is structural: the two protocols are separate branches decided during the TLS
// handshake, before a single byte of application data exists. The connection that carries enrolment
// was never asked for a certificate and was never offered the API's protocol; the connection that
// carries the API presented the pinned certificate before it could say anything at all. There is no
// state either one can be moved into that turns it into the other, because ALPN is settled once, in
// the ClientHello exchange, and nothing later re-opens it.
//
// The obvious alternative is one authenticated stream with a URL path — `/enroll` next to `/api` —
// and the difference is not stylistic. That version's claim would be a property of ROUTING CODE:
// true while every handler checks, one refactor away from false, and asserted about a port the owner
// has deliberately exposed to the internet. This version's claim is a property of the handshake, and
// the code that could weaken it is the twelve lines below rather than every handler ever added.
//
// # BOTH HALVES ARE WIRED, AND THIS IS WHERE THEY MEET
//
// The claim above has two halves and needed both. This file holds the first: a connection with no
// client certificate can only ever be on `agterm/enroll-1`, and every test in alpn_test.go asserts it
// against a real handshake.
//
// The second half is WHERE EACH BRANCH LEADS, and it is not here - it is the switch in
// internal/listener's accept path, which reads NegotiatedProtocol after the handshake and sends
// `agterm/enroll-1` to enroll.Serve, `agterm/api-1` to the API handler, and anything else to a closed
// connection. **Until that switch existed this comment was aspirational**, and said so: the listener
// handed every completed handshake to the API handler, so an open window would have meant an
// anonymous caller reaching the whole API. It was measured as exactly that - `{"verb":"sessions"}`
// answered ok=true with no client certificate - and it had never mattered only because nothing in the
// repository called Window.Open.
//
// So the sentence in bold above is now true of the running program rather than of the handshake alone,
// and the two files that could make it false again are this one and internal/listener's accept. The
// history is kept because a comment that overstates a security property is worse than no comment, and
// somebody deciding whether this boundary holds should be able to see which claim was checked when.
//
// # Versioned from the first release, and never renegotiated
//
// The `-1` is not decoration. An ALPN identifier is the one place a protocol change can be announced
// before any data is exchanged, so a future bridge that has to change the wire format offers
// `agterm/api-2` alongside `agterm/api-1` and an old phone keeps working, with no version field
// inside a stream to disagree about. What must NOT happen is a protocol string a caller can use to
// select a weaker branch: there are exactly two, and only one of them is ever offered to a caller
// without a certificate.
const (
	ProtoAPI    = "agterm/api-1"
	ProtoEnroll = "agterm/enroll-1"
)

// ServerConfigFor is the bridge's listener configuration: enrolment and the API, split by ALPN, with
// the branch chosen per connection.
//
// # Why GetConfigForClient rather than one config with two protocols
//
// A single tls.Config can only make one decision about client authentication, and the two branches
// need opposite ones — the API requires the pinned certificate, enrolment must not require any. So
// the branch is chosen when the ClientHello arrives and the whole configuration follows from it:
// ClientAuth, the verifier, and the single protocol that will be negotiated.
//
// That the callback runs PER CONNECTION is the other half of what it buys, and it is what closes the
// startup snapshot main.go used to carry: `peers` is called here, on this handshake, so a phone that
// enrolled a second ago is accepted by the next connection instead of by the next restart. It is a
// function rather than a slice for exactly that reason.
//
// # The rules, in the order the code applies them
//
//   - `agterm/enroll-1` is offered ONLY while the window is open. A closed window means the
//     configuration returned carries `agterm/api-1` and nothing else, and Go's ALPN negotiation then
//     fails the handshake of a caller that has nothing else to offer — `no application protocol`.
//     That refusal travels the listener's existing single refusal path (a handshake that returned an
//     error: connection closed, counted, nothing logged per attempt) rather than becoming a second
//     one, which is why nothing here writes to the connection or returns an error of its own.
//   - The enrolment branch requires NO client certificate, and it leads to [Serve] and to nothing
//     else. That second half is the switch in internal/listener's accept path rather than anything
//     here - see the note by the constants - and it is what turns "this connection is anonymous" into
//     "this connection can only pair".
//   - The API branch requires the pinned client certificate, exactly as before this split existed.
//
// # What the ordering means, and the one surprise in it
//
// The window is checked FIRST, so a caller that offers `agterm/enroll-1` while a window is open gets
// enrolment even if it also offered `agterm/api-1`. That is the safe direction of the two and it is
// deliberate: the branch a certificate-less caller can reach must be the one that leads to a
// token-guarded handler, never the one that leads to the owner's terminal. Its cost is the surprise —
// an ALREADY PAIRED phone that offered both while the owner happened to have a pairing panel open
// would be handed enrolment and would have to reconnect offering only the API. The Android client
// offers one protocol at a time, by design, so it does not meet this; the alternative ordering would
// let the choice of which branch to serve depend on what an unauthenticated caller asked for, and
// that is the property this function exists to deny.
//
// The returned outer config exists to hold the callback. Its MinVersion is set because a config that
// only routes still has to refuse an old protocol version, and because Go consults the outer config
// before the callback runs.
func ServerConfigFor(own tls.Certificate, peers func() []*x509.Certificate, window *Window) *tls.Config {
	return &tls.Config{
		MinVersion: tls.VersionTLS13,
		GetConfigForClient: func(hello *tls.ClientHelloInfo) (*tls.Config, error) {
			if window.IsOpen() && slices.Contains(hello.SupportedProtos, ProtoEnroll) {
				c := pinning.AnonymousServerConfig(own)
				// Exactly one protocol, so what gets negotiated is this branch's and not the
				// caller's preference. A list of two here would hand the caller the choice, which is
				// the whole thing this split takes away from them.
				c.NextProtos = []string{ProtoEnroll}
				return c, nil
			}
			// Everything else is the API branch, including a caller that offered no ALPN at all and a
			// caller that offered a protocol nobody serves. The strict branch is the default, so a
			// protocol string this code has never heard of gets the configuration that demands a
			// pinned certificate rather than the one that demands nothing.
			c := pinning.ServerConfig(own, peers())
			c.NextProtos = []string{ProtoAPI}
			return c, nil
		},
	}
}

// PeerSource is the ONE read method PinnedClients needs from the trust store.
//
// One, deliberately. This used to also take a Certificates method off the store, and asking a store
// that can be written to two questions about the same moment is a race whatever order they are asked
// in - see the note on PinnedClients. That method has since been deleted from trust.Store outright,
// because this was its last caller and a second copy of the drop-what-does-not-parse rule with no
// live user is a copy that drifts. An interface so the cache can be tested for what it does and does
// not ask the store for, which is the whole point of it.
type PeerSource interface {
	// Peers is the current list, carrying the DER this cache parses for itself.
	Peers() []trust.Peer
}

// PinnedClients is the per-connection list of certificates the API branch accepts, with the parse
// cached.
//
// # Why a cache is needed at all
//
// The list the API branch accepts is read on EVERY TLS handshake, and turning stored DER into
// certificates costs an x509 parse per paired phone - plus, when one of them goes bad, a log line. On
// a startup path that is nothing. On a per-handshake path the parse is waste and the log line is an
// unbounded write against the owner's disk driven by whoever can reconnect.
//
// trust.Store used to answer this question itself, with exactly that shape, and it was deliberately
// left alone about it twice: the store's job is bytes, and the caller that made the path hot is the
// one that should pay for the caching. **That method is now gone rather than merely unused** - this
// cache was its last caller, and the same rule written down in two places with one live user is the
// version that drifts.
//
// # How it is invalidated, and why it cannot serve a stale list
//
// There is nothing to invalidate, because the cache KEY IS THE STORE'S CONTENT: the DER of every
// peer, in order. Each call reads the current list — a mutex and a clone, no parse and no log — and
// reuses the parsed certificates only when those bytes are identical to the ones they were parsed
// from. A phone that enrols writes a new list, the very next handshake sees a key that differs, and
// the parse happens again. An unpaired phone disappears the same way.
//
// This is deliberately not "whoever enrols calls Invalidate". That is one line in a future handler
// away from being forgotten, and the failure it produces is a bridge that keeps accepting an
// unpaired phone — silent, and indistinguishable from working. A key derived from the content cannot
// be forgotten, because there is no call to omit.
//
// # ONE read of the store, and why ordering two was not enough
//
// The key and the certificates come out of the SAME call to Peers, and the parse happens here rather
// than in a second call to the store. This is the correctness of the whole type, and the first
// version got it wrong in a way that reads as careful.
//
// That version read the key with Peers and then the certificates with the store's own Certificates,
// and argued the
// order was load-bearing: key first, so a write landing between the two could only file NEWER
// certificates under an OLDER key, never the reverse. Both halves of that are true and the
// conclusion does not follow, because the surviving direction is the dangerous one. Read the key at
// [A]; a phone enrols; read the certificates as [A, B]. The cache now holds {key: [A], certs:
// [A, B]}. The owner unpairs B, the store returns to [A] — and the key MATCHES, so every later
// handshake trusts B, for the life of the process, with the store saying otherwise the whole time.
// An attacker who can enrol once and then reconnect in a loop drives both sides of that race, and
// what they win is surviving their own unpairing. It was reproduced through this interface before it
// was fixed.
//
// A tighter comparison cannot fix it: the two answers describe two different moments and no amount
// of care about which was read first changes that. One read has no interleaving to reason about, and
// it deletes the log-per-handshake problem that motivated the cache as well — see parseAll.
//
// The list handed out is a fresh slice each time, so a caller that appends to what it was given
// cannot grow what the next handshake trusts. The certificates inside it are shared and read-only,
// parsed from the copy Peers handed over, so nothing here aliases the store either.
//
// Safe for concurrent use. It is on the accept path, which is concurrent by nature, while pairing
// writes to the store from somewhere else entirely.
type PinnedClients struct {
	source PeerSource

	mu    sync.Mutex
	key   [][]byte
	certs []*x509.Certificate
}

// NewPinnedClients returns a cache over source. Its Certificates method is the `peers` argument
// ServerConfigFor takes.
func NewPinnedClients(source PeerSource) *PinnedClients {
	return &PinnedClients{source: source}
}

// Certificates is the current pinned client list, parsed at most once per version of it.
//
// ONE read of the store, and the key and the certificates both come out of it. Two reads cannot be
// made safe by ordering them - see the note on the type.
func (p *PinnedClients) Certificates() []*x509.Certificate {
	p.mu.Lock()
	defer p.mu.Unlock()

	key := certificateDER(p.source.Peers())
	if !sameDER(p.key, key) {
		p.certs = parseAll(key)
		p.key = key
	}
	out := make([]*x509.Certificate, len(p.certs))
	copy(out, p.certs)
	return out
}

// certificateDER is the store's content, reduced to what actually authenticates a phone. The
// fingerprint and the name are labels over these bytes, so a change to either without a change here
// cannot change who is accepted.
func certificateDER(peers []trust.Peer) [][]byte {
	out := make([][]byte, len(peers))
	for i := range peers {
		out[i] = peers[i].CertificateDER
	}
	return out
}

// parseAll turns the snapshot into certificates, dropping anything that no longer parses.
//
// Dropping rather than refusing to serve:
// a stored certificate can stop parsing - a Go release tightening x509 is the realistic way - and of
// drop it, refuse to serve, or hand the TLS layer a nil, only the first leaves the bridge running
// for whoever is still valid. A dropped certificate authenticates nobody, so nothing is loosened.
//
// **This is the only place the rule lives now.** trust.Store had its own copy, and it was the reason
// this one could be described as "the store's own rule"; the store's version had no callers left once
// this cache existed, so it was deleted rather than left as a second statement of a security rule
// that nothing exercises.
//
// The count is logged, never the identity: a fingerprint identifies the owner's phone and this log
// is a file on a laptop. **At most one line per version of the list**, because this runs only when
// the snapshot differs from the cached one. That bound is the point - the same message on a
// per-handshake path would have been one line per connection, which is an unbounded write against
// the owner's disk driven by whoever can reconnect.
//
// Parsed from the bytes Peers already cloned, so the certificates' Raw fields - which is what the
// pinning verifier compares - alias nothing the store or any other caller can reach.
func parseAll(der [][]byte) []*x509.Certificate {
	out := make([]*x509.Certificate, 0, len(der))
	dropped := 0
	for _, raw := range der {
		cert, err := x509.ParseCertificate(raw)
		if err != nil {
			dropped++
			continue
		}
		out = append(out, cert)
	}
	if dropped > 0 {
		log.Printf("trust: %d of %d stored certificates no longer parse and were ignored", dropped, len(der))
	}
	return out
}

// sameDER compares two lists byte for byte, in order.
//
// Not constant time, and nothing here needs it to be: both sides are the owner's own public
// certificates, neither is a secret, and no caller supplies either of them. The one comparison in
// this package that IS against caller-supplied bytes is the token in Consume, and that one is
// subtle.ConstantTimeCompare.
func sameDER(a, b [][]byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if !bytes.Equal(a[i], b[i]) {
			return false
		}
	}
	return true
}
