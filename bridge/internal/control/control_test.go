package control

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"slices"
	"sort"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/enroll"
	"github.com/isachivka/agterm-remote/bridge/internal/pinning"
	"github.com/isachivka/agterm-remote/bridge/internal/trust"
)

// fakeFit is the bridge as this door sees it: a flag and a restore that records being called.
type fakeFit struct {
	inForce  bool
	restores int
	fail     bool
}

func (f *fakeFit) FitInForce() bool { return f.inForce }

func (f *fakeFit) RestoreFit(context.Context) error {
	if f.fail {
		return errors.New("agterm would not resize the window")
	}
	f.restores++
	f.inForce = false
	return nil
}

// shortDir is NOT t.TempDir(): that embeds the test's name in the path, and a unix socket path is
// capped at 104 bytes - long test names push it over and the failure reads as `bind: invalid
// argument`. Measured here first, which is why Listen now checks the length and says so.
func shortDir(t *testing.T) string {
	t.Helper()
	dir, err := os.MkdirTemp("", "bos")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	return dir
}

func serving(t *testing.T, fit Fit) string {
	t.Helper()
	return servingWith(t, fit, nil)
}

// servingWith is the same door with a pairing half behind it. nil is a bridge that serves the
// restore verb and no pairing, which is what every test above this line is about.
func servingWith(t *testing.T, fit Fit, pairing *Pairing) string {
	t.Helper()
	dir := shortDir(t)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)

	ln, err := Listen(ctx, dir, fit, pairing)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	return SocketPath(dir)
}

func ask(t *testing.T, path, verb string) response {
	t.Helper()
	conn, err := net.DialTimeout("unix", path, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))

	if _, err := io.WriteString(conn, fmt.Sprintf("{\"verb\":%q}\n", verb)); err != nil {
		t.Fatal(err)
	}
	line, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil && line == "" {
		t.Fatal(err)
	}
	var resp response
	if err := json.Unmarshal([]byte(line), &resp); err != nil {
		t.Fatalf("unparseable reply %q: %v", line, err)
	}
	return resp
}

func TestRestoreAsksTheBridgeAndReportsIt(t *testing.T) {
	fit := &fakeFit{inForce: true}
	resp := ask(t, serving(t, fit), VerbRestore)

	if !resp.OK || !resp.Restored {
		t.Fatalf("got %+v, want a restore", resp)
	}
	if fit.restores != 1 {
		t.Errorf("the bridge was asked %d times, want once", fit.restores)
	}
}

// **Nothing in force is an ordinary answer, not a failure.** The owner may run this when the fit is
// already off, and a command that resized their window anyway would be acting on a guess about a
// window they are sitting in front of.
func TestWithNoFitInForceItDoesNothingAndSaysSo(t *testing.T) {
	fit := &fakeFit{inForce: false}
	resp := ask(t, serving(t, fit), VerbRestore)

	if !resp.OK {
		t.Errorf("doing nothing was reported as a failure: %+v", resp)
	}
	if resp.Restored {
		t.Error("claimed to restore a window with no fit in force")
	}
	if fit.restores != 0 {
		t.Errorf("resized %d times with nothing in force", fit.restores)
	}
}

// The set is closed. Anything outside it is refused, and the refusal names no alternatives.
func TestAVerbOutsideTheSetIsRefused(t *testing.T) {
	fit := &fakeFit{inForce: true}
	path := serving(t, fit)

	for _, verb := range []string{"resize", "sessions", "screen", "type", "calibrate", ""} {
		resp := ask(t, path, verb)
		if resp.OK {
			t.Errorf("verb %q was accepted by the local door", verb)
		}
		if fit.restores != 0 {
			t.Fatalf("verb %q reached the bridge", verb)
		}
	}
}

// **The permission IS the authentication**, so it is asserted rather than assumed. A umask of 0 would
// otherwise produce a socket anyone on the machine could open.
func TestTheSocketIsOwnerOnly(t *testing.T) {
	path := serving(t, &fakeFit{})

	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Errorf("socket mode is %o, want 600 - anyone on this machine could undo the owner's window", perm)
	}
}

// A failed restore is reported as one. The bridge's own words are passed through, because they name
// what went wrong with the window and that is what the person at the keyboard can act on.
func TestAFailedRestoreIsNotReportedAsSuccess(t *testing.T) {
	fit := &fakeFit{inForce: true, fail: true}
	resp := ask(t, serving(t, fit), VerbRestore)

	if resp.OK || resp.Restored {
		t.Fatalf("a failed restore came back as %+v", resp)
	}
	if resp.Error == "" {
		t.Error("the refusal said nothing about what went wrong")
	}
}

// A second bridge against the same directory must not silently take the door from the first: a stale
// socket and a live one look identical on disk, and clobbering the live one would leave the owner's
// running bridge unreachable from their palette command with nothing to point at.
func TestItRefusesToTakeOverASocketSomethingElseIsServing(t *testing.T) {
	dir := shortDir(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	first, err := Listen(ctx, dir, &fakeFit{}, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()

	if _, err := Listen(ctx, dir, &fakeFit{}, nil); err == nil {
		t.Fatal("a second listener took over a socket that was already being served")
	}
}

// **After a restart, a client can CONNECT.** The property, stated as the client experiences it.
//
// Not "the second listener exists" - that was true all afternoon while the owner's palette command
// reported no bridge listening. Go's UnixListener unlinks its path on Close without checking whether
// the file there is still its own, so the outgoing instance deleted the incoming instance's socket and
// left a listener nobody could reach: fd open, process healthy, log claiming success.
//
// The sequence below is what a restart does, in order. It fails without SetUnlinkOnClose(false) - and
// it fails on the DIAL, which is the only assertion that could have caught this.
func TestAfterARestartAClientCanStillConnect(t *testing.T) {
	dir := shortDir(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// The outgoing instance, serving.
	outgoing, err := Listen(ctx, dir, &fakeFit{}, nil)
	if err != nil {
		t.Fatal(err)
	}

	// The incoming instance. A restarting bridge finds the path free - the old process is on its way
	// out - removes anything stale, and binds a fresh inode at the same path.
	if err := os.Remove(SocketPath(dir)); err != nil {
		t.Fatal(err)
	}
	incoming, err := Listen(ctx, dir, &fakeFit{inForce: true}, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer incoming.Close()

	// The outgoing instance finishes shutting down. This is the line that used to delete the socket
	// the incoming instance had just bound.
	if err := outgoing.Close(); err != nil {
		t.Fatal(err)
	}

	// THE DIAL. Everything above can succeed while this fails, which is exactly what happened.
	conn, err := net.DialTimeout("unix", SocketPath(dir), time.Second)
	if err != nil {
		t.Fatalf("a client cannot reach the bridge after a restart: %v", err)
	}
	defer conn.Close()

	// And it is the INCOMING instance answering, not a leftover: this one has a fit in force.
	resp := ask(t, SocketPath(dir), VerbRestore)
	if !resp.OK || !resp.Restored {
		t.Errorf("the surviving socket is not the new instance's: %+v", resp)
	}
}

// --- The pairing verbs -------------------------------------------------------------------------
//
// These four are the seam between the two halves of the product: the Mac app owns the address, the
// onboarding and the QR panel; the bridge owns the identity, the trust store and the enrolment
// window. They meet here, on a socket at mode 0600 that no caller off this machine has an address
// for - which is the only reason a verb that opens an enrolment window may exist at all.

// bridge is a real one, as far as this door can see: a real window on an injected clock, a real
// trust store on a real disk, and a real minted certificate.
//
// Deliberately not fakes. Two of the properties these tests exist to pin - that the payload carries
// the SHA-256 of the certificate the bridge is actually serving, and that its expiry is the one the
// window will actually enforce - are only worth asserting against the real things. A fake window
// that returns whatever expiry the test wants proves nothing about the clamp.
type bridge struct {
	pairing *Pairing
	window  *enroll.Window
	peers   *trust.Store
	leaf    *x509.Certificate
	now     time.Time
	agterm  bool
}

// listenAddress is RFC 5737 documentation space. The address a phone dials belongs to whoever runs
// the bridge and never appears in this repository - see scripts/check-no-addresses.sh.
const listenAddress = "203.0.113.5:8443"

func newBridge(t *testing.T) *bridge {
	t.Helper()

	id, err := pinning.Mint("agterm-remote bridge under test", time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	leaf, err := pinning.LoadPeer(id.CertPEM)
	if err != nil {
		t.Fatal(err)
	}
	peers, err := trust.Open(shortDir(t))
	if err != nil {
		t.Fatal(err)
	}

	b := &bridge{
		peers:  peers,
		leaf:   leaf,
		now:    time.Unix(1_700_000_000, 0).UTC(),
		agterm: true,
	}
	b.window = enroll.NewWindow(func() time.Time { return b.now })
	b.pairing = &Pairing{
		Listening:   listenAddress,
		Window:      b.window,
		Peers:       peers,
		Certificate: leaf,
		Agterm:      func(context.Context) bool { return b.agterm },
	}
	return b
}

// pair puts a phone in the trust store the way enrolment does, and returns its fingerprint.
func (b *bridge) pair(t *testing.T, name string) string {
	t.Helper()
	id, err := pinning.Mint(name, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := pinning.LoadPeer(id.CertPEM)
	if err != nil {
		t.Fatal(err)
	}
	peer := trust.Peer{
		Fingerprint:    pinning.Fingerprint(cert),
		CertificateDER: cert.Raw,
		Name:           name,
		PairedAt:       b.now,
	}
	if err := b.peers.Replace(peer); err != nil {
		t.Fatal(err)
	}
	return peer.Fingerprint
}

// call sends one raw line and returns the reply as the bytes that came back, decoded into a generic
// map.
//
// A map rather than a struct on purpose: these tests assert the SHAPE the Mac app will parse -
// which keys exist and which do not - and decoding into the package's own reply type would assert
// only that this package agrees with itself.
func call(t *testing.T, path, line string) map[string]any {
	t.Helper()
	raw := callRaw(t, path, line)
	var got map[string]any
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatalf("unparseable reply %q: %v", raw, err)
	}
	return got
}

func callRaw(t *testing.T, path, line string) []byte {
	t.Helper()
	conn, err := net.DialTimeout("unix", path, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))

	if _, err := io.WriteString(conn, line+"\n"); err != nil {
		t.Fatal(err)
	}
	reply, err := bufio.NewReader(conn).ReadBytes('\n')
	if err != nil && len(reply) == 0 {
		t.Fatalf("no reply to %q: %v", line, err)
	}
	return reply
}

// keys reports the reply's field names, sorted, for an assertion about the whole shape rather than
// about the one field a test happens to read.
func keys(m map[string]any) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

func wantKeys(t *testing.T, got map[string]any, want ...string) {
	t.Helper()
	sort.Strings(want)
	if have := keys(got); !slices.Equal(have, want) {
		t.Errorf("reply carries %v, want exactly %v", have, want)
	}
}

// **status is what the menu bar is drawn from**, so it answers three questions in one line: where a
// phone would dial, which phones are paired, and whether agterm is answering at all.
func TestStatusNamesTheAddressThePairedPhonesAndAgterm(t *testing.T) {
	b := newBridge(t)
	fingerprint := b.pair(t, "a phone")
	path := servingWith(t, &fakeFit{}, b.pairing)

	got := call(t, path, `{"verb":"status"}`)
	wantKeys(t, got, "listening", "paired", "agterm", "window")

	if got["listening"] != listenAddress {
		t.Errorf("listening is %v, want %q", got["listening"], listenAddress)
	}
	if got["agterm"] != true {
		t.Errorf("agterm is %v, want true", got["agterm"])
	}

	paired, ok := got["paired"].([]any)
	if !ok || len(paired) != 1 {
		t.Fatalf("paired is %#v, want one phone", got["paired"])
	}
	phone, _ := paired[0].(map[string]any)
	wantKeys(t, phone, "fingerprint", "name", "paired_at")
	if phone["fingerprint"] != fingerprint {
		t.Errorf("fingerprint is %v, want %q", phone["fingerprint"], fingerprint)
	}
	if phone["name"] != "a phone" {
		t.Errorf("name is %v, want %q", phone["name"], "a phone")
	}
	if at, _ := phone["paired_at"].(float64); int64(at) != b.now.Unix() {
		t.Errorf("paired_at is %v, want %d", phone["paired_at"], b.now.Unix())
	}
}

// An unpaired bridge is the ordinary first state, and `paired` must be an empty LIST rather than
// null: a menu that iterates over it would otherwise have to special-case the only state every new
// owner is in.
func TestStatusOnABridgeNobodyHasPairedReportsAnEmptyList(t *testing.T) {
	b := newBridge(t)
	path := servingWith(t, &fakeFit{}, b.pairing)

	raw := callRaw(t, path, `{"verb":"status"}`)
	if !bytes.Contains(raw, []byte(`"paired":[]`)) {
		t.Errorf("an unpaired bridge reports %s", raw)
	}
}

// **The panel cannot guess why a code stopped working, so status says.** "It expired" and "it closed
// after five wrong attempts" are different things to tell an owner and lead to different next moves.
func TestStatusSaysWhyACodeStoppedWorking(t *testing.T) {
	b := newBridge(t)
	path := servingWith(t, &fakeFit{}, b.pairing)

	window := func() map[string]any {
		t.Helper()
		got := call(t, path, `{"verb":"status"}`)
		w, ok := got["window"].(map[string]any)
		if !ok {
			t.Fatalf("status carries no window: %#v", got)
		}
		return w
	}

	if w := window(); w["open"] != false || w["ended"] != nil {
		t.Errorf("a bridge nobody has paired from reports %#v", w)
	}

	_, expiry := b.window.Open(enroll.MaxTTL)
	w := window()
	if w["open"] != true {
		t.Errorf("an open window reports %#v", w)
	}
	if at, _ := w["expires_at"].(float64); int64(at) != expiry.Unix() {
		t.Errorf("expires_at is %v, want %d", w["expires_at"], expiry.Unix())
	}
	if left, _ := w["attempts_left"].(float64); int(left) != enroll.MaxAttempts {
		t.Errorf("attempts_left is %v, want %d", w["attempts_left"], enroll.MaxAttempts)
	}

	// Five wrong tokens. **Only Consume burns one** - a dropped connection or a retried handshake
	// does not - which makes five looser than it reads and is why the count is reported rather than
	// assumed.
	for i := 0; i < enroll.MaxAttempts; i++ {
		_ = b.window.Consume(make([]byte, 32))
	}
	if w := window(); w["open"] != false || w["ended"] != "attempts" {
		t.Errorf("after five wrong tokens the window reports %#v", w)
	}

	// And expiry, which is the other thing an owner is told, and it must not read as the first.
	b.window.Open(enroll.MaxTTL)
	b.now = b.now.Add(enroll.MaxTTL + time.Second)
	if w := window(); w["open"] != false || w["ended"] != "expired" {
		t.Errorf("after the window ran out it reports %#v", w)
	}
}

// **pair-open is the only thing anywhere that can open an enrolment window**, and what it hands back
// is the text the QR code carries - which the phone has to be able to read.
func TestPairOpenReturnsAPayloadThePhoneCanRead(t *testing.T) {
	b := newBridge(t)
	path := servingWith(t, &fakeFit{}, b.pairing)

	got := call(t, path, `{"verb":"pair-open","ttl_seconds":300}`)
	wantKeys(t, got, "payload", "expires_at")

	text, _ := got["payload"].(string)
	payload, err := enroll.DecodeText(text)
	if err != nil {
		t.Fatalf("the phone cannot read the code this bridge would show: %v", err)
	}
	if payload.Host != "203.0.113.5" || payload.Port != 8443 {
		t.Errorf("the code points at %s:%d, want the address the bridge is listening on",
			payload.Host, payload.Port)
	}
	if !b.window.IsOpen() {
		t.Error("a code was minted against a window that is not open")
	}
	// The token in the code is the one the window will accept, and it is accepted exactly once.
	if err := b.window.Consume(payload.Token[:]); err != nil {
		t.Fatalf("the token in the code was refused by the window that minted it: %v", err)
	}
}

// **The fingerprint in the code is the bridge's OWN certificate**, SHA-256 over its DER, read from
// where the bridge holds it rather than derived a second time.
//
// This is what the phone checks the TLS server certificate against. A mismatch is not a subtle bug:
// every pairing fails, and it fails with a message about a wrong certificate, which points the owner
// at their network rather than at this line.
func TestPairOpenCarriesTheFingerprintOfTheCertificateTheBridgeServes(t *testing.T) {
	b := newBridge(t)
	path := servingWith(t, &fakeFit{}, b.pairing)

	got := call(t, path, `{"verb":"pair-open","ttl_seconds":300}`)
	payload, err := enroll.DecodeText(got["payload"].(string))
	if err != nil {
		t.Fatal(err)
	}

	want := sha256.Sum256(b.leaf.Raw)
	if payload.Fingerprint != want {
		t.Fatalf("the code fingerprints %x, the bridge serves %x", payload.Fingerprint, want)
	}
}

// **The code may never advertise a longer life than the window has.**
//
// Window.Open clamps to five minutes and returns the expiry it will enforce. Nothing forces a payload
// minter to carry THAT value rather than the one it asked for, and a code claiming an hour against a
// five-minute window strands somebody mid-pairing: they scan it, the bridge has stopped listening,
// and the phone has no way to tell that from a network fault.
func TestPairOpenAdvertisesTheExpiryTheWindowWillEnforce(t *testing.T) {
	b := newBridge(t)
	path := servingWith(t, &fakeFit{}, b.pairing)

	got := call(t, path, `{"verb":"pair-open","ttl_seconds":3600}`)
	payload, err := enroll.DecodeText(got["payload"].(string))
	if err != nil {
		t.Fatal(err)
	}

	want := b.now.Add(enroll.MaxTTL)
	if !payload.Expiry.Equal(want) {
		t.Errorf("the code says %s, the window closes at %s", payload.Expiry, want)
	}
	if at, _ := got["expires_at"].(float64); int64(at) != want.Unix() {
		t.Errorf("expires_at is %v, want %d", got["expires_at"], want.Unix())
	}
	// And the reply and the code are the same instant, because the Mac app draws a countdown from
	// one and the phone reads the other.
	if at, _ := got["expires_at"].(float64); int64(at) != payload.Expiry.Unix() {
		t.Errorf("the reply says %v and the code says %d", got["expires_at"], payload.Expiry.Unix())
	}
	// The window itself agrees, which is the whole point of taking the value from Open.
	if state := b.window.State(); !state.Expiry.Equal(payload.Expiry) {
		t.Errorf("the window enforces %s, the code advertises %s", state.Expiry, payload.Expiry)
	}
}

// A ttl of zero would mint a window that is already shut, and a code for it. Refused rather than
// served, because the owner would be looking at a QR code that cannot work and nothing anywhere
// would say so.
func TestPairOpenRefusesATTLThatWouldMintADeadCode(t *testing.T) {
	b := newBridge(t)
	path := servingWith(t, &fakeFit{}, b.pairing)

	for _, line := range []string{
		`{"verb":"pair-open"}`,
		`{"verb":"pair-open","ttl_seconds":0}`,
		`{"verb":"pair-open","ttl_seconds":-1}`,
	} {
		got := call(t, path, line)
		if got["error"] == nil {
			t.Errorf("%s was answered %#v", line, got)
		}
		if got["payload"] != nil {
			t.Errorf("%s produced a code", line)
		}
		if b.window.IsOpen() {
			t.Fatalf("%s opened a window", line)
		}
	}
}

// pair-close is what the owner closing the panel does, and it says whether there was anything to
// close. Nothing open is an ordinary answer rather than a failure - the same reading the restore
// verb takes of a window with no fit in force.
func TestPairCloseShutsTheWindowAndSaysWhetherOneWasOpen(t *testing.T) {
	b := newBridge(t)
	path := servingWith(t, &fakeFit{}, b.pairing)
	b.window.Open(enroll.MaxTTL)

	got := call(t, path, `{"verb":"pair-close"}`)
	wantKeys(t, got, "ok", "closed")
	if got["ok"] != true || got["closed"] != true {
		t.Errorf("closing an open window replied %#v", got)
	}
	if b.window.IsOpen() {
		t.Error("the window is still open after pair-close")
	}
	if state := b.window.State(); state.Ended != enroll.EndedClosed {
		t.Errorf("the window ended as %q, want %q", state.Ended, enroll.EndedClosed)
	}

	// Again, with nothing open.
	got = call(t, path, `{"verb":"pair-close"}`)
	if got["ok"] != true {
		t.Errorf("closing nothing was reported as a failure: %#v", got)
	}
	if got["closed"] != false {
		t.Errorf("claimed to close a window that was not open: %#v", got)
	}
}

// **unpair is total in v1**, because trust.Store.Replace keeps exactly one peer: unpairing the phone
// leaves the bridge answering nobody. So the reply says what it did rather than `ok`, and what it
// says is how many phones are left.
func TestUnpairRemovesThePhoneAndSaysWhatIsLeft(t *testing.T) {
	b := newBridge(t)
	fingerprint := b.pair(t, "a phone")
	path := servingWith(t, &fakeFit{}, b.pairing)

	got := call(t, path, fmt.Sprintf(`{"verb":"unpair","fingerprint":%q}`, fingerprint))
	wantKeys(t, got, "ok", "unpaired", "remaining")
	if got["ok"] != true || got["unpaired"] != true {
		t.Fatalf("unpairing the paired phone replied %#v", got)
	}
	if left, _ := got["remaining"].(float64); int(left) != 0 {
		t.Errorf("remaining is %v, want 0 - Replace keeps exactly one peer, so unpairing is total",
			got["remaining"])
	}
	if peers := b.peers.Peers(); len(peers) != 0 {
		t.Errorf("the store still holds %d phones", len(peers))
	}
}

// **An unpair that removed nothing is not a success.**
//
// trust.Store.Remove is deliberately silent about a fingerprint it does not hold - the caller asked
// for it to be gone and it is gone - and that reading is right for the store and wrong here. The Mac
// app got its list from `status`; a fingerprint it did not find is a typo, a stale panel or a second
// window that already unpaired, and reporting `ok` for it leaves the owner believing they unpaired a
// phone that is still paired.
func TestUnpairOfAPhoneThatIsNotPairedIsNotSuccess(t *testing.T) {
	b := newBridge(t)
	kept := b.pair(t, "a phone")
	path := servingWith(t, &fakeFit{}, b.pairing)

	got := call(t, path, `{"verb":"unpair","fingerprint":"AAAA BBBB CCCC DDDD"}`)
	if got["ok"] == true {
		t.Fatalf("unpairing a phone that is not paired replied %#v", got)
	}
	if got["error"] == nil {
		t.Error("the refusal said nothing about what did not happen")
	}
	if peers := b.peers.Peers(); len(peers) != 1 || peers[0].Fingerprint != kept {
		t.Errorf("the paired phone did not survive: %#v", peers)
	}
}

// An unpair with no fingerprint at all is refused rather than read as "unpair everything". The verb
// is destructive and the empty string is not an identifier.
func TestUnpairWithoutAFingerprintUnpairsNobody(t *testing.T) {
	b := newBridge(t)
	kept := b.pair(t, "a phone")
	path := servingWith(t, &fakeFit{}, b.pairing)

	got := call(t, path, `{"verb":"unpair"}`)
	if got["ok"] == true {
		t.Fatalf("an unpair with no fingerprint replied %#v", got)
	}
	if peers := b.peers.Peers(); len(peers) != 1 || peers[0].Fingerprint != kept {
		t.Errorf("the paired phone did not survive: %#v", peers)
	}
}

// A bridge built without a pairing half serves the restore verb and refuses these four, rather than
// panicking on a nil window. nil is a legitimate value - the same reading internal/listener takes of
// a nil enrolment handler - and it must be the SAFER one.
func TestWithoutAPairingHalfTheFourVerbsAreRefused(t *testing.T) {
	path := servingWith(t, &fakeFit{}, nil)

	for _, line := range []string{
		`{"verb":"status"}`,
		`{"verb":"pair-open","ttl_seconds":300}`,
		`{"verb":"pair-close"}`,
		`{"verb":"unpair","fingerprint":"AAAA"}`,
	} {
		got := call(t, path, line)
		if got["error"] == nil {
			t.Errorf("%s was answered %#v", line, got)
		}
	}
}

// A malformed line is refused in words rather than by a dropped connection: the app can then say
// "the bridge did not understand" instead of "the bridge is not running", which are different
// problems for the person at the keyboard.
func TestAMalformedLineIsRefusedInWords(t *testing.T) {
	b := newBridge(t)
	path := servingWith(t, &fakeFit{}, b.pairing)

	for _, line := range []string{`not json`, `{`, `[]`, `{"verb":1}`, `{"verb":"status","surprise":1}`} {
		got := call(t, path, line)
		if got["error"] == nil {
			t.Errorf("%q was answered %#v", line, got)
		}
	}
}

// **A local caller must not be able to size an allocation.** The permission on this socket says the
// caller is the owner's own app, and an unbounded read would still turn a bug in it - or anything
// running as the owner - into this process's memory. The read stops at the cap rather than buffering
// to a newline that may never come.
func TestAnOversizedLineIsRefusedWithoutBufferingIt(t *testing.T) {
	b := newBridge(t)
	path := servingWith(t, &fakeFit{}, b.pairing)

	conn, err := net.DialTimeout("unix", path, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))

	// No newline anywhere in it.
	if _, err := conn.Write(bytes.Repeat([]byte("a"), maxRequestBytes+1)); err != nil {
		t.Fatal(err)
	}
	reply, err := bufio.NewReader(conn).ReadBytes('\n')
	if err != nil && len(reply) == 0 {
		t.Fatalf("an oversized line got no reply: %v", err)
	}
	var got map[string]any
	if err := json.Unmarshal(reply, &got); err != nil {
		t.Fatalf("unparseable reply %q: %v", reply, err)
	}
	if got["error"] == nil {
		t.Errorf("an oversized line was answered %#v", got)
	}
}

// **The file permission is the whole authentication story for these verbs**, and one of them opens
// the enrolment window - the single moment this bridge will talk to a phone it has never met. A
// widened mode here hands anybody on the machine the ability to open that window while the owner is
// away from the screen.
//
// Asserted by reading the mode back off the disk, on a door that is actually serving the pairing
// half, rather than by trusting the chmod above.
func TestTheDoorThatOpensAnEnrolmentWindowIsOwnerOnly(t *testing.T) {
	b := newBridge(t)
	path := servingWith(t, &fakeFit{}, b.pairing)

	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Fatalf("the socket is %o, want 600 - anyone on this machine could open a pairing window", perm)
	}
	if info.Mode()&os.ModeSocket == 0 {
		t.Errorf("%s is not a socket", path)
	}
	// And it is a unix socket, which has no address off this machine at all: there is no port here
	// for the LAN, the router or the internet to reach.
	if _, ok := any(mustDial(t, path)).(*net.UnixConn); !ok {
		t.Error("the control door is not a unix socket")
	}
}

func mustDial(t *testing.T, path string) net.Conn {
	t.Helper()
	conn, err := net.DialTimeout("unix", path, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	return conn
}
