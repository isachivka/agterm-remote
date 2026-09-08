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
	if held := seamHolder.by; held != "" {
		seamHolder.mu.Unlock()
		// Two different tests, and one test twice, are different mistakes with different remedies.
		// One message for both used to read "X replaced the seam while X still holds it", which
		// sounds like a defect in this guard rather than in the caller.
		if held == t.Name() {
			panic(fmt.Sprintf("parent: %s installed the kill seam twice. SetKill holds it for the "+
				"whole test and puts the real syscall back through t.Cleanup, so one call per test is "+
				"all there is; to vary the kernel's answer, change what the fake RETURNS rather than "+
				"replacing the fake.", t.Name()))
		}
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

// refuseParallel fails t, with the seam's rule spelled out, if t has called t.Parallel.
//
// # It reports rather than re-panicking, and that is the whole difference to a contributor
//
// Re-raising the panic aborts the entire test binary, leads with a standard-library sentence about
// t.Chdir, and buries the explanation under thirty lines of stack - and it would attribute ANY other
// panic out of t.Setenv to a t.Parallel call that may not be there. t.Fatalf gives one line naming
// the rule, quotes what was actually recovered instead of asserting a cause, and lets the rest of the
// suite run.
//
// # Asking the question also answers it for the runtime
//
// t.Setenv is documented to panic once t.Parallel has been called, and it is the only way from here
// to ask. It is not only a question: a successful call latches the runtime's own denyParallel, so a
// later t.Parallel in the same test is refused by testing itself, with no help from this package.
//
// **denyParallel is not inherited, though.** A SUBTEST that calls t.Parallel inside a test holding
// the seam is still permitted, and it runs while the seam is installed. Nothing here can see that;
// the race detector is the only cover for it, which is one more reason CI now runs with -race.
//
// If a future Go stops panicking, this degrades to permitting what it used to refuse - the benign
// direction, and still covered by -race.
func refuseParallel(t *testing.T) {
	t.Helper()
	defer func() {
		if r := recover(); r != nil {
			t.Helper()
			t.Fatalf("this test uses the kill seam and must not call t.Parallel(): the seam is a "+
				"package-level variable that every other test's watcher goroutine reads, so a parallel "+
				"test racing on it produces failures nobody can reproduce. Remove the t.Parallel() "+
				"call. (t.Setenv answered: %v)", r)
		}
	}()
	// Nothing reads this variable; the question is the whole point of the call.
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
