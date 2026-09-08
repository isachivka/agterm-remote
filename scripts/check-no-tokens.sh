#!/usr/bin/env bash
#
# Fails when anything that looks like a GitHub token is committed to this repository.
#
# This repository is public. A token that reaches it is a token published, and the only remaining
# fix afterwards is to revoke it and rewrite history. The moment to catch it is before the push.
#
# Usage: scripts/check-no-tokens.sh [path]        default: the whole working tree
set -euo pipefail

# The repository root, not the directory this was invoked from. `git ls-files` run inside a
# subdirectory lists only that subtree, and lists it relative to that subdirectory - so the
# self-exclusion below stops matching and a clean subdirectory produces OK on a partial scan. A
# guard that reports on a fraction of the tree while claiming to have checked it is worse than no
# guard.
if ! root="$(git -C "${1:-.}" rev-parse --show-toplevel 2>/dev/null)"; then
  echo "::error::check-no-tokens.sh found no git repository at '${1:-.}'."
  echo "    A guard that cannot list the tracked files must not report a clean tree."
  exit 2
fi

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
# The listing is captured and its status checked, rather than piped straight into the loop. A
# failing `git ls-files` produces an empty list, the loop never runs, and the script prints OK and
# exits 0 - a guard reporting a clean tree precisely because it could not look at one.
if ! files="$(git -C "$root" ls-files)"; then
  echo "::error::check-no-tokens.sh could not list the tracked files in '$root'."
  echo "    Refusing to report a clean tree on the strength of an empty listing."
  exit 2
fi

self="scripts/check-no-tokens.sh"
found=0
while IFS= read -r path; do
  [ "$path" = "$self" ] && continue
  # ls-files prints repository-root-relative paths, so they are resolved against $root rather than
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
done <<< "$files"

if [ "$found" -ne 0 ]; then
  echo "::error::A GitHub token must never be committed. Revoke it now - assume it is public -"
  echo "    then remove it from the working tree and from history before pushing again."
  exit 1
fi
echo "OK: no GitHub token pattern in any tracked file."
