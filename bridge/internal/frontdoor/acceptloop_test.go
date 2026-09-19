package frontdoor

import (
	"errors"
	"net"
	"testing"
	"time"
)

// flakyListener wraps a real listener and injects accept errors: `temporary` of them first, then
// `fatal` once if set, then the real listener's own answers.
type flakyListener struct {
	net.Listener
	temporary int
	fatal     error
}

type tempErr struct{}

func (tempErr) Error() string   { return "accept: too many open files" }
func (tempErr) Temporary() bool { return true }
func (tempErr) Timeout() bool   { return false }

func (f *flakyListener) Accept() (net.Conn, error) {
	if f.temporary > 0 {
		f.temporary--
		return nil, tempErr{}
	}
	if f.fatal != nil {
		err := f.fatal
		f.fatal = nil
		return nil, err
	}
	return f.Listener.Accept()
}

// A temporary error - the shape EMFILE takes - is survived: the loop backs off and the next caller is
// still served. This used to end the accept loop for good.
func TestATemporaryAcceptErrorIsSurvived(t *testing.T) {
	inner, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	l := Listen(&flakyListener{Listener: inner, temporary: 3})
	defer l.Close()

	c := dial(t, l.Addr().String())
	if _, err := c.Write([]byte("POST / HTTP/1.1\r\n\r\n")); err != nil {
		t.Fatal(err)
	}
	if got := readAll(t, c); len(got) == 0 {
		t.Fatal("after three temporary accept errors the listener served nobody")
	}
}

// A fatal error is not swallowed: Accept returns it, and Close still returns promptly.
func TestAFatalAcceptErrorIsReportedByAccept(t *testing.T) {
	inner, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	boom := errors.New("accept: the descriptor table is gone")
	l := Listen(&flakyListener{Listener: inner, fatal: boom})

	done := make(chan error, 1)
	go func() { _, err := l.Accept(); done <- err }()
	select {
	case err := <-done:
		if !errors.Is(err, boom) {
			t.Fatalf("Accept returned %v, want the accept loop's own error", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Accept blocked forever after the accept loop died - the silent-loss shape")
	}

	closed := make(chan struct{})
	go func() { _ = l.Close(); close(closed) }()
	select {
	case <-closed:
	case <-time.After(5 * time.Second):
		t.Fatal("Close hung after the accept loop had already stopped")
	}
}

// Closing is still closing: Accept after Close says net.ErrClosed, not an error the loop never had.
func TestCloseStillReadsAsClosed(t *testing.T) {
	l, _ := start(t)
	_ = l.Close()
	if _, err := l.Accept(); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("Accept after Close returned %v, want net.ErrClosed", err)
	}
}
