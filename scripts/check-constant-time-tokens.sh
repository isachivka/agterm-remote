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
# WHAT IT IS WORTH, stated narrowly on purpose
#
# It stops ONE edit: somebody simplifying the subtle call, in place, into the obvious comparison. It
# reads text, not Go, so a comparison split across two lines, moved into a helper, or performed on a
# renamed copy of the token walks straight past it and always will. A green `guards` job is therefore
# not evidence that this package is timing-safe, and must not be read as one. The structural half of
# the property - that Consume compares with subtle at all - is held by
# TestConsumeComparesWithConstantTime, which parses the package with go/parser and catches exactly
# the cases this script structurally cannot.
#
# WHAT IT ACTUALLY COVERS, which is deliberately less than "all of Go"
#
#   - Non-test .go files under bridge/internal/enroll only. Tests legitimately compare two minted
#     tokens with == to assert they differ; that is not a secret comparison and must stay allowed.
#   - bytes.Equal, bytes.Compare, slices.Equal, slices.Compare, reflect.DeepEqual, strings.Compare
#     and strings.EqualFold, on a line that mentions a token. Deliberately NOT "with the token as the
#     first argument": requiring it before the first `)` let bytes.Equal(hkdf(given), w.token[:])
#     through, because the nested call swallowed the rest of the line.
#   - == and != with a token as an immediate operand, on either side.
#   - string(token) != string(other), which is how two byte slices get compared by somebody who did
#     not want to import bytes. Go compiles it to memequal, which returns on the first differing
#     byte like every other one of these. It is the single most likely way this rule gets broken and
#     it walked past the first version of this guard.
#   - Comments are cut off before anything is matched, so neither the prose explaining this rule nor
#     a trailing comment mentioning a token trips it - a check that objects to a comment sitting
#     beside a comparison is a check somebody deletes. This is NOT free, and an earlier draft of the
#     strip claimed it was: sed cannot tell a comment from a `//` inside a string literal, so the cut
#     costs the guard any comparison written on a line that carries one. The rule below keeps the
#     common cases - `://` in a URL, a `//` right after a quote - and what is left uncovered is a
#     `//` inside a bare-backtick raw string. A comparison hidden behind one of those is missed.
#
# WHAT IT MUST NOT DO, which took as much care as the matching
#
# For the == and != shapes it matches the token VALUE - an identifier whose last segment is exactly
# `token`, bounded at BOTH ends - and not every identifier with the word in it. `w.tokenExpiry !=
# expiry`, `w.tokenCount == 0`, `err == errTokenMissing`, `causeToken == err` and `hdr.TokenType !=
# "bearer"` are ordinary code, and earlier drafts failed all of them. Nobody had been burned only
# because none of those names existed yet; the first contributor to add one would have read the
# failure as noise, and a guard read as noise is a guard about to be weakened or deleted.
#
# The self-test covers all FOUR quadrants of that - a name with the word as a prefix and as a suffix,
# on each side of the operator. The previous version tested a suffix name on the left and a prefix
# name on the right, which left the fourth quadrant untested and a live bug in it: the boundary had
# been appended to one alternative and not the other, so `causeToken == err` exited 1 while
# `err == causeToken` passed. Two quadrants are not a matrix.
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

# The token value: an optional qualifier (w., h., ...) and then the word token as the WHOLE final
# segment, bounded at both ends. \b on BOTH sides is the whole of the false-positive fix - one side
# is not half a fix, it is a bug in the untested half. \b is supported by BSD grep, GNU grep and
# ugrep alike; only [[:<:]] is BSD-only.
tok='\b([A-Za-z0-9_]+\.)*[Tt]oken\b'

# Three shapes.
#
#   calls  - a comparison helper on a line that mentions a token. The token is NOT required to be the
#            first argument: bytes.Equal(hkdf(given), w.token[:]) is the same early-exit comparison,
#            and a version of this pattern that looked only before the first `)` let it through.
#   ops    - == or != with the token VALUE as an immediate operand. This is what keeps
#            len(token) == 32 and subtle.ConstantTimeCompare(...) != 1 - both correct code - out of
#            the results, since neither has the token up against the operator.
#   strcmp - the string(...) conversion form, on either side.
calls="(bytes\.(Equal|Compare)|slices\.(Equal|Compare)|reflect\.DeepEqual|strings\.(Compare|EqualFold))\("
ops="${tok}(\[[^]]*\])?[[:space:]]*[!=]=|[!=]=[[:space:]]*${tok}"
strcmp="string\([^)]*[Tt]oken[^)]*\)[[:space:]]*[!=]=|[!=]=[[:space:]]*string\([^)]*[Tt]oken"
pattern="(${calls})|(${ops})|(${strcmp})"

# The single scan. Defined once and called from BOTH the self-test below and the real scan, so the
# probe cannot succeed through a path the scan does not use.
#
#   1. cut comments off, keeping the line count so the numbers reported stay true,
#   2. find the shapes,
#   3. keep only lines carrying the token VALUE - $tok, the same bounded pattern the operator branch
#      uses, and NOT a case-insensitive search for the word.
#
# Step 3 is where this guard nearly died. While it grepped for the word, `bytes.Equal(a, b)` next to
# a `w.tokenCount` or a comment mentioning tokens was a failure, and a required check that objects to
# a comment is a check that gets deleted - which costs more than it ever protected.
#
# Step 1 is two expressions, not one, and the difference is a whole class of missed comparisons. A
# plain `s://.*::` truncates at the first `//` ANYWHERE, including inside a string, so
# `bytes.Equal([]byte("https://x/"+given), w.token[:])` lost everything from the URL onwards and the
# token with it - a line the guard caught before the strip existed. The first expression takes a
# comment that owns its whole line; the second takes a trailing one only when the `//` is not
# preceded by a colon, a quote, a backtick or another slash. What that still cannot see is a `//`
# inside a bare-backtick raw string.
#
# `-a`, not `-I`. Letting grep decide what is binary was a hole in every guard here: it calls any
# file holding a NUL byte binary and skips it, while a NUL is perfectly valid UTF-8 - so one NUL
# pasted into a source file laundered everything around it. Deciding what is readable is the shared
# walk's job, where the decision is explicit and counted.
scan() {
  sed -e 's|^[[:space:]]*//.*||' -e 's|\([^:"`/]\)//.*|\1|' "$1" 2>/dev/null \
    | grep -anE -e "$pattern" \
    | grep -E "$tok"
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
  # The token behind a nested call, which the previous pattern lost: it looked only as far as the
  # first `)`, and hkdf(given) swallowed it.
  '	if bytes.Equal(hkdf(given), w.token[:]) {'
  '	if bytes.Compare(w.token[:], token) != 0 {'
  '	if slices.Equal(w.token[:], token) {'
  '	if reflect.DeepEqual(w.token, token) {'
  '	if strings.Compare(string(w.token[:]), string(token)) == 0 {'
  '	if strings.EqualFold(hex.EncodeToString(w.token[:]), token) {'
  '	if w.token == candidate {'
  '	if candidate == w.token {'
  '	if w.token[0] != token[0] {'
  '	return got == w.token'
  # The two that walked past the first version of this guard, kept here so they cannot walk past a
  # later one. Both compile, both pass the whole enroll suite, both are early-exit.
  '	if string(w.token[:]) != string(token) {'
  '	if string(token) == string(w.token[:]) {'
  # Code before a comment is still code.
  '	if w.token == candidate { // still the wrong comparison'
  '	if bytes.Equal(w.token[:], token) { // simplified, was subtle'
  # And a `//` inside a string is not a comment. The first version of the comment strip truncated
  # these at the URL and lost the token, turning a catch into a silent pass.
  '	if bytes.Equal([]byte("https://x/"+given), w.token[:]) {'
  '	if fmt.Sprintf("https://%s", host) == string(w.token[:]) {'
)
must_not_match=(
  '	if subtle.ConstantTimeCompare(w.token[:], token) != 1 {'
  '	// never == or bytes.Equal on a token'
  '	if len(token) == 32 {'
  '	w.token = token'
  '	if w.attempts >= maxAttempts {'
  # The false-positive family: identifiers that merely contain the word. None of these exists in the
  # package today, which is exactly why they are here - the first one somebody writes must not be
  # met with a failure they will read as noise.
  #
  # All four quadrants: the word as a SUFFIX and as a PREFIX of a longer name, on the LEFT and on the
  # RIGHT of the operator. The fourth of these - a suffix name on the left - is where the live bug
  # was, and it was the one quadrant the previous self-test did not cover.
  '	if w.tokenExpiry != expiry {'        # prefix name, left
  '	if expiry == w.tokenExpiry {'        # prefix name, right
  '	if err == errTokenMissing {'         # prefix name inside a longer name, right
  '	if causeToken == err {'              # suffix name, left
  '	if err == causeToken {'              # suffix name, right
  '	if w.tokenCount == 0 {'
  '	if hdr.TokenType != "bearer" {'
  '	if tokenLen != 32 {'
  #
  # And the `calls` shape, which had NO negatives at all until it was widened and every one of these
  # started failing the build. A pattern that is widened or narrowed owes a case in the shape it
  # changed; negatives for a different shape do not cover it.
  '	if bytes.Equal(a, b) { // a trailing comment about the token'
  '	if bytes.Equal(a, b) && w.tokenCount == 0 {'
  '	if bytes.Equal(sig, want) && tokenLen == 32 {'
  '	if reflect.DeepEqual(a, b) && w.tokenExpiry.IsZero() {'
  '	// tokenised'
  '	tokenAge := 0'
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
# The walk lives in scripts/lib/tracked-files.sh, shared by every guard here - including this one,
# which is scoped to a single package and would otherwise have inherited the same defect the others
# had: a bare `git ls-files` cannot name a path holding a byte above ASCII, and a Go file named in
# Russian would have gone unexamined here exactly as it did there.
. "$(cd "$(dirname "$0")" && pwd)/lib/tracked-files.sh"

# The listing is limited to the package this guard is about. An empty result is an error rather than
# a pass, and the library says so: a guard over an empty set is not a pass, and if the package moved,
# this guard moves with it.
guard_pathspec=("bridge/internal/enroll/*.go")

guard_excluded() { # $1 = path
  case "$1" in
    # The package's own tests compare tokens freely - that is what a test does. The rule is about the
    # code that runs against an unauthenticated caller.
    *_test.go) return 0 ;;  esac
  return 1
}

examine() { # $1 = path, $2 = the file to read, $3 = what it is
  local matches
  matches="$(scan "$2")" || return 1
  echo "::error file=$1::$3 compares the enrolment token without crypto/subtle"
  echo "$matches" | while IFS= read -r line; do
    echo "    line ${line%%:*}"
  done
  return 0
}

guard_walk "$root" "check-constant-time-tokens.sh" examine

if [ "$guard_found" -ne 0 ]; then
  echo "::error::Compare the enrolment token with subtle.ConstantTimeCompare and nothing else."
  echo "    ==, bytes.Equal, reflect.DeepEqual and strings.Compare return on the first differing"
  echo "    byte. The caller on the other end of this comparison is unauthenticated and can retry."
fi
guard_finish "the enrolment token is compared with crypto/subtle only."
