#!/usr/bin/env bash
#
# The test for scripts/check-no-addresses.sh and scripts/check-no-cyrillic.sh, plus the fail-open
# cases that apply to every guard here.
#
# It builds a throwaway git repository in a temp directory, stages one file at a time, and runs the
# guard against it. A real repository is the only honest fixture here: the guard scans `git ls-files`
# and nothing else, so a test that fed it a loose file would be testing something the guard never
# does.
#
# Every guard here carries its own positives and negatives inside it, and running it exercises them.
# What lands in this file is what a guard cannot check from within itself: the addresses guard, which
# needed a test written before it existed, and the loop around the Cyrillic guard's pattern - the git
# listing, the path resolution, and the binary skip.
set -euo pipefail

# Resolved from this script's own location, not from $OLDPWD. The first draft used "$OLDPWD/scripts/
# check-no-addresses.sh" after cd-ing into the temp directory, which quietly requires the test to be
# launched from the repository root - run it by absolute path from anywhere else and OLDPWD points at
# the caller's directory, the guard is not found, every case reports "exit 1", and the four cases
# that expect a rejection all pass. Three quarters green for entirely the wrong reason.
here="$(cd "$(dirname "$0")" && pwd)"
guard="$here/../check-no-addresses.sh"
[ -x "$guard" ] || { echo "FAIL: $guard is missing or not executable"; exit 1; }

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
cd "$tmp" && git init -q .

fail=0

run() { # runs the guard, echoes its numeric exit status
  local status=0
  "$@" >/dev/null 2>&1 || status=$?
  printf '%s\n' "$status"
}

check() { # name, content, expected exit
  printf '%s' "$2" > candidate.txt && git add -A
  got="$(run "$guard")"
  # The guard exits 2 when its own self-test fails. Folding that into "1" would report a guard that
  # can no longer detect anything as a guard that detected something.
  if [ "$got" -ge 2 ]; then
    echo "FAIL $1: guard aborted with exit $got (its self-test failed)"; fail=1; return
  fi
  [ "$got" = "$3" ] || { echo "FAIL $1: expected $3 got $got"; fail=1; }
}

check "bare ipv4"       "connect to 10.11.12.13 now"       1
check "ipv4 with port"  "host=10.11.12.14:8443"            1
check "home path"       "/Users/somebody/.config/x"        1
check "ddns name"       "laptop.mynetname.net"             1
check "clean text"      "the owner supplies host and port" 0
check "placeholder"     "example: HOST:PORT"               0
# Documentation ranges (RFC 5737) are ALLOWED: the README and the onboarding copy need an
# example address, and refusing them would push writers towards a real one.
check "documentation ip" "for example 203.0.113.5:8443"    0
check "loopback"         "listens on 127.0.0.1"            0
check "bare documentation ip" "203.0.113.5"                0
check "bare loopback"         "127.0.0.1"                  0

# An allowed address on the same line as a real one must not launder it. This is the shape onboarding
# prose actually takes, and a whole-line allow filter waves it through.
check "allowed masking real" "listens on 127.0.0.1, point the phone at 10.11.12.15" 1

# A real address that CONTAINS an allowed one. Every one of these reported clean while the allow list
# was erased from the line as an unanchored substring: erase `1.2.3.4` from `81.2.3.4` and the
# remainder is not an address any more. The allow list must match a WHOLE extracted address.
check "allow-substring 81.2.3.4"  "bridge at 81.2.3.4" 1
check "allow-substring 10.0.0.0"  "network 10.0.0.0"   1
check "allow-substring 210.0.0.0" "host 210.0.0.0"     1
check "allow-substring 127.0.0.10" "host 127.0.0.10"   1
check "allow-substring 1.2.3.45"  "host 1.2.3.45"      1

# Capitalised macOS home directories and uppercase hostnames are ordinary.
check "capitalised home path" "/Users/Igor/.ssh/id"  1
check "uppercase ddns name"   "MYBOX.MYNETNAME.NET"  1

# --- The fail-open cases -----------------------------------------------------------------------
#
# Both of these produced a clean tree and exit 0 before they were fixed, which is the single failure
# mode these scripts exist to prevent.

# Outside a repository, `git ls-files` fails and yields nothing. An empty listing must never be read
# as an empty tree.
outside="$(mktemp -d)"
printf 'bridge host: 10.11.12.16\n' > "$outside/candidate.txt"
got="$(cd "$outside" && run "$guard")"
[ "$got" = "2" ] || { echo "FAIL outside a repository: expected 2 got $got"; fail=1; }
rm -rf "$outside"

# From a subdirectory, `git ls-files` lists only that subtree. A clean subdirectory must not clear a
# tree whose offending file is somewhere else in it.
printf 'bridge host: 10.11.12.17\n' > candidate.txt
mkdir -p sub && printf 'nothing to see here\n' > sub/clean.txt
git add -A
got="$(cd sub && run "$guard")"
[ "$got" = "1" ] || { echo "FAIL from a subdirectory: expected 1 got $got"; fail=1; }
# ...and the same subdirectory over a clean tree still passes.
printf 'the owner supplies host and port\n' > candidate.txt && git add -A
got="$(cd sub && run "$guard")"
[ "$got" = "0" ] || { echo "FAIL from a subdirectory, clean tree: expected 0 got $got"; fail=1; }

# --- scripts/check-no-cyrillic.sh -----------------------------------------------------------------
#
# That guard carries its own positives and negatives inside it, matrixed per byte pattern, and
# running it exercises them. What it cannot test from within is the scan LOOP around them: the git
# listing, the path resolution, and the skip that keeps a binary asset from being read as text. Those
# are what this section covers, and each of them has been the whole bug in a guard before.
cyrillic="$here/../check-no-cyrillic.sh"
[ -x "$cyrillic" ] || { echo "FAIL: $cyrillic is missing or not executable"; exit 1; }

cyr() { # name, printf-escaped content, expected exit
  printf '%b' "$2" > candidate.txt && git add -A
  got="$(run "$cyrillic")"
  if [ "$got" -ge 2 ]; then
    echo "FAIL $1: guard aborted with exit $got (its self-test failed)"; fail=1; return
  fi
  [ "$got" = "$3" ] || { echo "FAIL $1: expected $3 got $got"; fail=1; }
}

cyr "russian comment"  '// \320\275\320\265 \321\202\321\200\320\276\320\263\320\260\320\271\n' 1
cyr "latin homoglyph"  'the p\320\260ir code\n'                                                 1
cyr "english prose"    'the bridge answers on the configured port\n'                            0
cyr "em dash"          'the bridge \342\200\224 once paired \342\200\224 answers\n'              0
cyr "curly quote"      'the owner\342\200\231s own words\n'                                      0
cyr "greek test data"  'the constant \317\200 and the angle \316\261\n'                          0

# Bytes that are not valid UTF-8, under a name that claims to be text. D0 B0 is a valid two-byte
# sequence on its own, so the pattern matches it, but the file cannot be decoded and the match is not
# a character - so the guard neither reports Cyrillic nor reports OK. It refuses, because the same
# "cannot decode" is what UTF-16 Russian looks like. A genuine binary asset is skipped by its
# EXTENSION instead, which the loop cases at the end of this file cover in both directions.
cyr "undecodable bytes under a text name" '\211PNG\r\n\320\260\377\376\320\261\n'                 1

# Outside a repository, `git ls-files` fails and yields nothing. An empty listing must never be read
# as an empty tree.
outside="$(mktemp -d)"
printf '%b' '// \320\275\320\265\n' > "$outside/candidate.txt"
got="$(cd "$outside" && run "$cyrillic")"
[ "$got" = "2" ] || { echo "FAIL cyrillic outside a repository: expected 2 got $got"; fail=1; }
rm -rf "$outside"

# From a subdirectory, `git ls-files` lists only that subtree. A clean subdirectory must not clear a
# tree whose offending file is somewhere else in it.
printf '%b' '// \320\275\320\265 \321\202\321\200\320\276\320\263\320\260\320\271\n' > candidate.txt
mkdir -p sub && printf 'nothing to see here\n' > sub/clean.txt
git add -A
got="$(cd sub && run "$cyrillic")"
[ "$got" = "1" ] || { echo "FAIL cyrillic from a subdirectory: expected 1 got $got"; fail=1; }
printf 'the bridge answers\n' > candidate.txt && git add -A
got="$(cd sub && run "$cyrillic")"
[ "$got" = "0" ] || { echo "FAIL cyrillic from a subdirectory, clean tree: expected 0 got $got"; fail=1; }

# --- check-no-cyrillic.sh: THE LOOP, not the pattern ----------------------------------------------
#
# Everything above feeds the guard one ordinary file and checks the verdict. These cases feed it the
# files whose NAME, ENCODING or stray BYTES decide whether the loop ever reads them at all - which is
# where all three of the holes found in review lived, and none of them was visible to a self-test that
# only ever ran the pattern against a probe:
#
#   1. `git ls-files` C-quotes any path with a byte above ASCII, so the guard's own existence test
#      failed on it and the file was skipped in silence. A source file named in Russian - the file
#      likeliest of all to be written in Russian - reported OK.
#   2. One NUL byte made grep call a .swift file binary and skip it, while the same NUL is valid
#      UTF-8 and passed the decode check. Russian around it went green.
#   3. UTF-16 and CP1251 could not be decoded, and "cannot decode" was treated as "nothing to see".
#
# Each case gets a fresh `loop/` directory so a fixture from the previous one cannot decide this one.
loop_fixture() { # name, expected exit; the fixture is written by the caller into loop/
  git add -A
  got="$(run "$cyrillic")"
  if [ "$got" -ge 2 ]; then
    echo "FAIL $1: guard aborted with exit $got (its self-test failed)"; fail=1; return
  fi
  [ "$got" = "$2" ] || { echo "FAIL $1: expected $2 got $got"; fail=1; }
}
fresh_loop() { rm -rf loop && mkdir loop; }

russian='// \320\275\320\265 \321\202\321\200\320\276\320\263\320\260\320\271 \321\215\321\202\320\276\n'
english='// nothing to see here\n'

# 1. A Cyrillic FILE NAME. Both halves: a Russian body must be caught, and an English body under the
#    same name must not be - the name is how the file is reached, never a verdict on its contents.
fresh_loop; printf '%b' "$russian" > "loop/$(printf 'Modal\320\236\320\272\320\275\320\276.swift')"
loop_fixture "cyrillic filename, russian body" 1
fresh_loop; printf '%b' "$english" > "loop/$(printf 'Modal\320\236\320\272\320\275\320\276.swift')"
loop_fixture "cyrillic filename, english body" 0

# 2. A NUL byte in an otherwise ordinary source file. Again both halves.
fresh_loop; printf '%b\000\n' "$russian" > loop/nul.swift
loop_fixture "nul byte, russian body" 1
fresh_loop; printf '%b\000\n' "$english" > loop/nul.swift
loop_fixture "nul byte, english body" 0

# 3. Encodings this guard cannot read. Refused rather than skipped, and refused whatever they say:
#    the guard cannot know what it cannot decode, and "I could not read it" must never render as OK.
fresh_loop; printf '%b' "$russian" | iconv -f UTF-8 -t UTF-16 > loop/utf16.swift
loop_fixture "utf-16 russian" 1
fresh_loop; printf '%b' "$russian" | iconv -f UTF-8 -t CP1251 > loop/cp1251.swift
loop_fixture "cp1251 russian" 1
fresh_loop; printf '%b' "$english" | iconv -f UTF-8 -t UTF-16 > loop/utf16-english.swift
loop_fixture "utf-16 english is refused too, not read" 1

# 4. A binary asset carrying bytes that land in a Cyrillic range by chance. Skipped BY EXTENSION, and
#    the extension is on a list somebody wrote down - so the same bytes under an extension nobody
#    listed are refused instead of waved through. A guard that reports Cyrillic in an icon gets
#    deleted; a guard that trusts any unreadable file gets walked around.
fresh_loop; printf '\211PNG\r\n\032\n\320\260\377\376\320\261' > loop/icon.png
loop_fixture "binary asset, listed extension" 0
fresh_loop; printf '\211PNG\r\n\032\n\320\260\377\376\320\261' > loop/blob.dat
loop_fixture "the same bytes, unlisted extension" 1
fresh_loop; printf '\211PNG\r\n\032\n\320\260\377\376\320\261' > loop/ICON.PNG
loop_fixture "the extension list is case-insensitive" 0

# 5. Valid UTF-8 with no extension at all - a shell script, a LICENSE - is read like anything else.
fresh_loop; printf '%b' "$russian" > loop/Makefile
loop_fixture "no extension, russian body" 1
fresh_loop; printf '%b' "$english" > loop/Makefile
loop_fixture "no extension, english body" 0

# 6. A file in a SUBDIRECTORY of a subdirectory, because `git ls-files -z` and the path resolution
#    around it are the mechanism every case above depends on.
fresh_loop; mkdir -p loop/a/b; printf '%b' "$russian" > loop/a/b/deep.swift
loop_fixture "nested path" 1

rm -rf loop && git add -A

# --- check-no-cyrillic.sh: SYMLINKS, and the accounting that makes the next hole visible ----------
#
# A symlink's blob is the TARGET'S NAME, not whatever it points at, and both directions were wrong:
#
#   - a link whose target is named in Russian put Cyrillic straight into the blob, and on a fresh
#     clone where the target is absent the link dangled, the existence test failed, and the guard
#     printed OK. Reproduced on a real clone before it was fixed.
#   - a link pointing OUT of the repository was scanned as though the far end's contents were the
#     committed data, which is a false positive on content nobody committed.
#
# That was the fourth defect of one shape - the guard declining to look at something and saying
# nothing - so the cases below also assert the ACCOUNTING, which is the fix for the shape rather than
# for its members. Each family of skip has to appear in the summary as a number.
out() { # runs the guard, echoes its stdout
  "$@" 2>/dev/null || true
}
says() { # name, expected substring of the guard's output
  got="$(out "$cyrillic")"
  case "$got" in
    (*"$2"*) ;;
    (*) echo "FAIL $1: the summary never said \"$2\""; printf '%s\n' "$got" | sed 's/^/        /'; fail=1 ;;
  esac
}

# The target's name is Russian; the link's own body is that name. Caught whether or not the target
# is present, because the blob is read rather than the filesystem followed.
fresh_loop
target="$(printf 'loop/\321\201\320\265\320\272\321\200\320\265\321\202.txt')"
printf 'nothing to see here\n' > "$target"
ln -s "$(basename "$target")" loop/link.txt
loop_fixture "symlink to a russian name, target present" 1
rm -f -- "$target"                    # exactly what a fresh clone of a filtered checkout leaves
git add -A
loop_fixture "symlink to a russian name, target absent" 1
says "the symlink is reported as a name" "read 1 symlink target name(s)"

# A link pointing outside the repository at a file full of Russian. The blob is one line of path, so
# this must be CLEAN - the content at the far end was never committed here.
fresh_loop
outside="$(mktemp -d)"
printf '// \320\275\320\265 \321\202\321\200\320\276\320\263\320\260\320\271\n' > "$outside/russian.swift"
ln -s "$outside/russian.swift" loop/points-away.swift
loop_fixture "symlink out of the repository is not the far end's content" 0
rm -rf "$outside"

# Tracked and then deleted from the working tree. Skipped in silence before; now read from the index,
# because the blob is what would be pushed. The staging happens BEFORE the delete and is not repeated
# afterwards - `git add -A` over a deleted file stages the deletion, which would untrack the very
# thing this case is about.
fresh_loop
printf '// \320\275\320\265 \321\202\321\200\320\276\320\263\320\260\320\271\n' > loop/staged-then-deleted.swift
git add -A && rm loop/staged-then-deleted.swift
got="$(run "$cyrillic")"
[ "$got" = "1" ] || { echo "FAIL staged, then deleted from the working tree: expected 1 got $got"; fail=1; }
says "the index read is reported" "read 1 from the index"

# Every family of skip is counted and named. A guard that declines to look at something without
# saying so is how all four holes stayed invisible; these assert the numbers rather than the verdict.
fresh_loop; printf '\211PNG\r\n\032\n\320\260\377\376\320\261' > loop/icon.png
git add -A
says "a declined binary asset is counted" "declined 1: binary asset(s)"
says "the summary always states both totals" "declined 1)."

fresh_loop; git add -A
says "a clean run still states its totals" "declined 0)."

# An unreadable file names its real cause. It used to be reported as invalid UTF-8, which is the
# wrong cause and sends whoever reads it to convert an encoding that was never the problem.
#
# Tracked while it is still readable, then locked: `git add` cannot index a file it cannot open, so
# staging afterwards would fail the test run rather than the guard.
fresh_loop; printf 'tracked while readable\n' > loop/locked.txt
git add -A && chmod 000 loop/locked.txt
got="$(run "$cyrillic")"
[ "$got" = "1" ] || { echo "FAIL an unreadable file is refused, not skipped: expected 1 got $got"; fail=1; }
says "the permission message names its cause" "could not be opened for reading"
chmod 644 loop/locked.txt

rm -rf loop && git add -A

exit "$fail"
