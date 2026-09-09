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
# # What these guards cannot see, at all
#
# Stated here so a green run is not read as more than it is. The listing reaches tracked paths in the
# current index and nothing else:
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

# Overridden by a guard that has paths of its own to leave alone. The default excludes nothing.
guard_excluded() { return 1; }

# Walks the tracked files, calling $3 for each one that can be read.
#
# Exits 2 rather than returning on anything that would make the walk incomplete: a guard reporting on
# a fraction of the tree while claiming to have checked all of it is worse than no guard.
guard_walk() { # $1 = repository root, $2 = the guard's name, $3 = examine function
  local root="$1" name="$2" examine="$3"
  local listing blob entry meta path mode rest object file target subject previous extension origin

  guard_tmp="$(mktemp -d)"
  trap 'rm -rf "$guard_tmp"' EXIT
  listing="$guard_tmp/listing"
  blob="$guard_tmp/blob"

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
    if ! iconv -f UTF-8 -t UTF-8 < "$target" >/dev/null 2>&1; then
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

    if "$examine" "$path" "$target" "$subject"; then
      guard_found=1
    fi
  done < "$listing"

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
