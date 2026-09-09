package main

import (
	"crypto/tls"
	"crypto/x509"
	"errors"
	"io"
	"io/fs"
	"log"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
)

func TestMain(m *testing.M) {
	// The minting path logs, and a test suite that prints "minted a bridge identity" nine times
	// says nothing a reader needs. Discarded here rather than in each test, so a test that starts
	// logging by accident cannot leak into the output either.
	log.SetOutput(io.Discard)
	os.Exit(m.Run())
}

// mustLeaf parses the certificate half of a loaded identity, which is what the phone pins and what
// a matching key has to belong to.
func mustLeaf(t *testing.T, own tls.Certificate) *x509.Certificate {
	t.Helper()
	if len(own.Certificate) == 0 {
		t.Fatal("the identity carries no certificate")
	}
	leaf, err := x509.ParseCertificate(own.Certificate[0])
	if err != nil {
		t.Fatalf("parsing the identity's certificate: %v", err)
	}
	return leaf
}

// **The first start mints a usable pair, and it is usable rather than merely present.**
//
// `tls.X509KeyPair` is what proves the two halves belong together - it compares the public key in
// the certificate against the one derived from the private key - so an identity that loads at all
// is an identity the TLS stack will serve. Asserting the two files exist would not have caught the
// concurrency defect this suite exists for, because there both files existed.
func TestAFirstStartMintsAMatchingPairAt0600(t *testing.T) {
	dir := t.TempDir()

	own, err := identity(dir)
	if err != nil {
		t.Fatalf("first start: %v", err)
	}
	if own.PrivateKey == nil {
		t.Fatal("the minted identity has no private key")
	}
	leaf := mustLeaf(t, own)
	if leaf.Subject.CommonName == "" {
		t.Error("the minted certificate has no common name, so a person reading it cannot tell what it is")
	}

	for _, name := range []string{certFile, keyFile} {
		info, err := os.Stat(filepath.Join(dir, name))
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		// 0600 on the key is the whole of its protection, and on the certificate it costs nothing.
		// Checked with the permission bits alone: the type bits are not ours to assert on.
		if got := info.Mode().Perm(); got != 0o600 {
			t.Errorf("%s is mode %v, want 0600", name, got)
		}
	}
}

// **A second start REUSES the identity rather than minting a new one.**
//
// This is not a tidiness property. Re-minting would produce a certificate no paired phone has ever
// seen, so every phone would stop connecting on the next restart with no message saying why - the
// bridge would look broken and the trust store would look correct.
func TestASecondStartReusesTheIdentity(t *testing.T) {
	dir := t.TempDir()

	first, err := identity(dir)
	if err != nil {
		t.Fatalf("first start: %v", err)
	}
	certBefore, err := os.ReadFile(filepath.Join(dir, certFile))
	if err != nil {
		t.Fatal(err)
	}

	second, err := identity(dir)
	if err != nil {
		t.Fatalf("second start: %v", err)
	}

	if mustLeaf(t, first).Raw == nil || string(mustLeaf(t, second).Raw) != string(mustLeaf(t, first).Raw) {
		t.Error("the second start served a different certificate; every paired phone pins the first one")
	}
	certAfter, err := os.ReadFile(filepath.Join(dir, certFile))
	if err != nil {
		t.Fatal(err)
	}
	if string(certAfter) != string(certBefore) {
		t.Error("the certificate on disk was rewritten by a start that should not have minted")
	}
}

// **Each half-pair direction is refused, and the message says why rather than only that.**
//
// Both directions, because the switch that decides this has an arm per direction and one of them
// could be written to fall through to minting without the other noticing. The message has to name
// the consequence: somebody looking at a state directory with one file in it will otherwise delete
// the survivor, which is exactly the action that unpairs their phone.
func TestAHalfPairIsRefusedInEitherDirection(t *testing.T) {
	// **The two directions get different advice, and the test is what holds them apart.**
	//
	// One shared message used to say, of both, that deleting the survivor would unpair every phone.
	// That is true of a surviving CERTIFICATE and false of a surviving KEY - a mint interrupted
	// between installing the key and installing the certificate leaves a key that nothing ever
	// pinned, and deleting it is precisely the recovery. Since that is the state a crash routinely
	// leaves, the wrong half of the advice was the one the owner was most likely to read.
	//
	// So each case asserts the sentence it must carry AND the sentence it must not, because a
	// message that is merely different is not necessarily right, and the failure that matters here
	// is the two swapping over.
	for _, c := range []struct {
		remove    string
		says      string
		neverSays string
	}{
		{
			remove: keyFile,
			// The certificate survives: it may be the one a phone pinned, and nothing here can tell.
			says:      "unpairs every phone that pinned it",
			neverSays: "unpairs nobody",
		},
		{
			remove: certFile,
			// The key survives: no certificate was ever published, so nothing pinned it.
			says:      "unpairs nobody",
			neverSays: "unpairs every phone",
		},
	} {
		t.Run("without "+c.remove, func(t *testing.T) {
			dir := t.TempDir()
			if _, err := identity(dir); err != nil {
				t.Fatalf("first start: %v", err)
			}
			if err := os.Remove(filepath.Join(dir, c.remove)); err != nil {
				t.Fatal(err)
			}

			_, err := identity(dir)
			if err == nil {
				t.Fatal("a half pair was accepted; the missing half would be minted over the surviving one")
			}
			if !strings.Contains(err.Error(), c.remove) {
				t.Errorf("the refusal does not name the file that is missing: %v", err)
			}
			if !strings.Contains(err.Error(), c.says) {
				t.Errorf("the refusal does not say %q, so it does not tell the owner what deleting "+
					"the survivor costs: %v", c.says, err)
			}
			if strings.Contains(err.Error(), c.neverSays) {
				t.Errorf("the refusal says %q, which is the advice for the OTHER direction: %v", c.neverSays, err)
			}

			// And it did not quietly mint the missing half while refusing.
			if _, err := os.Stat(filepath.Join(dir, c.remove)); !errors.Is(err, fs.ErrNotExist) {
				t.Errorf("%s was recreated by a call that reported failure", c.remove)
			}
		})
	}
}

// **Starts racing on one empty state directory all end up holding the SAME pair, and it matches.**
//
// This is the regression test for a defect that was reproduced 3 times in 40 double-starts. With
// `os.WriteFile` - which is O_CREATE|O_TRUNC and takes no lock - two minters install their two
// files independently, so the loser can overwrite the winner's key after the winner has installed
// its certificate. Both files then exist, so nothing complains, and every later start dies with
// `tls: private key does not match public key` until somebody deletes both and re-pairs.
//
// Goroutines rather than processes: the race is between two open-and-write sequences against one
// filesystem, and which address space they run in makes no difference to it. Running it in-process
// is what lets the assertion be "every caller holds a pair that loads", which is the property that
// matters and the one a subprocess test could only infer.
//
// **The loop count is the test.** A single round passes against the broken version most of the
// time, so a run of one would be a test that reports the absence of a bug it did not look for.
func TestConcurrentFirstStartsAgreeOnOneUsableIdentity(t *testing.T) {
	const rounds = 40
	const starters = 4

	for round := 0; round < rounds; round++ {
		dir := t.TempDir()

		var (
			wg     sync.WaitGroup
			mu     sync.Mutex
			serial []string
			fail   []error
		)
		// Released together, so the starts overlap inside the mint rather than queueing behind each
		// other's completion - which would be a test of nothing at all.
		start := make(chan struct{})
		for i := 0; i < starters; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				<-start
				own, err := identity(dir)
				mu.Lock()
				defer mu.Unlock()
				if err != nil {
					fail = append(fail, err)
					return
				}
				// Serial rather than the raw bytes: it is unique per mint, so two different serials
				// are two different identities however they came to be on disk.
				leaf, err := x509.ParseCertificate(own.Certificate[0])
				if err != nil {
					fail = append(fail, err)
					return
				}
				serial = append(serial, leaf.SerialNumber.String())
			}()
		}
		close(start)
		wg.Wait()

		if len(fail) != 0 {
			t.Fatalf("round %d: %d of %d starts failed: %v", round, len(fail), starters, fail)
		}
		for _, got := range serial {
			if got != serial[0] {
				t.Fatalf("round %d: the starts hold different identities: %v", round, serial)
			}
		}

		// And what is on disk is the pair they agreed on, not one file from each mint. Against the
		// old write the check above fatals first - four independent mints have four serials - so
		// this one is the backstop for the narrower case where the starts DO agree on a certificate
		// and the key underneath it came from somebody else. X509KeyPair, inside loadIdentity, is
		// what compares the certificate's public key with the private key's own.
		own, found, err := loadIdentity(filepath.Join(dir, certFile), filepath.Join(dir, keyFile))
		if err != nil || !found {
			t.Fatalf("round %d: the state directory does not hold a loadable identity: found=%v err=%v", round, found, err)
		}
		leaf, err := x509.ParseCertificate(own.Certificate[0])
		if err != nil {
			t.Fatalf("round %d: %v", round, err)
		}
		if leaf.SerialNumber.String() != serial[0] {
			t.Fatalf("round %d: disk holds a different identity from the one every start returned", round)
		}
	}
}

// **The ready line is a contract, so it is asserted end to end rather than by reading the source.**
//
// The macOS app spawns this process and cannot otherwise tell a working bridge from one macOS froze
// at exec: a quarantined binary returns a pid, never reaches main, writes nothing and never exits.
// The app waits for this line. A change here that stopped printing it, printed it before the listener
// was bound, or reworded it would silently disarm that check on the other side of the repository - so
// this runs the real startup path and waits for the real line, on a real bound port.
func TestTheReadyLineIsPrintedOnceTheListenerIsBound(t *testing.T) {
	// A port the kernel picks and then releases, so this does not fight whatever else is running.
	probe, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("reserving a port: %v", err)
	}
	addr := probe.Addr().String()
	if err := probe.Close(); err != nil {
		t.Fatalf("releasing the port: %v", err)
	}

	said := &safeBuffer{}
	log.SetOutput(said)
	defer log.SetOutput(io.Discard)

	done := make(chan error, 1)
	go func() { done <- run(addr, filepath.Join(t.TempDir(), "absent.sock"), t.TempDir(), "", 0) }()

	deadline := time.Now().Add(20 * time.Second)
	for !strings.Contains(said.String(), readyLine) {
		if time.Now().After(deadline) {
			t.Fatalf("the bridge never printed %q. It said:\n%s", readyLine, said.String())
		}
		select {
		case err := <-done:
			t.Fatalf("the bridge stopped before it was ready: %v\n%s", err, said.String())
		case <-time.After(10 * time.Millisecond):
		}
	}

	// **Bound, not merely announced.** The whole value of this line is that it means the port is
	// answering, so the port is dialled rather than trusted.
	conn, err := net.DialTimeout("tcp", addr, 5*time.Second)
	if err != nil {
		t.Fatalf("the bridge said it was ready and %s does not answer: %v", addr, err)
	}
	_ = conn.Close()

	// The same signal the macOS app sends. `run` installs a handler for it, so this unwinds the
	// bridge rather than killing this test process.
	if err := syscall.Kill(os.Getpid(), syscall.SIGTERM); err != nil {
		t.Fatalf("stopping the bridge: %v", err)
	}
	select {
	case <-done:
	case <-time.After(20 * time.Second):
		t.Fatal("the bridge did not unwind on SIGTERM")
	}
}

// A log sink two goroutines touch: `log` writes from the bridge, the loop above reads.
type safeBuffer struct {
	mu   sync.Mutex
	text strings.Builder
}

func (b *safeBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.text.Write(p)
}

func (b *safeBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.text.String()
}
