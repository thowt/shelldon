# Shelldon SSH: plan

Canonical copy of the plan. It started as the audit in the "Android apps" project
(`ssh-client/01-audit-and-recommendation.md`); from now on this file in the repo is
the source of truth. Last updated 2026-11-16.

Goal: a FOSS Termius-style SSH client for Android. Hosts, nested groups, cross-group
tags, reusable identities, stored keys, then export/import, then E2E-encrypted sync
that can later be offered to the public.

## Decisions so far

- **Name: Shelldon SSH.** Application id `io.github.thowt.shelldon`. Repo `thowt/shelldon` (public since 2026-11-25).
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
  id `io.github.thowt.shelldon.debug` and version name like `git-v1.12.0-alpha-3364` (from round 1 on: `0.1.0+<commits since the anchor tag>`, no `-oss` suffix).
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
  listing. Bug and source links point to `github.com/thowt/shelldon` (the repo has been public since 2026-11-25).
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

## Delivery in three rounds (decided 2026-11-16 evening)

The owner wants the remaining milestones in at most three big builds, tested on the phone between them,
then fine-tuning. Each round is one pull request. Decisions taken with it:

- **Round 1 (branch `m6-keychain-groups`):** Keychain with Identities, Keys and Known hosts; group tree,
  tags, host-list filters; group, tag and identity pickers in the host editor; the resolved login applied
  when a connection opens; version line at the bottom of Settings; F-Droid notes (`docs/FDROID.md`).
- **Round 2 (branch `m7-vault-export`, see "Round 2" below):** vault (required from first launch, master
  password with Argon2id, biometric unlock, auto-lock), encrypted export/import plus `ssh_config` import,
  OpenSSH container tests (M3). Identity passwords did not make it and are a fine-tuning candidate.
- **Round 3:** SFTP browser, snippets, tunnels tab, WebDAV sync. **Sync is the first thing to slip** to a
  later round if this one is too big.
- **Vault is required, not optional**: one code path to test, and secrets are never stored in the clear.
- **Argon2id comes from BouncyCastle** (MIT-style licence, pure Java, actively maintained) instead of a
  native library: no NDK step, builds from source for F-Droid. Added in round 2; check first whether
  sshlib already brings it in.
- **Publishing:** IzzyOnDroid first, official F-Droid later (see `docs/FDROID.md`).

### Round 1: what was built and how (2026-11-16)

Status: CI is green on the pull request (debug APK, unit tests, Room schema check) and on the optimised
release build of `main` after the merge (run 43924739116). **Not yet tried on the phone.**

- **Keychain tab** is a hub, not a copy of the upstream screens: Identities (create, edit, delete, usage
  count, warning when the named key was deleted), the stored keys (a tap opens the existing key editor,
  "Manage" opens the existing key screen with generate/import/FIDO2), and a Known hosts overview across all
  hosts (`known_hosts_overview`, forget one key). Identities hold a user name and an optional key and
  **no password**: stored secrets wait for the vault in round 2.
- **Resolution order** (host, then group chain nearest first, then nothing; `EntityIds.NONE` opts out;
  a deleted identity counts as unset) is applied **only when a connection opens**, on a copy of the host
  (`TerminalManager.withEffectiveLogin`, and for jump hosts in `SSH.kt`). Nothing is written back, so a
  host keeps following its folder. A login the host states itself (user name, a specific key, "never use
  keys") always wins over the identity.
- **Hosts screen**: one folder level at a time with a breadcrumb; folders first, then hosts. A text search
  or a tag filter looks across all folders (flat list, the folder path shown under each host) so a match
  is never hidden. Tags combine with AND. Create, edit, delete folders and "Move to folder" from the host
  menu. Deleting a folder moves its hosts and sub-folders up one level.
- **Host editor** got an "Organise" section (folder, tags, identity) with a plain-language preview of the
  login that will be used. This also fixed a data-loss bug in the upstream editor: it rebuilt the host
  from the form and would have dropped `groupId` and `identityId` on every save.
- **Version line**: `shelldon.version` in `gradle.properties` gives the name (`0.1.0`, plus `+N` for builds
  after the anchor tag). The version code still comes from the upstream tag anchor until the owner pushes
  an own `v0.1.0` tag. Shown at the bottom of Settings (needed for bug reports and releases).
- **Not in round 1**: crypto-policy inheritance from folders (needs schema 22 and an explicit "inherit"
  value on the host), group colour picker, drag-to-reorder, creating a host directly inside the open
  folder. Fine-tuning candidates.

### Round 2: what was built and how (2026-11-16, branch `m7-vault-export`)

Status: written, **not compiled or run by me** (the VM has no Android SDK or Kotlin compiler; CI is the first
compile). Spotless and lint were not run either. Update this section after the CI run and the phone test.

**Vault** (`org.connectbot.shelldon.vault`, UI in `shelldon.ui.vault`)
- Required from first launch: the app shows "Create master password" before anything else, then an unlock
  screen on every start and after auto-lock. The shortcut picker is exempt (it shows no secrets). A
  `connectbot://` URI waits for the unlock.
- A random 256-bit data key (DEK) seals each secret with AES-256-GCM. The DEK is stored wrapped by a key
  derived from the master password with **Argon2id** (RFC 9106, v1.3; default 64 MiB, 3 passes, 1 lane; the
  password is NFC-normalised UTF-8, minimum 8 characters). The parameters live in the header file
  `shelldon-vault.hdr` (109 bytes, authenticated as GCM associated data, written atomically). Parameters read
  from a file are range-checked (8 to 256 MiB, 1 to 10 passes, 1 to 8 lanes), so a damaged or hostile header
  cannot make the app allocate gigabytes.
- Sealed values carry a context (`pubkey/<id>/private-key`, `password/<hostId>`) in the associated data, so a
  blob moved to another record refuses to open. What is sealed: private keys and saved passwords.
  Public keys, host names and the rest stay in the clear in Room, as before.
- Argon2id comes from BouncyCastle `bcprov-jdk18on` 1.85.2 (MIT-style licence, maven central, actively
  maintained; 1.85 (released 2026-07-28) fixed a long list of CVEs in earlier versions, so 1.85.2 is the floor). The answer to "does sshlib already bring it?"
  is no: sshlib copies a few BouncyCastle classes (sntrup, Poly1305) into its own package, so there is
  neither a clash nor an Argon2id to reuse. (Read in the sshlib sources at 0.6.10-SNAPSHOT, not in the 0.6.9 jar.)
  The implementation is checked against known-answer vectors made with an independent implementation
  (argon2-cffi) in `VaultTest`.
- **Biometric unlock** is a shortcut to the same DEK: an Android Keystore AES-GCM key (alias
  `shelldon_vault_biometric`, biometric-strong, per-use authentication, invalidated when fingerprints
  change, StrongBox when the device has it) wraps the DEK into `shelldon-vault.bio`. If the key is
  invalidated, the unlock screen says so, the master password still works, and the shortcut can be turned
  on again under Security.
  **Switched off only for a real loss** (2026-11-25): the system invalidated the key, the key is gone while
  the wrap file is still there, or the wrap file is damaged. Any other error when starting an unlock (the
  Keystore is busy, the device counts as locked, a vendor glitch) keeps the setup, shows "not available
  right now" and falls back to the password. Before, every such error deleted the key and the wrap and
  claimed the biometrics had changed. The unlock screen also reads what is set up each time it appears and
  shows prompt errors (too many attempts, sensor not available); choosing the password or leaving the app
  shows nothing. Verified: unit tests for the error policy and the view model. Not verified: on a phone,
  and the real Keystore (it cannot run on the JVM).
- **Auto-lock**: after 0, 1, 5, 15 or 60 minutes in the background (default 5; there is no "never"). Locking
  zeroes the DEK. A connection that is already open keeps working because it holds what it needs; a new
  connection that needs a key or a saved password fails while locked.
- **Migration**: the first unlock after the update seals the plaintext private keys in Room and moves
  passwords out of the old Android-Keystore store into the vault. Remnants of the old plaintext may stay in
  free database pages or flash; this cannot be cleaned from app code. Changing the master password
  re-wraps the DEK only (no re-encryption of the data).
- **Forgotten password**: the only way out is "Reset vault", which deletes the saved passwords and the
  stored private keys (the key entries are removed; hosts and everything else stay). That is the price of
  no backdoor.
- **Cloud backup**: found and fixed in this round. The upstream manifest still had `allowBackup="true"` with
  ConnectBot's backup agent, against our rule. The agent left private keys out unless the "Backup pubkeys"
  setting was on (default off), but it did back up the host database. Builds up to and including round 1 are
  affected. The agent and the setting are gone. `BackupFilter.kt` and its tests are dead code left for a
  later clean-up (upstream merges).

**Encrypted backup and restore** (`shelldon.backup`, "Settings > Security, backup and import")
- File format `SHBK`: magic(4) version(1) Argon2id memory(4) passes(4) lanes(4) salt(16) nonce(12), then
  the AES-256-GCM ciphertext; the first 45 bytes are the associated data. It uses its **own backup
  password** (minimum 8, default 64 MiB and 4 passes), not the vault password, so a backup opens on any
  install. Maximum file size 64 MiB.
- Payload (JSON): the existing host export (hosts, port forwards, profiles, keyboard), host links (jump
  hosts), folders, tags with their host assignments, identities, and optionally private keys (only those
  that can be exported, not hardware-backed ones) and saved passwords (off by default).
- Restore is **merge only**: hosts are matched by nickname, keys by nickname, identities and tags by
  lower-case name, folders by parent and name; what exists is left alone, everything new gets fresh random
  ids, and links, tags and passwords attach only to hosts that the restore itself created. One database
  transaction, so a failure leaves nothing half-imported. A wrong password and a damaged file look the same
  by design (authenticated encryption cannot tell them apart).
- `~/.ssh/config` import (`shelldon.sshconfig`): `Host`, `HostName`, `User`, `Port`, `ProxyJump`; first value
  wins in file order as in OpenSSH; wildcard and negated patterns act as defaults for the hosts after them;
  `Match` and `Include` are reported and not evaluated; options that are read but not imported are listed.
  Only adds hosts (existing nicknames are skipped); a single-hop `ProxyJump` becomes a jump-host link when
  the named host exists in the file or in the app.

**OpenSSH container tests** (`org.connectbot.shelldon.container`, M3)
- The library already runs old OpenSSH (3.7 to 7.4) and current releases in its own CI, so these tests do
  not repeat that. They drive the app's stored presets (`HostCryptoConfig.resolve()`) and the same client
  calls as the SSH transport against a throw-away Debian 13 `openssh-server` in Docker (Docker CLI, no
  Testcontainers dependency; loopback only; host keys checked against the real server, no accept-all
  verifier): stock server negotiates ML-KEM; ML-KEM-only; sntrup-only; classical-only (default connects,
  FUTURE refuses); SHA-1-only legacy server (DEFAULT refuses, LEGACY connects); the server log shows our
  identification line.
- The policy tests run on the JDK's own crypto providers: all test classes share one JVM and Robolectric
  leaves Conscrypt first in the provider list, which broke ChaCha20-Poly1305 on the JVM (see "ChaCha20 and
  Conscrypt" under "Known limits and risks"). Two more tests connect with Conscrypt first, as
  `ProviderLoader` does at app start; the AES-GCM one passes, the ChaCha20 one is `@Ignore`d: it fails
  on the JVM only.
- Off unless `SHELLDON_CONTAINER_TESTS=1`; with it set, a missing Docker fails instead of skipping. The
  pull-request workflow sets it. `CryptoDefaultsTest` (no Docker) fails when a library update puts a weak
  algorithm into DEFAULT or stops preferring post-quantum key exchange.
- The SSH identification line was the library's `SSH-2.0-CBSSH_1.0` (names the fork origin). It is now
  `SSH-2.0-ShelldonSSH`, without a version, set in both client configs (`ShelldonSshIdentity`).

**Mosh**: the APK does include it (`libmoshexec.so` for four ABIs plus the `xterm-256color` terminfo),
downloaded at build time from `connectbot/mosh4android` release `android-2026.11.12`. The build never
checked those downloads, and `mosh-client` is native code that runs under this app's user id. The four
SHA-256 hashes are now pinned in `gradle/mosh4android.sha256`; the build fails on a mismatch or when the
file is for a different release than `gradle/mosh4android.version`. The hashes are trust on first use: they
match what CI had built into the APK (same file sizes), not a signature from upstream. The build-logic
tests for this (`./gradlew -p build-logic test`) are not run by our CI.

**Known limits and risks**
- **ChaCha20 and Conscrypt: fine on the phone, broken only on a JVM (settled 2026-11-16).** The oss
  flavour's `ProviderLoader` (identical to upstream ConnectBot) inserts Conscrypt at provider position 1,
  and `chacha20-poly1305@openssh.com` is sshlib's first-choice cipher. On a plain JVM with Conscrypt first
  and the JDK providers behind it, sshlib 0.6.9 finishes the key exchange against real OpenSSH 10.0 and
  then fails both ways ("ChaCha20-Poly1305 authentication failed" in the client, "Bad packet length" in
  sshd); AES-GCM works. Likely cause, from reading the sources: sshlib decides once whether to pass a
  `ChaCha20ParameterSpec` by test-initialising a cipher in decrypt mode; Conscrypt refuses that, so the
  JDK's provider answers and the test passes; the real ciphers are then first initialised in encrypt
  mode, which Conscrypt accepts while ignoring the spec and using a random nonce. **On the owner's phone
  (Android 17, CI debug APK) two different servers connected and "Connection info" reports
  `chacha20-poly1305@openssh.com` for both**, so Android does not hit it (no JDK providers behind
  Conscrypt there, which fits the cause above). The probe test
  `chaCha20Poly1305WorksWithTheProviderOrderTheAppInstalls` stays `@Ignore`d because a JVM cannot pass it;
  it would matter again if a provider that offers ChaCha20 were ever added behind Conscrypt on Android, or
  if sshlib changes how it picks the parameter style.
- `ShelldonBackup.restore` nests a blocking `runInTransaction` inside `withTransaction`; the Robolectric
  test `ShelldonBackupTest` exercises it and passed in CI (2026-11-16).
- BouncyCastle on minSdk 24 to 27 could clash with the platform's own `org.bouncycastle`; the target phone
  runs Android 17. Check before raising the audience.
- Identity passwords are not stored (the identity editor still has no password field).
- Tried on the phone: SSH to two servers (ChaCha20-Poly1305). Not tried: biometric prompt, auto-lock
  timing, the SAF file pickers, backup export and restore, ssh_config import.

### Round 3: design (2026-11-16, branch `m8-sftp-snippets-tunnels`)

Items 1 to 3 of round 3 in one pull request: SFTP browser, Tunnels tab, Snippets. WebDAV sync is a
separate later pull request. No new dependency, no new Android permission, **no database change**.

Existing apps looked at: Termius (snippets, port-forwarding list, SFTP pane), Termux and ConnectBot
itself (port forwards), Material Files and Solid Explorer (file-list behaviour), OpenSSH `sftp` (short
reads, pipelined requests). What we take and what we drop:

- **Snippets are the existing keyboard macros.** ConnectBot already has `keyboard_macros` (name plus a
  versioned document), a runner (`TerminalBridge.sendKeyboardMacro`), usage tracking, and the macros are
  already inside the config export that the encrypted backup carries. A second snippet table would be
  two stores for one thing. The Snippets tab lists the macros that consist of text only (key presses and
  raw bytes stay in the keyboard settings, where the keyboard defaults live) and edits them as plain
  multi-line text: a line break is sent as Enter (CR). Dropped from Termius: variables and "run on many
  hosts at once" (one mis-tap on a fleet is not worth it).
  - A snippet with more than one line asks for confirmation, showing the text, before it is sent. A
    single line runs on tap. This is a deliberate UX versus safety call: scripts are where a mis-tap hurts.
  - Deleting a snippet that a keyboard button or automation still uses is refused, naming the user.
  - Snippets are stored in the clear, like every macro. The editor says so: no passwords in snippets.
    Sealing them in the vault is possible later (`Vault.sealText`), at the cost of a schema change.
- **SFTP browser** is a screen per open session (`sftp/{hostId}`), reached from the console menu, on the
  same SSH connection (a second channel, not a second login). `AbsTransport.openSftp()` is the only
  upstream change; `SSH` answers it from its `SshClient`. Telnet and Mosh answer "not supported".
  - Pure core, no Android types, unit-tested with an in-memory fake `SftpClient`: path handling,
    listing (sorted, folders first, hidden files off by default, entries with `/` or NUL in the name are
    dropped, so a hostile server cannot make us build a path outside the folder), pipelined download and
    upload (window of 8 requests of 32 KiB, short reads handled, truncated or growing files detected),
    recursive delete.
  - Download goes to a file the user picks (Storage Access Framework), upload reads files the user picks.
    That is why no storage permission is needed. Uploads never overwrite silently: an existing name is asked about.
  - Transfers run while the app is open and the session is up; there is no background service and no
    resume. Said plainly in the UI. Dropped: editing remote files in place, folder download, chmod
    editor (permissions are shown, not edited): candidates for fine-tuning.
- **Tunnels tab** is one list of every port forward across hosts, grouped by host, with state (running,
  stopped, host not connected) and a switch that starts or stops a forward on a connected host, using the
  existing transport calls. Tapping a forward opens the existing editor; "New" asks for the host first.
  Forwards still live and die with their SSH session (`ssh -N` style tunnel-only sessions would need a
  transport change and are not part of this round). Binding is unchanged and, as found on 2026-11-17
  reading `SSH.enablePortForward`, stricter than first written here: **`-L` and `-D` always bind to the
  phone's loopback**, the editor offers a bind address only for `-R` (where it is the address on the
  server). So the "exposed" badge can only appear on a `-R` forward, and a SOCKS proxy cannot be shared
  with other devices on the Wi-Fi. A per-forward "listen on all interfaces" switch (the SSH library has
  the `bindAddress` overloads, and a SOCKS5 password for `-D`) is a fine-tuning candidate.
- The bottom bar goes from three to five tabs: Hosts, Keychain, Snippets, Tunnels, Settings (the order of
  the design canvas).

What was built (all in `org.connectbot.shelldon.*`, upstream files touched only for wiring):

- `sftp/`: `SftpPaths`, `SftpBrowser`, `SftpTransfers`, `SftpFailure` (a closed list of problems the UI
  can word), `SftpModel`. `ui/sftp/`: view model, list rows, screen. Reached from the console menu
  ("Files") when the transport can open SFTP; one transfer at a time, cancelled when the screen is left.
  Uploads use `EXCLUDE` so an existing file is never replaced without the user saying yes; a half-written
  new file is removed when an upload fails. Recursive delete asks first and never follows a symlink.
- `snippets/` and `ui/snippets/`: Snippets tab (list, search, editor) and a snippet picker in the console
  menu. Multi-line snippets show the text and ask before sending.
- `tunnels/` and `ui/tunnels/`: Tunnels tab. A `-R` forward whose server-side bind address is not loopback
  carries an "exposed" badge.
- Wiring: `AbsTransport.canOpenSftp()/openSftp()` (SSH implements, Mosh says no), `PortForwardDao.observeAll()`,
  `HostRepository.observeAllPortForwards()`, five-tab bottom bar, three new routes.

How far this is checked (say which claim you are reading):

- **Pure cores, unit tests passed on the VM** with a hand-made harness (Kotlin compiler, JUnit and assertj
  shims, no Gradle): 53 tests for paths, listing, browser, transfers (against an in-memory fake
  `SftpClient`) and snippet text; 9 for the tunnel board (run earlier, code unchanged since).
- **Not run anywhere yet:** `SftpContainerTest` (real OpenSSH `sftp-server`, checks file content with
  `sha256sum` on the server). It runs in CI with `SHELLDON_CONTAINER_TESTS=1`; there is no Docker daemon
  on the VM.
- **Not compiled yet:** all Compose, Hilt and navigation code and the upstream wiring. Gradle does not run
  on the VM; the first real compile is the CI run. Style (`ktlint`) and a name-resolution check were run by hand.
- **Not run:** `spotlessCheck`, `lint`, the Room schema check (no schema change, so none expected).
- **Not tried on the phone.** Throughput and odd read limits on Cisco, MikroTik or BSD servers are untested.

### Round 3: design check against the canvas (2026-11-17)

The owner compared the new screens with the "phone UI redesign" canvas (Hosts, Group, First launch,
Keychain boards) and found the Keychain without its three tabs. What differed, and what was done on
branch `m8-sftp-snippets-tunnels`:

- **Keychain**: now Identities | Keys | Known hosts as a segmented control, a vault card (state, auto-lock,
  biometric, Lock), a "New" button, colour tiles, mono `user · key` lines, "Used by N hosts" and "Hardware
  key" badges, and "added 3 Mar" on keys. The separate Known hosts screen and its route are gone; its list
  is the third tab.
- **Snippets, Tunnels**: the canvas has no board for them, so they follow its language: "New" button,
  pill search, tile rows with a mono line and badges, the same empty state as Hosts. A tunnel's type and
  its "exposed" warning are badges; a host that is not connected shows its switches off and disabled.
- **Bottom bar**: order fixed (see above).
- Shared pieces live in `ui/components/ShelldonComponents.kt`. The Hosts screen still has its own copies
  from before that file; moving it over is a clean-up for later.

Differences from the canvas that are on purpose:

- **No "auto-locks in 4:32" countdown.** The vault locks after N minutes away from the app, not on a timer
  while it is open, so the card says "Auto-lock · After 5 minutes away".
- **No password identities** ("root · password" in the canvas). Identity passwords need a schema change and
  vault sealing and were left out of round 2. Still a fine-tuning candidate, and the owner's call.
- Segments are 40 dp high, not 36, for the touch target.
- **Adding a key.** The first cut of the parity pass sent "New" on the Keys tab to the old Pubkeys screen,
  which has its own "+" menu: three steps to add a key, which the owner rightly called out. "New" (and the
  empty-state button) now opens a bottom sheet with the five ways to add a key (generate, file, clipboard,
  FIDO2, YubiKey PIV). It runs on the same `PubkeyListViewModel` and import dialogs as the old screen, so
  a key imported here behaves identically. Copy, export, delete and load/unload still live on the old
  Pubkeys screen, now reached through "Manage" on the Keys list. Moving those into the rows (and retiring
  that screen, as was done for Known hosts) is a fine-tuning candidate.

Not verified: none of this is compiled or seen on a phone yet. ktlint is clean and a name-resolution check
was run by hand; the CI run is the first compile.

### Running commands on servers, and "install key on server" (2026-11-17, branch `m10-key-install`)

Owner request after testing round 3 on the phone: keys and identities work, but there is no way to put a
key into `authorized_keys` on a server (Termius: "export key to host"; OpenSSH: `ssh-copy-id`). Second
requirement, same evening: "run a command (or snippet) on a server or a group of servers" is needed by
later features too, for example simple monitoring or data collection that shows the output of commands or
files. So the command runner is a general building block and key install is its first user.

**Exec layer** (`org.connectbot.shelldon.exec`, no UI):

- `SshExec.run(client, command, limits)` opens one session channel, sends an exec request, reads standard
  output and standard error at the same time (so neither can block the other), and returns `ExecResult`:
  both streams, exit code or signal name, truncation flags, duration. Output beyond `maxOutputBytes` per
  stream (default 256 KiB) is read and thrown away; the command is abandoned after `timeoutMillis` (default
  30 s). The channel is always closed, also on cancellation.
- `FanOut.run(targets, concurrency, onUpdate, execute)` runs one operation over many servers, at most N at
  a time, results in the original order, one server failing never stops the others. This is what "run on a
  group" and a monitoring refresh will use.
- `ExecOutcome` separates "ran" (`Finished`, even with a non-zero exit code), "could not run at all"
  (`Unavailable`: no live connection, Telnet, Mosh, local shell) and "failed" (`Failed`: refused, timed out,
  connection broke), so a screen can say which.
- Hook: `AbsTransport.canExecCommand()` / `execCommand()` (default: no), overridden in `SSH`. Two small
  additions to upstream files, nothing else touched.

**Why it runs on an already-open session.** Same as Tunnels, Snippets and SFTP: the login, the host-key
check (a changed key blocks), and every prompt already happened in the console session. The exec layer
adds no second login path, so it cannot bypass any of that.

**Key install** (`org.connectbot.shelldon.keyinstall`, `ui/keychain/KeyInstall*`):

- The command is one line, `sh -c '...'`, with no single quote, backtick, `!` or backslash in it, so bash,
  zsh, fish, csh and BusyBox login shells all read it the same. Everything that goes into it comes from
  strict alphabets: key type `[a-z0-9@.-]`, the base64 blob, and a comment cut down to `[A-Za-z0-9._@+-]`
  (a hostile key name cannot add a second line or run anything). The blob's own type must equal the
  declared type.
- It is idempotent (same key under another comment, or with options, counts as present), adds a newline
  first if the file does not end in one, creates `~/.ssh` and the file with `umask 077`, and removes
  group/other write permission. It never edits or removes an existing line.
- Success means a marker alone on a line of the output. An echoed command, an error or a shell that does
  not understand `sh -c` is reported as "the server did not confirm", never as success.
- The dialog shows key name, SHA256 fingerprint and server name and asks before it writes anything.
- Entry points: "Install on server…" in the menu of a key card and of an identity card (Keychain), which
  lists SSH servers (connected: Install; not connected: Connect, which opens its console to log in first),
  and "Install a key here…" in the console menu, which lists keys. Keys without a usable public part (old
  imports of unknown type) say so and cannot be installed. FIDO2 keys are installed as `sk-…@openssh.com`.

**Lesson for unattended runs.** The SSH library hangs up by itself when its last channel closes
(`autoDisconnectOnLastChannelClose`, default true). The app turns that off for console sessions, so key
install on an open session is unaffected, but a background connection that runs several commands (a
monitoring refresh) must set it off on purpose and close the connection itself. The container tests missed
this at first: every second command on a test connection found it gone.

**Decided by the owner (2026-11-19): unattended runs are opt-in, saved key only.** Installing a key or
running a command on a server that is not connected, and anything on a whole group (monitoring,
collection), needs logins without prompts. The rules:

- Off by default. Switched on per host or per group, visibly.
- Only a saved key from the keychain is used. Vault passwords are never used in the background, so a
  stolen unlocked phone gives no more than the keys already on it.
- The host key must already be known. An unknown or a changed key is a hard failure with no prompt, and
  the failure is shown; there is no accept-and-continue path (same rule as everywhere else).
- Not built yet. Until it is, everything above runs on live sessions only.

Verified in CI: unit tests for the line, the script and its parser, the mapping from stored keys, and the
fan-out; OpenSSH container tests (`ExecContainerTest`, `KeyInstallContainerTest`) for exit codes, stderr,
signals, output limits, timeouts, concurrency, permissions, idempotence, a hostile nickname, and a file
without a trailing newline; Compose tests for both dialogs and the new menu items. Not verified: the
console menu entry has no UI test, and none of it has been tried on a phone.

### UX fixes from the owner's phone test (2026-11-17, branch `m11-ux-round`)

Found by using the merged round-3 build and key install on the phone:

- **Key install did not survive the login.** Tapping Connect on a server that was not connected closed the
  dialog and the request was forgotten, so after logging in nothing was installed. Now the key and server ids
  are remembered (`PendingKeyInstall`, ids only, runs out after 15 minutes) and the console, once the login is
  through, asks "Install this key?" with the fingerprint and reports the result. The confirmation stays: it is
  the only point where the user sees which key is about to be allowed to log in.
- **Hosts: New** now asks "New host" or "New folder" instead of always opening the host editor, and the
  "+ Folder" chip under the search field is gone (it was easy to hit by accident and sat in the wrong place).
- **Hosts: search hint** says "Search, or quick connect user@host", which is what typing an address does.
- **Saving a session** (an active quick-connect session or an entry under Last sessions) used to store it at
  once. It now asks for the folder in a small dialog that starts on "No folder", lists the existing folders by
  full path and can create a new top-level folder in the same step. A refused folder name (taken, too long)
  keeps the dialog open and says why; nothing is stored until Save.
- **Keychain > Known hosts** has the same pill search as Hosts, with a clear button. It matches host and port,
  key fingerprint (paste a `SHA256:` value to find the host that presented it) and key type. A real
  `known_hosts` file can hold a thousand entries, which was unusable without it.

Phone-tested by the owner on 2026-11-19 (merged as PR #13): works, and the "another bugs" items he had
noticed were already fixed by it. The console and the save dialog are also covered by Compose tests.

### Onboarding: import from `~/.ssh` (2026-11-19, branch `m12-ssh-import`)

Owner request: moving in from other tools must be easy ("call it onboarding"). His own set-up is an
`ssh_config`, `known_hosts` and key files on a PC, so that comes first; other apps that export CSV and
the like come after. A real profile of his (counts only, no content was shared) shaped the design: 57 `Host`
blocks, 12 `IdentityFile`, 18 `KexAlgorithms`, 13 `HostKeyAlgorithms`, 11 `Ciphers`, 4 `StrictHostKeyChecking`,
1 `UserKnownHostsFile`; no `Include`, `ProxyJump`, forwards or `Match`. `known_hosts` has 1060 lines, none
hashed. The files themselves were not sent, and should never be: they hold private keys and a map of his
network.

**Flow.** One screen, "Import from ~/.ssh", reached from Security, the Hosts overflow menu and the empty Hosts
screen. Pick any mix of files with the system picker (config, known_hosts, private and public keys). The app
recognises each file by its content, not its name, shows a preview, and writes nothing until Import. The
write is one database transaction, so a failure leaves nothing half done. Files stay in memory only and are
dropped, with any passphrases typed, when the screen is left.

**What is taken from `ssh_config`:** host blocks (first value wins, as in OpenSSH), `HostName`, `User`, `Port`,
`IdentityFile` (collected across blocks), `PubkeyAuthentication`, a single-hop `ProxyJump` (by alias or
address), and the algorithm lists. Not followed: `Include` (import the included file on its own) and `Match`.
Everything else is listed as "read but not imported".

**Algorithm lists become the per-host crypto policy**, the same visible per-host setting as the editor, never
a global change. `+a,b` is rewritten to the editor's `+a,+b`. A name the crypto policy does not know is
dropped and named in the preview instead of failing the whole host. Lists that only came from a `Host *`
block are off by default, so one weak default cannot spread to every server; an option turns them on.
Entries that are older algorithms (SHA-1, CBC, 3DES, ssh-rsa and so on) are marked as older. The Diffie-Hellman
minimum is lowered to 1024 bits only on a host whose list actually names group1 or group-exchange-sha1.

**Refused on purpose.** `StrictHostKeyChecking no/off/accept-new` and `UserKnownHostsFile /dev/null` are not
imported. The host is, the setting is not, and the preview says so: host keys are always checked and a
changed key blocks the connection, legacy hosts included.

**Keys.** Private keys are decoded (a passphrase field appears for encrypted ones, wrong passphrases are told
apart from unreadable files), compared with the keychain by public key so nothing is added twice, stored
through the vault (so it must be unlocked to import them), and linked to the hosts that name them in
`IdentityFile`. A host whose key file was not picked is shown as "key missing".

**`known_hosts`.** Entries are matched to hosts by name and port, including `[host]:port`, wildcards, `!`
negation and hashed `|1|` entries. Because a known key belongs to a saved host here, a line is imported only
if it matches a saved or newly imported host; the rest is counted as "no matching host", and importing again
after adding the host picks it up. An entry that differs from a key already stored is a conflict and is never
overwritten (a changed key is the warning, not something an import may fix). Two different keys for one host
and key type are reported as ambiguous and skipped. `@cert-authority` and `@revoked` lines are counted and
not imported.

**Smaller things in the same branch:** a Files action in the host card menu (enabled while that host is
connected, it opens the SFTP browser).

**Dependency clean-up.** Removed `media3-common-ktx` (no source used it; it was also what brought Guava into
the APK) and a duplicated `reorderable` line; `navigation-testing` moved from the app to the test classpath,
where it belongs. Removing them changes what Gradle resolves, so the first CI run failed dependency
verification on six artifacts (newer `savedstate` versions, and `listenablefuture` 1.0 now that Guava no
longer replaces it). The `Dependency metadata` workflow was run on the branch and its output committed
after reading the diff: only additions, all from Google Maven and Maven Central. The Material View
library (`com.google.android.material`) is not used by any source or layout, but it could **not** be
removed: without the explicit pin some other dependency's request for `material:1.2.0-rc01` wins and an old
release candidate (with `transition`, `customview`, `drawerlayout`) would ship instead. The security job's
"who brings each library in" listing shows who asks: `org.connectbot:termlib` 0.3.15, ConnectBot's terminal
library. So the pin stays, with a comment, until termlib stops asking (a newer termlib, or a change
upstream). The checksums for that old chain were left out of the file on purpose. With media3 gone, the
scan shows 231 shipped libraries (239 before) and no Guava, only the empty `listenablefuture` 1.0 marker, so
the Guava floor in `app/build.gradle.kts` and its version entry were deleted. Version
bumps were not done: each one needs the same workflow run, and a monthly batch is the plan.

**Not built yet (next onboarding steps):** CSV import with a column mapping, then PuTTY, MobaXterm,
SecureCRT and mRemoteNG; export to `ssh_config` and CSV; per-host checkboxes in the preview; `Include`
following; port forwards from `LocalForward` and friends; the old Keychain keys screen still exists for key
export, YubiKey and FIDO2.

**Verified.** In CI (pull request #14): compile, 1931 unit tests including the new parser, `known_hosts`,
planner, importer (Room under Robolectric), real policy validation and key decoding, and the Compose tests
for the import screen and the host list; Spotless/ktlint; Android lint; gitleaks; OSV. CI found two real
problems on the way, both fixed: a `known_hosts` file made mostly of `@cert-authority` or `@revoked` lines
was not recognised, and test fixtures that spelled out a key header tripped the secret scan (the markers are
built at run time now, in `TestPem`). **Not verified:** nothing has been tried on a phone, and the import
has not been run against the owner's real files. No OpenSSH container test is involved, because nothing in
`transport/` or the crypto code changed; the algorithm lists go through the existing policy validator.

### Tools section (noted 2026-11-20, not built)

Owner request: a Tools section in the app, with **ping** and **nslookup** as the first tools. This is a note
for later, not a design that was decided; nothing here exists yet.

**Existing apps, read first (not tried).** HostLookup (F-Droid) queries 25 record types, compares public
resolvers over UDP, TCP, DNS-over-TLS and DNS-over-HTTPS, and adds WHOIS and network info; its resolver list
is fixed. Vernet has ping and a host and port scanner. Android Ping Utils has ping and dig. The
`android-network-tools` library behind the "Network Tools Library" app (Apache-2.0) has ping, port scan and
Wake-on-LAN. Two lessons: OG Network Tools does its DNS lookups through a Google web API, which F-Droid flags
as a non-free network dependency, and NextTraceroute needs the NextTrace web API. We send lookups to no
third-party service. The licences of HostLookup, Vernet and Ping Utils were not checked, so no code is taken
from them.

**First slice.**
- *Ping.* Apps cannot open raw ICMP sockets. Plan: run the system `ping` binary as a child process (no root
  needed on stock Android; its output has to be parsed, and it must be checked on the Pixel and on Android
  17), with a TCP connect to a chosen port as the fallback, which also tells open, refused and timed out
  apart. Shows each reply, min/avg/max and loss. Count and interval are capped, so it cannot be used as a
  flood tool.
- *nslookup.* Android has no `nslookup` binary. Plan: the system resolver through `DnsResolver` (API 29 and
  newer; A, AAAA and other record types, follows Private DNS), and plain `InetAddress` (A and AAAA only) on
  older devices. Asking a chosen server (`nslookup name 1.1.1.1`) or using DoT/DoH needs a DNS client of our
  own: a small hand-written one for plain UDP/TCP queries, or a library (dnsjava is a candidate, BSD-licensed,
  to be checked). That is a dependency, so the owner is asked first. The default is always the system
  resolver, and the result says which server answered.

**How it ties in.** A host card gets Ping and Resolve with the address prefilled. Later, "check this group":
a plain TCP connect to each host's SSH port, run in parallel with the existing `FanOut`, shows who is
reachable. It never logs in, so the decision on unattended logins (saved key only) is not involved.

**Later candidates, not committed:** TCP port check, Wake-on-LAN (one UDP packet), TLS certificate expiry and
fingerprint check, SSH host key fingerprint of a server (like `ssh-keyscan`, using the existing SSH code),
WHOIS (port 43, the query goes out unencrypted), own addresses and DNS servers. Traceroute is hard without
raw sockets (the "time exceeded" replies cannot be read), so it probably stays out.

**Rules that apply:** no new permission (`INTERNET` and `ACCESS_NETWORK_STATE` are declared; asking before
any other); no telemetry; no history saved by default, results live on the screen with a copy button; host
names and addresses are validated before they reach a process or a socket; the screen says when a query
travels unencrypted (plain DNS, WHOIS).

**Where it would live:** a Tools destination next to Tunnels and Snippets (placement is open), code in
`org.connectbot.shelldon.tools`, each tool a small unit with a pure Kotlin core (the `ping` output parser, DNS
message encode and decode) covered by unit tests. If a DNS client of our own is written, it also gets a test
against a real DNS server in a container.

**Open questions for the owner:** where the Tools entry sits in the navigation; whether the first slice may
be system resolver only, with a chosen server, DoT and DoH as a second step.

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

## Quality gates (added 2026-11-17)

What runs on every change, and what each gate does and does not prove. "Wired" means the workflow
exists; whether it is green is shown by the CI run, not by this file.

| Gate | Workflow | Runs on | What it checks |
| --- | --- | --- | --- |
| Build, unit and container tests, Room schema | `build.yml` | PR, push to `main`, manual (not docs-only) | It compiles, the tests pass, the Room schema JSON matches the generated code |
| Spotless and ktlint | `checks.yml` job `style` | PR, push to `main`, manual (not docs-only) | ktlint 1.8.0 with the Compose rules over all Kotlin including `org.connectbot.shelldon`, licence headers, whitespace. CI passes `-PspotlessFull`, so it checks every file, not only those changed since `main` |
| Android lint | `checks.yml` job `lint` | PR and manual (not docs-only) | `:app:lintOssDebug`; the findings are printed in the log and attached |
| Secret scan | `security.yml` job `secrets` | every push and PR, also docs-only | gitleaks 8.30.1 over the full history; reviewed false positives are listed by fingerprint in `.gitleaksignore` |
| Known vulnerabilities | `security.yml` job `osv` | every push and PR, weekly | OSV-Scanner 2.5.1 over two lists. *Shipped*: the resolved release runtime classpath (what is inside the APK); fails the check, reviewed exceptions go in `osv-scanner.toml` with a reason and an expiry date. *Tooling*: everything in `gradle/verification-metadata.xml`, which also holds the Android Gradle plugin, lint, test and formatting tools; shown in the run summary as a warning, does not fail |
| Dependency checksums | Gradle itself | every build | Once `gradle/verification-metadata.xml` exists, Gradle refuses any library whose SHA-256 differs |
| Action updates | `.github/dependabot.yml` | weekly | Pull requests that bump the SHA-pinned actions, grouped into one |

Notes:
- gitleaks and OSV-Scanner are downloaded as release binaries and checked against a SHA-256 in the
  workflow. No third-party action runs in these jobs. gitleaks is in maintenance mode upstream
  (security fixes only; its author moved on to a successor), which is fine for a scan of this size;
  revisit if it stops receiving fixes.
- Gradle dependency bumps are **not** automated. They are done by hand in one monthly batch: change
  the version, run the `Dependency metadata` workflow on the branch, read the diff of
  `gradle/verification-metadata.xml`, commit it. That is why Dependabot is limited to GitHub Actions.
- First real audit, 2026-11-17 (OSV-Scanner over everything the build resolves: 821 components,
  15 advisories in 12 libraries, 2 critical and 7 high). Split by what ships in the APK, which is the
  release runtime classpath (239 libraries):
  - Shipped: 1 finding, Guava 33.3.1-android (GHSA-xxph-c9ww-hj94 / CVE-2026-102554, denial of
    service when deserializing a crafted Java stream). Not reachable in our code (no `ObjectInputStream`),
    fixed anyway by raising exactly that version to 33.7.2-android on the debug and release runtime
    classpaths. Guava came in through `androidx.media3:media3-common-ktx`, which no source file used. That
    dependency was removed on 2026-11-19 (branch `m12-ssh-import`) and the next OSV scan showed no Guava in
    the shipped libraries, so the floor was deleted. Fixed by removal.
  - Build tooling only, not in the APK: the other 14 (Bouncy Castle 1.80.2, Guava jre variants,
    freemarker, jdom2, jose4j, httpclient, logback, commons-lang3). They are not on the release runtime
    classpath, so they belong to the build, lint or test tools (which tool brings which one is not traced
    yet). They are listed in each run's summary as a warning and are fixed by updating the tool, in the
    monthly batch. Bouncy Castle 1.86 (ours, Argon2id) is not flagged.
  - Done from the earlier partial audit: Bouncy Castle 1.85.2 to 1.86. Open: `biometric-ktx` is on an alpha
  (`1.4.0-alpha02`) in the unlock path; beta or stable should be chosen before the first release.
- Trap for later: `build.yml` and `checks.yml` skip docs-only changes with `paths-ignore`. A
  branch-protection rule or ruleset that *requires* `Build APK` would then wait forever on a
  docs-only pull request, because the check never starts. Before requiring checks, either do not
  require them, or replace `paths-ignore` with a first job that detects docs-only changes and lets
  the required job finish at once.

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

## Going public (checked 2026-11-17, switched to public 2026-11-25)

The repository was made public on 2026-11-25. What was checked and what is still open.

**History and tree.** The history is ConnectBot's public history (3,400 commits) plus ours. Scanned
for key and keystore files, private-key headers, token patterns, the owner's email and domain, and
private addresses in our commits. Nothing real turned up: the only hits are test fixtures (fake keys,
`10.0.0.x` addresses in `ssh_config` tests) and a `tests/local.properties` that upstream committed in 2009 (an Android SDK path, since removed). This was
a manual scan; the quality PR adds a gitleaks job that scans the whole history in CI.

**Correction (2026-11-25).** The first version of this note said our commits carry only
`Claude <noreply@anthropic.com>`, so no personal address was exposed. That was wrong. The 14 "Merge pull
request" commits that GitHub made when the owner merged (11 of them on `main`) are authored with the
owner's personal address. Rewriting history would not remove it from a repository that is already
public (old commits stay reachable through pull request refs), and the repository went public as it was.
Merges made after the owner enables "Keep my email addresses private" in the GitHub settings use the
GitHub no-reply address.

**Done in the readiness PR (Markdown only, so CI is skipped):** README (status, badges, security
commitments, build steps including the upstream tag a fresh clone needs), CONTRIBUTING (was still
ConnectBot's text with ConnectBot's links), SECURITY.md, THIRD_PARTY.md, CHANGELOG section for Shelldon,
branding note.

**Open, the owner's call:**
1. **Icon licence.** AI-generated artwork; the repo has to say what licence it is under (options: CC0
   dedication, the same Apache-2.0, or all rights reserved for the artwork only). `branding/README.md` says
   "not yet decided".
2. **Mosh is GPL-3.0-or-later (OpenSSL exception).** It ships inside an Apache-2.0 app, as ConnectBot
   does. `THIRD_PARTY.md` states the facts and the source pointer. Needs a position before release; the
   fallback is a build without the Mosh module.
3. **Name and trademark.** One web search found no app called "Shelldon"; similar names exist
   (ShellDroid, Shellwave, Shelly). A search in the trademark registers (EUIPO, USPTO, WIPO) is still to do.
4. **Internal-key APKs.** Once public, CI artifacts of release runs are downloadable by anyone with a
   GitHub login, signed with the internal key. That is harmless in itself, but never publish them as a
   release: releases get the separate production key (see `docs/FDROID.md`).

**GitHub settings after the switch (the owner flips these; an assistant does not change repo settings):**
Settings, General: description, topics, social preview image (the turtle). Security: private
vulnerability reporting, Dependabot alerts and security updates, dependency graph with automatic
dependency submission, secret scanning with push protection, code scanning (CodeQL default setup).
Branch protection or a ruleset on `main`: require a pull request and the `Build APK` check, block force
pushes. Actions, General: fork pull request workflows from outside collaborators must need approval
(set it to "all outside collaborators"), and keep the default workflow token read-only.

**What public changes for CI.** Public repositories get free Actions minutes and, per the comment in
`build.yml`, larger runners than private ones. The memory workarounds in the workflow (low heap,
in-process Kotlin, swap) may then be unnecessary; check before removing them.

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
- **M7: snippets and SFTP browser.** Built in round 3 (PR `m8-sftp-snippets-tunnels`), with the Tunnels tab.
- **M8: export/import** (encrypted file, plus `~/.ssh/config` import). Encrypted backup is in round 2; the
  `~/.ssh` import is rebuilt as onboarding in M12.
- **M9: sync.** Backend interface, then WebDAV, then optional server.
- **M10: run commands, install key on server.** Exec layer, key install (merged, PR #12). Next on it: snippet
  "run on server or group and show the output", simple monitoring and data collection (see the open
  decision on unattended runs above, decided 2026-11-19: opt-in, saved key only).
- **M11: UX round** (PR #13, merged and phone-tested 2026-11-19).
- **M12: onboarding and cleanup** (branch `m12-ssh-import`). Import from `~/.ssh` (config, known_hosts,
  keys), Files action on the host card, dependency clean-up (merged, PR #14). Next: CSV and other apps'
  exports, then export.
- **M13: Tools section** (noted 2026-11-20, not started). Ping and nslookup first, see "Tools section".

## Open items

- User may find an old Cisco 2960/3750 for real-device legacy testing.
- ShellDroid is worth a second look if its licence is permissive.
- Fine-tuning candidates after round 3: background and resumable transfers, folder download, chmod editor,
  editing remote files in place. (The Files entry on the Hosts screen is done in M12.)
- Per-forward LAN bind: `-L` and `-D` always bind loopback, the editor offers a bind address only for `-R`.
  Needs a change in `transport/`, so it comes with container tests.
- Identity passwords in the vault (needs a database migration).
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
- https://f-droid.org/packages/de.obsp.hostlookup/ (HostLookup: DNS record types, resolver comparison)
- https://f-droid.org/en/packages/com.stealthcotper.networktools/ (Network Tools Library: ping, port scan)
- https://f-droid.org/en/packages/org.fsociety.vernet/ (Vernet: ping, host and port scanner)
- https://f-droid.org/en/packages/xyz.pisoj.og.nettools/ (OG Network Tools: DNS through a Google API)
