package parent_test

import (
	"context"
	"os"
	"os/exec"
	"sync/atomic"
	"testing"
	"time"

	"github.com/isachivka/agterm-remote/bridge/internal/parent"
)

// every is the poll interval every test here uses.
//
// A millisecond, so the whole file costs milliseconds rather than seconds. **This matters more than
// it looks: a watchdog test that sleeps is a test everybody who touches this repository pays for,
// thousands of times over.** The interval is a parameter of [parent.Watch] precisely so the tests
// need not wait out the two seconds the bridge itself uses.
const every = time.Millisecond

// window is how long a "must not fire" test watches before it believes the answer.
//
// A hundred milliseconds is a hundred polls at the interval above. A negative can never be proved,
// only made expensive to be wrong about, and a hundred consecutive correct answers is the price
// this file is willing to pay for it.
const window = 100 * time.Millisecond

// helperEnv marks a re-executed copy of this test binary as the stand-in parent.
const helperEnv = "AGTERM_REMOTE_PARENT_HELPER"

// TestTheHelperProcessSleeps is not a test. It is the body of the child process that [reapedPID]
// starts and kills, and it exists so this file needs no external program: `sleep` is on the PATH of
// every machine anybody will run this on until the one day it is not, and a test that fails for
// that reason teaches nobody anything.
func TestTheHelperProcessSleeps(t *testing.T) {
	if os.Getenv(helperEnv) == "" {
		t.Skip("this test is the stand-in parent process, and is skipped in a normal run")
	}
	time.Sleep(time.Minute)
}

// reapedPID returns the pid of a process that this test started, killed, and waited for - so it is
// a pid that certainly named a process and certainly does not now.
//
// **This is here instead of a hard-coded high pid, and the difference is not pedantry.** The obvious
// way to write "a process that does not exist" is to pick a number near the top of the pid space and
// assume nothing is using it. On macOS that is a fair bet; on Linux `/proc/sys/kernel/pid_max` is
// routinely raised well past it on a busy machine, and then the number names somebody's process and
// the test fails for a reason that has nothing to do with this package. A test that MANUFACTURES the
// condition it needs beats one that assumes it, and this one costs the same milliseconds.
//
// **The Wait is the load-bearing line.** A child that has been killed but not reaped is a zombie,
// and a zombie still holds its pid entry: `kill(pid, 0)` succeeds against it, so a version of this
// helper that skipped the Wait would hand back a pid that reads as ALIVE and the test would fail
// while the code under test was correct.
func reapedPID(t *testing.T) int {
	t.Helper()

	cmd := exec.Command(os.Args[0], "-test.run=^TestTheHelperProcessSleeps$")
	cmd.Env = append(os.Environ(), helperEnv+"=1")
	if err := cmd.Start(); err != nil {
		t.Fatalf("starting the stand-in parent: %v", err)
	}
	pid := cmd.Process.Pid
	if err := cmd.Process.Kill(); err != nil {
		t.Fatalf("killing the stand-in parent: %v", err)
	}
	_ = cmd.Wait()
	return pid
}

// **The whole point of the package: an absent parent makes gone fire.**
//
// Without this the bridge outlives a crashed menu-bar app, holding open the one port its owner
// deliberately exposed to the internet, with no user interface left anywhere that could close it.
func TestGoneFiresWhenTheParentIsNotThere(t *testing.T) {
	t.Parallel()

	fired := make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	parent.Watch(ctx, reapedPID(t), every, func() { close(fired) })

	select {
	case <-fired:
	case <-time.After(2 * time.Second):
		t.Fatal("gone never fired for an absent parent, so the bridge would outlive the app that started it")
	}
}

// **A live parent must not make gone fire, and this is the half that costs the owner their session
// when it is wrong.** A false positive here is a bridge that quits underneath a working phone.
func TestGoneDoesNotFireWhileTheParentLives(t *testing.T) {
	t.Parallel()

	fired := make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	parent.Watch(ctx, os.Getpid(), every, func() { close(fired) })

	select {
	case <-fired:
		t.Fatal("gone fired while the parent was alive")
	case <-time.After(window):
	}
}

// **A process this user may not signal is ALIVE, and the kernel says so with EPERM rather than with
// success.**
//
// This is the trap the whole implementation turns on. `kill(pid, 0)` returns ESRCH for a pid that
// names nothing and EPERM for a pid that names a process belonging to somebody else - and EPERM is
// an existence proof, not a failure. Code that reads "err != nil" as "gone" passes every other test
// in this file and then makes the bridge quit at random on a machine where pids get recycled into
// other users' processes.
//
// Pid 1 is the deterministic case: it exists on macOS and on Linux and in a container by definition,
// and it belongs to root. An unprivileged run gets EPERM from it, which is the answer being asserted
// on; a run that happens to be root gets success. Both mean alive, so the test is correct either way
// and does real work in the case that matters.
func TestGoneDoesNotFireForAProcessThisUserMayNotSignal(t *testing.T) {
	t.Parallel()

	fired := make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	parent.Watch(ctx, 1, every, func() { close(fired) })

	select {
	case <-fired:
		t.Fatal("gone fired for pid 1, which exists; only ESRCH means the parent is gone")
	case <-time.After(window):
	}
}

// **Pid zero starts no watcher at all, and that is a guard rather than a convenience.**
//
// Zero is how a person running the bridge by hand from a terminal says "nothing is supervising me".
// It must not reach the kernel: `kill(0, sig)` does not mean "ask about process zero", it addresses
// **every process in the caller's own process group**. With signal 0 that is harmless and would
// simply answer "alive" forever, so the bug would hide - until somebody widened the same call.
func TestAZeroPidStartsNoWatcher(t *testing.T) {
	t.Parallel()

	fired := make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	parent.Watch(ctx, 0, every, func() { close(fired) })

	select {
	case <-fired:
		t.Fatal("gone fired for pid 0, which is not a pid and must never be asked about")
	case <-time.After(window):
	}
}

// **Cancelling the context stops the watcher, so a bridge already shutting down is not told twice.**
//
// The sequence is what makes this a real test rather than a test of a ticker that never ticked: the
// watcher runs against a LIVE process first, is cancelled, and only THEN does that process die. A
// watcher that ignored the context would have every reason to fire in the window that follows.
func TestCancellingTheContextStopsTheWatcher(t *testing.T) {
	t.Parallel()

	cmd := exec.Command(os.Args[0], "-test.run=^TestTheHelperProcessSleeps$")
	cmd.Env = append(os.Environ(), helperEnv+"=1")
	if err := cmd.Start(); err != nil {
		t.Fatalf("starting the stand-in parent: %v", err)
	}

	fired := make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	parent.Watch(ctx, cmd.Process.Pid, every, func() { close(fired) })

	// Long enough that the watcher has polled a live parent many times over before it is stopped.
	time.Sleep(20 * time.Millisecond)
	select {
	case <-fired:
		t.Fatal("gone fired while the stand-in parent was still running")
	default:
	}

	cancel()
	if err := cmd.Process.Kill(); err != nil {
		t.Fatalf("killing the stand-in parent: %v", err)
	}
	_ = cmd.Wait()

	select {
	case <-fired:
		t.Fatal("gone fired after the context was cancelled")
	case <-time.After(window):
	}
}

// **gone is called once and the watcher then stops.**
//
// It cancels the bridge's context, and the second call would land on a process already tearing down
// its listener and putting the owner's window back. Firing on every subsequent tick would also mean
// the goroutine outlives the shutdown it asked for.
func TestGoneFiresExactlyOnce(t *testing.T) {
	t.Parallel()

	var calls atomic.Int64
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	parent.Watch(ctx, reapedPID(t), every, func() { calls.Add(1) })

	// Many multiples of the interval: a watcher that kept polling would be into the dozens by now.
	time.Sleep(window)
	if got := calls.Load(); got != 1 {
		t.Fatalf("gone was called %d times, want exactly 1", got)
	}
}

// A non-positive interval must not panic. [time.NewTicker] panics on one, and a panic in the
// watchdog's goroutine takes down the bridge it exists to shut down cleanly - the failure mode is
// strictly worse than the mistake that caused it, so the package substitutes its own default.
func TestANonPositiveIntervalIsNotFatal(t *testing.T) {
	t.Parallel()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	parent.Watch(ctx, os.Getpid(), 0, func() {})
	time.Sleep(20 * time.Millisecond)
}
