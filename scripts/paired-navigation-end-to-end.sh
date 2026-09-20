#!/usr/bin/env bash
#
# Pair the app, running on an emulator, through its own screens, and prove it lands on the terminal.
#
# The unit tests around pairing are green and were green on the day the first person to pair on real
# hardware sat on the settings screen twice with sessions appearing only after the app was killed.
# None of them is the app. This one is: PairedNavigationTest drives MainActivity from the first frame
# - paste field, Pair button, the terminal's own top bar - against a bridge built from this tree.
#
# What it does, in order:
#   1. builds the Go bridge from this tree and starts it on loopback with a scratch state directory
#      (a short path: a unix socket path has a 104-byte ceiling);
#   2. opens an enrolment window through the real control socket and takes the code it mints;
#   3. clears the app's data so it opens on the pairing screen, grants the camera so no system
#      dialog covers the field, and forwards the bridge's port into the emulator with adb reverse;
#   4. installs the debug app and its test APK and runs the one test with the code as an argument.
#
# The verdict is the instrumentation's own, read through scripts/lib/instrumentation-verdict.sh, and
# the bridge's log is printed afterwards: "a phone enrolled" followed by "verb=sessions ok=true" is
# the whole claim, seen from the other side.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PORT="${PORT:-8459}"
STATE="${STATE:-/tmp/agtr-nav}"
PACKAGE=dev.isachivka.agtermremote

cd "$ROOT/bridge" && go build -o "$STATE.bridge" ./cmd/agterm-remote-bridge
rm -rf "$STATE" && mkdir -p "$STATE" && chmod 700 "$STATE"
"$STATE.bridge" --listen "127.0.0.1:$PORT" --advertise "127.0.0.1:$PORT" --state-dir "$STATE" --log "$STATE/bridge.log" &
BRIDGE=$!
trap 'kill "$BRIDGE" 2>/dev/null || true' EXIT
for _ in $(seq 1 50); do grep -q "ready" "$STATE/bridge.log" 2>/dev/null && break; sleep 0.1; done

CODE="$(python3 - "$STATE/control.sock" "127.0.0.1:$PORT" <<'PY'
import json, socket, sys
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM); s.settimeout(5); s.connect(sys.argv[1])
s.sendall(json.dumps({"verb": "pair-open", "ttl_seconds": 300, "advertise": sys.argv[2], "scheme": "tls"}).encode() + b"\n")
d = b""
while not d.endswith(b"\n"):
    d += s.recv(65536)
print(json.loads(d)["payload"])
PY
)"

cd "$ROOT"
./gradlew -q assembleDebug assembleDebugAndroidTest
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
"$ADB" shell pm clear "$PACKAGE" >/dev/null
"$ADB" shell pm grant "$PACKAGE" android.permission.CAMERA
"$ADB" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null

OUT="$(mktemp)"
"$ADB" shell am instrument -w -r \
    -e class "$PACKAGE.ui.nav.PairedNavigationTest" \
    -e pairingCode "$CODE" \
    "$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" > "$OUT" 2>&1 || true
"$ROOT/scripts/lib/instrumentation-verdict.sh" "$OUT" 1 pairingLandsOnTheTerminalWithoutARestart
echo "--- the bridge's side ---"
grep -E "enrolled|verb=sessions" "$STATE/bridge.log"
