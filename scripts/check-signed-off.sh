#!/usr/bin/env bash
#
# Fails when a commit in the range carries no `Signed-off-by:` trailer.
#
# CONTRIBUTING.md makes sign-off (the Developer Certificate of Origin) a condition of contributing.
# A rule nothing checks is a rule that holds until the first person who has not read it, and the
# only fix after a merge is rewriting published history - so the check runs before the merge.
#
# Usage: scripts/check-signed-off.sh <git-range>        e.g. origin/main..HEAD
#
# BOTS ARE NOT EXEMPT, and this was checked rather than assumed. Dependabot signs its own commits:
# pull request #1 of this repository carries `Signed-off-by: dependabot[bot] <support@github.com>`
# on the commit it authored. Every other bot that opens pull requests here would have to do the
# same. Adding an exemption keyed on an author name would have been both unnecessary and trivially
# forgeable - the author field of a commit is whatever its author typed.
#
# What is NOT checked: that the trailer's identity equals the commit's author. GitHub's own DCO app
# does not require that either (a patch you carry for someone else is signed off by you), and
# demanding it would reject exactly the case the DCO exists to describe.
set -euo pipefail

range="${1:?usage: $0 <git-range>}"
fail=0

# --no-merges: a merge commit introduces no authored change, and neither the DCO nor GitHub's DCO
# app asks for a sign-off on one.
for sha in $(git rev-list --no-merges "$range"); do
  # git's own trailer parser, not a grep over the whole message: a `Signed-off-by:` line quoted in
  # the middle of a commit body is not a trailer, and treating it as one would pass a commit that
  # merely talks about signing off. `only` drops every other trailer from the output.
  trailers="$(git log -1 --format='%(trailers:key=Signed-off-by,valueonly,only)' "$sha")"

  ok=0
  while IFS= read -r value; do
    [ -z "$value" ] && continue
    # Shape only: a name, then an address in angle brackets. Enough to reject `Signed-off-by: me`
    # and an empty trailer, and not so much that it starts adjudicating what a real name looks like.
    case "$value" in
      *\ \<*@*\>) ok=1 ;;
    esac
  done <<< "$trailers"

  if [ "$ok" -eq 0 ]; then
    echo "::error::$(git log -1 --format=%h "$sha") has no Signed-off-by trailer."
    echo "    subject: $(git log -1 --format=%s "$sha")"
    echo "    Every commit needs one (CONTRIBUTING.md, Developer Certificate of Origin)."
    echo "    Add it with 'git commit -s', or to a branch you already have:"
    echo "        git rebase --signoff main   # then force-push the branch"
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "::error::Sign-off check failed."
  exit 1
fi
echo "OK: every commit in $range is signed off."
