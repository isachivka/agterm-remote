# agterm-remote

Your [agterm](https://github.com/umputun/agterm) sessions on your phone. Pair by scanning one QR
code.

> **Under construction.** Nothing here is installable yet. This README is a stub; it will be
> replaced with real instructions once the first release exists.

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

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) and
[SECURITY.md](SECURITY.md).

## Licence

[Apache-2.0](LICENSE).
