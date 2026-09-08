package enroll_test

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"io"
	"log"
	"math/big"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/enroll"
	"github.com/isachivka/agterm-remote/bridge/internal/pinning"
	"github.com/isachivka/agterm-remote/bridge/internal/trust"
)

// handlerFixture is a bridge identity, an empty trust store and a closed window - the state of a
// laptop that has never paired anything, which is where every enrolment starts.
func handlerFixture(t *testing.T) (*enroll.Window, *trust.Store, *x509.Certificate) {
	t.Helper()
	log.SetOutput(io.Discard)
	t.Cleanup(func() { log.SetOutput(io.Discard) })

	_, own := mint(t, "agterm-remote bridge")
	store, err := trust.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	return enroll.NewWindow(time.Now), store, own
}

// exchange runs one request through the real handler and returns the one line it answered.
//
// Over net.Pipe rather than a socket: the handler is given a net.Conn and the exchange is one line
// each way, so a port would add a listener, an address and a teardown to assert nothing extra. The
// end-to-end test in this package is where a real port earns its keep.
func exchange(t *testing.T, window *enroll.Window, store *trust.Store, own *x509.Certificate, req enroll.Request) enroll.Reply {
	t.Helper()
	line, err := json.Marshal(req)
	if err != nil {
		t.Fatal(err)
	}
	return exchangeRaw(t, window, store, own, string(line)+"\n")
}

func exchangeRaw(t *testing.T, window *enroll.Window, store *trust.Store, own *x509.Certificate, line string) enroll.Reply {
	t.Helper()
	reply, err := rawExchange(t, window, store, own, line, nil)
	if err != nil {
		t.Fatalf("reading the reply: %v", err)
	}
	return reply
}

// rawExchange is the machinery, with the caller's callback and the decode error both visible - the
// two things the tests below assert on that the convenience wrappers swallow.
func rawExchange(t *testing.T, window *enroll.Window, store *trust.Store, own *x509.Certificate, line string, paired func(trust.Peer)) (enroll.Reply, error) {
	t.Helper()
	client, server := net.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		enroll.Serve(server, window, store, own, paired)
	}()
	// In a goroutine, and its error ignored on purpose: an oversized line is refused before it has
	// all been read, so the write is expected not to finish.
	go func() { _, _ = io.WriteString(client, line) }()

	_ = client.SetDeadline(time.Now().Add(10 * time.Second))
	var reply enroll.Reply
	err := json.NewDecoder(client).Decode(&reply)
	_ = client.Close()
	<-done
	return reply, err
}

func b64(b []byte) string { return base64.StdEncoding.EncodeToString(b) }

// --- The four behaviours -------------------------------------------------------------------------

func TestSuccessfulEnrolmentPinsThePhoneAndReturnsTheBridgeCertificate(t *testing.T) {
	window, store, own := handlerFixture(t)
	code, _ := window.Open(time.Minute)
	_, phone := mint(t, "a phone")

	reply := exchange(t, window, store, own, enroll.Request{
		Verb:        "enroll",
		Token:       b64(code[:]),
		Certificate: b64(phone.Raw),
		Name:        "a phone",
	})

	if !reply.OK {
		t.Fatalf("enrolment failed: %s", reply.Error)
	}
	if reply.Certificate == "" {
		t.Fatal("the bridge must return its own certificate")
	}
	gotOwn, err := base64.StdEncoding.DecodeString(reply.Certificate)
	if err != nil {
		t.Fatalf("the returned certificate is not standard base64: %v", err)
	}
	if !bytesEqual(gotOwn, own.Raw) {
		t.Fatal("the certificate returned is not this bridge's own; the phone would pin the wrong laptop")
	}
	if len(store.Peers()) != 1 {
		t.Fatalf("want the phone pinned, got %d peers", len(store.Peers()))
	}
	if store.Peers()[0].Name != "a phone" {
		t.Fatal("the name must be stored for the menu")
	}
	if !bytesEqual(store.Peers()[0].CertificateDER, phone.Raw) {
		t.Fatal("the certificate stored is not the one that was sent")
	}
	// The receipt: the fingerprint of what was PINNED, which is what the owner's menu will show.
	if reply.Fingerprint != pinning.Fingerprint(phone) {
		t.Fatalf("want the pinned phone's fingerprint, got %q", reply.Fingerprint)
	}
	// Single use. The token that worked is worthless, so a second phone cannot walk through the same
	// window behind the first.
	if window.IsOpen() {
		t.Fatal("a spent token must close the window")
	}
}

func TestAWrongTokenPinsNothing(t *testing.T) {
	window, store, own := handlerFixture(t)
	window.Open(time.Minute)
	_, phone := mint(t, "a phone")

	reply := exchange(t, window, store, own, enroll.Request{
		Verb:        "enroll",
		Token:       b64(make([]byte, 32)),
		Certificate: b64(phone.Raw),
	})
	if reply.OK {
		t.Fatal("a wrong token must not enrol")
	}
	if len(store.Peers()) != 0 {
		t.Fatal("nothing may be pinned on failure")
	}
	if reply.Certificate != "" || reply.Fingerprint != "" {
		t.Fatalf("a refusal must carry nothing else: %+v", reply)
	}
}

func TestGarbageCertificatePinsNothing(t *testing.T) {
	window, store, own := handlerFixture(t)
	code, _ := window.Open(time.Minute)

	reply := exchange(t, window, store, own, enroll.Request{
		Verb:        "enroll",
		Token:       b64(code[:]),
		Certificate: b64([]byte("not a certificate")),
	})
	if reply.OK {
		t.Fatal("an unparseable certificate must be refused")
	}
	if len(store.Peers()) != 0 {
		t.Fatal("nothing may be pinned")
	}
	// And the token is gone regardless. It was spent on the way in - the caller held it - so the
	// owner has to open a new window rather than the code staying live for whoever comes next.
	if window.IsOpen() {
		t.Fatal("a token that reached the gate is spent, whatever followed it")
	}
}

func TestAnOversizedLineIsRefused(t *testing.T) {
	window, store, own := handlerFixture(t)
	window.Open(time.Minute)

	// 1 MiB of 'a' followed by a newline.
	if reply := exchangeRaw(t, window, store, own, strings.Repeat("a", 1<<20)+"\n"); reply.OK {
		t.Fatal("an oversized request must be refused")
	}
	// It never reached the gate, so it cost the owner nothing: the window they opened is still the
	// window they opened.
	if !window.IsOpen() {
		t.Fatal("an oversized line must not close the owner's window")
	}
	if len(store.Peers()) != 0 {
		t.Fatal("nothing may be pinned")
	}
}

// --- What a refusal may say ----------------------------------------------------------------------

// **Every refusal is the same bytes**, whatever actually happened.
//
// Window.Consume already collapses its three causes into one ErrRefused so that a handler cannot
// relay window state to a stranger. This handler adds four failures of its own, and the same rule
// has to hold across all seven or the collapse in Consume was pointless: a caller that can tell "no
// window is open" from "wrong token" learns whether the owner is at their laptop, and one that can
// tell "bad verb" from "bad certificate" learns how far it got.
func TestEveryRefusalIsTheSameWords(t *testing.T) {
	_, phone := mint(t, "a phone")

	// Each case builds the window in the state it is about, and the request that meets it. The three
	// window states need a clock rather than a sleep - expiry is a property this package injects time
	// for, and a suite that sleeps for its timing gets its assertions loosened the first time CI is
	// slow.
	wrong := b64(make([]byte, 32))
	open := func(*testing.T) *enroll.Window {
		w := enroll.NewWindow(time.Now)
		w.Open(time.Minute)
		return w
	}
	cases := []struct {
		name   string
		window func(*testing.T) *enroll.Window
		req    enroll.Request
	}{
		{"no window is open", func(*testing.T) *enroll.Window {
			return enroll.NewWindow(time.Now)
		}, enroll.Request{Verb: "enroll", Token: wrong, Certificate: b64(phone.Raw)}},
		{"the window expired", func(*testing.T) *enroll.Window {
			at := time.Unix(1_000_000, 0)
			w := enroll.NewWindow(func() time.Time { return at })
			w.Open(time.Minute)
			at = at.Add(2 * time.Minute)
			// Consume checks the clock before it compares anything, so this is causeExpired rather
			// than causeToken - a distinct cause inside the package, and the same word on the wire.
			return w
		}, enroll.Request{Verb: "enroll", Token: wrong, Certificate: b64(phone.Raw)}},
		{"the wrong token", open, enroll.Request{
			Verb: "enroll", Token: wrong, Certificate: b64(phone.Raw)}},
		{"a verb nobody serves", open, enroll.Request{
			Verb: "sessions", Token: wrong, Certificate: b64(phone.Raw)}},
		{"a token that is not base64", open, enroll.Request{
			Verb: "enroll", Token: "!!!!", Certificate: b64(phone.Raw)}},
		{"a certificate that is not base64", open, enroll.Request{
			Verb: "enroll", Token: wrong, Certificate: "!!!!"}},
		{"a certificate that does not parse", open, enroll.Request{
			Verb: "enroll", Token: wrong, Certificate: b64([]byte("nope"))}},
	}

	var first string
	for _, tc := range cases {
		_, store, own := handlerFixture(t)
		window := tc.window(t)
		reply := exchange(t, window, store, own, tc.req)
		if reply.OK {
			t.Fatalf("%s: must be refused", tc.name)
		}
		line, err := json.Marshal(reply)
		if err != nil {
			t.Fatal(err)
		}
		if first == "" {
			first = string(line)
			continue
		}
		if string(line) != first {
			t.Fatalf("%s answered %s, but another refusal answered %s; a caller can tell them apart",
				tc.name, line, first)
		}
	}
}

// The refusal must not carry the cause even by inclusion: ErrRefused's own text, and every
// unexported cause behind it, would be a leak if a handler ever wrote err.Error() into the reply.
func TestARefusalNamesNoCause(t *testing.T) {
	window, store, own := handlerFixture(t)
	window.Open(time.Minute)
	_, phone := mint(t, "a phone")

	reply := exchange(t, window, store, own, enroll.Request{
		Verb: "enroll", Token: b64(make([]byte, 32)), Certificate: b64(phone.Raw)})

	for _, leak := range []string{"window", "expired", "token", "certificate", "closed", "parse"} {
		if strings.Contains(strings.ToLower(reply.Error), leak) {
			t.Fatalf("the refusal %q names why: %q", reply.Error, leak)
		}
	}
}

// --- What a refused caller costs -----------------------------------------------------------------

// **A caller that never reaches the gate cannot burn an attempt.**
//
// Five wrong tokens close the window, and that counter exists for the window nobody is using - not
// as a way for a stranger to shut the owner's pairing panel. Everything that fails before
// Window.Consume must therefore be free: a dropped connection, a line over the ceiling, a request
// that will not parse, a verb this handler does not serve, a token that is not base64. If any of
// them counted, the owner's window could be closed from the network without the attacker ever
// guessing at the secret.
func TestNothingBeforeTheGateBurnsAnAttempt(t *testing.T) {
	window, store, own := handlerFixture(t)
	code, _ := window.Open(time.Minute)
	_, phone := mint(t, "a phone")

	// Far more than maxAttempts of each shape.
	for i := 0; i < 20; i++ {
		exchangeRaw(t, window, store, own, strings.Repeat("a", 1<<20)+"\n")
		exchangeRaw(t, window, store, own, "{not json}\n")
		exchangeRaw(t, window, store, own, `{"verb":"enroll","surprise":1}`+"\n")
		exchange(t, window, store, own, enroll.Request{Verb: "sessions", Token: b64(code[:])})
		exchange(t, window, store, own, enroll.Request{Verb: "enroll", Token: "!!!!"})
	}

	// The owner's code still works, which is the whole property.
	reply := exchange(t, window, store, own, enroll.Request{
		Verb: "enroll", Token: b64(code[:]), Certificate: b64(phone.Raw)})
	if !reply.OK {
		t.Fatalf("100 refusals before the gate closed the owner's window: %s", reply.Error)
	}
}

// **No log line for a refusal a stranger can repeat.**
//
// One line per attempt would hand whoever can reach the port an unbounded write against the owner's
// disk - the rule internal/listener holds for failed handshakes, and the same reasoning: an
// unauthenticated caller must not be able to make the bridge do anything at all, and writing to a
// file is doing something. The paths below never touch the attempt counter, so they can be repeated
// forever and must cost nothing that accumulates.
func TestARefusalBeforeTheGateWritesNoLogLine(t *testing.T) {
	window, store, own := handlerFixture(t)
	window.Open(time.Minute)

	var lines atomic.Int32
	log.SetOutput(writerFunc(func(p []byte) (int, error) { lines.Add(1); return len(p), nil }))
	t.Cleanup(func() { log.SetOutput(io.Discard) })

	for i := 0; i < 20; i++ {
		exchangeRaw(t, window, store, own, strings.Repeat("a", 1<<20)+"\n")
		exchangeRaw(t, window, store, own, "{not json}\n")
		exchange(t, window, store, own, enroll.Request{Verb: "sessions"})
		exchange(t, window, store, own, enroll.Request{Verb: "enroll", Token: "!!!!"})
	}

	if n := lines.Load(); n != 0 {
		t.Fatalf("80 repeatable refusals wrote %d log line(s); a caller who never spent an attempt "+
			"must not be able to write to the owner's disk", n)
	}
}

// The other half, so the test above is not passing because nothing is ever logged: a refusal the
// OWNER can act on does reach their log, and it is bounded by the window rather than by the caller.
func TestASpentAttemptIsLoggedForTheOwner(t *testing.T) {
	window, store, own := handlerFixture(t)
	window.Open(time.Minute)
	_, phone := mint(t, "a phone")

	var written strings.Builder
	log.SetOutput(&written)
	log.SetFlags(0)
	t.Cleanup(func() { log.SetOutput(io.Discard); log.SetFlags(log.LstdFlags) })

	exchange(t, window, store, own, enroll.Request{
		Verb: "enroll", Token: b64(make([]byte, 32)), Certificate: b64(phone.Raw)})

	got := written.String()
	if strings.Count(got, "\n") != 1 {
		t.Fatalf("one attempt must produce one line, got %q", got)
	}
	// **It has to NAME the cause**, and the first version did not. ErrRefused hides the cause from
	// Error() on purpose, so the line read "enrolment refused: enroll: enrolment refused" - the
	// handler claimed to give the owner a diagnosis and gave them the wire's answer twice. causeText
	// is the accessor that exists for this, and it is reachable from the log path only.
	if !strings.Contains(got, "token") {
		t.Fatalf("the owner is not told why their pairing failed: %q", got)
	}
	if strings.Count(got, "enrolment refused") > 1 {
		t.Fatalf("the cause was not translated, only the wire's refusal repeated: %q", got)
	}
	// Never the caller's bytes. A fingerprint identifies the owner's phone and this log is a file.
	if strings.Contains(got, pinning.Fingerprint(phone)) {
		t.Fatalf("the log carries a fingerprint: %q", got)
	}
}

// --- The rest of the wire contract ---------------------------------------------------------------

// A field this bridge does not understand is refused rather than ignored, exactly as api.Decode
// treats one. It is also refused BEFORE the gate, so probing for fields is free for the attacker in
// the only sense that matters here - it costs the owner's window nothing.
func TestAnUnknownFieldIsRefused(t *testing.T) {
	window, store, own := handlerFixture(t)
	code, _ := window.Open(time.Minute)
	_, phone := mint(t, "a phone")

	line := `{"verb":"enroll","token":"` + b64(code[:]) + `","certificate":"` + b64(phone.Raw) +
		`","admin":true}` + "\n"
	if reply := exchangeRaw(t, window, store, own, line); reply.OK {
		t.Fatal("an unknown field must be refused, not ignored")
	}
	if len(store.Peers()) != 0 {
		t.Fatal("nothing may be pinned")
	}
	if !window.IsOpen() {
		t.Fatal("a request that never parsed must not spend the token inside it")
	}
}

// The name is what the owner will see in a menu and it is written to a file on their disk, so it is
// checked rather than stored as sent. A control character would be an escape sequence in a string
// their tools print.
func TestAHostileNameIsRefusedAndPinsNothing(t *testing.T) {
	for _, name := range []string{"a\x1b[2Jphone", "a\nphone", strings.Repeat("x", 65)} {
		window, store, own := handlerFixture(t)
		code, _ := window.Open(time.Minute)
		_, phone := mint(t, "a phone")

		reply := exchange(t, window, store, own, enroll.Request{
			Verb: "enroll", Token: b64(code[:]), Certificate: b64(phone.Raw), Name: name})
		if reply.OK {
			t.Fatalf("%q must be refused as a name", name)
		}
		if len(store.Peers()) != 0 {
			t.Fatalf("%q was pinned", name)
		}
	}
}

// A phone that sends no name still pairs. The field is optional, and the menu falls back to the
// fingerprint - refusing here would make an optional field mandatory.
func TestAPhoneWithNoNameStillPairs(t *testing.T) {
	window, store, own := handlerFixture(t)
	code, _ := window.Open(time.Minute)
	_, phone := mint(t, "a phone")

	reply := exchange(t, window, store, own, enroll.Request{
		Verb: "enroll", Token: b64(code[:]), Certificate: b64(phone.Raw)})
	if !reply.OK {
		t.Fatalf("a phone with no name was refused: %s", reply.Error)
	}
	if len(store.Peers()) != 1 || store.Peers()[0].Name != "" {
		t.Fatalf("want one unnamed peer, got %+v", store.Peers())
	}
}

// The callback is what the Mac app's menu is fed from, so it must see what was STORED - after the
// write, with the fingerprint and the name the store holds - rather than what was asked for.
func TestThePairedCallbackSeesWhatWasStored(t *testing.T) {
	window, store, own := handlerFixture(t)
	code, _ := window.Open(time.Minute)
	_, phone := mint(t, "a phone")

	var got []trust.Peer
	line, err := json.Marshal(enroll.Request{
		Verb: "enroll", Token: b64(code[:]), Certificate: b64(phone.Raw), Name: "the owner's phone"})
	if err != nil {
		t.Fatal(err)
	}
	reply, err := rawExchange(t, window, store, own, string(line)+"\n", func(p trust.Peer) {
		got = append(got, p)
	})
	if err != nil {
		t.Fatal(err)
	}
	if !reply.OK {
		t.Fatalf("refused: %s", reply.Error)
	}
	if len(got) != 1 {
		t.Fatalf("the callback must fire exactly once, fired %d times", len(got))
	}
	if got[0].Fingerprint != pinning.Fingerprint(phone) || got[0].Name != "the owner's phone" {
		t.Fatalf("the callback was handed %+v", got[0])
	}
	if len(store.Peers()) != 1 || store.Peers()[0].Fingerprint != got[0].Fingerprint {
		t.Fatal("the callback and the store disagree about what was paired")
	}
}

// A refusal must never fire it. The menu saying a phone paired when nothing was written is the one
// failure the owner cannot detect from the phone's side.
func TestThePairedCallbackDoesNotFireOnARefusal(t *testing.T) {
	window, store, own := handlerFixture(t)
	window.Open(time.Minute)
	_, phone := mint(t, "a phone")

	fired := false
	line, err := json.Marshal(enroll.Request{
		Verb: "enroll", Token: b64(make([]byte, 32)), Certificate: b64(phone.Raw)})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := rawExchange(t, window, store, own, string(line)+"\n", func(trust.Peer) {
		fired = true
	}); err != nil {
		t.Fatal(err)
	}
	if fired {
		t.Fatal("a refused enrolment told the menu a phone had paired")
	}
}

// One phone, and pairing a second replaces it. That is store.Replace rather than Add, and it is the
// v1 rule the owner sees: the phone they just scanned with is the one that reaches this laptop.
func TestASecondEnrolmentReplacesTheFirst(t *testing.T) {
	window, store, own := handlerFixture(t)
	_, first := mint(t, "the first phone")
	_, second := mint(t, "the second phone")

	for _, phone := range []*x509.Certificate{first, second} {
		code, _ := window.Open(time.Minute)
		reply := exchange(t, window, store, own, enroll.Request{
			Verb: "enroll", Token: b64(code[:]), Certificate: b64(phone.Raw)})
		if !reply.OK {
			t.Fatalf("refused: %s", reply.Error)
		}
	}

	peers := store.Peers()
	if len(peers) != 1 || peers[0].Fingerprint != pinning.Fingerprint(second) {
		t.Fatalf("want only the phone that paired last, got %+v", peers)
	}
}

// writerFunc counts what the log writes without keeping any of it.
type writerFunc func(p []byte) (int, error)

func (f writerFunc) Write(p []byte) (int, error) { return f(p) }

// **The bound the first version of this handler CLAIMED and did not have.**
//
// It logged every refusal that reached Window.Consume and asserted a bound of maxAttempts + 1 lines
// per window, citing internal/listener's no-unbounded-write rule. Two of Consume's three causes burn
// no attempt: "no window is open" and "the window expired". So the counter never moved, the bound
// never applied, and one line per connection was available to anyone who could reach the port while
// the owner's panel was shut.
//
// The measured shape of it, which is what this reproduces: hold connections open, let the window go
// away, then flush them. Every one reaches the gate, every one is refused for a cause that costs it
// nothing, and every one used to write a line. **200 connections produced 200 lines.** The only brake
// was the listener's twelve-slot handshake semaphore, which is released before the branch.
//
// The fix is the one internal/listener already made for failed handshakes: count them, report an
// aggregate per interval. So the assertion here is not "fewer lines", it is a CEILING that does not
// move with the number of callers.
func TestRefusalsThatCostNothingAreCountedNotLogged(t *testing.T) {
	const callers = 200

	for _, tc := range []struct {
		name  string
		state func(*enroll.Window)
	}{
		{"no window was ever open", func(*enroll.Window) {}},
		{"the owner closed the panel", func(w *enroll.Window) { w.Open(time.Minute); w.Close() }},
		{"five wrong tokens shut it", func(w *enroll.Window) {
			w.Open(time.Minute)
			for i := 0; i < 5; i++ {
				_ = w.Consume(make([]byte, 32))
			}
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			window, store, own := handlerFixture(t)
			tc.state(window)

			var lines atomic.Int32
			log.SetOutput(writerFunc(func(p []byte) (int, error) { lines.Add(1); return len(p), nil }))
			t.Cleanup(func() { log.SetOutput(io.Discard) })

			for i := 0; i < callers; i++ {
				exchange(t, window, store, own, enroll.Request{
					Verb: "enroll", Token: b64(make([]byte, 32))})
			}

			// One line per interval, not per caller. The interval is a minute and this loop takes
			// milliseconds, so the honest ceiling here is one - and the point is that it does not
			// grow with `callers`.
			n := lines.Load()
			// Recorded rather than merely asserted: the number this used to be was exactly `callers`,
			// and a reader of this test should be able to see which side of that it is on.
			t.Logf("%d callers refused by a window that was not open: %d log lines", callers, n)
			if n > 1 {
				t.Fatalf("%d callers wrote %d log lines; a refusal that spends no attempt must be "+
					"counted rather than written, or reaching the port is a write against the "+
					"owner's disk", callers, n)
			}
			// And the attempt counter is untouched, which is the other half of why these must not be
			// logged: nothing the caller did cost them anything.
		})
	}
}

// **A certificate that parses but can never authenticate anybody must not be pinned.**
//
// This handler is the ONLY place in the design that can refuse one. Downstream everything keeps bytes
// or compares them, so an unusable certificate is stored, the phone is told `ok:true`, it cannot
// connect - and because pairing is Replace, the owner's working phone has already been evicted by the
// one that cannot work. Both of the cases below were accepted by the first version.
func TestACertificateThatCanNeverAuthenticateIsRefused(t *testing.T) {
	for _, tc := range []struct {
		name string
		der  func(*testing.T) []byte
	}{
		// Already outside its own validity window: pinnedPeers checks the dates on every handshake, so
		// this one is refused there for the whole of its life. Pinning it promises something already
		// false.
		{"already expired", func(t *testing.T) []byte { return expiredCertificate(t) }},
		// x509 leaves PublicKey nil for an algorithm it does not implement, and returns no error for
		// it. There is nothing to prove possession of.
		{"a public key nothing implements", func(t *testing.T) []byte { return unknownAlgorithm(t) }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			window, store, own := handlerFixture(t)
			// The owner's real phone, already paired, which must survive.
			_, paired := mint(t, "the owner's phone")
			if err := store.Replace(trust.Peer{
				Fingerprint:    pinning.Fingerprint(paired),
				CertificateDER: paired.Raw,
				Name:           "the owner's phone",
				PairedAt:       time.Unix(1, 0),
			}); err != nil {
				t.Fatal(err)
			}

			code, _ := window.Open(time.Minute)
			reply := exchange(t, window, store, own, enroll.Request{
				Verb: "enroll", Token: b64(code[:]), Certificate: b64(tc.der(t))})

			if reply.OK {
				t.Fatal("a certificate that can never complete a handshake was accepted")
			}
			// And the eviction did not happen: Replace is what pairing calls, so a refusal here has to
			// be a refusal BEFORE the store is touched or the owner loses their phone to a caller whose
			// certificate does not work.
			peers := store.Peers()
			if len(peers) != 1 || peers[0].Name != "the owner's phone" {
				t.Fatalf("the owner's phone was evicted by an unusable certificate: %+v", peers)
			}
		})
	}
}

// The control: a certificate the trust model accepts for its own reasons is still accepted. The
// extended-key-usage question belongs to the trust model rather than to this handler, and widening
// these two checks into a general policy here is what this asserts has not happened.
func TestAnUnusualButUsableCertificateStillPairs(t *testing.T) {
	window, store, own := handlerFixture(t)
	code, _ := window.Open(time.Minute)

	der := caCertificate(t)
	reply := exchange(t, window, store, own, enroll.Request{
		Verb: "enroll", Token: b64(code[:]), Certificate: b64(der)})
	if !reply.OK {
		t.Fatalf("this handler refused a certificate on grounds that are the trust model's: %s", reply.Error)
	}
	if len(store.Peers()) != 1 {
		t.Fatal("nothing was pinned")
	}
}

// The name is echoed as STORED. keys.Label trims, so a phone that sent whitespace around its name
// would otherwise display a name the owner's menu does not have.
func TestTheReplyEchoesTheNameAsStored(t *testing.T) {
	window, store, own := handlerFixture(t)
	code, _ := window.Open(time.Minute)
	_, phone := mint(t, "a phone")

	reply := exchange(t, window, store, own, enroll.Request{
		Verb: "enroll", Token: b64(code[:]), Certificate: b64(phone.Raw), Name: "  the owner's phone  "})
	if !reply.OK {
		t.Fatalf("refused: %s", reply.Error)
	}
	if reply.Name != "the owner's phone" {
		t.Fatalf("the reply says %q", reply.Name)
	}
	if store.Peers()[0].Name != reply.Name {
		t.Fatalf("the reply and the menu disagree: %q against %q", reply.Name, store.Peers()[0].Name)
	}
}

// expiredCertificate is a real self-signed certificate whose validity window has already passed.
func expiredCertificate(t *testing.T) []byte {
	t.Helper()
	// Mint backdates NotBefore by a minute and sets NotAfter to now+validFor, so a short lifetime and
	// a wait produce a genuinely lapsed certificate rather than a hand-built one.
	id, err := pinning.Mint("a lapsed phone", 200*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := pinning.LoadPeer(id.CertPEM)
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(300 * time.Millisecond)
	return cert.Raw
}

// unknownAlgorithm is a real certificate with the id-ecPublicKey OID replaced by one nobody
// implements, so x509 parses it and leaves PublicKey nil.
//
// One byte, in place, so no length in the DER changes. The signature no longer matches what it covers,
// which is irrelevant: nothing in this design verifies a self-signed certificate's signature, and
// that is precisely why the parse succeeding is not evidence the certificate is usable.
func unknownAlgorithm(t *testing.T) []byte {
	t.Helper()
	_, cert := mint(t, "a phone with an algorithm nobody has")
	der := bytesClone(cert.Raw)

	// 1.2.840.10045.2.1, id-ecPublicKey, as it appears inside the SubjectPublicKeyInfo.
	oid := []byte{0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01}
	at := indexOf(der, oid)
	if at < 0 {
		t.Fatal("the public-key algorithm OID is not where this test expects it")
	}
	// ...2.99, which is not assigned to anything Go implements.
	der[at+len(oid)-1] = 0x63

	parsed, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatalf("the patched certificate no longer parses, so it tests the wrong refusal: %v", err)
	}
	if parsed.PublicKey != nil {
		t.Fatal("the patched certificate still has a public key, so this tests nothing")
	}
	return der
}

// caCertificate is a certificate the pinning model would never have minted - a CA, with no client
// extended key usage - and which is nonetheless usable: a real key, inside its dates.
func caCertificate(t *testing.T) []byte {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := x509.Certificate{
		SerialNumber:          big.NewInt(7),
		Subject:               pkix.Name{CommonName: "a phone that is also a CA"},
		NotBefore:             time.Now().Add(-time.Minute),
		NotAfter:              time.Now().Add(time.Hour),
		KeyUsage:              x509.KeyUsageCertSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return der
}

func bytesClone(b []byte) []byte {
	out := make([]byte, len(b))
	copy(out, b)
	return out
}

func indexOf(haystack, needle []byte) int {
	for i := 0; i+len(needle) <= len(haystack); i++ {
		if bytesEqual(haystack[i:i+len(needle)], needle) {
			return i
		}
	}
	return -1
}
