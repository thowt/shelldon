# Sync (WebDAV first, end-to-end encrypted) and agent alerts: plan

Status 2026-11-27. **Plan only; no code was written for this.** Written by an AI assistant for the
owner. Facts marked "from memory" were not checked in this session. "Reported" means a third-party
page said it and I did not find it in the vendor's own documentation. Decisions that are the owner's
are numbered D1 to D10 in section 12.

## 1. Short answer

- **You are right that sync is not built.** What exists is the database half that upstream shipped
  (change tracking tables and triggers), the encrypted backup file, the vault and an SFTP client (the folder picker helper is in an open pull request). There is no sync format, no network client, no merge code and no screen.
- **WebDAV is a good first network backend, with two corrections.** (1) Do not make correctness depend
  on the server: WebDAV servers differ in how they handle conditional writes, so the design detects
  and keeps clashing edits instead of relying on locks. (2) Build the format, encryption and merge
  first with no network at all, and ship a "folder" backend on top of it (so Syncthing, the Nextcloud
  app or any file-sync app can carry the encrypted files). WebDAV then becomes the second backend
  rather than a prerequisite.
- **Better or cheaper alternatives exist for part of the job:** SFTP (the app already has the client
  and the host-key checks, no new dependency), S3-compatible storage (strong conditional writes), a
  plain folder (zero network code). Section 4 compares them. None replaces WebDAV for reach: Nextcloud
  and most NAS boxes speak it.
- **Agent alerts ("Claude is idle or waiting for approval") should not ride on the sync service.**
  Sync is pull-based and must be dumb; alerts need push. Two cheap steps cover most of it with no
  server of ours: read terminal bells and OSC notification sequences inside the app, and use ntfy for
  sessions that are not connected. Section 10.
- **First thing to build, whichever way you decide:** the agent-alert step 1 (small, no dependency, no
  new permission) and sync step S1 (pure Kotlin, testable here).

## 2. Where we stand

| Piece | State |
| --- | --- |
| `data/sync/SyncDatabase.kt` (upstream) | Tables `sync_repositories`, `sync_outbox`, `sync_versions`, `sync_batches`, `sync_conflicts`, `sync_certificates`, `sync_control`, and triggers on `hosts`, `profiles`, `port_forwards`, `known_hosts`, `host_groups`, `tags`, `host_tags`, `identities`, `pubkeys` and more. Every row has a `repository_id` (`Local` by default). Writes land in `sync_outbox`; `sync_control.applying` switches the triggers off while remote changes are applied. **No network client exists in the tree.** |
| Vault | Done (round 2): Argon2id, AES-256-GCM, secrets sealed with a context in the associated data. |
| Encrypted backup `SHBK` | Done (round 2): own password, whole payload sealed, merge-only restore. |
| Crypto library | BouncyCastle `bcprov` is already a dependency: HKDF, AES-GCM, ChaCha20-Poly1305 and Argon2 are all in it. |
| JSON | `org.json` (Android), as the backup uses. |
| HTTP client | **None.** `HttpURLConnection` cannot send `PROPFIND` or `MKCOL` (from memory: it only accepts the basic methods), so WebDAV needs a new dependency (section 9). |
| XML | `hostimport/MiniXml` refuses DOCTYPE and entity declarations (safe against XXE) but reports no text content, so it needs a text-aware sibling for WebDAV answers. |
| SFTP | Client and browser exist. Resume, folder download, chmod and in-place editing are in open pull requests #22 to #25. |
| Local-network permission | `targetSdk` is 36, so apps keep implicit local-network access. Raising it to 37 means a runtime permission for servers on a LAN address (Android docs, section 14). Not needed for this plan. |

Found while reading, to settle in S2: upstream's triggers treat `hosts.pubkey_id` as a **local** field,
so "this host uses that key" may not travel between devices unless the link is turned into a portable
reference (key UUID or fingerprint).

## 3. Validating the WebDAV idea

**Verdict: sound, with caveats that shape the design.**

What supports it:
- Nextcloud and ownCloud (SabreDAV) honour `If-Match` and `If-None-Match` on PUT, so create-only and
  compare-and-swap writes work there. A SOGo bug report quotes SabreDAV rejecting a clashing
  `If-None-Match`.
- Joplin, Floccus and an Obsidian E2EE plugin all use WebDAV as a target for encrypted sync. The
  pattern is proven.
- It is the most common self-hosted storage protocol: Nextcloud, many NAS boxes, Apache and nginx
  modules, `rclone serve webdav`.

What argues for care:
- **Servers differ.** Go's `x/net/webdav` ignores conditional PUT headers (open issue). Joplin's
  maintainer notes that when a WebDAV server misbehaves, Joplin gets the bug reports. Floccus users hit
  `404` on PUT with some providers. Joplin's lock files broke with the Nextcloud end-to-end-encryption
  app (`MKCOL` locks error).
- **No change feed.** WebDAV can list a folder with ETags; it cannot say "what changed since X". Fine for
  a few hundred records, bad for tens of thousands.
- **Absence is dangerous.** Joplin's FAQ warns that pointing it at an empty location makes it think
  everything was deleted and delete locally. Floccus added a failsafe that refuses to delete more than half
  of the local bookmarks.
- **Setup friction.** URLs, app passwords, certificates: the usual failure is a wrong path, not a crypto bug.

Design rules that follow (these are the "bad features and stupidities to leave out"):
1. **No lock files.** Joplin's locks are a recurring source of errors. Our layout needs none: every
   write is a whole-object replace and clashes are detected afterwards (section 5.5).
2. **Conditional writes are an optimisation, not a dependency.** Setup runs a probe and shows whether
   the server supports them. Correctness holds either way.
3. **Absence never deletes.** Only a tombstone deletes. An empty or different server prompts; it never wipes.
4. **No plaintext mode.** Joplin's own write-up says making encryption a property of the target (so
   unencrypted data cannot reach an encrypted target) would be simpler and safer than a client setting.
   Ours is always on: one code path.
5. **HTTPS only.** No `http://`. A LAN server uses your own CA or a pinned certificate (D8). No accept-all.
6. **A compatibility matrix is part of the work**, tested in containers (section 8), not a hope.

## 4. Backends compared

| Backend | Strengths | Weaknesses | Fit |
| --- | --- | --- | --- |
| **Folder (Storage Access Framework)** | No network code. Works with Syncthing (Android app), the Nextcloud app, Dropsync-style tools, USB. Can reuse `SafTree` from the folder-download pull request (#24). No new permission. | Latency and reliability are the other app's. No push. File-sync conflict copies (`.sync-conflict`) must be tolerated, which our layout does. | **First.** Cheapest way to prove the format on real data. |
| **WebDAV** | Reach (Nextcloud, NAS, hosting). HTTPS through proxies. App passwords. Conditional writes on the common servers. | New HTTP dependency. Server quirks. No change feed. | **Second, as you asked.** The main network backend. |
| **SFTP** | The app already has the client, host-key pinning, keys and passwords. No dependency. Create-exclusive writes exist. You already own SSH servers. | Needs a Unix account on the server. Poor fit for a future public service. No overwrite-if-match (not needed by our design). | **Third, nearly free.** Possibly first if you want zero new dependencies (D1). |
| **S3-compatible** (Garage, MinIO, Backblaze B2, R2, AWS) | Strong conditional writes: AWS S3 added `If-None-Match` in Aug 2024 and `If-Match` in Nov 2024; MinIO supports both. Per-prefix keys. Cheap. | Needs SigV4 request signing (about 150 lines by hand, or a heavy SDK). Most users do not run S3. | On demand, after WebDAV. |
| **Git repository** | History, familiar. Floccus supports it. | Needs JGit (heavy) or a CLI; tokens on a phone; merges are text, not records. | No. |
| **Google Drive, Dropbox, OneDrive** | Many people have one. | Proprietary SDKs and OAuth, against the FOSS and no-Play-Services line. | No. |
| **Own server** (later) | Accounts, change cursor, push hints, quotas, a place for shared repositories. | We would run it and keep it patched. | Later, optional. The format is built so it can implement the same interface. |
| **Export only (today)** | Exists, simple, safe. | Manual. | Keep. A "back up to a remote folder" button is S0 below. |

Existing apps read for this plan: Joplin (per-item files, E2EE, locks, WebDAV and S3 targets), Floccus
(WebDAV with encryption, mass-delete failsafe), an Obsidian WebDAV E2EE plugin (ETag incremental sync, journal),
Syncthing (folder sync, conflict files). From memory only: KeePass2Android (database file over WebDAV or SFTP
with built-in merge), Bitwarden (server with end-to-end encrypted vault and revision dates), Termius (vault sync
behind an account). Haven has "own sync profiles" per the earlier audit; not read in depth.

## 5. Design

### 5.1 Principles

1. The server is untrusted and dumb: it stores named byte strings. Everything else happens on the phone.
2. Every stored byte is ciphertext. File names are random ids; kinds, names and fields are inside.
3. Never lose a user's edit. A clash keeps both versions until the user decides.
4. Never act on absence. Only an authenticated tombstone deletes.
5. Sync runs only while the vault is unlocked (section 5.7).
6. One format, versioned, so a future server and per-user accounts can speak it.

### 5.2 What syncs, and the defaults

| Category | Default | Why |
| --- | --- | --- |
| Hosts, groups, tags, host-tag links, port forwards, snippets, profiles and colour schemes | **On** | The point of sync. |
| Identities (name, user name, key reference) | **On** | No secret inside. |
| Private keys and saved passwords | **Off**, one switch per kind | If the sync passphrase and the server are ever both exposed, these are the loss. Hardware-backed keys never leave the device. |
| Known host keys | **Off** | A host key trusted on one device would silently become trusted on all. Each device decides its own trust. (D5) |
| Settings | Only the portable ones, **off** | Avoids surprise. |

### 5.3 Repository layout (on the server, under a folder the user picks)

```
shelldon-sync/
  repo.json            plain and tiny: format, repo id (random), epoch, shard count
  keys/epoch-1.key     the sync key, wrapped (KDF parameters, salt, AEAD)
  o/<s>/<object id>    one encrypted file per record: its latest version, or a tombstone
  d/<device id>        a device card: name, created, last sync, push counter
```

`<s>` is the first hex character of the id, so at most 16 folders. No locks. A device writes only its
own device card; objects are replaced as a whole.

Object file: `magic | version | epoch | salt(16) | nonce(12) | AEAD ciphertext`. The key is
`HKDF-SHA256(syncKey, salt, "shelldon-sync v1 object")`, so every version gets its own key and nonce
reuse cannot happen. The associated data binds magic, version, epoch, **repo id and object id**, so the
server cannot move an object to another name. The plaintext JSON holds `kind`, `id`, `rev`, `hlc`
(writer's hybrid logical clock), `dev`, `base` (the revision this version was derived from), a
`deleted` flag and the fields. Final choice of AES-GCM versus ChaCha20-Poly1305 and exact encodings
are fixed in S1 with test vectors (the vault already uses AES-256-GCM).

### 5.4 Keys and enrolment

- A random 256-bit **sync key** is made on the first device. It is stored on that device sealed by the
  vault, and on the server wrapped in `keys/epoch-N.key`.
- The wrapping key comes from either a **generated recovery code** (128 random bits shown once as
  groups of letters; no brute force possible) or a **user-chosen passphrase** through Argon2id with the
  same parameter limits as the vault header. Recommendation: the recovery code, with the passphrase as
  an option (D4). The code can be shown again from an unlocked device.
- Enrolling a new device: server URL, account, certificate trust, recovery code or passphrase. The
  device downloads the key file, unwraps it, and pulls.
- **Removing a device cannot take back a key it already has.** "Remove device" therefore offers
  **rotate key**: new epoch, new sync key, every object re-encrypted, old epoch deleted. Clients that
  see a newer epoch stop and ask to enrol again. Until rotation, a removed device could still read
  what it already downloaded; the screen says so.
- **Rollback** (the server returns an older valid version): each device remembers the highest `hlc` it
  has seen per object and the highest push counter per device card, ignores older data and shows a
  warning. A brand-new device cannot detect this; that limit is stated in the UI help.

### 5.5 Merge and conflicts

Per-record last-writer-wins on a **hybrid logical clock** (wall time, counter, device id; a clock far
in the future is clamped, as the task hub design does). Per-field merge is not worth it for one person
with two or three devices; revisit if conflicts turn out to be common.

On pull, for each remote object whose ETag differs from the stored one:

| Situation | Action |
| --- | --- |
| Local record unchanged since last sync | Apply the remote version. |
| Local changed, remote still equals the version we last synced (`base` matches) | Push ours. |
| Both changed | Higher `hlc` wins; the **loser is kept whole** in a conflicts list (sealed with the vault if it holds secrets). The user sees "N conflicts", never a popup. |
| Remote is a tombstone, local was edited later | The edit wins and is pushed again; otherwise delete locally and keep a conflict copy. |
| Local record was synced before, now missing remotely, no tombstone | **Ask.** Default is keep. This covers tombstones removed after their retention (default 180 days) and a server that lost data. Never auto-delete, never auto-upload to a server that looks reset (`repo.json` id differs). |

Because `base` is in every version, **a clobbered write is detected even on a server that ignores
`If-Match`**: if the object now has a version whose `base` is not the one this device wrote, our write
was overwritten, and the content we still hold becomes a conflict copy.

Safeguards: refuse a pull that would delete more than 30 % of local records or more than 20 of them
without a confirmation (Floccus and Joplin lesson); first sync with data on both sides shows a
preview ("this device has 41 hosts, the server has 38; 30 match") and asks merge, replace local, or
replace server.

Invariants that remote data must not break, with tests: group depth at most 16, no loops, sibling
names unique ignoring case, references to missing identities or groups count as unset. Apply remote
records in dependency order and run them through the same rules as local edits; a record that violates
a rule is renamed (as the group delete flow does) or kept as a conflict copy.

Changes that arrive from another device and alter **where a connection goes or who it uses** (address,
port, user, jump host, identity) are listed once in a "Changes from sync" digest with before and after.
One compromised device otherwise becomes a way to point every other device at an attacker's host.

### 5.6 Transport and credentials

- WebDAV calls: `OPTIONS` (probe), `PROPFIND` depth 1 (list with `getetag`, size), `GET`, `PUT` with
  `If-None-Match: *` for new objects and `If-Match` for replacements, `DELETE`, `MKCOL`.
- Setup probe checks: connection and certificate, authentication, write and read back, create-only,
  conditional replace, and whether directory ETags change with their children (an optimisation that
  lets a pull skip unchanged shards). The result is shown, not hidden.
- **HTTPS only. Trust is the system store, or a per-profile imported CA, or a pinned certificate
  (SPKI).** Redirects to another host or to HTTP are refused. Never accept-all.
- Credentials (user name plus app password) are stored sealed in the vault with a context in the
  associated data, like saved passwords. Never logged, never in error text.
- Limits: object size 1 MiB, 20 000 objects, strict decode, anything malformed is skipped and counted,
  not guessed at.
- Errors map to a short list the user can act on (wrong password, certificate changed, server does not
  allow overwrite, repository not found, newer format), not to raw HTTP text.

### 5.7 When sync runs

**Only while the vault is unlocked**, on app start and resume, after local changes (debounced), and on
"Sync now". No periodic background sync in the first version.

This is a UX-versus-security trade: background sync needs the sync key while the vault is locked, which
means keeping it readable without the master password. I recommend against that. The cost is that a change
made on the tablet shows up on the phone the next time you open Shelldon, not before. For host lists that
is acceptable. (D6)

### 5.8 Mapping to what upstream already has

| Upstream | Use |
| --- | --- |
| `sync_repositories` | One row per sync target (`server` = URL, `account_id` = user, `remote_id` = repo id, `role` = admin). Rows of the Local repository stay local. |
| `repository_id` on records | Which records belong to the synced repository. Moving into it is one transaction with `applying` set; a "never sync this host" flag keeps a host in Local. |
| `sync_outbox` | The dirty set; `generation` detects an edit made during an in-flight push. |
| `sync_versions` | Last synced revision per object. |
| `sync_batches` | A push that was interrupted, so it can be resumed or retried safely. |
| `sync_control.applying` | Suppresses triggers while remote changes are applied, so they do not echo back. |
| `sync_conflicts` | One row per repository in upstream. **Not enough** for per-record conflict copies; we add our own table in `org.connectbot.shelldon.sync`, not in upstream files. |
| ETag per object | Not stored upstream. Our own small table, so upstream schemas stay untouched. |

Code lives in `org.connectbot.shelldon.sync`, pure Kotlin where possible (`RemoteStore` interface:
`list`, `get`, `put` with optional expected ETag, `delete`). New Room tables need a migration; CI
already checks `app/schemas/`.

## 6. Threat model

| Who | Can | Cannot | Because |
| --- | --- | --- | --- |
| Storage operator or a hacked server | See ciphertext, sizes, counts, times, your IP, device count; delete, withhold, replay | Read content; forge or move objects | AEAD with the object id bound; no key on the server |
| Network attacker | Block | Read or alter | TLS, pinned or custom CA |
| Someone who steals sync credentials only | Download ciphertext; delete | Read | Needs the recovery code or passphrase too |
| Someone with ciphertext and a **weak passphrase** | Guess offline | | Recovery code by default; Argon2id with the vault's limits; a strength check on user passphrases |
| Stolen locked phone | Nothing useful | | Vault lock; sync key sealed by the vault |
| A device you enrolled and lost | Read what it already holds | Read new data after rotation | Remove device, then rotate key |
| A compromised enrolled device | Write anything | | "Changes from sync" digest for connection targets; known hosts not synced; secrets off by default |

Metadata leakage (counts, sizes, timing) is accepted and written in the help text. Padding objects to
size buckets is possible later.

## 7. Milestones

Each is its own branch and pull request from `main`.

| Step | What | Size | Needs from you |
| --- | --- | --- | --- |
| **S0** | "Back up to a remote folder": upload the existing `SHBK` file through the first backend. Proves transport, certificate trust and credential UX with almost no logic. Optional. | S | D1, D2 |
| **S1** | Format, crypto and merge, **no network**. `RemoteStore` plus an in-memory store and a folder store for tests. HLC, objects, tombstones, conflict copies, key wrapping, rollback checks. Test vectors. Runs in pure Kotlin here. | L | D3, D4, D7 |
| **S2** | Engine on Room: pull, merge, push through the outbox; per-category switches; conflicts screen; "Changes from sync"; first-sync preview; mass-delete guard; **Folder backend through SAF**. A usable sync with no network code. Settles the key-link question from section 2. | L | D5 |
| **S3** | WebDAV backend: HTTP dependency, PROPFIND parsing, conditional PUT, probe screen, certificate import or pin, credentials in the vault, container tests, compatibility matrix. | M to L | D2, D8 |
| **S4** | SFTP backend over the existing client. | S to M | none |
| **S5** | Devices screen, remove device and key rotation, recovery code display, reset remote, repair, diagnostics without secrets. | M | D4 |
| **S6** | Optional: S3 backend; the own server; shared repositories (public-key crypto, ML-KEM hybrids per the main plan). | later | D10 |

Agent alerts N1 to N3 (section 10) are independent of S0 to S6 and N1 is small enough to go first.

## 8. Test plan

- **Unit and property tests (CI, and locally in pure Kotlin):** codec round trips; wrong key, wrong
  object id, swapped object, truncated or oversized input all refused; rollback detected; two simulated
  replicas with random edits, deletes, moves and reordered or duplicated deliveries must converge, lose no
  edit (every edit is in the state or in a conflict copy) and keep every invariant of section 5.5.
- **Android tests (CI):** migrations, trigger interplay with `applying`, SAF folder store, conflict screen.
- **Container tests (CI, Docker, like the OpenSSH ones):** a WebDAV matrix. Candidates: SabreDAV or a
  Nextcloud image (conditional writes expected), Apache `mod_dav`, `rclone serve webdav`, a Go
  `x/net/webdav` server (no conditional writes expected), nginx with the WebDAV extension module. The
  probe's report is asserted per server. A Nextcloud image is large; run it nightly or manually.
- **Not testable here:** behaviour on real hosting providers and NAS boxes, and anything on the phone.
  Each step's pull request says which of "compiles", "unit tests pass", "container tests pass" and "tried
  on the phone" it can claim.

## 9. Dependencies and permissions (ask before adding)

| Item | Needed for | Notes |
| --- | --- | --- |
| **HTTP client with custom methods: OkHttp** (Apache-2.0, Square, widely used) | WebDAV (S3-backed storage later) | The only new library in this plan. Alternative: Ktor client, heavier and not otherwise used here. Check current release, maintainers and transitive tree at the time (policy in `CLAUDE.md`). Without it, only Folder and SFTP backends are possible. |
| `androidx.work` | Background sync | **Not needed** as long as D6 holds. |
| New permissions | none | SAF needs none. Notifications already have `POST_NOTIFICATIONS` in the manifest. If `targetSdk` moves to 37, a LAN server needs `ACCESS_LOCAL_NETWORK` (ask then). |
| Crypto | HKDF, AES-GCM, Argon2 | Already in BouncyCastle. |

## 10. Agent alerts: "idle or waiting" notifications

**Question: reuse the sync service for it?** Not the sync service. Sync is pull and its server must be
unable to do anything to the device; alerts are push. Mixing them would make "the server that stores my
encrypted keys" also "a server that can make my phone buzz with text I did not write". What can be shared
later is the **optional Shelldon server's accounts and device registration**, as a separate endpoint.

What the agents offer (Claude Code, from the official hooks reference and guide): the `Notification`
event (matchers include `permission_prompt`, about six seconds after a permission dialog appears
without typing, and `idle_prompt`, reported as after roughly a minute of waiting for input), the
`PermissionRequest` event and the `Stop` event (Claude finished responding). Hooks run shell commands, so
they can ring the terminal bell, print an OSC notification, or call a URL. Codex CLI has a `notify`
script (reported). Check the current docs when building: hook names change.

Two situations, two answers:

| Situation | What can see the event | Cheapest fix |
| --- | --- | --- |
| **A. The SSH session is open** in Shelldon (phone in pocket, app in background, foreground service alive) | The app sees the terminal stream | **N1:** turn terminal signals into Android notifications. No server. |
| **B. The session is gone** (phone asleep, network dropped, agent still running in tmux) | Only the server | **N2:** the hook posts to a push channel (ntfy). **N3** only when an own server exists. |

**N1: terminal notifications in the app (small).** Recognise `BEL` from a session that is not on
screen, `OSC 9;<text>` (iTerm2 style), `OSC 777;notify;<title>;<body>` and `OSC 99` (kitty). **Do not**
treat `OSC 9;4` as a notification: it is a progress report. Per host and per group switches; default
on only for explicit opt-in; separate Android channels for "needs input" and "finished" so the user can
silence one. Tap opens that session. Remote text is untrusted: the title is always the host's nickname
(the remote cannot choose it), text is stripped of control characters and links, capped in length and
hidden on the lock screen, with a rate limit per session. No new permission or dependency. Inside tmux,
inner programs' escape sequences need passthrough (`allow-passthrough`, from memory); document it and
test it. A "watch this session" option (alert when output stops for N seconds) fits tools that emit no
signal; it will have false positives with spinners, so it is opt-in and last.

**N2: ntfy for disconnected sessions (almost no app code).** A documented hook that calls an ntfy topic
with a long random name on your own ntfy server, and a notification action that opens the host (the app
already handles `connectbot://` links; verify the exact link form when building). The payload is
**content-free by design**: host label and event type, never agent output. The ntfy Android app is on
F-Droid and doubles as the UnifiedPush distributor that the task-hub app is already planned to use, so one
installed app serves both. A "set up agent alerts on this server" wizard that edits the server's Claude
settings is possible with the exec layer, but it edits remote config, so it needs a visible diff and is a
later step.

**N3: own relay (later, only with an own server).** If the optional Shelldon server exists, a relay can
accept events from hooks and send UnifiedPush hints. Content-free again, or end-to-end encrypted with a
per-host key. Not before there is a server for other reasons.

Honest limits: N1 does nothing when the connection is gone; N2 needs a server you run (or the public
ntfy server, which sees the topic and the message, so use a random topic and no content); Android may
delay or drop background work on its own; none of this has been tried on a phone.

## 11. Per-user accounts and a public service, later

The format keeps this open without building it: repository id, epoch, device cards and a role field
already exist. What would change: authentication on the server (not Basic over WebDAV), quotas, abuse
handling, and **shared repositories**, which need public-key crypto (key wrap per member, ML-KEM hybrids,
as the main plan says) and a different rotation story. A public service also makes the name and
trademark question (`docs/NAME-AND-ICON.md`, class 42) matter more, and brings privacy-law duties for
account data. None of it is in S0 to S5.

## 12. Decisions for the owner

| # | Question | Recommendation | Trade-off |
| --- | --- | --- | --- |
| D1 | First network backend: WebDAV (as you asked) or SFTP? | **WebDAV first, SFTP second, Folder as the proving ground inside S2.** | SFTP needs no dependency and is nearly free; WebDAV has the reach and fits a public service. |
| D2 | Allow a new dependency, OkHttp, for HTTP? | **Yes, when S3 starts.** | Supply-chain surface versus no WebDAV at all. |
| D3 | Secrets (private keys, saved passwords) in sync? | **Off by default, one switch each.** | Convenience on a new device versus exposure if passphrase and server leak. |
| D4 | Enrolment: generated recovery code or passphrase? | **Recovery code by default, passphrase optional.** | Typing a code once per device versus offline guessing. |
| D5 | Sync known host keys? | **Off.** | One accept on each device versus silent shared trust. |
| D6 | Background sync while locked? | **No. Sync while unlocked only.** | Fresher data versus keeping the sync key behind the master password. |
| D7 | Conflict policy | **Per-record last-writer-wins with kept losers.** | Simplicity versus finer merge you probably will not need. |
| D8 | Certificate trust for your own servers | **System store, imported CA or pinned certificate per profile; HTTPS only.** | A little setup (you run your own CA) versus any accept-all shortcut. |
| D9 | Agent alerts | **N1 now and separate from sync; N2 as a documented recipe; N3 not yet.** | Cheap and safe versus a bigger service nobody needs yet. |
| D10 | Public service and per-user accounts | **Not now; keep the format ready.** | Time to market versus doing it properly once. |

## 13. Risks

- **Merge bugs lose data.** The most serious risk. Mitigated by property tests, kept losers, previews
  and the delete guards, and by shipping the Folder backend first on your own data.
- **Server quirks eat time.** The compatibility matrix and the probe contain it; an unsupported server is
  reported, not worked around.
- **Invariants versus remote data** (group loops, name clashes, dangling ids). Tests plus the same rules for
  local and remote edits.
- **Key loss.** Lost recovery code means lost synced secrets. The encrypted backup file remains the
  separate safety net; the UI says so.
- **Scope creep.** Per-field merge, background sync, shared repositories and the relay are all
  deliberately outside S0 to S5.
- **Verification gap.** Nothing here can be tried on the phone from the cloud session; every step ends
  with a short phone test sheet, as in earlier rounds.

## 14. Sources

- Joplin sync lock spec: <https://joplinapp.org/help/dev/spec/sync_lock>
- Joplin end-to-end encryption notes (encryption as a property of the target): <https://joplinapp.org/news/20200906-172325>
- Joplin FAQ (changing the WebDAV location): <https://github.com/laurent22/joplin/blob/dev/readme/faq.md>
- Joplin forum: Nextcloud E2EE app and `MKCOL` locks error: <https://discourse.joplinapp.org/t/joplin-webdav-mkcol-locks-error-when-sync-with-nextcloud/31866>
- Joplin forum: WebDAV server faults reported against Joplin: <https://discourse.joplinapp.org/t/discussions-about-sync/9580?page=2>
- Floccus update (delete failsafe, WebDAV encryption): <https://opencollective.com/floccus/updates/marching-on-march-update>
- Floccus issue (PUT 404 on a WebDAV provider): <https://github.com/floccusaddon/floccus/issues/2079>
- Go `x/net/webdav` ignores conditional PUT: <https://golang.org/issue/44492>
- SabreDAV rejecting a clashing `If-None-Match` (SOGo bug report): <https://sogo.nu/bugs/view.php?id=1624>
- Nextcloud WebDAV developer manual: <https://docs.nextcloud.com/server/stable/developer_manual/client_apis/WebDAV/basic.html>
- Amazon S3 conditional writes: <https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html>, announcement of `If-Match` (Nov 2024): <https://aws.amazon.com/about-aws/whats-new/2024/11/amazon-s3-functionality-conditional-writes>
- MinIO conditional writes: <https://www.min.io/blog/leading-the-way-minios-conditional-write-feature-for-modern-data-workloads>
- Android local network permission: <https://developer.android.com/privacy-and-security/local-network-permission>
- Claude Code hooks reference: <https://code.claude.com/docs/en/hooks>; hooks guide: <https://code.claude.com/docs/en/hooks-guide>
- Third-party notes on Notification matchers and terminal sequences: <https://github.com/FlorianBruniaux/claude-code-ultimate-guide/blob/main/guide/core/hooks-events-reference.md>, <https://claudcod.com/blog/claude-code-hooks-terminal-sequences/>
- ntfy on F-Droid: <https://f-droid.org/repository/browse/?fdid=io.heckel.ntfy>; UnifiedPush: <https://unifiedpush.org>
