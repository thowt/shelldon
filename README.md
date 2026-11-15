# Shelldon SSH

A free, open-source SSH client for Android that aims to match what Termius offers
for managing many servers, without the subscription: stored hosts, nested groups,
tags that cut across groups, reusable identities, a key vault, and later
end-to-end-encrypted sync that you host yourself.

**Status: pre-alpha, a work in progress.** Nothing here is released, and many of
the features below are plans, not code. See [docs/PLAN.md](docs/PLAN.md) for the
audit of existing apps, the decisions taken, and the milestones.

## Where it comes from

Shelldon SSH is a fork of [ConnectBot](https://github.com/connectbot/connectbot)
(Apache-2.0). ConnectBot already provides the hard parts: an SSH engine with
per-host crypto policy and post-quantum key exchange, a terminal emulator, Mosh,
FIDO2/PIV keys, jump hosts and port forwarding. This project adds the management
layer on top and removes features that don't earn their place.
The original README is kept in [docs/upstream-README.md](docs/upstream-README.md).

## Goals

- Hosts, nested groups, tags, identities and keys, with sensible inheritance
- Legacy servers stay reachable (old Cisco IOS, old Debian, BSD, even Telnet),
  but weak crypto is always an explicit, visible, per-host or per-group choice
- Post-quantum key exchange by default where the server supports it
- Vault protected by a master password, with a biometric shortcut
- Snippets, SFTP browser, port forwarding, jump hosts, Mosh
- Export/import, then end-to-end-encrypted sync with pluggable storage
- No telemetry, no analytics, no accounts needed

## Building

Requires JDK 17 and the Android SDK (see `compileSdk` in `gradle/libs.versions.toml`).

```
./gradlew :app:assembleOssDebug     # debug APK: app/build/outputs/apk/oss/debug/
./gradlew :app:testOssDebugUnitTest # unit tests
```

The application id is `io.github.thowt.shelldon` (debug builds add `.debug`).
Only the `oss` flavour is built and shipped.

## Licence

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
