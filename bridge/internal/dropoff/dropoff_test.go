package dropoff

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// **The property the whole design rests on: the caller cannot choose where the file goes.**
//
// Every one of these is a name that, if it were joined onto a directory without checking, would put the
// file somewhere the bridge did not choose. They are refused rather than repaired, so there is no
// question of what a "cleaned" version would have been.
func TestANameCannotBecomeAPath(t *testing.T) {
	for _, name := range []string{
		"../escaped",
		"../../etc/passwd",
		"a/b",
		`a\b`,
		"/absolute",
		"..",
		".",
		"sub/../../out",
		"..hidden",
	} {
		if _, err := CleanName(name); err == nil {
			t.Errorf("%q was accepted as a filename; it can leave the directory the bridge chose", name)
		}
	}
}

// The control: ordinary filenames must go through untouched, or the test above passes because
// everything is refused.
func TestOrdinaryNamesAreAcceptedUnchanged(t *testing.T) {
	for _, name := range []string{
		"notes.md",
		"IMG_20260730_0042.jpg",
		"docker-compose.yml",
		"έκθεση.txt",
		"日本語.pdf",
		"a file with spaces.log",
		"v1.2.3-rc1.patch",
	} {
		got, err := CleanName(name)
		if err != nil {
			t.Errorf("%q was refused: %v", name, err)
		}
		if got != name {
			t.Errorf("%q came back as %q; names are refused, never rewritten", name, got)
		}
	}
}

func TestNamesThatWouldReadAsFlagsOrHideAreRefused(t *testing.T) {
	for _, name := range []string{"-rf", "--force", ".bashrc", ".ssh"} {
		if _, err := CleanName(name); err == nil {
			t.Errorf("%q was accepted", name)
		}
	}
}

func TestControlCharactersAndBadEncodingInNamesAreRefused(t *testing.T) {
	for _, name := range []string{"a\nb.txt", "a\x00b", "a\x1b[2Jb", "a\x9bb", "bad\xff\xfe"} {
		if _, err := CleanName(name); err == nil {
			t.Errorf("%q was accepted as a filename", name)
		}
	}
}

func TestAnOverlongNameIsRefused(t *testing.T) {
	if _, err := CleanName(strings.Repeat("a", MaxNameBytes+1)); err == nil {
		t.Error("an unbounded filename was accepted")
	}
	if _, err := CleanName(strings.Repeat("a", MaxNameBytes)); err != nil {
		t.Errorf("a name exactly at the limit was refused: %v", err)
	}
}

// The path is the bridge's, and the name is the only part the caller contributed.
func TestSaveWritesUnderARandomDirectoryTheCallerDidNotChoose(t *testing.T) {
	root := t.TempDir()

	path, err := Save(root, "notes.md", []byte("hello"))
	if err != nil {
		t.Fatal(err)
	}

	if filepath.Dir(filepath.Dir(path)) != root {
		t.Errorf("wrote to %q, which is not one level under %q", path, root)
	}
	if filepath.Base(path) != "notes.md" {
		t.Errorf("basename is %q, want notes.md", filepath.Base(path))
	}
	body, err := os.ReadFile(path)
	if err != nil || string(body) != "hello" {
		t.Errorf("content came back as %q, %v", body, err)
	}
}

// **Nothing is ever overwritten, and that is the owner's ruling rather than only hygiene.**
//
// They ruled "/tmp is fine, don't delete anything", so nothing cleans up — which means a reused
// filename would silently destroy the previous send. Two files of the same name must coexist.
func TestTwoSendsOfTheSameNameBothSurvive(t *testing.T) {
	root := t.TempDir()

	first, err := Save(root, "same.txt", []byte("first"))
	if err != nil {
		t.Fatal(err)
	}
	second, err := Save(root, "same.txt", []byte("second"))
	if err != nil {
		t.Fatal(err)
	}

	if first == second {
		t.Fatal("both sends chose the same path, so the first file was overwritten")
	}
	a, _ := os.ReadFile(first)
	b, _ := os.ReadFile(second)
	if string(a) != "first" || string(b) != "second" {
		t.Errorf("contents are %q and %q; a send destroyed the other", a, b)
	}
}

// The directory holding the file must not be readable by everything else on the machine, because /tmp
// is world-readable and world-writable.
func TestTheFileAndItsDirectoryAreTheOwnersOnly(t *testing.T) {
	root := t.TempDir()

	path, err := Save(root, "private.txt", []byte("x"))
	if err != nil {
		t.Fatal(err)
	}

	for _, c := range []struct {
		what string
		p    string
		want os.FileMode
	}{
		{"the file", path, filePerm},
		{"its directory", filepath.Dir(path), dirPerm},
		{"the root", root, dirPerm},
	} {
		info, err := os.Stat(c.p)
		if err != nil {
			t.Fatal(err)
		}
		if got := info.Mode().Perm(); got != c.want {
			t.Errorf("%s is %04o, want %04o — /tmp is world-readable, so this is what keeps it private",
				c.what, got, c.want)
		}
	}
}

// **The refusal has to teach the bound**, or the owner shrinks the file and guesses again.
func TestAFileOverTheLimitIsRefusedAndTheMessageNamesBothSizes(t *testing.T) {
	root := t.TempDir()

	_, err := Save(root, "big.bin", make([]byte, MaxFileBytes+(2<<20)))

	if !errors.Is(err, ErrTooLarge) {
		t.Fatalf("a file over the bound gave %v, want ErrTooLarge", err)
	}
	msg := err.Error()
	if !strings.Contains(msg, "18.0 MB") {
		t.Errorf("the message does not say how big the file was: %q", msg)
	}
	if !strings.Contains(msg, "16.0 MB") {
		t.Errorf("the message does not say what the limit is: %q", msg)
	}
	// And nothing was left behind by the attempt.
	entries, _ := os.ReadDir(root)
	if len(entries) != 0 {
		t.Errorf("a refused send left %d entries under the root", len(entries))
	}
}

// The bound has to admit the case the feature exists for: a photograph off a modern phone.
func TestAPhoneSizedPhotographIsAccepted(t *testing.T) {
	root := t.TempDir()

	for _, mb := range []int{5, 10, 15} {
		if _, err := Save(root, "IMG_0001.jpg", make([]byte, mb<<20)); err != nil {
			t.Errorf("a %d MB photograph was refused: %v — this is the obvious thing to pick", mb, err)
		}
	}
}

func TestMegabytesReadsTheWayAPersonThinks(t *testing.T) {
	for _, c := range []struct {
		n    int64
		want string
	}{
		{512, "0 KB"},
		{200 << 10, "200 KB"},
		{16 << 20, "16.0 MB"},
		{(16 << 20) + (2 << 20), "18.0 MB"},
	} {
		if got := megabytes(c.n); got != c.want {
			t.Errorf("%d bytes rendered as %q, want %q", c.n, got, c.want)
		}
	}
}

// **Past the ceiling, sending is REFUSED and nothing is deleted to make room.**
//
// Deleting would be exactly the tidying the owner ruled against, and it would be destroying their data
// to make space for their data.
func TestPastTheCeilingSendingIsRefusedRatherThanMakingRoom(t *testing.T) {
	root := t.TempDir()
	// One file just under the ceiling, written the way Save would.
	dir := filepath.Join(root, "existing")
	if err := os.MkdirAll(dir, dirPerm); err != nil {
		t.Fatal(err)
	}
	existing := filepath.Join(dir, "already-here.bin")
	if err := os.WriteFile(existing, make([]byte, MaxRootBytes-16), filePerm); err != nil {
		t.Skipf("cannot stage a %d byte file here: %v", MaxRootBytes, err)
	}

	_, err := Save(root, "next.txt", []byte("thirty-two bytes or so of content"))

	if !errors.Is(err, ErrRootFull) {
		t.Errorf("got %v, want ErrRootFull", err)
	}
	if _, err := os.Stat(existing); err != nil {
		t.Error("the earlier file was removed to make room; nothing here may delete anything")
	}
}

// A name that would escape must be refused by Save itself, not only by CleanName - the check has to be
// on the path that actually writes.
func TestSaveRefusesAnEscapingNameRatherThanRelyingOnItsCaller(t *testing.T) {
	root := t.TempDir()
	outside := filepath.Join(filepath.Dir(root), "escaped.txt")

	if _, err := Save(root, "../escaped.txt", []byte("x")); err == nil {
		t.Fatal("Save accepted a traversing name")
	}
	if _, err := os.Stat(outside); err == nil {
		t.Fatal("a file was written outside the root")
	}
}
