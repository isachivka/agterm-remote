package frontdoor

import (
	"encoding/binary"
	"errors"
	"io"
	"net"
	"time"
)

// RFC 6455 opcodes. Only the ones this bridge will ever see.
const (
	opContinuation = 0x0
	opBinary       = 0x2
	opClose        = 0x8
	opPing         = 0x9
	opPong         = 0xA

	// maxFrameBytes caps one frame's payload. The mTLS records inside are far smaller; the cap
	// exists so a declared length cannot size an allocation.
	maxFrameBytes = 1 << 20
)

// conn presents a WebSocket data stream as a net.Conn, which is all the mTLS listener wants.
//
// This is the seam that keeps the listener untouched. It receives something that reads and writes
// bytes and never learns that a proxy, an HTTP request or a frame header exists.
//
// Binary frames only, no extensions, no compression, no fragmentation on the way out. A client
// sending anything else is a client this bridge does not serve, and refusing it is the feature.
type conn struct {
	net.Conn
	pending []byte // payload read but not yet handed to the caller
}

func newConn(c net.Conn) net.Conn { return &conn{Conn: c} }

func (c *conn) Read(p []byte) (int, error) {
	for len(c.pending) == 0 {
		payload, opcode, err := c.readFrame()
		if err != nil {
			return 0, err
		}
		switch opcode {
		case opBinary, opContinuation:
			c.pending = payload
		case opPing:
			// Answered rather than ignored: a proxy or a client may ping to keep the path warm, and a
			// silent bridge would look dead to it.
			if err := c.writeFrame(opPong, payload); err != nil {
				return 0, err
			}
		case opPong:
			// Nothing to do; a peer answering our silence is not an error.
		case opClose:
			return 0, io.EOF
		default:
			return 0, errors.New("unsupported opcode")
		}
	}
	n := copy(p, c.pending)
	c.pending = c.pending[n:]
	return n, nil
}

func (c *conn) Write(p []byte) (int, error) {
	if err := c.writeFrame(opBinary, p); err != nil {
		return 0, err
	}
	return len(p), nil
}

// readFrame reads one frame. Client-to-server frames MUST be masked (RFC 6455 §5.1); an unmasked one
// is a protocol violation and is refused rather than tolerated.
func (c *conn) readFrame() ([]byte, byte, error) {
	var head [2]byte
	if _, err := io.ReadFull(c.Conn, head[:]); err != nil {
		return nil, 0, err
	}
	opcode := head[0] & 0x0F
	masked := head[1]&0x80 != 0
	length := int(head[1] & 0x7F)

	switch length {
	case 126:
		var ext [2]byte
		if _, err := io.ReadFull(c.Conn, ext[:]); err != nil {
			return nil, 0, err
		}
		length = int(binary.BigEndian.Uint16(ext[:]))
	case 127:
		var ext [8]byte
		if _, err := io.ReadFull(c.Conn, ext[:]); err != nil {
			return nil, 0, err
		}
		v := binary.BigEndian.Uint64(ext[:])
		if v > maxFrameBytes {
			return nil, 0, errors.New("frame too large")
		}
		length = int(v)
	}
	if length > maxFrameBytes {
		return nil, 0, errors.New("frame too large")
	}
	if !masked {
		return nil, 0, errors.New("client frames must be masked")
	}

	var mask [4]byte
	if _, err := io.ReadFull(c.Conn, mask[:]); err != nil {
		return nil, 0, err
	}
	payload := make([]byte, length)
	if _, err := io.ReadFull(c.Conn, payload); err != nil {
		return nil, 0, err
	}
	for i := range payload {
		payload[i] ^= mask[i%4]
	}
	return payload, opcode, nil
}

// writeFrame writes one unmasked server frame. Server-to-client frames are never masked.
func (c *conn) writeFrame(opcode byte, payload []byte) error {
	header := make([]byte, 0, 10)
	header = append(header, 0x80|opcode) // FIN set: this bridge never fragments
	switch n := len(payload); {
	case n < 126:
		header = append(header, byte(n))
	case n <= 0xFFFF:
		header = append(header, 126, byte(n>>8), byte(n))
	default:
		header = append(header, 127)
		var ext [8]byte
		binary.BigEndian.PutUint64(ext[:], uint64(n))
		header = append(header, ext[:]...)
	}
	if _, err := c.Conn.Write(header); err != nil {
		return err
	}
	if len(payload) == 0 {
		return nil
	}
	_, err := c.Conn.Write(payload)
	return err
}

func (c *conn) SetDeadline(t time.Time) error      { return c.Conn.SetDeadline(t) }
func (c *conn) SetReadDeadline(t time.Time) error  { return c.Conn.SetReadDeadline(t) }
func (c *conn) SetWriteDeadline(t time.Time) error { return c.Conn.SetWriteDeadline(t) }
