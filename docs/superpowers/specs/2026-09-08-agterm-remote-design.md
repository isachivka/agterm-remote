# agterm-remote — design

Date: 2026-09-08
Status: approved, not yet implemented

## 1. What this is

An open-source companion that puts a Mac's [agterm](https://github.com/umputun/agterm) sessions on an
Android phone. Two programs:

- a **macOS menu-bar app** the owner installs on the machine agterm runs on;
- an **Android app**, published on Google Play.

The phone reads and writes the same terminal sessions the owner has open on the Mac: the session and
workspace list, scrollback, typing, resizing, pane splits. Nothing else.

### The audience is agterm users, and that is a deliberate ceiling

The bridge is a client of agterm's control socket
(`~/Library/Application Support/agterm/agterm.sock`). It has no PTY of its own and spawns no shell.
Every session it shows belongs to agterm; every keystroke it sends goes through agterm.

Two consequences that must be stated in the README rather than discovered:

1. **agterm is a prerequisite that this project cannot bundle.** The macOS app ships the bridge
   inside itself, but agterm is a separate application by a different author and is installed
   separately.
2. **The control socket's shape is not under this project's control.** An agterm release can change
   it. The project pins the agterm versions it has been verified against and treats a socket change
   as a compatibility break, not a bug in the far end.

### Non-goals

- **NAT traversal.** The project does not run a relay, does not embed Tailscale or a tunnel client,
  and does not negotiate holes through anything. The owner arranges an address; see §6.
- **Android Auto.** Deliberately absent. It carries Play's Car App Quality review, and this is not a
  driving app.
- **In-app updates.** Play updates the Android app; Homebrew updates the Mac app.
- **Multiple paired phones.** One phone in v1; see §5.6.
- **Anything that is not a terminal.** No dashboards, no module grid, no service tiles, no usage
  meters.

## 2. Relationship to `beware-of-sugar` (`home-app`)

The code is derived from a private repository. **No git history is imported.** That history contains
the owner's router hostname, dial addresses, homelab documents and a GitHub token flow. The new
repository starts from one initial commit of scrubbed sources.

What carries over:

| From | To | Notes |
|---|---|---|
| `bridge/internal/{agterm,api,control,listener,pinning,frontdoor,keys,logfile,resize,styled,typing,dropoff}` | `bridge/` | mostly unchanged |
| `bridge/internal/{pairing,qr}` | `bridge/internal/enroll` | rewritten for the new payload, §5 |
| `bridge/cmd/agtermbridge` | `bridge/cmd/…` | renamed, config reworked |
| `face/` (SwiftUI) | `mac/` | renamed, onboarding added, bridge embedded |
| `app/src/**/agterm/**` | `app/` | the terminal, unchanged |
| `app/src/**/pairing/**` | `app/` | rewritten for the new flow |

What does **not** carry over, and must not appear anywhere in the new tree:

- `app/**/update/**` — the GitHub personal access token, its Keystore cipher, its token screen, and
  APK installation from private releases. Play forbids this and it is not needed.
- `app/**/car/**` (Android Auto), `app/**/limits/**`, `app/**/ui/home/**` (module grid),
  `bridge/cmd/ailimits`, `bridge/internal/limits`.
- Any address: `dial.txt` values, the KeenDNS suffix, the hard-coded host in
  `RouterCertificateTrustTest.kt`, the `GITHUB_REPO` build-config field.
- `keystore-backup/`, `local.properties`, `docs/runbooks/release-key-recovery.md`, `.claude/`,
  `.agents/`, `skills-lock.json`, `docs/req/**`, `docs/qa/**`.
- Any `self-hosted` runner label, and anything else that assumes one specific machine.

The Kotlin/Go/Swift package identifiers change from `dev.isachivka.bewareofsugar` to the new
project's namespace.

## 3. Architecture

```
Android app                            Mac app (menu bar)
 │                                      ├── onboarding + pairing UI
 │                                      ├── supervises ──► bridge (Go, embedded in the bundle)
 │                                      │                    │
 └──────────── network ─────────────────┴────────────────────┤
                                                             └──► agterm control socket
```

Three units with separate lifetimes and separate tests:

- **`bridge`** (Go) — one process, no UI. Owns the listener, the trust store, the enrollment window
  and the agterm client. Runs as a child of the Mac app and exits with it. **No launchd.**
- **`mac`** (Swift) — a menu-bar app. Owns the address, the onboarding, the QR, the bridge's
  lifecycle, and the login item. Talks to the bridge over a local control socket (the existing
  `internal/control` package).
- **`app`** (Kotlin/Compose) — the phone.

### 3.1 Network layers

Unchanged from the source project, and the layering is what makes §5 work:

```
TCP
 └─ optional outer TLS (only when the bridge terminates TLS itself)
     └─ frontdoor: HTTP Upgrade
         └─ pinned mTLS      ← the trust anchor. Everything below is authenticated.
             └─ NDJSON API
```

`frontdoor` exists because a proxy that terminates TLS (a router's own reverse proxy, Cloudflare
Tunnel, nginx) destroys any client certificate on the way through. Running mTLS *inside* an upgraded
stream means the proxy moves bytes it cannot read. Since this project tells owners to arrange
reachability with whatever tool they like, a proxy is the common case, not the exotic one — `frontdoor`
carries over unchanged.

## 4. Trust model

No certificate authority. Each side holds one self-signed certificate and pins the exact bytes of
exactly one peer certificate. There is nothing to sign and nothing that can be tricked into signing.

- The **phone's** key is generated in the Android Keystore, hardware-backed where StrongBox or a TEE
  is available, and **never leaves the device**. Only its certificate is transmitted.
- The **bridge's** key lives in `~/.config/agterm-remote/`, mode 0600.
- Certificates are public. The leg that crosses between devices must be *authentic*, not
  *confidential* — which is exactly what a QR code on the owner's own screen provides.

## 5. Pairing

One scan on the phone. Zero clicks on the Mac beyond opening the pairing window.

### 5.1 The QR payload

A fixed binary record, base64 (standard alphabet, padded) so it survives being copied, pasted or
typed. About 135 characters, which is a materially smaller and more scannable symbol than the source
project's payload, which carried a whole certificate.

| field | width | notes |
|---|---|---|
| version | `uint8` | bumped only on an incompatible change |
| host | `uint16` length + UTF-8 | no default, ever |
| port | `uint16` | |
| bridge certificate fingerprint | 32 bytes | SHA-256 of the DER |
| enrollment token | 32 bytes | `crypto/rand` |
| expiry | `uint32` | Unix seconds |

The fingerprint rather than the certificate: it is a third of the size, and the full DER arrives over
the authenticated channel in step 4 below.

**The wire format is pinned by shared golden vectors.** The source project also had two
implementations of its payload — Kotlin's decoder and Go's encoder — and they could drift, with the
failure showing up as a phone that will not pair rather than as a build error. The fix here is cheap
and needs no code generator: the format is specified once, in `bridge/internal/enroll/format.go`, and a
checked-in file of **golden vectors** (payload bytes with their decoded fields) is read by both the Go
and the Kotlin test suites. A change on one side that is not made on the other fails a test in CI.

### 5.2 The flow

1. **Mac** — owner opens the pairing window. The bridge opens an *enrollment window*: a fresh token,
   5-minute TTL, held in memory only. The QR is rendered from the record above.
2. **Phone** — scans, dials `host:port`, upgrades, and performs the inner TLS handshake presenting
   **no client certificate** and ALPN `agterm/enroll-1`. It verifies the server certificate against
   the fingerprint from the QR. A man in the middle fails here, before any secret is offered.
3. **Phone** — generates its keypair in the Keystore, self-signs, and sends
   `{token, certificate DER}`.
4. **Bridge** — compares the token in constant time. On success: pins the phone's certificate,
   closes the window, burns the token, and returns its own certificate DER plus a display name for
   the Mac.
5. **Phone** — pins those exact bytes, stores `host:port`, and connects normally with ALPN
   `agterm/api-1`. The terminal opens.

The Mac shows "paired with `<fingerprint>`" when step 4 completes. The phone shows the same
fingerprint on its settings screen, so a human who wants to compare them can, and a human who does not
is not asked to.

### 5.3 ALPN, not a URL path

A connection declares its purpose in the handshake:

| ALPN | client certificate | reaches |
|---|---|---|
| `agterm/api-1` | required, must be the pinned one | the API |
| `agterm/enroll-1` | must be absent | the enrollment handler, only while a window is open |

The invariant a test holds: **a connection with no client certificate cannot reach the API under any
sequence of actions.** With ALPN this is structural — the two protocols are separate branches decided
during the handshake. With a URL path inside one authenticated stream it would be a property of
routing code, which is a weaker thing to assert.

When no window is open, `agterm/enroll-1` is not offered, and a client requesting only it fails
protocol negotiation — the same refusal an unauthenticated caller already gets, through the existing
single refusal path.

### 5.4 The enrollment window is the only thing that softens the door

Everything the source project holds about unauthenticated callers still holds: bounded work rather
than refused service, one refusal path, no per-request log line, no disk write. The window adds
exactly one capability to an anonymous caller, and only while a human is looking at a QR code:

- it can complete a TLS handshake and send one bounded message;
- a wrong token closes the window (max 5 attempts), so the flow is not a guessing game — and a
  32-byte token would not be guessable regardless;
- the window closes on success, on expiry, and when the pairing panel is closed.

The token never touches disk. A crash loses it, which is correct: the QR on screen is stale and the
owner opens a new one.

### 5.5 Address changes must not break pairing

The pinned identity is independent of the address. A dynamic IP that changes is an address problem,
not a trust problem, so:

- the phone can edit `host:port` in settings while keeping its pinned peer;
- re-scanning a fresh QR is also fine — it is one scan, and re-enrolment replaces the address.

### 5.6 One phone

v1 pins one peer. Pairing a second phone replaces the first. The Mac's menu shows the paired
fingerprint and an "Unpair" item. Multiple phones is a plausible v2 and the trust store should be a
list from the start so that it is a UI change rather than a format change.

### 5.7 The camera must actually be tested

In the source project the viewfinder is written, covered by tests over synthetic frames, and **has
never been run against a real lens** — acceptable there, because the working route was a file over
AirDrop. Here there is no other route. End-to-end scanning on an emulator with a virtual camera, and
at least one run against real hardware, are acceptance criteria, not wishes.

A **paste-the-code-as-text** field is also required. A scanner that will not read leaves the owner
with no way forward, and the payload is base64 text precisely so that it can be typed.

## 6. Reachability is the owner's problem, stated plainly

The onboarding does not integrate with any tunnel or DNS provider. It explains what is needed and
lists what people use:

- port forward + `IP:PORT`, when the IP does not change;
- port forward + DDNS name, when it does;
- Tailscale, WireGuard, Cloudflare Tunnel, or any reverse proxy — `frontdoor` survives all of them.

**No "test this address" button.** The Mac cannot honestly test its own public address: hairpin NAT
makes the answer wrong in both directions. Instead the address is marked *unproven* until a phone
connects through it, and the pairing scan is what proves it. A button that is sometimes wrong is worse
than no button.

## 7. Onboarding

### macOS, first launch

1. **Is agterm there?** Check the control socket. If not, say so and link to agterm; do not continue.
2. **The address.** The text from §6 and one `host:port` field.
3. **The QR.** Shown until scanned or until the panel is closed.

Afterwards the menu bar carries: bridge status, the paired fingerprint, "Pair a phone…", "Unpair",
the address, "Open at login", "Quit".

### Android, first launch

Camera permission → viewfinder → (fallback: paste the code) → paired → session list.

## 8. Packaging

- **The Go bridge is a universal binary inside the app bundle** (`arm64` + `x86_64` via `lipo`), in
  `Contents/Resources/`. The Mac app launches it as a child process and terminates it on quit. One
  thing to install, no daemon left behind, no plist.
- **Distribution: Homebrew cask is the documented route** (`brew install --cask agterm-remote`, from a
  tap in the same GitHub account). A `.dmg` is also attached to each GitHub release.
- **Not notarized.** There is no Apple Developer account, and the README says so in a section that
  explains what Gatekeeper will show and why, rather than telling people to click through a warning
  they do not understand. The release pipeline is built so that signing and notarization can be
  switched on later by adding secrets, without restructuring.
- **Android: Google Play**, `.aab`, signed in CI.

## 9. Repository and process

Public repository, development through pull requests.

- `main` is protected: no direct pushes, required status checks, `CODEOWNERS`, squash merge.
- Conventional commits, enforced in CI. Versioning by `release-please`.
- Contributions under **DCO** (`Signed-off-by`), not a CLA.
- **Apache-2.0.** The explicit patent grant is worth having in a project that ships a network
  protocol and a trust model.
- `LICENSE`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md` (carrying the threat model of
  §10), issue and PR templates, Dependabot, CodeQL.
- **Naming.** The repository is `agterm-remote`. The Play listing is worded as *"Remote for agterm"*
  rather than as an official agterm product, because it is not one.

### CI

GitHub Actions on standard runners, which are free for public repositories. No self-hosted runner.

| workflow | runner | does |
|---|---|---|
| `ci.yml` | `ubuntu-latest` | Go build/vet/test/`golangci-lint`; Android assemble, unit tests, lint; instrumented tests on an emulator (KVM is available) |
| `ci.yml` | `macos-latest` | Swift build and tests; bundle assembly |
| `release.yml` | both | `.aab` to Play, `.dmg` to the GitHub release, cask update |

**Secrets run only on `push` and on tags.** `pull_request_target` is not used anywhere — it is the
standard way secrets leak out of a public repository through a fork.

## 10. Threat model, summarized

| Adversary | Outcome |
|---|---|
| Anyone on the internet who finds the port | TLS handshake failure; bounded work; no log line per attempt; no disk write |
| A proxy or router in the path | Moves bytes it cannot read; mTLS runs inside the upgraded stream |
| A man in the middle during pairing | Fails at step 2 — the server certificate does not match the fingerprint from the QR |
| Someone who photographs the QR | Has 5 minutes and one shot at a token that dies on first use, and must also reach the address |
| A stolen phone | Holds a hardware-backed key it cannot export; the owner unpairs from the Mac |
| A stolen laptop | Holds everything. Out of scope, as it is for any terminal on that machine |

## 11. Risks

1. **agterm's control socket can change.** Pin verified versions; treat a change as a compatibility
   break and say so in the README.
2. **The camera is unproven.** §5.7.
3. **Play review of a remote-terminal app.** The listing must be explicit that it connects only to a
   machine the user controls and pairs with. No dynamic code loading, no accessibility abuse, no
   background data collection — none of which this app does, but the listing has to say so.
4. **Gatekeeper on an unnotarized app** is a real drop-off point in the funnel. Homebrew mitigates it;
   nothing removes it short of an Apple account.

## 12. Acceptance criteria

- A person with agterm and a reachable address installs the Mac app, follows the onboarding, scans
  one QR with the phone, and reaches a live terminal session. No file transfer, no second scan, no
  drag-and-drop, no terminal commands.
- A test asserts a certificate-less connection cannot reach the API.
- A test asserts the enrollment token is single-use, expires, and is never written to disk.
- The QR is scanned by a real camera on real hardware at least once, and the distance it worked at is
  recorded.
- No address, token, keystore, or personal path appears anywhere in the repository.
