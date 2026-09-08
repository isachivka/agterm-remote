#!/usr/bin/env bash
#
# The test for scripts/check-no-addresses.sh, plus the fail-open cases that apply to all four guards.
#
# It builds a throwaway git repository in a temp directory, stages one file at a time, and runs the
# guard against it. A real repository is the only honest fixture here: the guard scans `git ls-files`
# and nothing else, so a test that fed it a loose file would be testing something the guard never
# does.
#
# The three older guards carry their own self-tests inside them and are covered by running them; this
# one is the guard that needed a test written before it existed.
set -euo pipefail

# Resolved from this script's own location, not from $OLDPWD. The first draft used "$OLDPWD/scripts/
# check-no-addresses.sh" after cd-ing into the temp directory, which quietly requires the test to be
# launched from the repository root - run it by absolute path from anywhere else and OLDPWD points at
# the caller's directory, the guard is not found, every case reports "exit 1", and the four cases
# that expect a rejection all pass. Three quarters green for entirely the wrong reason.
here="$(cd "$(dirname "$0")" && pwd)"
guard="$here/../check-no-addresses.sh"
[ -x "$guard" ] || { echo "FAIL: $guard is missing or not executable"; exit 1; }

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
cd "$tmp" && git init -q .

fail=0

run() { # runs the guard, echoes its numeric exit status
  local status=0
  "$@" >/dev/null 2>&1 || status=$?
  printf '%s\n' "$status"
}

check() { # name, content, expected exit
  printf '%s' "$2" > candidate.txt && git add -A
  got="$(run "$guard")"
  # The guard exits 2 when its own self-test fails. Folding that into "1" would report a guard that
  # can no longer detect anything as a guard that detected something.
  if [ "$got" -ge 2 ]; then
    echo "FAIL $1: guard aborted with exit $got (its self-test failed)"; fail=1; return
  fi
  [ "$got" = "$3" ] || { echo "FAIL $1: expected $3 got $got"; fail=1; }
}

check "bare ipv4"       "connect to 10.11.12.13 now"       1
check "ipv4 with port"  "host=10.11.12.14:8443"            1
check "home path"       "/Users/somebody/.config/x"        1
check "ddns name"       "laptop.mynetname.net"             1
check "clean text"      "the owner supplies host and port" 0
check "placeholder"     "example: HOST:PORT"               0
# Documentation ranges (RFC 5737) are ALLOWED: the README and the onboarding copy need an
# example address, and refusing them would push writers towards a real one.
check "documentation ip" "for example 203.0.113.5:8443"    0
check "loopback"         "listens on 127.0.0.1"            0
check "bare documentation ip" "203.0.113.5"                0
check "bare loopback"         "127.0.0.1"                  0

# An allowed address on the same line as a real one must not launder it. This is the shape onboarding
# prose actually takes, and a whole-line allow filter waves it through.
check "allowed masking real" "listens on 127.0.0.1, point the phone at 10.11.12.15" 1

# A real address that CONTAINS an allowed one. Every one of these reported clean while the allow list
# was erased from the line as an unanchored substring: erase `1.2.3.4` from `81.2.3.4` and the
# remainder is not an address any more. The allow list must match a WHOLE extracted address.
check "allow-substring 81.2.3.4"  "bridge at 81.2.3.4" 1
check "allow-substring 10.0.0.0"  "network 10.0.0.0"   1
check "allow-substring 210.0.0.0" "host 210.0.0.0"     1
check "allow-substring 127.0.0.10" "host 127.0.0.10"   1
check "allow-substring 1.2.3.45"  "host 1.2.3.45"      1

# Capitalised macOS home directories and uppercase hostnames are ordinary.
check "capitalised home path" "/Users/Igor/.ssh/id"  1
check "uppercase ddns name"   "MYBOX.MYNETNAME.NET"  1

# --- The fail-open cases -----------------------------------------------------------------------
#
# Both of these produced a clean tree and exit 0 before they were fixed, which is the single failure
# mode these scripts exist to prevent.

# Outside a repository, `git ls-files` fails and yields nothing. An empty listing must never be read
# as an empty tree.
outside="$(mktemp -d)"
printf 'bridge host: 10.11.12.16\n' > "$outside/candidate.txt"
got="$(cd "$outside" && run "$guard")"
[ "$got" = "2" ] || { echo "FAIL outside a repository: expected 2 got $got"; fail=1; }
rm -rf "$outside"

# From a subdirectory, `git ls-files` lists only that subtree. A clean subdirectory must not clear a
# tree whose offending file is somewhere else in it.
printf 'bridge host: 10.11.12.17\n' > candidate.txt
mkdir -p sub && printf 'nothing to see here\n' > sub/clean.txt
git add -A
got="$(cd sub && run "$guard")"
[ "$got" = "1" ] || { echo "FAIL from a subdirectory: expected 1 got $got"; fail=1; }
# ...and the same subdirectory over a clean tree still passes.
printf 'the owner supplies host and port\n' > candidate.txt && git add -A
got="$(cd sub && run "$guard")"
[ "$got" = "0" ] || { echo "FAIL from a subdirectory, clean tree: expected 0 got $got"; fail=1; }

exit "$fail"
