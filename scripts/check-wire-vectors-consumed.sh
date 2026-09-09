#!/usr/bin/env bash
#
# Fails when a hand-written reader of the wire format stops reading the shared vectors, or reads a copy.
#
# `wire/enroll-payload-vectors.json` and `wire/enroll-payload-reject-vectors.json` are the contract
# between hand-written implementations of one format that cannot be compiled against each other, and
# nothing else in any build connects them. The bytes in those files are the connection. There are
# THREE readers now:
#
#   - the Go encoder and decoder in bridge/internal/enroll, held by the bridge job re-deriving them;
#   - the Kotlin decoder that reads the QR code, which faces a camera and is held to BOTH files;
#   - the Swift reader in mac/Tests, which decodes codes the bridge has just minted, and is held to
#     the ACCEPT file. Not the reject file: those are the contract for a decoder parsing bytes a
#     stranger holds up, and that reader talks to a control socket on the same machine.
#
# The third one is why this guard now covers `mac/`. It was not held to anything, and it drifted the
# moment the payload reached version 2 - it went on reading version 1's offsets, every suite stayed
# green, and a person noticed afterwards. That is precisely the failure this file exists to make
# impossible, and it happened in a directory this file was not looking at.
#
# WHICH GIVES TWO WAYS TO LOSE IT, and neither one fails a build:
#
#   1. A reader stops reading them. One that pins nothing passes every test it has while disagreeing
#      with the encoder about anything the tests do not happen to cover - and the symptom is a phone
#      that will not pair, found by a person holding it. The predecessor project had exactly this
#      shape and exactly this failure.
#   2. Somebody copies the files into a module. This is the worse one, because it looks like it
#      works: the copy is what one suite pins, the original is what another pins, both are green, and
#      they are pinning different bytes. wire/README.md says "never `cp` them into src/" for this
#      reason; that sentence is a rule, and this is the part that enforces it.
#
# WHAT THIS CANNOT SEE, so that a green run is not read as more than it is:
#
#   - Whether the reference is REACHED. A test that names the file and never opens it satisfies this
#     guard. What it buys is that removing the reference is a deliberate act with a red check on it.
#     What it no longer accepts is a mention in a COMMENT - see `mentions` below, and the defect that
#     made that necessary.
#   - Whether the decoder is CORRECT, or whether it consumes every vector. That is the test suite's
#     job, and it is the reason this guard is deliberately shallow.
#   - A copy outside the app module, or one that is not tracked.
#   - A copy whose CONTENT has been edited - a key reordered, a value changed, a case dropped. Two
#     comparisons are made, byte equality and equality ignoring whitespace, so a `cp` and a
#     reindented `cp` are both caught; anything past that is a parser, which is a different program.
#
# The listing, the mode dispatch, the decode check and the accounting all live in
# scripts/lib/tracked-files.sh, shared by every guard here.
#
# Usage: scripts/check-wire-vectors-consumed.sh [path]     default: the whole working tree
set -euo pipefail

if ! root="$(git -C "${1:-.}" rev-parse --show-toplevel 2>/dev/null)"; then
  echo "::error::check-wire-vectors-consumed.sh found no git repository at '${1:-.}'."
  echo "    A guard that cannot list the tracked files must not report a clean tree."
  exit 2
fi

# The two files, named once. Written as variables rather than repeated, because this script also
# greps for these strings and a guard whose two spellings can drift is the defect it is about.
accept="enroll-payload-vectors.json"
reject="enroll-payload-reject-vectors.json"

# The single matcher, used by the self-test below and by the walk after it, so the probe cannot
# succeed through a path the real scan does not use.
#
# -F: these are filenames, and `.` in a pattern would match any byte. A guard that accepted
# `enroll-payload-vectorsXjson` is not wrong in a way anybody would ever notice, which is worse.
#
# **COMMENTS ARE STRIPPED FIRST, and that is the fix for a hole this guard shipped with.** It required
# a mac/Tests source to name the accept vectors; `BridgeIntegrationTests.swift` names them in a doc
# comment explaining that ANOTHER file reads them. So deleting `WireVectorsTests.swift` outright - the
# only Swift code that opens the file - left this guard green, which is precisely the state it exists
# to make impossible. A prose reference to a contract is not a reader of it.
#
# What survives the strip is a line with code on it: `resolve("...")`, `appending(path: "...")`,
# `os.ReadFile("...")`. What does not is `//`, `///`, a `*` continuation line of a block comment, and
# a `#` shell comment. It is a heuristic about three languages' comment syntax rather than a parser,
# and the direction of its error is the safe one: a mention inside a string that also contains `//`
# would be missed, and a missed mention fails the build rather than passing it.
mentions() { # $1 = the file to read, $2 = the name to look for
  LC_ALL=C sed -e 's,//.*,,' -e 's,^[[:space:]]*\*.*,,' -e 's,^[[:space:]]*#.*,,' "$1" 2>/dev/null \
    | LC_ALL=C grep -qF -e "$2"
}

# Content equality ignoring whitespace, for a copy that was reindented on the way in.
#
# cmp catches a `cp`. It does not catch `python -m json.tool < wire/... > src/...`, which is the same
# bytes with different spacing and is just as much a second copy of the contract. Comparing the files
# with every space, tab and newline removed catches both, and it is still equality rather than
# parsing: a file whose CONTENT differs anywhere is not a copy by this test either.
squashed() { # $1 = the file to read
  LC_ALL=C tr -d '[:space:]' < "$1" 2>/dev/null | cksum
}

# --- The self-test ---------------------------------------------------------------------------
#
# It proves it can still detect, AND that it still tolerates a neighbour, before it is allowed to
# report OK. Both halves: a guard that cannot detect reports a clean tree that means nothing, and a
# guard that rejects what belongs here gets switched off, after which it protects nothing.
#
# The tolerating case is the sharp one here. `enroll-payload-vectors.json` is a SUBSTRING of
# `enroll-payload-reject-vectors.json`... no, it is not - and that is exactly the kind of claim that
# has to be checked rather than believed, so it is checked: a file naming only the reject vectors
# must not satisfy the accept requirement.
probe="$(mktemp)"
trap 'rm -f "$probe"' EXIT

printf 'val vectors = wireDir.resolve("%s")\n' "$accept" > "$probe"
mentions "$probe" "$accept" || {
  echo "::error::check-wire-vectors-consumed.sh is broken: it cannot match a known-positive."
  exit 2
}
printf 'val vectors = wireDir.resolve("%s")\n' "$reject" > "$probe"
mentions "$probe" "$accept" && {
  echo "::error::check-wire-vectors-consumed.sh is too broad: the reject filename satisfied the"
  echo "    accept requirement, so a suite reading only half the contract would pass."
  exit 2
}
printf 'nothing to do with the wire format at all\n' > "$probe"
mentions "$probe" "$reject" && {
  echo "::error::check-wire-vectors-consumed.sh is too broad: an unrelated file matched."
  exit 2
}

# **The comment case, in all three shapes a comment takes in this repository.**
#
# This is the half that was missing, and its absence is what let a doc comment stand in for a reader.
# Each of these is a file that TALKS ABOUT the vectors and never opens them; none may satisfy the
# requirement.
for comment in \
  "/// The other suite reads %s and this one does not." \
  " * The other suite reads %s and this one does not." \
  "# The other suite reads %s and this one does not."
do
  printf "$comment\n" "$accept" > "$probe"
  mentions "$probe" "$accept" && {
    echo "::error::check-wire-vectors-consumed.sh is too broad: a COMMENT naming wire/$accept"
    echo "    satisfied the requirement that a test reads it. Deleting the only reader would then"
    echo "    leave this guard green, which is the hole it exists to close."
    exit 2
  }
done

# And a code line with a trailing comment on it still counts, because it is still a reader.
printf 'let file = root.appending(path: "wire/%s")  // read from the root, never a copy\n' "$accept" > "$probe"
mentions "$probe" "$accept" || {
  echo "::error::check-wire-vectors-consumed.sh is too narrow: a code line carrying a trailing"
  echo "    comment stopped counting as a reference, so an ordinary reader would fail this guard."
  exit 2
}

# **The renamed copy, which is the case this guard shipped unable to catch.**
#
# The content check is what stands between a `cp` and two implementations pinning different bytes,
# and for one review round it was gated on a `.json` extension - so a copy saved as `.txt` walked
# past it. Both directions are probed here, on the REAL vector files rather than on a fixture,
# because a fixture would only prove that cmp compares two things this script made up.
copy="$(mktemp)"
trap 'rm -f "$probe" "$copy"' EXIT
cat "$root/wire/$accept" > "$copy"
cmp -s "$copy" "$root/wire/$accept" || {
  echo "::error::check-wire-vectors-consumed.sh is broken: cmp did not match an exact copy of"
  echo "    wire/$accept, so the content check cannot detect a renamed copy at all."
  exit 2
}
printf 'x\n' >> "$copy"
cmp -s "$copy" "$root/wire/$accept" && {
  echo "::error::check-wire-vectors-consumed.sh is too broad: cmp matched a file that differs from"
  echo "    wire/$accept, so every JSON file in the app module would be called a copy."
  exit 2
}

# **The REINDENTED copy, which cmp cannot see at all.**
#
# `python3 -m json.tool` produces the same contract with different spacing, and until this check
# existed that walked straight past: cmp is byte equality, and the reformatted file is not byte-equal
# to anything. Both halves are probed, on the real vector file rather than on a fixture - a fixture
# would only prove that two things this script made up compare equal.
# Indented by two spaces and given a trailing blank line: a pure whitespace change, which is the
# shape a reformat takes. (`python3 -m json.tool` was tried first and is the wrong probe - it also
# escapes non-ASCII, so it changes the CONTENT of the vector whose host is `münchen.example.test`.)
{ sed 's/^/  /' "$root/wire/$accept"; echo; } > "$copy"
if [ "$(squashed "$copy")" != "$(squashed "$root/wire/$accept")" ]; then
  echo "::error::check-wire-vectors-consumed.sh is broken: a reindented copy of wire/$accept did not"
  echo "    compare equal ignoring whitespace, so the reformatted-copy check cannot detect anything."
  exit 2
fi
printf '{"not":"the contract"}\n' > "$copy"
if [ "$(squashed "$copy")" = "$(squashed "$root/wire/$accept")" ]; then
  echo "::error::check-wire-vectors-consumed.sh is too broad: an unrelated file compared equal to"
  echo "    wire/$accept ignoring whitespace, so every file in the module would be called a copy."
  exit 2
fi
rm -f "$probe" "$copy"
trap - EXIT

# The two vector files, squashed once, so the walk does not re-read them for every tracked file.
accept_squashed="$(squashed "$root/wire/$accept")"
reject_squashed="$(squashed "$root/wire/$reject")"

# --- The originals have to be there ------------------------------------------------------------
#
# Checked before anything else. A reference to a file that does not exist is not a contract being
# honoured, and the two failures would otherwise arrive in the wrong order: "the tests read the
# vectors" is a clean run even when there are no vectors to read.
missing=0
for name in "$accept" "$reject"; do
  if [ ! -f "$root/wire/$name" ]; then
    echo "::error::wire/$name does not exist."
    echo "    The Android tests are asserted below to read it. If the format moved, move this guard"
    echo "    and wire/README.md with it; if it was deleted, so was the contract."
    missing=1
  fi
done
[ "$missing" -eq 0 ] || exit 1

. "$(cd "$(dirname "$0")" && pwd)/lib/tracked-files.sh"

# The two modules that hold hand-written readers. The Go side's use of these files is asserted by the
# bridge job re-deriving them, and a pathspec that reached the whole tree would let this guard's own
# text, or wire/README.md, satisfy the requirement that a TEST mentions them.
guard_pathspec=("app/" "mac/")

# If this is ever empty the answer is not OK. A module arriving, moving or being renamed is exactly
# when this guard has to say something.
guard_nothing_scanned="The app and mac modules are gone from the listing. If they moved, move this guard with them."

# The three facts the walk collects, and the one it collects them for.
#
# Per module, not per file, because the requirement is per reader: the Kotlin side owes both halves
# of the contract, and the Swift side owes the accept half. A single pair of flags would let the
# Android tests satisfy the requirement on behalf of a Swift reader that reads nothing - which is the
# exact state this guard was extended to catch.
seen_accept=0
seen_reject=0
seen_accept_mac=0

examine() { # $1 = path, $2 = the file to read, $3 = what it is
  # A copy of either vector file, anywhere under the app module. Checked by NAME first, because that
  # is the shape a `cp` takes and it is readable in the failure message.
  case "${1##*/}" in
    "$accept"|"$reject")
      echo "::error file=$1::$3 is a copy of a shared wire vector file"
      echo "    Read wire/$accept and wire/$reject from the repository root instead - see"
      echo "    wire/README.md. A copy is what the Kotlin tests would pin while the Go tests pin the"
      echo "    original, and nothing would notice they had diverged."
      return 0
      ;;
  esac

  # And by CONTENT, for a copy that was renamed on the way in.
  #
  # **EVERY file, not just `*.json`.** This check was gated on the extension for one review round, and
  # that gate was the whole hole: `cp wire/enroll-payload-vectors.json app/src/test/resources/
  # vectors.txt` printed OK. The three breaks it was proved against all happened to keep a `.json`
  # name, so the tolerating half was tested and the detecting half was not - which is where every
  # defect these guards have had has lived. A copy is a copy under any extension, and the extension is
  # chosen by whoever makes the copy.
  #
  # cmp rather than a checksum: two comparisons per file, and cmp says nothing on a mismatch that has
  # to be parsed. It exits non-zero on a size difference before reading a byte, so this costs nothing
  # on the 190 files that are not a copy.
  for name in "$accept" "$reject"; do
    if cmp -s "$2" "$root/wire/$name"; then
      echo "::error file=$1::$3 is byte-identical to wire/$name under another name"
      echo "    Renaming a copy does not make it not a copy, and neither does renaming the extension."
      echo "    Read the original from the repository root - see wire/README.md."
      return 0
    fi
  done

  # And the same content with different spacing, which cmp cannot see. A pretty-printed copy is
  # still a second set of bytes that one suite pins while another pins the original.
  case "$(squashed "$2")" in
    "$accept_squashed")
      echo "::error file=$1::$3 is wire/$accept reformatted"
      echo "    Reindenting a copy does not make it not a copy. Read the original from the"
      echo "    repository root - see wire/README.md."
      return 0
      ;;
    "$reject_squashed")
      echo "::error file=$1::$3 is wire/$reject reformatted"
      echo "    Reindenting a copy does not make it not a copy. Read the original from the"
      echo "    repository root - see wire/README.md."
      return 0
      ;;
  esac

  # The reference. Only from a test source: a mention in main/ or Sources/ would be a shipped
  # application carrying a path into a repository that is not on the machine running it.
  case "$1" in
    app/src/test/*|app/src/androidTest/*)
      mentions "$2" "$accept" && seen_accept=1
      mentions "$2" "$reject" && seen_reject=1
      ;;
    mac/Tests/*)
      mentions "$2" "$accept" && seen_accept_mac=1
      ;;
  esac
  return 1
}

guard_walk "$root" "check-wire-vectors-consumed.sh" examine

# The walk runs `examine` in this shell, so the two flags above survive it. Asserted rather than
# assumed, because a pipeline anywhere in the walk would put it in a subshell and both flags would
# read 0 forever - a guard that always fails is noticed, but one that always fails for the wrong
# reason gets its assertion deleted rather than its cause found.
for row in \
  "accept:$seen_accept:$accept:Android test source:Kotlin decoder" \
  "reject:$seen_reject:$reject:Android test source:Kotlin decoder" \
  "accept:$seen_accept_mac:$accept:mac/Tests source:Swift reader"; do
  IFS=: read -r half seen name where who <<EOF
$row
EOF
  if [ "$seen" -eq 0 ]; then
    echo "::error::No $where names wire/$name."
    echo "    The $half half of the wire contract is not pinned by the $who, so it and the Go"
    echo "    encoder can disagree with nothing to say so. The symptom of that is a phone that will"
    echo "    not pair, found by a person holding it, and it is what wire/ exists to prevent. Read it"
    echo "    from the repository root - see wire/README.md - never from a copy."
    guard_found=$((guard_found + 1))
  fi
done

guard_finish "every hand-written reader names the shared wire vectors, and none holds a copy."
