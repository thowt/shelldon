# Contributing to Shelldon SSH

Thanks for wanting to help. Shelldon SSH is a small project run by one maintainer, so a short,
focused pull request that explains itself is the easiest kind to review.

Shelldon SSH is a fork of [ConnectBot](https://github.com/connectbot/connectbot). Problems that are
in ConnectBot's SSH engine, terminal or other upstream code are best reported there as well; this
project merges upstream changes from time to time.

## Reporting a bug or asking for a feature

- Search the [issues](https://github.com/thowt/shelldon/issues) first.
- For a bug, say which Android version and phone you use, which Shelldon build, what you did, what
  you expected and what happened. For a connection problem, the server's software and version help
  most (for example "OpenSSH 7.4" or "Cisco IOS 15.0"). Never paste private keys, passwords or
  full logs that contain them.
- **Security problems do not go in public issues.** See [SECURITY.md](SECURITY.md).

## Building and testing

You need JDK 17 and the Android SDK (see [README.md](README.md#building), including the one-time
`git fetch` of the upstream tag that a fresh clone needs).

```
./gradlew :app:assembleOssDebug      # debug APK
./gradlew :app:testOssDebugUnitTest  # unit tests
./gradlew spotlessCheck              # formatting (spotlessApply fixes it)
./gradlew lint                       # Android lint
```

Tests for the SSH transport run against a real OpenSSH server in a container and need Docker; they
are skipped unless you set the variable:

```
SHELLDON_CONTAINER_TESTS=1 ./gradlew :app:testOssDebugUnitTest --tests 'org.connectbot.shelldon.container.*'
```

Please say in the pull request which of these you ran. "It compiles", "the unit tests pass" and "I
tried it on a phone" are different claims, and all three are useful.

## Making changes

- Branch from `main`, keep commits small and logical, and write short imperative subjects
  ("Add a Files entry to the host list"). Add a few lines of explanation when the why is not obvious.
- New code goes in `org.connectbot.shelldon.*` where it can, and touches upstream files as little as
  possible. That keeps merges from ConnectBot cheap.
- New source files that you write carry the header
  `Copyright <year> the Shelldon SSH contributors` and `SPDX-License-Identifier: Apache-2.0`
  (copy one from `app/src/main/java/org/connectbot/shelldon/`). Do not edit headers of existing
  upstream files.
- Run `git diff --check` before you commit.
- By contributing you agree that your work is licensed under the Apache License 2.0, like the rest of
  the project.

## Ground rules that protect users

These are not up for negotiation in a pull request; [CLAUDE.md](CLAUDE.md) has the full list.

- Never commit secrets: keystores, passwords, tokens, private keys, `local.properties`.
- No telemetry, analytics or crash reporting. Android backup stays off.
- Do not weaken default crypto. Weak algorithms for old servers are per-host or per-group choices
  that stay visible to the user, never global.
- A changed host key blocks the connection. No accept-all mode.
- A change in `transport/` or in crypto code needs tests, ideally against a real OpenSSH container.
- A new dependency needs a reason, a licence that is Apache, MIT or BSD, and a look at how well it is
  maintained. Say so in the pull request. GPL needs the maintainer's decision.

## Pull requests

- Open the pull request against `main`. CI builds a debug APK and runs the unit tests (including the
  container tests); it has to pass. The maintainer merges.
- Keep it to one topic. A refactor and a feature in one pull request are hard to review.
- Screenshots help for anything visual.
