#!/usr/bin/env bash
#
# The test for scripts/lib/instrumentation-verdict.sh — **the guard behind the one end-to-end claim
# this project makes about the settings screen.**
#
# Every fixture below is a REAL transcript, captured from `am instrument` on an API 37 emulator and
# trimmed only of repeated stack lines. An invented transcript would test the invention: the whole
# defect this replaces came from believing `INSTRUMENTATION_CODE: -1` meant something it does not,
# and no hand-written fixture would have carried it, because the person writing it would have
# written what they believed.
#
# The first case is the one that matters. That transcript — a genuinely failed assertion — was
# ACCEPTED AS A PASS by the guard this replaces, which is what made `address-change-end-to-end.sh` a
# harness that could not fail.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
verdict="$here/../lib/instrumentation-verdict.sh"
[ -x "$verdict" ] || { echo "FAIL: $verdict is missing or not executable"; exit 1; }

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail=0

check() { # name, expected exit, transcript on stdin
  local name="$1" expected="$2" status=0
  cat > "$tmp/transcript.txt"
  "$verdict" "$tmp/transcript.txt" "${3:-1}" "$name" >/dev/null 2>&1 || status=$?
  [ "$status" = "$expected" ] || { echo "FAIL $name: expected exit $expected, got $status"; fail=1; }
}

# **A failed test.** `am instrument` exited 0 and printed `INSTRUMENTATION_CODE: -1` all the same.
check "a failed assertion is not a pass" 1 <<'TRANSCRIPT'
INSTRUMENTATION_STATUS: class=dev.isachivka.agtermremote.pairing.PhoneIdentityTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stream=
INSTRUMENTATION_STATUS: test=theKeyIsInsideSecureHardware
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: the key must live in secure hardware
	at org.junit.Assert.fail(Assert.java:89)
INSTRUMENTATION_STATUS: test=theKeyIsInsideSecureHardware
INSTRUMENTATION_STATUS_CODE: -2
INSTRUMENTATION_RESULT: stream=

Time: 0.122
There was 1 failure:
1) theKeyIsInsideSecureHardware(dev.isachivka.agtermremote.pairing.PhoneIdentityTest)
java.lang.AssertionError: the key must live in secure hardware

FAILURES!!!
Tests run: 1,  Failures: 1


INSTRUMENTATION_CODE: -1
TRANSCRIPT

# **A real pass**, and the guard has to accept it or the harness is useless in the other direction.
check "a green run is a pass" 0 <<'TRANSCRIPT'
INSTRUMENTATION_STATUS: class=dev.isachivka.agtermremote.settings.SettingsTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stream=
INSTRUMENTATION_STATUS: test=theCameraSettingsRouteResolvesToARealScreen
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=dev.isachivka.agtermremote.settings.SettingsTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stream=.
INSTRUMENTATION_STATUS: test=theCameraSettingsRouteResolvesToARealScreen
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_RESULT: stream=

Time: 2.197

OK (1 test)


INSTRUMENTATION_CODE: -1
TRANSCRIPT

# **A run that matched no test.** A renamed or misspelled method produces exactly this, and it is
# green: `OK (0 tests)`. The half of the old guard that worked.
check "a run that found nothing is not a pass" 1 <<'TRANSCRIPT'
INSTRUMENTATION_RESULT: stream=

Time: 0

OK (0 tests)


INSTRUMENTATION_CODE: -1
TRANSCRIPT

# A skip is not a pass either: a test that assumed itself out of existence proved nothing.
check "an assumption failure is not a pass" 1 <<'TRANSCRIPT'
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stream=
INSTRUMENTATION_STATUS: test=changingTheAddressReachesTheMovedBridgeWithoutRePairing
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: stack=org.junit.AssumptionViolatedException: no moved address was supplied
INSTRUMENTATION_STATUS_CODE: -4
INSTRUMENTATION_RESULT: stream=

Time: 0.03

OK (1 test)


INSTRUMENTATION_CODE: -1
TRANSCRIPT

# A run the process did not survive. `OK` never appears, but neither does anything the old guard
# would have called a failure.
check "a crashed process is not a pass" 1 <<'TRANSCRIPT'
INSTRUMENTATION_RESULT: shortMsg=Process crashed.
INSTRUMENTATION_CODE: 0
TRANSCRIPT

# The transcript the runner never wrote, because adb never reached the device. An empty file is the
# cheapest way to make a guard built only out of "no failure appears" report success.
check "an empty transcript is not a pass" 1 < /dev/null

# A count that disagrees with what the phase asked for. Phase two runs one test; a transcript for
# two of them is not that phase's evidence.
check "the wrong number of tests is not a pass" 1 <<'TRANSCRIPT'
INSTRUMENTATION_STATUS: numtests=2
INSTRUMENTATION_RESULT: stream=

Time: 1.2

OK (2 tests)


INSTRUMENTATION_CODE: -1
TRANSCRIPT

# And the same transcript IS the evidence when two were asked for, so the count is read rather than
# hardcoded.
check "two tests pass when two were asked for" 0 2 <<'TRANSCRIPT'
INSTRUMENTATION_STATUS: numtests=2
INSTRUMENTATION_RESULT: stream=

Time: 1.2

OK (2 tests)


INSTRUMENTATION_CODE: -1
TRANSCRIPT

[ "$fail" = 0 ] && echo "instrumentation-verdict: all cases pass"
exit "$fail"
