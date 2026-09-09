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
# With --proxied it runs the OTHER deployment instead: a bridge serving TLS on its own port, behind a
# reverse proxy that terminates HTTPS at its edge and speaks HTTPS back - which is what a router that
# publishes a Mac by proxying it does, and is the shape both of this round's regressions hid in. The
# proxy is scripts/tls-proxy; its CA has to be trusted by the emulator, which the script explains if
# it is not.
#
#     phone -> TLS to the proxy -> proxy -> TLS to the bridge -> HTTP Upgrade -> pinned mTLS
#
# Usage: scripts/enrol-end-to-end.sh [--proxied] [port]

set -euo pipefail

proxied=false
if [ "${1:-}" = "--proxied" ]; then
    proxied=true
    shift
fi

port="${1:-8459}"
# The proxy's edge, which is what the phone dials in --proxied mode. The bridge keeps $port.
front=$((port + 1000))
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d)"
state="$work/state"
mkdir -p "$state"
chmod 700 "$state"

: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
adb="$ANDROID_HOME/platform-tools/adb"

bridge_pid=""
proxy_pid=""
cleanup() {
    [ -n "$bridge_pid" ] && kill "$bridge_pid" 2>/dev/null || true
    [ -n "$proxy_pid" ] && kill "$proxy_pid" 2>/dev/null || true
    "$adb" reverse --remove "tcp:$port" >/dev/null 2>&1 || true
    "$adb" reverse --remove "tcp:$front" >/dev/null 2>&1 || true
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

# The two deployments differ in exactly three arguments, which is the whole of what the scheme and
# the on-link hop are.
if $proxied; then
    dial="127.0.0.1:$front"
    extra=(-advertise-scheme tls -on-link-tls)
else
    dial="127.0.0.1:$port"
    extra=(-advertise-scheme plain)
fi

echo "== starting the bridge on 127.0.0.1:$port with a scratch state directory"
"$work/agterm-remote-bridge" \
    -listen "127.0.0.1:$port" \
    -advertise "$dial" \
    "${extra[@]}" \
    -state-dir "$state" \
    -log "$work/bridge.log" &
bridge_pid=$!

for _ in $(seq 1 50); do
    [ -S "$state/control.sock" ] && break
    sleep 0.2
done
[ -S "$state/control.sock" ] || { echo "the bridge never opened its control socket"; cat "$work/bridge.log"; exit 1; }

if $proxied; then
    echo "== starting the proxy: HTTPS on $front, HTTPS to $port"
    (cd "$root/scripts/tls-proxy" && go build -o "$work/tls-proxy" .)
    # The CA is kept between runs, beside the proxy, because the emulator has it INSTALLED. A fresh
    # one every start is a certificate the phone has never trusted, and it fails looking exactly like
    # the thing under test.
    ca="$root/scripts/tls-proxy/.ca.pem"
    "$work/tls-proxy" -listen "127.0.0.1:$front" -backend "127.0.0.1:$port" \
        -ca-out "$ca" -ca-key "$root/scripts/tls-proxy/.ca-key.pem" > "$work/proxy.log" 2>&1 &
    proxy_pid=$!
    sleep 1
    echo "   the emulator must trust $ca. Install it into the user store under both hash names:"
    echo "     h=\$(openssl x509 -subject_hash_old -in $ca -noout); cp $ca \$h.0"
    echo "     adb root && adb push \$h.0 /data/misc/user/0/cacerts-added/"
    echo "   (a debug build trusts the user store; see network_security_config.xml)"
fi

echo "== opening an enrolment window through the control socket"
scheme=plain
$proxied && scheme=tls
payload="$(printf '%s' '{"verb":"pair-open","ttl_seconds":300,"scheme":"'"$scheme"'","advertise":"'"$dial"'"}' \
    | nc -U "$state/control.sock" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["payload"])')"
echo "   code is ${#payload} characters"

echo "== forwarding the port the phone dials into the emulator"
if $proxied; then
    "$adb" reverse "tcp:$front" "tcp:$front"
else
    "$adb" reverse "tcp:$port" "tcp:$port"
fi

echo "== running the instrumented test"
(cd "$root" && ./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.isachivka.agtermremote.pairing.EnrollmentTest \
    -Pandroid.testInstrumentationRunnerArguments.pairingCode="$payload")

echo
echo "== what the bridge logged"
cat "$work/bridge.log"
if $proxied; then
    echo "== what the proxy logged"
    cat "$work/proxy.log"
fi
echo
echo "== what it pinned"
python3 -c '
import json, sys
peers = json.load(open(sys.argv[1]))
for p in peers if isinstance(peers, list) else peers.get("peers", []):
    print("  ", p.get("fingerprint"), p.get("name"), p.get("paired_at"))
' "$state/peers.json"
