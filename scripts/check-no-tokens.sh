#!/usr/bin/env bash
#
# Fails when anything that looks like a GitHub token is committed to this repository.
#
# This repository is public. A token that reaches it is a token published, and the only remaining
# fix afterwards is to revoke it and rewrite history. The moment to catch it is before the push.
#
# Usage: scripts/check-no-tokens.sh [path]        default: the whole working tree
set -euo pipefail

root="${1:-.}"

# GitHub's documented prefixes. Assembled at runtime rather than written out, so that this file
# does not itself contain the literals it searches for - otherwise the check would find itself and
# every run would be red.
prefixes=(ghp gho ghu ghs ghr github_pat)
pattern="$(printf '%s_[A-Za-z0-9_]{20,}|' "${prefixes[@]}")"
pattern="${pattern%|}"

# The single grep invocation. Defined once and called from BOTH the self-test below and the scan
# after it, so the probe cannot succeed through a path the real scan does not use.
#
# -e is not style. Two of these patterns begin with a character grep would otherwise read as the
# start of a bundle of options; without -e it exits 2, and with stderr discarded and the status
# consumed by an `if`, every file reports clean.
scan() { grep -InE -e "$pattern" "$1" 2>/dev/null; }

# --- The self-test ---------------------------------------------------------------------------------
#
# This check proves it can still detect before it is allowed to report OK. A failure to match a
# known-positive is a hard error, not a clean run.
#
# The reason is history, not caution. In the project these guards were written for, four separate
# checks shipped broken and the only symptom of each was a green tick: two siblings resolving paths
# against the wrong directory; a test that passed with the very banner it existed to catch; a
# `git grep` silently matching nothing on an unsupported escape; and a private-key check whose
# pattern grep parsed as command-line options. Every one produced the output that means "fine",
# which is the only output nobody investigates.
#
# The probe goes through `scan`, the SAME function the real loop calls. That is the point rather
# than tidiness: a probe running its own grep would test a copy of the pattern through a different
# invocation, and would pass happily while the real invocation was broken.
#
# Every alternative in the pattern is exercised, not just one, because a pattern with a single
# broken branch still matches a single-sample probe.
probe="$(mktemp)"
trap 'rm -f "$probe"' EXIT
for prefix in "${prefixes[@]}"; do
  sample="${prefix}_AAAAAAAAAAAAAAAAAAAAAAAA"
  : > "$probe"
  printf '%s\n' "$sample" > "$probe"
  if ! scan "$probe" >/dev/null; then
    echo "::error::check-no-tokens.sh is broken."
    echo "    It cannot match a known-positive, so a clean run would mean nothing."
    echo "    Fix the pattern before trusting any result from this script."
    exit 2
  fi
done
rm -f "$probe"
trap - EXIT

# Tracked files only: anything gitignored is not what this protects, and a token has to be tracked
# to be pushed. This script excludes itself.
self="scripts/check-no-tokens.sh"
found=0
while IFS= read -r path; do
  [ "$path" = "$self" ] && continue
  # ls-files prints repository-relative paths, so they are resolved against $root rather than
  # against wherever this was invoked from. Without this the [path] argument made every file vanish
  # and the check pass silently - green for the wrong reason, and only when pointed at a tree other
  # than the current directory, which is precisely what testing it looks like.
  file="$root/$path"
  [ -f "$file" ] || continue

  if matches="$(scan "$file")"; then
    echo "::error file=$path::looks like a GitHub token was committed here"
    # The matching line number, never the match itself - printing the secret into a public CI log
    # would finish the job the commit started.
    echo "$matches" | while IFS= read -r line; do
      echo "    line ${line%%:*}"
    done
    found=1
  fi
done <<< "$(git -C "$root" ls-files)"

if [ "$found" -ne 0 ]; then
  echo "::error::A GitHub token must never be committed. Revoke it now - assume it is public -"
  echo "    then remove it from the working tree and from history before pushing again."
  exit 1
fi
echo "OK: no GitHub token pattern in any tracked file."
