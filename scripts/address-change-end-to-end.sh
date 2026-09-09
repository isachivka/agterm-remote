#!/usr/bin/env bash
#
# Pair against a real bridge, MOVE that bridge to a different port, change the address on the real
# settings screen, and reach the API again — with no re-pairing anywhere in it.
#
# This is the one property the settings screen exists for, and it is exactly the kind a unit test can
# pass while the product fails. Everything about the address change is a file write, and a file write
# says nothing about whether the pinned certificate still satisfies a handshake somewhere else.
#
# What it does, in order:
#
#   1. builds the Go bridge from this tree and gives it a scratch state directory;
#   2. starts it on 127.0.0.1:$port, advertising that address;
#   3. opens an enrolment window through the REAL local control socket and takes the code out of it;
#   4. forwards both ports into the emulator with `adb reverse` — loopback ON THE EMULATOR, because
#      dialling the host alias hangs in SYN-SENT against the emulator's NAT;
#   5. installs the app and the test app ONCE, then runs phase one, which pairs;
#   6. **stops the bridge and starts it again on $moved, with the SAME state directory** — the same
#      identity, the same pinned phone, a different port. That is a router renumbering, or a tunnel
#      rebuilt, as far as the phone is concerned;
#   7. runs phase two, which types the new address into the settings screen and reaches the API.
#
# **`am instrument` rather than a second `connectedDebugAndroidTest`**, and that is load-bearing: the
# Gradle task reinstalls the application, and an install wipes exactly the two things under test — the
# stored pairing and the keystore key. The pairing phase two operates on has to be the one phase one
# left on the device.
#
# Nothing here touches the owner's own bridge, their agterm state, or a phone. The state directory is
# created under a temporary path and removed on the way out.
#
# Usage: scripts/address-change-end-to-end.sh [port]        the moved port is port + 1

set -euo pipefail

port="${1:-8459}"
moved=$((port + 1))
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d)"
state="$work/state"
mkdir -p "$state"
chmod 700 "$state"

: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
adb="$ANDROID_HOME/platform-tools/adb"

package="dev.isachivka.agtermremote"
runner="$package.test/androidx.test.runner.AndroidJUnitRunner"
suite="dev.isachivka.agtermremote.settings.AddressChangeTest"

bridge_pid=""
cleanup() {
    [ -n "$bridge_pid" ] && kill "$bridge_pid" 2>/dev/null || true
    "$adb" reverse --remove "tcp:$port" >/dev/null 2>&1 || true
    "$adb" reverse --remove "tcp:$moved" >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

# The emulator has to be UNLOCKED, and the failure when it is not is unrecognisable — see the longer
# note in enrol-end-to-end.sh. "0 tests, green" is the exact shape of evidence this project refuses.
if ! "$adb" shell ls /sdcard/Android >/dev/null 2>&1; then
    echo "the emulator's storage is not available - unlock the device and run this again" >&2
    exit 1
fi

start_bridge() { # $1 = the port to listen on and advertise
    "$work/agterm-remote-bridge" \
        -listen "127.0.0.1:$1" \
        -advertise "127.0.0.1:$1" \
        -advertise-scheme plain \
        -state-dir "$state" \
        -log "$work/bridge.log" &
    bridge_pid=$!
    for _ in $(seq 1 50); do
        [ -S "$state/control.sock" ] && return 0
        sleep 0.2
    done
    echo "the bridge never opened its control socket"; cat "$work/bridge.log"; exit 1
}

# **The runner's own report, read rather than trusted.** `am instrument` exits 0 on a failed test, and
# it exits 0 having run nothing at all. So the transcript is kept and three things are checked in it:
# that the run said OK, that it was not a skip, and that at least one test executed.
run_phase() { # $1 = method, $2... = -e arguments
    local method="$1"; shift
    local out="$work/$method.txt"
    "$adb" shell am instrument -w -r \
        -e class "$suite#$method" "$@" "$runner" | tee "$out"
    grep -q "INSTRUMENTATION_STATUS: stream=.*OK\|^OK (" "$out" ||
        grep -q "INSTRUMENTATION_CODE: -1" "$out" ||
        { echo "== $method did not report success"; exit 1; }
    if grep -q "assumption_failure\|AssumptionViolated" "$out"; then
        echo "== $method SKIPPED ITSELF, which is not a pass"; exit 1
    fi
    grep -q "Tests run: 1\|numtests=1" "$out" || { echo "== $method ran no test"; exit 1; }
}

echo "== building the bridge"
(cd "$root/bridge" && go build -o "$work/agterm-remote-bridge" ./cmd/agterm-remote-bridge)

echo "== starting the bridge on 127.0.0.1:$port with a scratch state directory"
start_bridge "$port"

echo "== opening an enrolment window through the control socket"
payload="$(printf '%s' '{"verb":"pair-open","ttl_seconds":300,"scheme":"plain","advertise":"127.0.0.1:'"$port"'"}' \
    | nc -U "$state/control.sock" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["payload"])')"
echo "   code is ${#payload} characters"

echo "== forwarding both ports into the emulator"
"$adb" reverse "tcp:$port" "tcp:$port"
"$adb" reverse "tcp:$moved" "tcp:$moved"

echo "== installing the app and the test app, once"
(cd "$root" && ./gradlew installDebug installDebugAndroidTest)

echo
echo "== phase one: pair at 127.0.0.1:$port"
run_phase pairsAgainstTheBridgeAtTheAddressOnTheCode -e pairingCode "$payload"

echo
echo "== MOVING the bridge from $port to $moved, same state directory"
kill "$bridge_pid" 2>/dev/null || true
wait "$bridge_pid" 2>/dev/null || true
bridge_pid=""
# Proof that the old address is genuinely gone. Without this the second phase could be reaching the
# bridge it paired with and nobody would know.
if nc -z 127.0.0.1 "$port" 2>/dev/null; then
    echo "something is still answering on $port; this run would prove nothing" >&2
    exit 1
fi
start_bridge "$moved"
echo "   the bridge is now on 127.0.0.1:$moved and nothing answers on $port"

echo
echo "== phase two: change the address on the settings screen and reach the API"
run_phase changingTheAddressReachesTheMovedBridgeWithoutRePairing -e movedAddress "127.0.0.1:$moved"

echo
echo "== what the bridge logged"
cat "$work/bridge.log"
echo
echo "== what it pinned, and when — one phone, paired once"
python3 -c '
import json, sys
peers = json.load(open(sys.argv[1]))
for p in peers if isinstance(peers, list) else peers.get("peers", []):
    print("  ", p.get("fingerprint"), p.get("name"), p.get("paired_at"))
' "$state/peers.json"
