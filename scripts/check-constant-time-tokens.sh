#!/usr/bin/env bash
#
# Fails when the enrolment token is compared with anything other than crypto/subtle.
#
# WHAT IT PROTECTS
#
# bridge/internal/enroll compares a 32-byte one-time secret against bytes an unauthenticated caller
# supplied, and that caller can retry as fast as the network allows. bytes.Equal, ==, DeepEqual and
# strings.Compare all return on the first differing byte, so the time they take is a function of how
# many leading bytes were right - and a caller who can retry turns that into the token, one byte at a
# time. subtle.ConstantTimeCompare is the only comparison that does not have that shape.
#
# WHY A TEST CANNOT DO THIS
#
# The property is about timing, and timing is exactly what a unit test cannot assert honestly here.
# A measurement over a 32-byte compare is nanoseconds against a scheduler, a garbage collector and a
# shared CI runner; a test written around it is either so loose it passes on bytes.Equal or so tight
# it fails at random and gets deleted within a month. This was measured rather than assumed:
# swapping subtle.ConstantTimeCompare for bytes.Equal on 2026-09-09 left the whole enroll package
# green, -race included. So the rule lives in the shape of the code, and this is what reads it.
#
# WHAT IT ACTUALLY COVERS, which is deliberately less than "all of Go"
#
#   - Non-test .go files under bridge/internal/enroll only. Tests legitimately compare two minted
#     tokens with == to assert they differ; that is not a secret comparison and must stay allowed.
#   - bytes.Equal, reflect.DeepEqual, strings.Compare and strings.EqualFold, on a line that mentions
#     a token.
#   - == and != with a token as an immediate operand, on either side.
#   - Full-line comments are skipped, so the prose explaining this very rule does not trip it. The
#     cost is that a trailing comment on a code line is still read as code: keep the reasoning on its
#     own line.
#
# It is a floor. A comparison spelled across two lines, hidden behind a helper, or written on a copy
# of the token under another name walks through it. What it stops is the edit that actually happens -
# somebody simplifying an unfamiliar subtle call into the obvious one.
#
# The fifth sibling of check-no-tokens.sh, check-no-credentials.sh, check-no-private-keys.sh and
# check-no-addresses.sh. Separate for the same reason they are separate from each other: the remedy
# differs. Those four say "this must never have been committed"; this one says "put the comparison
# back", and nothing has leaked.
#
# Usage: scripts/check-constant-time-tokens.sh [path]     default: the whole working tree
set -euo pipefail

# The repository root, not the directory this was invoked from. `git ls-files` run inside a
# subdirectory lists only that subtree, and a partial scan that reports OK is worse than no guard.
if ! root="$(git -C "${1:-.}" rev-parse --show-toplevel 2>/dev/null)"; then
  echo "::error::check-constant-time-tokens.sh found no git repository at '${1:-.}'."
  echo "    A guard that cannot list the tracked files must not report a clean tree."
  exit 2
fi

# A token-ish identifier: an optional qualifier (w., h., etc.), the word token, and whatever suffix
# a name carries (tokenBytes, candidateToken).
tok='[A-Za-z0-9_.]*[Tt]oken[A-Za-z0-9_]*'
# The two shapes. The call form is caught by the function name plus the token filter below; the
# operator form demands the token be an IMMEDIATE operand, which is what keeps len(token) == 32 and
# ConstantTimeCompare(...) != 1 - both correct code - out of the results.
calls='(bytes\.Equal|reflect\.DeepEqual|strings\.(Compare|EqualFold))\('
ops="${tok}(\[[^]]*\])?[[:space:]]*[!=]=|[!=]=[[:space:]]*${tok}"
pattern="(${calls})|(${ops})"

# The single scan. Defined once and called from BOTH the self-test below and the real scan, so the
# probe cannot succeed through a path the scan does not use.
#
#   1. find the shapes,   2. drop full-line comments,   3. keep only lines about a token.
scan() {
  grep -InE -e "$pattern" "$1" 2>/dev/null \
    | grep -vE '^[0-9]+:[[:space:]]*//' \
    | grep -iE 'token'
}

# --- The self-test ---------------------------------------------------------------------------------
#
# This check proves it can still detect, AND that it still tolerates the correct code, before it is
# allowed to report OK. The second half matters as much as the first: a guard that fires on
# subtle.ConstantTimeCompare gets switched off, and then the first half protects nothing.
probe="$(mktemp)"
trap 'rm -f "$probe"' EXIT

must_match=(
  '	if bytes.Equal(w.token[:], token) {'
  '	if reflect.DeepEqual(w.token, token) {'
  '	if strings.Compare(string(w.token[:]), string(token)) == 0 {'
  '	if strings.EqualFold(hex.EncodeToString(w.token[:]), token) {'
  '	if w.token == candidate {'
  '	if candidate == w.token {'
  '	if w.token[0] != token[0] {'
)
must_not_match=(
  '	if subtle.ConstantTimeCompare(w.token[:], token) != 1 {'
  '	// never == or bytes.Equal on a token'
  '	if len(token) == 32 {'
  '	w.token = token'
  '	if w.attempts >= maxAttempts {'
)

for line in "${must_match[@]}"; do
  printf '%s\n' "$line" > "$probe"
  if ! scan "$probe" >/dev/null; then
    echo "::error::check-constant-time-tokens.sh is broken."
    echo "    It cannot match a known-positive, so a clean run would mean nothing:"
    echo "        $line"
    exit 2
  fi
done
for line in "${must_not_match[@]}"; do
  printf '%s\n' "$line" > "$probe"
  if scan "$probe" >/dev/null; then
    echo "::error::check-constant-time-tokens.sh is too broad."
    echo "    It rejects correct code, which is how a guard gets switched off:"
    echo "        $line"
    exit 2
  fi
done
rm -f "$probe"
trap - EXIT

# --- The scan --------------------------------------------------------------------------------------
#
# The listing is captured and its status checked rather than piped straight into the loop. A failing
# `git ls-files` yields an empty list, the loop never runs, and the script prints OK - a guard
# reporting a clean tree precisely because it could not look at one.
if ! files="$(git -C "$root" ls-files 'bridge/internal/enroll/*.go')"; then
  echo "::error::check-constant-time-tokens.sh could not list bridge/internal/enroll in '$root'."
  echo "    Refusing to report a clean tree on the strength of an empty listing."
  exit 2
fi
if [ -z "$files" ]; then
  # The package is what this guard exists for. If it is gone or moved, this script is being asked a
  # question about nothing, and must say so rather than pass.
  echo "::error::check-constant-time-tokens.sh found no Go files under bridge/internal/enroll."
  echo "    If the package moved, move this guard with it; a guard over an empty set is not a pass."
  exit 2
fi

found=0
while IFS= read -r path; do
  case "$path" in
    *_test.go) continue ;;
  esac
  file="$root/$path"
  [ -f "$file" ] || continue

  if matches="$(scan "$file")"; then
    echo "::error file=$path::the enrolment token is compared without crypto/subtle here"
    echo "$matches" | while IFS= read -r line; do
      echo "    line ${line%%:*}"
    done
    found=1
  fi
done <<< "$files"

if [ "$found" -ne 0 ]; then
  echo "::error::Compare the enrolment token with subtle.ConstantTimeCompare and nothing else."
  echo "    ==, bytes.Equal, reflect.DeepEqual and strings.Compare return on the first differing"
  echo "    byte. The caller on the other end of this comparison is unauthenticated and can retry."
  exit 1
fi
echo "OK: the enrolment token is compared with crypto/subtle only."
