# Pairing, and what has actually been proved about the camera

One QR code, shown by the Mac and read by the phone. This page is about the reading half: what the
screen does, what has been run, and — the part that matters most — **what has not**.

## The screen

Five states, one screen, and one way into the pairing from either route.

| State | What it shows |
|---|---|
| `Scanning` | The viewfinder, and the paste field under it. |
| `NeedsCamera` | No camera, or the owner refused it. The paste field, alone. |
| `Working` | Talking to the Mac. Nothing is stored yet. |
| `Failed(reason)` | One sentence saying what went wrong **and what to do next**, and a Try again button. |
| `Paired(fingerprint)` | The receipt: the fingerprint the Mac stored for this phone. |

Two things about it are worth stating rather than reading out of the code:

- **The paste field is always there**, under the viewfinder as well as instead of it. The payload is
  base64 text precisely so a code can travel through any channel the owner already has. Reaching it
  needs no permission.
- **The scanner has no path of its own.** The text a symbol carried and the text a person pasted go
  through the same callback, into `EnrollCodec` — the decoder the shared vectors in `wire/` pin — and
  fail the same way. There is exactly one decoder reachable from the camera, and
  `PairingScreenTest.aPastedCodeEnrolsTheSameWayAScannedOneDoes` drives both routes and compares the
  payloads that came out.

## What has been run against a camera

**On the emulator's virtual scene, end to end into an enrolment against a bridge built from this
tree.** `scripts/scan-end-to-end.sh` is the whole thing in one command: it starts a bridge with a
scratch state directory, opens an enrolment window through the real control socket, renders the code
it gets back as a QR symbol, hands that symbol to the emulator's virtual scene, walks the virtual
camera to it, forwards the port, opens the app, and waits for the bridge to say a phone enrolled.

Nothing about the pairing is injected. The phone learns the address, the fingerprint and the one-time
token from the picture.

Measured on 2026-09-09, on an API 37 arm64 emulator (`sdk_gphone64_arm64`, a Pixel 9 Pro XL AVD):

| | |
|---|---|
| Payload | 112 characters of base64 |
| Symbol | QR version 6 — 53×53 modules — error correction M, 4-module quiet zone |
| Rendered | 20 px per module, 1060×1060 px |
| Displayed on | the virtual scene's **wall** poster, 2 m × 2 m — so ≈ 33 mm per module at 1:1 |
| Analysis stream | requested 1280×960 |
| Window open → enrolled | 19 s, of which 12 s is the script waiting for the camera-walk animation |

And one negative result worth keeping, because it is the same cliff the 2026-08-09 probe measured:
**the identical symbol on the scene's `table` poster (1 m × 1 m, lying flat, so strongly foreshortened
from where the camera stands) was not read at all**, repeatedly, over minutes. The viewfinder showed
it clearly and the app went on saying *Still nothing*. Halving the apparent module size and adding
perspective was enough to cross from "reads in about a second" to "does not read". That is why the
script uses the wall.

Two failure states were also seen on the device rather than only in a test, both through the camera:

- a **spent** code — scanned a second time — produced *"That pairing code did not work. Open a new one
  on your Mac and scan it."* and not a claim that the Mac was unreachable. Neither side logged
  anything, which is what the design says should happen: the window was shut, so the handshake died on
  ALPN before any certificate was presented.
- a code whose **bridge was gone** produced *"Nothing answered at the address on the code. Check your
  Mac is awake and reachable from this phone, then scan a new code."*

## What has NOT been run: a real lens. This is outstanding.

Spec §5.7 and the acceptance criteria require that **the QR is scanned by a real camera on real
hardware at least once, and the distance it worked at is recorded.** That has not been done, and an
emulator does not do it.

The distinction is not pedantry, and the reason is specific: the project this one succeeds shipped a
viewfinder that compiled, passed its tests on synthetic frames, had never met a lens, and failed the
first time a human used it. A virtual scene renders a perfect, evenly lit, motionless, glare-free
symbol into a synthetic camera pipeline. It cannot produce autofocus hunting, rolling shutter, a
screen's own pixel grid beating against the symbol, the reflection of a room in a laptop's display, or
a hand that shakes. Every one of those is a way this screen can fail that nothing here has tested.

**This criterion stays open until somebody does the following.** It takes about two minutes and needs
no help from anyone.

1. On the Mac, from this repository:

   ```
   cd bridge && go build -o /tmp/bridge ./cmd/agterm-remote-bridge
   /tmp/bridge -listen 0.0.0.0:8459 -advertise <the Mac's LAN address>:8459 \
       -advertise-scheme plain -state-dir /tmp/pair-state -log /tmp/bridge.log
   ```

   `-advertise` must be an address the **phone** can reach — the Mac's address on the same wifi, not
   `127.0.0.1`. Nothing about the phone's own network is needed beyond being on it.

2. In a second terminal, open a window and put the code on the screen:

   ```
   code=$(printf '%s' '{"verb":"pair-open","ttl_seconds":300,"scheme":"plain","advertise":"<same address>:8459"}' \
     | nc -U /tmp/pair-state/control.sock \
     | python3 -c 'import json,sys; print(json.load(sys.stdin)["payload"])')
   qrencode -o /tmp/code.png -s 20 -m 4 -l M "$code"
   open /tmp/code.png
   ```

   Then make the image about **10 cm across** on the screen — Preview at a known zoom, or just
   full-screen it and measure with a ruler; the number that matters is the one you write down. The
   window is open for five minutes; if it lapses, run this step again, and leave step 1 running.

3. On the phone: install the debug build, open it, allow the camera, and point it at the screen.

4. Record **the distance at which it read**, in centimetres, together with the size the code was
   displayed at and the phone. Walk it in until it reads, then out until it stops, and write down
   both. Add it to the table above, replacing this sentence.

If it does **not** read at any distance, that is the result this criterion exists to produce, and it
is worth more than a green run: capture what the viewfinder showed and file it. The three things most
likely to be wrong are the analysis resolution (the code needs 7–9 pixels per module and the frame is
1280 px across), autofocus at close range, and the phone's screen refresh beating against the Mac's.

## Known gaps, stated rather than discovered

- **A key that cannot sign still pairs.** The enrolment presents no client certificate — it only sends
  the certificate's bytes — so a phone whose keystore key will not sign completes the pairing and then
  cannot reach the API. **The screen has no way out of that**, because the remedy is replacing the
  key, and replacing a key on a verdict this broad is exactly what destroyed the owner's pairing on
  2026-07-29 - so it must follow from something the owner pressed, and there is no such button yet.
  It belongs with the Settings screen that owns unpairing. Until then the terminal's copy for that
  failure names the remedy that does work without one: reinstall, which takes the keystore entry with
  it, and pair again.
- **The permission dialog itself is not exercised by any automated run.** `PairingScreenTest` covers
  what the screen does when the camera is unavailable; the emulator script grants the permission
  rather than tapping through the dialog.
