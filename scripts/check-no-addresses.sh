#!/usr/bin/env bash
#
# Refuses committed IPv4 addresses, macOS home-directory paths and consumer dynamic-DNS hostnames.
#
# The rule this enforces is spec section 12: the address a phone dials belongs to the person running
# the bridge and must never be in the repository. The project these guards came from kept that rule
# in prose, and it was violated in three files before anyone noticed. It was violated again in this
# repository, on 2026-09-08, while the skeleton was being built: a real LAN address written into the
# plan as a test fixture reached the public remote and the history had to be rewritten. Prose does
# not enforce anything. This does.
#
# WHAT IT ACTUALLY COVERS, which is less than "any address":
#   - IPv4, in any surrounding text, minus a short allow list of documentation and loopback
#     addresses (see `allow_ipv4`).
#   - `/Users/<name>` - a macOS home directory, case-insensitively.
#   - Six consumer dynamic-DNS suffixes, case-insensitively (see `patterns`).
#
# WHAT IT DOES NOT COVER, and where a leak can still walk through:
#   - IPv6, in any form.
#   - A generic hostname. `bridge.example.org` and a real vanity domain look identical to this.
#   - `/home/<name>`, and every other non-macOS home directory layout.
#   - A MAC address, a serial number, a Wi-Fi SSID.
#   - Anything under docs/superpowers/plans/, which is excluded outright - see the note by that
#     exclusion.
# This is a floor, not a ceiling. It catches the mistakes that have actually been made twice.
#
# The fourth sibling of check-no-tokens.sh, check-no-credentials.sh and check-no-private-keys.sh, and
# separate from all three for the same reason they are separate from each other: the remedy differs.
# A token is revoked, a password is changed, a private key is destroyed - and an address cannot be
# any of those. It is a fact about where somebody lives. The only remedy is that it never lands.
#
# Usage: scripts/check-no-addresses.sh [path]        default: the whole working tree
set -euo pipefail

# The repository root, not the directory this was invoked from. `git ls-files` run inside a
# subdirectory lists only that subtree, and lists it relative to that subdirectory - so the
# exclusions below stop matching and a clean subdirectory produces OK on a partial scan. A guard
# that reports on a fraction of the tree while claiming to have checked it is worse than no guard.
if ! root="$(git -C "${1:-.}" rev-parse --show-toplevel 2>/dev/null)"; then
  echo "::error::check-no-addresses.sh found no git repository at '${1:-.}'."
  echo "    A guard that cannot list the tracked files must not report a clean tree."
  exit 2
fi

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
#
# ANCHORED, and applied to a whole extracted address rather than to the line it sits on. Both halves
# of that are load-bearing and both were got wrong first:
#
#   - Filtering by line lets an allowed address launder a real one sharing it. "listens on 127.0.0.1,
#     point the phone at <a real LAN address>" is one line, and a whole-line filter waves it through
#     - the exact shape of sentence onboarding copy is made of.
#   - Erasing an unanchored allow pattern from the line is no better. `81.2.3.4` contains `1.2.3.4`,
#     `127.0.0.10` contains `127.0.0.1`, `10.0.0.0` contains `0.0.0.0`; erase the substring and the
#     remainder no longer looks like an address, so the line reports clean. All five of those were
#     measured passing before this was rewritten.
#
# So: extract every complete match, and allow it only if the WHOLE address is on this list.
allow_ipv4='0\.0\.0\.0|127\.0\.0\.1|255\.255\.255\.0|192\.0\.2\.[0-9]{1,3}|198\.51\.100\.[0-9]{1,3}|203\.0\.113\.[0-9]{1,3}'
# Parallel to `patterns`. The other two have no allowed forms at all.
allows=("$allow_ipv4" "" "")

# The single scan. Defined once and called from BOTH the self-test below and the real loop after it,
# so the probe cannot succeed through a path the real scan does not use. Prints the line numbers of
# genuine hits, deduplicated, and nothing else.
#
# -o is what makes the whole-address allow list possible: grep prints one line per match rather than
# per matching line, so several addresses on one line are judged separately.
# -i because a capitalised macOS home directory is ordinary and MYBOX.MYNETNAME.NET resolves exactly
# like the lowercase form. IPv4 has no letters, so it costs that pattern nothing.
# -e is not style. A pattern beginning with a character grep reads as the start of a bundle of
# options makes it exit 2, and with stderr discarded and the status consumed by an `if`, every file
# reports clean.
scan() { # $1 = pattern, $2 = allowed whole matches (may be empty), $3 = file
  local p="$1" a="$2" f="$3" hit match
  grep -noEai -e "$p" "$f" 2>/dev/null | while IFS= read -r hit; do
    match="${hit#*:}"
    if [ -n "$a" ] && printf '%s\n' "$match" | grep -qEi -e "^($a)$"; then
      continue
    fi
    printf '%s\n' "${hit%%:*}"
  done | awk '!seen[$0]++'
}

# --- The self-test ---------------------------------------------------------------------------------
#
# This check proves it can still detect, and can still allow, before it is allowed to report OK. A
# failure to match a known-positive is a hard error, not a clean run - see the longer note in
# check-no-tokens.sh for the four green-tick failures that put this here. Both directions are
# exercised, because an allow list that swallowed everything would also produce a clean run.
probe="$(mktemp)"
trap 'rm -f "$probe"' EXIT
positives=(
  "connect to 10.11.12.13 now"
  "/Users/Somebody/.config/x"
  "LAPTOP.MYNETNAME.NET"
)
# Near misses of the allow list. Every one of these passed before the allow list was anchored.
near_misses=("bridge at 81.2.3.4" "network 10.0.0.0" "host 210.0.0.0" "host 127.0.0.10" "host 1.2.3.45")
i=0
while [ "$i" -lt "${#patterns[@]}" ]; do
  printf '%s\n' "${positives[$i]}" > "$probe"
  if [ -z "$(scan "${patterns[$i]}" "${allows[$i]}" "$probe" || true)" ]; then
    echo "::error::check-no-addresses.sh is broken."
    echo "    It cannot match a known-positive (${names[$i]}), so a clean run would mean nothing."
    echo "    Fix the pattern before trusting any result from this script."
    exit 2
  fi
  i=$((i + 1))
done
for sample in "${near_misses[@]}"; do
  printf '%s\n' "$sample" > "$probe"
  if [ -z "$(scan "${patterns[0]}" "${allows[0]}" "$probe" || true)" ]; then
    echo "::error::check-no-addresses.sh is broken."
    echo "    Its allow list is swallowing a real address ('$sample'), so a clean run would mean"
    echo "    nothing. Anchor the allow list against the whole address before trusting any result."
    exit 2
  fi
done
printf '%s\n' "for example 203.0.113.5:8443 and 127.0.0.1" > "$probe"
if [ -n "$(scan "${patterns[0]}" "${allows[0]}" "$probe" || true)" ]; then
  echo "::error::check-no-addresses.sh is broken."
  echo "    It rejects the documentation addresses it is supposed to allow, which will push whoever"
  echo "    hits it towards writing a real one instead. Fix the allow list."
  exit 2
fi
rm -f "$probe"
trap - EXIT

# The walk - which files are read, which are declined and which are refused - lives in
# scripts/lib/tracked-files.sh, shared by every guard here. It is not a tidiness: this script used a
# bare `git ls-files` and skipped in silence any path holding a byte above ASCII, so an address and a
# home directory inside a file named in Russian reported clean while the identical bytes under an
# ASCII name were refused. The file most likely to carry a Russian name is the file most likely to
# carry the owner's own words, so this was the worst place in the tree to be unable to look.
. "$(cd "$(dirname "$0")" && pwd)/lib/tracked-files.sh"

guard_self="scripts/check-no-addresses.sh"

# The paths this guard leaves alone, each for its own stated reason. They are DECLINED rather than
# skipped, so the summary counts them and nobody has to read this function to know how many files
# went unexamined.
guard_excluded() { # $1 = path
  case "$1" in
    # scripts/tests/ holds this guard's fixtures - a detector for addresses cannot be tested without
    # strings that look like addresses.
    scripts/tests/*) return 0 ;;
    # The licence text is not ours to edit and contains nothing personal.
    LICENSE) return 0 ;;
    # This is a hole. docs/superpowers/plans/ carries the plan document, and the plan document spells
    # out this guard's fixtures verbatim - so the guard cannot be run against it without failing on
    # its own test data. Nothing else protects that directory: an address pasted into the plan will
    # reach the public remote unchallenged. The hole closes when docs/superpowers/ is deleted from
    # the repository at the end of the project, and this case arm must be deleted with it.
    #
    # Narrowed to plans/ deliberately: the spec under docs/superpowers/specs/ carries no fixtures and
    # is scanned like everything else. Excluding less costs nothing.
    docs/superpowers/plans/*) return 0 ;;
  esac
  return 1
}

examine() { # $1 = path, $2 = the file to read, $3 = what it is
  local i=0 lines hit=1
  while [ "$i" -lt "${#patterns[@]}" ]; do
    lines="$(scan "${patterns[$i]}" "${allows[$i]}" "$2" || true)"
    if [ -n "$lines" ]; then
      echo "::error file=$1::$3 holds ${names[$i]}"
      # Line numbers and the category, never the matching text. The address is the private thing;
      # echoing it into a public CI log would publish it a second time.
      echo "$lines" | while IFS= read -r n; do
        echo "    line $n"
      done
      hit=0
    fi
    i=$((i + 1))
  done
  return "$hit"
}

guard_walk "$root" "check-no-addresses.sh" examine

if [ "$guard_found" -ne 0 ]; then
  echo "::error::An address, hostname or personal path must never be committed. Replace it with a"
  echo "    placeholder, or with an RFC 5737 documentation address (203.0.113.x), and remove it from"
  echo "    history if it has already been pushed."
fi
guard_finish "no address, hostname or personal path in any tracked file."
