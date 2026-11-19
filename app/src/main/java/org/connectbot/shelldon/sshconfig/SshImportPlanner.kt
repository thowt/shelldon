/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sshconfig

import org.connectbot.sshlib.AlgorithmCategory
import java.util.UUID

/** A file the person picked, read into memory. */
class PickedFile(val name: String, val bytes: ByteArray)

enum class PickedFileKind { CONFIG, KNOWN_HOSTS, PRIVATE_KEY, PUBLIC_KEY, UNKNOWN }

/** What a picked file is, decided by its content. Android file pickers hide dot folders and rename files, so names are only a hint. */
object PickedFiles {
    const val MAX_TEXT_BYTES = 4 * 1024 * 1024
    const val MAX_KEY_BYTES = 32 * 1024

    private val configLine = Regex("^(?i:host|match|include)\\s+\\S")
    private val publicKeyLine = Regex("^(ssh-|ecdsa-|sk-)\\S+\\s+\\S+")

    fun kindOf(file: PickedFile): PickedFileKind {
        if (file.bytes.any { it == 0.toByte() }) return PickedFileKind.UNKNOWN
        val text = file.bytes.toString(Charsets.UTF_8)
        if (text.trimStart().startsWith("-----BEGIN ") && text.contains("PRIVATE KEY-----")) {
            return if (file.bytes.size <= MAX_KEY_BYTES) PickedFileKind.PRIVATE_KEY else PickedFileKind.UNKNOWN
        }
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        if (lines.isEmpty()) return PickedFileKind.UNKNOWN
        if (file.name.endsWith(".pub") || (lines.size == 1 && publicKeyLine.containsMatchIn(lines[0]))) return PickedFileKind.PUBLIC_KEY
        if (lines.any { configLine.containsMatchIn(it) && KnownHostsFile.parse(it).entries.isEmpty() }) return PickedFileKind.CONFIG
        val knownHosts = KnownHostsFile.parse(text)
        if (knownHosts.entries.isNotEmpty() && knownHosts.entries.size * 2 >= lines.size) return PickedFileKind.KNOWN_HOSTS
        return PickedFileKind.UNKNOWN
    }
}

sealed interface KeyDecodeResult {
    class Decoded(val type: String, val privateKey: ByteArray, val publicKey: ByteArray) : KeyDecodeResult
    data object NeedsPassphrase : KeyDecodeResult
    data object WrongPassphrase : KeyDecodeResult
    data object Unreadable : KeyDecodeResult
}

fun interface KeyFileDecoder {
    fun decode(text: String, passphrase: String?): KeyDecodeResult
}

/** A host that is already saved here. */
class ExistingHost(val id: UUID, val nickname: String, val hostname: String, val port: Int, val local: Boolean)

/** A key that is already in the Keychain. [publicKey] is the stored public key encoding, if any. */
class ExistingKey(val id: UUID, val nickname: String, val publicKey: ByteArray?)

class StoredHostKey(val algorithm: String, val keyBlob: ByteArray)

class SshImportInput(
    val files: List<PickedFile>,
    val existingHosts: List<ExistingHost>,
    val existingKeys: List<ExistingKey>,
    /** Host keys already stored, by host id. */
    val storedHostKeys: Map<UUID, List<StoredHostKey>>,
    /** Passphrases typed for encrypted key files, by file name. */
    val passphrases: Map<String, String> = emptyMap(),
)

class PlannedAlgorithm(val category: AlgorithmCategory, val expression: String, val fromWildcard: Boolean, val older: Boolean)

enum class HostImportStatus { NEW, EXISTS }

enum class IdentityLinkState { NONE, EXISTING_KEY, IMPORTED_KEY, MISSING }

class PlannedHost(
    val entry: SshConfigHost,
    /** The id the host gets (NEW) or already has (EXISTS). */
    val id: UUID,
    val status: HostImportStatus,
    val algorithms: List<PlannedAlgorithm>,
    /** `Category: name` for entries the SSH library does not accept; they are left out. */
    val droppedAlgorithms: List<String>,
    /** Enabling `diffie-hellman-group1-sha1` needs the 1024-bit minimum, the way the Legacy preset does. */
    val lowersDhMinimum: Boolean,
    /** The key file name `IdentityFile` points at (first one that is not `none`). */
    val identityName: String?,
    val identityState: IdentityLinkState,
    val identityKeyId: UUID?,
)

enum class KeyFileState { IMPORT, ALREADY_IN_KEYCHAIN, NEEDS_PASSPHRASE, WRONG_PASSPHRASE, UNREADABLE, NAME_TAKEN }

class PlannedKey(
    val fileName: String,
    val text: String,
    val state: KeyFileState,
    /** The id the key gets (IMPORT) or already has (ALREADY_IN_KEYCHAIN). */
    val id: UUID,
    val decoded: KeyDecodeResult.Decoded?,
)

class PlannedKnownHost(
    val hostId: UUID,
    val hostNickname: String,
    val hostname: String,
    val port: Int,
    val algorithm: String,
    val keyBlob: ByteArray,
)

class KnownHostsPlan(
    val add: List<PlannedKnownHost>,
    val alreadyKnown: Int,
    /** The file has a different key than the one stored for this host and algorithm. Never replaced. */
    val conflicts: List<String>,
    /** The file itself has several different keys of one type for this host (a reused address). Not imported. */
    val ambiguous: List<String>,
    val unmatchedLines: Int,
    val certAuthorityLines: Int,
    val revokedLines: Int,
    val unreadableLines: Int,
    val totalLines: Int,
)

class SshImportPlan(
    val hosts: List<PlannedHost>,
    val keys: List<PlannedKey>,
    val knownHosts: KnownHostsPlan?,
    val wildcardPatterns: List<String>,
    val matchBlocks: Int,
    val includes: List<String>,
    /** Options read but not imported, lower case, without the ones listed in [refused]. */
    val unsupportedOptions: Set<String>,
    /** Options refused on purpose, with the aliases they applied to. */
    val refused: Map<String, List<String>>,
    /** Picked files that were not used: other kinds, or a second config or known_hosts. */
    val ignoredFiles: List<String>,
) {
    val newHosts: List<PlannedHost> get() = hosts.filter { it.status == HostImportStatus.NEW }
}

/**
 * Turns what the person picked into a plan: nothing is written here. The plan is what the preview
 * shows and what [SshImporter] later carries out, so the two cannot disagree.
 */
object SshImportPlanner {
    private val olderMarkers = listOf("sha1", "cbc", "3des", "arcfour", "md5", "ssh-dss", "ssh-rsa", "blowfish", "cast128")
    private val lowDhKex = setOf("diffie-hellman-group1-sha1", "diffie-hellman-group-exchange-sha1")

    fun plan(
        input: SshImportInput,
        decodeKey: KeyFileDecoder,
        accepts: (String) -> Boolean,
    ): SshImportPlan {
        val kinds = input.files.map { it to PickedFiles.kindOf(it) }
        val ignored = mutableListOf<String>()
        val configFile = pickOne(kinds, PickedFileKind.CONFIG, "config", ignored)
        val knownHostsFile = pickOne(kinds, PickedFileKind.KNOWN_HOSTS, "known_hosts", ignored)
        kinds.filter { it.second == PickedFileKind.PUBLIC_KEY || it.second == PickedFileKind.UNKNOWN }.forEach { ignored.add(it.first.name) }

        val keys = planKeys(kinds.filter { it.second == PickedFileKind.PRIVATE_KEY }.map { it.first }, input, decodeKey)
        val parsed = configFile?.let { SshConfigParser.parse(it.bytes.toString(Charsets.UTF_8)) }
        val hosts = planHosts(parsed?.hosts.orEmpty(), input, keys, accepts)

        val knownHosts = knownHostsFile?.let { planKnownHosts(it.bytes.toString(Charsets.UTF_8), hosts, input) }

        val refused = LinkedHashMap<String, MutableList<String>>()
        parsed?.hosts.orEmpty().forEach { host -> host.refused.forEach { (option, _) -> refused.getOrPut(option) { mutableListOf() }.add(host.alias) } }
        val refusedKeys = refused.keys.map { it.lowercase() }.toSet()

        return SshImportPlan(
            hosts = hosts,
            keys = keys,
            knownHosts = knownHosts,
            wildcardPatterns = parsed?.wildcardPatterns.orEmpty(),
            matchBlocks = parsed?.matchBlocks ?: 0,
            includes = parsed?.includes.orEmpty(),
            unsupportedOptions = parsed?.hosts.orEmpty().flatMap { it.unsupported }.filterNot { it in refusedKeys }.toSortedSet(),
            refused = refused,
            ignoredFiles = ignored,
        )
    }

    /** The file named [preferredName] if there is one, else the first of that kind; the others are reported as ignored. */
    private fun pickOne(kinds: List<Pair<PickedFile, PickedFileKind>>, kind: PickedFileKind, preferredName: String, ignored: MutableList<String>): PickedFile? {
        val files = kinds.filter { it.second == kind }.map { it.first }
        val chosen = files.firstOrNull { it.name == preferredName } ?: files.firstOrNull()
        files.filter { it !== chosen }.forEach { ignored.add(it.name) }
        return chosen
    }

    private fun planKeys(files: List<PickedFile>, input: SshImportInput, decodeKey: KeyFileDecoder): List<PlannedKey> {
        val takenNames = input.existingKeys.map { it.nickname }.toMutableSet()
        return files.map { file ->
            val text = file.bytes.toString(Charsets.UTF_8)
            when (val decoded = decodeKey.decode(text, input.passphrases[file.name])) {
                is KeyDecodeResult.Decoded -> {
                    val same = input.existingKeys.firstOrNull { it.publicKey != null && it.publicKey.contentEquals(decoded.publicKey) }
                    when {
                        same != null -> PlannedKey(file.name, text, KeyFileState.ALREADY_IN_KEYCHAIN, same.id, decoded)

                        file.name in takenNames -> PlannedKey(file.name, text, KeyFileState.NAME_TAKEN, UUID.randomUUID(), decoded)

                        else -> {
                            takenNames.add(file.name)
                            PlannedKey(file.name, text, KeyFileState.IMPORT, UUID.randomUUID(), decoded)
                        }
                    }
                }

                KeyDecodeResult.NeedsPassphrase -> PlannedKey(file.name, text, KeyFileState.NEEDS_PASSPHRASE, UUID.randomUUID(), null)

                KeyDecodeResult.WrongPassphrase -> PlannedKey(file.name, text, KeyFileState.WRONG_PASSPHRASE, UUID.randomUUID(), null)

                KeyDecodeResult.Unreadable -> PlannedKey(file.name, text, KeyFileState.UNREADABLE, UUID.randomUUID(), null)
            }
        }
    }

    private fun planHosts(entries: List<SshConfigHost>, input: SshImportInput, keys: List<PlannedKey>, accepts: (String) -> Boolean): List<PlannedHost> {
        val existingByNickname = input.existingHosts.associateBy { it.nickname }
        val seen = mutableSetOf<String>()
        return entries.filter { seen.add(it.alias) }.map { entry ->
            val existing = existingByNickname[entry.alias]
            val accepted = mutableListOf<PlannedAlgorithm>()
            val dropped = mutableListOf<String>()
            for ((category, value) in entry.algorithms) {
                val expression = SshConfigParser.toPolicyExpression(value) ?: continue
                val tokens = expression.split(',')
                val ok = tokens.filter(accepts)
                tokens.filterNot(accepts).forEach { dropped.add("${category.name}: ${it.trimStart('+', '-', '^')}") }
                if (ok.isEmpty()) continue
                accepted.add(
                    PlannedAlgorithm(
                        category = category,
                        expression = ok.joinToString(","),
                        fromWildcard = category in entry.algorithmsFromWildcard,
                        older = ok.any { token -> token.first() != '-' && olderMarkers.any { it in token } },
                    ),
                )
            }
            val lowersDh = accepted.any { a ->
                a.category == AlgorithmCategory.KEX && a.expression.split(',').any { it.trimStart('+', '^') in lowDhKex && !it.startsWith("-") }
            }
            val identityName = entry.identityFiles.firstOrNull()?.let(::keyNameOf)
            val importedKey = keys.firstOrNull { it.fileName == identityName && it.state in setOf(KeyFileState.IMPORT, KeyFileState.ALREADY_IN_KEYCHAIN) }
            val existingKey = input.existingKeys.firstOrNull { it.nickname == identityName }
            val (state, keyId) = when {
                identityName == null -> IdentityLinkState.NONE to null
                importedKey != null && importedKey.state == KeyFileState.IMPORT -> IdentityLinkState.IMPORTED_KEY to importedKey.id
                importedKey != null -> IdentityLinkState.EXISTING_KEY to importedKey.id
                existingKey != null -> IdentityLinkState.EXISTING_KEY to existingKey.id
                else -> IdentityLinkState.MISSING to null
            }
            PlannedHost(
                entry = entry,
                id = existing?.id ?: UUID.randomUUID(),
                status = if (existing != null) HostImportStatus.EXISTS else HostImportStatus.NEW,
                algorithms = accepted,
                droppedAlgorithms = dropped,
                lowersDhMinimum = lowersDh,
                identityName = identityName,
                identityState = state,
                identityKeyId = keyId,
            )
        }
    }

    /** `~/.ssh/id_ed25519`, `%d/.ssh/id_rsa` or `C:\Users\me\.ssh\id_rsa` all name the key `id_...`. */
    internal fun keyNameOf(identityFile: String): String = identityFile.trim().trim('"').substringAfterLast('/').substringAfterLast('\\')

    private fun planKnownHosts(text: String, hosts: List<PlannedHost>, input: SshImportInput): KnownHostsPlan {
        val parsed = KnownHostsFile.parse(text)
        class Candidate(val id: UUID, val nickname: String, val hostname: String, val port: Int)

        val candidates = buildList {
            input.existingHosts.filter { it.local }.forEach { add(Candidate(it.id, it.nickname, it.hostname, it.port)) }
            hosts.filter { it.status == HostImportStatus.NEW }.forEach { add(Candidate(it.id, it.entry.alias, it.entry.hostName, it.entry.port)) }
        }
        val found = LinkedHashMap<Pair<UUID, String>, MutableList<ByteArray>>()
        var unmatched = 0
        for (entry in parsed.entries) {
            var any = false
            for (candidate in candidates) {
                if (!KnownHostsFile.matches(entry, candidate.hostname, candidate.port)) continue
                any = true
                val blobs = found.getOrPut(candidate.id to entry.algorithm) { mutableListOf() }
                if (blobs.none { it.contentEquals(entry.keyBlob) }) blobs.add(entry.keyBlob)
            }
            if (!any) unmatched++
        }
        val byId = candidates.associateBy { it.id }
        val add = mutableListOf<PlannedKnownHost>()
        val conflicts = mutableListOf<String>()
        val ambiguous = mutableListOf<String>()
        var already = 0
        for ((key, blobs) in found) {
            val (hostId, algorithm) = key
            val host = byId.getValue(hostId)
            val label = "${host.nickname} ($algorithm)"
            if (blobs.size > 1) {
                ambiguous.add(label)
                continue
            }
            val stored = input.storedHostKeys[hostId].orEmpty().filter { it.algorithm == algorithm }
            when {
                stored.any { it.keyBlob.contentEquals(blobs[0]) } -> already++
                stored.isNotEmpty() -> conflicts.add(label)
                else -> add.add(PlannedKnownHost(hostId, host.nickname, host.hostname, host.port, algorithm, blobs[0]))
            }
        }
        return KnownHostsPlan(
            add = add,
            alreadyKnown = already,
            conflicts = conflicts,
            ambiguous = ambiguous,
            unmatchedLines = unmatched,
            certAuthorityLines = parsed.certAuthorityLines,
            revokedLines = parsed.revokedLines,
            unreadableLines = parsed.unreadableLines,
            totalLines = parsed.entries.size + parsed.certAuthorityLines + parsed.revokedLines + parsed.unreadableLines,
        )
    }
}
