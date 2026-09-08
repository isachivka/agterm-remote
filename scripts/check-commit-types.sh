#!/usr/bin/env bash
#
# Fails when a commit is typed feat: or fix: while changing ONLY documentation.
#
# Why this exists as a mechanism rather than a rule: release-please publishes feat:/fix: subjects
# verbatim as the release notes a user reads on a store listing, so a docs-only commit typed feat:
# announces a feature that does not exist. Once such a subject is in published history the only
# remaining lever is rewriting history, so the check has to run before the merge, not at release
# time. CONTRIBUTING.md states the rule; this is the part that enforces it.
#
# Usage: scripts/check-commit-types.sh <git-range>       e.g. origin/main..HEAD
#
# Scoped to a RANGE on purpose: it judges the commits being added and never re-litigates history.
# A single bad subject already on main would otherwise fail every future run forever.
set -euo pipefail

range="${1:?usage: $0 <git-range>}"
fail=0

# --no-merges: a merge commit's subject is not authored copy and its diff spans other people's work.
for sha in $(git rev-list --no-merges "$range"); do
  subject="$(git log -1 --format=%s "$sha")"

  case "$subject" in
    feat:*|feat\(*|feat!:*|fix:*|fix\(*|fix!:*) ;;
    *) continue ;;
  esac

  # Every path this commit touches. A commit touching docs AND code is fine - only docs-ONLY is wrong.
  non_doc=0
  while IFS= read -r path; do
    [ -z "$path" ] && continue
    case "$path" in
      docs/*|*.md|LICENSE) ;;
      *) non_doc=1 ;;
    esac
  done <<< "$(git show --pretty=format: --name-only "$sha")"

  if [ "$non_doc" -eq 0 ]; then
    echo "::error::$(git log -1 --format=%h "$sha") is typed feat:/fix: but changes only documentation."
    echo "    subject: $subject"
    echo "    Release notes are generated from feat:/fix: subjects and are read by users, so this"
    echo "    would announce a change that did not ship. Retype it as docs: (or chore:) and"
    echo "    force-push the branch."
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "::error::Commit type check failed."
  exit 1
fi
echo "OK: no docs-only commit is typed feat: or fix: in $range."
