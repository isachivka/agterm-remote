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
| PNG handed to the emulator | 1060×1060 px, 20 px per module |
| Displayed on | the virtual scene's **wall** poster, 2 m × 2 m — ≈ 33 mm per module at 1:1 |
| Analysis stream | requested 1280×960 |
| Window open → enrolled | 19 s, of which 12 s is the script waiting for the camera-walk animation |

**"20 pixels per module" is a property of the PNG and not of anything the decoder saw**, and the
distinction matters enough to spell out. What reached the decoder was a 1280×960 render of that poster
from an unmeasured distance and angle, so the symbol's apparent size in the analysis frame — the only
figure a decode threshold can be stated in — **was not measured on this run**. Measuring it means
dumping one analysis frame to disk and counting modules across the symbol in it; nothing here does
that, and no pixels-per-module claim should be read into the table above.

And one negative result, which is the most useful thing on this page and is stated with its
limitation: **the identical symbol on the scene's `table` poster was not read at all**, repeatedly,
over minutes, while the viewfinder showed it plainly and the app went on saying *Still nothing*.

What changed between the two was **three variables at once**: the poster went from 2 m to 1 m square,
and from vertical to flat, which changed the apparent size, the distance and the viewing angle
together. So the honest statement is *it read there and not here, and we do not know which variable
did it*. It is not evidence for a pixels-per-module cliff:

- perspective defeats a grid decoder independently of module size, and the flat poster was seen at a
  glancing angle;
- the virtual scene's texture filtering at a glancing angle has no counterpart on a real lens, so the
  blur in that frame is the renderer's rather than an optic's.

Separating them would take one variable at a time — the same 2 m poster at two distances, then the
same distance at two angles — and would still be a measurement about a renderer.

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

0. You need `qrencode` (`brew install qrencode`) and `adb` on the path — the emulator script checks
   for both and this list used to assume them.

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

3. On the phone, with it plugged in and USB debugging on:

   ```
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n dev.isachivka.agtermremote/.MainActivity
   ```

   Allow the camera when it asks, and point the phone at the code on the Mac's screen.

4. Record **the distance at which it read**, in centimetres, together with the size the code was
   displayed at and the phone. Walk it in until it reads, then out until it stops, and write down
   both. Add it to the table above, replacing this sentence.

If it does **not** read at any distance, that is the result this criterion exists to produce, and it
is worth more than a green run: capture what the viewfinder showed and file it. The three things most
likely to be wrong are the analysis resolution (the code needs 7–9 pixels per module and the frame is
1280 px across), autofocus at close range, and the phone's screen refresh beating against the Mac's.

## Known gaps, stated rather than discovered

- **A key that cannot sign is refused, not repaired.** The enrolment presents no client certificate —
  it only sends the certificate's bytes — so a phone whose keystore key will not sign would enrol
  perfectly and then be unable to reach the API, holding a receipt for a pairing that cannot work, and
  pinned on the Mac where removing it needs a screen that does not exist yet. `EnrolGate` therefore
  refuses **before any socket is opened**, and says so.

  What it does **not** do is replace the key. That verdict is reached by catching a broad exception,
  and wiring it to a `clear()` is what destroyed the owner's identity on 2026-07-29 — so the
  replacement has to follow from a button somebody presses, and that button belongs with the Settings
  screen that owns unpairing. Until then the remedy is a reinstall, which takes the keystore entry
  with it, and the refusal names it.
- **The permission dialog itself is not exercised by any automated run.** `PairingScreenTest` covers
  what the screen does when the camera is unavailable; the emulator script grants the permission
  rather than tapping through the dialog. What a *permanent* denial looks like — Android silently
  declining to show the dialog again — has been reasoned about and not observed.
- **A camera that will not open lands on the paste field rather than crashing**, which was run: on an
  emulator booted `-camera-back none -camera-front emulated`, `bindToLifecycle` raises
  `IllegalArgumentException: No available camera can be found`, the screen shows *No camera to read the
  code* with the paste field under it, and the process stays alive. The other shape — a camera another
  application is holding, which raises the **checked** `CameraUnavailableException` — goes through the
  same catch and is covered by `CameraGuardTest` rather than by a run.
- **A rotation during the enrolment cancels it.** The exchange runs in the composition's scope, so
  turning the phone mid-pairing ends it; if the bridge had already spent the token, the Mac is paired
  and the phone is not. It self-heals on the next pairing — the Mac replaces the phone it pinned — and
  the behaviour is left alone rather than papered over with a cancel button, which would be a second
  and deliberate way into the same state.
