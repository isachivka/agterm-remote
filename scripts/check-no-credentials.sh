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

root="${1:-.}"

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
scan() { grep -InEi -e "$pattern" "$1" 2>/dev/null; }

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

# Tracked files only. Anything gitignored is not what this protects, and a credential has to be
# tracked to be pushed.
self="scripts/check-no-credentials.sh"
found=0
while IFS= read -r path; do
  [ "$path" = "$self" ] && continue
  # ls-files prints repository-relative paths, so they are resolved against $root and not against
  # wherever this was invoked from. Reading them relative to the caller's directory makes every file
  # vanish and the whole check pass - silently, and only when it is pointed somewhere other than the
  # current directory, which is exactly what anyone testing it would do.
  file="$root/$path"
  [ -f "$file" ] || continue

  if matches="$(scan "$file")"; then
    echo "::error file=$path::looks like a credential was committed here"
    # Line numbers only, never the matching text. Printing the credential into a CI log would
    # finish the job the commit started.
    echo "$matches" | while IFS= read -r line; do
      echo "    line ${line%%:*}"
    done
    found=1
  fi
done <<< "$(git -C "$root" ls-files)"

if [ "$found" -ne 0 ]; then
  echo "::error::A service credential must never be committed. Change it on the service now -"
  echo "    assume it is published - then remove it from the working tree and from history."
  echo "    This app authenticates to nothing and never needs one."
  exit 1
fi
echo "OK: no credential-shaped field in any tracked file."
