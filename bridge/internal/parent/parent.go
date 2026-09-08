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

	go func() {
		tick := time.NewTicker(every)
		defer tick.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-tick.C:
				if !alive(pid) {
					gone()
					return
				}
			}
		}
	}()
}

// alive reports whether pid still names a process.
//
// Signal 0 performs the permission checks and delivers nothing. ESRCH is the only answer that means
// the process is gone; EPERM means it exists and belongs to somebody else, and anything else is an
// answer this package does not understand and will not act on. See the package comment.
func alive(pid int) bool {
	return !errors.Is(syscall.Kill(pid, 0), syscall.ESRCH)
}
