package frontdoor

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"io"
	"log"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/api"
	"github.com/isachivka/agterm-remote/bridge/internal/listener"
	"github.com/isachivka/agterm-remote/bridge/internal/pinning"
)

// The whole stack, with the listener untouched.
//
// `listener.New(...).Serve(ctx, frontdoor.Listen(tcp))` is the entire integration: the front door is
// a net.Listener, so the mTLS listener runs against it unchanged and never learns that a proxy, an
// HTTP request or a WebSocket frame exists. If that seam ever stops holding, this file stops
// compiling.
type recorder struct{ calls int }

func (r *recorder) Handle(context.Context, api.Request) api.Response {
	r.calls++
	return api.Response{OK: true}
}

func stack(t *testing.T) (addr string, bridgeCert, phoneCert *x509.Certificate, phoneOwn tls.Certificate) {
	t.Helper()
	log.SetOutput(io.Discard)

	bridgeID, _ := pinning.Mint("bridge", time.Hour)
	phoneID, _ := pinning.Mint("phone", time.Hour)
	bridgeOwn, err := pinning.LoadIdentity(bridgeID)
	if err != nil {
		t.Fatal(err)
	}
	phoneOwn, err = pinning.LoadIdentity(phoneID)
	if err != nil {
		t.Fatal(err)
	}
	bc, _ := pinning.LoadPeer(bridgeID.CertPEM)
	pc, _ := pinning.LoadPeer(phoneID.CertPEM)

	tcp, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	front := Listen(tcp)
	// false: every connection arrives from the router, so the peer address is not the caller's.
	srv := listener.New(pinning.ServerConfig(bridgeOwn, pc), &recorder{}, false)

	ctx, cancel := context.WithCancel(context.Background())
	go func() { _ = srv.Serve(ctx, front) }()
	t.Cleanup(func() { cancel(); front.Close() })

	return tcp.Addr().String(), bc, pc, phoneOwn
}

// wsClient does the client half: handshake, then masked binary frames. Written here rather than
// imported, because a client that used the same code as the server would prove the two agree with
// themselves rather than with RFC 6455.
type wsClient struct {
	net.Conn
	pending []byte
}

func dialWS(t *testing.T, addr string) net.Conn {
	t.Helper()
	c, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	raw := make([]byte, 16)
	_, _ = rand.Read(raw)
	k := base64.StdEncoding.EncodeToString(raw)
	_, _ = c.Write([]byte(upgradeRequest(k)))

	r := bufio.NewReader(c)
	line, err := r.ReadString('\n')
	if err != nil || !strings.HasPrefix(line, "HTTP/1.1 101 ") {
		t.Fatalf("upgrade refused: %q (%v)", line, err)
	}
	for {
		h, err := r.ReadString('\n')
		if err != nil {
			t.Fatal(err)
		}
		if strings.TrimSpace(h) == "" {
			break
		}
	}
	if r.Buffered() != 0 {
		t.Fatal("server sent data before the client spoke")
	}
	t.Cleanup(func() { c.Close() })
	return &wsClient{Conn: c}
}

func (w *wsClient) Write(p []byte) (int, error) {
	header := []byte{0x82} // FIN + binary
	var mask [4]byte
	_, _ = rand.Read(mask[:])
	switch n := len(p); {
	case n < 126:
		header = append(header, byte(n)|0x80)
	case n <= 0xFFFF:
		header = append(header, 126|0x80, byte(n>>8), byte(n))
	default:
		var ext [8]byte
		binary.BigEndian.PutUint64(ext[:], uint64(n))
		header = append(header, 127|0x80)
		header = append(header, ext[:]...)
	}
	header = append(header, mask[:]...)
	masked := make([]byte, len(p))
	for i := range p {
		masked[i] = p[i] ^ mask[i%4]
	}
	if _, err := w.Conn.Write(append(header, masked...)); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (w *wsClient) Read(p []byte) (int, error) {
	for len(w.pending) == 0 {
		var head [2]byte
		if _, err := io.ReadFull(w.Conn, head[:]); err != nil {
			return 0, err
		}
		length := int(head[1] & 0x7F)
		switch length {
		case 126:
			var ext [2]byte
			if _, err := io.ReadFull(w.Conn, ext[:]); err != nil {
				return 0, err
			}
			length = int(binary.BigEndian.Uint16(ext[:]))
		case 127:
			var ext [8]byte
			if _, err := io.ReadFull(w.Conn, ext[:]); err != nil {
				return 0, err
			}
			length = int(binary.BigEndian.Uint64(ext[:]))
		}
		if head[1]&0x80 != 0 {
			return 0, io.ErrUnexpectedEOF // a server frame must never be masked
		}
		payload := make([]byte, length)
		if _, err := io.ReadFull(w.Conn, payload); err != nil {
			return 0, err
		}
		if head[0]&0x0F == 0x8 {
			return 0, io.EOF
		}
		w.pending = payload
	}
	n := copy(p, w.pending)
	w.pending = w.pending[n:]
	return n, nil
}

// The pinned phone reaches the verbs through the proxy shape.
func TestThePinnedPhoneReachesTheBridgeThroughTheWebSocket(t *testing.T) {
	addr, bridgeCert, _, phoneOwn := stack(t)

	tlsConn := tls.Client(dialWS(t, addr), pinning.ClientConfig(phoneOwn, bridgeCert))
	_ = tlsConn.SetDeadline(time.Now().Add(10 * time.Second))
	if err := tlsConn.Handshake(); err != nil {
		t.Fatalf("mTLS must complete inside the WebSocket: %v", err)
	}
	if _, err := tlsConn.Write([]byte(`{"verb":"sessions"}` + "\n")); err != nil {
		t.Fatal(err)
	}
	var resp api.Response
	if err := json.NewDecoder(io.LimitReader(tlsConn, 1<<20)).Decode(&resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if !resp.OK {
		t.Fatalf("refused: %s", resp.Error)
	}
}

// **The assertion that proves the proxy did not eat the guarantee.**
//
// A caller who completes the WebSocket upgrade is still nobody. Reaching the bridge's front door is
// not authentication, and the pinning behind it is unchanged — the router moves bytes it cannot read.
func TestAnUnpinnedPhoneIsStillRefusedThroughTheWebSocket(t *testing.T) {
	addr, bridgeCert, _, _ := stack(t)
	strangerID, _ := pinning.Mint("somebody else", time.Hour)
	strangerOwn, err := pinning.LoadIdentity(strangerID)
	if err != nil {
		t.Fatal(err)
	}

	// The upgrade succeeds - that is the point. Everything after it must not.
	tlsConn := tls.Client(dialWS(t, addr), pinning.ClientConfig(strangerOwn, bridgeCert))
	_ = tlsConn.SetDeadline(time.Now().Add(10 * time.Second))
	_ = tlsConn.Handshake()
	_, _ = tlsConn.Write([]byte(`{"verb":"sessions"}` + "\n"))
	buf := make([]byte, 1)
	if _, err := tlsConn.Read(buf); err == nil {
		t.Fatal("an unpinned certificate must be refused even after a successful upgrade")
	}
}

// **The regression test for the collapsed-source lockout.**
//
// A TLS-terminating proxy proxies, so every connection's peer is the proxy. A per-source counter over one collapsed
// source is a global ceiling wearing a per-source costume: five failed handshakes and every caller is
// refused, the owner included, for the rest of the window — free to cause, now that the upgrade is
// free.
//
// Nothing in the front door was wrong on its own. The property lived in the listener and its
// precondition — that the peer address is the caller's — was invalidated here, which is why nothing
// flagged it.
func TestFailedHandshakesFromTheProxyDoNotLockOutTheOwner(t *testing.T) {
	addr, bridgeCert, _, phoneOwn := stack(t)

	// Well past failuresPerSource, all arriving with the same peer address.
	for i := 0; i < 15; i++ {
		strangerID, _ := pinning.Mint("stranger", time.Hour)
		strangerOwn, err := pinning.LoadIdentity(strangerID)
		if err != nil {
			t.Fatal(err)
		}
		c := tls.Client(dialWS(t, addr), pinning.ClientConfig(strangerOwn, bridgeCert))
		_ = c.SetDeadline(time.Now().Add(5 * time.Second))
		_ = c.Handshake()
		_, _ = c.Read(make([]byte, 1))
		c.Close()
	}

	// The owner, immediately afterwards, must still be served.
	tlsConn := tls.Client(dialWS(t, addr), pinning.ClientConfig(phoneOwn, bridgeCert))
	_ = tlsConn.SetDeadline(time.Now().Add(10 * time.Second))
	if err := tlsConn.Handshake(); err != nil {
		t.Fatalf("the owner was locked out by other callers' failures: %v", err)
	}
	if _, err := tlsConn.Write([]byte(`{"verb":"sessions"}` + "\n")); err != nil {
		t.Fatal(err)
	}
	var resp api.Response
	if err := json.NewDecoder(io.LimitReader(tlsConn, 1<<20)).Decode(&resp); err != nil {
		t.Fatalf("the owner was locked out by other callers' failures: %v", err)
	}
	if !resp.OK {
		t.Fatalf("refused: %s", resp.Error)
	}
}
