# Third-party components

Shelldon SSH is licensed under the Apache License 2.0 (see [LICENSE](LICENSE) and [NOTICE](NOTICE)).
It is built on, and ships, other people's work. Every library is used under its own licence. This
page calls out the ones that matter most and the ones that carry obligations. The full list of
libraries and their versions is `gradle/libs.versions.toml`.

> **To do before the first release:** a generated, per-library licence report that ships inside the
> app. Until then this page is the reference, and it is not exhaustive.

## Mosh (GPL-3.0-or-later, with an OpenSSL exception)

The Mosh module bundles `libmoshexec.so` for each supported CPU architecture, plus Mosh's terminfo
files. These are built by the ConnectBot project from the Mosh sources and published at
<https://github.com/connectbot/mosh4android>; the build downloads them and checks them against the
SHA-256 sums in `gradle/mosh4android.sha256`.

- Mosh is released under the GNU General Public License version 3 or later, with an exception for
  OpenSSL. See <https://github.com/mobile-shell/mosh> and its `COPYING` file.
- The rest of Shelldon SSH is Apache-2.0. Whether bundling a GPL program in the same package makes the
  whole application subject to the GPL is a legal question. ConnectBot ships Mosh the same way, and
  the Mosh module is a separate Gradle module, but the maintainer has not taken legal advice, and the
  position should be settled before the first release.
- The source that corresponds to the bundled binaries is the Mosh source at the tag named in
  `gradle/mosh4android.version`, together with the build scripts in the `mosh4android` repository.
- A build of Shelldon SSH without the Mosh module is possible; if the licence question or an app
  store's rules call for it, that is the fallback (see `docs/FDROID.md`).

## Cryptography

- **Bouncy Castle** (`bcprov-jdk18on`): MIT-style licence. Used for Argon2id in the vault.
- **Conscrypt**: Apache-2.0. Provides the cryptography providers on the JVM test side and on Android.

## SSH, terminal and related libraries

These come from the ConnectBot organisation and are used under the licences in their repositories:
`sshlib` (the SSH implementation), `termlib` (the terminal), `telnetlib` and `zmodemlib`.

## Security keys

- **YubiKit** (`com.yubico.yubikit`): Apache-2.0. FIDO2 and PIV support.

## Android, Kotlin and UI libraries

AndroidX (Compose, Room, Lifecycle, Navigation, Biometric and others), Material Components, Kotlin
and kotlinx.coroutines, Dagger and Hilt are Apache-2.0.

## Artwork

The launcher icon (a turtle with a `#` on its shell) was made for this project by its maintainer with
an AI image tool. Its licence position is described in [branding/README.md](branding/README.md).
ConnectBot's name and artwork are not used.
