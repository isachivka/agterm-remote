package enroll

import (
	"errors"
	"testing"
	"time"
)

// The cause of a refusal is knowable here and nowhere else.
//
// This test is inside the package because that is exactly the boundary the design draws: the bridge
// may distinguish "expired" from "wrong bytes" for its own log and for what it eventually tells the
// owner, and the caller on the other end of the connection may not. A test in enroll_test could not
// assert this, and that is the property, not an inconvenience.
func TestARefusalKnowsItsCauseAndDoesNotSayIt(t *testing.T) {
	now := time.Unix(1000, 0)
	w := NewWindow(func() time.Time { return now })

	closed := w.Consume(make([]byte, 32))

	token, _ := w.Open(time.Minute)
	wrong := w.Consume(make([]byte, 32))

	now = now.Add(2 * time.Minute)
	expired := w.Consume(token[:])

	for _, c := range []struct {
		name  string
		err   error
		cause error
		other []error
	}{
		{"closed", closed, causeClosed, []error{causeToken, causeExpired}},
		{"wrong token", wrong, causeToken, []error{causeClosed, causeExpired}},
		{"expired", expired, causeExpired, []error{causeClosed, causeToken}},
	} {
		t.Run(c.name, func(t *testing.T) {
			if !errors.Is(c.err, ErrRefused) {
				t.Fatalf("every refusal must be an ErrRefused, got %v", c.err)
			}
			if !errors.Is(c.err, c.cause) {
				t.Fatalf("the cause must survive for the bridge's own log, got %v", c.err)
			}
			for _, o := range c.other {
				if errors.Is(c.err, o) {
					t.Fatalf("%v must not match the cause %v", c.err, o)
				}
			}
			// The whole point: what a handler can print is the generic text and only that.
			if got := c.err.Error(); got != ErrRefused.Error() {
				t.Fatalf("a refusal must say %q and nothing more, it says %q", ErrRefused.Error(), got)
			}
		})
	}
}
