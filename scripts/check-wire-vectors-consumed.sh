#!/usr/bin/env bash
#
# Fails when the Android tests stop reading the shared wire vectors, or start reading a copy.
#
# `wire/enroll-payload-vectors.json` and `wire/enroll-payload-reject-vectors.json` are the contract
# between two hand-written implementations of one format: the Go encoder in bridge/internal/enroll
# and the Kotlin decoder that reads the QR code. They cannot be compiled against each other and
# nothing else in either build connects them. The bytes in those files are the connection.
#
# WHICH GIVES TWO WAYS TO LOSE IT, and neither one fails a build:
#
#   1. The Kotlin side stops reading them. A decoder that pins nothing passes every test it has while
#      disagreeing with the encoder about anything the tests do not happen to cover - and the symptom
#      is a phone that will not pair, found by a person holding it. The predecessor project had
#      exactly this shape and exactly this failure.
#   2. Somebody copies the files into the app module. This is the worse one, because it looks like it
#      works: the copy is what the Kotlin test pins, the original is what the Go test pins, both
#      suites are green, and they are pinning different bytes. wire/README.md says "never `cp` them
#      into src/" for this reason; that sentence is a rule, and this is the part that enforces it.
#
# WHAT THIS CANNOT SEE, so that a green run is not read as more than it is:
#
#   - Whether the reference is REACHED. A filename in a comment satisfies this guard. What it buys is
#     that removing the reference is a deliberate act with a red check attached, not an omission.
#   - Whether the decoder is CORRECT, or whether it consumes every vector. That is the test suite's
#     job, and it is the reason this guard is deliberately shallow.
#   - A copy outside the app module, or one that is not tracked.
#   - A copy that has been REFORMATTED - reindented, key-reordered, a trailing newline added. cmp is
#     byte equality, and anything short of that is a parser, which is a different program. What it
#     does catch is the shape that actually happens, which is a copy.
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

# The single grep, used by the self-test below and by the walk after it, so the probe cannot succeed
# through a path the real scan does not use.
#
# -F: these are filenames, and `.` in a pattern would match any byte. A guard that accepted
# `enroll-payload-vectorsXjson` is not wrong in a way anybody would ever notice, which is worse.
mentions() { # $1 = the file to read, $2 = the name to look for
  LC_ALL=C grep -qF -e "$2" "$1" 2>/dev/null
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
rm -f "$probe" "$copy"
trap - EXIT

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

# Only the app module. The Go side's use of these files is asserted by the bridge job re-deriving
# them, and a pathspec that reached the whole tree would let this guard's own text, or wire/README.md,
# satisfy the requirement that the ANDROID TESTS mention them.
guard_pathspec=("app/")

# If this is ever empty the answer is not OK. The app module arriving, moving or being renamed is
# exactly when this guard has to say something.
guard_nothing_scanned="The app module is gone from the listing. If it moved, move this guard with it."

# The two facts the walk collects, and the one it collects them for.
seen_accept=0
seen_reject=0

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

  # The reference. Only from a test source: a mention in main/ would be the app shipping a path into
  # a repository that is not on the phone.
  case "$1" in
    app/src/test/*|app/src/androidTest/*)
      mentions "$2" "$accept" && seen_accept=1
      mentions "$2" "$reject" && seen_reject=1
      ;;
  esac
  return 1
}

guard_walk "$root" "check-wire-vectors-consumed.sh" examine

# The walk runs `examine` in this shell, so the two flags above survive it. Asserted rather than
# assumed, because a pipeline anywhere in the walk would put it in a subshell and both flags would
# read 0 forever - a guard that always fails is noticed, but one that always fails for the wrong
# reason gets its assertion deleted rather than its cause found.
for pair in "accept:$seen_accept:$accept" "reject:$seen_reject:$reject"; do
  half="${pair%%:*}"; rest="${pair#*:}"; seen="${rest%%:*}"; name="${rest#*:}"
  if [ "$seen" -eq 0 ]; then
    echo "::error::No Android test source names wire/$name."
    echo "    The $half half of the wire contract is not pinned on the Kotlin side, so the decoder"
    echo "    and the Go encoder can disagree with nothing to say so. The symptom of that is a phone"
    echo "    that will not pair, found by a person holding it, and it is what wire/ exists to"
    echo "    prevent. Read it from the repository root - see wire/README.md - never from a copy."
    guard_found=$((guard_found + 1))
  fi
done

guard_finish "the Android tests read both shared wire vector files, and hold no copy of either."
