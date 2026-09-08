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

## agterm is a prerequisite

[agterm](https://github.com/umputun/agterm) is a separate macOS terminal, by a different author.
This project is a client of its local control socket: it has no terminal of its own, spawns no shell,
and shows nothing that is not an agterm session. You install and run agterm yourself; this project
cannot bundle it.

Because the socket belongs to agterm, its shape is not under this project's control. Releases here
name the agterm versions they were verified against.

## What it is not

- Not a relay or tunnel service. It does no NAT traversal; you arrange an address for your Mac.
- Not a dashboard. Terminal sessions, nothing else.
- Not an Android Auto app.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) and
[SECURITY.md](SECURITY.md).

## Licence

[Apache-2.0](LICENSE).
