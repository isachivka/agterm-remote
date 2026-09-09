#!/usr/bin/env bash
#
# Fails when a credential-shaped field is committed to this repository.
#
# The sibling of scripts/check-no-tokens.sh, and deliberately not part of it. That one guards a
# GitHub token and its remedy is "revoke it now"; this one guards service logins and passwords and
# the remedy is "change it on the service". One script printing both messages would print the wrong
# one half the time.
#
# This app authenticates to nothing and never needs a credential, so every hit here is a mistake -
# a line pasted from a config file, a fixture written with a real password, a note left in a doc.
#
# Usage: scripts/check-no-credentials.sh [path]      default: the whole working tree
set -euo pipefail

# The repository root, not the directory this was invoked from. `git ls-files` run inside a
# subdirectory lists only that subtree, and lists it relative to that subdirectory - so the
# self-exclusion below stops matching and a clean subdirectory produces OK on a partial scan. A
# guard that reports on a fraction of the tree while claiming to have checked it is worse than no
# guard.
if ! root="$(git -C "${1:-.}" rev-parse --show-toplevel 2>/dev/null)"; then
  echo "::error::check-no-credentials.sh found no git repository at '${1:-.}'."
  echo "    A guard that cannot list the tracked files must not report a clean tree."
  exit 2
fi

# The field names, assembled at runtime rather than written into a pattern string, so that this file
# does not contain the shape it searches for and cannot find itself. (It is also excluded below -
# the same belt and braces check-no-tokens.sh wears.)
fields=(password passwd pwd login username)
joined="$(printf '%s|' "${fields[@]}")"
joined="${joined%|}"

# What counts as a hit, and why each piece is there:
#
#   ["']?              the name may be quoted, as it is in JSON.
#   [[:space:]]*[:=]   assigned, either style.
#   ["'][^"']+["']     to a NON-EMPTY QUOTED literal. This is the load-bearing part, and it is doing
#                      two jobs. It separates an assignment from a type annotation, which is why the
#                      pattern is clean against a Kotlin codebase full of the word. And it is what
#                      lets release-signing plumbing through: those lines assign from an environment
#                      variable rather than a literal, so they do not match - while a keystore
#                      password hardcoded as a literal in the same file does, which is correct and is
#                      the point.
#
# There is deliberately no word boundary before the name. One was tried first, to stop the pattern
# firing on longer identifiers, then both were measured against a tracked tree: they have the same
# number of false positives, which is none, and the boundary loses one true positive - a hardcoded
# storePassword. It was guarding against something the quoted-value requirement already handles.
#
# Known and accepted gap: an unquoted scalar - the plain YAML or dotenv style - is not matched,
# because the only thing distinguishing it from a Kotlin type annotation is knowledge of the type
# names. Widening to catch it would fire on ordinary source and the check would stop being trusted.
pattern="(${joined})[\"']?[[:space:]]*[:=][[:space:]]*[\"'][^\"']+[\"']"

# The single grep invocation. Defined once and called from BOTH the self-test below and the scan
# after it, so the probe cannot succeed through a path the real scan does not use.
#
# -e is not style. A pattern beginning with a character grep reads as the start of a bundle of
# options exits 2, and with stderr discarded and the status consumed by an `if`, every file reports
# clean.
scan() { grep -anEi -e "$pattern" "$1" 2>/dev/null; }

# --- The self-test ---------------------------------------------------------------------------------
#
# This check proves it can still detect before it is allowed to report OK. A failure to match a
# known-positive is a hard error, not a clean run. See the longer note in check-no-tokens.sh for the
# four green-tick failures that put this here.
#
# The probe goes through `scan`, the SAME function the real loop calls, and exercises every
# alternative in the pattern, because a pattern with a single broken branch still matches a
# single-sample probe.
probe="$(mktemp)"
trap 'rm -f "$probe"' EXIT
for field in "${fields[@]}"; do
  sample="${field}: \"a-value\""
  : > "$probe"
  printf '%s\n' "$sample" > "$probe"
  if ! scan "$probe" >/dev/null; then
    echo "::error::check-no-credentials.sh is broken."
    echo "    It cannot match a known-positive, so a clean run would mean nothing."
    echo "    Fix the pattern before trusting any result from this script."
    exit 2
  fi
done
rm -f "$probe"
trap - EXIT

# The walk - which files are read, which are declined and which are refused - lives in
# scripts/lib/tracked-files.sh, shared by every guard here. It is not a tidiness: this script used a
# bare `git ls-files` and skipped in silence any path holding a byte above ASCII, so a password
# inside a file named in Russian reported clean while the same bytes under an ASCII name were caught.
# That defect was found and fixed in one guard while four others carried it verbatim, which is why
# the mechanism is now in one place instead of four.
. "$(cd "$(dirname "$0")" && pwd)/lib/tracked-files.sh"

# This script excludes itself: it is made of the field names it searches for.
guard_self="scripts/check-no-credentials.sh"

examine() { # $1 = path, $2 = the file to read, $3 = what it is
  local matches
  matches="$(scan "$2")" || return 1
  echo "::error file=$1::$3 holds what looks like a credential"
  # Line numbers only, never the matching text. Printing the credential into a CI log would finish
  # the job the commit started.
  echo "$matches" | while IFS= read -r line; do
    echo "    line ${line%%:*}"
  done
  return 0
}

guard_walk "$root" "check-no-credentials.sh" examine

if [ "$guard_found" -ne 0 ]; then
  echo "::error::A service credential must never be committed. Change it on the service now -"
  echo "    assume it is published - then remove it from the working tree and from history."
  echo "    This app authenticates to nothing and never needs one."
fi
guard_finish "no credential-shaped field in any tracked file."
