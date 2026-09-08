// Package parent makes this process die with the one that started it.
//
// # Why the bridge watches at all
//
// The bridge is a child of the menu-bar app that owns the Mac side. There is no launchd job, no
// plist and no installer - which is a deliberate choice made elsewhere and it has one consequence
// that has to be paid for here: **nothing outside this process will ever clean it up.** If the app
// crashes, the bridge it left behind keeps listening on the one port its owner deliberately exposed
// to the internet, and there is no user interface anywhere that can close it. The owner would have
// to find the pid themselves, on a machine they may not be sitting at, to stop a service they never
// meant to still be running.
//
// So the bridge outlives its parent by at most one poll interval, and the poll is here.
//
// # Only ESRCH means gone
//
// `kill(pid, 0)` sends no signal. It asks the kernel whether the caller COULD signal that pid, and
// its two interesting answers are not symmetrical:
//
//   - **ESRCH** - no process has that pid. This is the one that means gone.
//   - **EPERM** - a process has that pid and it is not yours. **This is an existence proof.**
//
// The tempting implementation is `if err != nil { gone() }`, and it is wrong in the direction that
// costs the most. Pids are recycled, and a pid recycled into another user's process answers EPERM -
// so that version of this package makes the bridge quit at random on a busy machine, out from under
// a working phone, with a log line saying the parent is gone when it is sitting right there. Every
// answer other than ESRCH is therefore read as alive, including ones this package has never seen: a
// watchdog that guesses "shut down" from an error it does not recognise is a watchdog that fails
// towards the outage.
//
// # Pid reuse, which is not solved here
//
// The remaining hole is the other direction. The parent dies, the operating system eventually hands
// its pid to something else, and a poll that lands after that finds a live process and concludes the
// parent is fine - so the bridge never exits. It is unlikely, because the poll interval is seconds
// and pid allocation is sequential on both macOS and Linux, so the window is the whole trip around
// the pid space rather than the moment after the death; and it is bounded, because the very next
// poll after the death, before any reuse, is the one that fires.
//
// It is left open on purpose. Closing it needs an identity for the parent that outlives the pid -
// its start time, a pipe held open across the fork whose read end reports EOF, or the app passing a
// token this process can check - and each of those is a change to the CONTRACT with the Mac app,
// which does not exist yet. The pipe is the right answer when it does: a closed descriptor cannot be
// recycled into somebody else's, so it removes the hazard rather than narrowing it. Written down so
// the next reader knows this was weighed and left, rather than missed.
//
// # Zero is not a pid
//
// Zero means the bridge was started by hand from a terminal and nothing is supervising it, so no
// watcher is started. It must never reach the kernel: `kill(0, sig)` does not ask about process
// zero, it addresses **every process in the caller's process group**. Signal 0 makes that harmless
// today - it would simply answer "alive" forever - which is precisely why the guard is explicit
// rather than relied upon.
//
// # One is a pid, and watching it is a no-op
//
// A pid of 1 is accepted and starts a real watcher that will never fire, because init and launchd
// outlive everything. That is left alone rather than guarded: watching a process that cannot die is
// exactly equivalent to not watching, so the outcome is the one a caller passing 1 by mistake wanted
// least but suffers not at all from. Recorded so the next reader does not take its absence from the
// guard above for an oversight.
package parent

import (
	"context"
	"errors"
	"syscall"
	"time"
)

// DefaultEvery is how often the bridge asks after its parent.
//
// Two seconds. The check is a syscall that touches no memory of the target process, so the cost is
// irrelevant and the number is chosen for the other end: it bounds how long a crashed app can leave
// a port open. Seconds rather than minutes because that port faces the internet; seconds rather than
// milliseconds because nothing is gained by noticing sooner than a person could react.
const DefaultEvery = 2 * time.Second

// Watch calls gone, once, when pid stops naming a running process.
//
// It returns immediately; the polling happens in a goroutine that stops when ctx is done or when it
// has called gone. A pid of zero or less starts nothing at all - see the package comment for why
// that guard is not merely a validation.
//
// **gone is expected to shut the bridge down through the same context cancellation a SIGTERM uses,
// not to call os.Exit.** The difference is visible to the owner: the graceful path closes the
// listener and puts a window this bridge resized back the way it found it, and an exit in here would
// skip both and leave their screen wrong.
func Watch(ctx context.Context, pid int, every time.Duration, gone func()) {
	if pid <= 0 {
		return
	}
	// A non-positive interval would panic inside NewTicker, in a goroutine, taking down the process
	// this package exists to shut down cleanly. Substituting the default is the smaller wrong.
	if every <= 0 {
		every = DefaultEvery
	}

	tick := time.NewTicker(every)
	// **Read here, in the caller's goroutine, and not from inside the loop.** The watcher outlives
	// the call that started it, so a package variable read from in there is read at a time nobody
	// chose - which is a data race against a test replacing the seam, and, more to the point, means
	// the behaviour of a running watcher could change underneath it. Captured once, what this
	// watcher does is fixed at the moment it was asked for.
	probe := kill
	go func() {
		defer tick.Stop()
		watch(ctx, pid, probe, tick.C, gone)
	}()
}

// watch is the loop, with the ticks supplied rather than made here.
//
// The channel is a parameter for one reason: **the race in the select below cannot be provoked by a
// test that owns only a clock.** A test can cancel the context and wait for a real ticker, but by the
// time the tick arrives the cancellation has long since been the only ready case, so the select is
// deterministic and the interesting arm is never taken. Handed the channel, a test can make BOTH
// cases ready before the loop runs, which is the only way to observe the thing the guard below
// exists for. Watch passes a real ticker and nothing else calls this.
func watch(ctx context.Context, pid int, probe killFunc, tick <-chan time.Time, gone func()) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick:
			if !alive(probe, pid) {
				// **Both cases can be ready at once, and select then picks at random.** A shutdown
				// that coincides with the parent's death would otherwise announce "parent process
				// is gone; exiting" on a context that was already cancelled by a SIGTERM - harmless,
				// because stop() is idempotent, and untrue, which is worse: somebody debugging a
				// shutdown reads that line as evidence.
				if ctx.Err() != nil {
					return
				}
				gone()
				return
			}
		}
	}
}

// killFunc is the shape of [syscall.Kill]: ask about a pid, get an errno or nil.
type killFunc func(pid int, sig syscall.Signal) error

// kill is [syscall.Kill], indirected so that a test can answer for the kernel.
//
// **The seam is here, at the syscall, and not one level up at the rule.** Putting it at the rule -
// exporting the classifier and testing it directly - looks equivalent and is not: it leaves nothing
// tying the rule to the code that runs. `alive` could be rewritten to compare the error against nil
// itself, and a table test over the extracted classifier would keep passing while the bridge quit on
// every EPERM. The only test that would catch it is the one that sends a real signal to pid 1, and
// that test skips under root - so on a root runner the package would go green over a broken
// classifier. Faking the syscall instead means one test pins the rule AND its call site, on every
// machine, whoever is running it.
//
// The cost is one indirect call every two seconds.
var kill killFunc = syscall.Kill

// alive reports whether pid still names a process.
//
// Signal 0 performs the permission checks and delivers nothing. ESRCH is the only answer that means
// the process is gone: nil means it exists and this user may signal it, EPERM means it exists and
// belongs to somebody else, and anything else is an answer this package does not understand and will
// not shut the bridge down on the strength of. See the package comment for why the direction of that
// last clause is the one that matters.
func alive(probe killFunc, pid int) bool {
	return !errors.Is(probe(pid, 0), syscall.ESRCH)
}
