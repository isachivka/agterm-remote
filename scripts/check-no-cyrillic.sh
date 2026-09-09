#!/usr/bin/env bash
#
# Fails when a Cyrillic character is committed to this repository.
#
# Every file here is English by rule, and the rule has already been broken once. The Go bridge was
# ported out of a private repository, and it carried 280 fragments of Cyrillic with it - not stray
# characters but whole sentences of the owner's own words, quoted from a private conversation into a
# public tree. Removing them meant rewriting history. The Swift sources under mac/ come out of that
# same private repository, so this guard was written before a single file was copied: a guard that
# arrives after the port documents the leak instead of preventing it.
#
# This is NOT a ban on non-ASCII. Em dashes and curly quotes run through the prose in this
# repository, Greek letters appear in test data, and accented Latin appears in names. A guard that
# rejected all of them would be switched off within a week, and then it would protect nothing. It
# refuses the Cyrillic blocks and nothing else.
#
# WHAT IS TESTED WHERE. The self-test below proves the PATTERN: that it still matches every block and
# still tolerates their neighbours, checked on every run so a broken pattern can never report a clean
# tree. It cannot prove the LOOP - which files are read, which are skipped, and which are refused -
# because a loop test needs a tree to walk. That lives in scripts/tests/guards_test.sh, which builds
# a scratch repository and runs this script over it. Three holes lived in the loop and none of them
# was visible to a self-test that only ever fed the pattern: a file skipped for its NAME, a file
# skipped for one NUL byte, and a file skipped for its ENCODING.
#
# OUT OF SCOPE, deliberately: Cyrillic written as an escape sequence rather than as bytes - Swift's
# \u{43F}, Java's \u043f, JSON's \u043F. Those are ASCII on disk and no byte scan can see them.
# Catching them means a per-language decoder, and a guard that half-decodes source is a guard that
# argues with the compiler. If it ever matters, it is a separate check with its own name.
#
# Usage: scripts/check-no-cyrillic.sh [path]      default: the whole working tree
set -euo pipefail

# The repository root, not the directory this was invoked from. `git ls-files` run inside a
# subdirectory lists only that subtree, so a clean subdirectory would produce OK on a partial scan.
# A guard that reports on a fraction of the tree while claiming to have checked it is worse than no
# guard.
if ! root="$(git -C "${1:-.}" rev-parse --show-toplevel 2>/dev/null)"; then
  echo "::error::check-no-cyrillic.sh found no git repository at '${1:-.}'."
  echo "    A guard that cannot list the tracked files must not report a clean tree."
  exit 2
fi

# --- The pattern -----------------------------------------------------------------------------------
#
# Matched as UTF-8 BYTES under LC_ALL=C rather than as characters. Character classes like [[:alpha:]]
# and multibyte ranges depend on the locale and on the collation the runner happens to have, and the
# two greps this has to work under - GNU grep on the CI runner, BSD grep on a developer's Mac - do
# not agree about them. Bytes are the one thing both read identically.
#
# Every byte below is written as an octal escape, so this file contains no non-ASCII character at
# all. That is deliberate twice over: the guard cannot match itself and therefore needs no
# self-exclusion (an exclusion is a hole, and holes are where the next leak goes), and a reviewer can
# see which code points are covered without trusting their font.
#
# The blocks, and why each one:
#
#   U+0400-U+04FF  Cyrillic                D0 80 .. D3 BF   Russian prose. The whole reason for this.
#   U+0500-U+052F  Cyrillic Supplement     D4 80 .. D4 AF   Ends at AF: D4 B0 is already Armenian.
#   U+1C80-U+1C8F  Cyrillic Extended-C     E1 B2 80 .. 8F   Ends at 8F: E1 B2 90 is Georgian.
#   U+2DE0-U+2DFF  Cyrillic Extended-A     E2 B7 A0 .. BF   Starts at A0: E2 B7 80 is Ethiopic.
#   U+A640-U+A69F  Cyrillic Extended-B     EA 99 80 .. EA 9A 9F
#
# In well-formed UTF-8 these byte sequences cannot occur inside any other character: 80-BF are only
# ever continuation bytes, and D0-D3, E1, E2 and EA are only ever lead bytes. The one place the
# equivalence breaks is a file that is not UTF-8 at all - a binary - and those are excluded below.
b() { printf "$1"; }
c80="$(b '\200')"; c8f="$(b '\217')"; c9f="$(b '\237')"; ca0="$(b '\240')"; caf="$(b '\257')"
cbf="$(b '\277')"
d0="$(b '\320')"; d3="$(b '\323')"; d4="$(b '\324')"
e1="$(b '\341')"; e2="$(b '\342')"; ea="$(b '\352')"
b2="$(b '\262')"; b7="$(b '\267')"; x99="$(b '\231')"; x9a="$(b '\232')"

pattern="[$d0-$d3][$c80-$cbf]"
pattern="$pattern|$d4[$c80-$caf]"
pattern="$pattern|$e1$b2[$c80-$c8f]"
pattern="$pattern|$e2$b7[$ca0-$cbf]"
pattern="$pattern|$ea$x99[$c80-$cbf]|$ea$x9a[$c80-$c9f]"

# The single grep invocation. Defined once and called from BOTH the self-test below and the scan
# after it, so the probe cannot pass through a path the real scan does not use. A probe running its
# own grep tests a copy of the pattern and passes happily while the real invocation is broken.
#
# `-a`, NOT `-I`. Letting grep decide what is binary was a hole: grep calls any file holding a NUL
# byte binary and skips it, while a NUL is perfectly valid UTF-8 - so one NUL pasted into a .swift
# file laundered the Russian around it and the run went green. Deciding what is text is the loop's
# job below, where the decision is explicit and testable; grep is told to read whatever it is given.
scan() { LC_ALL=C grep -anE -e "$pattern" "$1" 2>/dev/null; }

# --- The self-test ---------------------------------------------------------------------------------
#
# This check proves it can still detect, AND that it still tolerates what belongs here, before it is
# allowed to report OK. The second half is not the lesser half. On an earlier guard in this
# repository, four review rounds turned up six defects and five of them were in the tolerating half -
# a guard that rejects correct content is a guard somebody deletes, and a deleted guard catches
# nothing at all.
#
# The negatives are matrixed per SHAPE, not per family: each of the five byte patterns above gets its
# own neighbours, on both sides where a neighbour exists. Widening or narrowing any one of them owes
# a new case in the shape it changed; a negative for a different block does not cover it, because it
# never touched those bytes.
#
# Probes are emitted with printf '%b' so the escape sequences below become the bytes they name.
probe="$(mktemp)"
trap 'rm -f "$probe"' EXIT

must_match=(
  # Russian prose, the shape that actually happened: a comment in the owner's language.
  '// \320\275\320\265 \321\202\321\200\320\276\320\263\320\260\320\271 \321\215\321\202\320\276'
  '\320\237\321\200\320\270\320\262\320\265\321\202, \320\274\320\270\321\200'
  # A single Cyrillic letter hiding inside an otherwise Latin word: U+0430 in "pair". This is the
  # shape a reader can never see and a byte scan always can.
  'the p\320\260ir code'
  # Both ends of the basic block: U+0400 and U+04FF.
  'edge \320\200 low'
  'edge \323\277 high'
  # Both ends of the Supplement: U+0500 and U+052F.
  'supplement \324\200 low'
  'supplement \324\257 high'
  # Both ends of Extended-C: U+1C80 and U+1C8F.
  'ext-c \341\262\200 low'
  'ext-c \341\262\217 high'
  # Both ends of Extended-A: U+2DE0 and U+2DFF.
  'ext-a \342\267\240 low'
  'ext-a \342\267\277 high'
  # All four corners of Extended-B, which is two byte ranges rather than one: U+A640, U+A67F,
  # U+A680, U+A69F. The second range was the easy one to leave out.
  'ext-b \352\231\200 low'
  'ext-b \352\231\277 mid'
  'ext-b \352\232\200 mid'
  'ext-b \352\232\237 high'
  # A tolerated character on the same line must not launder the line. This is the shape the prose in
  # this repository actually takes - an em dash and a curly quote in an English sentence - and a
  # guard that filtered whole lines by what they also contain would wave it through.
  'the bridge \342\200\224 \321\210\321\202\320\276\320\261\321\213 \342\200\224 answers'
  'it\342\200\231s \320\275\320\265 fine'
)
must_not_match=(
  # Plain ASCII, including the words this guard talks about.
  'the pairing code is shown in the menu bar'
  '// Cyrillic is refused by scripts/check-no-cyrillic.sh'
  # Shape 1, low neighbour: Greek. U+03C0, U+03B1, U+03A9 - lead bytes CE and CF, immediately below
  # D0. Greek is legitimately present as test data and must never trip this.
  'the constant \317\200 and the angle \316\261 and \316\251'
  # Shape 2, high neighbour: Armenian. U+0531 is D4 B1, one byte past where the Supplement stops,
  # and U+0561 is D5 A1, past the lead byte entirely.
  'armenian \324\261 and \325\241'
  # Shape 3, both neighbours: U+1C7F (E1 B1 BF, Ol Chiki) below and U+1C90 (E1 B2 90, Georgian)
  # immediately above. The Georgian one shares two of the three bytes.
  'below \341\261\277 and above \341\262\220'
  # Shape 4, low neighbour: U+2DC0 (E2 B7 80, Ethiopic Extended) shares two bytes with Extended-A and
  # differs only in the third. Plus the two characters this repository's prose is full of - the em
  # dash U+2014 and the curly quotes U+2018/U+2019/U+201C/U+201D - which share only the lead byte.
  'ethiopic \342\267\200 stays'
  'a sentence \342\200\224 with an em dash \342\200\224 in it'
  'the \342\200\234quoted\342\200\235 word and the owner\342\200\231s note'
  # Shape 4, high neighbour: U+2E00, supplemental punctuation, one code point past the block.
  'punctuation \342\270\200 stays'
  # Shape 5, both neighbours: U+A63F (EA 98 BF) below and U+A6A0 (EA 9A A0, Bamum) immediately above
  # the second of Extended-B's two byte ranges.
  'below \352\230\277 and above \352\232\240'
  # The rest of the legitimate non-ASCII in a repository like this one: accented Latin in a name,
  # CJK, an emoji in a README, and the box drawing a terminal project draws diagrams with.
  'reported by Jos\303\251 and by M\303\274ller'
  'the Japanese word \346\227\245\346\234\254 in a comment'
  'a lock \360\237\224\222 in the README'
  'a diagram \342\224\200\342\224\200\342\224\220 in the docs'
)

for line in "${must_match[@]}"; do
  printf '%b\n' "$line" > "$probe"
  if ! scan "$probe" >/dev/null; then
    echo "::error::check-no-cyrillic.sh is broken."
    echo "    It cannot match a known-positive, so a clean run would mean nothing:"
    echo "        $line"
    exit 2
  fi
done
for line in "${must_not_match[@]}"; do
  printf '%b\n' "$line" > "$probe"
  if scan "$probe" >/dev/null; then
    echo "::error::check-no-cyrillic.sh is too broad."
    echo "    It rejects content that belongs here, which is how a guard gets switched off:"
    echo "        $line"
    exit 2
  fi
done
rm -f "$probe"
trap - EXIT

# --- The scan --------------------------------------------------------------------------------------
#
# Tracked files only: a gitignored file is not what this protects, and text has to be tracked to be
# pushed.
#
# `-z`, and it is not a nicety. Without it `git ls-files` renders a path containing any byte above
# ASCII in C-quoted form - "Modal\320\236\320\272\320\275\320\276.swift", quotes and
# backslash escapes included - because `core.quotePath` defaults to true. That name never matches a
# file on disk, the `[ -f ]` test below fails, the loop skips the file IN SILENCE, and a source file
# with a Cyrillic name and a Russian body reported OK. A file named in Russian is the likeliest file
# in the tree to be written in Russian, so the one path the guard could not see was the one it most
# needed to. `-z` emits raw bytes with a NUL terminator and no quoting at all.
#
# The listing goes to a file rather than a variable: a shell variable cannot hold a NUL byte, so
# capturing `-z` output in one silently mangles every path.
listing="$(mktemp)"
trap 'rm -f "$listing"' EXIT
if ! git -C "$root" ls-files -z > "$listing"; then
  echo "::error::check-no-cyrillic.sh could not list the tracked files in '$root'."
  echo "    Refusing to report a clean tree on the strength of an empty listing."
  exit 2
fi
if [ ! -s "$listing" ]; then
  # A listing that came back empty is not an empty tree until somebody proves it is one. Every other
  # failure this script has had produced the output that means "fine", which is the only output
  # nobody investigates.
  echo "::error::check-no-cyrillic.sh found no tracked files under '$root'."
  echo "    A guard over an empty set is not a pass."
  exit 2
fi

# Extensions whose files are not text and are not expected to be readable. A file with one of these
# is skipped; anything else this script cannot decode is REFUSED, below. The list is here, in a diff,
# rather than implied by a heuristic - which is the difference between a decision and an accident.
binary_extensions="png jpg jpeg gif webp ico icns pdf zip jar apk aab keystore jks p12 woff woff2 \
ttf otf mp3 mp4 mov so dylib dll a o bin wasm class ser gz bz2 xz zst 7z"

is_binary_asset() { # $1 = path
  local lower extension
  lower="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  extension="${lower##*.}"
  [ "$extension" = "$lower" ] && return 1   # no extension at all
  case " $binary_extensions " in (*" $extension "*) return 0 ;; esac
  return 1
}

found=0
unreadable=0
skipped=0
while IFS= read -r -d '' path; do
  file="$root/$path"
  [ -f "$file" ] || continue

  # A file this script cannot decode as UTF-8 is one of two things, and they have opposite answers.
  #
  # An icon is binary: its bytes are not characters, some of them land in these ranges by chance, and
  # reporting Cyrillic in a PNG is how a guard gets deleted. It is skipped, and the extension that
  # said so is on a list somebody wrote down.
  #
  # Anything else is text in an encoding this script cannot read - UTF-16, CP1251 - and Russian in
  # either walks straight through a scan that skips what it cannot decode. Skipping it and printing
  # OK is the exact failure this file's header warns about: reporting on a fraction of the tree while
  # claiming to have checked all of it. So it is refused, loudly, and the run does not go green until
  # somebody decides which of the two it is.
  if ! iconv -f UTF-8 -t UTF-8 < "$file" >/dev/null 2>&1; then
    if is_binary_asset "$path"; then
      skipped=$((skipped + 1))
      continue
    fi
    echo "::error file=$path::this file is not valid UTF-8, so this guard cannot read it"
    echo "    Text in UTF-16 or CP1251 passes an encoding-blind scan untouched, so a file that"
    echo "    cannot be decoded is never reported as clean. Convert it to UTF-8, or - if it really"
    echo "    is a binary asset - add its extension to binary_extensions in this script."
    unreadable=1
    continue
  fi

  if matches="$(scan "$file")"; then
    echo "::error file=$path::Cyrillic text was committed here"
    # Line numbers, never the matched text. The fragments this exists to catch are the owner's
    # private words, and echoing them into a public CI log would finish the job the commit started.
    echo "$matches" | while IFS= read -r line; do
      echo "    line ${line%%:*}"
    done
    found=1
  fi
done < "$listing"

if [ "$found" -ne 0 ]; then
  echo "::error::Every committed file in this repository is English. Cyrillic here is almost always"
  echo "    text carried over from the private repository this project was ported out of - translate"
  echo "    it or delete it, and check the rest of the same file before pushing again."
fi
if [ "$found" -ne 0 ] || [ "$unreadable" -ne 0 ]; then
  exit 1
fi
if [ "$skipped" -ne 0 ]; then
  echo "OK: no Cyrillic in any tracked file ($skipped binary asset(s) skipped by extension)."
else
  echo "OK: no Cyrillic in any tracked file."
fi
