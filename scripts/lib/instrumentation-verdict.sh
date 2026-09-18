#!/usr/bin/env bash
#
# **Did an `am instrument` run actually pass?** — read out of the transcript, on positive evidence.
#
# `am instrument` exits 0 whatever happened: on a green run, on a failed assertion, on a run that
# matched no test at all, and on a process that crashed before the first one. So the exit status
# carries no information and the transcript is the only witness. Anything that treats an
# instrumentation run as proof has to read it, and has to read it correctly.
#
# ## The defect this file exists to make impossible
#
# The end-to-end script used to accept a run like this:
#
#     grep -q "INSTRUMENTATION_STATUS: stream=.*OK\|^OK (" "$out" ||
#         grep -q "INSTRUMENTATION_CODE: -1" "$out" ||
#         { echo "did not report success"; exit 1; }
#
# `INSTRUMENTATION_CODE: -1` is `Activity.RESULT_OK`, and `am instrument` prints it at the end of
# **every completed run**, passing or failing. So the second arm of the `||` was true for every
# transcript that existed, the first arm never decided anything, and a phase in which the phone
# failed to reach the moved bridge would have been reported as proof that it reached it. The harness
# behind the project's one end-to-end claim could not fail. `instrumentation_verdict_test.sh` replays
# that exact failing transcript through this file and requires a refusal.
#
# ## What it checks, and why each one is here
#
# Everything is positive evidence plus an explicit list of the ways a run says it went wrong. A
# guard built only out of "no failure appears" passes on an empty file.
#
#   * `OK (n tests)` — JUnit's own verdict line, with the count this run was told to expect. It is
#     the only line in the transcript that means *every test that ran passed*.
#   * `numtests=n` — how many the runner actually found. `OK (0 tests)` is what a renamed or
#     misspelled method produces, and green-on-nothing is the failure shape this repository has been
#     bitten by six times.
#   * `INSTRUMENTATION_CODE: -1` — the run reached its end. Worthless as a pass (see above) and
#     genuinely useful as a completion check, so it is kept as a conjunct rather than a disjunct.
#   * The refusals: `FAILURES!!!`, a per-test status code of -1 (error), -2 (failure) or -4
#     (assumption failure), an aborted run, and a crashed process. A skip is not a pass: a test that
#     assumed itself out of existence proved nothing, and it is the shape an emulator produces when
#     a precondition it cannot meet is quietly tolerated.
#
# Usage: instrumentation-verdict.sh <transcript> [expected test count, default 1] [label]
#        exits 0 when the run passed, 1 with a sentence on stderr when it did not.

set -euo pipefail

transcript="${1:?usage: instrumentation-verdict.sh <transcript> [tests] [label]}"
expected="${2:-1}"
label="${3:-$(basename "$transcript")}"

refuse() {
    echo "== $label: $1" >&2
    exit 1
}

[ -f "$transcript" ] || refuse "there is no transcript at $transcript, so nothing was proved"
[ -s "$transcript" ] || refuse "the transcript is empty, so nothing was proved"

# Carriage returns stripped before anything is matched. Some adb builds line-end the shell's output
# with CRLF and some do not, and an anchored pattern that silently stops matching on one of them
# would turn every check below into a refusal on one machine - or, if the anchors were dropped to
# cope, into a check that matches the middle of a stack trace.
normalised="$(mktemp)"
trap 'rm -f "$normalised"' EXIT
tr -d '\r' < "$transcript" > "$normalised"

# The ways a run says it went wrong, each with the sentence a reader needs.
if grep -q '^FAILURES!!!' "$normalised"; then
    refuse "a test FAILED - see $transcript"
fi
if grep -qE '^INSTRUMENTATION_STATUS_CODE: -(1|2)$' "$normalised"; then
    refuse "a test errored or failed (status code -1/-2) - see $transcript"
fi
if grep -qE '^INSTRUMENTATION_STATUS_CODE: -4$' "$normalised" ||
    grep -q 'assumption_failure\|AssumptionViolated' "$normalised"; then
    refuse "a test SKIPPED ITSELF, which is not a pass - see $transcript"
fi
if grep -q 'INSTRUMENTATION_ABORTED\|shortMsg=Process crashed\|Process crashed' "$normalised"; then
    refuse "the process crashed before the run finished - see $transcript"
fi

# And the positive evidence, which is what actually says it passed.
#
# The plural is part of the line JUnit prints - "OK (1 test)", "OK (2 tests)" - so both spellings are
# accepted for the count that was asked for, and no other count is.
if ! grep -qE "^OK \($expected test(s)?\)$" "$normalised"; then
    refuse "no 'OK ($expected test...)' line, so it did not report success - see $transcript"
fi
if ! grep -q "numtests=$expected" "$normalised"; then
    refuse "the runner did not report $expected test(s) found - see $transcript"
fi
if ! grep -q '^INSTRUMENTATION_CODE: -1$' "$normalised"; then
    refuse "the run never reached its end - see $transcript"
fi
