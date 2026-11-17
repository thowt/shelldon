# Shelldon SSH

[![Build](https://github.com/thowt/shelldon/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/thowt/shelldon/actions/workflows/build.yml)
![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)
![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84.svg)
![Status: pre-alpha](https://img.shields.io/badge/status-pre--alpha-orange.svg)
![Telemetry: none](https://img.shields.io/badge/telemetry-none-brightgreen.svg)

A free, open-source SSH client for Android that aims to match what Termius offers for managing
many servers, without the subscription: stored hosts, nested groups, tags that cut across groups,
reusable identities, a key vault, and later end-to-end-encrypted sync that you host yourself.

## Status

**Pre-alpha.** There is no release and no store listing yet. The code builds and the tests pass in
CI, but so far it has been tried on a phone only by its author, against a handful of servers. Expect
rough edges, and do not rely on it for anything you cannot afford to lose.

| Area | State |
| --- | --- |
| Hosts, nested groups, tags, quick connect, search | Built |
| Identities and a keychain (keys, known hosts) | Built |
| Vault: master password (Argon2id), biometric unlock, auto-lock | Built |
| Encrypted backup, export and import, `~/.ssh/config` import | Built |
| SFTP browser, snippets, port forwarding (`-L`, `-R`, `-D`) with a Tunnels tab | Built |
| Legacy servers (old Cisco IOS, old Debian, BSD, Telnet) through explicit per-host or per-group choices | Built, tested against OpenSSH containers; real old devices are untested |
| Post-quantum key exchange (ML-KEM and sntrup hybrids) where the server supports it | In the SSH engine; not yet verified against a real server on a phone |
| Sync through your own storage (WebDAV first), end-to-end encrypted | Planned |
| Passwords stored in identities | Planned (keys only for now) |

[docs/PLAN.md](docs/PLAN.md) has the audit of existing apps, the decisions taken and why, and what
each round of work contained.

## Where it comes from

Shelldon SSH is a fork of [ConnectBot](https://github.com/connectbot/connectbot) (Apache-2.0).
ConnectBot already provides the hard parts: an SSH engine with per-host crypto policy and
post-quantum key exchange, a terminal emulator, Mosh, FIDO2 and PIV keys, jump hosts and port
forwarding. This project adds the management layer on top and drops features that do not earn their
place. ConnectBot's original README is kept in [docs/upstream-README.md](docs/upstream-README.md).
This project is not affiliated with or endorsed by the ConnectBot project.

Much of the new code was written with the help of an AI coding assistant (Claude, by Anthropic) and
reviewed and tested by the maintainer. The commit history shows which commits.

## Security and privacy

- No telemetry, no analytics, no crash reporting, no account. Android backup is switched off.
- A changed host key blocks the connection. There is no "accept everything" mode, legacy hosts
  included.
- Weak algorithms for old servers are a visible choice per host or per group, never a global
  switch. The defaults are not weakened.
- Local and SOCKS forwards (`-L`, `-D`) listen on the phone's loopback only.
- Telnet is a per-host opt-in with a plaintext warning.

These are commitments the project holds itself to, written down in [CLAUDE.md](CLAUDE.md); they
are not an audit result. How to report a vulnerability: [SECURITY.md](SECURITY.md).

## Getting it

Nothing is released yet. Until the first release, the only builds are the debug APKs that CI
attaches to its runs (Actions, pick a run, Artifacts), which need a GitHub login to download. Debug
builds from CI are signed with a throw-away key and cannot be installed over each other.

## Building

Requires JDK 17 and the Android SDK (see `compileSdk` in `gradle/libs.versions.toml`).

```
./gradlew :app:assembleOssDebug      # debug APK: app/build/outputs/apk/oss/debug/
./gradlew :app:testOssDebugUnitTest  # unit tests
./gradlew spotlessCheck lint         # formatting and Android lint
```

The version code is derived from an upstream git tag, so a fresh clone needs this once, or the
build fails:

```
git remote add upstream https://github.com/connectbot/connectbot.git
git fetch upstream 'refs/tags/v1.12.0-alpha:refs/tags/v1.12.0-alpha'
```

(This goes away when the project has its own release tags.) The application id is
`io.github.thowt.shelldon`; debug builds add `.debug`. Only the `oss` flavour is built and shipped.

## Contributing

Bug reports, ideas and pull requests are welcome, see [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Third-party components keep their
own licences; the ones that carry obligations (Mosh is GPL-3.0) are listed in
[THIRD_PARTY.md](THIRD_PARTY.md).
