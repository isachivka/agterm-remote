#!/usr/bin/env bash
#
# Fails when a pull request title is not a conventional-commit subject.
#
# WHY THE TITLE AND NOT THE COMMITS. This repository merges by squash only. The commits on a branch
# never reach `main`; GitHub synthesises one commit whose subject is the PULL REQUEST TITLE (pinned
# with `squash_merge_commit_title=PR_TITLE`, so it is the title even when the branch has exactly one
# commit). release-please then parses that subject to pick the next version and to write the release
# notes. A conventional-commit check that inspected only branch commits would be validating text
# that is thrown away at merge time, while the one string that survives went unchecked.
#
# Usage: scripts/check-pr-title.sh "<title>" [file-of-changed-paths]
#
# With a second argument, the SAME docs-only rule that `check-commit-types.sh` applies to each commit
# is applied to the title. It has to be here as well, and the reason is mechanical: after a squash
# the title IS the subject on `main`, so a pull request titled `feat:` whose diff touches only
# documentation announces a feature that does not exist - and the per-commit check cannot see it,
# because the branch's own commits may be typed `docs:` and be perfectly correct. Without this, such
# a pull request goes green at review time and turns `main` red on the push run right after the
# merge, which is the worst possible moment to learn about it.
set -euo pipefail

title="${1-}"
paths_file="${2-}"

# feat, feat(scope), feat!, feat(scope)!  then ": " then a non-empty description.
# The type list is CONTRIBUTING.md's, and the two must stay in step.
types='feat|fix|docs|chore|refactor|test|ci|build'
if printf '%s' "$title" | grep -Eq "^($types)(\([a-zA-Z0-9._/-]+\))?!?: .+"; then
  echo "OK: pull request title is a conventional-commit subject."

  # The docs-only rule, applied to the title. Same path classification as check-commit-types.sh; if
  # you change one, change the other.
  if [ -n "$paths_file" ] && [ -s "$paths_file" ]; then
    case "$title" in
      feat:*|feat\(*|feat!:*|fix:*|fix\(*|fix!:*)
        non_doc=0
        while IFS= read -r path; do
          [ -z "$path" ] && continue
          case "$path" in
            docs/*|*.md|LICENSE) ;;
            *) non_doc=1 ;;
          esac
        done < "$paths_file"
        if [ "$non_doc" -eq 0 ]; then
          echo "::error::The title is typed feat:/fix: but the pull request changes only documentation."
          echo "    title: $title"
          echo "    A squash merge makes this title the subject on main, and release-please turns"
          echo "    feat:/fix: subjects into release notes - so this would announce a change that"
          echo "    did not ship. Retitle it as docs: (or chore:)."
          exit 1
        fi
        ;;
    esac
    echo "OK: the title is not a feat:/fix: over a documentation-only diff."
  fi
  exit 0
fi

echo "::error::The pull request title is not a conventional-commit subject."
echo "    title: $title"
echo "    This repository squash-merges, and the title becomes the commit subject on main and the"
echo "    text release-please turns into release notes. Shape it like a commit message:"
echo "        <type>[(scope)][!]: <description>"
echo "    Allowed types: feat fix docs chore refactor test ci build"
echo "    For example: fix(bridge): stop the reconnect loop after a socket restart"
exit 1
