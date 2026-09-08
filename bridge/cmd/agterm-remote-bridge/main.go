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
	parent := flag.Int("parent-pid", 0, "exit when this pid goes away; 0 disables")
	flag.Parse()

	if *listen == "" || *stateDir == "" {
		fmt.Fprintln(os.Stderr, "--listen and --state-dir are required")
		os.Exit(2)
	}
	// A negative pid is not a process this or any other program can wait on, so it is a typo rather
	// than an instruction. Refused here rather than logged verbatim: a flag that accepts a value it
	// can never act on is a flag that reports success for a mistake.
	if *parent < 0 {
		fmt.Fprintln(os.Stderr, "--parent-pid must be a pid, or 0 to disable")
		os.Exit(2)
	}
	if err := run(*listen, *socket, *stateDir, *logPath, *parent); err != nil {
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
	if parentPID != 0 {
		// Recorded rather than acted on. The supervisor that makes this binary exit with its parent
		// is separate work; until it lands, the flag is accepted so the parent's command line does
		// not have to change when it does, and this line is here so nobody reads the flag as a
		// promise that is already being kept.
		log.Printf("parent pid %d recorded; this process does not yet exit on its own when it goes", parentPID)
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
// question, and that command would exist only to write two files this process is already allowed to
// write.
//
// A half-written pair is not repaired silently. If one file is there and the other is not, that is
// reported rather than papered over by minting, because a new identity unpairs every phone that
// pinned the old certificate.
func identity(stateDir string) (tls.Certificate, error) {
	certPath := filepath.Join(stateDir, certFile)
	keyPath := filepath.Join(stateDir, keyFile)

	certPEM, certErr := os.ReadFile(certPath)
	keyPEM, keyErr := os.ReadFile(keyPath)
	switch {
	case certErr == nil && keyErr == nil:
		own, err := pinning.LoadIdentity(pinning.Identity{CertPEM: certPEM, KeyPEM: keyPEM})
		if err != nil {
			return own, fmt.Errorf("bridge identity: %w", err)
		}
		return own, nil
	case errors.Is(certErr, os.ErrNotExist) && errors.Is(keyErr, os.ErrNotExist):
		// Neither half is there, which is a bridge that has never run. Fall through and mint.
	case errors.Is(certErr, os.ErrNotExist) || errors.Is(keyErr, os.ErrNotExist):
		return tls.Certificate{}, fmt.Errorf(
			"bridge identity: %s and %s must both exist or neither; minting over half of a pair would "+
				"unpair every phone that pinned the certificate", certFile, keyFile)
	default:
		return tls.Certificate{}, fmt.Errorf("bridge identity: %w", errors.Join(certErr, keyErr))
	}

	id, err := pinning.Mint("agterm-remote bridge", identityLifetime)
	if err != nil {
		return tls.Certificate{}, fmt.Errorf("mint bridge identity: %w", err)
	}
	// 0700 on the directory, 0600 on both files. The key is the one secret this process holds, and
	// the certificate is written at the same mode because nothing needs to read either of them
	// except this process and the app that started it.
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return tls.Certificate{}, fmt.Errorf("state directory: %w", err)
	}
	// The key first. A certificate with no key behind it is the half-pair the switch above refuses,
	// so writing the key second would make a crash between the two produce the unrecoverable order.
	if err := os.WriteFile(keyPath, id.KeyPEM, 0o600); err != nil {
		return tls.Certificate{}, fmt.Errorf("write bridge key: %w", err)
	}
	if err := os.WriteFile(certPath, id.CertPEM, 0o600); err != nil {
		return tls.Certificate{}, fmt.Errorf("write bridge certificate: %w", err)
	}
	log.Printf("minted a bridge identity in %s", stateDir)

	own, err := pinning.LoadIdentity(id)
	if err != nil {
		return own, fmt.Errorf("bridge identity: %w", err)
	}
	return own, nil
}
