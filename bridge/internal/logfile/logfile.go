// Package logfile is a size-bounded log the owner can read without a terminal.
//
// Rotation is in-process rather than delegated to newsyslog. Two reasons, and neither is a
// preference: the bridge has zero third-party dependencies and adding a system-level rotation config
// is another file to install and another thing that can be absent on a fresh machine; and a log that
// grows without limit on the owner's laptop is the failure this bounds. Handing it to newsyslog would
// mean the bridge is only bounded when something outside it was installed correctly.
//
// Nothing written here carries screen content. That rule lives with the caller — see the listener —
// but it is worth restating where the file is opened: this is the artefact that would leak if it
// were ever broken, because a log carrying content would be a transcript of the owner's work and
// their code sitting on disk.
package logfile

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
)

// Writer appends to a file, rotating it once it passes maxBytes and keeping a bounded number of
// older generations.
type Writer struct {
	mu       sync.Mutex
	path     string
	maxBytes int64
	keep     int
	file     *os.File
	size     int64
}

// Open opens (or creates) the log. keep is how many rotated generations to retain, so total disk is
// bounded at roughly maxBytes * (keep + 1).
func Open(path string, maxBytes int64, keep int) (*Writer, error) {
	if maxBytes <= 0 {
		return nil, fmt.Errorf("maxBytes must be positive")
	}
	if keep < 0 {
		return nil, fmt.Errorf("keep must not be negative")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	w := &Writer{path: path, maxBytes: maxBytes, keep: keep}
	if err := w.open(); err != nil {
		return nil, err
	}
	return w, nil
}

// 0600: the log names sessions and verbs. Not a secret, but the owner's business and nobody else's.
func (w *Writer) open() error {
	f, err := os.OpenFile(w.path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	info, err := f.Stat()
	if err != nil {
		f.Close()
		return err
	}
	w.file, w.size = f, info.Size()
	return nil
}

func (w *Writer) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()

	// Rotate BEFORE writing when this record would cross the bound, so a file never exceeds
	// maxBytes by more than one record rather than by a whole write burst.
	if w.size > 0 && w.size+int64(len(p)) > w.maxBytes {
		if err := w.rotate(); err != nil {
			return 0, err
		}
	}
	n, err := w.file.Write(p)
	w.size += int64(n)
	return n, err
}

// rotate shifts the generations down and reopens an empty current file.
func (w *Writer) rotate() error {
	if err := w.file.Close(); err != nil {
		return err
	}
	// Descending, so nothing is overwritten before it has been moved. The oldest generation falls off
	// the end and is removed by the rename onto it.
	for i := w.keep; i >= 1; i-- {
		older := fmt.Sprintf("%s.%d", w.path, i)
		newer := w.path
		if i > 1 {
			newer = fmt.Sprintf("%s.%d", w.path, i-1)
		}
		if i == w.keep {
			// Beyond the last generation there is nowhere to go.
			_ = os.Remove(older)
		}
		if _, err := os.Stat(newer); err == nil {
			if err := os.Rename(newer, older); err != nil {
				return err
			}
		}
	}
	if w.keep == 0 {
		if err := os.Remove(w.path); err != nil && !os.IsNotExist(err) {
			return err
		}
	}
	w.size = 0
	return w.open()
}

func (w *Writer) Close() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.file.Close()
}
