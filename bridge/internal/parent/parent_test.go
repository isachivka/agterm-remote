package parent_test

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"sync/atomic"
	"syscall"
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
// and it belongs to root. An unprivileged run gets EPERM from it, which is the arm being asserted on.
//
// **As root it gets nil instead, and then this test passes without exercising that arm at all.** It
// SKIPS rather than passing quietly, because a test that goes vacuous in an environment and says
// nothing about it is worse than no test: it reports a guard that is not there. A root container or
// a `sudo go test` would otherwise retire the only check on the errno rule reachable through a real
// signal, and nobody would learn that from the output.
//
// The rule itself is pinned uid-independently by TestOnlyESRCHMeansGone, which is why a skip here is
// a loss of one signal rather than of the coverage.
func TestGoneDoesNotFireForAProcessThisUserMayNotSignal(t *testing.T) {
	t.Parallel()

	if os.Geteuid() == 0 {
		t.Skip("running as root: kill(1, 0) answers nil rather than EPERM, so this test would pass " +
			"without touching the EPERM arm it exists for. TestOnlyESRCHMeansGone pins the same rule " +
			"without depending on who is running the tests.")
	}

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

// pollOnce drives the REAL watcher loop through exactly one poll and reports whether it decided the
// parent was alive. The caller must already have installed the kill seam.
//
// **No timeout and no sleep, and the two outcomes are told apart by which channel operation becomes
// possible.** The tick channel is unbuffered, so the first send returns only once the loop has taken
// it and is classifying. After that exactly one of two things can happen: the loop decided "gone",
// called gone and returned - in which case nothing will ever receive again and only `fired` can be
// ready - or it decided "alive" and came back to the select, in which case the second send is the
// only one that can proceed. They are mutually exclusive and one of them always happens, so the
// select below is a decision rather than a race.
//
// It also returns only once the watcher has stopped, which is what lets the caller change what the
// fake kernel answers between calls without synchronising anything: the channel operations order
// every write before the read that follows it.
func pollOnce(t *testing.T, pid int) bool {
	t.Helper()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	tick := make(chan time.Time)
	fired := make(chan struct{}, 1)
	done := make(chan struct{})
	go func() {
		defer close(done)
		parent.WatchOnTicks(ctx, pid, tick, func() { fired <- struct{}{} })
	}()

	tick <- time.Now()

	var isAlive bool
	select {
	case <-fired:
		isAlive = false
	case tick <- time.Now():
		isAlive = true
	}

	// **The watcher has to be off the seam before the caller touches it again**, and the race
	// detector found this the first time it was written without it: in the alive case the loop is
	// parked at its select, still holding the faked kill. Cancelling releases it; in the gone case it
	// returned before the select above resolved.
	cancel()
	<-done
	return isAlive
}

// **The errno rule, with every answer the kernel can give, driven through the code that actually
// runs, and with no dependence on who is running the tests.**
//
// This is the invariant the package turns on, and it used to be reachable only through a real
// `kill(1, 0)` - whose answer is EPERM for an ordinary user and nil for root - so the arm that
// matters most disappeared in exactly the environments easiest not to notice.
//
// **The seam is the syscall, not the rule, and that is the whole point of this shape.** An earlier
// version exported the classifier and tested it directly. It pinned the rule and pinned nothing to
// the caller: `alive` could be rewritten to compare the error against nil and this table would still
// have passed, leaving only the pid-1 test to catch it - the one test that skips under root. Faking
// the kernel instead means the assertion runs through the watcher's own loop and its own `alive`.
//
// The unrecognised errno is not filler. It fixes the DIRECTION this package fails in: an answer it
// does not understand must read as alive, because guessing "gone" from an unfamiliar error is a
// bridge that shuts itself down over a syscall quirk, and guessing "alive" is a bridge that stays up
// one extra poll.
//
// **No subtests and no t.Parallel.** The seam is installed once, for this test, and every row is
// driven through it; see [parent.SetKill], which now refuses both mistakes rather than documenting
// them. The rows are ordered writes and reads separated by channel operations, so the fake needs no
// lock of its own.
func TestOnlyESRCHMeansGone(t *testing.T) {
	const pid = 4242

	// Plain locals rather than atomics, and that is safe for one reason worth stating: while the
	// seam is installed nothing but pollOnce's watcher can call the fake, and every write here is
	// separated from the read that follows it by a channel operation - the unbuffered `tick <-` send
	// happens-before the fake runs, and `<-done` happens-after it. If anything else ever calls the
	// fake concurrently, these have to become atomics; -race is the only thing that would say so.
	var (
		answer error
		asked  int
		wrong  string
	)
	parent.SetKill(t, func(got int, sig syscall.Signal) error {
		asked++
		// The call site is under test too: it must ask about the pid it was given, with signal 0.
		if got != pid || sig != 0 {
			wrong = fmt.Sprintf("kill(%d, %d)", got, sig)
		}
		return answer
	})

	for _, c := range []struct {
		name  string
		err   error
		alive bool
	}{
		{"nil: the process exists and this user may signal it", nil, true},
		{"ESRCH: no process has that pid", syscall.ESRCH, false},
		{"EPERM: the process exists and belongs to somebody else", syscall.EPERM, true},
		{"an errno this package does not know", syscall.EINVAL, true},
		// The only row that pins errors.Is rather than ==. A `==` comparison against syscall.ESRCH
		// passes every other row here and fails this one.
		{"a wrapped ESRCH is still ESRCH", fmt.Errorf("kill: %w", syscall.ESRCH), false},
		// The two below distinguish nothing that plain EPERM and EINVAL do not - a rule that gets
		// those right gets these right. They are kept as documentation of the intended reading, not
		// as coverage, and should not be counted as pinning anything.
		{"a wrapped EPERM is still alive", fmt.Errorf("kill: %w", syscall.EPERM), true},
		{"an error carrying no errno at all", errors.New("something else went wrong"), true},
	} {
		answer, asked, wrong = c.err, 0, ""

		got := pollOnce(t, pid)

		if asked == 0 {
			t.Fatalf("%s: the watcher decided without asking the kernel at all", c.name)
		}
		if wrong != "" {
			t.Fatalf("%s: the watcher asked %s; it must ask about the pid it was given, with signal 0",
				c.name, wrong)
		}
		if got == c.alive {
			continue
		}
		// Spelled out rather than %v/%v: the failure that will actually happen here is the rule being
		// inverted, and the reader needs to be told which direction costs what.
		if c.alive {
			t.Errorf("%s: %v was classified as GONE; only ESRCH means gone, and reading any other "+
				"answer that way makes the bridge quit at random on a machine where pids are recycled "+
				"into other users' processes", c.name, c.err)
			continue
		}
		t.Errorf("%s: %v was classified as ALIVE; then a crashed parent leaves the bridge listening on "+
			"the owner's exposed port forever", c.name, c.err)
	}
}

// **A cancellation and a tick that are ready at the same instant must not produce "parent process is
// gone".**
//
// The select in the loop has two cases and picks at random between the ones that are ready, so a
// SIGTERM arriving as the parent dies could otherwise log a death about a shutdown the owner asked
// for. Nothing breaks - stop() is idempotent - but the line is untrue, and it is exactly the line
// somebody is reading while they debug a shutdown.
//
// **The first version of this test proved nothing and this one is why the tick channel is a
// parameter.** It cancelled the context and waited for a real ticker: cancellation won by a
// millisecond every time, so the tick arm was never taken at all - deleting the guard under test left
// it passing 340 runs out of 340, and forcing the body to fire unconditionally ALSO left it passing,
// which is how it was found out. A test that cannot fail is worse than no test, because it advertises
// coverage that is not there.
//
// Here the tick is buffered and delivered, and the context cancelled, BEFORE the loop is entered. Both
// cases are genuinely ready when the select executes, so the arm is taken about half the time; with
// the guard removed this fails roughly every other run under -count.
func TestGoneDoesNotFireWhenCancellationAndATickAreBothReady(t *testing.T) {
	// The parent is gone as far as the loop can tell, so the tick arm WOULD fire if it were taken
	// without the guard. Faked rather than reaped, because this test is about the select and not
	// about the kernel.
	parent.SetKill(t, func(int, syscall.Signal) error { return syscall.ESRCH })

	ctx, cancel := context.WithCancel(context.Background())
	tick := make(chan time.Time, 1)
	tick <- time.Now()
	cancel()

	fired := false
	// Synchronous: both arms of the select return, so this cannot hang, and there is nothing to wait
	// for afterwards.
	parent.WatchOnTicks(ctx, 4242, tick, func() { fired = true })

	if fired {
		t.Fatal("gone fired on a context that was already cancelled, so a shutdown would be reported " +
			"as the parent having disappeared")
	}
}
