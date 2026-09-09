# agterm-remote Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship an open-source, Play-published Android terminal for agterm, paired to a Mac by scanning one QR code.

**Architecture:** A Go bridge speaks agterm's control socket and listens for the phone behind pinned mTLS; it is a universal binary embedded in a SwiftUI menu-bar app that owns onboarding, the address, and the pairing QR. The Android app holds a hardware-backed key, enrolls itself over a TLS channel it authenticates from the QR's fingerprint, and then talks the existing NDJSON API. Code is ported from the private `beware-of-sugar` repository with no git history and with every personal value stripped.

**Tech Stack:** Go 1.24 (zero third-party dependencies), Kotlin/Jetpack Compose (AGP, JDK 21), Swift 6 / SwiftPM (macOS 14+), GitHub Actions on public standard runners.

**Spec:** `docs/superpowers/specs/2026-09-08-agterm-remote-design.md`

## Global Constraints

Every task's requirements implicitly include this section.

- **Repository:** `isachivka/agterm-remote`, **public**, Apache-2.0, DCO sign-off, PR-only into `main`, squash merge, conventional commits.
- **Go module:** `github.com/isachivka/agterm-remote/bridge`. Go 1.24. **Zero third-party dependencies** — CI fails on any `require` block.
- **Android:** namespace and `applicationId` `dev.isachivka.agtermremote`; `minSdk 34`, `targetSdk 37`, `compileSdk 37`; JDK 21.
- **macOS:** SwiftPM package `AgtermRemote`, targets `AgtermRemoteCore` (library, tested) + `AgtermRemote` (menu-bar executable); `platforms: [.macOS(.v14)]`; bundle id `dev.isachivka.agtermremote`.
- **No launchd anywhere.** The bridge is a child process of the Mac app.
- **Nothing personal in the tree.** No address, hostname, IP, token, private key, keystore, or `/Users/<name>` path. Enforced by `scripts/check-*.sh` in CI, which are required checks.
- **All committed text is English**, including comments and commit messages.
- **The source repository is `~/pets/home-app`.** It is read-only for this work: never commit to it, never push it.

### Deviation from the spec, decided here

The spec left the bridge's configuration unspecified. This plan gives the bridge **no config file**: it
takes `--listen`, `--socket`, `--state-dir`, `--log`, `--parent-pid` as arguments, and the Mac app is
the only thing that launches it. The state directory holds identity and paired peers, nothing else.
Rationale: the Mac app already owns the address, and a config file would make two sources of truth
that can disagree.

---

## File Structure

```
agterm-remote/
├── LICENSE                       Apache-2.0
├── README.md                     install → onboarding → pair → use; the Gatekeeper section
├── CONTRIBUTING.md               DCO, conventional commits, how to run each suite
├── CODE_OF_CONDUCT.md            Contributor Covenant 2.1
├── SECURITY.md                   threat model (spec §10), how to report
├── .github/
│   ├── workflows/ci.yml          guards + go + android + swift
│   ├── workflows/release.yml     release-please, Play, dmg, cask
│   ├── workflows/codeql.yml
│   ├── dependabot.yml
│   ├── CODEOWNERS
│   ├── pull_request_template.md
│   └── ISSUE_TEMPLATE/{bug.yml,feature.yml,config.yml}
├── scripts/
│   ├── check-no-tokens.sh        ported
│   ├── check-no-credentials.sh   ported
│   ├── check-no-private-keys.sh  ported
│   └── check-no-addresses.sh     NEW — hostnames, IPs, /Users paths
├── wire/
│   └── enroll-payload-vectors.json   golden vectors, read by Go AND Kotlin tests
├── bridge/                       Go
│   ├── cmd/agterm-remote-bridge/main.go
│   └── internal/
│       ├── agterm/ api/ control/ frontdoor/ keys/ listener/ logfile/ pinning/ resize/ styled/ dropoff/
│       ├── enroll/               NEW — payload format, window, handler
│       └── trust/                NEW — the peer list on disk
├── mac/                          Swift
│   ├── Package.swift
│   ├── Sources/AgtermRemoteCore/ Address, BridgeProcess, MenuModel, Onboarding, PairingPanelModel, QRPayload…
│   ├── Sources/AgtermRemote/     AppKit shell, windows
│   ├── Tests/AgtermRemoteCoreTests/
│   └── scripts/bundle.sh         builds the universal Go binary INTO Contents/Resources
└── app/                          Android
    └── src/main/java/dev/isachivka/agtermremote/{agterm,pairing,ui,wire}
```

---

## Phase A — Repository foundation

The point of doing this first: the scrub guards must exist **before** any ported code lands, so a
personal value can never reach `main` even once.

### Task 1: Create the public repository and its skeleton

**Files:**
- Create: `LICENSE`, `README.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `.gitignore`, `.editorconfig`
- Create: `.github/CODEOWNERS`, `.github/pull_request_template.md`, `.github/ISSUE_TEMPLATE/{bug.yml,feature.yml,config.yml}`

**Interfaces:**
- Produces: a public repo at `github.com/isachivka/agterm-remote` with `main` as the default branch.

- [ ] **Step 1: Create the repository**

```bash
cd ~/pets/agterm-remote
gh repo create isachivka/agterm-remote --public --source=. --remote=origin \
  --description "Your agterm sessions on your phone. Pair by scanning one QR code."
```

- [ ] **Step 2: Write LICENSE**

```bash
curl -fsSL https://www.apache.org/licenses/LICENSE-2.0.txt -o LICENSE
```

- [ ] **Step 3: Write `.gitignore`**

```
.DS_Store
build/
.build/
*.xcuserstate
local.properties
.gradle/
.kotlin/
app/build/
bridge/bridge
mac/.build/
dist/
```

- [ ] **Step 4: Write `CONTRIBUTING.md`**

Must state, concretely: fork → branch → PR; every commit carries `Signed-off-by` (`git commit -s`);
conventional commit types (`feat|fix|docs|chore|refactor|test|ci|build`); and the three commands that
must pass locally — `cd bridge && go test ./...`, `./gradlew testDebugUnitTest lintDebug`,
`cd mac && swift test`.

- [ ] **Step 5: Write `CODE_OF_CONDUCT.md`**

Contributor Covenant 2.1 verbatim, with the maintainer's contact for reports.

- [ ] **Step 6: Write `SECURITY.md`**

Copy the threat-model table from spec §10, state that reports go to a private security advisory
(`gh security-advisory`), and state explicitly that a stolen laptop is out of scope.

- [ ] **Step 7: Write `.github/CODEOWNERS`**

```
* @isachivka
```

- [ ] **Step 8: Write the PR and issue templates**

`pull_request_template.md` asks for: what changed, why, how it was tested, and a checkbox for DCO.
`bug.yml` requires agterm version, macOS version, Android version, and what the menu-bar icon said.
`config.yml` sets `blank_issues_enabled: false`.

- [ ] **Step 9: Commit**

```bash
git add -A && git commit -s -m "chore: repository skeleton, licence and community files"
```

### Task 2: The scrub guards

These are the only tasks in the plan whose failure is unrecoverable if deferred: once a secret is in
git history, removing it means rewriting a public repository.

**Files:**
- Create: `scripts/check-no-tokens.sh`, `scripts/check-no-credentials.sh`, `scripts/check-no-private-keys.sh`, `scripts/check-no-addresses.sh`
- Test: `scripts/tests/guards_test.sh`

**Interfaces:**
- Produces: four scripts, each exiting non-zero on a hit, each taking no arguments and scanning `git ls-files`.

- [ ] **Step 1: Port the three existing guards**

```bash
cp ~/pets/home-app/scripts/check-no-tokens.sh \
   ~/pets/home-app/scripts/check-no-credentials.sh \
   ~/pets/home-app/scripts/check-no-private-keys.sh scripts/
chmod +x scripts/*.sh
```

Read each one and remove any reference to `beware-of-sugar` paths.

- [ ] **Step 2: Write the failing test for the new address guard**

`scripts/tests/guards_test.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
cd "$tmp" && git init -q .

fail=0
check() { # name, content, expected exit
  printf '%s' "$2" > candidate.txt && git add -A
  if "$OLDPWD/scripts/check-no-addresses.sh" >/dev/null 2>&1; then got=0; else got=1; fi
  [ "$got" = "$3" ] || { echo "FAIL $1: expected $3 got $got"; fail=1; }
}

check "bare ipv4"       "connect to 10.11.12.13 now"       1
check "ipv4 with port"  "host=10.11.12.14:8443"            1
check "home path"       "/Users/somebody/.config/x"        1
check "ddns name"       "laptop.mynetname.net"             1
check "clean text"      "the owner supplies host and port" 0
check "placeholder"     "example: HOST:PORT"               0
# Documentation ranges (RFC 5737) are ALLOWED: the README and the onboarding copy need an
# example address, and refusing them would push writers towards a real one.
check "documentation ip" "for example 203.0.113.5:8443"    0
check "loopback"         "listens on 127.0.0.1"            0
exit "$fail"
```

- [ ] **Step 3: Run it and watch it fail**

Run: `bash scripts/tests/guards_test.sh`
Expected: FAIL — `check-no-addresses.sh` does not exist.

- [ ] **Step 4: Write `scripts/check-no-addresses.sh`**

```bash
#!/usr/bin/env bash
# Refuses any committed hostname, IP address or personal path.
#
# The rule this enforces is spec §12: the address a phone dials belongs to the person running the
# bridge and must never be in the repository. A previous project kept the rule in prose, and it was
# violated in three files before anyone noticed.
set -euo pipefail

patterns=(
  '\b(([0-9]{1,3}\.){3}[0-9]{1,3})\b'          # IPv4
  '/Users/[a-z]'                               # a personal home directory
  '\b[a-z0-9-]+\.(mynetname\.net|keenetic\.(pro|link|name)|duckdns\.org|ddns\.net|no-ip\.(org|com))\b'
)
allow='(0\.0\.0\.0|127\.0\.0\.1|255\.255\.255\.0|1\.2\.3\.4|203\.0\.113\.|198\.51\.100\.|192\.0\.2\.)'

status=0
while IFS= read -r file; do
  case "$file" in scripts/check-no-addresses.sh|scripts/tests/*|LICENSE) continue ;; esac
  [ -f "$file" ] || continue
  for p in "${patterns[@]}"; do
    if hits="$(grep -nEI "$p" "$file" | grep -vE "$allow" || true)"; [ -n "$hits" ]; then
      echo "$file: address-like content"; echo "$hits"; status=1
    fi
  done
done < <(git ls-files)
exit "$status"
```

- [ ] **Step 5: Run the test until it passes**

Run: `bash scripts/tests/guards_test.sh`
Expected: no output, exit 0.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -s -m "ci: guards that refuse committed secrets and addresses"
```

### Task 3: CI

**Files:**
- Create: `.github/workflows/ci.yml`, `.github/workflows/codeql.yml`, `.github/dependabot.yml`

**Interfaces:**
- Produces: required check names `guards`, `bridge`, `android`, `mac`.

- [ ] **Step 1: Write `.github/workflows/ci.yml`**

```yaml
name: CI
on:
  pull_request:
  push:
    branches: [main]
permissions:
  contents: read
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}

jobs:
  guards:
    name: guards
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - run: bash scripts/tests/guards_test.sh
      - run: ./scripts/check-no-tokens.sh
      - run: ./scripts/check-no-credentials.sh
      - run: ./scripts/check-no-private-keys.sh
      - run: ./scripts/check-no-addresses.sh

  bridge:
    name: bridge
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: bridge } }
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-go@v5
        with: { go-version: '1.24', cache-dependency-path: bridge/go.sum }
      - name: Refuse any third-party dependency
        run: |
          if grep -q '^require' go.mod; then echo "third-party dependency added"; exit 1; fi
      - run: test -z "$(gofmt -l .)"
      - run: go vet ./...
      - run: go test ./...

  android:
    name: android
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest

  mac:
    name: mac
    runs-on: macos-latest
    defaults: { run: { working-directory: mac } }
    steps:
      - uses: actions/checkout@v7
      - run: swift build
      - run: swift test
```

- [ ] **Step 2: Write `.github/workflows/codeql.yml`**

```yaml
name: CodeQL
on:
  pull_request:
  push: { branches: [main] }
  schedule: [{ cron: '0 3 * * 1' }]
permissions: { contents: read, security-events: write }
jobs:
  analyze:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        language: [go, java-kotlin]
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        if: matrix.language == 'java-kotlin'
        with: { distribution: temurin, java-version: '21' }
      - uses: github/codeql-action/init@v3
        with: { languages: ${{ matrix.language }} }
      - uses: github/codeql-action/autobuild@v3
      - uses: github/codeql-action/analyze@v3
```

- [ ] **Step 3: Write `.github/dependabot.yml`**

```yaml
version: 2
updates:
  - package-ecosystem: github-actions
    directory: /
    schedule: { interval: weekly }
  - package-ecosystem: gradle
    directory: /
    schedule: { interval: weekly }
  - package-ecosystem: gomod
    directory: /bridge
    schedule: { interval: weekly }
```

- [ ] **Step 4: Push a branch and confirm the four checks run**

```bash
git checkout -b ci/foundation && git add -A && git commit -s -m "ci: build, test and lint on public runners"
git push -u origin ci/foundation && gh pr create --fill && gh pr checks --watch
```

Expected: `android`, `bridge`, `mac` fail (nothing to build yet), `guards` passes. That is the correct
state; the next tasks turn them green.

- [ ] **Step 5: Merge once Phase B and D land**

Leave the PR open, or merge with the jobs allowed to fail by making them `continue-on-error: true` in
this commit and removing that flag in Task 8. Prefer the second — an open PR blocking every later task
is worse than a temporary flag with an explicit removal step.

### Task 4: Branch protection and versioning

**Files:**
- Create: `release-please-config.json`, `.release-please-manifest.json`, `.github/workflows/commit-types.yml`

- [ ] **Step 1: Protect `main`**

```bash
gh api -X PUT repos/isachivka/agterm-remote/branches/main/protection \
  -f 'required_status_checks[strict]=true' \
  -f 'required_status_checks[contexts][]=guards' \
  -f 'required_status_checks[contexts][]=bridge' \
  -f 'required_status_checks[contexts][]=android' \
  -f 'required_status_checks[contexts][]=mac' \
  -F 'enforce_admins=false' \
  -F 'required_pull_request_reviews[required_approving_review_count]=0' \
  -F 'restrictions=null' \
  -F 'allow_force_pushes=false' -F 'allow_deletions=false'
gh api -X PATCH repos/isachivka/agterm-remote \
  -F allow_squash_merge=true -F allow_merge_commit=false -F allow_rebase_merge=false \
  -F delete_branch_on_merge=true
```

- [ ] **Step 2: Port `release-please` config**

Copy `release-please-config.json` and `.release-please-manifest.json` from `~/pets/home-app`, set the
package path to `.`, the version file to `app/build.gradle.kts`, and reset the manifest to `0.1.0`.

- [ ] **Step 3: Port the commit-type check**

Copy `.github/workflows/commit-types.yml`, strip references to the old repository, and add a step that
fails a commit missing `Signed-off-by`.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -s -m "ci: branch protection, release-please and commit hygiene"
```

---

## Phase B — The bridge

### Task 5: Port the Go packages

**Files:**
- Create: `bridge/go.mod`, `bridge/internal/{agterm,api,control,dropoff,frontdoor,keys,listener,logfile,pinning,resize,styled}/**`
- Create: `bridge/cmd/agterm-remote-bridge/main.go`

**Interfaces:**
- Produces: `pinning.Identity`, `pinning.Mint(commonName string, validFor time.Duration) (Identity, error)`, `pinning.Fingerprint(*x509.Certificate) string`, `pinning.LoadIdentity(Identity) (tls.Certificate, error)`, `pinning.LoadPeer([]byte) (*x509.Certificate, error)`, `pinning.ServerConfig(own tls.Certificate, pinnedClient *x509.Certificate) *tls.Config`, `pinning.ClientConfig(own tls.Certificate, pinnedServer *x509.Certificate) *tls.Config`, `listener.New(tlsConfig *tls.Config, handler listener.Requests, peerIsCaller bool) *listener.Server`, `frontdoor.Listen(net.Listener) net.Listener`, `control.Listen(ctx context.Context, dir string, fit control.Fit) (net.Listener, error)`, `agterm.DefaultSocketPath() string`, `api.New(*agterm.Client, string) *api.Handler`.

- [ ] **Step 1: Copy the packages that carry over unchanged**

```bash
mkdir -p bridge/internal
cd ~/pets/agterm-remote
for p in agterm api control dropoff frontdoor keys listener logfile pinning resize styled; do
  cp -R ~/pets/home-app/bridge/internal/$p bridge/internal/$p
done
cp ~/pets/home-app/bridge/go.mod bridge/go.mod
```

- [ ] **Step 2: Rewrite the module path**

```bash
cd bridge
sed -i '' 's|dev.isachivka.bewareofsugar/bridge|github.com/isachivka/agterm-remote/bridge|g' \
  go.mod $(find . -name '*.go')
sed -i '' '1s|.*|module github.com/isachivka/agterm-remote/bridge|' go.mod
```

- [ ] **Step 3: Delete what does not carry over**

```bash
rm -rf internal/limits internal/pairing internal/qr
grep -rl 'UseLimits\|internal/limits' . | xargs sed -i '' '/UseLimits/d;/internal\/limits/d'
```

Then remove the now-unused `limits` field and its method from `internal/api`, and delete
`internal/api/limits.go`, `internal/api/limits_test.go`.

- [ ] **Step 4: Strip the source project's history from comments**

Every `REQ-00NN`, `PLAN-00NN`, `docs/qa/...` and `docs/req/...` reference in a Go comment points at
documents that do not exist here and will read as rot. Replace each with the reasoning it stood for,
or delete the sentence. Find them:

```bash
grep -rn 'REQ-0\|PLAN-0\|docs/qa\|docs/req' --include='*.go' .
```

Also grep for the private repository's own name and for the brand of the owner's router, neither of
which is written out here for the reason this step exists. The router's brand becomes "a
TLS-terminating proxy" — the mechanism is general, only the example was specific.

- [ ] **Step 5: Write the new `main.go`**

Arguments only, no config file:

```go
func main() {
	listen := flag.String("listen", "", "host:port to listen on (required)")
	socket := flag.String("socket", "", "agterm control socket; empty means the default")
	stateDir := flag.String("state-dir", "", "directory holding identity and paired peers (required)")
	logPath := flag.String("log", "", "log file; empty means stderr")
	parent := flag.Int("parent-pid", 0, "exit when this pid goes away; 0 disables")
	flag.Parse()
	if *listen == "" || *stateDir == "" {
		fmt.Fprintln(os.Stderr, "--listen and --state-dir are required")
		os.Exit(2)
	}
	if err := run(*listen, *socket, *stateDir, *logPath, *parent); err != nil {
		log.Fatal(err)
	}
}
```

`run` keeps the body of the source project's `run`, minus `Config`, minus `UseLimits`, plus the parent
watchdog from Task 7.

- [ ] **Step 6: Build and test**

Run: `cd bridge && gofmt -l . && go vet ./... && go test ./...`
Expected: no `gofmt` output, no vet output, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -s -m "feat(bridge): port the agterm bridge, without limits or pairing"
```

### Task 6: The peer list on disk

Replaces the single pinned peer with a list, so that "more than one phone" is later a UI change rather
than a file-format change (spec §5.6).

**Files:**
- Create: `bridge/internal/trust/trust.go`
- Test: `bridge/internal/trust/trust_test.go`

**Interfaces:**
- Produces:
  - `type Peer struct { Fingerprint string; CertificateDER []byte; Name string; PairedAt time.Time }`
  - `type Store struct { … }`
  - `func Open(dir string) (*Store, error)`
  - `func (s *Store) Peers() []Peer`
  - `func (s *Store) Add(p Peer) error`
  - `func (s *Store) Replace(p Peer) error` — the v1 behaviour: one peer, added one drops the rest
  - `func (s *Store) Remove(fingerprint string) error`
  - `func (s *Store) Certificates() []*x509.Certificate`

- [ ] **Step 1: Write the failing test**

```go
func TestReplaceKeepsExactlyOnePeer(t *testing.T) {
	dir := t.TempDir()
	s, err := trust.Open(dir)
	if err != nil { t.Fatal(err) }
	first := trust.Peer{Fingerprint: "aa", CertificateDER: []byte{1}, Name: "one", PairedAt: time.Unix(1, 0)}
	second := trust.Peer{Fingerprint: "bb", CertificateDER: []byte{2}, Name: "two", PairedAt: time.Unix(2, 0)}
	if err := s.Replace(first); err != nil { t.Fatal(err) }
	if err := s.Replace(second); err != nil { t.Fatal(err) }

	got := s.Peers()
	if len(got) != 1 || got[0].Fingerprint != "bb" {
		t.Fatalf("want only bb, got %+v", got)
	}
}

func TestPeersSurviveReopen(t *testing.T) {
	dir := t.TempDir()
	s, _ := trust.Open(dir)
	_ = s.Replace(trust.Peer{Fingerprint: "aa", CertificateDER: []byte{1}, Name: "one", PairedAt: time.Unix(1, 0)})

	again, err := trust.Open(dir)
	if err != nil { t.Fatal(err) }
	if len(again.Peers()) != 1 { t.Fatalf("want 1 peer after reopen, got %d", len(again.Peers())) }
}

func TestFileIsOwnerOnly(t *testing.T) {
	dir := t.TempDir()
	s, _ := trust.Open(dir)
	_ = s.Replace(trust.Peer{Fingerprint: "aa", CertificateDER: []byte{1}, PairedAt: time.Unix(1, 0)})

	info, err := os.Stat(filepath.Join(dir, "peers.json"))
	if err != nil { t.Fatal(err) }
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("peers.json must be 0600, got %v", info.Mode().Perm())
	}
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd bridge && go test ./internal/trust/ -run Test -v`
Expected: FAIL — package does not exist.

- [ ] **Step 3: Implement `trust.go`**

JSON at `<dir>/peers.json`, `0600`, written to a temp file in the same directory and renamed, so a
crash mid-write cannot leave a half-file. `Certificates()` parses each `CertificateDER` with
`x509.ParseCertificate` and drops any that no longer parses, logging a count and not an identity.

- [ ] **Step 4: Run the tests**

Run: `cd bridge && go test ./internal/trust/ -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -s -m "feat(bridge): a peer list on disk, replacing the single pinned certificate"
```

### Task 7: Exit with the parent

Without this, a crash of the Mac app leaves a bridge listening on the owner's port with no UI to stop it.

**Files:**
- Create: `bridge/internal/parent/parent.go`
- Test: `bridge/internal/parent/parent_test.go`
- Modify: `bridge/cmd/agterm-remote-bridge/main.go`

**Interfaces:**
- Produces: `func Watch(ctx context.Context, pid int, every time.Duration, gone func())`

- [ ] **Step 1: Write the failing test**

```go
func TestGoneFiresWhenTheParentIsNotThere(t *testing.T) {
	fired := make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	// pid 1 exists; a very high pid almost certainly does not.
	parent.Watch(ctx, 4194303, time.Millisecond, func() { close(fired) })
	select {
	case <-fired:
	case <-time.After(2 * time.Second):
		t.Fatal("gone never fired for an absent parent")
	}
}

func TestGoneDoesNotFireWhileTheParentLives(t *testing.T) {
	fired := make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	parent.Watch(ctx, os.Getpid(), time.Millisecond, func() { close(fired) })
	select {
	case <-fired:
		t.Fatal("gone fired while the parent was alive")
	case <-time.After(100 * time.Millisecond):
	}
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd bridge && go test ./internal/parent/ -v`
Expected: FAIL — package does not exist.

- [ ] **Step 3: Implement**

A goroutine on a ticker calling `syscall.Kill(pid, 0)`; `ESRCH` means gone. Zero pid means the watcher
is not started at all.

- [ ] **Step 4: Wire it into `main.go`**

```go
if parentPID > 0 {
    parent.Watch(ctx, parentPID, 2*time.Second, func() {
        log.Print("parent process is gone; exiting")
        stop()
    })
}
```

- [ ] **Step 5: Run the tests and commit**

Run: `cd bridge && go test ./...`

```bash
git add -A && git commit -s -m "feat(bridge): exit when the Mac app that launched it goes away"
```

### Task 8: Turn the CI jobs green

- [ ] **Step 1: Remove `continue-on-error` from the `bridge` job**
- [ ] **Step 2: Push and confirm `guards` and `bridge` pass**

Run: `gh pr checks --watch`
Expected: `guards` ✓, `bridge` ✓; `android` and `mac` still red until Phases D and E.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -s -m "ci: require the bridge job to pass"
```

---

## Phase C — Enrolment: the new pairing, bridge side

This is the only genuinely new logic in the project. Everything in this phase is test-first.

### Task 9: The QR payload format and its golden vectors

**Files:**
- Create: `bridge/internal/enroll/format.go`
- Create: `wire/enroll-payload-vectors.json`
- Test: `bridge/internal/enroll/format_test.go`

**Interfaces:**
- Produces:
  - `const Version = 1`
  - `type Payload struct { Host string; Port int; Fingerprint [32]byte; Token [32]byte; Expiry time.Time }`
  - `func Encode(p Payload) ([]byte, error)`
  - `func Decode(b []byte) (Payload, error)`
  - `func EncodeToText(p Payload) (string, error)` — standard base64, padded
  - `func DecodeText(s string) (Payload, error)`

- [ ] **Step 1: Write the failing tests**

```go
func TestRoundTrip(t *testing.T) {
	want := enroll.Payload{
		Host:        "example.test",
		Port:        8443,
		Fingerprint: [32]byte{1, 2, 3},
		Token:       [32]byte{4, 5, 6},
		Expiry:      time.Unix(1_800_000_000, 0).UTC(),
	}
	text, err := enroll.EncodeToText(want)
	if err != nil { t.Fatal(err) }
	got, err := enroll.DecodeText(text)
	if err != nil { t.Fatal(err) }
	if got != want { t.Fatalf("round trip changed the payload:\n want %+v\n got  %+v", want, got) }
}

func TestFieldOrderIsFixed(t *testing.T) {
	p := enroll.Payload{Host: "ab", Port: 0x1234, Expiry: time.Unix(0x01020304, 0)}
	b, err := enroll.Encode(p)
	if err != nil { t.Fatal(err) }
	// version, host length, host, port, fingerprint, token, expiry
	if b[0] != enroll.Version { t.Fatalf("version must be first, got %d", b[0]) }
	if b[1] != 0x00 || b[2] != 0x02 { t.Fatalf("host length must be big-endian uint16, got %x", b[1:3]) }
	if string(b[3:5]) != "ab" { t.Fatalf("host must follow its length, got %q", b[3:5]) }
	if b[5] != 0x12 || b[6] != 0x34 { t.Fatalf("port must be big-endian uint16, got %x", b[5:7]) }
	if len(b) != 3+2+2+32+32+4 { t.Fatalf("unexpected length %d", len(b)) }
}

func TestRejectsAWrongVersion(t *testing.T) {
	b, _ := enroll.Encode(enroll.Payload{Host: "a", Port: 1, Expiry: time.Unix(1, 0)})
	b[0] = 99
	if _, err := enroll.Decode(b); err == nil {
		t.Fatal("a payload from a newer version must be refused, not misread")
	}
}

func TestRejectsTrailingBytes(t *testing.T) {
	b, _ := enroll.Encode(enroll.Payload{Host: "a", Port: 1, Expiry: time.Unix(1, 0)})
	if _, err := enroll.Decode(append(b, 0)); err == nil {
		t.Fatal("trailing bytes must be refused")
	}
}

func TestMatchesTheGoldenVectors(t *testing.T) {
	raw, err := os.ReadFile("../../../wire/enroll-payload-vectors.json")
	if err != nil { t.Fatal(err) }
	var vectors []struct {
		Name        string `json:"name"`
		Host        string `json:"host"`
		Port        int    `json:"port"`
		Fingerprint string `json:"fingerprint_hex"`
		Token       string `json:"token_hex"`
		Expiry      int64  `json:"expiry_unix"`
		Text        string `json:"text"`
	}
	if err := json.Unmarshal(raw, &vectors); err != nil { t.Fatal(err) }
	if len(vectors) < 3 { t.Fatalf("want at least three vectors, got %d", len(vectors)) }
	for _, v := range vectors {
		var fp, tok [32]byte
		mustHex(t, v.Fingerprint, fp[:])
		mustHex(t, v.Token, tok[:])
		got, err := enroll.EncodeToText(enroll.Payload{
			Host: v.Host, Port: v.Port, Fingerprint: fp, Token: tok,
			Expiry: time.Unix(v.Expiry, 0).UTC(),
		})
		if err != nil { t.Fatalf("%s: %v", v.Name, err) }
		if got != v.Text { t.Fatalf("%s: encoder drifted from the vector\n want %s\n got  %s", v.Name, v.Text, got) }
	}
}
```

- [ ] **Step 2: Run and watch them fail**

Run: `cd bridge && go test ./internal/enroll/ -v`
Expected: FAIL — package does not exist.

- [ ] **Step 3: Implement `format.go`**

Field order exactly as asserted: `uint8` version, `uint16` host length, host UTF-8, `uint16` port,
32-byte fingerprint, 32-byte token, `uint32` expiry — all big-endian. `MaxField = 4096` guards a
hostile length. `Decode` refuses a wrong version, a short buffer, and trailing bytes.

- [ ] **Step 4: Generate the golden vectors**

Write a `go test -run TestWriteVectors -tags vectors` helper, or a small `go run` program, that emits
three vectors: a short host, a host at 253 bytes, and a host containing non-ASCII. Commit the JSON.
**These bytes are the contract; do not regenerate them casually.**

- [ ] **Step 5: Run the tests**

Run: `cd bridge && go test ./internal/enroll/ -v`
Expected: PASS, five tests.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -s -m "feat(enroll): the QR payload format, pinned by golden vectors"
```

### Task 10: The enrolment window

**Files:**
- Create: `bridge/internal/enroll/window.go`
- Test: `bridge/internal/enroll/window_test.go`

**Interfaces:**
- Produces:
  - `type Window struct { … }`
  - `func NewWindow(now func() time.Time) *Window`
  - `func (w *Window) Open(ttl time.Duration) (token [32]byte, expiry time.Time)`
  - `func (w *Window) Close()`
  - `func (w *Window) IsOpen() bool`
  - `func (w *Window) Consume(token []byte) error` — `nil` on success, and the window is closed either way after `maxAttempts`

- [ ] **Step 1: Write the failing tests**

```go
func TestConsumeSucceedsOnceAndOnlyOnce(t *testing.T) {
	w := enroll.NewWindow(time.Now)
	token, _ := w.Open(5 * time.Minute)
	if err := w.Consume(token[:]); err != nil { t.Fatalf("first use must succeed: %v", err) }
	if err := w.Consume(token[:]); err == nil { t.Fatal("a token must not be usable twice") }
	if w.IsOpen() { t.Fatal("the window must close on success") }
}

func TestExpiredTokenIsRefused(t *testing.T) {
	now := time.Unix(1000, 0)
	w := enroll.NewWindow(func() time.Time { return now })
	token, _ := w.Open(time.Minute)
	now = now.Add(61 * time.Second)
	if err := w.Consume(token[:]); err == nil { t.Fatal("an expired token must be refused") }
	if w.IsOpen() { t.Fatal("an expired window must report itself closed") }
}

func TestFiveWrongAttemptsCloseTheWindow(t *testing.T) {
	w := enroll.NewWindow(time.Now)
	token, _ := w.Open(5 * time.Minute)
	wrong := make([]byte, 32)
	for i := 0; i < 5; i++ { _ = w.Consume(wrong) }
	if w.IsOpen() { t.Fatal("the window must close after five wrong attempts") }
	if err := w.Consume(token[:]); err == nil { t.Fatal("the real token must not work after the window closed") }
}

func TestClosedWindowRefusesEverything(t *testing.T) {
	w := enroll.NewWindow(time.Now)
	if w.IsOpen() { t.Fatal("a fresh window must start closed") }
	if err := w.Consume(make([]byte, 32)); err == nil { t.Fatal("a closed window must refuse") }
}

func TestTokensDiffer(t *testing.T) {
	w := enroll.NewWindow(time.Now)
	a, _ := w.Open(time.Minute)
	w.Close()
	b, _ := w.Open(time.Minute)
	if a == b { t.Fatal("each window must mint a fresh token") }
}
```

- [ ] **Step 2: Run and watch them fail**

Run: `cd bridge && go test ./internal/enroll/ -run Window -v`
Expected: FAIL.

- [ ] **Step 3: Implement `window.go`**

`crypto/rand` for the token, `subtle.ConstantTimeCompare` for the check, a `sync.Mutex` around all of
it, and **no field written to disk anywhere**. `maxAttempts = 5`.

- [ ] **Step 4: Assert the token never reaches disk**

Add a test that opens a window, walks the state directory, and fails if any file's bytes contain the
token.

- [ ] **Step 5: Run the tests and commit**

Run: `cd bridge && go test ./internal/enroll/ -v`

```bash
git add -A && git commit -s -m "feat(enroll): a single-use, in-memory enrolment window"
```

### Task 11: The ALPN split

The structural invariant of the whole design (spec §5.3).

**Files:**
- Create: `bridge/internal/enroll/alpn.go`
- Test: `bridge/internal/enroll/alpn_test.go`
- Modify: `bridge/internal/pinning/pinning.go` — `ServerConfig` takes a slice of pinned clients

**Interfaces:**
- Produces:
  - `const ProtoAPI = "agterm/api-1"`, `const ProtoEnroll = "agterm/enroll-1"`
  - `func ServerConfigFor(own tls.Certificate, peers func() []*x509.Certificate, window *Window) *tls.Config` — sets `GetConfigForClient`
  - `func pinning.AnonymousServerConfig(own tls.Certificate) *tls.Config` — serves `own`, `ClientAuth: tls.NoClientCert`, verifies nothing, because during enrolment there is nothing yet to verify
- Changes: `pinning.ServerConfig(own tls.Certificate, pinnedClients []*x509.Certificate) *tls.Config` — was a single `*x509.Certificate`; every existing caller and test passes a one-element slice

- [ ] **Step 1: Write the failing tests**

```go
func TestCertlessClientReachesOnlyEnrolment(t *testing.T) {
	srv, window, _ := testBridge(t)          // helper: real TLS listener over a pipe
	window.Open(time.Minute)

	state, err := dial(t, srv, nil /* no client certificate */, []string{enroll.ProtoAPI})
	if err == nil {
		t.Fatalf("a certificate-less client negotiated %q", state.NegotiatedProtocol)
	}
}

func TestCertlessClientIsRefusedWhenTheWindowIsClosed(t *testing.T) {
	srv, _, _ := testBridge(t)               // window never opened
	if _, err := dial(t, srv, nil, []string{enroll.ProtoEnroll}); err == nil {
		t.Fatal("enrolment must not be offered while no window is open")
	}
}

func TestPinnedClientReachesTheAPI(t *testing.T) {
	srv, _, phone := testBridge(t)
	state, err := dial(t, srv, &phone, []string{enroll.ProtoAPI})
	if err != nil { t.Fatal(err) }
	if state.NegotiatedProtocol != enroll.ProtoAPI {
		t.Fatalf("want %q, got %q", enroll.ProtoAPI, state.NegotiatedProtocol)
	}
}

func TestUnpinnedClientCertificateIsRefused(t *testing.T) {
	srv, _, _ := testBridge(t)
	stranger := mintIdentity(t)
	if _, err := dial(t, srv, &stranger, []string{enroll.ProtoAPI}); err == nil {
		t.Fatal("a certificate that is not pinned must be refused")
	}
}

// The invariant, stated as a test rather than as a comment.
func TestNoCertificateCanEverMeanAPI(t *testing.T) {
	srv, window, _ := testBridge(t)
	window.Open(time.Minute)
	state, err := dial(t, srv, nil, []string{enroll.ProtoEnroll, enroll.ProtoAPI})
	if err != nil { t.Fatal(err) }
	if state.NegotiatedProtocol != enroll.ProtoEnroll {
		t.Fatalf("a certificate-less connection negotiated %q", state.NegotiatedProtocol)
	}
}
```

- [ ] **Step 2: Run and watch them fail**

Run: `cd bridge && go test ./internal/enroll/ -run TestCert -v`
Expected: FAIL.

- [ ] **Step 3: Implement `alpn.go`**

```go
func ServerConfigFor(own tls.Certificate, peers func() []*x509.Certificate, window *Window) *tls.Config {
	return &tls.Config{
		MinVersion: tls.VersionTLS13,
		GetConfigForClient: func(hello *tls.ClientHelloInfo) (*tls.Config, error) {
			if window.IsOpen() && slices.Contains(hello.SupportedProtos, ProtoEnroll) {
				c := pinning.AnonymousServerConfig(own)
				c.NextProtos = []string{ProtoEnroll}
				return c, nil
			}
			c := pinning.ServerConfig(own, peers())
			c.NextProtos = []string{ProtoAPI}
			return c, nil
		},
	}
}
```

Add `pinning.AnonymousServerConfig(own tls.Certificate) *tls.Config` — `ClientAuth: tls.NoClientCert`,
serving `own` and verifying nothing, because there is nothing yet to verify.

- [ ] **Step 4: Widen `pinning.ServerConfig` to a list**

Its verifier already compares exact bytes; make it accept a match against any element and keep
`crypto/subtle` for the comparison. Update the existing `pinning` tests to pass a one-element slice.

- [ ] **Step 5: Run every bridge test**

Run: `cd bridge && go test ./...`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -s -m "feat(enroll): split enrolment from the API by ALPN"
```

### Task 12: The enrolment handler

**Files:**
- Create: `bridge/internal/enroll/handler.go`
- Test: `bridge/internal/enroll/handler_test.go`
- Modify: `bridge/cmd/agterm-remote-bridge/main.go`

**Interfaces:**
- Produces:
  - `type Request struct { Verb string `json:"verb"`; Token string `json:"token"`; Certificate string `json:"certificate"`; Name string `json:"name"` }`
  - `type Reply struct { OK bool `json:"ok"`; Certificate string `json:"certificate,omitempty"`; Fingerprint string `json:"fingerprint,omitempty"`; Error string `json:"error,omitempty"` }`
  - `func Serve(conn net.Conn, window *Window, store *trust.Store, own *x509.Certificate, paired func(trust.Peer))`

`Token` and `Certificate` are standard base64. `Serve` reads exactly one line, replies with exactly
one line, and closes.

- [ ] **Step 1: Write the failing tests**

```go
func TestSuccessfulEnrolmentPinsThePhoneAndReturnsTheBridgeCertificate(t *testing.T) {
	window, store, own := handlerFixture(t)
	token, _ := window.Open(time.Minute)
	phone := mintIdentity(t)

	reply := exchange(t, window, store, own, enroll.Request{
		Verb:        "enroll",
		Token:       base64.StdEncoding.EncodeToString(token[:]),
		Certificate: base64.StdEncoding.EncodeToString(phone.Leaf.Raw),
		Name:        "a phone",
	})

	if !reply.OK { t.Fatalf("enrolment failed: %s", reply.Error) }
	if reply.Certificate == "" { t.Fatal("the bridge must return its own certificate") }
	if len(store.Peers()) != 1 { t.Fatalf("want the phone pinned, got %d peers", len(store.Peers())) }
	if store.Peers()[0].Name != "a phone" { t.Fatal("the name must be stored for the menu") }
}

func TestAWrongTokenPinsNothing(t *testing.T) {
	window, store, own := handlerFixture(t)
	window.Open(time.Minute)
	phone := mintIdentity(t)

	reply := exchange(t, window, store, own, enroll.Request{
		Verb:        "enroll",
		Token:       base64.StdEncoding.EncodeToString(make([]byte, 32)),
		Certificate: base64.StdEncoding.EncodeToString(phone.Leaf.Raw),
	})
	if reply.OK { t.Fatal("a wrong token must not enrol") }
	if len(store.Peers()) != 0 { t.Fatal("nothing may be pinned on failure") }
}

func TestGarbageCertificatePinsNothing(t *testing.T) {
	window, store, own := handlerFixture(t)
	token, _ := window.Open(time.Minute)
	reply := exchange(t, window, store, own, enroll.Request{
		Verb:        "enroll",
		Token:       base64.StdEncoding.EncodeToString(token[:]),
		Certificate: base64.StdEncoding.EncodeToString([]byte("not a certificate")),
	})
	if reply.OK { t.Fatal("an unparseable certificate must be refused") }
	if len(store.Peers()) != 0 { t.Fatal("nothing may be pinned") }
}

func TestAnOversizedLineIsRefused(t *testing.T) {
	window, store, own := handlerFixture(t)
	window.Open(time.Minute)
	// 1 MiB of 'a' followed by a newline.
	if reply := exchangeRaw(t, window, store, own, strings.Repeat("a", 1<<20)+"\n"); reply.OK {
		t.Fatal("an oversized request must be refused")
	}
}
```

- [ ] **Step 2: Run and watch them fail**

Run: `cd bridge && go test ./internal/enroll/ -run Enrol -v`
Expected: FAIL.

- [ ] **Step 3: Implement `handler.go`**

Bounded read (64 KiB ceiling — a certificate is about 500 bytes), one JSON line in, one out,
`window.Consume` before anything is parsed beyond the token, `x509.ParseCertificate` before anything
is stored, `store.Replace` on success, `paired` callback so the Mac app's menu updates.

- [ ] **Step 4: Wire it into the accept path**

In `main.go`, after the handshake, branch on `conn.ConnectionState().NegotiatedProtocol`:
`ProtoEnroll` → `enroll.Serve`, `ProtoAPI` → the existing `listener.Server`.

- [ ] **Step 5: Write the end-to-end Go test**

`bridge/internal/enroll/endtoend_test.go`: a real bridge on a real port, a client that dials with no
certificate and ALPN `enroll-1`, enrols, then reconnects with the certificate it just enrolled and
ALPN `api-1` and gets a session list back from a fake agterm.

- [ ] **Step 6: Run every bridge test and commit**

Run: `cd bridge && go test ./...`

```bash
git add -A && git commit -s -m "feat(enroll): the enrolment handler, end to end"
```

### Task 13: Control verbs for the Mac app

**Files:**
- Modify: `bridge/internal/control/control.go`
- Test: `bridge/internal/control/control_test.go`

**Interfaces:**
- Produces four verbs on the local control socket, each a JSON line:
  - `{"verb":"status"}` → `{"listening":"…","paired":[{"fingerprint":"…","name":"…","paired_at":…}],"agterm":true}`
  - `{"verb":"pair-open","ttl_seconds":300}` → `{"payload":"<base64 QR text>","expires_at":…}`
  - `{"verb":"pair-close"}` → `{"ok":true}`
  - `{"verb":"unpair","fingerprint":"…"}` → `{"ok":true}`

- [ ] **Step 1: Write the failing tests**

One per verb, over a `net.Pipe`, asserting the exact JSON shape above. Add one asserting that
`pair-open` returns a payload that `enroll.DecodeText` parses, and whose fingerprint equals
`sha256(bridge certificate DER)`.

- [ ] **Step 2: Run and watch them fail**

Run: `cd bridge && go test ./internal/control/ -v`

- [ ] **Step 3: Implement**

Extend the existing `Fit` interface, or add a second interface handled by the same socket — follow
whichever shape the ported `control.go` already uses. **The control socket stays 0600 and
loopback-only:** it is the one place a caller can open an enrolment window.

- [ ] **Step 4: Run the tests and commit**

```bash
git add -A && git commit -s -m "feat(bridge): control verbs for status, pairing and unpairing"
```

---

## Phase D — The Mac app

### Task 14: Port and rename the SwiftPM package

**Files:**
- Create: `mac/Package.swift`, `mac/Sources/AgtermRemoteCore/**`, `mac/Sources/AgtermRemote/**`, `mac/Tests/AgtermRemoteCoreTests/**`

- [ ] **Step 1: Copy and rename**

```bash
cp -R ~/pets/home-app/face mac
cd mac && rm -rf .build
git mv Sources/AgtermFaceCore Sources/AgtermRemoteCore
git mv Sources/AgtermFace Sources/AgtermRemote
git mv Sources/AgtermFaceIcon Sources/AgtermRemoteIcon
git mv Tests/AgtermFaceCoreTests Tests/AgtermRemoteCoreTests
grep -rl 'AgtermFace' . | xargs sed -i '' 's/AgtermFaceCore/AgtermRemoteCore/g; s/AgtermFaceIcon/AgtermRemoteIcon/g; s/AgtermFace/AgtermRemote/g'
sed -i '' 's/dev\.isachivka\.agtermbridge/dev.isachivka.agtermremote/g' $(grep -rl 'dev.isachivka' .)
```

- [ ] **Step 2: Delete what does not carry over**

`CertificateDropView.swift`, `CertificateDrop.swift`, `CertificateDropTests.swift`, `PinCertificate.swift`,
`DialFile.swift`, `DialFileTests.swift` — the drag-and-drop certificate handover and the `dial.txt`
file are both replaced by enrolment and by the app's own preferences.

- [ ] **Step 3: Strip REQ/PLAN references from comments**

Same treatment as Task 5 step 4.

- [ ] **Step 4: Build and test**

Run: `cd mac && swift build && swift test`
Expected: PASS, with the deleted tests gone.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -s -m "feat(mac): port the menu-bar app as AgtermRemote"
```

### Task 15: Supervise the bridge as a child process

Replaces `launchctl` entirely.

**Files:**
- Create: `mac/Sources/AgtermRemoteCore/BridgeProcess.swift`
- Delete: `mac/Sources/AgtermRemoteCore/BridgeSupervisor.swift`, `mac/Tests/AgtermRemoteCoreTests/BridgeSupervisorTests.swift`
- Test: `mac/Tests/AgtermRemoteCoreTests/BridgeProcessTests.swift`

**Interfaces:**
- Produces:
  - `public enum BridgeState: Equatable { case stopped, starting, running(pid: Int32), failed(String) }`
  - `public protocol ProcessLauncher { func launch(_ executable: URL, _ arguments: [String], onExit: @escaping (Int32) -> Void) throws -> Int32; func terminate(_ pid: Int32) }`
  - `public final class BridgeProcess { public init(launcher: ProcessLauncher, executable: URL, stateDir: URL); public private(set) var state: BridgeState; public func start(listen: String, socket: String?) throws; public func stop(); public var onStateChange: ((BridgeState) -> Void)? }`

- [ ] **Step 1: Write the failing tests**

```swift
func testStartPassesTheAddressAndItsOwnPidToTheBridge() throws {
    let launcher = RecordingLauncher()
    let bridge = BridgeProcess(launcher: launcher, executable: URL(fileURLWithPath: "/tmp/bridge"),
                               stateDir: URL(fileURLWithPath: "/tmp/state"))
    try bridge.start(listen: "0.0.0.0:8443", socket: nil)

    XCTAssertTrue(launcher.arguments.contains("--listen"))
    XCTAssertTrue(launcher.arguments.contains("0.0.0.0:8443"))
    XCTAssertTrue(launcher.arguments.contains("--parent-pid"))
    XCTAssertTrue(launcher.arguments.contains(String(ProcessInfo.processInfo.processIdentifier)))
}

func testAnExitRestartsWithBackoffAndThenGivesUp() throws {
    let launcher = AlwaysFailingLauncher()   // calls onExit(1) immediately
    let bridge = BridgeProcess(launcher: launcher, executable: URL(fileURLWithPath: "/tmp/bridge"),
                               stateDir: URL(fileURLWithPath: "/tmp/state"))
    try bridge.start(listen: "0.0.0.0:8443", socket: nil)

    XCTAssertEqual(launcher.launches, 5, "must stop retrying rather than loop forever")
    guard case .failed = bridge.state else { return XCTFail("state must be .failed, was \(bridge.state)") }
}

func testStopTerminatesAndReportsStopped() throws {
    let launcher = RecordingLauncher()
    let bridge = BridgeProcess(launcher: launcher, executable: URL(fileURLWithPath: "/tmp/bridge"),
                               stateDir: URL(fileURLWithPath: "/tmp/state"))
    try bridge.start(listen: "0.0.0.0:8443", socket: nil)
    bridge.stop()
    XCTAssertEqual(launcher.terminated, [launcher.lastPid])
    XCTAssertEqual(bridge.state, .stopped)
}
```

- [ ] **Step 2: Run and watch them fail**

Run: `cd mac && swift test --filter BridgeProcessTests`

- [ ] **Step 3: Implement `BridgeProcess.swift`**

Backoff `0.5s, 1s, 2s, 4s`, five launches maximum, reset on a run that lasts longer than 30 seconds.
The production `ProcessLauncher` uses `Foundation.Process` with `terminationHandler`.

- [ ] **Step 4: Terminate on quit**

In `Sources/AgtermRemote/main.swift`, call `bridge.stop()` from
`applicationWillTerminate` **and** register a `signal(SIGTERM)` handler, because a menu-bar app can be
killed without the delegate running.

- [ ] **Step 5: Run the tests and commit**

```bash
git add -A && git commit -s -m "feat(mac): run the bridge as a child process instead of a launchd job"
```

### Task 16: Embed the bridge in the app bundle

**Files:**
- Modify: `mac/scripts/bundle.sh`
- Test: `mac/Tests/AgtermRemoteCoreTests/BundleLayoutTests.swift`

**Interfaces:**
- Produces: `public enum BundledBridge { public static func url(in bundle: Bundle) -> URL? }` — resolves `Contents/Resources/agterm-remote-bridge`.

- [ ] **Step 1: Write the failing test**

```swift
func testTheBridgeIsLookedUpInResources() {
    let bundle = Bundle(for: type(of: self))
    let url = BundledBridge.url(in: bundle)
    XCTAssertEqual(url?.lastPathComponent, "agterm-remote-bridge")
    XCTAssertTrue(url?.path.contains("Resources") ?? false)
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `cd mac && swift test --filter BundleLayoutTests`

- [ ] **Step 3: Implement `BundledBridge`**

- [ ] **Step 4: Extend `bundle.sh` to build the universal binary**

```bash
GOOS=darwin GOARCH=arm64 go build -trimpath -o "$tmp/bridge-arm64" ./cmd/agterm-remote-bridge
GOOS=darwin GOARCH=amd64 go build -trimpath -o "$tmp/bridge-amd64" ./cmd/agterm-remote-bridge
lipo -create -output "$APP/Contents/Resources/agterm-remote-bridge" "$tmp/bridge-arm64" "$tmp/bridge-amd64"
chmod 755 "$APP/Contents/Resources/agterm-remote-bridge"
```

`-trimpath` is not decorative: without it the binary carries the build machine's absolute paths, which
is exactly the class of personal value this project refuses to ship.

- [ ] **Step 5: Verify by hand once**

Run: `cd mac && ./scripts/bundle.sh && lipo -archs dist/AgtermRemote.app/Contents/Resources/agterm-remote-bridge`
Expected: `x86_64 arm64`.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -s -m "build(mac): ship the bridge inside the app bundle as a universal binary"
```

### Task 17: Onboarding

**Files:**
- Create: `mac/Sources/AgtermRemoteCore/Onboarding.swift`, `mac/Sources/AgtermRemoteCore/Address.swift`
- Create: `mac/Sources/AgtermRemote/OnboardingWindow.swift`
- Test: `mac/Tests/AgtermRemoteCoreTests/{OnboardingTests,AddressTests}.swift`

**Interfaces:**
- Produces:
  - `public enum OnboardingStep: Equatable { case agtermMissing, address, pairing, done }`
  - `public struct Onboarding { public init(agtermSocketExists: Bool, address: String?, isPaired: Bool); public var step: OnboardingStep }`
  - `public struct Address: Equatable { public let host: String; public let port: Int; public static func parse(_ text: String) -> Result<Address, AddressError>; public var listen: String }`
  - `public enum AddressError: Equatable { case empty, noPort, badPort, hostLooksLikeAURL }`

- [ ] **Step 1: Write the failing tests**

```swift
func testStepIsAgtermMissingBeforeAnythingElse() {
    let o = Onboarding(agtermSocketExists: false, address: "example.test:8443", isPaired: true)
    XCTAssertEqual(o.step, .agtermMissing, "agterm is a prerequisite; nothing else matters without it")
}

func testStepIsAddressWhenThereIsNone() {
    XCTAssertEqual(Onboarding(agtermSocketExists: true, address: nil, isPaired: false).step, .address)
}

func testStepIsPairingWhenAnAddressExistsButNoPhoneDoes() {
    XCTAssertEqual(Onboarding(agtermSocketExists: true, address: "example.test:8443", isPaired: false).step, .pairing)
}

func testDoneOnlyWhenAllThreeHold() {
    XCTAssertEqual(Onboarding(agtermSocketExists: true, address: "example.test:8443", isPaired: true).step, .done)
}

func testAddressParsing() {
    XCTAssertEqual(Address.parse("example.test:8443"), .success(Address(host: "example.test", port: 8443)))
    XCTAssertEqual(Address.parse(""), .failure(.empty))
    XCTAssertEqual(Address.parse("example.test"), .failure(.noPort))
    XCTAssertEqual(Address.parse("example.test:0"), .failure(.badPort))
    XCTAssertEqual(Address.parse("example.test:99999"), .failure(.badPort))
    XCTAssertEqual(Address.parse("https://example.test:8443"), .failure(.hostLooksLikeAURL))
}
```

- [ ] **Step 2: Run and watch them fail**

Run: `cd mac && swift test --filter OnboardingTests`

- [ ] **Step 3: Implement both types**

- [ ] **Step 4: Build the onboarding window**

Three panes driven by `OnboardingStep`. The address pane's copy, verbatim, is the spec §6 text: a
sentence about needing an address the phone can reach, then the list — port forward with `IP:PORT`
when the address does not change, port forward with a DDNS name when it does, or Tailscale, WireGuard,
Cloudflare Tunnel, or any reverse proxy. **No "test this address" button** — a comment in the source
must say why, because it is the obvious thing for a later contributor to add.

- [ ] **Step 5: Store the address**

`UserDefaults` under `dev.isachivka.agtermremote.address`, plus `…addressProvenAt: Date?` set only when
a phone completes enrolment through it. The menu shows "unproven" until then.

- [ ] **Step 6: Run the tests and commit**

```bash
git add -A && git commit -s -m "feat(mac): onboarding — agterm, the address, and the code"
```

### Task 18: The pairing panel

**Files:**
- Create: `mac/Sources/AgtermRemoteCore/PairingPanelModel.swift`, `mac/Sources/AgtermRemoteCore/QRRender.swift`
- Modify: `mac/Sources/AgtermRemoteCore/MenuModel.swift`
- Test: `mac/Tests/AgtermRemoteCoreTests/{PairingPanelModelTests,MenuModelTests}.swift`

**Interfaces:**
- Consumes: the control verbs from Task 13.
- Produces:
  - `public struct PairedPhone: Equatable { public let fingerprint: String; public let name: String }`
  - `public protocol ControlClient { func status() throws -> (listening: String, paired: [PairedPhone], agterm: Bool); func openPairing(ttl: TimeInterval) throws -> (payload: String, expiresAt: Date); func closePairing() throws; func unpair(fingerprint: String) throws }`
  - `public final class UnixControlClient: ControlClient` — one JSON line per connection over `<stateDir>/control.sock`, matching the bridge's socket exactly
  - `public enum PairingPanelState: Equatable { case closed, showing(payload: String, expiresAt: Date), expired, paired(fingerprint: String, name: String) }`
  - `public final class PairingPanelModel { public init(control: ControlClient, now: @escaping () -> Date); public private(set) var state: PairingPanelState; public func open(); public func close(); public func tick() }`
- Changes: `MenuAction` gains `case unpair`, loses `case setAddress` (folded into onboarding), and `showPairingCode` is renamed `pairPhone`. `MenuModel.items` changes from `items(status:hasAddress:launchesAtLogin:)` to `items(paired: [PairedPhone], hasAddress: Bool, launchesAtLogin: Bool) -> [MenuItem]` — the icon's three-way verdict is replaced by what the bridge actually reports.

- [ ] **Step 1: Write the failing tests**

```swift
func testOpenAsksTheBridgeForAPayloadAndShowsIt() {
    let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
    let model = PairingPanelModel(control: control, now: { Date(timeIntervalSince1970: 0) })
    model.open()
    XCTAssertEqual(model.state, .showing(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300)))
}

func testTheCodeExpiresOnItsOwn() {
    let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
    var now = Date(timeIntervalSince1970: 0)
    let model = PairingPanelModel(control: control, now: { now })
    model.open()
    now = Date(timeIntervalSince1970: 301)
    model.tick()
    XCTAssertEqual(model.state, .expired, "a stale code on screen is a code someone will scan")
}

func testClosingThePanelClosesTheWindowOnTheBridge() {
    let control = FakeControl(payload: "AQA...", expiresAt: Date(timeIntervalSince1970: 300))
    let model = PairingPanelModel(control: control, now: { Date(timeIntervalSince1970: 0) })
    model.open()
    model.close()
    XCTAssertTrue(control.closed, "the enrolment window must not outlive the panel")
}

func testMenuOffersUnpairOnlyWhenAPhoneIsPaired() {
    let withPhone = MenuModel.items(paired: [.init(fingerprint: "ab", name: "a phone")], hasAddress: true, launchesAtLogin: false)
    XCTAssertTrue(withPhone.contains { $0.action == .unpair })
    let without = MenuModel.items(paired: [], hasAddress: true, launchesAtLogin: false)
    XCTAssertFalse(without.contains { $0.action == .unpair })
}
```

- [ ] **Step 2: Run and watch them fail**

Run: `cd mac && swift test --filter PairingPanel`

- [ ] **Step 3: Implement the model and the QR renderer**

`CIFilter.qrCodeGenerator()` with correction level `M`, rendered at a size that is at least 240 pt on
screen. The panel shows the payload as selectable text beneath the code — that text is the phone's
paste fallback, and it is the same string.

- [ ] **Step 4: Show the result**

On the `paired` state the panel says which phone paired, with its fingerprint, and offers "Done". This
is also what flips `addressProvenAt`.

- [ ] **Step 5: Run the tests and commit**

```bash
git add -A && git commit -s -m "feat(mac): the pairing panel, and unpairing from the menu"
```

---

## Phase E — The Android app

### Task 19: Port the app module and strip it

**Files:**
- Create: `app/**`, `build.gradle.kts`, `settings.gradle.kts`, `gradle/**`, `gradlew`, `buildSrc/**`

- [ ] **Step 1: Copy the Gradle scaffolding and the app module**

```bash
cd ~/pets/agterm-remote
cp -R ~/pets/home-app/{app,buildSrc,gradle,gradlew,gradlew.bat,build.gradle.kts,settings.gradle.kts,gradle.properties} .
rm -rf app/build buildSrc/build buildSrc/.gradle .gradle
```

- [ ] **Step 2: Delete the modules that do not carry over**

```bash
rm -rf app/src/main/java/dev/isachivka/bewareofsugar/{car,limits,update,ui/home}
rm -rf app/src/androidTest/java/dev/isachivka/bewareofsugar/{car,limits,update,ui/home}
rm -rf app/src/main/res/xml/automotive_app_desc.xml
```

Then remove every reference to them: the Android Auto service and its `<meta-data>` from
`AndroidManifest.xml`, the update and limits entries from navigation, and the `GITHUB_REPO`
`buildConfigField` from `app/build.gradle.kts`.

- [ ] **Step 3: Rename the package**

```bash
cd app/src && for tree in main test androidTest debug; do
  [ -d "$tree/java/dev/isachivka/bewareofsugar" ] && git mv "$tree/java/dev/isachivka/bewareofsugar" "$tree/java/dev/isachivka/agtermremote"
done
cd ~/pets/agterm-remote && grep -rl 'bewareofsugar\|beware-of-sugar' --include='*.kt' --include='*.kts' --include='*.xml' --include='*.pro' . \
  | xargs sed -i '' 's/dev\.isachivka\.bewareofsugar/dev.isachivka.agtermremote/g; s/beware-of-sugar/agterm-remote/g'
```

- [ ] **Step 4: Retitle the app**

`app/src/main/res/values/strings.xml`: `app_name` becomes `Remote for agterm`. Not "Agterm Remote" —
spec §9, the listing must not read as an official agterm product.

- [ ] **Step 5: Strip REQ/PLAN references from comments**

Same treatment as Task 5 step 4, across `.kt`.

- [ ] **Step 6: Build**

Run: `./gradlew assembleDebug testDebugUnitTest lintDebug`
Expected: PASS. Anything that fails here is a leftover reference to a deleted module; delete the
reference rather than restoring the module.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -s -m "feat(app): port the Android terminal, without the launcher, updates, limits or Android Auto"
```

### Task 20: Open on the terminal, or on pairing

**Files:**
- Modify: `app/src/main/java/dev/isachivka/agtermremote/MainActivity.kt`, `…/ui/nav/**`
- Test: `app/src/androidTest/java/dev/isachivka/agtermremote/ui/nav/StartDestinationTest.kt`

**Interfaces:**
- Produces: `sealed interface Start { data object Pairing : Start; data object Terminal : Start }` and `fun startDestination(hasPairedLaptop: Boolean): Start`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun unpairedPhoneStartsAtPairing() {
    assertEquals(Start.Pairing, startDestination(hasPairedLaptop = false))
}

@Test fun pairedPhoneStartsAtTheTerminal() {
    assertEquals(Start.Terminal, startDestination(hasPairedLaptop = true))
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `./gradlew testDebugUnitTest --tests '*StartDestination*'`

- [ ] **Step 3: Implement, and delete the home grid from navigation**

The grid, its tiles and its routes go. The back stack has two destinations: pairing and terminal, plus
a settings sheet.

- [ ] **Step 4: Run the tests and commit**

```bash
git add -A && git commit -s -m "feat(app): open on the terminal, or on pairing when there is no laptop yet"
```

### Task 21: Decode the QR payload, against the same golden vectors

**Files:**
- Create: `app/src/main/java/dev/isachivka/agtermremote/pairing/EnrollPayload.kt`
- Delete: `…/pairing/ConnectionProfile.kt` (the old codec)
- Test: `app/src/test/java/dev/isachivka/agtermremote/pairing/EnrollPayloadTest.kt`
- Modify: `app/build.gradle.kts` — copy `wire/` into unit-test resources

**Interfaces:**
- Produces:
  - `data class EnrollPayload(val host: String, val port: Int, val fingerprint: ByteArray, val token: ByteArray, val expiryUnix: Long)`
  - `object EnrollCodec { const val VERSION = 1; fun decode(bytes: ByteArray): EnrollPayload?; fun decodeText(text: String): EnrollPayload? }`

`EnrollPayload.toString()` must redact host and port, matching what the deleted `ConnectionProfile`
did — a stack trace in a bug report must not carry someone's address.

- [ ] **Step 1: Make the vectors available to the test**

In `app/build.gradle.kts`:

```kotlin
val copyWireVectors by tasks.registering(Copy::class) {
    from(rootProject.file("wire"))
    into(layout.buildDirectory.dir("wire-vectors"))
}
tasks.withType<Test>().configureEach {
    dependsOn(copyWireVectors)
    systemProperty("wire.vectors", layout.buildDirectory.dir("wire-vectors").get().asFile.path)
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
@Test fun decodesEveryGoldenVector() {
    val file = File(System.getProperty("wire.vectors")!!, "enroll-payload-vectors.json")
    val vectors = JSONArray(file.readText())
    assertTrue("want at least three vectors", vectors.length() >= 3)
    for (i in 0 until vectors.length()) {
        val v = vectors.getJSONObject(i)
        val got = EnrollCodec.decodeText(v.getString("text"))
            ?: fail("vector ${v.getString("name")} did not decode")
        assertEquals(v.getString("host"), got.host)
        assertEquals(v.getInt("port"), got.port)
        assertEquals(v.getString("fingerprint_hex"), got.fingerprint.toHex())
        assertEquals(v.getString("token_hex"), got.token.toHex())
        assertEquals(v.getLong("expiry_unix"), got.expiryUnix)
    }
}

@Test fun refusesANewerVersion() {
    val bytes = Base64.getDecoder().decode(firstVectorText()).also { it[0] = 99 }
    assertNull(EnrollCodec.decode(bytes))
}

@Test fun refusesTrailingBytes() {
    val bytes = Base64.getDecoder().decode(firstVectorText()) + 0
    assertNull(EnrollCodec.decode(bytes))
}

@Test fun toStringHidesTheAddress() {
    val p = EnrollCodec.decodeText(firstVectorText())!!
    assertFalse(p.toString().contains(p.host))
    assertFalse(p.toString().contains(p.port.toString()))
}
```

- [ ] **Step 3: Run and watch it fail**

Run: `./gradlew testDebugUnitTest --tests '*EnrollPayload*'`

- [ ] **Step 4: Implement `EnrollCodec`**

Big-endian, `java.util.Base64.getDecoder()` (the padded standard alphabet, matching Go's
`base64.StdEncoding`), a 4096-byte field ceiling, and a refusal on any trailing byte.

- [ ] **Step 5: Run the tests and commit**

```bash
git add -A && git commit -s -m "feat(app): decode the enrolment payload, checked against the shared vectors"
```

### Task 22: Enrol over TLS

**Files:**
- Create: `app/src/main/java/dev/isachivka/agtermremote/pairing/Enrollment.kt`
- Create: `app/src/main/java/dev/isachivka/agtermremote/pairing/FingerprintTrust.kt`
- Modify: `…/pairing/PhoneIdentity.kt` — alias becomes `agterm-remote.phone-identity`
- Modify: `…/pairing/PairedLaptop.kt` — stores host, port and the bridge certificate DER
- Test: `app/src/test/java/dev/isachivka/agtermremote/pairing/FingerprintTrustTest.kt`
- Test: `app/src/androidTest/java/dev/isachivka/agtermremote/pairing/EnrollmentTest.kt`

**Interfaces:**
- Produces:
  - `class FingerprintTrust(private val expected: ByteArray) : X509TrustManager`
  - `sealed interface EnrollResult { data class Paired(val bridgeCertificate: X509Certificate, val fingerprint: String) : EnrollResult; data class Refused(val reason: String) : EnrollResult; data class Unreachable(val cause: Throwable) : EnrollResult }`
  - `object Enrollment { fun enroll(payload: EnrollPayload, identity: X509Certificate, keyManager: KeyManager, deviceName: String): EnrollResult }`
  - `data class PairedLaptopRecord(val host: String, val port: Int, val bridgeCertificate: X509Certificate)` and, on `PairedLaptop`, `fun read(): PairedLaptopRecord?`, `fun write(record: PairedLaptopRecord)`, `fun setAddress(host: String, port: Int)` — `setAddress` rewrites the address and leaves the certificate untouched, which is the whole of spec §5.5

- [ ] **Step 1: Write the failing unit test for the trust manager**

```kotlin
@Test fun acceptsExactlyTheCertificateWhoseFingerprintMatches() {
    val cert = selfSigned()
    val trust = FingerprintTrust(sha256(cert.encoded))
    trust.checkServerTrusted(arrayOf(cert), "EC")   // must not throw
}

@Test fun refusesAnyOtherCertificate() {
    val trust = FingerprintTrust(sha256(selfSigned().encoded))
    assertThrows(CertificateException::class.java) {
        trust.checkServerTrusted(arrayOf(selfSigned()), "EC")
    }
}

@Test fun refusesAnEmptyChain() {
    val trust = FingerprintTrust(sha256(selfSigned().encoded))
    assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(), "EC") }
}

@Test fun comparesOnlyTheLeaf() {
    val leaf = selfSigned(); val other = selfSigned()
    val trust = FingerprintTrust(sha256(leaf.encoded))
    assertThrows(CertificateException::class.java) {
        trust.checkServerTrusted(arrayOf(other, leaf), "EC")  // leaf is chain[0], and only chain[0]
    }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `./gradlew testDebugUnitTest --tests '*FingerprintTrust*'`

- [ ] **Step 3: Implement `FingerprintTrust`**

`MessageDigest.getInstance("SHA-256")` over `chain[0].encoded`, compared with
`MessageDigest.isEqual`. `checkClientTrusted` throws — this manager is never a server.

- [ ] **Step 4: Implement `Enrollment.enroll`**

```kotlin
val ssl = SSLContext.getInstance("TLSv1.3").apply {
    init(null, arrayOf(FingerprintTrust(payload.fingerprint)), SecureRandom())
}
val socket = (ssl.socketFactory.createSocket(payload.host, payload.port) as SSLSocket).apply {
    applicationProtocols = arrayOf("agterm/enroll-1")
    startHandshake()
}
```

Then the HTTP upgrade the bridge's front door expects (reuse the code the terminal already uses to
reach it), one JSON line out, one line in, and `PairedLaptop.write` on success.

- [ ] **Step 5: Write the instrumented end-to-end test**

`EnrollmentTest` runs a real Go bridge? No — it runs against a **fake bridge in Kotlin**: an
`SSLServerSocket` with a self-signed certificate and the ALPN and JSON contract above. Assert: the
happy path pairs; a certificate whose fingerprint differs fails before any bytes are sent; a `{"ok":false}`
reply produces `Refused` and pins nothing.

- [ ] **Step 6: Run the tests and commit**

Run: `./gradlew testDebugUnitTest connectedDebugAndroidTest --tests '*Enrollment*'`

```bash
git add -A && git commit -s -m "feat(app): enrol by scanning, with the server pinned from the code"
```

### Task 23: The scanner screen and its paste fallback

**Files:**
- Modify: `…/pairing/PairingViewfinder.kt`, `…/pairing/PairingSection.kt`, `…/pairing/PairingState.kt`
- Delete: `…/pairing/CertificateHandover.kt`, `…/pairing/PairingCodeFrames.kt`, `…/pairing/LeftoverScans.kt`, `…/pairing/PhoneHalf.kt`
- Test: `app/src/androidTest/java/dev/isachivka/agtermremote/pairing/PairingScreenTest.kt`

**Interfaces:**
- Produces: `sealed interface PairingUi { data object NeedsCamera; data object Scanning; data object Working; data class Failed(val reason: String); data class Paired(val fingerprint: String) }`

- [ ] **Step 1: Write the failing UI tests**

```kotlin
@Test fun showsThePasteFieldWhenTheCameraIsDenied() {
    setPairing(PairingUi.NeedsCamera)
    composeRule.onNodeWithText("Paste the code instead").assertIsDisplayed()
}

@Test fun aPastedCodeEnrolsTheSameWayAScannedOneDoes() {
    setPairing(PairingUi.Scanning)
    composeRule.onNodeWithTag("paste-field").performTextInput(goldenVectorText())
    composeRule.onNodeWithText("Pair").performClick()
    composeRule.waitUntil { state is PairingUi.Working }
}

@Test fun aFailureSaysWhatToDoNext() {
    setPairing(PairingUi.Failed("the laptop did not answer"))
    composeRule.onNodeWithText("the laptop did not answer").assertIsDisplayed()
    composeRule.onNodeWithText("Try again").assertIsDisplayed()
}
```

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew connectedDebugAndroidTest --tests '*PairingScreen*'`

- [ ] **Step 3: Implement**

One screen, five states. Delete the two-way handover: there is no certificate to share out of the app
any more, so `CertificateHandover` and the file-sharing provider entry in `AndroidManifest.xml` go.

- [ ] **Step 4: Prove the camera against a real lens**

Spec §5.7 makes this an acceptance criterion, not a nicety. Run the app on the emulator with a virtual
scene showing the QR, then on real hardware once, and **record the working distance in
`docs/pairing.md`**. Do not soften the README's wording about the camera until this step has actually
been done.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -s -m "feat(app): one screen for pairing, with a paste fallback"
```

### Task 24: Settings — address, fingerprint, unpair

**Files:**
- Modify: `…/settings/**`
- Test: `app/src/androidTest/java/dev/isachivka/agtermremote/settings/SettingsTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun editingTheAddressKeepsThePinnedLaptop() {
    pairWithFake()
    val before = PairedLaptop(dir).read()!!.bridgeCertificate
    openSettings(); setAddress("example.test:9443")
    val after = PairedLaptop(dir).read()!!
    assertEquals("example.test", after.host)
    assertEquals(9443, after.port)
    assertArrayEquals(before.encoded, after.bridgeCertificate.encoded)
}

@Test fun unpairClearsBothTheLaptopAndThePhoneKey() {
    pairWithFake()
    openSettings(); composeRule.onNodeWithText("Unpair").performClick()
    composeRule.onNodeWithText("Unpair").performClick()   // the confirmation
    assertNull(PairedLaptop(dir).read())
    assertNull(PhoneIdentity.existing())
}
```

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew connectedDebugAndroidTest --tests '*Settings*'`

- [ ] **Step 3: Implement**

Address field, the paired laptop's fingerprint shown in full, and "Unpair" behind one confirmation.
Spec §5.5: changing the address must not touch the pinned certificate.

- [ ] **Step 4: Run the tests and commit**

```bash
git add -A && git commit -s -m "feat(app): settings — edit the address, see the fingerprint, unpair"
```

### Task 25: Turn the Android and Mac CI jobs green

- [ ] **Step 1: Remove the remaining `continue-on-error` flags from `ci.yml`**
- [ ] **Step 2: Add the emulator job**

```yaml
  instrumented:
    name: instrumented
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: '21' }
      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' \
            | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm
      - uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 34
          arch: x86_64
          script: ./gradlew connectedDebugAndroidTest
```

- [ ] **Step 3: Add `instrumented` to the required checks**

```bash
gh api -X PUT repos/isachivka/agterm-remote/branches/main/protection/required_status_checks/contexts \
  -f 'contexts[]=guards' -f 'contexts[]=bridge' -f 'contexts[]=android' -f 'contexts[]=mac' -f 'contexts[]=instrumented'
```

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -s -m "ci: require every job, and run instrumented tests on an emulator"
```

---

## Phase F — Release

### Task 26: Release the Android app

**Files:**
- Create: `.github/workflows/release.yml`
- Modify: `app/build.gradle.kts` — release signing from environment variables only

**Interfaces:**
- Consumes secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`.

- [ ] **Step 1: Port the signing block**

Copy the `signingConfigs` block and `releaseSigningFrom` helper from `~/pets/home-app`. It already
resolves everything from the environment and decodes into `build/`, which is gitignored — keep that
property exactly.

- [ ] **Step 2: Generate a fresh upload key**

**A new key, not the old one.** Store the keystore outside the repository, put its base64 in the four
secrets above, and write the recovery procedure in a private note — not in this repository.

- [ ] **Step 3: Write the release workflow's Android job**

```yaml
  android:
    if: needs.release-please.outputs.release_created == 'true'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: '21' }
      - run: ./gradlew bundleRelease
        env:
          ANDROID_KEYSTORE_BASE64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}
          ANDROID_KEYSTORE_PASSWORD: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
          ANDROID_KEY_ALIAS: ${{ secrets.ANDROID_KEY_ALIAS }}
          ANDROID_KEY_PASSWORD: ${{ secrets.ANDROID_KEY_PASSWORD }}
      - uses: r0adkll/upload-google-play@v1
        with:
          serviceAccountJsonPlainText: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}
          packageName: dev.isachivka.agtermremote
          releaseFiles: app/build/outputs/bundle/release/app-release.aab
          track: internal
```

**`internal` first.** Promotion to production is a decision, not a side effect of a merge.

- [ ] **Step 4: Confirm no workflow uses `pull_request_target`**

Run: `grep -rn 'pull_request_target' .github/`
Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -s -m "ci: sign and publish the Android app to Play's internal track"
```

### Task 27: Release the Mac app

**Files:**
- Modify: `.github/workflows/release.yml`
- Create: `mac/scripts/dmg.sh`

- [ ] **Step 1: Write `dmg.sh`**

`hdiutil create` over the bundled `.app` with a symlink to `/Applications`.

- [ ] **Step 2: Add the macOS release job**

Runs on `macos-latest`, executes `mac/scripts/bundle.sh` then `dmg.sh`, and attaches the `.dmg` to the
GitHub release with `gh release upload`.

- [ ] **Step 3: Create the Homebrew tap**

```bash
gh repo create isachivka/homebrew-tap --public
```

A cask pointing at the release asset, with `sha256` updated by the release job.

- [ ] **Step 4: Verify the download path by hand once**

Download the `.dmg` on a Mac that has never built this project, install it, and **write down every
screen Gatekeeper shows**. That transcript is the README's Gatekeeper section; it must be written from
what actually happened, not from what is expected to happen.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -s -m "ci: build, package and publish the Mac app"
```

### Task 28: The documentation a stranger needs

**Files:**
- Modify: `README.md`
- Create: `docs/pairing.md`, `docs/architecture.md`, `docs/play-listing.md`

- [ ] **Step 1: Write the README**

In this order: what it is and what it is not; that agterm is required and is somebody else's project;
install the Mac app (Homebrew first, `.dmg` second); the Gatekeeper section written from Task 27 step
4; arrange an address (spec §6, verbatim); install from Play; scan; done. Then: how to build, how to
run each test suite, and the compatibility statement about agterm's control socket.

- [ ] **Step 2: Write `docs/pairing.md`**

The payload format table, the ALPN split, the window's lifetime, and the measured scanning distance
from Task 23 step 4.

- [ ] **Step 3: Write `docs/architecture.md`**

The layer diagram from spec §3.1 and one paragraph per component saying what it owns.

- [ ] **Step 4: Write `docs/play-listing.md`**

The store description, and the data-safety answers: no data collected, no data shared, all traffic to
a machine the user controls. Keep it in the repository so a listing change is reviewable.

- [ ] **Step 5: Run every guard one last time**

```bash
./scripts/check-no-tokens.sh && ./scripts/check-no-credentials.sh \
  && ./scripts/check-no-private-keys.sh && ./scripts/check-no-addresses.sh
```

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -s -m "docs: install, pair, build and the Play listing"
```

---

## Acceptance, restated as commands

| Spec §12 criterion | How it is checked |
|---|---|
| One scan, no file transfer, no second scan | Task 23 step 4, on real hardware |
| A certificate-less connection cannot reach the API | `go test ./internal/enroll/ -run TestNoCertificateCanEverMeanAPI` |
| The token is single-use, expires, and never hits disk | `go test ./internal/enroll/ -run Window` |
| The QR is read by a real camera; the distance is recorded | `docs/pairing.md` |
| Nothing personal in the repository | the four `scripts/check-*.sh`, required on every PR |
