#!/usr/bin/env bash
#
# Refuses any committed hostname, IP address or personal path.
#
# The rule this enforces is spec section 12: the address a phone dials belongs to the person running
# the bridge and must never be in the repository. The project these guards came from kept that rule
# in prose, and it was violated in three files before anyone noticed. It was violated again in this
# repository, on 2026-09-08, while the skeleton was being built: a real LAN address written into the
# plan as a test fixture reached the public remote and the history had to be rewritten. Prose does
# not enforce anything. This does.
#
# The fourth sibling of check-no-tokens.sh, check-no-credentials.sh and check-no-private-keys.sh, and
# separate from all three for the same reason they are separate from each other: the remedy differs.
# A token is revoked, a password is changed, a private key is destroyed - and an address cannot be
# any of those. It is a fact about where somebody lives. The only remedy is that it never lands.
#
# Usage: scripts/check-no-addresses.sh [path]        default: the whole working tree
set -euo pipefail

root="${1:-.}"

patterns=(
  '\b(([0-9]{1,3}\.){3}[0-9]{1,3})\b'          # IPv4
  '/Users/[a-z]'                               # a personal home directory
  '\b[a-z0-9-]+\.(mynetname\.net|keenetic\.(pro|link|name)|duckdns\.org|ddns\.net|no-ip\.(org|com))\b'
)
# What each pattern is called, for the failure message. Parallel to `patterns` by index.
names=(
  "an IPv4 address"
  "a personal home directory path"
  "a dynamic-DNS hostname"
)

# Deliberately allowed. The README and the onboarding copy need an example address, and a guard that
# refuses every one of them pushes writers towards a real one - which is the failure this script
# exists to prevent. So the RFC 5737 documentation ranges, loopback, the unspecified address and a
# netmask are let through on purpose.
allow='(0\.0\.0\.0|127\.0\.0\.1|255\.255\.255\.0|1\.2\.3\.4|203\.0\.113\.|198\.51\.100\.|192\.0\.2\.)'

# The single scan. Defined once and called from BOTH the self-test below and the real loop after it,
# so the probe cannot succeed through a path the real scan does not use. Prints the line numbers of
# genuine hits, nothing else.
#
# The allowed forms are erased from the matching line and the line is then re-tested, rather than the
# line being dropped wholesale when it contains an allowed form. Dropping the whole line is the
# obvious way to write this and it is wrong: "the bridge listens on 127.0.0.1, point the phone at
# <a real LAN address>" is one line, and a whole-line filter would wave it through - the exact shape
# of sentence that onboarding copy is made of.
#
# -e is not style. A pattern beginning with a character grep reads as the start of a bundle of
# options makes it exit 2, and with stderr discarded and the status consumed by an `if`, every file
# reports clean.
scan() { # $1 = pattern, $2 = file
  local p="$1" f="$2" hit content
  grep -nEI -e "$p" "$f" 2>/dev/null | while IFS= read -r hit; do
    content="$(printf '%s\n' "${hit#*:}" | sed -E "s/${allow}/ /g")"
    if printf '%s\n' "$content" | grep -qE -e "$p"; then
      printf '%s\n' "${hit%%:*}"
    fi
  done
}

# --- The self-test ---------------------------------------------------------------------------------
#
# This check proves it can still detect, and can still allow, before it is allowed to report OK. A
# failure to match a known-positive is a hard error, not a clean run - see the longer note in
# check-no-tokens.sh for the four green-tick failures that put this here. Both directions are
# exercised, because an allow list that swallowed everything would also produce a clean run.
probe="$(mktemp)"
trap 'rm -f "$probe"' EXIT
positives=("connect to 10.11.12.13 now" "/Users/somebody/.config/x" "laptop.mynetname.net")
i=0
while [ "$i" -lt "${#patterns[@]}" ]; do
  printf '%s\n' "${positives[$i]}" > "$probe"
  if [ -z "$(scan "${patterns[$i]}" "$probe" || true)" ]; then
    echo "::error::check-no-addresses.sh is broken."
    echo "    It cannot match a known-positive (${names[$i]}), so a clean run would mean nothing."
    echo "    Fix the pattern before trusting any result from this script."
    exit 2
  fi
  i=$((i + 1))
done
printf '%s\n' "for example 203.0.113.5:8443 and 127.0.0.1" > "$probe"
if [ -n "$(scan "${patterns[0]}" "$probe" || true)" ]; then
  echo "::error::check-no-addresses.sh is broken."
  echo "    It rejects the documentation addresses it is supposed to allow, which will push whoever"
  echo "    hits it towards writing a real one instead. Fix the allow list."
  exit 2
fi
rm -f "$probe"
trap - EXIT

# Tracked files only. Anything gitignored is not what this protects, and an address has to be tracked
# to be pushed.
self="scripts/check-no-addresses.sh"
found=0
while IFS= read -r path; do
  [ "$path" = "$self" ] && continue
  case "$path" in
    # scripts/tests/ holds this guard's fixtures - a detector for addresses cannot be tested without
    # strings that look like addresses.
    scripts/tests/*) continue ;;
    # The licence text is not ours to edit and contains nothing personal.
    LICENSE) continue ;;
    # This is a hole. docs/superpowers/ carries the plan document, and the plan document spells out
    # this guard's fixtures verbatim - so the guard cannot be run against it without failing on its
    # own test data. Nothing else protects that directory: an address pasted into the plan will reach
    # the public remote unchallenged. The hole closes when docs/superpowers/ is deleted from the
    # repository at the end of the project, and this case arm must be deleted with it.
    docs/superpowers/*) continue ;;
  esac
  # ls-files prints repository-relative paths, so they are resolved against $root and not against
  # wherever this was invoked from. Reading them relative to the caller's directory makes every file
  # vanish and the whole check pass - silently, and only when it is pointed somewhere other than the
  # current directory, which is exactly what anyone testing it would do.
  file="$root/$path"
  [ -f "$file" ] || continue

  i=0
  while [ "$i" -lt "${#patterns[@]}" ]; do
    lines="$(scan "${patterns[$i]}" "$file" || true)"
    if [ -n "$lines" ]; then
      echo "::error file=$path::${names[$i]} was committed here"
      # Line numbers and the category, never the matching text. The address is the private thing;
      # echoing it into a public CI log would publish it a second time.
      echo "$lines" | while IFS= read -r n; do
        echo "    line $n"
      done
      found=1
    fi
    i=$((i + 1))
  done
done <<< "$(git -C "$root" ls-files)"

if [ "$found" -ne 0 ]; then
  echo "::error::An address, hostname or personal path must never be committed. Replace it with a"
  echo "    placeholder, or with an RFC 5737 documentation address (203.0.113.x), and remove it from"
  echo "    history if it has already been pushed."
  exit 1
fi
echo "OK: no address, hostname or personal path in any tracked file."
