# agterm-remote — review profile

## What this is

Two programs, one repository, Apache-2.0, one maintainer. `bridge/` is a Go daemon (zero third-party
dependencies, Go 1.24) that is embedded in `mac/`, a SwiftUI/AppKit menu-bar app which runs it as a
child process; `app/` is an Android terminal client (Kotlin, minSdk 34). The phone shows the sessions
of agterm — somebody else's macOS terminal — reached over the bridge's control socket. The phone
pairs with the Mac by scanning ONE QR code. There is no certificate authority: each side pins the
exact bytes of one peer certificate; the phone's key lives in the hardware-backed Android Keystore
and never leaves it. The wire format of that code is defined once by golden vectors in `wire/` and
decoded by three readers (Go, Kotlin, Swift). Enrolment and the API are separated by ALPN:
`agterm/enroll-1` (no client certificate, offered only while an enrolment window is open) versus
`agterm/api-1` (requires the pinned client certificate). mTLS runs INSIDE an HTTP Upgrade so a
TLS-terminating router or proxy in the path cannot strip the client certificate.

## What a real failure looks like here

- Any path by which a connection with no pinned client certificate can reach an API verb.
- An enrolment token that is reusable, outlives its window, or touches disk.
- The three readers of the QR payload disagreeing on any input — accept or reject.
- A failure on the phone reported under the wrong arm: "your Mac is not reachable" for something
  that answered and refused, or the reverse. The app's discipline is that it never guesses a cause.
- An address edit on the phone that touches the pinned certificate or the phone's key.
- A bridge that can outlive the Mac app, or a listener that stays up after the owner quits.
- An anonymous caller who can make the bridge write a log line per request, or allocate unboundedly.
- Anything personal in the tree: an address, hostname, IP, token, key, keystore, `/Users/<name>`
  path, or Cyrillic. The guards in `scripts/check-*.sh` enforce SHAPES; a proper noun (a login, a
  machine name) is invisible to them and is worth a finding on sight.
- A CI or release step that can go green without doing what its name says — this project has been
  bitten repeatedly by "success by not running" (an unsigned release bundle, a skipped test that
  reads as passed, a verdict script that could not fail).

## Blast radius

The bridge listens on a port the owner deliberately exposes to the internet. A defect in `bridge/`
is reachable by anyone who finds the port. Release goes to Google Play's internal track and to a
GitHub Release automatically on merge to `main` via release-please; a wrong build ships.

## The reporting bar

Report a defect in executable code, a security-relevant gap, a divergence between the three wire
readers, or a claim in a comment/doc that the code beside it does not honour (this project treats
"the test exists" and "this is guarded" in a comment as a claim to verify — several were false).
Do NOT report: the length or tone of comments (see below), missing features, the use of a single
Gradle module, `internal` vs `public` in Kotlin (one module, no difference), or a test that asserts a
sentence's shape (those are deliberate). Finding nothing is a valid answer.

## Deliberate conventions

- Long doc comments citing dated incidents are the house style; they are the project's memory.
  Review their CLAIMS, not their length.
- Guards enforce shapes, not identity; the statement is in the guards' shared header.
- Every refusal the user can see must be a sentence naming an action; tests pin that property.
- Source-walk tests (reading `main.swift` etc. as text) pin WIRING, deliberately, because objects
  tested in isolation have passed while call sites bypassed them. They are weaker than driving the
  path; a finding that a wiring test can be fooled is welcome, a finding that it "parses source"
  is not.
- `direct`/`httpsInFront`/`httpsBothWays` front-door answers: the DEFAULT is `httpsBothWays` at the
  owner's instruction; do not propose moving it back.
- The Mac app starts the bridge itself whenever an address exists; there is no Start/Stop in the UI
  by design.
- `docs/superpowers/` and `.superpowers/` are build-process artefacts scheduled for deletion; ignore.
- All committed text is English; conventional commits with DCO sign-off; squash-merge to `main`.
