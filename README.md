# agterm-remote

Your [agterm](https://github.com/umputun/agterm) sessions on your phone. Pair by scanning one QR
code.

> **Early.** The first release exists and installs. The Android half ships to Play's internal
> testing track rather than to the store, and the QR code has never been read by a real camera on
> real hardware — [docs/pairing.md](docs/pairing.md) says what was proved and how to close it.

## What it is

Two programs that put a Mac's agterm sessions on an Android phone:

- a **macOS menu-bar app**, installed on the machine agterm runs on, which carries the bridge;
- an **Android app**, which shows the same sessions and workspaces, scrollback, typing, resizing and
  pane splits.

Pairing is one QR code shown by the Mac app and scanned by the phone. After that the two hold each
other's keys and talk over a mutually authenticated TLS connection.

The scanner has been run end to end against a real bridge on an emulator's virtual scene. It has
**not** been run against a real camera on real hardware, which is an acceptance criterion rather than
a nicety — [docs/pairing.md](docs/pairing.md) records what was proved, what was not, and exactly how
to close it.

## agterm is a prerequisite

[agterm](https://github.com/umputun/agterm) is a separate macOS terminal, by a different author.
This project is a client of its local control socket: it has no terminal of its own, spawns no shell,
and shows nothing that is not an agterm session. You install and run agterm yourself; this project
cannot bundle it.

Because the socket belongs to agterm, its shape is not under this project's control. Releases here
name the agterm versions they were verified against.

## Install

**The Mac app** — download `AgtermRemote-<version>.dmg` from
[Releases](https://github.com/isachivka/agterm-remote/releases), open it, and drag the app to
Applications. The Go bridge is inside the bundle; there is nothing else to install, no `go install`
first and no launchd plist afterwards. The bridge is a child process of the app and dies with it.

The bundle is **ad-hoc signed**, not signed with a paid Apple Developer ID and not notarised. macOS
will therefore refuse the first launch and offer the app in System Settings → Privacy & Security
instead, where **Open Anyway** allows it. The exact wording changes between macOS versions, so this
paragraph describes the shape of it rather than pretending to a transcript nobody recorded.

Building it yourself gets you the same bundle and the same signature:

```
cd mac && ./scripts/bundle.sh
```

**The phone app** — Play's internal testing track, which needs an invitation from the developer
account. There is no public listing yet.

```
./gradlew installDebug   # or build it yourself
```

## Arrange an address

The phone dials the Mac directly. How it gets there is yours to arrange and this project integrates
with none of it: a port forward with a fixed address, a port forward with a dynamic-DNS name, a
Tailscale or WireGuard address, or a reverse proxy in front. Whatever you choose, the Mac app asks
for one thing: the address the phone should dial. It does not ask what is in between — the bridge
answers TLS and plain HTTP alike on its one port, and the phone works out which to speak and
remembers.

## Pair

Open the Mac app. It opens its one window on first run; save the address, and the code appears
beneath it. On the phone, scan it. That is the whole of it: one code, one direction, no file to move
across and no second scan. The code carries the address, the bridge's certificate fingerprint and a
single-use token that expires in five minutes.

[docs/pairing.md](docs/pairing.md) records what has and has not been proved about the camera — the
read on real hardware is still outstanding. The payload format is in [wire/README.md](wire/README.md),
and the ALPN split that keeps a certificate-less connection away from the API is argued in
`bridge/internal/enroll/alpn.go`.

## Build and test

```
cd bridge && go test ./...          # the Go bridge, no third-party dependencies
./gradlew testDebugUnitTest         # Android unit tests, from the root
cd mac    && swift test             # the Mac app, including bridge integration tests
./scripts/check-*.sh                # the guards CI requires on every pull request
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) and
[SECURITY.md](SECURITY.md).

## Licence

[Apache-2.0](LICENSE).
