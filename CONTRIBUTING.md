# Contributing

Thanks for looking. This project is small and the rules are short.

## The flow

1. Fork the repository.
2. Branch from `main`. Name it after what it does — `feat/pairing-timeout`, `fix/resize-race`.
3. Commit with sign-off (see below).
4. Open a pull request against `main`. Everything lands through a pull request; nobody pushes to
   `main` directly.
5. Pull requests are squash-merged, so the PR title becomes the commit message on `main`. Give it
   the same shape as a commit message.

## Sign-off is required (DCO)

Every commit must carry a `Signed-off-by` trailer. That is your statement that you wrote the change
or otherwise have the right to submit it under the project's licence, per the
[Developer Certificate of Origin](https://developercertificate.org/).

Git writes the trailer for you:

```bash
git commit -s -m "fix: stop the reconnect loop after a socket restart"
```

To add it to commits you already made:

```bash
git rebase --signoff main
```

## Commit messages

[Conventional commits](https://www.conventionalcommits.org/). The types this project uses:

`feat` · `fix` · `docs` · `chore` · `refactor` · `test` · `ci` · `build`

Optionally scoped, e.g. `feat(bridge): ...`, `fix(app): ...`, `docs: ...`. Write the subject in the
imperative and keep it under about 72 characters.

## What must pass locally before you open a pull request

```bash
cd bridge && go test ./...
./gradlew testDebugUnitTest lintDebug
cd mac && swift test
```

Run the ones your change touches; run all three if you are not sure. Note that `bridge/`, the Gradle
project and `mac/` do not exist yet — the repository is still being bootstrapped, so until those
directories land there is nothing for these commands to run.

## Everything else

- All committed text is English.
- Nothing personal in the tree: no addresses, hostnames, IP addresses, tokens, private keys or
  home-directory paths in any committed file. Use placeholders and documentation-range examples.
- Found a security problem? Do not open an issue — see [SECURITY.md](SECURITY.md).
- By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).
