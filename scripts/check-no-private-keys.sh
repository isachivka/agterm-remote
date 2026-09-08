#!/usr/bin/env bash
#
# Fails when a private key is committed to this repository.
#
# The third sibling of scripts/check-no-tokens.sh and scripts/check-no-credentials.sh, and separate
# from both for the same reason they are separate from each other: the remedy differs. A token is
# revoked, a service password is changed, and a private key is DESTROYED and the peer that pinned it
# re-pinned. One script printing all three messages would print the wrong one most of the time.
#
# Neither sibling catches a key, and that was measured rather than assumed: a real P-256 key was
# staged into a tree on 2026-07-28 and both printed OK. A key is not credential-SHAPED - it carries
# no field name, no assignment, and nothing either pattern looks for - so nothing stood between
# `git add -A` and a published key until this file.
#
# The bridge's key lives outside the working tree by default, precisely so it has no path in here to
# be added from. This is the guard for the case where someone points a directory option inward
# anyway.
#
# Usage: scripts/check-no-private-keys.sh [path]      default: the whole working tree
set -euo pipefail

# The repository root, not the directory this was invoked from. `git ls-files` run inside a
# subdirectory lists only that subtree, and lists it relative to that subdirectory - so the
# self-exclusion below stops matching and a clean subdirectory produces OK on a partial scan. A
# guard that reports on a fraction of the tree while claiming to have checked it is worse than no
# guard.
if ! root="$(git -C "${1:-.}" rev-parse --show-toplevel 2>/dev/null)"; then
  echo "::error::check-no-private-keys.sh found no git repository at '${1:-.}'."
  echo "    A guard that cannot list the tracked files must not report a clean tree."
  exit 2
fi

# Assembled at runtime rather than written out, so this file does not contain the shape it searches
# for and cannot find itself. (It is also excluded below - the same belt and braces the siblings
# wear.)
dashes="-----"
label="PRIVATE KEY"

# Matches the PEM header for every private-key encoding in practical use: PKCS#8 ("PRIVATE KEY"),
# PKCS#1 ("RSA PRIVATE KEY"), SEC1 ("EC PRIVATE KEY"), encrypted PKCS#8, and OpenSSH's own format.
# The optional word before the label is what covers the algorithm-specific variants.
#
# Deliberately matches the ARMOUR rather than the key material. Base64 is base64, and a pattern that
# tried to recognise the payload would either miss a DER file or fire on every certificate, test
# fixture and image in the tree. The header is what every tool writes and what nobody types by
# accident.
pattern="${dashes}BEGIN( [A-Z0-9]+)*( ENCRYPTED)? ?${label}${dashes}"

# The single grep invocation. Defined once and called from BOTH the self-test below and the scan
# after it, so the probe cannot succeed through a path the real scan does not use.
#
# -e is not style. This pattern begins with a dash, so as a bare argument grep reads it as a bundle
# of options, exits 2, and - with stderr discarded and the status consumed by an `if` - reports
# every file clean. The first version of this script did exactly that and passed on a tree
# containing a real private key.
scan() { grep -InE -e "$pattern" "$1" 2>/dev/null; }

# --- The self-test ---------------------------------------------------------------------------------
#
# This check proves it can still detect before it is allowed to report OK. A failure to match a
# known-positive is a hard error, not a clean run.
#
# The first version of this self-test had the defect it was written to prevent. It probed with
# `grep -qE` while the scan used `grep -InE` - a DIFFERENT invocation - so it was testing a copy of
# the pattern rather than the code path that does the work, and would have passed while the real one
# was broken. It now goes through `scan`, the same function the loop calls.
#
# Every encoding is exercised, not just one, because a pattern with a single broken branch still
# matches a single-sample probe.
probe="$(mktemp)"
trap 'rm -f "$probe"' EXIT
for kind in "" "RSA " "EC " "OPENSSH " "ENCRYPTED "; do
  sample="${dashes}BEGIN ${kind}${label}${dashes}"
  printf '%s\n' "$sample" > "$probe"
  if ! scan "$probe" >/dev/null; then
    echo "::error::check-no-private-keys.sh is broken."
    echo "    It cannot match a known-positive (${kind}${label}), so a clean run would mean nothing."
    echo "    Fix the pattern before trusting any result from this script."
    exit 2
  fi
done
rm -f "$probe"
trap - EXIT

# Tracked files only. Anything gitignored is not what this protects, and a key has to be tracked to
# be pushed.
# The listing is captured and its status checked, rather than piped straight into the loop. A
# failing `git ls-files` produces an empty list, the loop never runs, and the script prints OK and
# exits 0 - a guard reporting a clean tree precisely because it could not look at one.
if ! files="$(git -C "$root" ls-files)"; then
  echo "::error::check-no-private-keys.sh could not list the tracked files in '$root'."
  echo "    Refusing to report a clean tree on the strength of an empty listing."
  exit 2
fi

self="scripts/check-no-private-keys.sh"
found=0
while IFS= read -r path; do
  [ "$path" = "$self" ] && continue
  # ls-files prints repository-root-relative paths, so they are resolved against $root and not against
  # wherever this was invoked from. Reading them relative to the caller's directory makes every file
  # vanish and the whole check pass - silently, and only when it is pointed somewhere other than the
  # current directory, which is exactly what anyone testing it would do. Both siblings shipped with
  # that defect and it went unnoticed because its only symptom was a green tick.
  file="$root/$path"
  [ -f "$file" ] || continue

  if matches="$(scan "$file")"; then
    echo "::error file=$path::a private key was committed here"
    # Line numbers only, never the matching text.
    echo "$matches" | while IFS= read -r line; do
      echo "    line ${line%%:*}"
    done
    found=1
  fi
done <<< "$files"

if [ "$found" -ne 0 ]; then
  echo "::error::A private key must never be committed. Assume it is published:"
  echo "    destroy it, mint a replacement, and re-pin the peer that trusted it."
  echo "    Removing it from the working tree is not enough - it is in the history."
  exit 1
fi
echo "OK: no private key in any tracked file."
