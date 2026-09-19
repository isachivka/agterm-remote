// Package hop serves TLS and plain HTTP on ONE port, deciding per connection from its first byte.
//
// # Why this exists: the question it removes
//
// The bridge used to be told, by a flag, whether its port serves TLS. The flag came from a popup on
// the Mac with three answers, and in two days two real setups behind the same kind of router chose
// the wrong one - each time the symptom was a phone reporting a Mac that does not answer, with
// nothing anywhere able to say why. The owner's verdict: this is the software's problem, and a
// person should never have been asked.
//
// They never needed to be. A TLS connection begins with a record header whose first byte is 0x16
// (handshake) and no HTTP request begins that way, so the port can answer whatever arrives: a
// router that insists on an HTTPS backend gets TLS, a tunnel that speaks plain HTTP to its backend
// gets plain HTTP, and a phone dialling the port directly gets TLS. One byte, peeked and put back.
//
// # What the TLS here is, and is not
//
// Opportunistic encryption of one hop. Whatever connects does not validate this certificate - it
// cannot, there is no name to check it against - so it buys confidentiality against a passive
// listener on the LAN and nothing else. The security of this service is the pinned mTLS INSIDE the
// upgraded stream, which is unaffected by whether this hop was plain or TLS; see internal/frontdoor.
//
// # Bounded, like the front door
//
// A caller who connects and sends nothing is held for one deadline and dropped, in its own
// goroutine, so a silent connection cannot stall the accept loop. The accept loop itself treats an
// error the way frontdoor's does: closed is closed, temporary backs off, anything else is reported
// through Accept rather than swallowed.
package hop

import (
	"bufio"
	"crypto/tls"
	"errors"
	"net"
	"sync"
	"time"
)

// sniffTimeout bounds a caller who connects and then says nothing.
const sniffTimeout = 10 * time.Second

// tlsRecordHandshake is the first byte of every TLS ClientHello: content type 22, handshake.
const tlsRecordHandshake = 0x16

// Listener is a net.Listener whose connections are TLS or plain by what they sent first.
type Listener struct {
	inner     net.Listener
	cfg       *tls.Config
	out       chan net.Conn
	done      chan struct{}
	stopped   chan struct{}
	closeOne  sync.Once
	acceptErr error
}

// Listen wraps inner. Every connection accepted from it is served as TLS with cfg when its first
// byte is a TLS record header, and as itself otherwise.
func Listen(inner net.Listener, cfg *tls.Config) *Listener {
	l := &Listener{
		inner:   inner,
		cfg:     cfg,
		out:     make(chan net.Conn),
		done:    make(chan struct{}),
		stopped: make(chan struct{}),
	}
	go l.accept()
	return l
}

func (l *Listener) accept() {
	defer close(l.stopped)
	var delay time.Duration
	for {
		c, err := l.inner.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			var ne net.Error
			if errors.As(err, &ne) && ne.Temporary() { //nolint:staticcheck // the one well-defined use
				if delay == 0 {
					delay = 5 * time.Millisecond
				} else if delay *= 2; delay > time.Second {
					delay = time.Second
				}
				time.Sleep(delay)
				continue
			}
			l.acceptErr = err
			l.closeOne.Do(func() { close(l.done) })
			return
		}
		delay = 0
		go l.sniff(c)
	}
}

// sniff reads the first byte without consuming it and hands the connection on in the right dress.
func (l *Listener) sniff(raw net.Conn) {
	_ = raw.SetReadDeadline(time.Now().Add(sniffTimeout))
	r := bufio.NewReader(raw)
	first, err := r.Peek(1)
	if err != nil {
		raw.Close()
		return
	}
	_ = raw.SetReadDeadline(time.Time{})
	var conn net.Conn = &peeked{Conn: raw, r: r}
	if first[0] == tlsRecordHandshake {
		conn = tls.Server(conn, l.cfg)
	}
	select {
	case l.out <- conn:
	case <-l.done:
		conn.Close()
	}
}

// peeked is a connection whose first bytes were already read into a buffer.
type peeked struct {
	net.Conn
	r *bufio.Reader
}

func (p *peeked) Read(b []byte) (int, error) { return p.r.Read(b) }

// Accept returns the next sniffed connection. After Close it returns net.ErrClosed; after the
// accept loop has died of an error it returns that error.
func (l *Listener) Accept() (net.Conn, error) {
	select {
	case c := <-l.out:
		return c, nil
	case <-l.done:
		if l.acceptErr != nil {
			return nil, l.acceptErr
		}
		return nil, net.ErrClosed
	}
}

// Close stops the listener and waits for the accept loop to finish.
func (l *Listener) Close() error {
	l.closeOne.Do(func() { close(l.done) })
	err := l.inner.Close()
	<-l.stopped
	return err
}

func (l *Listener) Addr() net.Addr { return l.inner.Addr() }
