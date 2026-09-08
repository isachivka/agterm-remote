# Security policy

## Reporting a vulnerability

Report privately, through GitHub's private security advisories:

**<https://github.com/isachivka/agterm-remote/security/advisories/new>**

That opens a report only the maintainer can see. Please do not open a public issue, and please do
not post the details in a pull request or a discussion. If you would rather start with a nudge than
a full report, mention [@isachivka](https://github.com/isachivka) on GitHub and ask for a private
channel.

Include, as far as you can: what an attacker gains, the steps to reproduce it, and the versions of
the Mac app, the Android app and agterm you saw it on.

The project is maintained by one person in his spare time, so expect an acknowledgement in days
rather than hours. Fixes ship in the next release; advisories are published once a fix is available.

## Supported versions

Only the latest release. There is no back-porting.

## Threat model

What the design is meant to withstand, and what it is not:

| Adversary | Outcome |
|---|---|
| Anyone on the internet who finds the port | TLS handshake failure; bounded work; no log line per attempt; no disk write |
| A proxy or router in the path | Moves bytes it cannot read; mTLS runs inside the upgraded stream |
| A man in the middle during pairing | Fails when the server certificate does not match the fingerprint carried in the QR code |
| Someone who photographs the QR code | Has five minutes and one attempt at a single-use token, and must also reach the address |
| A stolen phone | Holds a hardware-backed key it cannot export; the owner unpairs from the Mac |
| A stolen laptop | Holds everything. Out of scope, as it is for any terminal on that machine |

### A stolen laptop is explicitly out of scope

Someone with the unlocked Mac already has the terminal, the shell and every key on the machine. This
project does not defend against that, and no design of it could: it is a client of a terminal that
is already there. Full-disk encryption and a locked screen are the defence, and they are the
operating system's job, not this project's.
