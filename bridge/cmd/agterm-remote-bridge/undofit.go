package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os/exec"
	"strings"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/control"
)

// The `undo-fit` subcommand: the palette's way out of a phone fit, run by agterm on the Mac.
//
// # It asks the running bridge; it does not touch the bridge's files
//
// The bridge is the only writer of its store, and this command has no opinion about what is in it.
// It dials the bridge's local control socket, says `restore`, and prints what came back. A tool that
// edited resize-cache.json would race the running bridge for the file that holds the restore point -
// the one record of where the owner's window came from - and it could not release a pty the bridge
// is holding through a live connection. The bridge can; so the bridge is asked.
//
// The phone needs no telling. Its toggle renders whatever the bridge reports, and its poll re-reads
// that every couple of seconds, so pressing this on the laptop unpresses the button in the owner's
// hand.
//
// # Doing nothing, honestly
//
// With no fit in force there is nothing to undo. The bridge says so, this prints it and exits 0, and
// nothing is resized: the owner is sitting in front of that window, and moving it on a guess is
// worse than doing nothing.
//
// # Why --notify exists
//
// agterm runs a palette command through `/bin/sh -c`, detached and with no terminal, so stdout goes
// nowhere. Without a second channel the honest "nothing to undo" would be indistinguishable from
// the command not running at all. So --notify posts the same sentence as a macOS notification,
// through osascript: no new dependency, no new permission, and on a machine without it the command
// still does its work and still exits truthfully.
//
// # Why a subcommand of the bridge rather than a second binary
//
// The palette line names an executable, and the bridge is the one executable the Mac app installs
// and knows the path of. A second binary would be a second path to keep right.

// subcommand runs `undo-fit` when the first argument names it, and reports whether it did.
//
// Dispatched on the bare first argument, BEFORE the daemon's flag set is parsed, so the daemon's
// flags stay exactly as they are and a subcommand can have its own.
func subcommand(args []string, stdout, stderr io.Writer, say func(string)) (handled bool, code int) {
	if len(args) == 0 || args[0] != "undo-fit" {
		return false, 0
	}
	return true, undoFit(args[1:], stdout, stderr, say)
}

// undoFit is the subcommand's body: parse its flags, ask the bridge, say what happened.
func undoFit(args []string, stdout, stderr io.Writer, say func(string)) int {
	fs := flag.NewFlagSet("undo-fit", flag.ContinueOnError)
	fs.SetOutput(stderr)
	stateDir := fs.String("state-dir", "", "the bridge's state directory, which holds its control socket (required)")
	notify := fs.Bool("notify", false, "also post the outcome as a macOS notification, for a palette command that has no terminal")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *stateDir == "" {
		fmt.Fprintln(stderr, "undo-fit needs --state-dir")
		return 2
	}

	message, err := askRestore(control.SocketPath(*stateDir))
	if err != nil {
		message = err.Error()
	}
	fmt.Fprintln(stdout, message)
	if *notify {
		say(message)
	}
	if err != nil {
		return 1
	}
	return 0
}

// askRestore sends `restore` to the control socket and returns the bridge's sentence about it.
//
// Both replies the bridge gives on success are success here: "the window is back the way it was"
// and "no fit is in force" are two truthful outcomes of asking for an undo, and dressing the second
// up as a failure would be a lie the notification then repeats.
func askRestore(socketPath string) (string, error) {
	conn, err := net.DialTimeout("unix", socketPath, dialTimeout)
	if err != nil {
		// Distinguished from a failed restore because the remedies differ: this means the bridge is
		// not running, and retrying the command will not change that.
		return "", fmt.Errorf("no bridge is listening at %s (is Agterm Remote running?)", socketPath)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(callTimeout))

	if _, err := io.WriteString(conn, fmt.Sprintf("{\"verb\":%q}\n", control.VerbRestore)); err != nil {
		return "", fmt.Errorf("the bridge could not be asked: %w", err)
	}

	var resp struct {
		OK       bool   `json:"ok"`
		Restored bool   `json:"restored"`
		Message  string `json:"message"`
		Error    string `json:"error"`
	}
	if err := json.NewDecoder(conn).Decode(&resp); err != nil {
		return "", fmt.Errorf("the bridge did not answer: %w", err)
	}
	if !resp.OK {
		if resp.Error != "" {
			return "", errors.New(resp.Error)
		}
		return "", errors.New("the bridge refused")
	}
	if resp.Message != "" {
		return resp.Message, nil
	}
	// A bridge that answered ok with no sentence. Both outcomes are still success; say which.
	if resp.Restored {
		return "the window is back the way it was", nil
	}
	return "no fit is in force", nil
}

// notify posts a macOS notification, best effort.
//
// The work is already done by the time this runs, so a machine without osascript, or a notification
// the owner has muted, must not turn a successful restore into a failed command.
func notify(message string) {
	// Quoted for AppleScript rather than concatenated: the message is the bridge's today, but a
	// string that reaches an interpreter unescaped is a habit, not a one-off.
	script := fmt.Sprintf("display notification %s with title %s", appleScriptString(message), appleScriptString("Agterm Remote"))
	_ = exec.Command("osascript", "-e", script).Run()
}

func appleScriptString(s string) string {
	escaped := strings.ReplaceAll(s, `\`, `\\`)
	escaped = strings.ReplaceAll(escaped, `"`, `\"`)
	return `"` + escaped + `"`
}

const (
	dialTimeout = 2 * time.Second
	// One restore: a resize on a live window plus a pty released through a daemon, with the same
	// generosity the bridge allows itself for either.
	callTimeout = 30 * time.Second
)
