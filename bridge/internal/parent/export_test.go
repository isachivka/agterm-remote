package parent

import (
	"context"
	"time"
)

// This file is compiled only under `go test`, so neither seam below is in the bridge binary and
// neither can be reached by anything outside this package's own tests.

// SetKill replaces the kill syscall and returns a function that puts the real one back, so a test can
// drive the real [alive] with each answer the kernel can give instead of only the one this machine's
// euid happens to earn.
//
// **A test that calls this must not call t.Parallel().** It writes a package-level variable that the
// watcher goroutines of every other test read. Sequential tests all complete before any parallel test
// body resumes, so a non-parallel test has the seam to itself; the race detector is what will say so
// if that ever stops being true.
func SetKill(fake killFunc) (restore func()) {
	previous := kill
	kill = fake
	return func() { kill = previous }
}

// WatchOnTicks runs the watcher's loop on a tick channel the caller owns, synchronously, so a test
// can decide exactly when a poll happens and can make a tick and a cancellation ready at the same
// instant. [Watch] is this with a real ticker and a goroutine around it.
//
// It reads the kill seam at call time, exactly as Watch does, so a test that has just replaced it
// gets the replacement and one that has not gets the real syscall.
func WatchOnTicks(ctx context.Context, pid int, tick <-chan time.Time, gone func()) {
	watch(ctx, pid, kill, tick, gone)
}
