# Shelldon SSH: instructions for Claude Code

Read `docs/PLAN.md` first. It holds the audit, the decisions and the milestones.
Upstream build, test and style conventions are in `@AGENTS.md` (ConnectBot's). Where AGENTS.md
and this file disagree, this file wins. In particular: new project-owned source files get
`Copyright <year> the Shelldon SSH contributors` and `SPDX-License-Identifier: Apache-2.0`, not
Kenny Root's notice. Do not touch existing upstream headers.

## What this is

Shelldon SSH is a FOSS Termius-style SSH client for Android, forked from ConnectBot (Apache-2.0).
Repo `thowt/shelldon`, application id `io.github.thowt.shelldon`, code namespace still
`org.connectbot` on purpose (cheap upstream merges). Owner: the user (GitHub `thowt`).
Target device: Android 17. Only the `oss` flavour is built and shipped.

Standing instructions from the owner:
- Look for existing apps, read their features, and implement those. Do not reinvent the wheel.
- Get rid of bad features and stupidities.
- User experience and cybersecurity are the key requirements. When they conflict with speed, say so.

Wanted features, in short: stored hosts for one-tap connect, nested groups, tags across groups,
stored identities, stored keys, all the options around them; later export/import and sync via the
owner's own servers, designed so the app can go public with per-user accounts (so end-to-end
encrypted, pluggable backend). Legacy servers (old Cisco IOS, old Debian, BSD, Telnet) must stay
reachable through explicit per-host or per-group choices. Post-quantum key exchange on by default.

## Security non-negotiables

- Never commit secrets: signing keystores, passwords, tokens, private keys, `local.properties`.
  `.gitignore` blocks the common patterns; do not rely on it alone, check `git diff --staged`.
- Release signing keys live on the VM or in CI secrets only. CI uses a read-only token, no secrets.
- No accept-all host-key mode. A changed host key blocks the connection, legacy hosts included.
- No telemetry, analytics or crash reporting. `allowBackup` stays false.
- Do not weaken default crypto. Weak algorithms are per-host or per-group, visible, never global.
- Forwarded ports bind to loopback unless the owner opts in per forward.
- Never log or display secrets or private keys. Clear the clipboard after copying secrets.
- Telnet only as a per-host opt-in with a plaintext warning.
- Any change in `transport/` or crypto code needs tests, ideally against a real OpenSSH container.
- Treat dependencies as supply chain. Before adding one, say why it is needed, its licence (Apache,
  MIT or BSD are fine, GPL needs the owner's decision) and how it is maintained.

## Working rules

- Ask before: using `sudo`, installing system packages, adding dependencies, raising `minSdk`,
  adding permissions, touching signing, publishing anything, or deleting branches.
- Work on a branch per milestone, small logical commits, short imperative subjects. Open a pull
  request into `main`. The owner merges. Do not force-push `main`.
- Before committing: `git diff --check`, `./gradlew spotlessCheck`, `./gradlew :app:testOssDebugUnitTest`,
  and `./gradlew lint` when you touched code. Say plainly which of these you could not run.
- Be honest about what is verified. "Compiles", "unit tests pass" and "tested on the phone" are
  different claims.
- Upstream sync: remote `upstream` is `connectbot/connectbot`. Merge, do not rebase. Keep our code
  in separate packages or modules where possible. Ask before merging upstream into `main`.
- Do not run or "fix" things in `.github/upstream/`. It is parked reference material from ConnectBot.
- CI debug APKs are signed with a throw-away key, so they cannot be installed over each other.
  For upgrade-in-place on the phone, build on this VM (stable `~/.android/debug.keystore`).

## Build and test

```
./gradlew :app:assembleOssDebug      # APK in app/build/outputs/apk/oss/debug/
./gradlew :app:testOssDebugUnitTest  # unit tests
./gradlew lint
./gradlew spotlessCheck              # spotlessApply formats
```

JDK 17. Android SDK for the `compileSdk` in `gradle/libs.versions.toml`. Instrumentation tests
(`connectedOssDebugAndroidTest`) need an emulator and, for `OpenSSHContainerTest`, Docker.
Version code and name come from upstream git tags (`v1.12.*` filter) through the app-versioning
plugin, so the build needs full history and the tag `v1.12.0-alpha`. A fresh clone of this repo has
neither the `upstream` remote nor that tag, so after cloning run once:

```
git remote add upstream https://github.com/connectbot/connectbot.git
git fetch upstream 'refs/tags/v1.12.0-alpha:refs/tags/v1.12.0-alpha'
git push origin v1.12.0-alpha     # so the repo carries its own copy of the tag
```

(The cloud session that prepared this repo could not push tags: its git proxy answered 403.
CI works around it by fetching that one tag from upstream, see `.github/workflows/build.yml`.)

## Icon and branding

Display name "Shelldon SSH". Icon: a turtle named Shelldon with a `#` (the root prompt) on its
shell, original artwork only. Do not use ConnectBot's name, logo or artwork in the UI or store
listing, and do not draw any existing mascot. Keep attribution to ConnectBot in NOTICE and the
About screen.

## Current milestone: M0

Do M0 from `docs/PLAN.md` only. Check this VM (RAM, disk, Java, Docker, internet access to Google and
Maven), install what is missing to build (JDK 17 and the Android command-line SDK for the
`compileSdk` in `gradle/libs.versions.toml`), build the `oss` debug APK, run the unit tests, and
report. Don't change app code yet. Ask the owner before using `sudo`.
