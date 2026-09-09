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

### `-trimpath`, and why the script re-checks it

Without it the Go binary carries the absolute path of every source file it was compiled from —
measured on 2026-09-09, 25 lines naming the builder's home directory, shipped to whoever downloads
the app. None of the six guard scripts in `scripts/` can see this: they read tracked text, and the
binary is never tracked. So `bundle.sh` greps the bytes it is about to sign for `/Users/` and refuses
rather than trusting a flag nobody re-reads. CI repeats both checks.

### Signing, and what a downloaded copy does

The bridge is ad-hoc signed in its own right before the bundle is. `go build` signs only the `arm64`
slice it produces natively and leaves the cross-compiled `x86_64` one bare, so the joined file is
half-signed until we sign it: `codesign -dv` reports `adhoc` off the arm64 slice while `codesign -v`
says *code object is not signed at all*. Signed, `codesign -vvv --deep` passes on the whole bundle and
the bridge is sealed into `CodeResources` like any other resource.

Ad-hoc is not notarised, and that is visible. Measured on macOS 26.6:

- `spctl -a -t exec` on the assembled bundle: **rejected**. Expected — there is no Developer ID.
- A copy carrying `com.apple.quarantine` (what a browser download gets) **hangs the bridge at spawn**.
  The process exists and never reaches `main`, because Gatekeeper is waiting on a consent dialogue.
  Since the app spawns the bridge rather than the person launching it, there is no dialogue to answer
  and no error either: Start would appear to do nothing.
- The same copy with the attribute stripped (`xattr -dr com.apple.quarantine`, or `ditto --noextattr`)
  runs immediately, and the bundle's signature survives the strip.

A bundle built from source on the machine that runs it is never quarantined, so this is a property of
*downloading* a release, not of building one.

## Layout

- `Sources/AgtermRemoteCore` — everything testable without AppKit: the address type and its parser,
  the bridge status model, the menu-bar mark, the pairing-window model.
- `Sources/AgtermRemote` — the AppKit shell: the status item, the menus, the pairing window.
- `Sources/AgtermRemoteIcon` — a build-time tool that renders the same mark into an `.iconset`, so
  the icon and the menu bar cannot drift apart.
- `Tests/AgtermRemoteCoreTests` — including the tests that check the *tests*: that no menu item is
  enabled unless it is wired, that no state renders the same mark as another, and that the probe
  reading the icon can tell top from bottom.
