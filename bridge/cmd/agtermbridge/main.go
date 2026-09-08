// Command agtermbridge serves two read-only verbs over pinned mutual TLS.
//
// It cannot type into a session. The verb does not exist in this binary — not behind a flag, not
// behind a build tag. Read-only-first is a ruling rather than a preference, because with
// the VPN ruled out this service stands on the open internet in front of sixteen Claude instances
// running with permission checks disabled. A publicly reachable service that can only read is a bad
// day; one that can type is a catastrophe.
//
// The port is not in this file, not in any default, and not anywhere in the repository. It comes from
// a config file the owner keeps outside the tree. A port that is committed is a
// port that stays after it is rotated.
package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"github.com/isachivka/agterm-remote/bridge/internal/agterm"
	"github.com/isachivka/agterm-remote/bridge/internal/api"
	"github.com/isachivka/agterm-remote/bridge/internal/control"
	"github.com/isachivka/agterm-remote/bridge/internal/frontdoor"
	"github.com/isachivka/agterm-remote/bridge/internal/listener"
	"github.com/isachivka/agterm-remote/bridge/internal/logfile"
	"github.com/isachivka/agterm-remote/bridge/internal/pinning"
)

// Config is read from disk rather than compiled in, so that rotating the address the bridge answers
// on never requires a rebuild and never leaves a stale value in git history.
type Config struct {
	// Listen is a host:port. Loopback while iteration 1 is unanswered; the owner changes it in this
	// file, not in code.
	Listen string `json:"listen"`
	// Socket overrides agterm's control socket path. Empty means the default.
	Socket string `json:"socket,omitempty"`
	// Log is where the record goes. Empty means stderr, which under launchd is whatever the job
	// redirects — usable, but unbounded, so the packaged job sets this.
	Log string `json:"log,omitempty"`

	// LANCert and LANKey turn the ON-LINK hop into TLS. Both empty means plaintext on-link, which is
	// what this bridge originally assumed and what a proxy configured for HTTP expects.
	//
	// # Why this exists, and what it is NOT
	//
	// A TLS-terminating proxy proxies to the laptop, and may be configured to speak HTTPS to its
	// backend. When it is, the proxy opens TLS to this port; a plaintext listener sees a ClientHello,
	// cannot answer it, and the proxy reports 502 to the phone. Measured, not inferred: the proxy's first
	// 297 bytes on the wire began `16 03 01`, and a self-signed responder on the same port answered
	// 200 through the same name.
	//
	// **This is opportunistic encryption of one LAN hop and it authenticates nothing.** The proxy
	// does not validate this certificate — it cannot, there is no name it could check it against —
	// so anyone who can reach this port can complete the handshake. It buys confidentiality against
	// a passive listener on the LAN and nothing else.
	//
	// The security of this service is unchanged and lives entirely in the pinned mTLS INSIDE the
	// stream, which still terminates here and still accepts exactly one certificate. Nothing about
	// this layer weakens it, and nothing about it should be mistaken for authentication.
	LANCert string `json:"lan_cert,omitempty"`
	LANKey  string `json:"lan_key,omitempty"`
}

// Bounds on the log. Roughly 4 MB of disk in total, which is generous for one line per request and
// one line per window of failed handshakes, and is a ceiling rather than a hope.
const (
	logMaxBytes = 1 << 20
	logKeep     = 3
)

func main() {
	dir := flag.String("dir", defaultDir(), "directory holding config.json, the certificate, key and pinned peer")
	flag.Parse()

	if err := run(*dir); err != nil {
		log.Fatalf("agtermbridge: %v", err)
	}
}

func defaultDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return ".agterm-bridge"
	}
	return filepath.Join(home, ".config", "agterm-bridge")
}

func run(dir string) error {
	cfg, err := loadConfig(filepath.Join(dir, "config.json"))
	if err != nil {
		return err
	}

	// Every one of these is a hard failure. There is no degraded mode: a bridge that starts without a
	// pinned client certificate would be a bridge that answers anyone, which is the one thing this
	// milestone exists to prevent. Fail closed includes failing to start.
	certPEM, err := os.ReadFile(filepath.Join(dir, "bridge-cert.pem"))
	if err != nil {
		return fmt.Errorf("bridge certificate: %w (run bridgecert mint)", err)
	}
	keyPEM, err := os.ReadFile(filepath.Join(dir, "bridge-key.pem"))
	if err != nil {
		return fmt.Errorf("bridge key: %w (run bridgecert mint)", err)
	}
	peerPEM, err := os.ReadFile(filepath.Join(dir, "phone-cert.pem"))
	if err != nil {
		return fmt.Errorf("pinned client certificate: %w (run bridgecert pin)", err)
	}

	own, err := pinning.LoadIdentity(pinning.Identity{CertPEM: certPEM, KeyPEM: keyPEM})
	if err != nil {
		return fmt.Errorf("bridge identity: %w", err)
	}
	peer, err := pinning.LoadPeer(peerPEM)
	if err != nil {
		return fmt.Errorf("pinned client certificate: %w", err)
	}

	if cfg.Log != "" {
		lf, err := logfile.Open(cfg.Log, logMaxBytes, logKeep)
		if err != nil {
			return fmt.Errorf("log: %w", err)
		}
		defer lf.Close()
		log.SetOutput(lf)
	}

	socketPath := cfg.Socket
	if socketPath == "" {
		socketPath = agterm.DefaultSocketPath()
	}
	// The resize cache lives beside the bridge's own config, which is gitignored and 0700. It holds a
	// points-per-column line per display and the geometry to put back - no session name, no text.
	handler := api.New(agterm.New(socketPath), dir)
	// false: a TLS-terminating proxy stands in front, so every connection's peer is the proxy and
	// per-source blocking would
	// collapse into a global ceiling any anonymous caller could trip for everyone.
	srv := listener.New(pinning.ServerConfig(own, peer), handler, false)

	tcp, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		return fmt.Errorf("listen: %w", err)
	}
	defer tcp.Close()

	// The on-link TLS wrapper, when configured. It sits OUTSIDE the front door and inside nothing:
	// the phone's pinned mTLS is established through it and is unaffected by it.
	if cfg.LANCert != "" || cfg.LANKey != "" {
		if cfg.LANCert == "" || cfg.LANKey == "" {
			return errors.New("config: lan_cert and lan_key must both be set, or neither")
		}
		pair, err := tls.LoadX509KeyPair(cfg.LANCert, cfg.LANKey)
		if err != nil {
			return fmt.Errorf("on-link certificate: %w", err)
		}
		// No client auth: the proxy has no certificate to present and authentication is not this
		// layer's job. Saying so here rather than leaving it to be inferred from an absent field.
		tcp = tls.NewListener(tcp, &tls.Config{
			Certificates: []tls.Certificate{pair},
			MinVersion:   tls.VersionTLS12,
		})
		log.Printf("on-link hop is TLS (opportunistic; authenticates nothing)")
	}

	// A TLS-terminating proxy proxies rather than forwards: it terminates its own TLS and connects over
	// the LAN, so a client certificate cannot survive the trip and mTLS runs INSIDE the proxied stream.
	//
	// The front door is a net.Listener, so the listener below is unchanged and never learns that a
	// proxy, an HTTP request or a WebSocket frame exists. Pinning is not weakened to fit the proxy.
	ln := frontdoor.Listen(tcp)
	defer ln.Close()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() { <-ctx.Done(); ln.Close() }()

	// The address and the pinned fingerprint, and nothing else. No version banner and no build
	// identifier: this line goes to the owner's log, but the same restraint that keeps them off the
	// wire keeps them out of habit.
	log.Printf("listening on %s", cfg.Listen)
	log.Printf("pinned client %s", pinning.Fingerprint(peer))
	log.Printf("agterm socket %s", socketPath)

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

	// The LOCAL door: a unix socket beside the config, for the owner's agterm palette command. It is
	// not a second front door - it has no address off this machine, and the directory's own 0700 is
	// what authenticates it. See internal/control.
	//
	// Not fatal: a bridge that refused to start because the control socket could not be created would
	// take the phone offline to protect a convenience.
	if door, err := control.Listen(ctx, dir, handler); err != nil {
		log.Printf("control socket unavailable: %v", err)
	} else {
		// Closing it no longer removes the file - see SetUnlinkOnClose in internal/control - so this
		// defer cannot delete a socket that a newer instance has bound in the meantime. That is the
		// whole of the restart bug, and the defer is only safe because of the line over there.
		defer door.Close()
		// **"reachable" rather than "listening", and the difference is the bug this line used to
		// hide.** Listen dials the path before returning, so what is claimed here is what a client
		// would find, not merely that a bind succeeded. Those two came apart for an afternoon.
		log.Printf("control socket %s reachable (0600, this machine only)", control.SocketPath(dir))
	}

	if err := srv.Serve(ctx, ln); err != nil && !errors.Is(err, net.ErrClosed) {
		return err
	}
	return nil
}

func loadConfig(path string) (Config, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return Config{}, fmt.Errorf("config: %w (it holds the listen address, and is not in the repository)", err)
	}
	var cfg Config
	if err := json.Unmarshal(raw, &cfg); err != nil {
		return Config{}, fmt.Errorf("config: %w", err)
	}
	if cfg.Listen == "" {
		return Config{}, errors.New("config: listen is required (host:port); there is deliberately no default")
	}
	return cfg, nil
}
