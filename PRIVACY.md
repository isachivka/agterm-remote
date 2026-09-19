# Privacy policy

**agterm-remote collects nothing, sends nothing anywhere, and has no servers.**

This is the privacy policy for the Android application `dev.isachivka.agtermremote` and for the macOS
application it pairs with. Both are open source under Apache-2.0; the whole of their source is at
<https://github.com/isachivka/agterm-remote>, and anything stated here can be checked against it.

## What the app collects

Nothing. There is no analytics, no crash reporting, no advertising identifier, no telemetry and no
account. Nothing is transmitted to the author of this software or to any third party, because there
is no service to transmit it to: this project operates no servers at all.

## Where data goes instead

The Android app talks to **one** machine: the computer the person using it chooses, running the
macOS half of this same project, at an address that person supplies. That connection is mutually
authenticated with certificates the two devices exchange once, during pairing, and nothing else can
read it. The author has no access to that machine, to that connection, or to anything carried over
it.

## What is stored on the phone, and only on the phone

- **A private key**, generated inside the phone's hardware-backed Android Keystore during pairing.
  It cannot be extracted from the device — not by this app, and not by anyone with the file.
- **The paired computer's certificate fingerprint and address**, so the phone knows which machine it
  is allowed to talk to.
- **A small amount of interface state**, such as which workspaces are collapsed.

All of it lives in the app's private storage. Uninstalling the app destroys all of it, including the
key, which is why an uninstall means pairing again.

## Permissions, and why each exists

- **Camera** — to read the pairing QR code, and for nothing else. Frames are analysed in memory as
  they arrive and are never written to storage or transmitted. The permission is optional: the same
  code can be pasted as text instead, and the app works without ever being granted the camera.
- **Internet** — to reach the computer the person paired with, at the address they supplied. No other
  destination is ever contacted.

## Children

The application is not directed at children and collects no data from anyone, including children.

## Changes

This policy is versioned in the repository along with the code it describes. Its history is public:
<https://github.com/isachivka/agterm-remote/commits/main/PRIVACY.md>.

## Contact

Open an issue at <https://github.com/isachivka/agterm-remote/issues>.
