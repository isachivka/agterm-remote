#!/usr/bin/env bash
#
# Scan a real pairing code with the app's camera, on the emulator's virtual scene, into a real
# enrolment against a bridge built from this tree.
#
# `enrol-end-to-end.sh` puts the two halves in the same room with the code handed to the app as a
# string. This one puts a LENS between them: the code is rendered as a QR symbol, the emulator draws
# it into its virtual scene, the camera pipeline delivers frames of it, and the app's scanner reads
# the symbol and enrols. Nothing about the pairing is injected - the phone learns the address, the
# fingerprint and the token from the picture.
#
# **This is not a substitute for a real lens, and it is not offered as one.** What it proves is the
# software path end to end; what it cannot produce is a working distance, a focus behaviour, a screen
# with glare on it, or a hand that shakes. `docs/pairing.md` records that criterion as outstanding
# and says exactly how to close it.
#
# What it does, in order:
#
#   1. builds the Go bridge from this tree and starts it on loopback with a scratch state directory;
#   2. opens an enrolment window through the real local control socket, as the Mac app's pairing
#      panel does, and takes the code out of the reply;
#   3. renders that code as a QR symbol with `qrencode`;
#   4. hands the symbol to the emulator as the virtual scene's WALL poster and walks the virtual
#      camera to it, using the emulator's own console commands;
#   5. forwards the bridge's port into the emulator with `adb reverse`;
#   6. builds and installs the app, grants the camera permission, and opens it;
#   7. waits for the bridge to say a phone enrolled, and prints what it pinned.
#
# Nothing here touches the owner's own bridge, their agterm state, or a phone.
#
# Usage: scripts/scan-end-to-end.sh [port]
set -euo pipefail

port="${1:-8459}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# A SHORT path, and that is not tidiness. A unix socket path is capped at 104 bytes on macOS and the
# bridge refuses to open its control socket past that - which it says clearly, after doing everything
# else, so the first symptom is a run that got all the way to step 2 and stopped.
work="/tmp/agterm-remote-scan.$$"
state="$work/state"
mkdir -p "$state"
chmod 700 "$state"

: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
adb="$ANDROID_HOME/platform-tools/adb"
package="dev.isachivka.agtermremote"

bridge_pid=""
cleanup() {
    [ -n "$bridge_pid" ] && kill "$bridge_pid" 2>/dev/null || true
    "$adb" reverse --remove "tcp:$port" >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

command -v qrencode >/dev/null || {
    echo "qrencode is not installed - brew install qrencode" >&2
    exit 1
}

# The emulator has to be UNLOCKED, and the failure when it is not is unrecognisable: Android's
# file-based encryption keeps /sdcard unavailable until the device has been unlocked once, and half
# of what follows fails in ways that look like the thing under test.
if ! "$adb" shell ls /sdcard/Android >/dev/null 2>&1; then
    echo "the emulator's storage is not available - unlock the device and run this again" >&2
    exit 1
fi

echo "== building the bridge"
(cd "$root/bridge" && go build -o "$work/agterm-remote-bridge" ./cmd/agterm-remote-bridge)

echo "== starting the bridge on 127.0.0.1:$port with a scratch state directory"
"$work/agterm-remote-bridge" \
    -listen "127.0.0.1:$port" \
    -advertise "127.0.0.1:$port" \
    -advertise-scheme plain \
    -state-dir "$state" \
    -log "$work/bridge.log" &
bridge_pid=$!

for _ in $(seq 1 50); do
    [ -S "$state/control.sock" ] && break
    sleep 0.2
done
[ -S "$state/control.sock" ] || { echo "the bridge never opened its control socket"; cat "$work/bridge.log"; exit 1; }

echo "== opening an enrolment window through the control socket"
payload="$(printf '%s' '{"verb":"pair-open","ttl_seconds":300,"scheme":"plain","advertise":"127.0.0.1:'"$port"'"}' \
    | nc -U "$state/control.sock" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["payload"])')"
echo "   code is ${#payload} characters"

# -s 20: twenty pixels per module, so the texture the emulator samples is not the limiting factor.
# -l M: the error correction the Mac's own renderer uses. -m 4: the quiet zone the format requires,
# which a decoder needs as much as it needs the symbol.
echo "== rendering it as a QR symbol"
qrencode -o "$work/code.png" -s 20 -m 4 -l M "$payload"

echo "== putting it in the virtual scene and walking the camera to it"
# The macro is the emulator's own, shipped beside the scene. It walks the virtual camera into the
# room the two posters are in, so the code is in frame without anybody having to drive the scene by
# hand. Give it time to finish: it is an animation, and a screenshot taken mid-walk shows the camera
# somewhere it is not going to stay.
#
# **The WALL poster, not the table one, and that is an observation rather than a preference.** The
# same code on the scene's table poster - 1m square, lying flat, so seen small and at a glancing angle
# - was NOT read, repeatedly, while the viewfinder showed it plainly. On the wall poster, 2m square
# and roughly square-on, it reads in a second or two.
#
# **Which variable did it is not known**: size, distance and angle all changed together, and
# perspective defeats a grid decoder independently of module size. It is not evidence for a
# pixels-per-module threshold and must not be quoted as one - see docs/pairing.md.
"$adb" emu virtualscene-image wall "$work/code.png" >/dev/null
"$adb" emu automation play "$ANDROID_HOME/emulator/resources/macros/Walk_to_image_room" >/dev/null
sleep 12

echo "== forwarding the port the phone dials into the emulator"
# 127.0.0.1 and not the host alias: dialling the emulator's host alias hangs in SYN-SENT, an
# IPv4-mapped IPv6 socket against the emulator's NAT.
"$adb" reverse "tcp:$port" "tcp:$port"

echo "== building and installing the app"
# Built and installed rather than assumed present. `connectedDebugAndroidTest` UNINSTALLS the app when
# it finishes, so a run of this script after a test run met "package not found" from `pm clear` and
# stopped one line into the part that matters.
(cd "$root" && ./gradlew assembleDebug -q)
"$adb" install -r "$root/app/build/outputs/apk/debug/app-debug.apk" >/dev/null

echo "== opening the app on a phone that has never been paired"
"$adb" shell pm clear "$package" >/dev/null
# Granted rather than tapped, because a permission dialog is not what this run is about. The dialog
# itself, and what the screen does when it is refused, are covered by PairingScreenTest.
"$adb" shell pm grant "$package" android.permission.CAMERA
"$adb" shell am start -n "$package/.MainActivity" >/dev/null

echo "== waiting for the bridge to say a phone enrolled"
for _ in $(seq 1 60); do
    if [ -s "$state/peers.json" ] && grep -q fingerprint "$state/peers.json"; then
        echo
        echo "== the code was read through the camera and the bridge pinned this phone"
        python3 -c '
import json, sys
peers = json.load(open(sys.argv[1]))
for p in peers if isinstance(peers, list) else peers.get("peers", []):
    print("  ", p.get("fingerprint"))
    print("  ", p.get("name"), p.get("paired_at"))
' "$state/peers.json"
        echo
        echo "== what the bridge logged"
        cat "$work/bridge.log"
        exit 0
    fi
    sleep 1
done

echo
echo "no phone enrolled within 60 seconds." >&2
echo >&2
echo "if the app says a code did not work rather than saying nothing, it read a code from an" >&2
echo "EARLIER run: the scene keeps the last poster it was given, and a spent code is refused. If it" >&2
echo "says nothing at all, the symbol is not big enough in the frame - look at the emulator." >&2
echo >&2
echo "what the bridge logged:" >&2
cat "$work/bridge.log" >&2
exit 1
