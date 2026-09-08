// Package dropoff writes a file the phone sent into a directory the BRIDGE chooses.
//
// # The third kind of write, and the largest
//
// This bridge started able to do nothing but read. Then `window.resize` let it change the SHAPE of a
// window. Then `session.type` let it put keystrokes on a pty. This writes data to the owner's
// filesystem.
//
// **The cost, stated rather than left to be inferred: whoever gets past pairing can put bytes on the
// owner's disk.** It is a smaller escalation than the one already granted — typing can run anything at
// all, and a shell can write any file it likes — but it is a different KIND, and the next person
// reading this should have it in front of them rather than have to work it out.
//
// # The one property everything else rests on
//
// **The caller never names a path.** It sends a basename and content; this package decides where the
// file goes, and the answer is always `<root>/<random>/<name>`. A caller that can name a path can name
// any path, and no amount of checking a caller-supplied path is as good as not having one — that is the
// same reasoning as the agterm command allowlist and the closed set of typing keys.
//
// The basename is still checked, because it becomes a filename: separators, `..`, control characters,
// anything that reads as a command flag, and anything over the length bound are REFUSED. Refused, not
// rewritten — the same rule as `keys.Text`, which will not strip a control character out of the owner's
// typing and hand back something they did not write. A file that arrives under a name they did not
// choose is the same defect wearing different clothes.
//
// # Nothing here logs the content
//
// Not the bytes, not a prefix, not a length-and-hash. The owner's files are their content, under the
// same standing rule as their screen and their keystrokes. The only thing this package returns is the
// path it chose, which is what the phone puts in the draft.
package dropoff

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"unicode/utf8"
)

const (
	// MaxNameBytes bounds the basename. Long enough for any real filename and short enough that the
	// path cannot be used to fill a directory entry.
	MaxNameBytes = 120

	// MaxFileBytes bounds one file.
	//
	// **Sixteen megabytes, and the number is sized to the obvious case rather than to comfort.** The
	// thing anyone reaches for with a file picker on a phone is a photograph, and a Pixel photograph is
	// routinely five to ten megabytes with a modern one exceeding that. This was four, which meant the
	// first thing the owner would try was refused — and **a bounded feature that refuses the obvious
	// case is indistinguishable from a broken one.**
	//
	// The memory argument for a smaller number does not survive contact with the scale: 16 MB
	// base64-buffers to about 21, which is nothing on this laptop. And the caller is authenticated, so
	// it can already run arbitrary commands through `session.type` — a paired caller exhausting memory
	// is not the threat that matters here. If it ever is, the answer is streaming the body rather than a
	// smaller ceiling.
	MaxFileBytes = 16 << 20

	// MaxRootBytes is the ceiling on everything this package has written and had nothing clean up.
	//
	// **An undeclared lifetime is how a temp directory becomes an archive of somebody's documents.** The
	// lifetime here IS declared and it is the owner's — nothing deletes, see [Root] — so the only bound
	// on how much of their data accumulates is how many times they press the button. This is that bound.
	//
	// Past it, a send is REFUSED and the message names the directory. It does not make room by deleting
	// something, which would be exactly the tidying the ruling forbids.
	MaxRootBytes = 256 << 20

	// dirPerm and filePerm: the owner only. /tmp is world-readable and world-writable with the sticky
	// bit, so anything left at its top level is readable by every process on the machine. The random
	// subdirectory is 0700, which is what actually keeps the file private.
	dirPerm  fs.FileMode = 0o700
	filePerm fs.FileMode = 0o600
)

// Root is where files land.
//
// # Who deletes these files: nobody here, and that is the owner's ruling
//
//	"/tmp is fine, don't delete anything"  — the owner, 2026-07-30
//
// **So nothing in this codebase deletes a sent file.** Not on disconnect, not on a timer, not on the
// next send, and not as tidying. macOS clears `/tmp` — `/etc/periodic/daily/110.clean-tmps` removes
// entries not accessed for three days, and a restart clears it outright — or the owner does it
// themselves.
//
// This is written down because it is a decision and not an omission. The next person who feels tidy
// here should meet the ruling rather than a gap they will fill in with a cleanup they think is obviously
// correct: deleting on disconnect takes the file away while the owner is still working with it, and they
// asked for the path precisely so they could use it.
//
// It also settles something that would otherwise look like a nicety. **Each send gets its own path**,
// which the random component below provides — and now for two reasons rather than one. The security
// reason is that a predictable directory in a world-writable `/tmp` can be created in advance by another
// process. The plainer reason is that nothing cleans up, so a fixed filename would mean every send
// silently overwrote the last one, which is a way of deleting the owner's file while believing you are
// being tidy.
//
// [MaxRootBytes] is the only bound, and it refuses new writes rather than removing old ones.
const Root = "/tmp/agterm-remote"

// ErrTooLarge is a file over [MaxFileBytes]. Its own error so the API layer can say which bound.
var ErrTooLarge = errors.New("file is too large")

// ErrRootFull means [MaxRootBytes] is already used. The remedy is the owner's: clear the directory.
var ErrRootFull = fmt.Errorf("the drop-off directory is full; clear %s", Root)

// CleanName checks a basename and returns it unchanged, or explains why it will not do.
//
// **Every arm refuses. None rewrites.** A sanitiser that "fixes" a name is a sanitiser whose output the
// caller cannot predict, and the owner ends up with a file called something they did not choose sitting
// in a path they were told to type. Being told no is recoverable; being handed the wrong thing quietly
// is not.
func CleanName(name string) (string, error) {
	switch {
	case name == "":
		return "", errors.New("the file has no name")
	case len(name) > MaxNameBytes:
		return "", fmt.Errorf("the name is %d bytes; the limit is %d", len(name), MaxNameBytes)
	case !utf8.ValidString(name):
		return "", errors.New("the name is not valid UTF-8")
	}

	// Separators first: this is the arm that stops a name from being a path.
	if strings.ContainsAny(name, `/\`) {
		return "", errors.New("the name contains a path separator")
	}
	// `..` is refused whole rather than only as an exact match, because `..foo` is a legal filename but
	// a name that contains a traversal is a name somebody is trying something with.
	if strings.Contains(name, "..") {
		return "", errors.New("the name contains ..")
	}
	if name == "." {
		return "", errors.New("the name is a directory reference")
	}
	// A leading dash reads as a flag to every command the owner might type this path into. The path
	// this package builds is absolute so it cannot actually be mistaken for one — this refuses anyway,
	// because the whole value of the feature is that the owner can paste the path without thinking, and
	// a name that needs thinking about defeats it.
	if strings.HasPrefix(name, "-") {
		return "", errors.New("the name starts with a dash, which reads as a command flag")
	}
	// A leading dot hides the file from the ls the owner will run to find it.
	if strings.HasPrefix(name, ".") {
		return "", errors.New("the name starts with a dot")
	}
	for _, r := range name {
		if r < 0x20 || r == 0x7f || (r >= 0x80 && r <= 0x9f) {
			return "", fmt.Errorf("the name contains a control character (%#U)", r)
		}
	}
	return name, nil
}

// Save writes content under a fresh random directory and returns the full path.
//
// `root` is a parameter rather than [Root] directly so this is testable in a temp directory; the bridge
// passes [Root] and nothing else does.
func Save(root, name string, content []byte) (string, error) {
	clean, err := CleanName(name)
	if err != nil {
		return "", err
	}
	if len(content) > MaxFileBytes {
		// Both numbers, in units a person reads. The owner should learn the bound from the one message
		// they get rather than shrink the file and guess again.
		return "", fmt.Errorf("%w: it is %s and the limit is %s",
			ErrTooLarge, megabytes(int64(len(content))), megabytes(MaxFileBytes))
	}

	used, err := rootSize(root)
	if err != nil {
		return "", err
	}
	if used+int64(len(content)) > MaxRootBytes {
		return "", ErrRootFull
	}

	// A fresh directory per send, never a reused name. Two reasons, and the second is the owner's
	// ruling rather than a security property: nothing in this codebase deletes anything, so a fixed
	// filename would mean each send quietly overwrote the last - deleting their file in the belief that
	// it was tidying. See [Root].
	//
	// crypto/rand rather than math/rand: this is a directory name in a world-writable /tmp, so a
	// predictable one could be created in advance by another process and have its permissions chosen by
	// somebody else.
	var raw [8]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", err
	}
	dir := filepath.Join(root, hex.EncodeToString(raw[:]))
	if err := os.MkdirAll(dir, dirPerm); err != nil {
		return "", err
	}
	// MkdirAll leaves an existing directory's permissions alone, so root may predate this call with
	// looser bits. Set them explicitly rather than trusting the create.
	if err := os.Chmod(root, dirPerm); err != nil {
		return "", err
	}

	path := filepath.Join(dir, clean)
	// O_EXCL so this can never write through an existing entry - the random directory makes that
	// essentially impossible, and "essentially" is not the same as "cannot".
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, filePerm)
	if err != nil {
		return "", err
	}
	if _, err := f.Write(content); err != nil {
		f.Close()
		return "", err
	}
	if err := f.Close(); err != nil {
		return "", err
	}
	return path, nil
}

// megabytes renders a size the way the owner thinks about one.
//
// One decimal place, because "16.0 MB against a 16 MB limit" reads as a rounding argument and
// "16.4 MB" reads as a fact. Bytes below a megabyte are shown as kilobytes rather than as "0.0 MB".
func megabytes(n int64) string {
	const mb = 1 << 20
	if n < mb {
		return fmt.Sprintf("%d KB", n/1024)
	}
	return fmt.Sprintf("%.1f MB", float64(n)/mb)
}

// rootSize totals what is already there. A missing root is zero rather than an error.
func rootSize(root string) (int64, error) {
	var total int64
	err := filepath.WalkDir(root, func(_ string, d fs.DirEntry, err error) error {
		if err != nil {
			// An unreadable entry is skipped rather than fatal: this is a total for a ceiling, and
			// refusing the feature because one file cannot be stat'd would be the wrong trade.
			return nil
		}
		if d.IsDir() {
			return nil
		}
		if info, err := d.Info(); err == nil {
			total += info.Size()
		}
		return nil
	})
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		return 0, err
	}
	return total, nil
}
