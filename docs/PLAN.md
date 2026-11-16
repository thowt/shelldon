# Shelldon SSH: plan

Canonical copy of the plan. It started as the audit in the "Android apps" project
(`ssh-client/01-audit-and-recommendation.md`); from now on this file in the repo is
the source of truth. Last updated 2026-11-16.

Goal: a FOSS Termius-style SSH client for Android. Hosts, nested groups, cross-group
tags, reusable identities, stored keys, then export/import, then E2E-encrypted sync
that can later be offered to the public.

## Decisions so far

- **Name: Shelldon SSH.** Application id `io.github.thowt.shelldon`. Repo `thowt/shelldon` (private for now).
- **Icon direction:** a turtle named Shelldon with a `#` (the root prompt) on its shell.
  Original artwork only: no existing mascot, no ConnectBot artwork. Adaptive icon with a
  monochrome layer for themed icons. A name check (EUIPO TMview, Play Store, F-Droid,
  domains) is still to do before any public release.
- Audit existing apps first, then fork or build. **Result: fork ConnectBot** (below), with a
  ten-minute UX comparison on the phone as the tie-breaker (milestone M2).
- v1 extras: SFTP browser, port forwarding + jump hosts, snippets, Mosh.
- Vault: master password plus a biometric shortcut (portable, so export and sync work later).
- Sync: client-side encryption with a pluggable backend. v1 backends are bring-your-own storage
  (WebDAV/Nextcloud, S3, SFTP). An optional small self-hostable server can come later with the
  same encrypted format.
- Legacy servers must stay reachable (old Cisco 2960/3750, old Debian, BSD). Weak algorithms
  are never removed, only gated per host or group.
- **Telnet is kept**: off by default, per-host opt-in, with a plaintext warning.
- **Target device: Android 17.** `compileSdk` 37, `targetSdk` 36, `minSdk` 24 as inherited.
  Raise `minSdk` before a public release if older-device code gets in the way.
- **Hosting/CI: GitHub** as the working remote, builds on the user's Ubuntu VM and in GitHub
  Actions. GitLab can be added later as a mirror.
- **Post-quantum key exchange is on by default** where the server supports it.
- Only the `oss` flavour is built and shipped (F-Droid friendly, no Play Services). The
  `google` flavour stays untouched to keep upstream merges cheap.
- **UI direction (decided 2026-11-16 after first install on the phone).** The stock ConnectBot
  screens were judged empty and too hidden behind a top-right menu, and Mosh was not discoverable.
  Approved direction: dark-first Material 3 theme (background `#0E1411`, accent `#5FD38D`, amber
  for legacy, blue for Mosh), bottom navigation (Hosts, Keychain, Snippets, Tunnels, Settings), a
  Hosts screen with search plus quick connect (`user@host[:port]`), filter chips, active-session
  cards and host cards that carry visible badges (Mosh, Legacy, Telnet plaintext, jump host).
  Mosh is a visible protocol selector (SSH / Mosh / Telnet / Local) in the host editor, with a
  note that the server needs `mosh-server` and UDP 60000-61000.
  New UI code lives in `org.connectbot.shelldon.ui` so upstream merges stay cheap. The old
  ConnectBot host list screen is left in place and unused.
  Phase 1 (PR #1): theme, bottom bar, Hosts screen, protocol selector. Later phases: Keychain
  (Identities / Keys / Known hosts), group screen with inherited defaults (needs M5), Snippets and
  Tunnels tabs (M7), post-quantum badge (needs the negotiated KEX reported by the transport).
- **Icon (decided 2026-11-16):** a 3D-style green turtle with a cream `#` on its shell, drawn by the
  owner with an AI image tool (the first vector drawings were rejected as crude). Adaptive icon
  with a safe-zone-fitted foreground, dark background `#0C1916` and a monochrome layer for themed
  icons. Source and build script are in `branding/`. Open point: confirm the licence position of
  AI-generated artwork before a public release.

- **Findings from the CI APK listing (2026-11-16):** the `oss` debug APK bundles Mosh
  (`libmoshexec.so` for arm64-v8a, armeabi-v7a, x86, x86_64, plus the terminfo asset); whether a
  Mosh session actually works against a real server is still untested. The debug APK has application
  id `io.github.thowt.shelldon.debug` and version name like `git-v1.12.0-alpha-3364`.
- **Removed the `easylauncher` Gradle plugin.** It stamped a "debug" ribbon on the launcher icon and
  wrapped the old ConnectBot artwork, which hid the turtle on Android 8+. Debug builds are still told
  apart by the `.debug` application id.
- **Keys:** the extra-keys row is drawn 1.2x the stored key size (`KEY_SIZE_SCALE`). A real "key size"
  setting can replace the constant later.
- **Quick connect and host keys:** quick-connect sessions use a temporary host with no database
  record, so upstream does not persist their host key. Saving the session as a host fixes that.
  Quick connects are remembered (user, host, port, time only) for the "Last sessions" list.

- **Branding sweep (2026-11-16).** Every user-visible "ConnectBot" is now "Shelldon SSH": all 54 string
  files, the Help screen title, the Hints intro, the export and tombstone file names, the en-US store
  listing. Bug and source links point to `github.com/thowt/shelldon` (private until the repo goes public).
  The Help screen carries the copyright and a "Based on ConnectBot, (c) Kenny Root, Jeffrey Sharkey and the
  ConnectBot contributors, Apache-2.0" line, and the Contact screen links to the upstream project.
  **Removed:** the "Support Development" section (GitHub Sponsors and Buy Me a Coffee links of the upstream
  maintainer), the unused upstream logo files in `icons/`, `connectbot.iml`, and the ConnectBot screenshots
  and icon under the en-US store listing.
  **Deliberately kept:** the `org.connectbot` namespace and class names (`ConnectBotTheme`, ...) for cheap
  upstream merges; the Android Keystore aliases `connectbot_bio_*` and `connectbot_password_key` (renaming
  would orphan stored keys); licence headers; NOTICE and README attribution; the Mosh install link to
  upstream's `mosh4android` releases. **Open:** the SSH identification string sent to servers comes from the
  library (sshlib) and was not verified; check it with the OpenSSH container test (M3) and decide whether to
  set our own. Store listing text is a placeholder until a real submission.

## What was checked

Shallow clones of the four candidates were read: licence, module layout, dependencies,
database entities, and README feature lists. **Nothing was built or run**, so UX, stability
and runtime behaviour are unverified.

| | ConnectBot | Shellwave | Haven | SSHBorg |
|---|---|---|---|---|
| Licence | Apache-2.0 | GPLv3 | AGPL-3.0 | GPLv3 |
| Size (src files, incl. tests) | 464 / ~97k lines | 276 / ~42k | 1128 / ~300k | 143 / ~31k |
| Stack | Kotlin, Compose, Room, Hilt | Kotlin, Compose, Room, Hilt | Kotlin, Compose, Room | Kotlin, Compose, Room |
| SSH engine | own Kotlin lib (sshlib/cbssh 0.6.9, Apache-2.0) | sshj 0.41.2 + BouncyCastle | own/vendored modules | jsch 2.28.8 |
| Terminal | own lib (termlib 0.3.15) | own `terminal-core` (vendored) | own `terminal-haven` | own |
| IDs | UUID | auto-increment Long | String UUID | auto-increment Long |
| Groups | none | flat, coloured | flat, with group-level identity | flat, coloured |
| Tags | none | none | colour tag only | none |
| Identities | none | none | yes (name, username, password/key ref), inherited from group | none |
| Keys | Pubkey entity: Keystore/biometric, FIDO2, PIV fields | credentials sealed by Keystore, optional biometric | SshKey: biometric/hardware alias, certificates | key entity, encrypted blob |
| Jump host | yes | yes (host pointer chain) | yes | yes |
| Port forwarding | yes | yes (loopback by default) | yes | yes |
| Mosh | yes | no | yes (separate module) | no |
| SFTP in the app | no (library supports it) | upload/download | yes, full browser | yes, file manager |
| Snippets/scripts | no | yes (widget, tile, shortcut) | partial | no |
| Per-host crypto policy | **yes**: presets DEFAULT/FUTURE/FIPS/LEGACY/CUSTOM, per-category expressions, min RSA/DH bits, editor UI | not seen | not checked | not checked |
| Post-quantum KEX | **yes** (cbssh): mlkem768x25519-sha256, sntrup761x25519-sha512 | not checked | not checked | not checked |
| Export/sync | no | none, `allowBackup=false` | own sync profiles | no ("no cloud") |
| Min Android | 7 (API 24) | 12 (API 31) | 8 | 10 |

Notes:
- **Haven** has the closest identity model, but it is a very large "thin-client OS" (VNC, RDP,
  mail, chat, MCP and more) and its README calls itself a vibe-coding experiment. Not a sensible
  base for a lean, security-first client. A good design reference.
- **Shellwave** is the most polished on features and tests today, with strong security defaults:
  host-key changes block the connection, there is no accept-all mode, and forwards bind to loopback.
- **ConnectBot** is the most mature (since 2007), has the permissive licence, and already uses UUID
  primary keys.
- Seen but not audited: ShellDroid (identities module, Tink vault, beta), TabSSH, Chuchu, SlipShell.

## Recommendation: fork ConnectBot (moderate confidence)

Why:
1. **The hard parts already exist**: SSH engine, terminal emulator, Mosh, FIDO2/PIV keys, jump hosts,
   forwarding, known hosts, per-host crypto policy, post-quantum key exchange.
2. The missing parts (groups, tags, identities, snippets, SFTP UI, vault, export/sync) are mostly
   CRUD, UI and crypto plumbing.
3. **Licence freedom**: Apache-2.0 can be combined into a GPLv3 app, and it keeps the option of a
   different licence for the sync server.
4. **Sync-friendly data model**: UUID keys already, so there is no ID migration.

Trade-offs:
- The feature gap is the largest of the candidates.
- UX is unverified and may feel dated next to Shellwave.
- Following upstream will cost merge effort.
- Single-maintainer upstream, like the others.

Borrow ideas, not code, from the GPL/AGPL projects:
- Shellwave: strict host-key policy, loopback-only forwards, tmux reattach, key enrolment, script triggers.
- Haven: group → identity → key inheritance, with a per-host "none" override.

**Tie-breaker before going deep (M2):** use ConnectBot (our debug APK) and Shellwave (F-Droid)
side by side on the phone for ten minutes. UX cannot be judged from code. If Shellwave clearly
wins on feel, the alternative is to fork it and add Mosh, UUID IDs, the vault, a legacy-algorithm
policy and PQ key exchange.

## Legacy server support (old Cisco IOS, old Debian, BSD)

Principle: modern by default, legacy by explicit per-host (or per-group) choice. Nothing is removed,
and nothing is silently downgraded.

What ConnectBot and its SSH library (cbssh, Apache-2.0) already provide, verified in source:
- Per-host crypto policy with presets DEFAULT, FUTURE, FIPS, LEGACY and CUSTOM, plus a host-editor
  screen for it.
- CUSTOM takes an OpenSSH-style expression per category (KEX, cipher, MAC, host key, pubkey), for
  example `+diffie-hellman-group1-sha1`, with configurable minimum RSA and DH sizes.
- Algorithms implemented include `diffie-hellman-group1-sha1`, `diffie-hellman-group14-sha1`,
  `diffie-hellman-group-exchange-sha1`, `aes128-cbc`, `aes256-cbc`, `3des-cbc`, `hmac-sha1` and
  `ssh-rsa`. These cover what old Cisco 2960/3750 IOS typically offers.
- The LEGACY preset lowers the RSA/DH minimum to 1024 bits.

Gaps and additions to build:
- **Groups inherit the policy.** One group "Old network gear" set to LEGACY covers every switch in
  it. A host can override.
- **Fail-closed, then guided.** On a "no matching algorithm" handshake error, show what the server
  offered and offer a one-tap "allow only this for this host", adding the single needed algorithm
  rather than switching on the whole legacy list.
- **Visible warning.** Amber "legacy crypto" badge in the host list and in the session header.
- **Host-key pinning is mandatory even for legacy hosts.** A changed key still blocks the connection.
- **Very small RSA host keys** (below 1024 bits) need CUSTOM with a lowered minimum. This stays an
  explicit per-host choice.
- **DSA (`ssh-dss`) host keys**: no support found in cbssh. Probably rare, since old Debian and BSD
  usually also offer RSA. Verify against a real old server before deciding.
- **Telnet**: kept, off by default, per-host opt-in, plaintext warning.
- **Test it, don't assume**: integration test against a throwaway OpenSSH container configured with
  only legacy algorithms, ideally also an old Debian/OpenSSH image. A real old Cisco would be a bonus.

## Post-quantum key exchange

What matters is key exchange, because of "store now, decrypt later" capture of today's traffic.
Host-key and login signatures are a separate and later problem; as far as I know OpenSSH has no
post-quantum signature support yet (to verify when it matters).

Server side:
- OpenSSH has offered a PQ hybrid key exchange by default since 9.0 (2022, sntrup761x25519-sha512).
  It added mlkem768x25519-sha256 in 9.9 and made it the default in 10.0 (April 2025). OpenSSH 10.1
  warns when a connection does not use PQ key exchange.
- Ubuntu 26.04 ships OpenSSH 10.2p1 with the ML-KEM hybrid on by default.

Client side (cbssh, verified in source):
- Implements `mlkem768x25519-sha256`, `sntrup761x25519-sha512` (and the `@openssh.com` alias) and
  NIST-curve ML-KEM hybrids.
- ML-KEM provider is pluggable: a Java 23+ native provider via reflection, and a pure-Kotlin
  fallback. So it should work on Android without Play Services. **Unverified on a device**, test on
  Android 17.

Plan:
- Default order: `mlkem768x25519-sha256`, then `sntrup761x25519-sha512`, then `curve25519-sha256`.
  Already close to the DEFAULT/FUTURE presets, but confirm against the cbssh preset lists.
- **Session indicator**: "PQ key exchange" badge when negotiated, a neutral "classical" marker
  otherwise. Do not scare users about legacy hosts, which cannot do PQ.
- **Optional "require PQ"** per host or group: refuse to connect if PQ is not negotiated.
- A single quiet setting for the nag, like OpenSSH's `WarnWeakCrypto`.
- **Vault and sync need no PQ asymmetric crypto**: AES-256-GCM or XChaCha20-Poly1305 with an
  Argon2id-derived key is symmetric. Revisit only if we add sharing between users with public-key
  crypto (then use ML-KEM hybrids).
- **Tests**: container with OpenSSH 10.x to confirm ML-KEM negotiation, and one with only
  `sntrup761x25519-sha512` to confirm fallback.

## Target data model (sync-ready from day one)

- Every record has a UUID, `updatedAt`, a revision counter and a tombstone flag.
- Records: Host, Group (parent group, nested), Tag, HostTag, Identity (username + password or key
  reference), Key, Snippet, PortForward, KnownHost.
- Host settings, including the crypto policy, resolve in the order: host → group chain → app default.
- Identity resolution at connect time: host identity → nearest group identity → prompt.
- Secrets live inside the encrypted vault. The vault key comes from a master password (Argon2id)
  and is wrapped by the Android Keystore for the biometric shortcut.
- Each record is encrypted separately, so a server only ever sees ciphertext and can merge by revision.
- The sync backend is an interface: `list`, `get`, `put` with compare-and-swap, `delete`.

### M5 design, slice 1 (data layer, 2026-11-16)

Found while reading the code: **upstream already ships the database side of sync** (`data/sync/SyncDatabase.kt`):
every object has a `repository_id` (a "repository" is a set of objects that syncs together; `Local` is built
in), triggers write each change to `sync_outbox`, and `sync_versions`, `sync_batches`, `sync_conflicts` and
`sync_certificates` exist. There is no network client in the tree. M9 builds on this instead of a second
mechanism; new tables join it by adding their name to `SyncDatabase.tables`.

What slice 1 adds (code in `org.connectbot.shelldon.data.groups`, schema version 21, AutoMigration 20 to 21):
- `host_groups`: nested through `parent_id`, with name, colour, manual `sort_order` and an optional
  `identity_id`. Depth limited to 16, sibling names unique ignoring case, loops refused. Deleting a group
  moves its sub-groups and hosts up one level (numbering clashing names), so **nothing is ever deleted
  or hidden by deleting a folder**.
- `tags` and `host_tags`: free labels across groups; names unique ignoring case; foreign keys cascade, so
  deleting a tag or a host removes only the links.
- `identities`: name, user name and an optional key id. **No password column**: secrets wait for the vault (M4).
- `hosts.group_id` and `hosts.identity_id`, both nullable. These two columns are excluded from the JSON
  export until groups, tags and identities have their own export (M8); importing a host never invents a group.
- Resolution (pure Kotlin, `IdentityResolver`): host, then the group chain nearest first, then nothing.
  `EntityIds.NONE` on a host or group opts out of inheriting. A reference to a deleted identity counts as
  unset. A user name or key the host states itself always wins over the identity; the identity only fills
  what the host leaves open ("never use keys" is respected). The result is computed per connection and never
  written back to the host.
- The repository (`ShelldonGroupRepository`) checks every rule inside one transaction and answers with a
  `Rejection` instead of throwing, so the UI can show a message.
- New tables are copied by `BackupFilter` and are in the sync trigger list. CI now fails a pull request when
  `app/schemas/` is not current (Room rewrites it on every build).

Not in slice 1, next: screens (group tree, tag filter chips, identity editor), applying the resolved login
when a connection opens, crypto-policy inheritance (needs an explicit "inherit" value on the host), jump host
and port defaults on groups, drag reorder.

## Security rules and features to drop

- No accept-all host-key mode. A changed host key blocks the connection.
- No telemetry, analytics or crash reporting. `allowBackup=false`.
- Forwarded ports bind to loopback unless the user opts in.
- Private keys are never shown in plain text or logged. Clipboard is cleared after copy. Screenshots
  of terminal and key screens are blocked (`FLAG_SECURE`) behind a setting.
- Auto-lock the vault on timeout, and also when the app is backgrounded.
- Weak algorithms are **off by default but available per host or group**. They are never offered
  globally in one switch.
- Telnet only as a per-host opt-in with a plaintext warning.
- Do not support SSHv1. Servers that advertise "1.99" still speak SSHv2.

## Hosting and CI

State on 2026-11-15:
- The repo is `thowt/shelldon` on GitHub, history is ConnectBot's full `main` plus our commits.
  Remote `upstream` points at `connectbot/connectbot`.
- CI is `.github/workflows/build.yml`: read-only token, no secrets, no cron, actions pinned to SHAs.
  It builds the `oss` debug APK, runs the unit tests and uploads the APK for 14 days.
  ConnectBot's own pipeline is parked in `.github/upstream/` and does not run.
- **CI debug APKs are signed with a throw-away key**, so each one has a different signature and
  cannot be installed over the previous one. For upgrade-in-place on the phone, build on the VM
  (stable `~/.android/debug.keystore`), or later add a stable debug keystore as a CI secret.
- **Update in place (decided 2026-11-16):** CI also builds an optimised release APK
  (`io.github.thowt.shelldon`, R8, async memory tagging, no coverage). With the three
  `SHELLDON_KEYSTORE_*` secrets (see `docs/SIGNING.md`) it carries a fixed internal signature and
  installs over the previous build. Debug and release APKs have different application ids, so they
  coexist with separate data.
- **One variant per CI run (decided 2026-11-16).** A run that built both took ~37 minutes (debug build
  12, unit tests 8, release build 17). Now pull requests build the debug APK and run the unit tests;
  pushes to `main` build only the signed release APK (the tests already ran on the pull request, so
  the merge result itself is not re-tested); a manual run builds a release (or debug) of any branch.
  Pull request runs never read the signing secrets.
- Release signing keys never go in the repo. They stay on the VM or in CI secrets.
- Free-tier budget: GitHub Actions gives 2,000 minutes a month for private repos. Android Gradle
  builds are heavy, so watch usage. A self-hosted runner on the VM costs no minutes.
- Compile feedback also comes from the VM, since the cloud workspace used for planning cannot build
  Android code (no SDK, Maven Central blocked).

## Milestones

Each milestone is a branch and a pull request into `main`. The user merges after CI is green.

- **M0: environment and baseline (VM, no code changes).** Check RAM, disk, Java, Docker and network
  access. Install what is missing to build. Build the `oss` debug APK, run the unit tests, report.
  Also report: does the sideloaded APK include Mosh (it is a dynamic feature module), and what version
  code and name the build gets (versioning comes from upstream git tags, `v1.12.*` filter).
- **M1: first install and branding.** Actions run green. Install on the Android 17 phone and connect to
  one real host. Replace the launcher icon (turtle with `#`), sweep user-visible "ConnectBot" strings,
  add About-screen attribution. Decide our own versioning scheme.
- **M2: UX tie-breaker.** User compares with Shellwave on the phone. Decide: continue the fork, or switch base.
- **M3: integration tests.** Containerised OpenSSH: legacy-only, ML-KEM, sntrup-only, modern. Real old
  Cisco if one turns up.
- **M4: vault.** Master password (Argon2id), biometric shortcut, auto-lock, migrate existing secrets.
- **M5: data model (slice 1 done in PR, see "M5 design").** UUID records, nested groups, tags, identities, resolution rules including
  crypto-policy inheritance. Room migrations with tests.
- **M6: host-list UX.** Search, group tree, tag filters, quick connect, legacy and PQ badges.
- **M7: snippets and SFTP browser.**
- **M8: export/import** (encrypted file, plus `~/.ssh/config` import).
- **M9: sync.** Backend interface, then WebDAV, then optional server.

## Open items

- User may find an old Cisco 2960/3750 for real-device legacy testing.
- ShellDroid is worth a second look if its licence is permissive.
- Name and trademark check before any public release.
- Icon provenance: AI-generated artwork. Check the tool's terms and the copyright position before release.
- Decide where new code lives: new Gradle modules (for example `:vault`, `:sync`, pure Kotlin where
  possible) keep upstream merges cheap and the code unit-testable.

## Sources

- https://github.com/connectbot/connectbot
- https://github.com/connectbot/cbssh
- https://connectbot.org/
- https://github.com/LordOfPolls/Shellwave
- https://f-droid.org/packages/io.github.lordofpolls.shellwave/
- https://github.com/GlassHaven/Haven
- https://github.com/payne1982/sshborg
- https://github.com/ebadenes/ShellDroid
- https://www.openssh.org/pq.html (OpenSSH post-quantum status)
- https://computingforgeeks.com/ubuntu-2604-post-quantum-ssh-hardening/
- https://cicdcalculator.com/gitlab-ci-free-tier (GitLab Free vs GitHub Actions minutes)
- https://docs.termius.com/llms-full.txt (Termius concepts: vaults, groups, hosts, keychain)
- https://termai.sh/blog/juicessh-alternatives
