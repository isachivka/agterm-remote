// Package ptysize reads the live size of the pty behind a process.
//
// # Why the pty is asked, and not agterm or zmx
//
// The tall fit holds a daemon's pty at a size through a second client, and the hold can be taken
// away: when the owner types on the Mac, agterm's client becomes leader again and its own size is
// applied. Nothing tells the bridge that happened. agterm publishes no grid size, `zmx.list` reports
// clients and leaders but not rows, and the daemon's socket protocol has no "what size are you"
// message. The pty itself does: TIOCGWINSZ on the slave device answers with the size the daemon last
// applied, whoever applied it.
//
// The device is found through the shell the daemon started, whose pid agterm reports as the pane's
// `leaderPID`. `ps -o tty=` names its controlling terminal, and that terminal is the pty. Verified on
// the owner's machine: `ttys008`, and `stty -f /dev/ttys008 size` agreed with what the pane showed.
package ptysize

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

// Size is rows by columns, as the kernel reports them.
type Size struct{ Rows, Cols int }

// The two steps are package-level func values so a test can stand in a device name and a size
// without a pty, and so the real path is exercised only where a real pty exists.
var (
	// ttyOf names the controlling terminal of pid as a device path.
	ttyOf = func(pid int) (string, error) {
		ctx, cancel := context.WithTimeout(context.Background(), psTimeout)
		defer cancel()
		out, err := exec.CommandContext(ctx, "ps", "-o", "tty=", "-p", fmt.Sprint(pid)).Output()
		if err != nil {
			return "", fmt.Errorf("ps for pid %d: %w", pid, err)
		}
		name := strings.TrimSpace(string(out))
		// `??` is ps for "none", and a process with no terminal has no size to read. Refused by
		// name rather than opened, because /dev/?? is not a device and the error would say so
		// less clearly.
		if name == "" || strings.HasPrefix(name, "?") {
			return "", fmt.Errorf("pid %d has no controlling terminal", pid)
		}
		return "/dev/" + name, nil
	}

	// winsize asks the device for its size.
	winsize = func(device string) (Size, error) {
		// Read-only and without becoming its controlling process: this opens the OWNER'S terminal,
		// and O_NOCTTY is what keeps that a read rather than an attachment.
		f, err := os.OpenFile(device, os.O_RDONLY|syscall.O_NOCTTY, 0)
		if err != nil {
			return Size{}, err
		}
		defer f.Close()
		var ws struct{ Row, Col, Xpixel, Ypixel uint16 }
		if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, f.Fd(), syscall.TIOCGWINSZ,
			uintptr(unsafe.Pointer(&ws))); errno != 0 {
			return Size{}, fmt.Errorf("TIOCGWINSZ on %s: %w", device, errno)
		}
		if ws.Row == 0 || ws.Col == 0 {
			// A pty nobody has sized yet reports zeros, and a zero is not a size the hold can be
			// compared with.
			return Size{}, fmt.Errorf("%s reports no size", device)
		}
		return Size{Rows: int(ws.Row), Cols: int(ws.Col)}, nil
	}
)

// psTimeout bounds the ps step. It runs on the phone's poll path, so a ps that hangs would hang the
// screen; it normally takes milliseconds.
const psTimeout = 3 * time.Second

// Read is the size of the pty controlling pid.
func Read(pid int) (Size, error) {
	if pid <= 0 {
		return Size{}, errors.New("no pid for the pane's shell")
	}
	device, err := ttyOf(pid)
	if err != nil {
		return Size{}, err
	}
	return winsize(device)
}
