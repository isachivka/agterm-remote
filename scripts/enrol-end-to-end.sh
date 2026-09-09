#!/usr/bin/env bash
#
# Enrol the app, running on an emulator, against a bridge built from this repository.
#
# This is the only thing in the repository that puts the two halves in the same room. Everything else
# tests one side against a fake of the other, and a fake of the far end is evidence about the fake.
#
# What it does, in order:
#
#   1. builds the Go bridge from this tree;
#   2. gives it a scratch state directory (a fresh identity, no paired phones);
#   3. starts it on loopback, pointed at whatever agterm socket is around;
#   4. opens an enrolment window through the REAL local control socket, exactly as the Mac app's
#      pairing panel does, and takes the pairing code out of the reply;
#   5. forwards the port into the emulator with `adb reverse` — loopback ON THE EMULATOR, because
#      dialling the host alias instead hangs in SYN-SENT against the emulator's NAT;
#   6. runs the instrumented test with the code as an argument.
#
# Nothing here touches the owner's own bridge, their agterm state, or a phone. The state directory is
# created under a temporary path and removed on the way out.
#
# Usage: scripts/enrol-end-to-end.sh [port]

set -euo pipefail

port="${1:-8459}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d)"
state="$work/state"
mkdir -p "$state"
chmod 700 "$state"

: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
adb="$ANDROID_HOME/platform-tools/adb"

bridge_pid=""
cleanup() {
    [ -n "$bridge_pid" ] && kill "$bridge_pid" 2>/dev/null || true
    "$adb" reverse --remove "tcp:$port" >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

# The emulator has to be UNLOCKED, and the failure when it is not is unrecognisable.
#
# Android's file-based encryption keeps /sdcard unavailable until the device has been unlocked once.
# Gradle's connected-test task creates a directory there for additional test output before it runs
# anything, and when that mkdir fails it neither fails the build nor runs the tests: the task reports
# success having executed nothing. Checked here, loudly, because "0 tests, green" is the exact shape
# of evidence this project refuses.
if ! "$adb" shell ls /sdcard/Android >/dev/null 2>&1; then
    echo "the emulator's storage is not available - unlock the device and run this again" >&2
    exit 1
fi

echo "== building the bridge"
(cd "$root/bridge" && go build -o "$work/agterm-remote-bridge" ./cmd/agterm-remote-bridge)

echo "== starting it on 127.0.0.1:$port with a scratch state directory"
"$work/agterm-remote-bridge" \
    -listen "127.0.0.1:$port" \
    -advertise "127.0.0.1:$port" \
    -state-dir "$state" \
    -log "$work/bridge.log" &
bridge_pid=$!

for _ in $(seq 1 50); do
    [ -S "$state/control.sock" ] && break
    sleep 0.2
done
[ -S "$state/control.sock" ] || { echo "the bridge never opened its control socket"; cat "$work/bridge.log"; exit 1; }

echo "== opening an enrolment window through the control socket"
payload="$(printf '%s' '{"verb":"pair-open","ttl_seconds":300,"advertise":"127.0.0.1:'"$port"'"}' \
    | nc -U "$state/control.sock" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["payload"])')"
echo "   code is ${#payload} characters"

echo "== forwarding the port into the emulator"
"$adb" reverse "tcp:$port" "tcp:$port"

echo "== running the instrumented test"
(cd "$root" && ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.isachivka.agtermremote.pairing.EnrollmentTest \
    -Pandroid.testInstrumentationRunnerArguments.pairingCode="$payload")

echo
echo "== what the bridge logged"
cat "$work/bridge.log"
echo
echo "== what it pinned"
python3 -c '
import json, sys
peers = json.load(open(sys.argv[1]))
for p in peers if isinstance(peers, list) else peers.get("peers", []):
    print("  ", p.get("fingerprint"), p.get("name"), p.get("paired_at"))
' "$state/peers.json"
