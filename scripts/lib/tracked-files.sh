# The listing every guard walks, and the accounting every guard prints.
#
# Sourced, not executed. It owns three things that turned out to be the same thing five times over:
# WHICH files are tracked, WHAT to read for each of them, and HOW MANY the guard declined to look at.
#
# # Why this exists
#
# Five defects were found in one guard over three review rounds, and every one had the same shape: it
# declined to look at a file and said nothing, so the run printed the word that means fine.
#
#   1. `git ls-files` C-quotes any path holding a byte above ASCII, so an existence test on the
#      printed name failed and the file was skipped. A source file named in Russian - the file
#      likeliest of all to be written in Russian - reported clean.
#   2. `grep -I` calls any file holding a NUL byte binary and skips it, while a NUL is valid UTF-8.
#   3. A file in an encoding the guard could not decode was skipped rather than refused.
#   4. A symlink was followed to its target, or skipped when it dangled, when what is COMMITTED is
#      the target's name.
#   5. A blob that could not be read was counted as a file that had been read.
#
# Four of those were fixed in one script while four other guards carried number one verbatim: a
# private address and a home path inside a file named in Russian passed all four, and the identical
# bytes under an ASCII name were refused by all four. Copying the fix four times would have left the
# next guard to inherit the defect, so the mechanism moved here and the guards call it.
#
# # What a guard provides
#
#   guard_self       - its own path, excluded from its own scan (optional)
#   guard_pathspec   - an array limiting the listing, git pathspec syntax (optional)
#   guard_excluded   - a function returning 0 for a path to leave alone (optional)
#   an examine function, called as: examine <path> <readable file> <subject>
#     `path` names the file for a human, `readable file` is what to actually read - which is not
#     always the same file - and `subject` says what it is, because a symlink's blob is a NAME and
#     telling somebody there is something "in" a symlink sends them looking inside a file that has no
#     inside. It returns 0 when it found something.
#
# # What is actually read
#
# The LISTING is the index: `git ls-files` names every tracked path, with its mode and its object id.
# What is READ for each of those paths is the working-tree copy whenever one exists, and the
# committed blob only when there is no readable file to open - a deleted or sparse-checkout entry, a
# symlink (whose blob is its target's name), or a worktree symlink over a regular entry.
#
# That is deliberate, and it is the right way round for a check that runs before a commit: what
# somebody is about to add is on disk, and refusing it there is the whole point. It has a consequence
# worth naming rather than discovering - a string that is STAGED and then cleaned in the working tree
# passes, because the file that is read no longer holds it. These guards are a gate in front of a
# commit, not an audit of one.
#
# # What these guards cannot see, at all
#
# Stated here so a green run is not read as more than it is:
#
#   - HISTORY. A string removed in the working tree is still in every earlier commit, and every guard
#     here would report clean on a repository whose history is full of it.
#   - SUBMODULE CONTENTS. A gitlink records a commit id; the files live in another repository and
#     need their own run.
#   - BINARY ASSETS. Declined by extension and never inspected. A screenshot of a private
#     conversation is a binary asset.
#   - ANYTHING NOT IN THE INDEX. Untracked and gitignored files are out of scope by design, and so is
#     a stash.
#
# What a guard reads is bytes, so what it can recognise is bytes. Cyrillic written as an escape
# sequence is ASCII on disk; so is transliterated Russian; so are the owner's own words written in
# English. Those are asserted by the people writing the code, not enforced by anything here.
#
# PROPER NOUNS ARE THE SHARPEST CASE OF THAT, and it is worth stating rather than leaving implied: a
# machine name, a service name, a network name or a person's name is an ordinary word in an ordinary
# sentence. No pattern here can tell one from prose, and no pattern here ever will - a list of the
# owner's machine names, written into a guard, would commit exactly what the guard exists to keep
# out. A green run from every script in this directory says nothing whatever about them.

# Populated by guard_walk. Read by guard_accounting, and reconciled: a file is scanned, declined or
# refused, and the three must add up to the number of tracked paths or the walk itself is wrong.
guard_tracked=0
guard_read_worktree=0
guard_read_index=0
guard_read_link=0
guard_declined_binary=0
guard_declined_gitlink=0
guard_declined_excluded=0
guard_refused=0
guard_found=0

# Extensions whose files are not text and are not expected to be readable. A file with one of these
# is declined - counted and named in the summary, never in silence; anything else that cannot be
# decoded is REFUSED. The list is here, in a diff, rather than implied by a heuristic, which is the
# difference between a decision and an accident.
#
# `plist` is here because a COMPILED plist is binary; an ordinary XML one decodes fine and is scanned
# like any other text, so listing it costs nothing.
#
# `.strings` is deliberately NOT here, and the reason is the whole point of these guards. macOS
# writes localisation strings in UTF-16, so the first one committed will fail the decode check and
# somebody will reach for this list - and a localisation file is the likeliest file in any repository
# to hold Russian, or an address, or a name. Convert it to UTF-8, which every Apple tool reads. Do
# not teach a guard to look away from the one place the thing it hunts actually lives.
guard_binary_extensions="png jpg jpeg gif webp bmp tiff heic avif ico icns pdf zip jar aar apk aab \
tar tgz gz bz2 xz zst 7z dmg keystore jks p12 pfx der cer crt woff woff2 ttf otf mp3 mp4 mov wav \
webm so dylib dll a o bin wasm class ser plist"

guard_is_binary_asset() { # $1 = path
  local lower extension
  lower="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  extension="${lower##*.}"
  [ "$extension" = "$lower" ] && return 1   # no extension at all
  case " $guard_binary_extensions " in (*" $extension "*) return 0 ;; esac
  return 1
}

# Overridden by a guard that has paths of its own to leave alone. The default excludes nothing, and
# it is defined ONLY if the guard has not already defined one - a plain definition here would
# silently replace a guard's own exclusions if it happened to define them above its source line, and
# the symptom would be a guard quietly scanning its own fixtures.
if ! declare -F guard_excluded > /dev/null 2>&1; then
  guard_excluded() { return 1; }
fi

# Walks the tracked files, calling $3 for each one that can be read.
#
# Exits 2 rather than returning on anything that would make the walk incomplete: a guard reporting on
# a fraction of the tree while claiming to have checked all of it is worse than no guard.
guard_walk() { # $1 = repository root, $2 = the guard's name, $3 = examine function
  local root="$1" name="$2" examine="$3"
  local listing blob decoded entry meta path mode rest object file target subject previous extension
  local origin

  # The callback is checked BEFORE anything is walked, because the shell will not check it for you.
  # `if "$examine" ...; then` treats "command not found" as a non-zero status like any other, so one
  # misspelled function name made this guard - and every guard sourcing this file - report
  # `OK ... scanned 2` and exit 0 over a live token, with nothing but a line on stderr to say so.
  # That is the exact family this library was extracted to kill, available once and inherited six
  # times.
  if ! declare -F "$examine" > /dev/null 2>&1; then
    echo "::error::$name asked for an examine function called '$examine', which is not defined."
    echo "    Refusing to walk anything: a guard whose verdict is never asked for reports a clean"
    echo "    tree over whatever is in front of it."
    exit 2
  fi

  # Checked, and it was not. `mktemp -d` fails on a read-only or full TMPDIR, and an unchecked
  # failure left `$guard_tmp` empty, `$listing` as "/listing", and the redirect below unable to
  # open it - which surfaced as "could not list the tracked files", a cause that has nothing to do
  # with the real problem. Three defects in this library have now been a wrong stated cause rather
  # than a wrong verdict, and a message that sends somebody to look at their repository when the
  # fault is their temp directory is the same mistake in a new place.
  if ! guard_tmp="$(mktemp -d)"; then
    echo "::error::$name could not create a temporary directory (TMPDIR=${TMPDIR:-/tmp})."
    echo "    It needs one to hold the listing and the blobs it reads. This is the machine, not"
    echo "    the repository: nothing has been checked and nothing can be."
    exit 2
  fi
  # The cleanup must not be able to change the verdict, and it could. `rm -rf` fails on a temp
  # directory the guard is no longer allowed to write to - which is precisely the state the scratch
  # write below reports - and under `set -e` that aborted the trap on the spot, handing the failed
  # `rm`'s status back as the script's. Reproduced: `exit 2`, meaning THIS GUARD COULD NOT RUN, came
  # out as exit 1, meaning it found something. A verdict decided by whether the housekeeping worked
  # is not a verdict, so the status is saved first, the removal is allowed to fail, and the saved
  # status is what leaves.
  trap 'guard_status=$?; rm -rf "$guard_tmp" 2>/dev/null || :; exit "$guard_status"' EXIT
  listing="$guard_tmp/listing"
  blob="$guard_tmp/blob"
  # Where the decode check below throws its output. Truncated on every file, so it holds one file at
  # a time; why it is a file at all is the comment on that check.
  decoded="$guard_tmp/decoded"

  # `-s`, so every entry arrives with its MODE and its object id. The mode comes from the index
  # rather than from the filesystem, which matters twice: a symlink is a symlink even when it
  # dangles, and a submodule is a gitlink with nothing to open. Deciding what a thing is by whether
  # `[ -f ]` happens to succeed on it is how files went unexamined.
  #
  # `-z`, so a path is raw bytes and a NUL terminator rather than a C-quoted rendering of itself.
  #
  # The listing goes to a file rather than a variable: a shell variable cannot hold a NUL byte, so
  # capturing `-z` output in one silently mangles every path it was meant to protect.
  if ! git -C "$root" ls-files -s -z ${guard_pathspec+"${guard_pathspec[@]}"} > "$listing"; then
    echo "::error::$name could not list the tracked files in '$root'."
    echo "    Refusing to report a clean tree on the strength of an empty listing."
    exit 2
  fi
  if [ ! -s "$listing" ]; then
    echo "::error::$name found no tracked files under '$root'."
    echo "    A guard over an empty set is not a pass."
    exit 2
  fi

  previous=""
  while IFS= read -r -d '' entry; do
    meta="${entry%%$'\t'*}"
    path="${entry#*$'\t'}"
    mode="${meta%% *}"
    rest="${meta#* }"
    object="${rest%% *}"

    # During a conflicted merge `-s` lists one path three times, once per stage. The index is ordered
    # by path so the stages are adjacent, and the file on disk - the one with the conflict markers in
    # it - is the same file for all three. Counting it three times would make the totals below
    # irreconcilable for a reason that has nothing to do with what was scanned.
    [ "$path" = "$previous" ] && continue
    previous="$path"
    guard_tracked=$((guard_tracked + 1))

    if [ -n "${guard_self:-}" ] && [ "$path" = "$guard_self" ]; then
      guard_declined_excluded=$((guard_declined_excluded + 1))
      continue
    fi
    if guard_excluded "$path"; then
      guard_declined_excluded=$((guard_declined_excluded + 1))
      continue
    fi

    file="$root/$path"
    case "$mode" in
      160000)
        # A submodule. There is no blob and no file - the gitlink records a commit id in another
        # repository, which has its own tree and needs its own run of this guard.
        guard_declined_gitlink=$((guard_declined_gitlink + 1))
        continue
        ;;
      120000)
        # A SYMLINK, and what is committed is the target's NAME, not whatever it points at.
        #
        # Both directions were wrong before this. A link whose target is named in Russian put those
        # bytes straight into the blob, and on a fresh clone where the target is absent the link
        # dangled and the file was skipped in silence. The converse was a false positive: a link
        # pointing outside the repository was scanned as though the far end's contents were the
        # committed data, when the committed data is one line of path.
        #
        # Reading the blob answers both, and never follows the link.
        if ! git -C "$root" cat-file blob "$object" > "$blob" 2>/dev/null; then
          guard_unreadable_blob "$path" "this symlink's target"
          continue
        fi
        # A symlink's target is a path, so it ends without a newline; a line-oriented scan wants one.
        printf '\n' >> "$blob"
        target="$blob"
        subject="the name this symlink points at"
        origin=link
        ;;
      *)
        if [ -h "$file" ]; then
          # The index says regular and the working tree holds a symlink. Following it would read a
          # file nobody committed, so the committed blob is what gets scanned.
          #
          # This read is checked like the other two. It was `|| true` once - and an unreadable object
          # then produced an empty file, scanned as nothing and counted as a read. A decline reported
          # as a read is worse than a silent skip, because the accounting says it was checked.
          if ! git -C "$root" cat-file blob "$object" > "$blob" 2>/dev/null; then
            guard_unreadable_blob "$path" "this entry's committed content"
            continue
          fi
          target="$blob"
          subject="the committed content"
          origin=index
        elif [ -f "$file" ]; then
          if [ ! -r "$file" ]; then
            # Not "invalid encoding" - that was the wrong cause and the wrong sentence. The file may
            # be perfectly good text; this process cannot open it.
            echo "::error file=$path::this file could not be opened for reading (check its permissions)"
            echo "    A file this guard cannot open is not a file it has checked, so the run does"
            echo "    not go green on it. Restore read permission, or untrack it."
            guard_refused=$((guard_refused + 1))
            continue
          fi
          target="$file"
          subject="this file"
          origin=worktree
        else
          # Tracked, and not in the working tree: staged and then deleted, or a sparse checkout. What
          # would be pushed is the blob, so the blob is what is scanned.
          if ! git -C "$root" cat-file blob "$object" > "$blob" 2>/dev/null; then
            guard_unreadable_blob "$path" "this entry's committed content"
            continue
          fi
          target="$blob"
          subject="the committed content"
          origin=index
        fi
        ;;
    esac

    # A file that cannot be decoded as UTF-8 is one of two things, and they have opposite answers.
    #
    # An icon is binary: its bytes are not characters, and reporting a finding inside a PNG is how a
    # guard gets deleted. It is declined - counted and named, never in silence - and the extension
    # that said so is on a list somebody wrote down.
    #
    # Anything else is text in an encoding this scan cannot read, and text in UTF-16 or CP1251 walks
    # straight through a byte pattern written for UTF-8. So it is refused, with BOTH ways out named:
    # convert it if it is text, list it if it is not. A refusal that offers one answer sends whoever
    # hits it towards the other one by guessing.
    #
    # The decoded bytes are thrown at a scratch FILE, and that is not a style choice. This was
    # `>/dev/null`, and on macOS that makes iconv fail on VALID UTF-8 once the conversion fills its
    # 1024-byte output buffer, because it then does an ioctl on its own stdout and /dev/null answers
    # ENOTTY:
    #
    #     $ printf '\342\200\224%.0s' $(seq 342) > m.txt        # 1026 bytes, all valid
    #     $ iconv -f UTF-8 -t UTF-8 < m.txt >/dev/null
    #     iconv: iconv(): Inappropriate ioctl for device        # exit 1
    #     $ iconv -f UTF-8 -t UTF-8 < m.txt > some-file         # exit 0
    #
    # One em dash less passes, and so does a megabyte of ASCII, because nothing there is converted.
    # It is neither a size limit nor a content problem - it is the DESTINATION, and which files it
    # hits turns on where a multi-byte character happens to fall across that buffer, so it arrives
    # looking like a file being singled out for nothing. The guard then tells somebody their
    # perfectly good file is not UTF-8 and instructs them to convert it from an encoding it was
    # never in. Following that advice corrupts the file.
    #
    # Every tracked file today happens to be English prose with occasional punctuation above ASCII,
    # so the whole tree squeaks past. That is luck, not correctness, and it runs out the moment a
    # localisation string, a CJK test fixture or a table of box-drawing characters lands.
    #
    # A regular file is what iconv is for, on BSD and GNU alike, and it costs one truncating write
    # per file, of bytes the walk is about to read anyway. The alternative - a pipe, reading iconv's
    # own status out of PIPESTATUS - also works, and was not taken: it is correct only while
    # `pipefail` is set or the index is read explicitly, and the failure when it is not is that
    # every file decodes and nothing is ever refused. A fail-open one edit away is not worth a
    # saved write.
    #
    # Which buys one new way to be wrong, and it is answered below rather than left to be
    # discovered: the write can fail for reasons that have nothing to do with the file. A full or
    # read-only temp filesystem makes iconv exit non-zero exactly as an undecodable file does, and
    # reporting THAT as "this file is not valid UTF-8" would send somebody to convert a file whose
    # encoding was never the problem. That is the mistake the permission check was split out of this
    # branch to avoid, and this library has now made it three times. So a failure here asks which of
    # the two it was, by trying to add one byte to the scratch file it just tried to write.
    if ! iconv -f UTF-8 -t UTF-8 < "$target" > "$decoded" 2>/dev/null; then
      if ! printf 'x' >> "$decoded" 2>/dev/null; then
        echo "::error::$name could not write its scratch copy of '$path' into $guard_tmp."
        echo "    It decodes each file to a scratch copy to check that the file is UTF-8, and that"
        echo "    write failed - a full or read-only temp filesystem, not anything about the file."
        echo "    Its encoding has NOT been judged and neither has anything after it: free some"
        echo "    space, or point TMPDIR somewhere writable, and run this again."
        exit 2
      fi
      if guard_is_binary_asset "$path"; then
        guard_declined_binary=$((guard_declined_binary + 1))
        continue
      fi
      extension="${path##*.}"
      echo "::error file=$path::this file is not valid UTF-8, so $name cannot read it"
      echo "    Text in another encoding passes a byte scan written for UTF-8 untouched, so a file"
      echo "    that cannot be decoded is never reported as clean. One of these two, and they are"
      echo "    not interchangeable:"
      echo "      - it is TEXT in another encoding - a macOS .strings file is UTF-16 - so convert it:"
      echo "            iconv -f UTF-16 -t UTF-8 '$path' > converted && mv converted '$path'"
      echo "        A localisation file is the likeliest file in any repository to hold the very"
      echo "        thing these guards look for. It belongs in the scan, not on the list below."
      if [ "$extension" = "$path" ]; then
        echo "      - it is a BINARY asset. The list in scripts/lib/tracked-files.sh names"
        echo "        extensions, and this path has none, so it cannot be named there: give the file"
        echo "        the extension its format already has, and add that."
      else
        echo "      - it is a BINARY asset, in which case add its extension '$extension' to"
        echo "        guard_binary_extensions in scripts/lib/tracked-files.sh, where the decision is"
        echo "        visible in a diff."
      fi
      guard_refused=$((guard_refused + 1))
      continue
    fi

    # Counted here and nowhere else, AFTER every branch that could still decline or refuse it. The
    # first version counted a file as read the moment its source was chosen and then counted it again
    # when the decode failed, and the reconciliation below caught that on its first run - which is
    # what the reconciliation is for.
    case "$origin" in
      worktree) guard_read_worktree=$((guard_read_worktree + 1)) ;;
      index) guard_read_index=$((guard_read_index + 1)) ;;
      link) guard_read_link=$((guard_read_link + 1)) ;;
    esac

    # 0 means it found something, 1 means it did not, and ANYTHING ELSE is this guard failing rather
    # than passing. `if cmd; then` reads 127 as "no finding", which is how a misspelled function name
    # produced OK over a live token.
    if "$examine" "$path" "$target" "$subject"; then
      guard_found=1
    else
      local status=$?
      if [ "$status" -gt 1 ]; then
        echo "::error::$name: its examine function exited $status on '$path'."
        echo "    A verdict is 0 (found something) or 1 (did not). Anything else is the guard"
        echo "    failing, and a guard that cannot run must never look like one that found nothing."
        exit 2
      fi
    fi
  done < "$listing"

  # Zero files scanned is not the same as nothing found. A guard whose whole subject has been
  # renamed, moved or excluded away is being asked a question about nothing, and the honest answer is
  # not OK. `guard_nothing_scanned` lets a guard say what that means for it.
  if [ $((guard_read_worktree + guard_read_index + guard_read_link)) -eq 0 ]; then
    echo "::error::$name scanned no files at all."
    if [ -n "${guard_nothing_scanned:-}" ]; then
      echo "    $guard_nothing_scanned"
    else
      echo "    Everything tracked was declined or refused, so this run looked at nothing."
    fi
    echo "    A guard over an empty set is not a pass."
    guard_accounting
    exit 2
  fi

  # The reconciliation, checked rather than left for a reader to do. Every tracked path is scanned,
  # declined or refused; if the three do not add up, the walk dropped something on a path nobody
  # wrote a counter for - which is the fifth defect of this family arriving, and it arrives as an
  # error instead of as a clean run.
  local accounted=$((guard_read_worktree + guard_read_index + guard_read_link \
    + guard_declined_binary + guard_declined_gitlink + guard_declined_excluded + guard_refused))
  if [ "$accounted" -ne "$guard_tracked" ]; then
    echo "::error::$name walked $guard_tracked tracked paths and accounted for $accounted."
    echo "    Some path was neither scanned nor declined nor refused, so this run checked less than"
    echo "    it says it did. That is the shape of every defect these guards have had."
    exit 2
  fi
}

# The summary. Printed on every run, pass or fail.
#
# This is the fix for the FAMILY the five defects belonged to, rather than for its members. A guard
# that can decline to look at something has to say how many things it declined to look at and why, or
# its silence about them is indistinguishable from there being none. Nobody predicted any of the
# five, and nobody could have, because a silent skip has no symptom. A number somebody did not expect
# on a run that is still green is one.
guard_accounting() {
  local scanned declined
  scanned=$((guard_read_worktree + guard_read_index + guard_read_link))
  declined=$((guard_declined_binary + guard_declined_gitlink + guard_declined_excluded))
  echo "    tracked $guard_tracked: scanned $scanned, declined $declined, refused $guard_refused"
  [ "$guard_read_worktree" -ne 0 ] && echo "      scanned $guard_read_worktree from the working tree"
  [ "$guard_read_index" -ne 0 ] && echo "      scanned $guard_read_index from the index rather than the working tree"
  [ "$guard_read_link" -ne 0 ] && echo "      scanned $guard_read_link symlink target name(s) rather than what they point at"
  [ "$guard_declined_excluded" -ne 0 ] && echo "      declined $guard_declined_excluded by this guard's own exclusions"
  [ "$guard_declined_binary" -ne 0 ] && echo "      declined $guard_declined_binary binary asset(s) by extension"
  [ "$guard_declined_gitlink" -ne 0 ] && echo "      declined $guard_declined_gitlink submodule gitlink(s), scanned in their own repository"
  return 0
}

guard_unreadable_blob() { # $1 = path, $2 = what could not be read
  echo "::error file=$1::$2 could not be read out of the object store"
  echo "    A partial or promisor clone can be missing an object it lists. An entry this guard"
  echo "    could not read is not an entry it has checked, so it is refused rather than counted"
  echo "    as read - the accounting must never claim more than was looked at."
  guard_refused=$((guard_refused + 1))
}

# Prints the verdict and the accounting, and yields the exit status the guard should exit with.
guard_finish() { # $1 = the sentence for a clean run
  if [ "$guard_found" -ne 0 ] || [ "$guard_refused" -ne 0 ]; then
    guard_accounting
    return 1
  fi
  echo "OK: $1"
  guard_accounting
  return 0
}
