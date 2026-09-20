package zmxhold

import (
	"bytes"
	"context"
	"strings"
	"testing"

	"github.com/isachivka/agterm-remote/bridge/internal/zmxhold/zmxholdtest"
)

// The bytes are asserted literally, header included, because the contract is with a daemon whose
// source is pinned by agterm's build and not with a helper in this package: a test that compared
// against `sizeFrames` would pass whatever that function did.

// 200 rows by 41 columns, little-endian, pixels zero - the 8-byte form and the 4-byte legacy form.
var (
	init8   = []byte{7, 8, 0, 0, 0, 0, 0, 0, 200, 0, 41, 0, 0, 0, 0, 0}
	init4   = []byte{7, 4, 0, 0, 0, 0, 0, 0, 200, 0, 41, 0}
	resize8 = []byte{2, 8, 0, 0, 0, 0, 0, 0, 200, 0, 41, 0, 0, 0, 0, 0}
	resize4 = []byte{2, 4, 0, 0, 0, 0, 0, 0, 200, 0, 41, 0}
	claim   = append([]byte{0, 12, 0, 0, 0, 0, 0, 0}, "\x1b[200~\x1b[201~"...)
	detach  = []byte{3, 0, 0, 0, 0, 0, 0, 0}
)

const (
	tall     = 200
	columns  = 41
	origRows = 56
	origCols = 164
)

func raw(f zmxholdtest.Frame) []byte {
	b := []byte{f.Tag, byte(len(f.Payload)), 0, 0, 0, 0, 0, 0}
	return append(b, f.Payload...)
}

func expectFrames(t *testing.T, got []zmxholdtest.Frame, want ...[]byte) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("got %d frames, want %d: %+v", len(got), len(want), got)
	}
	for i := range want {
		if !bytes.Equal(raw(got[i]), want[i]) {
			t.Errorf("frame %d = % x, want % x", i, raw(got[i]), want[i])
		}
	}
}

func TestOpenIntroducesItselfThenClaimsInThatOrder(t *testing.T) {
	d := zmxholdtest.Start(t)
	h, err := Open(context.Background(), d.Path, Size{Rows: tall, Cols: columns})
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()

	// Init in both forms, then the claim: the paste that makes us leader, then the size in both
	// forms. The claim must FOLLOW the Init - an Input on a connection the daemon has not seen an
	// Init from is still forwarded, but the Init after it would then be a follower's and apply
	// nothing, leaving the size to the Resize alone.
	expectFrames(t, d.AwaitFrames(t, 5), init8, init4, claim, resize8, resize4)
	if h.Held() != (Size{Rows: tall, Cols: columns}) {
		t.Errorf("held %+v", h.Held())
	}
}

func TestTheDaemonsSizeQuestionIsAnsweredWithTheHeldSize(t *testing.T) {
	d := zmxholdtest.Start(t)
	h, err := Open(context.Background(), d.Path, Size{Rows: tall, Cols: columns})
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	d.AwaitFrames(t, 5)

	// An empty Resize is "tell me your size" - sent when the daemon makes this client leader.
	d.Send(t, 0, 2, nil)

	got := d.AwaitFrames(t, 7)
	expectFrames(t, got[5:], resize8, resize4)
}

func TestClaimSendsThePasteThenTheNewSize(t *testing.T) {
	d := zmxholdtest.Start(t)
	h, err := Open(context.Background(), d.Path, Size{Rows: tall, Cols: columns})
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	d.AwaitFrames(t, 5)

	if err := h.Claim(Size{Rows: tall, Cols: 38}); err != nil {
		t.Fatal(err)
	}
	got := d.AwaitFrames(t, 8)
	expectFrames(t, got[5:], claim,
		[]byte{2, 8, 0, 0, 0, 0, 0, 0, 200, 0, 38, 0, 0, 0, 0, 0},
		[]byte{2, 4, 0, 0, 0, 0, 0, 0, 200, 0, 38, 0})
	if h.Held() != (Size{Rows: tall, Cols: 38}) {
		t.Errorf("held %+v after a re-claim", h.Held())
	}
	// And the daemon's next question gets the NEW size, not the one Open was given.
	d.Send(t, 0, 2, nil)
	got = d.AwaitFrames(t, 10)
	expectFrames(t, got[8:],
		[]byte{2, 8, 0, 0, 0, 0, 0, 0, 200, 0, 38, 0, 0, 0, 0, 0},
		[]byte{2, 4, 0, 0, 0, 0, 0, 0, 200, 0, 38, 0})
}

func TestReleaseRestatesTheOriginalSizeThenDetaches(t *testing.T) {
	d := zmxholdtest.Start(t)
	h, err := Open(context.Background(), d.Path, Size{Rows: tall, Cols: columns})
	if err != nil {
		t.Fatal(err)
	}
	d.AwaitFrames(t, 5)

	if err := h.Release(Size{Rows: origRows, Cols: origCols}); err != nil {
		t.Fatal(err)
	}
	got := d.AwaitFrames(t, 8)
	// 56 rows, 164 columns: 0x38, 0xA4.
	expectFrames(t, got[5:],
		[]byte{2, 8, 0, 0, 0, 0, 0, 0, 56, 0, 164, 0, 0, 0, 0, 0},
		[]byte{2, 4, 0, 0, 0, 0, 0, 0, 56, 0, 164, 0},
		detach)
	d.AwaitClosed(t, 0)
	// No paste on the way out: the release must not make us leader again on a pane we are leaving.
	for _, f := range got[5:] {
		if f.Tag == 0 {
			t.Errorf("Release typed into the pty: % x", f.Payload)
		}
	}
}

func TestRestoreIsAFreshInitThenDetachAndTypesNothing(t *testing.T) {
	d := zmxholdtest.Start(t)

	if err := Restore(context.Background(), d.Path, Size{Rows: origRows, Cols: origCols}); err != nil {
		t.Fatal(err)
	}
	got := d.AwaitFrames(t, 3)
	expectFrames(t, got,
		[]byte{7, 8, 0, 0, 0, 0, 0, 0, 56, 0, 164, 0, 0, 0, 0, 0},
		[]byte{7, 4, 0, 0, 0, 0, 0, 0, 56, 0, 164, 0},
		detach)
	d.AwaitClosed(t, 0)
	// Init and nothing else: an Input here would take leadership from agterm, which is the opposite
	// of what a restore is for. Init applies the size only when nobody leads, and that is the point.
	for _, f := range got {
		if f.Tag == 0 {
			t.Errorf("Restore typed into the pty: % x", f.Payload)
		}
	}
}

func TestOutputIsDrainedAndTheQuestionAfterItStillAnswered(t *testing.T) {
	d := zmxholdtest.Start(t)
	h, err := Open(context.Background(), d.Path, Size{Rows: tall, Cols: columns})
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	d.AwaitFrames(t, 5)

	// A terminal snapshot the size the daemon sends a re-attaching client, then the question. A
	// reader that parsed the snapshot as frames, or stopped reading, would never see the question.
	d.Send(t, 0, 1, bytes.Repeat([]byte("x"), 4<<20))
	d.Send(t, 0, 6, []byte("info"))
	d.Send(t, 0, 2, nil)

	got := d.AwaitFrames(t, 7)
	expectFrames(t, got[5:], resize8, resize4)
}

func TestASizeTheWireCannotCarryIsRefused(t *testing.T) {
	d := zmxholdtest.Start(t)
	for _, s := range []Size{{0, 41}, {200, 0}, {70000, 41}, {-1, 41}} {
		if _, err := Open(context.Background(), d.Path, s); err == nil {
			t.Errorf("%+v was accepted", s)
		}
		if err := Restore(context.Background(), d.Path, s); err == nil {
			t.Errorf("Restore accepted %+v", s)
		}
	}
	if d.Connections() != 0 {
		t.Error("a refused size still dialled the daemon")
	}
}

func TestADeadDaemonIsReportedByErr(t *testing.T) {
	d := zmxholdtest.Start(t)
	h, err := Open(context.Background(), d.Path, Size{Rows: tall, Cols: columns})
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	d.AwaitFrames(t, 5)
	if h.Err() != nil {
		t.Fatalf("live hold reports %v", h.Err())
	}
	// The daemon hangs up - a Detach makes the fake do what the real one does, and a daemon that
	// exited looks the same from here.
	if err := h.write(frame(tagDetach, nil)); err != nil {
		t.Fatal(err)
	}
	d.AwaitClosed(t, 0)
	<-h.done
	if h.Err() == nil {
		t.Fatal("a closed connection reports no error")
	}
}

func TestTheClaimInputIsAnEmptyBracketedPaste(t *testing.T) {
	// Pinned as text, so a change here is a change somebody has to read. Anything else that zmx
	// classifies as user input is a keystroke into the owner's program.
	if ClaimInput != "\x1b[200~\x1b[201~" {
		t.Fatalf("ClaimInput = %q", ClaimInput)
	}
	if strings.ContainsAny(ClaimInput, "\r\n") {
		t.Fatal("the claim must not carry a newline")
	}
}

// The socket path names the daemon and is enough to dial the owner's pty; the callers log these
// errors, under a rule that says neither may appear there. So none of this package's errors carries
// it, and the test checks the two ways a path could get in: the dial, and the connection's own end.
func TestErrorsNameNeitherTheSocketNorTheDaemon(t *testing.T) {
	d := zmxholdtest.Start(t)
	missing := d.Path + "-gone"
	if _, err := Open(context.Background(), missing, Size{Rows: tall, Cols: columns}); err == nil {
		t.Fatal("opened a socket that is not there")
	} else if strings.Contains(err.Error(), missing) || strings.Contains(err.Error(), "agterm-1") {
		t.Fatalf("the error carries the socket: %v", err)
	}
	if err := Restore(context.Background(), missing, Size{Rows: tall, Cols: columns}); err == nil {
		t.Fatal("restored through a socket that is not there")
	} else if strings.Contains(err.Error(), missing) {
		t.Fatalf("the error carries the socket: %v", err)
	}

	h, err := Open(context.Background(), d.Path, Size{Rows: tall, Cols: columns})
	if err != nil {
		t.Fatal(err)
	}
	d.AwaitFrames(t, 5)
	if err := h.Close(); err != nil {
		t.Fatal(err)
	}
	if err := h.Err(); err == nil || strings.Contains(err.Error(), d.Path) || strings.Contains(err.Error(), "agterm-1") {
		t.Fatalf("Err after close = %v", err)
	}
	if err := h.Claim(Size{Rows: tall, Cols: columns}); err == nil || strings.Contains(err.Error(), d.Path) {
		t.Fatalf("Claim on a closed hold = %v", err)
	}
}
