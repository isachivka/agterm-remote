// Package zmxhold is a client of one zmx daemon that exists to hold the pty at a size.
//
// # Why a second client, and why it has to be a client at all
//
// The pane's height cannot come from agterm: `window.resize` is clamped to the screen's visible
// frame, so a window tall enough for two hundred rows is not a window macOS will give us. The zmx
// daemon behind a Live pane has no such limit. Its pty is sized by whichever client is its LEADER,
// and leadership is not agterm's by right - it belongs to the last client that typed. A second client
// that types nothing of consequence and then states a size is therefore enough, and the daemon does
// the rest: Claude Code sees a 200-row terminal and renders two hundred rows.
//
// The desktop pane shows the same stream squeezed into its own viewport while this is on. The owner
// accepted that; the palette command "Undo phone fit" is the way out, and Release is what it calls.
//
// # The protocol, as the daemon at rev fb1b6b6 speaks it
//
// A frame is an EIGHT-byte header followed by the payload: the tag (u8), the payload length as a
// little-endian u32, and three bytes of padding sent as zero. Eight and not five because zmx declares
// the header as a packed struct, which Zig sizes as its u40 backing integer - see [frame], and the
// daemon this once killed by sending five. A size is four little-endian u16 (rows, cols, xpixel,
// ypixel), and it is sent TWICE on
// every Init and Resize: the 8-byte form and then the 4-byte legacy form, exactly as zmx's own attach
// does, because a daemon drops the length it does not expect and processes the other once.
//
// Leadership, from `handleInit`, `handleInput` and `handleResize` in the daemon's loop.zig:
//
//   - Init from a client when there is no leader makes that client leader and applies its size; a
//     follower's Init applies nothing. That asymmetry is what [Restore] relies on.
//   - Input that the daemon classifies as "user input" makes the sender leader and is forwarded to
//     the pty. On becoming leader the daemon sends that client a Resize with an EMPTY payload, which
//     means "tell me your size", and expects a Resize back.
//   - Resize from a follower is ignored. Detach closes the client and clears the leader if it was one.
//
// # The claim input, and why nothing else is safe
//
// [ClaimInput] is an empty bracketed paste: the paste-start and paste-end markers with nothing
// between. The daemon's classifier reads a CSI ending in `~` as user input, so it hands us
// leadership; Claude Code, zsh and vim all read an empty paste as nothing at all. Every other byte
// that would count as input is a keystroke, and a keystroke lands in whatever program the owner
// left in that pane. Use exactly this; do not "improve" it.
package zmxhold

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"time"
)

// The daemon's message tags. Only the ones this client sends or reacts to are named; anything else
// that arrives is drained and ignored, which is what the daemon does with tags it does not know.
const (
	tagInput  byte = 0
	tagOutput byte = 1
	tagResize byte = 2
	tagDetach byte = 3
	tagInit   byte = 7
)

// ClaimInput is the one byte sequence this package ever puts on a pty. See the package comment.
const ClaimInput = "\x1b[200~\x1b[201~"

// Size is a terminal size in cells. Pixel sizes are always sent as zero, as zmx's own attach does
// when the terminal reports none.
type Size struct{ Rows, Cols int }

// valid refuses what the wire cannot carry and what no terminal can be. A zero row count would be
// sent as a real size and the daemon would resize the pty to it.
func (s Size) valid() error {
	if s.Rows <= 0 || s.Cols <= 0 || s.Rows > 0xFFFF || s.Cols > 0xFFFF {
		return fmt.Errorf("a terminal cannot be %d rows by %d columns", s.Rows, s.Cols)
	}
	return nil
}

// writeTimeout bounds one write to the daemon. The daemon reads its socket in its poll loop, so a
// write that does not complete in this long is a daemon that has stopped, not a slow one.
const writeTimeout = 5 * time.Second

// drainTimeout is how long Release and Restore wait for the daemon to close the connection after a
// Detach. The daemon does close it - that is what Detach means - so this is a ceiling for a daemon
// that has died mid-conversation, not a duration anything depends on.
const drainTimeout = 2 * time.Second

// Hold is a live connection holding one daemon's pty at a size.
type Hold struct {
	conn net.Conn
	// wmu serialises writes: the caller's and the reader goroutine's, which answers the daemon's
	// size question by itself. Two frames interleaved on the wire would be one corrupt frame.
	wmu sync.Mutex

	mu     sync.Mutex
	held   Size
	closed bool

	// done is closed when the reader goroutine stops, and readErr says why.
	done    chan struct{}
	readErr error
}

// Open connects to the daemon at socketPath, introduces this client with Init(size), and claims
// leadership at that size. The connection stays open: the daemon asks its leader for a size whenever
// leadership changes hands, and the reader goroutine answers with the held size for as long as this
// Hold lives.
func Open(ctx context.Context, socketPath string, size Size) (*Hold, error) {
	if err := size.valid(); err != nil {
		return nil, err
	}
	var d net.Dialer
	conn, err := d.DialContext(ctx, "unix", socketPath)
	if err != nil {
		return nil, daemonErr(err)
	}
	h := &Hold{conn: conn, held: size, done: make(chan struct{})}
	go h.read()

	// Init first, as every client does: it registers this connection as a terminal. When nobody
	// leads, this alone makes us leader and applies the size; when agterm leads, it applies nothing
	// and the Claim below does the work.
	if err := h.write(sizeFrames(tagInit, size)); err != nil {
		_ = h.Close()
		return nil, err
	}
	if err := h.Claim(size); err != nil {
		_ = h.Close()
		return nil, err
	}
	return h, nil
}

// Claim takes leadership and states a size: Input(ClaimInput), then Resize(size). The size is
// remembered so the reader can restate it when the daemon asks.
//
// Called again on a live Hold when the pty has been seen at another size - the owner typed on the
// Mac, agterm became leader and its size was applied - or when the fit's column count changed.
func (h *Hold) Claim(size Size) error {
	if err := size.valid(); err != nil {
		return err
	}
	h.mu.Lock()
	h.held = size
	h.mu.Unlock()
	// The size is recorded BEFORE the claim goes out: the daemon's "tell me your size" can arrive
	// the instant it reads the Input, and the reader must answer with this size and not the last.
	if err := h.write(frame(tagInput, []byte(ClaimInput))); err != nil {
		return err
	}
	return h.write(sizeFrames(tagResize, size))
}

// Held is the size this Hold last claimed.
func (h *Hold) Held() Size {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.held
}

// Release puts the pty back to original and lets go: Resize(original), then Detach, then the
// connection is closed. Detach clears the leader, so agterm's own client can take the pty back the
// next time the owner types, at agterm's size.
//
// If agterm has already taken leadership back, the Resize is ignored by the daemon - and rightly,
// because agterm's size is then the one in force. Either way the pty ends at a size somebody at the
// Mac chose.
func (h *Hold) Release(original Size) error {
	if err := original.valid(); err != nil {
		return err
	}
	// The reader may still owe the daemon an answer to "tell me your size" - the question is queued
	// behind the snapshot Init provokes, and the reader can reach it at any moment. So the answer it
	// would give is switched to the original BEFORE anything goes out, and the Resize and the Detach
	// leave in ONE write, under one hold of the write lock: with two, the reader's answer could land
	// between them and the stream would read Resize(original), Resize(tall), Detach - a pty left tall
	// by a release that reported success.
	h.mu.Lock()
	h.held = original
	h.mu.Unlock()
	err := h.write(append(sizeFrames(tagResize, original), frame(tagDetach, nil)...))
	// The daemon closes a detached client. Waiting for that, briefly, is what makes "released" mean
	// the daemon has read the frames rather than that they left this process.
	select {
	case <-h.done:
	case <-time.After(drainTimeout):
	}
	if cerr := h.Close(); err == nil {
		err = cerr
	}
	return err
}

// Close drops the connection without restating anything. The daemon clears the leader when the
// socket goes, so the pty keeps its last size until agterm or another client states one.
func (h *Hold) Close() error {
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		return nil
	}
	h.closed = true
	h.mu.Unlock()
	err := h.conn.Close()
	<-h.done
	return err
}

// Restore is the one-shot form of Release for a bridge that holds no live connection - it restarted
// while a fit was in force, and the connection that held the pty died with the old process.
//
// It is Init(original) then Detach. Init makes this client leader ONLY when nobody is, which is
// exactly the state a dead hold leaves behind: the daemon cleared the leader when our socket went,
// and agterm's client does not take it back until the owner types. So the size is applied when it
// should be and ignored when agterm has already moved on, with nothing to decide here.
func Restore(ctx context.Context, socketPath string, original Size) error {
	if err := original.valid(); err != nil {
		return err
	}
	var d net.Dialer
	conn, err := d.DialContext(ctx, "unix", socketPath)
	if err != nil {
		return daemonErr(err)
	}
	defer conn.Close()

	_ = conn.SetWriteDeadline(time.Now().Add(writeTimeout))
	if _, err := conn.Write(sizeFrames(tagInit, original)); err != nil {
		return daemonErr(err)
	}
	if _, err := conn.Write(frame(tagDetach, nil)); err != nil {
		return daemonErr(err)
	}
	// Drain until the daemon closes us, so the frames are known to have been read. A daemon that
	// never does is one that has died, and the pty it owned died with it.
	deadline := time.Now().Add(drainTimeout)
	if dl, ok := ctx.Deadline(); ok && dl.Before(deadline) {
		deadline = dl
	}
	_ = conn.SetReadDeadline(deadline)
	_, _ = io.Copy(io.Discard, conn)
	return nil
}

// write sends one or more frames as a single write under the write lock.
func (h *Hold) write(frames []byte) error {
	h.wmu.Lock()
	defer h.wmu.Unlock()
	_ = h.conn.SetWriteDeadline(time.Now().Add(writeTimeout))
	if _, err := h.conn.Write(frames); err != nil {
		return daemonErr(err)
	}
	return nil
}

// read drains everything the daemon sends and answers its one question.
//
// Output is broadcast to every client, and a client that Inits after the daemon has had one before
// is sent a snapshot of the whole terminal - megabytes, for a long scrollback. None of it is wanted
// here, and none of it may be left unread: a socket buffer that fills stalls the daemon's write to
// us, and the daemon's loop is single-threaded.
func (h *Hold) read() {
	defer close(h.done)
	header := make([]byte, headerLen)
	for {
		if _, err := io.ReadFull(h.conn, header); err != nil {
			h.readErr = daemonErr(err)
			return
		}
		tag, n := header[0], int64(binary.LittleEndian.Uint32(header[1:5]))
		if n > 0 {
			if _, err := io.CopyN(io.Discard, h.conn, n); err != nil {
				h.readErr = daemonErr(err)
				return
			}
			continue
		}
		if tag == tagResize {
			// "Tell me your size": the daemon just made us leader. The answer is what was claimed,
			// which is the whole reason the size is remembered.
			if err := h.write(sizeFrames(tagResize, h.Held())); err != nil {
				h.readErr = daemonErr(err)
				return
			}
		}
	}
}

// Err reports why the reader stopped, once it has: the daemon closed the connection, or a write
// failed. Nil while the connection is live.
func (h *Hold) Err() error {
	select {
	case <-h.done:
		if h.readErr == nil {
			return errors.New("zmx daemon: connection closed")
		}
		return h.readErr
	default:
		return nil
	}
}

// daemonErr is every error this package returns about the daemon, and it carries no address.
//
// A net.OpError renders as `dial unix /the/socket/dir/agterm-<name>: connect: ...`: the socket
// path, which names the daemon and is enough to dial the owner's pty from. The callers log these
// with `%v`, and their log has a rule against exactly that. So the operation and the underlying
// error are kept and the address is dropped - nothing in a log needs it, and the bridge itself
// already knows which daemon it was talking to.
func daemonErr(err error) error {
	var op *net.OpError
	if errors.As(err, &op) && op.Err != nil {
		return fmt.Errorf("zmx daemon: %s: %w", op.Op, op.Err)
	}
	return fmt.Errorf("zmx daemon: %w", err)
}

// frame is one message: an 8-byte header, then the payload.
//
// **The header is 8 bytes, not the 5 its two fields add up to.** zmx declares it as a packed struct
// of a u8 tag and a u32 length, and Zig sizes a packed struct as its backing integer - a u40 - whose
// ABI size is 8. The daemon reads exactly @sizeOf(Header) bytes per frame, so a 5-byte header shifts
// every byte after it: the first real daemon this ran against read a payload byte as tag 5, Kill,
// and shut itself down. The three trailing bytes are padding and are sent as zero.
func frame(tag byte, payload []byte) []byte {
	b := make([]byte, headerLen, headerLen+len(payload))
	b[0] = tag
	binary.LittleEndian.PutUint32(b[1:5], uint32(len(payload)))
	return append(b, payload...)
}

// headerLen is @sizeOf(ipc.Header) in the daemon: see frame.
const headerLen = 8

// sizeFrames is a size message in both encodings, 8-byte then 4-byte, back to back.
func sizeFrames(tag byte, s Size) []byte {
	p := make([]byte, 8)
	binary.LittleEndian.PutUint16(p[0:2], uint16(s.Rows))
	binary.LittleEndian.PutUint16(p[2:4], uint16(s.Cols))
	return append(frame(tag, p), frame(tag, p[:4])...)
}
