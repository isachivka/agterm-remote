package parent

// This file is compiled only under `go test`, so the seam below is not in the bridge binary and
// cannot be reached by anything outside this package's own tests.

// AliveFrom exposes the errno rule - the one decision this package makes - so it can be tested with
// each answer the kernel can give, rather than only with the answer the kernel happens to give to
// whoever is running the tests.
//
// A seam rather than more real signals, for the same reason internal/trust has one: a test that
// reaches the interesting branch only under a particular euid measures the environment's opinion of
// the person running it, and it quietly stops testing anything at all in the environments where that
// opinion differs. `kill(1, 0)` answers EPERM as an ordinary user and nil as root, so the EPERM arm -
// the arm the whole package turns on - is unreachable in a root container. This is reachable
// everywhere, always, and it is the rule itself rather than a proxy for it.
var AliveFrom = aliveFrom
