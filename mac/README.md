# The macOS app

The menu-bar app. Swift, SwiftPM, no dependencies — deliberately: nothing here resolves a package at
build time, and if that ever changes it is a decision worth arguing rather than absorbing.

## Building it

```sh
swift build            # a bare Mach-O executable
swift test             # swift-testing (@Test), not XCTest
scripts/bundle.sh      # AgtermRemote.app around that binary, bridge included — see below
```

`bundle.sh` needs a Go toolchain, because the bridge ships inside the bundle. It says so and stops
rather than assembling an app whose Start and Stop are permanently grey.

**`swift build` does not produce an application.** It produces an executable, and three things this
app needs live in the bundle rather than in the binary: `LSUIElement` (read from `Info.plist`, and
without it the only thing keeping the app out of the Dock is a runtime call), `SMAppService.mainApp`
(the login item, which requires bundle identity and cannot work from a loose executable), and being a
thing a person can double-click. `scripts/bundle.sh` assembles it and signs it **ad-hoc**
(`codesign -s -`) — not Developer ID, not notarised, because this project carries no paid Apple
identity. Apple Silicon refuses to execute wholly unsigned code, so ad-hoc is the floor rather than a
preference.

`bundle.sh` deliberately does **not** install, copy into `/Applications`, register a login item, or
start anything. Assembling and installing are separate acts, and whoever runs it authorises them
separately.

## What CI does with this directory

`.github/workflows/ci.yml` has a `mac` job that runs `swift build`, `swift test`, and then
`scripts/bundle.sh` on `macos-latest` — after which it asserts three things about the artefact that
no guard script can see, because they read tracked text and this is 19 MB of Mach-O that never enters
the repository: that the bundled bridge is universal, that it names no build machine, and that it
actually runs. It is gated on `mac/Package.swift` existing, so it skipped from the first commit of
this repository until this package landed — a job with nothing to build must not fail, and a job with
something to build must not be silently absent.

There is no self-hosted runner and there must not be one. A self-hosted runner executes whatever a
workflow file says, and a pull request from a fork can propose a workflow file. The signing keys for
the Android half live in repository secrets; a runner on somebody's own Mac plus a job that reads
those secrets is how they leave.

## The bridge is a child of this app

There is **no launchd job, no plist and no installer**. `swift build` produces a menu-bar app that
spawns `agterm-remote-bridge`, passes it the address to listen on, the state directory and its own
pid, and terminates it on the way out. The bridge polls that pid and exits when it goes, which is the
half that survives a force-quit — it polls every couple of seconds, so a crashed app leaves a bridge
alive for up to that long by design.

Start and Stop in the menu are enabled **only when a bridge binary is actually there**, because a
control that cannot do its job must look dead rather than pressable.

`bundle.sh` puts one there. It builds `./cmd/agterm-remote-bridge` for `arm64` and `amd64` with
`-trimpath` and `CGO_ENABLED=0`, joins them with `lipo`, and writes the result to
`Contents/Resources/agterm-remote-bridge` — which is the first place `BundledBridge.url(in:)` looks,
and the only thing anybody has to install. No `go install` beforehand, no launchd plist afterwards,
nothing left listening once the app is dragged to the Bin.

`swift build` alone produces no bundle and therefore no bridge, which is why a fresh clone still has
both items greyed. For development, put a locally built one beside the executable:

```sh
(cd ../bridge && go build -o ../mac/.build/debug/agterm-remote-bridge ./cmd/agterm-remote-bridge)
```

### `-trimpath`, and why a byte grep is not how you check it

Without it the Go binary carries the absolute path of every source file it was compiled from —
measured, 25 lines per slice naming the builder's home directory, shipped to whoever downloads the
app. None of the six guard scripts in `scripts/` can see this: they read tracked text, and the binary
is never tracked.

The first version of this check grepped the bytes for `/Users/`. **It passed on a binary carrying 50
source paths and 1400 toolchain paths** — reproduced by building from a checkout under `/private/tmp`,
and equally true for a checkout on an external volume, under `/opt`, or in a Linux container. It was
a test about *where the build happened* wearing the name of a test about *what shipped*.

The real check is Go's own record. `-trimpath` is written into the build info of every binary it
applies to, so `bundle.sh` asks for it directly — **per slice**, because `go version -m` reads one
slice of a universal file and would otherwise answer for whichever half it picked:

```sh
lipo -thin x86_64 -output slice bridge && go version -m slice | grep -- '-trimpath=true'
```

`CGO_ENABLED=0` and the expected `GOARCH` come from the same record. The byte grep is kept below it
as a cheaper second net, no longer as the guard. CI repeats all of it.

### Nothing enters the bundle until it has passed

Every check runs on a staged file outside the bundle, and a refusal removes the half-built `.app`
altogether. The earlier arrangement wrote the binary into `Contents/Resources` first and refused
afterwards, which left the offending artefact inside a bundle whose `_CodeSignature` no longer
matched it: `codesign -dv` reported `Sealed Resources=none` and `spctl` failed, on a bundle a person
can still double-click. It is now a complete signed bundle or none.

### Signing, and what a downloaded copy does

The bridge is ad-hoc signed in its own right before the bundle is. `go build` signs only the `arm64`
slice it produces natively and leaves the cross-compiled `x86_64` one bare, so the joined file is
half-signed until we sign it: `codesign -dv` reports `adhoc` off the arm64 slice while `codesign -v`
says *code object is not signed at all*. Signed, `codesign -vvv --deep` passes on the whole bundle and
the bridge is sealed into `CodeResources` like any other resource.

Ad-hoc is not notarised, and that is visible. Measured on macOS 26.6:

- `spctl -a -t exec` on the assembled bundle: **rejected**. Expected — there is no Developer ID.
- A copy carrying `com.apple.quarantine` (what a browser download gets) **hangs the bridge at spawn**.
  `Process.run()` succeeds and returns a pid, the child sits in `SN`, never reaches `main`, writes
  nothing and never exits. Since the app spawns the bridge rather than the person launching it, there
  is no dialogue to answer and no error either.
- The same copy with the attribute stripped (`xattr -dr com.apple.quarantine`, or `ditto --noextattr`)
  runs immediately, and the bundle's signature survives the strip.

A bundle built from source on the machine that runs it is never quarantined, so this is a property of
*downloading* a release, not of building one. It is, however, the **first-run experience of every
person who downloads one**, so the app does not leave it as a button that does nothing:

- **Quarantine is read before the spawn.** `getxattr` answers from an ordinary unentitled process, so
  `BridgeProcess.start` refuses with a paragraph naming quarantine and carrying the `xattr -dr`
  command, and nothing is spawned. Verified end to end against a real quarantined bundle: 0 processes
  started, the message shown.
- **The app will not clear it**, and not because it cannot — measured, `removexattr` from this process
  *succeeds*, on the nested binary and on the `.app` alike. Quarantine is macOS's record that this
  code came from outside; an app that erased that record about itself as a side effect of somebody
  pressing Start would be deleting the only Gatekeeper signal a non-notarised app is subject to. A
  test asserts the capability so nobody re-derives the false premise, and another asserts that no
  source file here calls `removexattr` or `setxattr`.
- **A spawn timeout is the generic backstop.** A child that is alive and has written nothing after
  `BridgeProcess.silenceCeiling` (two seconds) is stopped and reported as *started but never said
  anything* — which covers freezes that have nothing to do with Gatekeeper. It works because the
  bridge announces its listening address on stderr immediately, so `arguments()` must never pass
  `--log`; a test holds that in place.

## Layout

- `Sources/AgtermRemoteCore` — everything testable without AppKit: the address type and its parser,
  the bridge status model, the menu-bar mark, the pairing-window model.
- `Sources/AgtermRemote` — the AppKit shell: the status item, the menus, the pairing window.
- `Sources/AgtermRemoteIcon` — a build-time tool that renders the same mark into an `.iconset`, so
  the icon and the menu bar cannot drift apart.
- `Tests/AgtermRemoteCoreTests` — including the tests that check the *tests*: that no menu item is
  enabled unless it is wired, that no state renders the same mark as another, and that the probe
  reading the icon can tell top from bottom.
