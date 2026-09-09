# The macOS app

The menu-bar app. Swift, SwiftPM, no dependencies — deliberately: nothing here resolves a package at
build time, and if that ever changes it is a decision worth arguing rather than absorbing.

## Building it

```sh
swift build            # a bare Mach-O executable
swift test             # swift-testing (@Test), not XCTest
scripts/bundle.sh      # AgtermRemote.app around that binary — see below
```

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

`.github/workflows/ci.yml` has a `mac` job that runs `swift build` and `swift test` on
`macos-latest`. It is gated on `mac/Package.swift` existing, so it skipped from the first commit of
this repository until this package landed — a job with nothing to build must not fail, and a job with
something to build must not be silently absent.

There is no self-hosted runner and there must not be one. A self-hosted runner executes whatever a
workflow file says, and a pull request from a fork can propose a workflow file. The signing keys for
the Android half live in repository secrets; a runner on somebody's own Mac plus a job that reads
those secrets is how they leave.

## Layout

- `Sources/AgtermRemoteCore` — everything testable without AppKit: the address type and its parser,
  the bridge status model, the menu-bar mark, the pairing-window model.
- `Sources/AgtermRemote` — the AppKit shell: the status item, the menus, the pairing window.
- `Sources/AgtermRemoteIcon` — a build-time tool that renders the same mark into an `.iconset`, so
  the icon and the menu bar cannot drift apart.
- `Tests/AgtermRemoteCoreTests` — including the tests that check the *tests*: that no menu item is
  enabled unless it is wired, that no state renders the same mark as another, and that the probe
  reading the icon can tell top from bottom.
