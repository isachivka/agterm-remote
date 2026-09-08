package trust_test

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"errors"
	"math/big"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/trust"
)

func TestReplaceKeepsExactlyOnePeer(t *testing.T) {
	dir := t.TempDir()
	s, err := trust.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	first := trust.Peer{Fingerprint: "aa", CertificateDER: []byte{1}, Name: "one", PairedAt: time.Unix(1, 0)}
	second := trust.Peer{Fingerprint: "bb", CertificateDER: []byte{2}, Name: "two", PairedAt: time.Unix(2, 0)}
	if err := s.Replace(first); err != nil {
		t.Fatal(err)
	}
	if err := s.Replace(second); err != nil {
		t.Fatal(err)
	}

	got := s.Peers()
	if len(got) != 1 || got[0].Fingerprint != "bb" {
		t.Fatalf("want only bb, got %+v", got)
	}
}

func TestPeersSurviveReopen(t *testing.T) {
	dir := t.TempDir()
	s, _ := trust.Open(dir)
	_ = s.Replace(trust.Peer{Fingerprint: "aa", CertificateDER: []byte{1}, Name: "one", PairedAt: time.Unix(1, 0)})

	again, err := trust.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(again.Peers()) != 1 {
		t.Fatalf("want 1 peer after reopen, got %d", len(again.Peers()))
	}
}

func TestFileIsOwnerOnly(t *testing.T) {
	dir := t.TempDir()
	s, _ := trust.Open(dir)
	_ = s.Replace(trust.Peer{Fingerprint: "aa", CertificateDER: []byte{1}, PairedAt: time.Unix(1, 0)})

	info, err := os.Stat(filepath.Join(dir, "peers.json"))
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("peers.json must be 0600, got %v", info.Mode().Perm())
	}
}

// A bridge nobody has paired yet has no peers.json, and that is its ordinary first state rather
// than a fault. Open must be able to tell the difference between that and a store it could not
// read, which the corrupt-store test below asserts from the other side.
func TestOpenWithoutAStoreIsNotAnError(t *testing.T) {
	s, err := trust.Open(t.TempDir())
	if err != nil {
		t.Fatalf("opening a never-paired directory: %v", err)
	}
	if got := s.Peers(); len(got) != 0 {
		t.Fatalf("want no peers, got %+v", got)
	}
}

// Open creates the directory it is pointed at, so the bridge's very first pairing does not fail
// because nothing has yet made its configuration directory.
func TestOpenCreatesTheDirectory(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "config", "trust")
	s, err := trust.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.Replace(trust.Peer{Fingerprint: "aa", CertificateDER: []byte{1}}); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, "peers.json")); err != nil {
		t.Fatalf("store not written into a created directory: %v", err)
	}
}

// The file is a list from the first write, so allowing a second phone later is a change to what
// calls Add rather than a change to what reads the file.
func TestTheFileHoldsAList(t *testing.T) {
	dir := t.TempDir()
	s, _ := trust.Open(dir)
	if err := s.Replace(trust.Peer{Fingerprint: "aa", CertificateDER: []byte{1}}); err != nil {
		t.Fatal(err)
	}

	raw, err := os.ReadFile(filepath.Join(dir, "peers.json"))
	if err != nil {
		t.Fatal(err)
	}
	var list []map[string]any
	if err := json.Unmarshal(raw, &list); err != nil {
		t.Fatalf("peers.json is not a JSON list: %v", err)
	}
	if len(list) != 1 {
		t.Fatalf("want a list of 1, got %d", len(list))
	}
}

func TestAddKeepsEveryPeer(t *testing.T) {
	dir := t.TempDir()
	s, _ := trust.Open(dir)
	if err := s.Add(trust.Peer{Fingerprint: "aa", CertificateDER: []byte{1}}); err != nil {
		t.Fatal(err)
	}
	if err := s.Add(trust.Peer{Fingerprint: "bb", CertificateDER: []byte{2}}); err != nil {
		t.Fatal(err)
	}
	if got := s.Peers(); len(got) != 2 {
		t.Fatalf("want 2 peers, got %+v", got)
	}
}

// Re-pairing the same phone is a repair, not a second phone: the entry is replaced in place so the
// name and the pairing time are the current ones and the list does not grow a duplicate.
func TestAddReplacesTheSameFingerprint(t *testing.T) {
	dir := t.TempDir()
	s, _ := trust.Open(dir)
	if err := s.Add(trust.Peer{Fingerprint: "aa", Name: "old", CertificateDER: []byte{1}}); err != nil {
		t.Fatal(err)
	}
	if err := s.Add(trust.Peer{Fingerprint: "aa", Name: "new", CertificateDER: []byte{2}}); err != nil {
		t.Fatal(err)
	}

	got := s.Peers()
	if len(got) != 1 || got[0].Name != "new" {
		t.Fatalf("want one peer named new, got %+v", got)
	}
}

func TestRemoveDropsOnlyThatPeer(t *testing.T) {
	dir := t.TempDir()
	s, _ := trust.Open(dir)
	_ = s.Add(trust.Peer{Fingerprint: "aa", CertificateDER: []byte{1}})
	_ = s.Add(trust.Peer{Fingerprint: "bb", CertificateDER: []byte{2}})

	if err := s.Remove("aa"); err != nil {
		t.Fatal(err)
	}
	got := s.Peers()
	if len(got) != 1 || got[0].Fingerprint != "bb" {
		t.Fatalf("want only bb, got %+v", got)
	}

	again, err := trust.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(again.Peers()) != 1 {
		t.Fatalf("removal did not reach disk: %+v", again.Peers())
	}
}

// Unpairing something that is already unpaired is the state the caller asked for, so it is not an
// error. The owner tapping "Unpair" twice must not see a failure.
func TestRemoveAnUnknownPeerIsNotAnError(t *testing.T) {
	s, _ := trust.Open(t.TempDir())
	if err := s.Remove("nothing"); err != nil {
		t.Fatalf("removing an absent peer: %v", err)
	}
}

// Peers hands out a copy — of the list AND of the certificate bytes in it. The TLS layer holds the
// result of a call across a handshake, and a caller that could reach into the store through either
// could change which phone is trusted without calling a mutator. The DER half is the one that
// matters and the one that was missing: an earlier version of this test asserted only the
// fingerprint, which reads as coverage of the whole struct and is not.
func TestPeersIsACopy(t *testing.T) {
	der := selfSigned(t)
	s, _ := trust.Open(t.TempDir())
	if err := s.Replace(trust.Peer{Fingerprint: "aa", CertificateDER: der}); err != nil {
		t.Fatal(err)
	}

	got := s.Peers()
	got[0].Fingerprint = "tampered"
	got[0].CertificateDER[0] ^= 0xff

	after := s.Peers()
	if after[0].Fingerprint != "aa" {
		t.Fatal("mutating the returned slice changed the store")
	}
	if !bytes.Equal(after[0].CertificateDER, der) {
		t.Fatal("mutating the returned certificate bytes changed the store")
	}
	if len(s.Certificates()) != 1 {
		t.Fatal("mutating the returned certificate bytes changed what the TLS layer is given")
	}
}

// The other direction: the store must not keep the caller's slice. A pairing flow that reuses a
// buffer, or that zeroes what it read, would otherwise be editing the trust store from outside.
func TestTheStoreDoesNotAliasTheCaller(t *testing.T) {
	for _, tc := range []struct {
		name string
		pair func(*trust.Store, trust.Peer) error
	}{
		{"Add", (*trust.Store).Add},
		{"Replace", (*trust.Store).Replace},
	} {
		t.Run(tc.name, func(t *testing.T) {
			der := selfSigned(t)
			caller := bytes.Clone(der)
			s, _ := trust.Open(t.TempDir())
			if err := tc.pair(s, trust.Peer{Fingerprint: "aa", CertificateDER: caller}); err != nil {
				t.Fatal(err)
			}

			caller[0] ^= 0xff
			if !bytes.Equal(s.Peers()[0].CertificateDER, der) {
				t.Fatal("mutating the caller's slice changed the store")
			}
			if len(s.Certificates()) != 1 {
				t.Fatal("mutating the caller's slice changed what the TLS layer is given")
			}
		})
	}
}

// And what Certificates itself returns. A parsed certificate keeps a reference to the DER it was
// parsed from, and that Raw field is what a pinning verifier compares against.
func TestCertificatesDoNotAliasTheStore(t *testing.T) {
	der := selfSigned(t)
	s, _ := trust.Open(t.TempDir())
	if err := s.Replace(trust.Peer{Fingerprint: "aa", CertificateDER: der}); err != nil {
		t.Fatal(err)
	}

	s.Certificates()[0].Raw[0] ^= 0xff
	if len(s.Certificates()) != 1 {
		t.Fatal("mutating a returned certificate changed what the TLS layer is given")
	}
}

// The rule the whole package rests on: what the store returns has been written. A mutator whose
// write failed must leave the store exactly as it was — in memory, in what the TLS layer would
// accept, and on disk — because a caller that was told its pairing failed will act on that, and a
// bridge that trusts a certificate it could not persist is trusting something no restart brings
// back.
//
// The absence of this test is why both halves of that shipped broken: Add left the new peer in
// memory after failing to write it, and Replace dropped the owner's phone from memory while the
// file still held it.
func TestAFailedWriteChangesNothing(t *testing.T) {
	paired := selfSigned(t)
	other := selfSigned(t)
	boom := errors.New("the disk said no")

	for _, tc := range []struct {
		name   string
		mutate func(*trust.Store) error
	}{
		{"Add", func(s *trust.Store) error {
			return s.Add(trust.Peer{Fingerprint: "bb", Name: "second", CertificateDER: other})
		}},
		{"Replace", func(s *trust.Store) error {
			return s.Replace(trust.Peer{Fingerprint: "bb", Name: "second", CertificateDER: other})
		}},
		{"Remove", func(s *trust.Store) error { return s.Remove("aa") }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			s, err := trust.Open(dir)
			if err != nil {
				t.Fatal(err)
			}
			if err := s.Replace(trust.Peer{Fingerprint: "aa", Name: "paired", CertificateDER: paired}); err != nil {
				t.Fatal(err)
			}
			onDisk, err := os.ReadFile(filepath.Join(dir, "peers.json"))
			if err != nil {
				t.Fatal(err)
			}

			trust.FailAllWrites(s, boom)
			if err := tc.mutate(s); !errors.Is(err, boom) {
				t.Fatalf("want the write error back, got %v", err)
			}

			got := s.Peers()
			if len(got) != 1 || got[0].Fingerprint != "aa" || got[0].Name != "paired" {
				t.Fatalf("the store moved after a failed write: %+v", got)
			}
			certs := s.Certificates()
			if len(certs) != 1 || !bytes.Equal(certs[0].Raw, paired) {
				t.Fatalf("the TLS layer would be given %d certificates after a failed write", len(certs))
			}
			again, err := os.ReadFile(filepath.Join(dir, "peers.json"))
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(again, onDisk) {
				t.Fatalf("a failed write reached the file: %s", again)
			}
		})
	}
}

// A store that recovers keeps working, and adopts the list the successful write actually wrote.
func TestAWriteAfterAFailureStillLands(t *testing.T) {
	dir := t.TempDir()
	s, _ := trust.Open(dir)
	if err := s.Replace(trust.Peer{Fingerprint: "aa", CertificateDER: []byte{1}}); err != nil {
		t.Fatal(err)
	}

	trust.FailAllWrites(s, errors.New("the disk said no"))
	_ = s.Replace(trust.Peer{Fingerprint: "bb", CertificateDER: []byte{2}})
	trust.RestoreWrites(s)

	if err := s.Replace(trust.Peer{Fingerprint: "cc", CertificateDER: []byte{3}}); err != nil {
		t.Fatal(err)
	}
	reopened, err := trust.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	got := reopened.Peers()
	if len(got) != 1 || got[0].Fingerprint != "cc" {
		t.Fatalf("want only cc on disk, got %+v", got)
	}
}

// `null` parses without error into a nil slice, which would open as a bridge nobody has paired.
// Nothing here writes it and no truncation produces it, so this is a closed shape rather than a
// closed hole — but a file that plainly says something must not be read as an absent one.
func TestANullStoreIsAnError(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "peers.json"), []byte("null\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := trust.Open(dir); err == nil {
		t.Fatal("want an error for a store holding null, got nil")
	}
}

func TestCertificatesReturnsThePairedCertificate(t *testing.T) {
	der := selfSigned(t)
	s, _ := trust.Open(t.TempDir())
	_ = s.Replace(trust.Peer{Fingerprint: "aa", CertificateDER: der})

	certs := s.Certificates()
	if len(certs) != 1 {
		t.Fatalf("want 1 certificate, got %d", len(certs))
	}
	if string(certs[0].Raw) != string(der) {
		t.Fatal("certificate does not round-trip through the store")
	}
}

// The one rule the TLS layer depends on: nothing that failed to parse is ever handed back as
// something to trust. A stored certificate can stop parsing under a newer Go, and the answer is to
// drop it, not to hand the listener a nil or refuse to start.
func TestCertificatesDropsWhatWillNotParse(t *testing.T) {
	der := selfSigned(t)
	s, _ := trust.Open(t.TempDir())
	_ = s.Add(trust.Peer{Fingerprint: "good", CertificateDER: der})
	_ = s.Add(trust.Peer{Fingerprint: "junk", CertificateDER: []byte{1, 2, 3}})
	_ = s.Add(trust.Peer{Fingerprint: "empty", CertificateDER: nil})

	certs := s.Certificates()
	if len(certs) != 1 {
		t.Fatalf("want only the parseable certificate, got %d", len(certs))
	}
	for _, c := range certs {
		if c == nil {
			t.Fatal("a nil certificate reached the caller")
		}
	}
}

// A file that exists but cannot be read is not the same as no file. Treating it as "never paired"
// would silently unpair the owner's phone and then quietly overwrite the evidence on the next
// write, so it is an error and the file is left alone.
func TestACorruptStoreIsAnError(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "peers.json")
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}

	if _, err := trust.Open(dir); err == nil {
		t.Fatal("want an error for an unreadable store, got nil")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(raw) != "{not json" {
		t.Fatalf("a failed Open rewrote the store: %q", raw)
	}
}

// The write goes to a temporary file in the same directory and is renamed over the store, so a
// crash at any point leaves either the old file or the new one. Two things are asserted here that
// together are what makes that true: no temporary file survives a completed write, and the
// directory holds nothing but peers.json — a temporary file placed anywhere else would make the
// rename a cross-filesystem copy, which is not atomic.
func TestWritingLeavesNothingBehind(t *testing.T) {
	dir := t.TempDir()
	s, _ := trust.Open(dir)
	for i := range 5 {
		if err := s.Replace(trust.Peer{Fingerprint: "aa", CertificateDER: []byte{byte(i)}}); err != nil {
			t.Fatal(err)
		}
	}

	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != "peers.json" {
		names := make([]string, 0, len(entries))
		for _, e := range entries {
			names = append(names, e.Name())
		}
		t.Fatalf("want only peers.json in the directory, got %v", names)
	}
}

// A reader opening the store while it is being rewritten must never see a half-written file. The
// rename is what guarantees it; this is the assertion that the implementation actually uses one.
// Run under -race it also covers the store being read and written from two goroutines, which is
// what a handshake arriving during pairing looks like.
func TestAReaderNeverSeesAPartialFile(t *testing.T) {
	dir := t.TempDir()
	s, err := trust.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	der := selfSigned(t)
	if err := s.Replace(trust.Peer{Fingerprint: "aa", CertificateDER: der}); err != nil {
		t.Fatal(err)
	}

	var wg sync.WaitGroup
	stop := make(chan struct{})

	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := range 200 {
			if err := s.Replace(trust.Peer{Fingerprint: "aa", Name: string(rune('a' + i%26)), CertificateDER: der}); err != nil {
				t.Errorf("write %d: %v", i, err)
				break
			}
		}
		close(stop)
	}()

	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			select {
			case <-stop:
				return
			default:
			}
			reader, err := trust.Open(dir)
			if err != nil {
				t.Errorf("a reader saw an unreadable store: %v", err)
				return
			}
			if got := reader.Peers(); len(got) != 1 {
				t.Errorf("a reader saw %d peers, want 1", len(got))
				return
			}
			// Reading through the store's own accessor is not enough on its own: the mutex could
			// be doing the work. Certificates re-parses what came off disk, so a truncated file
			// would show up as a certificate that no longer parses.
			if len(reader.Certificates()) != 1 {
				t.Error("a reader saw a certificate that no longer parses")
				return
			}
		}
	}()

	wg.Wait()
}

// selfSigned mints a throwaway certificate, so the parsing tests work on real DER rather than on
// bytes that happen to survive.
func selfSigned(t *testing.T) []byte {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "test peer"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return der
}
