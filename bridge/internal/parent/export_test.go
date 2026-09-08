package parent

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"
)

// This file is compiled only under `go test`, so neither seam below is in the bridge binary and
// neither can be reached by anything outside this package's own tests.

// seamHolder names the test currently holding the kill seam, or is empty.
//
// It exists to turn "only one test at a time, and never a parallel one" from a comment somebody has
// to read into a failure that says so.
var seamHolder struct {
	mu sync.Mutex
	by string
}

// seamEnv is set on the holding test purely so that [testing.T.Setenv] gets a chance to object. The
// value is never read by anything.
const seamEnv = "AGTERM_REMOTE_KILL_SEAM"

// SetKill replaces the kill syscall for the duration of t, so a test can drive the real [alive] with
// each answer the kernel can give instead of only the one this machine's euid happens to earn. The
// real syscall is put back by [testing.T.Cleanup].
//
// # It refuses, loudly, rather than relying on a convention
//
// The seam is a package-level variable that every watcher goroutine reads. Two rules follow, and both
// used to be held by a comment and by a race detector that CI did not run - which is to say by
// nothing that would stop anybody:
//
//   - **A test using the seam must not call t.Parallel().** Parallel tests overlap with every other
//     parallel test, and the ones in this package start watchers. [testing.T.Setenv] is documented to
//     panic once t.Parallel has been called, and it is the only way from here to ask whether it has,
//     so it is used as the question and its panic is re-raised with an explanation of the actual rule.
//   - **Two tests may not hold the seam at once.** The second call panics naming both tests, which is
//     what a contributor gets for making two seam-using tests parallel, or for calling this twice in
//     one test.
//
// A failure that names the problem beats a green run and a mystery three weeks later.
func SetKill(t *testing.T, fake killFunc) {
	t.Helper()
	refuseParallel(t)

	seamHolder.mu.Lock()
	if seamHolder.by != "" {
		held := seamHolder.by
		seamHolder.mu.Unlock()
		panic(fmt.Sprintf("parent: %s replaced the kill seam while %s still holds it. The seam is a "+
			"package-level variable that every watcher goroutine reads, so exactly one test may hold "+
			"it at a time and no test that uses it may call t.Parallel().", t.Name(), held))
	}
	seamHolder.by = t.Name()
	previous := kill
	kill = fake
	seamHolder.mu.Unlock()

	t.Cleanup(func() {
		seamHolder.mu.Lock()
		defer seamHolder.mu.Unlock()
		kill = previous
		seamHolder.by = ""
	})
}

// refuseParallel panics, with the seam's rule spelled out, if t has called t.Parallel.
func refuseParallel(t *testing.T) {
	t.Helper()
	defer func() {
		if r := recover(); r != nil {
			panic(fmt.Sprintf("parent: %s uses the kill seam and must not call t.Parallel(). The seam "+
				"is a package-level variable that every other test's watcher goroutine reads, so a "+
				"parallel test racing on it produces failures nobody can reproduce. Remove the "+
				"t.Parallel() call. (asked via t.Setenv, which answered: %v)", t.Name(), r))
		}
	}()
	// Setenv is documented to panic once t.Parallel has been called. Nothing reads this variable;
	// the question is the whole point of the call.
	t.Setenv(seamEnv, t.Name())
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
