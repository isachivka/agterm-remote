// Command agterm-remote-bridge serves the phone's verbs over pinned mutual TLS.
//
// It has no pty of its own. Every session it can name belongs to agterm, and this process reaches
// them the same way a person's own terminal does: over agterm's control socket, one command at a
// time, from a closed set it constructs itself. Nothing a caller sends becomes an agterm command.
//
// # It is a child process, not a service
//
// This binary is started by the menu-bar app that owns the Mac side, and it is meant to die with
// it. There is no launchd job, no plist and no installer: the app knows where its own state
// directory is and passes it, along with the address it decided to listen on. That is why there are
// flags here and no configuration file - the parent already holds every one of these answers, and a
// file would be a second copy of them that can disagree.
//
// Dying with the parent is not left to the operating system, which does not do it: a child is
// re-parented and keeps running. --parent-pid names the process to follow and internal/parent polls
// it, so a crashed app cannot leave a listener on the owner's exposed port with nothing left that
// could close it.
//
// # No default address, ever
//
// --listen has no default and the program refuses to start without it. The address a phone dials
// belongs to the person running the bridge; a default is a value that ends up committed, then
// documented, then depended on. The same goes for --state-dir: the identity and the paired phones
// are the owner's, and guessing a path for them is how a bridge ends up trusting a directory nobody
// meant it to read.
package main

import (
	"context"
	"crypto/tls"
	"errors"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
	"github.com/isachivka/agterm-remote/bridge/internal/api"
	"github.com/isachivka/agterm-remote/bridge/internal/control"
	"github.com/isachivka/agterm-remote/bridge/internal/frontdoor"
	"github.com/isachivka/agterm-remote/bridge/internal/listener"
	"github.com/isachivka/agterm-remote/bridge/internal/logfile"
	"github.com/isachivka/agterm-remote/bridge/internal/parent"
	"github.com/isachivka/agterm-remote/bridge/internal/pinning"
	"github.com/isachivka/agterm-remote/bridge/internal/trust"
)

// Bounds on the log. Roughly 4 MB of disk in total, which is generous for one line per request and
// one line per window of failed handshakes, and is a ceiling rather than a hope.
const (
	logMaxBytes = 1 << 20
	logKeep     = 3
)

// The bridge's own certificate and key, inside the state directory.
const (
	certFile = "bridge-cert.pem"
	keyFile  = "bridge-key.pem"
)

// identityLifetime is how long a minted bridge identity is valid for, and it is deliberately longer
// than the machine it runs on.
//
// An expiry on a pinned self-signed certificate buys an attacker nothing - there is no issuer whose
// compromise it limits and no revocation it stands in for - and it guarantees a day the owner's
// phone stops working, most likely while they are away from the only machine that could fix it.
const identityLifetime = 20 * 365 * 24 * time.Hour

// How long a process that LOST the minting race waits for the winner to finish writing the pair.
//
// Two starts against the same empty state directory both see no identity and both mint one. Only
// one of them may install it, and the other has to end up holding the winner's certificate rather
// than its own - so it waits for a file it can see is being written. The budget is generous because
// the cost of being wrong is asymmetric: waiting two seconds on a first start is nothing, and
// giving up early means starting with a key that does not match the certificate the phone pinned.
const (
	identityWaitBudget = 2 * time.Second
	identityWaitStep   = 10 * time.Millisecond
)

func main() {
	listen := flag.String("listen", "", "host:port to listen on (required)")
	socket := flag.String("socket", "", "agterm control socket; empty means the default")
	stateDir := flag.String("state-dir", "", "directory holding identity and paired peers (required)")
	logPath := flag.String("log", "", "log file; empty means stderr")
	parentPID := flag.Int("parent-pid", 0, "exit when this pid goes away; 0 disables")
	flag.Parse()

	if *listen == "" || *stateDir == "" {
		fmt.Fprintln(os.Stderr, "--listen and --state-dir are required")
		os.Exit(2)
	}
	// A negative pid is not a process this or any other program can wait on, so it is a typo rather
	// than an instruction. Refused here rather than logged verbatim: a flag that accepts a value it
	// can never act on is a flag that reports success for a mistake.
	if *parentPID < 0 {
		fmt.Fprintln(os.Stderr, "--parent-pid must be a pid, or 0 to disable")
		os.Exit(2)
	}
	if err := run(*listen, *socket, *stateDir, *logPath, *parentPID); err != nil {
		log.Fatalf("agterm-remote-bridge: %v", err)
	}
}

func run(listenAddr, socketPath, stateDir, logPath string, parentPID int) error {
	// The log first, so that everything below reports where the owner will look for it rather than
	// on a stderr the parent may not be keeping.
	if logPath != "" {
		lf, err := logfile.Open(logPath, logMaxBytes, logKeep)
		if err != nil {
			return fmt.Errorf("log: %w", err)
		}
		defer lf.Close()
		log.SetOutput(lf)
	}

	// The peer list, which may be empty. **An unpaired bridge starts and listens.**
	//
	// The alternative - refusing to start until a phone is paired - would make enrolment impossible
	// to reach, since enrolment happens over this listener. An empty list accepts nobody, so the
	// door is shut in the only sense that matters; it is not also locked against the person holding
	// the key.
	//
	// **What the phone list is read into is a snapshot, and it is taken here.** ServerConfig is
	// built once, below, from the certificates this store holds at THIS moment, and the tls.Config
	// keeps that closure for the life of the process. So a phone that enrols while the bridge is
	// running is written to disk and is not accepted until the bridge is restarted. That is a fact
	// about this wiring rather than about the store, it is written down because a reader will
	// otherwise assume the listener re-reads, and whatever adds enrolment has to close it - by
	// rebuilding the config, or by giving the verifier the store instead of a slice.
	peers, err := trust.Open(stateDir)
	if err != nil {
		return err
	}

	// The state directory's mode is part of what authenticates the LOCAL control socket, so it is
	// enforced rather than assumed.
	//
	// trust.Open creates the directory 0700, but MkdirAll applies a mode only when it CREATES - a
	// directory the parent made at 0755, or one an older run left behind, keeps whatever it has.
	// This used to be asserted in a comment two lines further down and was simply not true in that
	// case, which left peers.json and the resize cache readable by anyone on the machine.
	//
	// Not fatal if the chmod fails: the key is written 0600 and the control socket is chmod'ed 0600
	// in its own package, so this is the outer of two layers rather than the only one.
	if err := os.Chmod(stateDir, 0o700); err != nil {
		log.Printf("could not set the state directory to 0700: %v", err)
	}

	own, err := identity(stateDir)
	if err != nil {
		return err
	}

	if socketPath == "" {
		socketPath = agterm.DefaultSocketPath()
	}
	// The resize cache lives in the state directory, whose mode was set above. It holds a
	// points-per-column line per display and the geometry to put back - no session name, no text.
	handler := api.New(agterm.New(socketPath), stateDir)

	// false: this bridge may stand behind a TLS-terminating proxy, and behind one every
	// connection's peer is the proxy rather than the caller. Per-source blocking over a single
	// collapsed source is a global ceiling wearing a per-source costume - five failed handshakes
	// and everybody is refused, the owner included. The concurrency bound in the listener does the
	// real work either way. See internal/listener, where the argument exists precisely so this is
	// said rather than defaulted.
	srv := listener.New(pinning.ServerConfig(own, peers.Certificates()), handler, false)

	tcp, err := net.Listen("tcp", listenAddr)
	if err != nil {
		return fmt.Errorf("listen: %w", err)
	}
	defer tcp.Close()

	// A proxy in front may terminate its own TLS and reconnect over the LAN, so a client
	// certificate cannot survive the trip and the pinned mTLS has to run INSIDE the proxied stream.
	// The front door is a net.Listener, so the listener above is unchanged and never learns that a
	// proxy, an HTTP request or a WebSocket frame exists. Pinning is not weakened to fit the proxy.
	ln := frontdoor.Listen(tcp)
	defer ln.Close()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() { <-ctx.Done(); ln.Close() }()

	// The address, and how many phones may reach it. No fingerprints: a fingerprint identifies the
	// owner's phone, this log is a file anything that can read the disk can read, and a count is
	// what a person needs in order to know whether pairing worked. No version banner and no build
	// identifier either - the restraint that keeps them off the wire keeps them out of the log.
	log.Printf("listening on %s", listenAddr)
	log.Printf("paired phones: %d", len(peers.Peers()))
	log.Printf("agterm socket %s", socketPath)
	// **This process exits with the app that started it.**
	//
	// There is no launchd job to notice that the menu-bar app is gone, so a crash there would
	// otherwise leave this bridge listening on the port its owner deliberately exposed to the
	// internet, with no user interface left anywhere that could close it.
	//
	// **stop() rather than os.Exit, and that is the whole reason the watchdog reports through a
	// callback instead of exiting for itself.** stop cancels the context above - the same one
	// SIGTERM cancels - so the listener closes, the control socket is removed, and a window this
	// bridge resized is put back the way it was found. An exit from inside the watcher would skip
	// every one of those and the owner would be left looking at the consequences of the second one.
	//
	// Zero is the mode a person running this by hand from a terminal gets: no watcher is started at
	// all, so the bridge lives until they stop it.
	if parentPID > 0 {
		log.Printf("watching parent pid %d", parentPID)
		parent.Watch(ctx, parentPID, parent.DefaultEvery, func() {
			log.Print("parent process is gone; exiting")
			stop()
		})
	}

	// If a previous run died with the owner's window resized, put it back. Logged and not fatal: a
	// bridge that refuses to start because agterm is not up yet would be a worse outcome than a
	// window that stays narrow until the next resize.
	if err := handler.RestorePending(ctx); err != nil {
		log.Printf("%v", err)
	}

	// Same reasoning one step further: a crash mid-calibration leaves a session in their sidebar that
	// nothing else will ever clear, and litter in a tool that touches their machine costs trust.
	if err := handler.CloseStrayCalibrationSession(ctx); err != nil {
		log.Printf("%v", err)
	}

	// The LOCAL door: a unix socket in the state directory, for the owner's own commands on this
	// Mac. It is not a second front door - it has no address off this machine, and the directory's
	// 0700 is what authenticates it. See internal/control.
	//
	// Not fatal: a bridge that refused to start because the control socket could not be created would
	// take the phone offline to protect a convenience.
	if door, err := control.Listen(ctx, stateDir, handler); err != nil {
		log.Printf("control socket unavailable: %v", err)
	} else {
		// Closing it no longer removes the file - see SetUnlinkOnClose in internal/control - so this
		// defer cannot delete a socket that a newer instance has bound in the meantime. That is the
		// whole of the restart bug, and the defer is only safe because of the line over there.
		defer door.Close()
		// **"reachable" rather than "listening", and the difference is the bug this line used to
		// hide.** Listen dials the path before returning, so what is claimed here is what a client
		// would find, not merely that a bind succeeded. Those two came apart for an afternoon.
		log.Printf("control socket %s reachable (0600, this machine only)", control.SocketPath(stateDir))
	}

	if err := srv.Serve(ctx, ln); err != nil && !errors.Is(err, net.ErrClosed) {
		return err
	}
	return nil
}

// identity loads the bridge's own certificate and key from the state directory, minting them on the
// first run.
//
// **Minting here rather than in a separate tool is what makes an argument-only entry point work at
// all.** The certificate is public - it is what the phone pins - and the key never leaves this
// directory, so there is nothing about generating it that needs a person present. A bridge that
// refused to start until somebody ran a provisioning command would make the first run a support
// question, and that command would exist only to write two files this process is already allowed
// to write.
//
// # Two starts at once must not produce a certificate and a key from different mints
//
// Minting is not "check, then write". Two processes given the same empty state directory both see
// no identity, both mint, and both write - and with a plain write the two files are installed
// independently, so the loser can overwrite the winner's key after the winner has already installed
// its certificate. **Both files then exist, the half-pair check passes, and every start after that
// dies with `tls: private key does not match public key`** - which is worse than either half being
// missing, because the recovery is deleting both and re-pairing every phone.
//
// Two properties fix it, and neither is enough alone:
//
//   - **The key is installed by a hard link from a temporary file, and the link IS the lock.** link
//     fails with EEXIST when the target is there, atomically, so exactly one process can install a
//     key. Whoever does owns the mint and goes on to install the certificate; whoever loses adopts
//     the winner's pair and throws away the one it minted. Nothing is ever overwritten.
//
//   - **Both files appear complete or not at all.** A create-then-write leaves a zero-length file
//     visible to any reader in between, and a reader that finds one is not looking at a half-pair,
//     it is looking at a file that will not parse. Content first, install second - link for the
//     key, rename for the certificate - so the only intermediate state anybody can observe is a
//     key with no certificate yet.
//
// That last state is also what a run that CRASHED between the two installs leaves behind, and the
// two cannot be told apart by looking. So they are told apart by waiting: a mint in flight resolves
// in milliseconds, and a crashed one never does. A half pair that outlasts the budget is reported
// rather than repaired, because minting over half of a pair would unpair every phone that pinned
// the certificate.
func identity(stateDir string) (tls.Certificate, error) {
	certPath := filepath.Join(stateDir, certFile)
	keyPath := filepath.Join(stateDir, keyFile)

	own, found, err := loadIdentity(certPath, keyPath)
	if found {
		return own, err
	}
	if errors.Is(err, errHalfPair) {
		return awaitIdentity(certPath, keyPath)
	}
	if err != nil {
		return tls.Certificate{}, err
	}

	// 0700: the directory holds the one secret this process has.
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return tls.Certificate{}, fmt.Errorf("state directory: %w", err)
	}

	id, err := pinning.Mint("agterm-remote bridge", identityLifetime)
	if err != nil {
		return tls.Certificate{}, fmt.Errorf("mint bridge identity: %w", err)
	}

	installed, err := installKey(stateDir, keyPath, id.KeyPEM)
	if err != nil {
		return tls.Certificate{}, err
	}
	if !installed {
		// Somebody else got there first. Their certificate is what every phone will pin, so this
		// process adopts it and the identity minted a few lines above is thrown away unused.
		return awaitIdentity(certPath, keyPath)
	}

	// The certificate second, and any failure from here takes the key with it. Leaving the key
	// behind would leave the half pair the check above refuses, and the next start would then wait
	// out the budget and stop, needing a person to delete a file before the bridge would run.
	if err := installFile(stateDir, certPath, id.CertPEM); err != nil {
		_ = os.Remove(keyPath)
		return tls.Certificate{}, fmt.Errorf("install bridge certificate: %w", err)
	}
	log.Printf("minted a bridge identity in %s", stateDir)

	own, err = pinning.LoadIdentity(id)
	if err != nil {
		return own, fmt.Errorf("bridge identity: %w", err)
	}
	return own, nil
}

// errHalfPair is one of the two files without the other. Its own error because the caller has to
// tell it from an unreadable directory: this one is worth waiting on, and that one is not.
var errHalfPair = errors.New("one half of the identity is missing")

// loadIdentity reads the pair if it is there.
//
// The three outcomes are distinct on purpose. Both files present is an identity; neither is a
// bridge that has never run, and found is false so the caller mints; exactly one is errHalfPair,
// which the caller must not paper over. Collapsing the last into the second is what would let a
// crashed run's leftover key be silently replaced, taking every paired phone with it.
func loadIdentity(certPath, keyPath string) (own tls.Certificate, found bool, err error) {
	certPEM, certErr := os.ReadFile(certPath)
	keyPEM, keyErr := os.ReadFile(keyPath)
	switch {
	case certErr == nil && keyErr == nil:
		own, err := pinning.LoadIdentity(pinning.Identity{CertPEM: certPEM, KeyPEM: keyPEM})
		if err != nil {
			return own, true, fmt.Errorf("bridge identity: %w", err)
		}
		return own, true, nil
	case errors.Is(certErr, fs.ErrNotExist) && errors.Is(keyErr, fs.ErrNotExist):
		return tls.Certificate{}, false, nil
	case errors.Is(keyErr, fs.ErrNotExist):
		// The CERTIFICATE survived. This is the dangerous direction: that certificate may be the one
		// a phone pinned, and there is no way from here to tell whether any phone did. Deleting it
		// and starting over is what unpairs them, so the message says so and offers nothing else.
		return tls.Certificate{}, false, fmt.Errorf(
			"bridge identity: %s is there and %s is not; a new identity cannot be minted over a "+
				"surviving certificate, because deleting that certificate unpairs every phone that "+
				"pinned it. Restore the key, or unpair and re-pair deliberately: %w",
			certFile, keyFile, errHalfPair)
	case errors.Is(certErr, fs.ErrNotExist):
		// The KEY survived, and this is the state a mint that crashed between installing the key and
		// installing the certificate routinely leaves. **No certificate was ever published, so
		// nothing pinned it and nothing is unpaired by deleting the key** - which is the whole
		// recovery. Saying "this would unpair every phone" here, as one shared message used to,
		// pointed the owner away from the only safe move in the common case.
		return tls.Certificate{}, false, fmt.Errorf(
			"bridge identity: %s is there and %s is not, which is what a mint interrupted partway "+
				"leaves behind; no certificate was published, so deleting %s unpairs nobody and the "+
				"next start mints a fresh pair: %w",
			keyFile, certFile, keyFile, errHalfPair)
	default:
		return tls.Certificate{}, false, fmt.Errorf("bridge identity: %w", errors.Join(certErr, keyErr))
	}
}

// awaitIdentity waits for the process that won the key to finish installing its certificate.
//
// The half-pair error is not an answer here, and that is the whole reason this is a loop rather
// than one more read: between the winner installing the key and installing the certificate there is
// a window in which exactly one file exists, and reporting that as a fault would turn a race this
// design has already handled into a failed start. It is only a fault once the winner has plainly
// stopped, which is what the budget decides - and then the error the caller gets is the half-pair
// one, naming both files and what deleting the survivor would cost.
func awaitIdentity(certPath, keyPath string) (tls.Certificate, error) {
	deadline := time.Now().Add(identityWaitBudget)
	for {
		own, found, err := loadIdentity(certPath, keyPath)
		if found {
			return own, err
		}
		if err != nil && !errors.Is(err, errHalfPair) {
			return tls.Certificate{}, err
		}
		if time.Now().After(deadline) {
			if err == nil {
				err = errors.New("the identity that was being written is not there")
			}
			return tls.Certificate{}, err
		}
		time.Sleep(identityWaitStep)
	}
}

// installKey writes the key to a temporary file and hard-links it into place, reporting whether
// this process was the one that got there.
//
// link rather than rename: rename REPLACES, which is exactly the overwrite this must not do, while
// link refuses when the target exists and refuses atomically. That refusal is the whole
// coordination mechanism - there is no lock file to go stale, because the thing being locked is the
// thing being created.
func installKey(dir, keyPath string, pem []byte) (bool, error) {
	tmp, err := writeTemp(dir, keyFile, pem)
	if err != nil {
		return false, fmt.Errorf("write bridge key: %w", err)
	}
	defer func() { _ = os.Remove(tmp) }()

	if err := os.Link(tmp, keyPath); err != nil {
		if errors.Is(err, fs.ErrExist) {
			return false, nil
		}
		return false, fmt.Errorf("install bridge key: %w", err)
	}
	return true, nil
}

// installFile writes content to a temporary file in the same directory and renames it over path, so
// no reader ever sees a partial one. Same directory because a rename across filesystems is a copy.
func installFile(dir, path string, content []byte) error {
	tmp, err := writeTemp(dir, filepath.Base(path), content)
	if err != nil {
		return err
	}
	defer func() { _ = os.Remove(tmp) }()
	return os.Rename(tmp, path)
}

// writeTemp writes content to a new file in dir at 0600 and returns its name.
//
// The chmod does not close a window. CreateTemp already asks for 0600 and a umask can only REMOVE
// bits, so nothing here is ever more permissive than that, even for an instant - the earlier note
// claiming this stopped the file being briefly readable was wrong about what a umask does. What it
// actually does is put back the bits a restrictive umask stripped: under `umask 0377` the file
// arrives 0400, and this process could then neither write the key into it nor, on a later start,
// read it back. The mode is the one this file is meant to have, asserted rather than inherited.
func writeTemp(dir, prefix string, content []byte) (string, error) {
	f, err := os.CreateTemp(dir, prefix+".tmp-*")
	if err != nil {
		return "", err
	}
	if err := f.Chmod(0o600); err != nil {
		_ = f.Close()
		_ = os.Remove(f.Name())
		return "", err
	}
	if _, err := f.Write(content); err != nil {
		_ = f.Close()
		_ = os.Remove(f.Name())
		return "", err
	}
	// Before the install, not after: a link or a rename that lands ahead of the data it points at is
	// the tearing both were chosen to prevent.
	if err := f.Sync(); err != nil {
		_ = f.Close()
		_ = os.Remove(f.Name())
		return "", err
	}
	if err := f.Close(); err != nil {
		_ = os.Remove(f.Name())
		return "", err
	}
	return f.Name(), nil
}
