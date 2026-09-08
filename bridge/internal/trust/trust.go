// Package trust is the list of phones this bridge will talk to, kept on disk.
//
// # Why a list, when the answer is one phone
//
// The bridge accepts exactly one paired phone, and Replace is what enforces it: pairing a second
// phone drops the first. The obvious shape for that is a single record — one certificate, one file,
// no iteration anywhere. The project this descends from did exactly that, and pinned one peer read
// from a certificate path.
//
// It is stored as a list anyway. Allowing a second phone is a plausible next step, and the whole
// cost of it should be a screen that lets the owner say so plus a call to Add instead of Replace.
// If the file held one record, that same step would additionally have to change the file's shape,
// migrate every store already written, and teach an old bridge to read a new file and the other way
// round — a data migration in a security-critical file, arriving with a user-interface change that
// has nothing to do with it. A list that happens to have one element costs a loop today and buys
// that away entirely.
//
// So the on-disk format is a JSON array from the first write, and the one-phone rule lives in which
// method the pairing code calls, where it can be changed by deleting a line.
//
// # A missing file and an unreadable one are different
//
// No peers.json means a bridge nobody has paired yet, which is its ordinary first state: Open
// returns an empty store and no error. A peers.json that will not parse is a fault, and Open
// returns the error rather than an empty store. Collapsing the two would silently unpair the
// owner's phone and then overwrite the only evidence of why on the next write — the failure would
// present as "my phone stopped connecting" with nothing left to look at. A store that refuses to
// open is recoverable; one that quietly empties itself is not.
//
// # Certificates never hands back something it could not parse
//
// Certificates is what the TLS layer asks which client certificates to accept, so every element of
// its result is dereferenced during a handshake. A stored certificate can stop parsing — a Go
// upgrade tightening what x509 accepts is the realistic way — and the answers to that are: drop it,
// refuse to serve, or hand the listener a nil. Dropping it is the only one that leaves the bridge
// running for whoever is still valid, and a dropped certificate cannot authenticate anyone, so
// nothing is loosened by it.
//
// The count of what was dropped is logged; the identity is not. A fingerprint identifies the
// owner's phone, and this log is a file on a laptop that can be read by anything that can read it,
// so the number is what a person needs to know something is wrong and the identity adds nothing to
// that.
package trust

import (
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// fileName is the store, and Open takes the directory rather than this path so that a caller cannot
// point the bridge at a file under a name that means something else.
const fileName = "peers.json"

// Peer is one paired phone.
//
// CertificateDER is the certificate itself, in DER, and it is the thing that actually authenticates
// the phone. Fingerprint is a label over it: it is what the owner compares on two screens while
// pairing and what Remove keys on, and it is never what a handshake is decided by. Storing the
// certificate whole rather than only its digest is deliberate — a digest could only ever answer
// "is this the same", and the TLS layer needs the certificate to answer with.
//
// Name and PairedAt exist for the owner, not for the protocol. When there is more than one entry in
// the list, "which of these do I unpair" is unanswerable from a fingerprint alone.
type Peer struct {
	Fingerprint    string    `json:"fingerprint"`
	CertificateDER []byte    `json:"certificate_der"`
	Name           string    `json:"name,omitempty"`
	PairedAt       time.Time `json:"paired_at"`
}

// Store is the peer list and the file behind it. The in-memory copy is authoritative for reads and
// every write reaches disk before it returns, so a caller never has to ask whether what it just
// wrote survived.
//
// The mutex is not decoration. Certificates is called from the accept path, which is concurrent by
// nature, while Replace runs from pairing — a handshake arriving while the owner is pairing is
// ordinary rather than exotic.
type Store struct {
	dir string

	mu    sync.Mutex
	peers []Peer
}

// Open reads the store in dir, creating the directory if the bridge has never written there.
//
// Creating it matters because the first thing that ever writes here is pairing, and pairing failing
// on a missing directory would be a first-run failure in the one flow that has no fallback.
func Open(dir string) (*Store, error) {
	// 0700: the directory holds the answer to "which phone may drive this Mac". Nothing but the
	// owner has any business listing it, never mind reading it.
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("trust directory: %w", err)
	}

	s := &Store{dir: dir}
	raw, err := os.ReadFile(s.path())
	if errors.Is(err, fs.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, fmt.Errorf("read trust store: %w", err)
	}
	if err := json.Unmarshal(raw, &s.peers); err != nil {
		return nil, fmt.Errorf("parse trust store: %w", err)
	}
	return s, nil
}

func (s *Store) path() string { return filepath.Join(s.dir, fileName) }

// Peers is the paired phones, in the order they were added.
//
// The slice is a copy: the caller holds this across a handshake, and a caller that could reach into
// the store's own slice could unpair a phone by writing to it. The certificate bytes inside are
// shared and are treated as immutable by everything here — a certificate is not edited, it is
// replaced.
func (s *Store) Peers() []Peer {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Peer, len(s.peers))
	copy(out, s.peers)
	return out
}

// Add pairs a phone, keeping the ones already there.
//
// An entry with the same fingerprint is replaced in place rather than appended. Re-pairing a phone
// that is already paired is a repair — the certificate was re-minted, the app was reinstalled — and
// it must leave one entry carrying the current name and time, not two entries where the stale one
// still authenticates.
//
// Nothing here validates the certificate. The store keeps what it was given and Certificates
// decides what parses, which is what keeps a store openable when a certificate in it stops being
// acceptable to x509.
func (s *Store) Add(p Peer) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.peers {
		if s.peers[i].Fingerprint == p.Fingerprint {
			s.peers[i] = p
			return s.save()
		}
	}
	s.peers = append(s.peers, p)
	return s.save()
}

// Replace pairs a phone and drops every other, which is the bridge's behaviour today: one phone.
//
// This is where the rule lives, and it is one method rather than a property of the file, so
// allowing a second phone is a caller calling Add instead. See the note at the top of the package.
func (s *Store) Replace(p Peer) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.peers = []Peer{p}
	return s.save()
}

// Remove unpairs the phone with this fingerprint.
//
// A fingerprint that is not in the list is not an error: the caller asked for it to be gone and it
// is gone. The owner tapping "Unpair" on a phone that another window already unpaired should see
// nothing happen, not a failure. Nothing is written when nothing changed.
func (s *Store) Remove(fingerprint string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := make([]Peer, 0, len(s.peers))
	for _, p := range s.peers {
		if p.Fingerprint != fingerprint {
			kept = append(kept, p)
		}
	}
	if len(kept) == len(s.peers) {
		return nil
	}
	s.peers = kept
	return s.save()
}

// Certificates is the set of client certificates the TLS layer will accept.
//
// Anything that no longer parses is dropped, and the count of what was dropped is logged without
// the identity. See the note at the top of the package for why that is the right answer rather than
// refusing to serve.
func (s *Store) Certificates() []*x509.Certificate {
	s.mu.Lock()
	defer s.mu.Unlock()

	out := make([]*x509.Certificate, 0, len(s.peers))
	dropped := 0
	for _, p := range s.peers {
		cert, err := x509.ParseCertificate(p.CertificateDER)
		if err != nil {
			dropped++
			continue
		}
		out = append(out, cert)
	}
	if dropped > 0 {
		log.Printf("trust: %d of %d stored certificates no longer parse and were ignored", dropped, len(s.peers))
	}
	return out
}

// save writes the list to a temporary file in the same directory and renames it over the store.
//
// The rename is the whole point. A rename within one directory is atomic, so a crash at any instant
// leaves either the file that was there before or the complete new one, and never a half-written
// trust store — which would present as a bridge that will not open its own store and an owner who
// has to re-pair. Writing in place, by contrast, has a window in which the file is truncated and
// the phone's certificate is simply not there.
//
// "In the same directory" is load-bearing twice over: a rename across filesystems is not a rename
// at all but a copy, and a temporary file in a shared location would put the owner's peer list
// somewhere other than the directory whose permissions were chosen for it.
//
// A crash between creating the temporary file and renaming it leaves that file behind, and nothing
// here sweeps it up. It is litter rather than a hazard — nothing ever reads a file by that name,
// and it is written at 0600 like the store itself. A sweep on Open was considered and left out: it
// would have to guess whether a temporary file belongs to a crashed run or to one happening right
// now, and deleting the second is a worse bug than leaving the first.
//
// Callers hold s.mu.
func (s *Store) save() error {
	// Never `null`. A nil slice marshals to null, which is not the empty list and would make an
	// unpaired store read differently from a never-paired one.
	peers := s.peers
	if peers == nil {
		peers = []Peer{}
	}
	data, err := json.MarshalIndent(peers, "", "  ")
	if err != nil {
		return fmt.Errorf("encode trust store: %w", err)
	}
	data = append(data, '\n')

	tmp, err := os.CreateTemp(s.dir, fileName+".tmp-*")
	if err != nil {
		return fmt.Errorf("create temporary trust store: %w", err)
	}
	// Removed on every path that does not end in a successful rename, so a failed write does not
	// leave a partial file sitting next to the real one. After the rename the name is gone and this
	// does nothing.
	defer func() { _ = os.Remove(tmp.Name()) }()

	// CreateTemp already creates at 0600, but that is subject to the process umask, and an
	// unusual umask must not be able to produce a trust store the owner cannot read. Set it
	// explicitly, before any content is written, so the file is never briefly readable with
	// content in it.
	if err := tmp.Chmod(0o600); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("secure temporary trust store: %w", err)
	}
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("write temporary trust store: %w", err)
	}
	// Before the rename, not after: a rename that lands ahead of the data it points at is exactly
	// the corruption the rename was chosen to prevent.
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("flush temporary trust store: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("close temporary trust store: %w", err)
	}
	if err := os.Rename(tmp.Name(), s.path()); err != nil {
		return fmt.Errorf("install trust store: %w", err)
	}

	// The directory entry itself. Without this the rename can still be lost to a power failure
	// after save returned, and a pairing the owner watched succeed would not be there on reboot.
	// Best effort: some filesystems refuse to sync a directory, and that is not a reason to report
	// a pairing as failed when the file is in place.
	if dir, err := os.Open(s.dir); err == nil {
		_ = dir.Sync()
		_ = dir.Close()
	}
	return nil
}
