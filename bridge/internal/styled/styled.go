// Package styled reads a pane's screen WITH its colours, through zmx rather than through agterm.
//
// agterm's `session.text` is plain UTF-8: libghostty's read API drops every attribute before the
// bridge sees the text, which is why the colours have to come from somewhere else. A pane running
// under agterm's "Live sessions" mode is owned by a zmx daemon, and that daemon keeps its own
// ghostty-vt terminal so it can rehydrate a reconnecting client. `zmx history <daemon> --vt` asks
// it to serialise that terminal with SGR sequences intact — the same ghostty formatter agterm would
// use, running in another process.
//
// What comes back is a REHYDRATION dump, not a screen read: it opens with the terminal's private
// modes, closes with the cursor position, a charset select, keyboard-protocol flags and OSC 7, and
// its rows end in CRLF. This package reduces that to the one thing the phone can draw — text plus
// SGR — and cuts it to the last N rows without losing the style in effect at the cut.
//
// The phone opts in per request (`styled: true`); nothing here runs otherwise. Any failure on this
// path is the caller's cue to fall back to `session.text`, which is why nothing here returns a
// partial answer.
package styled

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"os/exec"
	"strings"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
)

// Pick finds the running daemon behind one pane. False when the pane has none, which is the normal
// state for a session created before Live mode was switched on, a split whose process has exited,
// or a scratch pane — none of which is an error the phone should see.
func Pick(entries []agterm.ZmxEntry, sessionID, pane string) (string, bool) {
	for _, e := range entries {
		if e.SessionID == sessionID && e.Pane == pane && e.Observation == "running" && e.Daemon != "" {
			return e.Daemon, true
		}
	}
	return "", false
}

// History runs the bundled zmx against agterm's socket directory and returns the raw --vt dump.
//
// exec rather than the daemon's socket protocol: the wire format is zmx-internal and pinned by
// agterm's build, while the CLI is what agterm itself scripts. Both paths come from `zmx.list`, so
// the bridge never guesses where the binary or the sockets live.
func History(ctx context.Context, executable, socketDir, daemon string) (string, error) {
	cmd := exec.CommandContext(ctx, executable, "history", daemon, "--vt")
	cmd.Env = append(os.Environ(), "ZMX_DIR="+socketDir)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		msg := strings.TrimSpace(stderr.String())
		if msg == "" {
			msg = err.Error()
		}
		return "", fmt.Errorf("zmx history %s: %s", daemon, msg)
	}
	return stdout.String(), nil
}

// Clean reduces a --vt dump to text and SGR: every other escape sequence is removed, CRLF becomes
// LF, and trailing rows that hold nothing but whitespace and resets are dropped, so `lines` counts
// content rows the way agterm's own `--lines` does.
func Clean(dump string) string {
	var out strings.Builder
	out.Grow(len(dump))
	for i := 0; i < len(dump); {
		c := dump[i]
		if c != 0x1b {
			if c != '\r' {
				out.WriteByte(c)
			}
			i++
			continue
		}
		seq, n := escape(dump[i:])
		if isSGR(seq) {
			out.WriteString(seq)
		}
		i += n
	}
	rows := strings.Split(out.String(), "\n")
	for len(rows) > 0 && strings.TrimSpace(Strip(rows[len(rows)-1])) == "" {
		rows = rows[:len(rows)-1]
	}
	return strings.Join(rows, "\n")
}

// Strip removes every escape sequence, leaving the text a plain read would have returned. It is
// what column measurement runs on, since an SGR occupies no cell.
func Strip(s string) string {
	var out strings.Builder
	out.Grow(len(s))
	for i := 0; i < len(s); {
		if s[i] != 0x1b {
			out.WriteByte(s[i])
			i++
			continue
		}
		_, n := escape(s[i:])
		i += n
	}
	return out.String()
}

// Tail keeps the last n rows of a cleaned dump and restates, ahead of the first kept row, the SGR
// state that was in effect there. Styles carry across rows in a VT stream, so a plain slice would
// hand the phone a first row whose colour was set on a row it never saw.
func Tail(clean string, n int) string {
	rows := strings.Split(clean, "\n")
	if n <= 0 || len(rows) <= n {
		return clean
	}
	dropped := rows[:len(rows)-n]
	kept := strings.Join(rows[len(rows)-n:], "\n")
	state := sgrState(strings.Join(dropped, "\n"))
	if state == "" {
		return kept
	}
	return "\x1b[0m" + state + kept
}

// sgrState replays the SGR sequences in s and returns those still in force at its end: a reset
// clears everything, anything else accumulates. Coarse on purpose — `1m` then `22m` is kept as both
// rather than cancelled — because the phone applies them in order and reaches the same style.
func sgrState(s string) string {
	var live []string
	for i := 0; i < len(s); {
		if s[i] != 0x1b {
			i++
			continue
		}
		seq, n := escape(s[i:])
		i += n
		if !isSGR(seq) {
			continue
		}
		params := seq[2 : len(seq)-1]
		if params == "" || params == "0" {
			live = live[:0]
			continue
		}
		live = append(live, seq)
	}
	return strings.Join(live, "")
}

// isSGR reports whether seq is `ESC [ <digits and semicolons> m`. A private-parameter CSI such as
// `ESC[>4;2m` (modifyOtherKeys) also ends in `m` and is not one.
func isSGR(seq string) bool {
	if len(seq) < 3 || seq[0] != 0x1b || seq[1] != '[' || seq[len(seq)-1] != 'm' {
		return false
	}
	for _, c := range seq[2 : len(seq)-1] {
		if c != ';' && (c < '0' || c > '9') {
			return false
		}
	}
	return true
}

// escape returns the escape sequence starting at s[0] == ESC and its length. It understands CSI
// (`ESC [ ... final`), OSC (`ESC ] ... BEL|ST`), DCS/PM/APC (`ESC P|^|_ ... ST`), and the two-byte
// forms (`ESC ( B`, `ESC 7`). A truncated sequence swallows the rest of the input rather than
// leaking half an escape into the text.
func escape(s string) (string, int) {
	if len(s) < 2 {
		return s, len(s)
	}
	switch s[1] {
	case '[':
		for i := 2; i < len(s); i++ {
			if s[i] >= 0x40 && s[i] <= 0x7e {
				return s[:i+1], i + 1
			}
		}
		return s, len(s)
	case ']', 'P', '^', '_':
		for i := 2; i < len(s); i++ {
			if s[i] == 0x07 {
				return s[:i+1], i + 1
			}
			if s[i] == 0x1b && i+1 < len(s) && s[i+1] == '\\' {
				return s[:i+2], i + 2
			}
		}
		return s, len(s)
	case '(', ')', '*', '+', '#', '%':
		if len(s) >= 3 {
			return s[:3], 3
		}
		return s, len(s)
	default:
		return s[:2], 2
	}
}
