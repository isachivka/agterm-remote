package enroll_test

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io/fs"
	"log"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/enroll"
)

func TestConsumeSucceedsOnceAndOnlyOnce(t *testing.T) {
	w := enroll.NewWindow(time.Now)
	token, _ := w.Open(5 * time.Minute)
	if err := w.Consume(token[:]); err != nil {
		t.Fatalf("first use must succeed: %v", err)
	}
	if err := w.Consume(token[:]); err == nil {
		t.Fatal("a token must not be usable twice")
	}
	if w.IsOpen() {
		t.Fatal("the window must close on success")
	}
}

func TestExpiredTokenIsRefused(t *testing.T) {
	now := time.Unix(1000, 0)
	w := enroll.NewWindow(func() time.Time { return now })
	token, _ := w.Open(time.Minute)
	now = now.Add(61 * time.Second)
	if err := w.Consume(token[:]); err == nil {
		t.Fatal("an expired token must be refused")
	}
	if w.IsOpen() {
		t.Fatal("an expired window must report itself closed")
	}
}

func TestFiveWrongAttemptsCloseTheWindow(t *testing.T) {
	w := enroll.NewWindow(time.Now)
	token, _ := w.Open(5 * time.Minute)
	wrong := make([]byte, 32)
	for i := 0; i < 5; i++ {
		_ = w.Consume(wrong)
	}
	if w.IsOpen() {
		t.Fatal("the window must close after five wrong attempts")
	}
	if err := w.Consume(token[:]); err == nil {
		t.Fatal("the real token must not work after the window closed")
	}
}

func TestClosedWindowRefusesEverything(t *testing.T) {
	w := enroll.NewWindow(time.Now)
	if w.IsOpen() {
		t.Fatal("a fresh window must start closed")
	}
	if err := w.Consume(make([]byte, 32)); err == nil {
		t.Fatal("a closed window must refuse")
	}
}

func TestTokensDiffer(t *testing.T) {
	w := enroll.NewWindow(time.Now)
	a, _ := w.Open(time.Minute)
	w.Close()
	b, _ := w.Open(time.Minute)
	if a == b {
		t.Fatal("each window must mint a fresh token")
	}
}

// The window is refused AT its expiry, not one tick after it. The payload states an expiry as the
// instant the offer stops being accepted, and a phone that reads it will stop offering the token
// then; a bridge that still accepted it for one more second would be enforcing a different window
// from the one it advertised.
func TestTokenIsRefusedAtTheExpiryInstant(t *testing.T) {
	now := time.Unix(1000, 0)
	w := enroll.NewWindow(func() time.Time { return now })
	token, expiry := w.Open(time.Minute)

	now = expiry.Add(-time.Nanosecond)
	if !w.IsOpen() {
		t.Fatal("the window must still be open a nanosecond before its expiry")
	}
	now = expiry
	if err := w.Consume(token[:]); err == nil {
		t.Fatal("a token must be refused at the expiry instant itself")
	}
}

// The four wrong attempts a window survives are forgotten when a new one is opened. Otherwise the
// owner who mistyped a code four times would open a fresh panel and find it dies on the first slip -
// a counter that outlives the secret it was counting attempts against.
func TestOpenMintsAFreshTokenAndForgetsTheOldAttempts(t *testing.T) {
	w := enroll.NewWindow(time.Now)
	first, _ := w.Open(time.Minute)
	wrong := make([]byte, 32)
	for i := 0; i < 4; i++ {
		_ = w.Consume(wrong)
	}

	second, _ := w.Open(time.Minute)
	if first == second {
		t.Fatal("re-opening must mint a fresh token")
	}
	if err := w.Consume(first[:]); err == nil {
		t.Fatal("the token from the previous window must be worthless")
	}
	for i := 0; i < 3; i++ {
		_ = w.Consume(wrong)
	}
	if !w.IsOpen() {
		t.Fatal("the attempt count must have been reset by Open")
	}
	if err := w.Consume(second[:]); err != nil {
		t.Fatalf("the current token must still work: %v", err)
	}
}

// Task 13 puts this expiry into a Payload, and time.Time is exactly the type where "the same
// instant" and "the same value" part company: the wire carries whole seconds, the decoder returns
// UTC, and a monotonic reading compares unequal to one that has none. A window that reported an
// expiry the payload cannot represent exactly would advertise one deadline and enforce another.
func TestOpenReportsAnExpiryAPayloadCarriesExactly(t *testing.T) {
	w := enroll.NewWindow(time.Now)
	token, expiry := w.Open(5 * time.Minute)

	if canonical := expiry.Truncate(time.Second).UTC(); expiry != canonical {
		t.Fatalf("expiry %#v is not already canonical, want %#v", expiry, canonical)
	}
	want := enroll.Payload{Host: "example.test", Port: 8443, Token: token, Expiry: expiry}
	text, err := enroll.EncodeToText(want)
	if err != nil {
		t.Fatal(err)
	}
	got, err := enroll.DecodeText(text)
	if err != nil {
		t.Fatal(err)
	}
	if got != want {
		t.Fatalf("the window's expiry does not survive a payload round trip:\n got %+v\nwant %+v", got, want)
	}
}

// Two callers racing for one token must produce exactly one enrolment. The failure this guards
// against is not theoretical: a check-then-act on the token, with the state written after the
// comparison, hands the same window to both callers and pairs two phones off one code.
//
// Run this with -race -count=10; a single pass of a lock-free version can look correct.
func TestConcurrentConsumeSucceedsExactlyOnce(t *testing.T) {
	for round := 0; round < 200; round++ {
		w := enroll.NewWindow(time.Now)
		token, _ := w.Open(time.Minute)

		var (
			wg        sync.WaitGroup
			successes atomic.Int32
			start     = make(chan struct{})
		)
		for i := 0; i < 4; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				<-start
				if err := w.Consume(token[:]); err == nil {
					successes.Add(1)
				}
			}()
		}
		// Readers alongside the writers, so that -race sees IsOpen against the state Consume
		// mutates rather than only Consume against Consume.
		for i := 0; i < 2; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				<-start
				_ = w.IsOpen()
			}()
		}
		close(start)
		wg.Wait()

		if got := successes.Load(); got != 1 {
			t.Fatalf("round %d: %d of 4 racing callers succeeded, want exactly 1", round, got)
		}
	}
}

// From outside this package the three refusals are one value. A handler that writes err.Error() to
// the caller - the shape everybody reaches for first - must not thereby tell a stranger whether a
// window is open and whether their guess had the right shape.
func TestRefusalsAreIndistinguishableFromOutsideThePackage(t *testing.T) {
	now := time.Unix(1000, 0)
	w := enroll.NewWindow(func() time.Time { return now })

	closed := w.Consume(make([]byte, 32))
	token, _ := w.Open(time.Minute)
	wrong := w.Consume(make([]byte, 32))
	now = now.Add(2 * time.Minute)
	expired := w.Consume(token[:])

	for name, err := range map[string]error{"closed": closed, "wrong token": wrong, "expired": expired} {
		if !errors.Is(err, enroll.ErrRefused) {
			t.Fatalf("%s: every refusal must match ErrRefused, got %v", name, err)
		}
		if err.Error() != closed.Error() {
			t.Fatalf("%s: refusals must be indistinguishable, %q differs from %q", name, err.Error(), closed.Error())
		}
		// The words that would give the cause away if a future refusal grew a message of its own.
		for _, word := range []string{"expired", "expiry", "wrong", "open", "closed", "attempt", "token"} {
			if strings.Contains(strings.ToLower(err.Error()), word) {
				t.Fatalf("%s: a refusal must not say %q to the caller: %q", name, word, err.Error())
			}
		}
	}
}

// The token exists in memory and nowhere else. Losing it to a crash is the correct outcome - the
// code on the owner's screen is stale by then and the fix is a new one - whereas a token that
// reached a log, a cache or a state file outlives the window it belongs to and is readable by
// anything that can read the disk.
//
// The search below is the point of this test, so it proves it can find before it is allowed to
// report that it found nothing: it plants the token under the state directory in every rendering it
// searches for, requires a hit on each, and only then removes them and requires none.
//
// # The capture is the fence; the rendering list is only a sieve
//
// The first version of this test walked the filesystem alone, and a log.Printf("token=%x", token)
// added to Open left it passing: the standard logger writes to stderr, and WalkDir never sees
// stderr. It proved "this package writes no file", which is not what its name claims. The three
// default destinations - the std logger, os.Stdout and os.Stderr - are therefore pointed at files
// INSIDE the walked tree for the duration of the lifecycle. THAT is the durable part of this test:
// a whole channel closed, so a token printed anywhere lands somewhere the walk will find it.
//
// The seven renderings are not a fence and must not be read as one. No list of renderings can be
// complete, and these are demonstrably not: a leak written as base32, as a partial prefix (%x of the
// first sixteen bytes), as hex split into halves, as %q of a string cast, or as a decimal big.Int
// walks past all seven. They are a sieve sized for the forms a careless print actually takes -
// raw, hex, base64, and Go's own decimal - and adding an eighth is fair game, but the reason this
// test has teeth is the capture above it, not the length of the list below.
func TestTokenNeverReachesDisk(t *testing.T) {
	state := t.TempDir()
	// Every directory a Go program writes to without being told to lands inside the tree this test
	// walks, so "it wrote nothing under the state directory" cannot be satisfied by writing
	// somewhere else instead.
	for _, key := range []string{
		"HOME", "TMPDIR", "XDG_STATE_HOME", "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "XDG_DATA_HOME", "XDG_RUNTIME_DIR",
	} {
		dir := filepath.Join(state, "env-"+strings.ToLower(key))
		if err := os.MkdirAll(dir, 0o700); err != nil {
			t.Fatal(err)
		}
		t.Setenv(key, dir)
	}

	streams := filepath.Join(state, "streams")
	if err := os.MkdirAll(streams, 0o700); err != nil {
		t.Fatal(err)
	}
	captured := map[string]*os.File{}
	for _, name := range []string{"log", "stdout", "stderr"} {
		f, err := os.Create(filepath.Join(streams, name))
		if err != nil {
			t.Fatal(err)
		}
		captured[name] = f
	}
	// os.Stdout and os.Stderr are read at call time by fmt.Println and friends, so replacing the
	// variables catches a print made anywhere below. log.SetOutput catches the standard logger,
	// which holds its own reference to the original stderr and would otherwise escape both.
	realStdout, realStderr, realLog := os.Stdout, os.Stderr, log.Writer()
	os.Stdout, os.Stderr = captured["stdout"], captured["stderr"]
	log.SetOutput(captured["log"])
	restore := func() {
		os.Stdout, os.Stderr = realStdout, realStderr
		// log.Writer(), not os.Stderr. They are the same thing today; they would not be if anything
		// in this package's future test setup redirected the logger first, and restoring the
		// assumption rather than the observation is how that becomes a silent one-line bug.
		log.SetOutput(realLog)
	}
	t.Cleanup(restore)

	w := enroll.NewWindow(time.Now)
	token, _ := w.Open(5 * time.Minute)
	// The whole lifecycle, because a failed attempt is the thing most likely to be logged.
	_ = w.Consume(make([]byte, 32))
	_ = w.Consume([]byte("short"))
	consumeErr := w.Consume(token[:])
	w.Close()

	// Restored and closed BEFORE the walk: a buffered write still in a file handle is a leak the
	// scan would not see, and t.Fatalf below has to reach the real stderr to be readable.
	restore()
	for name, f := range captured {
		if err := f.Close(); err != nil {
			t.Fatalf("closing the captured %s: %v", name, err)
		}
	}
	if consumeErr != nil {
		t.Fatalf("consume: %v", consumeErr)
	}

	// The working directory of a `go test` binary is the package source directory. It is walked
	// too, so a stray write next to the source is caught, and so that the negative pass below is
	// searching real files rather than an empty tree.
	cwd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	roots := []string{state, cwd}

	forms := renderings(token[:])
	canary := filepath.Join(state, "canary")
	if err := os.MkdirAll(canary, 0o700); err != nil {
		t.Fatal(err)
	}
	for _, f := range forms {
		body := append([]byte("planted "+f.name+": "), f.bytes...)
		if err := os.WriteFile(filepath.Join(canary, f.name), append(body, " trailing\n"...), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	planted, _, _ := scanForToken(t, roots, forms)
	for _, f := range forms {
		if !slices.ContainsFunc(planted, func(h hit) bool { return h.rendering == f.name }) {
			t.Fatalf("the search is not searching: the token was planted as %s and the walk did not find it (found %v)", f.name, planted)
		}
	}
	t.Logf("self-check: the walk found all %d planted renderings (%s)", len(forms), strings.Join(names(forms), ", "))

	if err := os.RemoveAll(canary); err != nil {
		t.Fatal(err)
	}
	hits, files, size := scanForToken(t, roots, forms)
	if len(hits) != 0 {
		t.Fatalf("the enrolment token reached disk: %v", hits)
	}
	t.Logf("searched %d files (%d bytes) under %d roots for %d renderings of the token: no hit",
		files, size, len(roots), len(forms))
}

type rendering struct {
	name  string
	bytes []byte
}

// The renderings a leak could plausibly take. Raw bytes are what a memory dump or a length-prefixed
// record would carry; hex and base64 are what a log line, a JSON file or a QR-payload cache would.
//
// go-print is the one that is easy to forget and was found by mutation rather than by thinking:
// fmt.Println of a [32]byte writes neither hex nor base64 but Go's own decimal form, [12 34 ...],
// and a scan without it watched a deliberate fmt.Println leak go past.
func renderings(token []byte) []rendering {
	return []rendering{
		{"raw", token},
		{"go-print", []byte(fmt.Sprintf("%v", token))},
		{"hex-lower", []byte(hex.EncodeToString(token))},
		{"hex-upper", []byte(strings.ToUpper(hex.EncodeToString(token)))},
		{"base64-std", []byte(base64.StdEncoding.EncodeToString(token))},
		{"base64-url", []byte(base64.URLEncoding.EncodeToString(token))},
		{"base64-raw", []byte(base64.RawStdEncoding.EncodeToString(token))},
	}
}

func names(forms []rendering) []string {
	out := make([]string, len(forms))
	for i, f := range forms {
		out[i] = f.name
	}
	return out
}

// scanForToken reads every regular file under roots and reports each rendering it found, along with
// how much it actually read - a walk that silently covered nothing would otherwise pass.
func scanForToken(t *testing.T, roots []string, forms []rendering) (hits []hit, files int, size int64) {
	t.Helper()
	for _, root := range roots {
		err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if !d.Type().IsRegular() {
				return nil
			}
			body, err := os.ReadFile(path)
			if err != nil {
				return err
			}
			files++
			size += int64(len(body))
			for _, f := range forms {
				if bytes.Contains(body, f.bytes) {
					hits = append(hits, hit{rendering: f.name, path: path})
				}
			}
			return nil
		})
		if err != nil {
			t.Fatalf("walking %s: %v", root, err)
		}
	}
	return hits, files, size
}

// hit is one rendering of the token found in one file.
type hit struct {
	rendering string
	path      string
}

func (h hit) String() string { return fmt.Sprintf("%s in %s", h.rendering, h.path) }

// **A window cannot be opened for longer than MaxTTL, whatever the caller asks for.**
//
// This is the enforcement behind the sentence every argument about the anonymous branch rests on -
// that a window is seconds of the bridge's life. Nothing enforced it until now: the policy lived in a
// user interface nobody has written, so `Open(24*time.Hour)` would have produced an all-day anonymous
// port with no code anywhere objecting.
//
// The boundary is tested in both directions, because a clamp that is off by one in the permissive
// direction is the whole bug and a clamp that is off in the other silently shortens a legitimate
// window.
func TestOpenWillNotExceedTheMaximumTTL(t *testing.T) {
	at := time.Unix(1_700_000_000, 0).UTC()
	for _, tc := range []struct {
		name string
		ask  time.Duration
		want time.Duration
	}{
		{"well under the ceiling", time.Minute, time.Minute},
		{"exactly the ceiling", enroll.MaxTTL, enroll.MaxTTL},
		{"one nanosecond over", enroll.MaxTTL + time.Nanosecond, enroll.MaxTTL},
		{"a day", 24 * time.Hour, enroll.MaxTTL},
		{"a year", 365 * 24 * time.Hour, enroll.MaxTTL},
	} {
		t.Run(tc.name, func(t *testing.T) {
			w := enroll.NewWindow(func() time.Time { return at })
			_, expiry := w.Open(tc.ask)

			if got := expiry.Sub(at); got != tc.want {
				t.Fatalf("asked for %s, window runs for %s, want %s", tc.ask, got, tc.want)
			}
			if expiry.Sub(at) > enroll.MaxTTL {
				t.Fatalf("a window of %s is past the %s ceiling", expiry.Sub(at), enroll.MaxTTL)
			}
		})
	}
}

// The clamp is what the window ENFORCES as well as what it reports, and those are two different
// claims: an expiry that is returned truthfully but not honoured would be the same hole wearing an
// honest label.
func TestTheClampedWindowIsActuallyEnforced(t *testing.T) {
	at := time.Unix(1_700_000_000, 0).UTC()
	clock := func() time.Time { return at }
	w := enroll.NewWindow(clock)
	token, expiry := w.Open(24 * time.Hour)

	// A second before the clamped expiry: still live.
	at = expiry.Add(-time.Second)
	if !w.IsOpen() {
		t.Fatal("the window closed before its own stated expiry")
	}

	// At it: shut, and the right token no longer works. The day that was asked for is not honoured
	// anywhere, including by the code the phone is holding.
	at = expiry
	if w.IsOpen() {
		t.Fatal("the window outlived the expiry it advertised")
	}
	if err := w.Consume(token[:]); err == nil {
		t.Fatal("a token was spent after the clamped window had closed")
	}
}

// And the expiry Open returns is the one the QR payload will carry, so the phone and the bridge agree
// about a window the caller did not get the length it asked for. A clamp the payload did not learn
// about would show the owner a code claiming an hour against a window of five minutes.
func TestTheClampedExpiryIsWhatThePayloadWouldCarry(t *testing.T) {
	at := time.Unix(1_700_000_000, 0).UTC()
	w := enroll.NewWindow(func() time.Time { return at })
	token, expiry := w.Open(time.Hour)

	text, err := enroll.EncodeToText(enroll.Payload{
		Host: "a-laptop.invalid", Port: 8443, Token: token, Expiry: expiry})
	if err != nil {
		t.Fatal(err)
	}
	got, err := enroll.DecodeText(text)
	if err != nil {
		t.Fatal(err)
	}
	if !got.Expiry.Equal(expiry) {
		t.Fatalf("the code would say %s, the window enforces %s", got.Expiry, expiry)
	}
	if got.Expiry.Sub(at) > enroll.MaxTTL {
		t.Fatalf("the code advertises %s, past the %s ceiling", got.Expiry.Sub(at), enroll.MaxTTL)
	}
}

// **"Expired" and "closed after five wrong tokens" are different things to tell an owner**, and the
// panel showing the code cannot guess which it was: both present as a code that stopped working.
//
// So the window says. [Window.State] is the deliberate accessor the note on ErrRefused anticipated -
// it lives on this side, where the decision about what an owner may be told belongs, and nothing it
// returns ever travels to the anonymous caller.
func TestTheWindowSaysWhyItStoppedAcceptingAToken(t *testing.T) {
	at := time.Unix(1_700_000_000, 0).UTC()
	now := func() time.Time { return at }

	t.Run("never opened", func(t *testing.T) {
		s := enroll.NewWindow(now).State()
		if s.Open || s.Ended != enroll.EndedNever {
			t.Fatalf("a window nobody opened reports %+v", s)
		}
	})

	t.Run("open", func(t *testing.T) {
		w := enroll.NewWindow(now)
		_, expiry := w.Open(time.Minute)
		s := w.State()
		if !s.Open {
			t.Fatal("an open window reports itself closed")
		}
		if !s.Expiry.Equal(expiry) {
			t.Errorf("state says %s, Open said %s", s.Expiry, expiry)
		}
		if s.AttemptsLeft != enroll.MaxAttempts {
			t.Errorf("a fresh window has %d attempts left, want %d", s.AttemptsLeft, enroll.MaxAttempts)
		}
		if s.Ended != enroll.EndedNever {
			t.Errorf("an open window claims to have ended as %q", s.Ended)
		}
	})

	t.Run("expired", func(t *testing.T) {
		w := enroll.NewWindow(now)
		w.Open(time.Minute)
		at = at.Add(2 * time.Minute)
		defer func() { at = time.Unix(1_700_000_000, 0).UTC() }()

		// Nobody tried the token. The window has to notice by itself, or the panel says "wrong code"
		// about a code nobody entered.
		s := w.State()
		if s.Open || s.Ended != enroll.EndedExpired {
			t.Fatalf("a window that ran out reports %+v", s)
		}
	})

	t.Run("five wrong tokens", func(t *testing.T) {
		w := enroll.NewWindow(now)
		w.Open(time.Minute)
		for i := 0; i < enroll.MaxAttempts; i++ {
			_ = w.Consume(make([]byte, 32))
		}
		s := w.State()
		if s.Open || s.Ended != enroll.EndedAttempts {
			t.Fatalf("a window shut by wrong tokens reports %+v", s)
		}
		if s.AttemptsLeft != 0 {
			t.Errorf("%d attempts left after exhausting them", s.AttemptsLeft)
		}
	})

	t.Run("spent", func(t *testing.T) {
		w := enroll.NewWindow(now)
		token, _ := w.Open(time.Minute)
		if err := w.Consume(token[:]); err != nil {
			t.Fatal(err)
		}
		s := w.State()
		if s.Open || s.Ended != enroll.EndedPaired {
			t.Fatalf("a window a phone walked through reports %+v", s)
		}
	})

	t.Run("closed by the owner", func(t *testing.T) {
		w := enroll.NewWindow(now)
		w.Open(time.Minute)
		w.Close()
		s := w.State()
		if s.Open || s.Ended != enroll.EndedClosed {
			t.Fatalf("a window the owner shut reports %+v", s)
		}
	})
}

// **Only Consume burns an attempt**, which makes five looser than it reads: a dropped connection, a
// TLS handshake that failed, a request that never reached the gate - none of them costs the caller
// anything and none of them moves this counter. Said here as a test rather than as a sentence,
// because the Mac app reports the number and an owner reading "3 of 5 attempts left" would otherwise
// be told a count of the wrong events.
func TestOnlyASpentAttemptCountsAgainstTheWindow(t *testing.T) {
	at := time.Unix(1_700_000_000, 0).UTC()
	w := enroll.NewWindow(func() time.Time { return at })
	w.Open(time.Minute)

	// Reads that are not attempts.
	for i := 0; i < 20; i++ {
		w.IsOpen()
		w.State()
	}
	if left := w.State().AttemptsLeft; left != enroll.MaxAttempts {
		t.Fatalf("%d attempts left after twenty reads, want %d", left, enroll.MaxAttempts)
	}

	_ = w.Consume(make([]byte, 32))
	if left := w.State().AttemptsLeft; left != enroll.MaxAttempts-1 {
		t.Fatalf("%d attempts left after one wrong token, want %d", left, enroll.MaxAttempts-1)
	}
}

// Re-opening is what the owner means by pressing the button again, and it resets the count and the
// reason with the secret they belonged to. A panel that still said "closed after five wrong
// attempts" over a freshly minted code would be describing the previous one.
func TestReopeningForgetsWhyTheLastWindowEnded(t *testing.T) {
	at := time.Unix(1_700_000_000, 0).UTC()
	w := enroll.NewWindow(func() time.Time { return at })
	w.Open(time.Minute)
	for i := 0; i < enroll.MaxAttempts; i++ {
		_ = w.Consume(make([]byte, 32))
	}

	w.Open(time.Minute)
	s := w.State()
	if !s.Open || s.Ended != enroll.EndedNever || s.AttemptsLeft != enroll.MaxAttempts {
		t.Fatalf("a re-opened window still carries the last one's ending: %+v", s)
	}
}
