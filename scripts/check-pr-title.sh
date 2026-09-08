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
# Usage: scripts/check-pr-title.sh "<title>"
set -euo pipefail

title="${1-}"

# feat, feat(scope), feat!, feat(scope)!  then ": " then a non-empty description.
# The type list is CONTRIBUTING.md's, and the two must stay in step.
types='feat|fix|docs|chore|refactor|test|ci|build'
if printf '%s' "$title" | grep -Eq "^($types)(\([a-zA-Z0-9._/-]+\))?!?: .+"; then
  echo "OK: pull request title is a conventional-commit subject."
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
