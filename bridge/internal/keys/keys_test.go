package keys

import (
	"go/ast"
	"go/parser"
	"go/token"
	"strings"
	"testing"
)

// The three that decide whether typing works at all, and each one is wrong in a way that passes a
// casual test.
func TestTheKeysThatAreEasyToGetWrong(t *testing.T) {
	for _, c := range []struct{ name, want, why string }{
		{"enter", "\r", "a terminal's Return sends CR; LF works in a shell and breaks in vim"},
		{"backspace", "\x7f", "terminals send DEL for Backspace; BS (0x08) is Ctrl-H and binds differently"},
		{"escape", "\x1b", "Escape is a key, not only a prefix"},
	} {
		got, err := Key(c.name)
		if err != nil {
			t.Fatalf("%s: %v", c.name, err)
		}
		if got != c.want {
			t.Errorf("%s sent %q, want %q — %s", c.name, got, c.want, c.why)
		}
	}
}

func TestCursorKeysAreTheNormalModeForms(t *testing.T) {
	for name, want := range map[string]string{
		"up": "\x1b[A", "down": "\x1b[B", "right": "\x1b[C", "left": "\x1b[D",
	} {
		if got, _ := Key(name); got != want {
			t.Errorf("%s sent %q, want %q", name, got, want)
		}
	}
}

// **Text may not carry a control character**, because that is the second way to send an escape
// sequence and it is the one nobody guards.
func TestControlCharactersInTextAreRefused(t *testing.T) {
	for _, text := range []string{
		"ls\r",
		"ls\n",
		"ls\t",
		"\x1b[2J",      // a screen-clearing escape sequence, typed as text
		"a\x00b",       // NUL
		"a\x9bb",       // C1 CSI, which looks like an ordinary high rune
		"rm -rf /\x03", // a control character hidden at the end
	} {
		if _, err := Text(text); err == nil {
			t.Errorf("%q was accepted as text; it can reach the pty as a control sequence", text)
		}
	}
}

// The control: ordinary text a person would actually type must go through untouched, or the test above
// is passing because everything is refused.
func TestOrdinaryTextGoesThroughUnchanged(t *testing.T) {
	for _, text := range []string{
		"ls -la",
		"git commit -m \"fix: the thing\"",
		"привет",
		"echo '日本語'",
		"café",
		"grep -r 'x' . | wc -l",
	} {
		got, err := Text(text)
		if err != nil {
			t.Errorf("%q was refused: %v", text, err)
		}
		if got != text {
			t.Errorf("%q came back as %q; text must not be rewritten", text, got)
		}
	}
}

func TestEmptyTextIsItsOwnAnswer(t *testing.T) {
	if _, err := Text(""); err != ErrEmpty {
		t.Errorf("empty text gave %v, want ErrEmpty", err)
	}
}

func TestOversizedTextIsRefused(t *testing.T) {
	if _, err := Text(strings.Repeat("a", maxTextBytes+1)); err == nil {
		t.Error("an unbounded field on an authenticated request is still unbounded")
	}
}

func TestInvalidUTF8IsRefused(t *testing.T) {
	if _, err := Text("a\xff\xfeb"); err == nil {
		t.Error("bytes the owner did not type reached the pty")
	}
}

// An unknown key must not fall through to something.
func TestAnUnknownKeyIsRefusedAndSaysWhatExists(t *testing.T) {
	_, err := Key("f13")
	if err == nil {
		t.Fatal("an unknown key produced bytes")
	}
	if !strings.Contains(err.Error(), "enter") {
		t.Errorf("the error does not name the closed set, so a caller must guess: %v", err)
	}
}

// **The mechanism, and the arm that carries the weight.**
//
// A closed set of literals is decoration if a value can be assembled at run time, because anything
// assembled at run time can be assembled from input. This walks this package's own source and fails if
// any entry in `named` is anything other than a string literal.
//
// Same guard, same reasoning, as `emitted_commands_test.go` in internal/agterm. That one fired for real
// when the resize verbs were added and named the file and line.
func TestEveryKeySequenceIsALiteral(t *testing.T) {
	fset := token.NewFileSet()
	file, err := parser.ParseFile(fset, "keys.go", nil, 0)
	if err != nil {
		t.Fatal(err)
	}

	checked := 0
	ast.Inspect(file, func(n ast.Node) bool {
		lit, ok := n.(*ast.CompositeLit)
		if !ok {
			return true
		}
		m, ok := lit.Type.(*ast.MapType)
		if !ok || m.Key.(*ast.Ident).Name != "string" {
			return true
		}
		for _, e := range lit.Elts {
			kv, ok := e.(*ast.KeyValueExpr)
			if !ok {
				continue
			}
			if _, ok := kv.Key.(*ast.BasicLit); !ok {
				t.Errorf("%s: a key NAME is computed rather than written down",
					fset.Position(kv.Pos()))
			}
			if _, ok := kv.Value.(*ast.BasicLit); !ok {
				t.Errorf("%s: a key SEQUENCE is computed rather than written down; anything built at "+
					"run time can be built from input", fset.Position(kv.Pos()))
			}
			checked++
		}
		return true
	})

	// The control. A walk that found nothing passes every assertion above for free — which is exactly
	// how a source-walking test rots into a comment.
	if checked != len(named) {
		t.Fatalf("the walk checked %d entries and the map has %d; it is not reading what it thinks",
			checked, len(named))
	}
}

// **The paste markers are written down, not built.**
//
// Same argument as the key sequences above, and the reason it needs its own walk: these are consts
// rather than map entries, so the guard on the map would never look at them. A marker assembled at
// run time is a marker that can be assembled from input, and then the envelope this package puts
// around a paste is one the caller can forge.
func TestThePasteMarkersAreLiterals(t *testing.T) {
	fset := token.NewFileSet()
	file, err := parser.ParseFile(fset, "keys.go", nil, 0)
	if err != nil {
		t.Fatal(err)
	}

	found := map[string]bool{}
	ast.Inspect(file, func(n ast.Node) bool {
		spec, ok := n.(*ast.ValueSpec)
		if !ok {
			return true
		}
		for i, name := range spec.Names {
			if name.Name != "pasteStart" && name.Name != "pasteEnd" {
				continue
			}
			found[name.Name] = true
			if i >= len(spec.Values) {
				t.Errorf("%s: %s has no value", fset.Position(name.Pos()), name.Name)
				continue
			}
			if _, ok := spec.Values[i].(*ast.BasicLit); !ok {
				t.Errorf("%s: %s is computed rather than written down; anything built at run time "+
					"can be built from input", fset.Position(name.Pos()), name.Name)
			}
		}
		return true
	})

	// The control: a walk that found neither passes everything above for free.
	if len(found) != 2 {
		t.Fatalf("the walk found %v; it is not reading what it thinks", found)
	}
}

// **A paste permits the newline and nothing else new.**
//
// The envelope is what makes a newline safe - a terminal told "this is a paste" puts it in the line
// editor instead of running it, measured in zsh and in Claude Code on 2026-08-09. It does not make
// an ESC safe, and an ESC is the one byte that would let a caller close the envelope early and type
// a sequence of their own outside it.
func TestAPasteAllowsLineBreaksAndNothingElseNew(t *testing.T) {
	out, err := Paste("alpha\nbeta")
	if err != nil {
		t.Fatalf("a two-line paste was refused: %v", err)
	}
	if out != "\x1b[200~alpha\nbeta\x1b[201~" {
		t.Errorf("paste produced %q", out)
	}

	for _, bad := range []string{
		"alpha\x1bbeta",      // the byte that would close the envelope
		"alpha\x1b[201~beta", // the end marker itself, which needs that byte to write
		"alpha\rbeta",        // a lone carriage return IS Return on a pty
		"alpha\tbeta",        // still refused: a paste is not a new door for every control byte
		"alpha\x07beta",
	} {
		if _, err := Paste(bad); err == nil {
			t.Errorf("Paste(%q) was accepted; the envelope only excuses the newline", bad)
		}
	}
}

// CRLF is a line ENDING, not a character the owner chose, and refusing it would fail a paste for a
// carriage return they cannot see. A lone CR is a different thing and stays refused.
func TestAPasteNormalisesWindowsLineEndings(t *testing.T) {
	out, err := Paste("alpha\r\nbeta")
	if err != nil {
		t.Fatalf("CRLF was refused: %v", err)
	}
	if out != "\x1b[200~alpha\nbeta\x1b[201~" {
		t.Errorf("paste produced %q; the carriage return should be gone, not carried", out)
	}
}

// No named key may produce something a caller could also produce through Text, or the closed set has a
// second door.
func TestNoNamedKeyIsReachableAsPlainText(t *testing.T) {
	for _, name := range Names() {
		seq, _ := Key(name)
		if _, err := Text(seq); err == nil {
			t.Errorf("key %q sends %q, which Text also accepts — the key set is not the only way in",
				name, seq)
		}
	}
}

// The control characters, each named for what it DOES, with the byte spelled out.
//
// **A table rather than a spot check**, because the failure mode here is silent: a wrong byte in this
// map is a key that does something else on the owner's laptop, and nothing about the request or the
// reply would look wrong. Ctrl-A and Ctrl-U are the two the phone reaches for while editing a long
// command - back to the start, and wipe what is there.
func TestTheControlCharactersAreTheBytesTheyAreNamedFor(t *testing.T) {
	for _, c := range []struct{ name, want, letter string }{
		{"interrupt", "\x03", "Ctrl-C"},
		{"suspend", "\x1a", "Ctrl-Z"},
		{"clear", "\x0c", "Ctrl-L"},
		{"killline", "\x15", "Ctrl-U"},
		{"linestart", "\x01", "Ctrl-A"},
		{"lineend", "\x05", "Ctrl-E"},
	} {
		got, err := Key(c.name)
		if err != nil {
			t.Fatalf("%s (%s): %v", c.name, c.letter, err)
		}
		if got != c.want {
			t.Errorf("%s (%s) sent %q, want %q", c.name, c.letter, got, c.want)
		}
	}
}

// **The list is a list, not a range**, and this is what says so.
//
// Admitting 0x00-0x1f wholesale would hand a caller NUL, ESC and every sequence introducer for the
// sake of six keys. So the C0 bytes this package can emit are named exhaustively: the three that are
// ordinary keys in their own right, and the six control characters. A seventh cannot appear without
// this test being edited too, which is the point - the package comment counts them, and a comment
// that counts something is a comment that goes stale silently.
func TestTheC0BytesAreExactlyTheOnesNamedInTheComment(t *testing.T) {
	want := map[string]bool{
		// Keys, which happen to be C0.
		"enter": true, "tab": true, "escape": true,
		// The control characters, named for what they do.
		"interrupt": true, "suspend": true, "clear": true,
		"killline": true, "linestart": true, "lineend": true,
	}
	got := map[string]bool{}
	for _, name := range Names() {
		seq, _ := Key(name)
		if len(seq) == 1 && seq[0] < 0x20 {
			got[name] = true
		}
	}
	for name := range got {
		if !want[name] {
			t.Errorf("%q emits a C0 byte and is not one the package comment accounts for", name)
		}
	}
	for name := range want {
		if !got[name] {
			t.Errorf("%q was expected to emit a single C0 byte and does not", name)
		}
	}
}

// **A key that is no longer offered is no longer REACHABLE.** That is the difference between an
// allowlist and a catalogue.
//
// Ctrl-D was on the bar and the owner had it removed - *"я вообще не знаю что зачем"*. Taking the
// button away while leaving 0x04 in the map would keep it sendable by anything that has got past
// pairing, for the benefit of nobody: end-of-input closes a shell. This asserts the removal happened
// where it counts, and that the byte did not simply reappear under a different name.
func TestARemovedKeyIsGoneFromTheAllowlistAndNotOnlyFromTheBar(t *testing.T) {
	if _, err := Key("eof"); err == nil {
		t.Error("eof still resolves; it was taken off the bar but left in the allowlist")
	}
	for _, name := range Names() {
		if seq, _ := Key(name); seq == "\x04" {
			t.Errorf("%q still emits 0x04 under another name", name)
		}
	}
}
