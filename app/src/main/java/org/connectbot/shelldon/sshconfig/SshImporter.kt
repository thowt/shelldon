/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sshconfig

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.connectbot.data.ConnectBotDatabase
import org.connectbot.data.EntityIds
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.HostCryptoConfig
import org.connectbot.data.entity.KnownHost
import org.connectbot.data.entity.Pubkey
import org.connectbot.sshlib.AlgorithmCategory
import org.connectbot.sshlib.SshCryptoPolicy
import org.connectbot.sshlib.SshKeys
import org.connectbot.util.SshKeyType
import javax.inject.Inject
import javax.inject.Singleton

/** What the person left switched on in the preview. */
data class SshImportOptions(
    /** Algorithm lists that the config sets for a host by name. */
    val algorithms: Boolean = true,
    /** Algorithm lists that only arrive through wildcard blocks such as `Host *`. Off unless asked for. */
    val wildcardAlgorithms: Boolean = false,
    val keys: Boolean = true,
    val knownHosts: Boolean = true,
)

class SshImportResult(
    val hostsAdded: Int,
    /** Hosts whose nickname already exists; they are left exactly as they are. */
    val hostsSkipped: Int,
    val hostsWithAlgorithms: Int,
    val hostsLinkedToKey: Int,
    val keysImported: Int,
    val knownHostsAdded: Int,
    val jumpHostsLinked: Int,
    /** Aliases whose `ProxyJump` could not be turned into a jump host (several hops, or an unknown host). */
    val jumpHostsUnresolved: List<String>,
)

/**
 * Carries out an [SshImportPlan]. It only adds: an existing host, key or host key is never changed,
 * and everything happens in one transaction, so a failure (a locked vault, say) leaves nothing behind.
 */
@Singleton
class SshImporter @Inject constructor(
    private val database: ConnectBotDatabase,
    private val pubkeys: PubkeyRepository,
) {
    /** Reads what is already stored and plans the import. Writes nothing and does not need the vault unlocked. */
    suspend fun plan(files: List<PickedFile>, passphrases: Map<String, String>): SshImportPlan {
        val hosts = database.hostDao().getAll().map { ExistingHost(it.id, it.nickname, it.hostname, it.port, it.repositoryId == EntityIds.LOCAL) }
        val keys = database.pubkeyDao().getAll().map { ExistingKey(it.id, it.nickname, it.publicKey) }
        val stored = database.knownHostDao().getAll()
            .mapNotNull { known -> known.hostId?.let { it to StoredHostKey(known.hostKeyAlgo, known.hostKey) } }
            .groupBy({ it.first }, { it.second })
        val input = SshImportInput(files, hosts, keys, stored, passphrases)
        return withContext(Dispatchers.Default) { SshImportPlanner.plan(input, SshKeyFileDecoder, ::policyAccepts) }
    }

    suspend fun import(plan: SshImportPlan, options: SshImportOptions): SshImportResult = database.withTransaction {
        val hostDao = database.hostDao()

        var keysImported = 0
        if (options.keys) {
            for (key in plan.keys) {
                val decoded = key.decoded
                if (key.state != KeyFileState.IMPORT || decoded == null) continue
                pubkeys.save(
                    Pubkey(
                        id = key.id,
                        nickname = key.fileName,
                        type = decoded.type,
                        privateKey = decoded.privateKey,
                        publicKey = decoded.publicKey,
                        encrypted = false,
                        startup = false,
                        confirmation = false,
                        createdDate = System.currentTimeMillis(),
                    ),
                )
                keysImported++
            }
        }

        val byNickname = hostDao.getAll().associateBy { it.nickname }.toMutableMap()
        val created = mutableListOf<Pair<PlannedHost, Host>>()
        var withAlgorithms = 0
        var linkedToKey = 0
        for (planned in plan.newHosts) {
            val entry = planned.entry
            val crypto = cryptoFor(planned, options)
            val keyId = planned.identityKeyId?.takeIf { planned.identityState != IdentityLinkState.IMPORTED_KEY || options.keys }
            val host = Host(
                id = planned.id,
                nickname = entry.alias,
                hostname = entry.hostName,
                username = entry.user.orEmpty(),
                port = entry.port,
                crypto = crypto ?: HostCryptoConfig(),
                useKeys = entry.pubkeyAuthentication != false,
            ).let { if (keyId != null) it.copy(pubkeyId = keyId) else it }
            hostDao.insert(host)
            byNickname[entry.alias] = host
            created.add(planned to host)
            if (crypto != null) withAlgorithms++
            if (keyId != null) linkedToKey++
        }

        var jumpsLinked = 0
        val unresolved = mutableListOf<String>()
        for ((planned, host) in created) {
            val proxy = planned.entry.proxyJump ?: continue
            val jump = resolveJump(proxy, byNickname.values)
            if (jump == null || jump.id == host.id) {
                unresolved.add(planned.entry.alias)
            } else {
                hostDao.update(host.copy(jumpHostId = jump.id))
                jumpsLinked++
            }
        }

        var knownAdded = 0
        if (options.knownHosts) {
            val knownDao = database.knownHostDao()
            for (known in plan.knownHosts?.add.orEmpty()) {
                knownDao.insert(
                    KnownHost(
                        hostId = known.hostId,
                        hostname = known.hostname,
                        port = known.port,
                        hostKeyAlgo = known.algorithm,
                        hostKey = known.keyBlob,
                    ),
                )
                knownAdded++
            }
        }

        SshImportResult(
            hostsAdded = created.size,
            hostsSkipped = plan.hosts.size - created.size,
            hostsWithAlgorithms = withAlgorithms,
            hostsLinkedToKey = linkedToKey,
            keysImported = keysImported,
            knownHostsAdded = knownAdded,
            jumpHostsLinked = jumpsLinked,
            jumpHostsUnresolved = unresolved,
        )
    }

    /** Null when nothing is switched on or nothing applies: the host then keeps the default policy. */
    private fun cryptoFor(planned: PlannedHost, options: SshImportOptions): HostCryptoConfig? {
        val chosen = planned.algorithms.filter { if (it.fromWildcard) options.wildcardAlgorithms else options.algorithms }
        if (chosen.isEmpty()) return null
        var config = HostCryptoConfig(preset = "CUSTOM")
        for (algorithm in chosen) config = config.withExpression(algorithm.category, algorithm.expression)
        if (planned.lowersDhMinimum && chosen.any { it.category == AlgorithmCategory.KEX }) config = config.copy(minimumDhBits = LEGACY_DH_BITS)
        return config
    }

    /**
     * One hop only: an alias that is a host here, or `[user@]host[:port]` matching a host's address.
     */
    private fun resolveJump(proxyJump: String, hosts: Collection<Host>): Host? {
        if (proxyJump.contains(',')) return null
        hosts.firstOrNull { it.nickname == proxyJump }?.let { return it }
        val address = proxyJump.substringAfter('@').substringBeforeLast(':', proxyJump.substringAfter('@'))
        val port = proxyJump.substringAfter('@').substringAfterLast(':', "").toIntOrNull()
        return hosts.firstOrNull { it.hostname == address && (port == null || it.port == port) }
    }

    private companion object {
        const val LEGACY_DH_BITS = 1024
    }
}

/** The SSH library decides what an algorithm expression may contain. */
internal fun policyAccepts(expression: String): Boolean = runCatching { SshCryptoPolicy.validateExpression(expression) }.isSuccess

/** Reads PEM, PKCS#8 and OpenSSH private keys the way the Keychain's own import does. */
internal object SshKeyFileDecoder : KeyFileDecoder {
    override fun decode(text: String, passphrase: String?): KeyDecodeResult = try {
        val pair = if (passphrase.isNullOrEmpty()) SshKeys.decodePemPrivateKey(text) else SshKeys.decodePemPrivateKey(text, passphrase)
        KeyDecodeResult.Decoded(
            type = SshKeyType.fromJavaAlgorithm(pair.private.algorithm)?.storedName ?: pair.private.algorithm,
            privateKey = pair.private.encoded,
            publicKey = pair.public.encoded,
        )
    } catch (_: Exception) {
        when {
            !text.contains("-----BEGIN ") -> KeyDecodeResult.Unreadable
            passphrase.isNullOrEmpty() -> KeyDecodeResult.NeedsPassphrase
            else -> KeyDecodeResult.WrongPassphrase
        }
    }
}
