// Package keys turns what the phone can express into the bytes a terminal expects.
//
// # Why this is a package and not four lines in the handler
//
// This is the whole substance of typing. Everything else — a verb, a text field, a button — is
// plumbing that can be read and checked at a glance. What a keystroke IS on the wire is where the
// decisions live: whether Enter is a newline, what Backspace sends, whether an arrow key is three
// bytes or four, and what happens to a byte the owner could not have typed.
//
// It is pure. No agterm, no network, no state. That is deliberate: the part most likely to be wrong is
// the part that needs no Mac to test, and by the time the write verb exists this has nothing left to
// decide.
//
// # The rule this package exists to enforce
//
// **The phone never says which bytes to send.** It names a key from a closed set, or it sends text
// that must survive a check. It cannot compose an escape sequence, and it cannot smuggle one inside
// text — because a caller that can put arbitrary bytes on a pty can do considerably more than type.
//
// That is the same shape as the agterm command allowlist: a closed set written down in our own source,
// so no input can produce a member that is not on it. The difference between "we filter what looks
// dangerous" and "only these exist" is the difference between a rule and a mechanism.
//
// # What this deliberately does NOT solve
//
// **Application cursor key mode.** A terminal in DECCKM sends `ESC O A` for Up where a terminal in the
// normal mode sends `ESC [ A`; `vim` and `less` turn it on. Nothing in the control channel reports the
// mode, so this package sends the normal-mode form always. The honest consequence is that arrow keys
// may misbehave inside a full-screen program, and that is written here rather than discovered later —
// see [Up]. Guessing per-application would be worse: wrong silently instead of wrong predictably.
package keys

import (
	"errors"
	"fmt"
	"sort"
	"strings"
	"unicode/utf8"
)

// maxTextBytes bounds one typing request.
//
// Generous for a person typing on a phone and far below anything that costs the pty something. The
// bound exists because every field an authenticated caller controls needs one — a phone that has been
// taken is still a caller.
const maxTextBytes = 4 << 10

// ErrEmpty means the request asked for nothing to be typed.
//
// Its own error because it is the shape of a bug rather than an attack: a UI that fires on every
// keystroke and sends an empty one when the field is cleared. Saying so beats silently succeeding.
var ErrEmpty = errors.New("nothing to type")

// named is every key the phone may ask for, and the exact bytes each one produces.
//
// **A map of literals, and that is the mechanism.** There is no code path in this package that
// constructs an escape sequence from input; the sequences exist here, spelled out, or they do not
// exist at all. `TestEveryKeySequenceIsALiteral` walks this file's source and fails if a value is
// computed rather than written down — the same guard, and for the same reason, as the agterm command
// allowlist.
//
// The choices that are not obvious:
//
//   - **Enter is CR, not LF.** A terminal's Return key sends carriage return; the line discipline turns
//     it into a newline. Sending LF works in a shell and breaks in `vim`, which is exactly the class of
//     bug that looks fine in the first test.
//
//   - **Backspace is DEL (0x7f), not BS (0x08).** Every modern terminal sends DEL for the Backspace
//     key. BS is what Ctrl-H sends, and shells bind the two differently.
//
//   - **The control characters are a short list, not a range.** Ctrl-C, Ctrl-Z, Ctrl-L, Ctrl-U,
//     Ctrl-A and Ctrl-E are what a person actually needs from a phone: interrupt, suspend, clear,
//     kill-line, start-of-line, end-of-line. Admitting the whole 0x00-0x1f range because it is tidier
//     would hand a caller NUL, the terminal's own escape, and every sequence introducer, for the sake
//     of six keys.
//
//     The list grows and shrinks one entry at a time and each move is a decision. On 2026-07-31 the
//     owner asked for Ctrl-A and Ctrl-E by name - a phone keyboard has no Home or End that a shell's
//     line editor listens to, so neither end of a long command was reachable from the phone at all.
//
//     **Ctrl-D (0x04, "eof") was REMOVED the same day**, at their request: *"ctrl+d кстати я вообще
//     не знаю что зачем - можешь убрать"*. It is deleted from the map rather than merely left
//     unoffered by the phone, because an allowlist is the minimum a caller may ask for and not a
//     catalogue of what was once on the bar. Leaving 0x04 reachable with no button behind it widens
//     what a taken phone can send and buys nobody anything - end-of-input closes a shell. One line to
//     put back if they ever want it.
var named = map[string]string{
	// The three that make typing usable at all.
	"enter":     "\r",
	"tab":       "\t",
	"backspace": "\x7f",

	// Escape, which is a key on its own as well as a prefix. Sent bare, as pressing it does.
	"escape": "\x1b",

	// Cursor keys, normal mode. See the package comment on DECCKM.
	"up":    "\x1b[A",
	"down":  "\x1b[B",
	"right": "\x1b[C",
	"left":  "\x1b[D",

	"home":     "\x1b[H",
	"end":      "\x1b[F",
	"pageup":   "\x1b[5~",
	"pagedown": "\x1b[6~",
	"delete":   "\x1b[3~",

	// The control characters, named for what they DO rather than for the letter, because the owner is
	// pressing a button on a phone and not holding a modifier.
	"interrupt": "\x03", // Ctrl-C
	"suspend":   "\x1a", // Ctrl-Z
	"clear":     "\x0c", // Ctrl-L
	"killline":  "\x15", // Ctrl-U
	"linestart": "\x01", // Ctrl-A
	"lineend":   "\x05", // Ctrl-E
}

// Names lists the keys the phone may ask for, sorted. For the app, and for the error below.
func Names() []string {
	out := make([]string, 0, len(named))
	for k := range named {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

// Key returns the bytes for a named key.
//
// The error NAMES the alternatives, because the caller here is our own app and a closed set it cannot
// discover is a closed set somebody guesses at.
func Key(name string) (string, error) {
	seq, ok := named[name]
	if !ok {
		return "", fmt.Errorf("no key %q; the keys are %s", name, strings.Join(Names(), ", "))
	}
	return seq, nil
}

// Text returns the bytes for literal text the owner typed.
//
// **Control characters are refused, not stripped.** Stripping silently changes what they typed and
// leaves them looking at a terminal that received something they did not send. Refusing is one clear
// failure instead of a class of quiet ones — and it is what keeps this path from becoming a second,
// unchecked way to send an escape sequence.
//
// A newline in text is refused for the same reason it is not how Enter is sent: "type a line" and
// "press Return" are different acts, and collapsing them makes the second unavailable inside any
// program that treats them differently.
func Text(text string) (string, error) {
	if text == "" {
		return "", ErrEmpty
	}
	if len(text) > maxTextBytes {
		return "", fmt.Errorf("text is %d bytes, the limit is %d", len(text), maxTextBytes)
	}
	if !utf8.ValidString(text) {
		// Not merely tidiness: invalid UTF-8 reaching a pty is bytes the owner did not type, and the
		// terminal's interpretation of them is anybody's guess.
		return "", errors.New("text is not valid UTF-8")
	}
	for i, r := range text {
		if isControl(r) {
			return "", fmt.Errorf(
				"text contains a control character (%#U at byte %d); press a key instead, one of %s",
				r, i, strings.Join(Names(), ", "))
		}
	}
	return text, nil
}

// The bracketed paste markers, written down here and assembled nowhere.
//
// A terminal that has turned bracketed paste on — measured 2026-08-09: zsh does at every prompt, and
// Claude Code emits `ESC[?2004h` on startup — treats everything between these as PASTED rather than
// TYPED. The newlines inside land as line breaks in whatever is reading, instead of as Return.
//
// **Literals, and const, so the AST guard in this package's tests can see them and so nothing can
// build them at run time.** The whole safety argument of this package is that a caller supplies text
// and never a sequence; a marker computed from anything would be a second door into that.
const (
	pasteStart = "\x1b[200~"
	pasteEnd   = "\x1b[201~"
)

// Paste returns the bytes for text the owner PASTED, wrapped so the far end knows it is a paste.
//
// # Why this exists rather than letting Text take a newline
//
// The owner pasted a message out of a chat app, and [Text] refused it at byte 75 — rightly,
// because a bare newline on a pty is a Return they did not press. Measured in a real zsh: two lines
// sent raw ran the first one. The same two lines between these markers sat in the line editor in
// reverse video and ran nothing, and in Claude Code they landed as two lines in the composer with
// nothing submitted.
//
// So the answer to "I want to paste several lines" is not to relax [Text]. It is a different act with
// a different envelope, which is exactly what a terminal invented bracketed paste for.
//
// # What is allowed inside, and what is not
//
// **Only the newline is added to what [Text] permits.** ESC stays refused, which is what makes this
// containable: a caller cannot terminate the paste early, cannot name a sequence, and cannot smuggle
// [pasteEnd] into the payload, because writing it requires a byte they may not send.
//
// CRLF is normalised to LF — the one substitution here, and it is a line ending rather than a
// character: text copied from a Windows-ish source would otherwise be refused for a carriage return
// the owner cannot see. A LONE carriage return is still refused, because on a pty that IS Return.
//
// # The promise this package makes, stated precisely
//
// It has not weakened. **The only control bytes that reach the pty are ones written down in this
// file, and nothing a caller sends can name or assemble one.** [Text] refuses every control
// character; [Key] returns one of a closed set of literals; [Paste] adds a literal envelope of its
// own and permits exactly one control character inside it, the one whose meaning the envelope
// changes.
func Paste(text string) (string, error) {
	if text == "" {
		return "", ErrEmpty
	}
	// Normalised before the length check, so the bound is on what will actually be sent.
	text = strings.ReplaceAll(text, "\r\n", "\n")
	if len(text) > maxTextBytes {
		return "", fmt.Errorf("pasted text is %d bytes, the limit is %d", len(text), maxTextBytes)
	}
	if !utf8.ValidString(text) {
		return "", errors.New("pasted text is not valid UTF-8")
	}
	for i, r := range text {
		if r == '\n' {
			continue
		}
		if isControl(r) {
			return "", fmt.Errorf(
				"pasted text contains a control character (%#U at byte %d); only line breaks are "+
					"allowed in a paste", r, i)
		}
	}
	return pasteStart + text + pasteEnd, nil
}

// maxLabelRunes bounds a workspace or session name.
//
// **Runes, not bytes**, because the bound exists to keep a sidebar label readable and a person typing
// Cyrillic would otherwise get half the allowance of a person typing ASCII.
//
// Sixty-four is chosen against measurement rather than taste: on 2026-07-31 the owner's live tree held
// workspace names of 4-19 characters and session names of 2-39, so this is comfortably above every
// name they actually use and far below anything that turns a sidebar into a wall. The number matters
// less than that a number EXISTS - every field an authenticated caller controls needs one, and a phone
// that has been taken is still a caller.
const maxLabelRunes = 64

// Label checks a name the owner typed for a workspace or a session, and returns it trimmed.
//
// # Why this lives beside Text instead of being Text
//
// The check is the same check — [isControl], the one predicate, guarded by this package's own AST
// test — because two copies of "what counts as a control character" is one copy that drifts. What
// differs is everything a caller sees.
//
// [Text] is for keystrokes: it refuses a newline because "type a line" and "press Return" are
// different acts, it allows four kilobytes, and its error names the key alternatives and QUOTES the
// offending rune so the owner can see what the terminal would have received. Every one of those is
// wrong for a rename. A name has no Return to press, sixty-four runes is the ceiling, "one of enter,
// tab, backspace…" is not advice about a name — and the quoted rune is a character of the name
// itself.
//
// # The name never appears in the error
//
// Same rule as session names, workspace names and screen contents: it is not logged, not persisted,
// and not put in a message. So these errors describe the SHAPE of the problem — too long, empty,
// contains a control character — and a caller who wants to know which character it was has the name in
// front of them already. `TestLabelErrorsNeverQuoteTheInput` holds this, because it is the kind of
// rule that survives right up until someone adds %q to make debugging easier.
func Label(name string) (string, error) {
	trimmed := strings.TrimSpace(name)
	if trimmed == "" {
		// Checked AFTER trimming, so a name of three spaces is refused rather than stored as three
		// spaces - which would leave the owner looking at a sidebar row with no visible label and no
		// account of why.
		return "", errors.New("a name cannot be empty")
	}
	if !utf8.ValidString(trimmed) {
		return "", errors.New("a name must be valid UTF-8")
	}
	if n := utf8.RuneCountInString(trimmed); n > maxLabelRunes {
		return "", fmt.Errorf("a name may be %d characters, this one is %d", maxLabelRunes, n)
	}
	for _, r := range trimmed {
		if isControl(r) {
			return "", errors.New("a name cannot contain a control character")
		}
	}
	return trimmed, nil
}

// isControl is the C0 range, DEL, and the C1 range.
//
// C1 (0x80–0x9f) is included because those are control codes too, and a terminal decoding UTF-8 will
// act on some of them. They are easy to omit precisely because they look like ordinary high runes.
func isControl(r rune) bool {
	return r < 0x20 || r == 0x7f || (r >= 0x80 && r <= 0x9f)
}
