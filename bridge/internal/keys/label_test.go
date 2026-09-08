package keys

import (
	"strings"
	"testing"
)

// The names the owner types when they rename a workspace or a session.
//
// [Label] shares one predicate with [Text] and shares nothing else — a different bound, a different
// unit for that bound, and a deliberately duller error. Each of those is tested here rather than
// assumed from the fact that the two functions sit in one file.

func TestAnOrdinaryNameGoesThroughTrimmed(t *testing.T) {
	got, err := Label("  release notes  ")
	if err != nil {
		t.Fatalf("a perfectly ordinary name was refused: %v", err)
	}
	if got != "release notes" {
		t.Errorf("Label(%q) = %q, want the trimmed name", "  release notes  ", got)
	}
}

// **Trimming happens before the emptiness check, not after it.** A name of three spaces that survived
// would leave the owner looking at a sidebar row with no visible label and no account of why.
func TestANameThatIsOnlyWhitespaceIsRefused(t *testing.T) {
	for _, name := range []string{"", "   ", "\t \t"} {
		if _, err := Label(name); err == nil {
			t.Errorf("Label(%q) was accepted; a name that renders as nothing is not a name", name)
		}
	}
}

// The boundary, from both sides. A cap tested only from far away is a cap whose comparison could be
// `>=` and nobody would know.
func TestTheLengthCapIsExactlyWhereItSaysItIs(t *testing.T) {
	if _, err := Label(strings.Repeat("a", maxLabelRunes)); err != nil {
		t.Errorf("a name of exactly %d characters was refused: %v", maxLabelRunes, err)
	}
	if _, err := Label(strings.Repeat("a", maxLabelRunes+1)); err == nil {
		t.Errorf("a name of %d characters was accepted; the cap is %d", maxLabelRunes+1, maxLabelRunes)
	}
}

// **The cap counts RUNES, and a test written only in ASCII cannot tell.**
//
// This is the assertion the whole unit choice rests on: `len(s)` would give a Cyrillic name half the
// allowance of an English one, so the owner writing a Russian workspace name — which they do — would
// hit a limit an English speaker never sees. Sixty-four two-byte runes is 128 bytes, so a
// byte-counting implementation fails right here.
func TestTheCapIsInRunesAndNotInBytes(t *testing.T) {
	cyrillic := strings.Repeat("я", maxLabelRunes)
	if len(cyrillic) <= maxLabelRunes {
		t.Fatalf("this test cannot detect the bug it exists for: %d bytes is within the rune cap", len(cyrillic))
	}
	if _, err := Label(cyrillic); err != nil {
		t.Errorf("a %d-rune Cyrillic name was refused, so the cap is counting bytes: %v", maxLabelRunes, err)
	}
}

// The same predicate [Text] uses, reached through a different door.
//
// **The C1 case is built from its code point rather than typed.** A raw control byte sitting in source
// is invisible in every diff and in every review, and one editor that normalises it away deletes the
// only thing this case tests while the test stays green. C1 is the range most worth holding here
// precisely because those bytes look like ordinary high runes.
func TestControlCharactersInANameAreRefused(t *testing.T) {
	nel := string(rune(0x85)) // NEL, in the C1 range

	for _, name := range []string{
		"before\x00after", // NUL
		"two\nlines",      // a newline is not a name
		"tab\there",
		"esc\x1b[31m", // the sequence introducer, which is the reason this check exists at all
		"c1" + nel + "here",
	} {
		if _, err := Label(name); err == nil {
			t.Errorf("Label(%q) was accepted; a control character in a name is the thing this refuses", name)
		}
	}
}

// **A name is never logged, never persisted, and never put in an error message.**
//
// The same rule as session names, workspace names and screen contents. This is the kind of rule that
// survives right up until somebody adds `%q` to make debugging easier, so it is held by a test rather
// than by a sentence in a comment.
//
// [Text] does quote the offending rune, deliberately and for keystrokes. Label must not, and that
// difference is the reason these are two functions.
func TestLabelErrorsNeverQuoteTheInput(t *testing.T) {
	// Every one of these must be REFUSED, or it contributes nothing — which is asserted rather than
	// assumed, because a leak test whose inputs are all accepted passes for free.
	secrets := []string{
		strings.Repeat("хозяйская", 9), // over the cap
		strings.Repeat("secret", 40),   // over the cap
		"pass\x00word",                 // a control character
		"   ",                          // empty after trimming
	}
	for _, secret := range secrets {
		_, err := Label(secret)
		if err == nil {
			t.Errorf("a name that should be refused was accepted, so it guards nothing here")
			continue
		}
		message := err.Error()
		// The whole-value leak, which is the one that matters.
		if trimmed := strings.TrimSpace(secret); trimmed != "" && strings.Contains(message, trimmed) {
			t.Errorf("the error for a rejected name contains the name itself: %q", message)
		}
		// And the substring leak: a message quoting the offending run of characters is the same
		// disclosure arriving one piece at a time.
		for _, word := range []string{"хозяйская", "secretsecret", "password", "pass"} {
			if strings.Contains(secret, word) && strings.Contains(message, word) {
				t.Errorf("the error leaks part of the name (%q): %q", word, message)
			}
		}
	}
}
