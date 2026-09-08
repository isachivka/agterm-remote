package enroll_test

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io/fs"
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

// The token exists in memory and nowhere else. Losing it to a crash is the correct outcome - the
// code on the owner's screen is stale by then and the fix is a new one - whereas a token that
// reached a log, a cache or a state file outlives the window it belongs to and is readable by
// anything that can read the disk.
//
// The search below is the point of this test, so it proves it can find before it is allowed to
// report that it found nothing: it plants the token under the state directory in every rendering it
// searches for, requires a hit on each, and only then removes them and requires none.
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

	w := enroll.NewWindow(time.Now)
	token, _ := w.Open(5 * time.Minute)
	// The whole lifecycle, because a failed attempt is the thing most likely to be logged.
	_ = w.Consume(make([]byte, 32))
	_ = w.Consume([]byte("short"))
	if err := w.Consume(token[:]); err != nil {
		t.Fatalf("consume: %v", err)
	}
	w.Close()

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
func renderings(token []byte) []rendering {
	return []rendering{
		{"raw", token},
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
