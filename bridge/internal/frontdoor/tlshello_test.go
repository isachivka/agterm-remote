package frontdoor

import (
	"bytes"
	"testing"
	"time"
)

// A ClientHello against this plain port is refused with the same bytes as anything else - a caller
// learns nothing - and is counted, which is the one thing the owner's app can learn.
func TestATLSClientHelloIsRefusedLikeAnythingElseAndCounted(t *testing.T) {
	l, addr := start(t)

	c := dial(t, addr)
	// The first bytes of a real TLS 1.2/1.3 ClientHello record: handshake content type, TLS major
	// version, a record length, then the handshake type. Enough to be unmistakable.
	if _, err := c.Write([]byte{0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x00, 0x00, 0x01, 0x00}); err != nil {
		t.Fatal(err)
	}
	if got := readAll(t, c); !bytes.Equal(got, refusalResponse) {
		t.Fatalf("a ClientHello got a different answer than any other refusal:\n%q", got)
	}

	count, last := l.TLSHellos()
	if count != 1 {
		t.Fatalf("hellos = %d, want 1", count)
	}
	if time.Since(last) > 5*time.Second {
		t.Fatalf("last hello stamped %v ago", time.Since(last))
	}

	// An ordinary bad request is refused too, and is NOT a hello: the count must not move for it.
	c2 := dial(t, addr)
	if _, err := c2.Write([]byte("POST / HTTP/1.1\r\n\r\n")); err != nil {
		t.Fatal(err)
	}
	_ = readAll(t, c2)
	if count, _ := l.TLSHellos(); count != 1 {
		t.Fatalf("a plain bad request moved the hello count to %d", count)
	}

	// Nothing seen: zero and the zero time, so a consumer can tell "never" from "at the epoch".
	fresh, _ := start(t)
	if n, when := fresh.TLSHellos(); n != 0 || !when.IsZero() {
		t.Fatalf("fresh listener reports %d hellos at %v", n, when)
	}
}
