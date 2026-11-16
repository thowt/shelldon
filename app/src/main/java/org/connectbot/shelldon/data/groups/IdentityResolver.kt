/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.data.groups

import org.connectbot.data.EntityIds
import org.connectbot.data.entity.Host
import org.connectbot.util.HostConstants
import java.util.UUID

/** Where a resolved identity came from. */
enum class IdentitySource {
    /** The host names the identity itself. */
    HOST,

    /** Inherited from the nearest group that has one. */
    GROUP,

    /** No identity applies: none is set anywhere, or a host or group opted out with [EntityIds.NONE]. */
    NONE,
}

data class ResolvedIdentity(
    val identity: Identity?,
    val source: IdentitySource,
    /** The group the identity was inherited from (or the group that opted out), if any. */
    val group: HostGroup? = null,
)

/** What a connection should use after inheritance. Computed on the fly and never written back to the host. */
data class EffectiveLogin(
    val username: String,
    /** A stored key id, or [HostConstants.PUBKEYID_ANY] / [HostConstants.PUBKEYID_NEVER] as on the host. */
    val keyId: UUID,
    val identity: ResolvedIdentity,
)

/**
 * Resolution order: host, then the group chain (nearest first), then nothing. A host or group can
 * set [EntityIds.NONE] to stop inheriting. A reference to an identity that no longer exists counts
 * as unset, so a deleted identity never blocks a connection.
 */
object IdentityResolver {
    fun resolveIdentity(
        hostIdentityId: UUID?,
        groupId: UUID?,
        groups: Map<UUID, HostGroup>,
        identities: Map<UUID, Identity>,
    ): ResolvedIdentity {
        if (hostIdentityId == EntityIds.NONE) return ResolvedIdentity(null, IdentitySource.NONE)
        hostIdentityId?.let { id -> identities[id]?.let { return ResolvedIdentity(it, IdentitySource.HOST) } }
        for (group in GroupTree.chain(groups, groupId)) {
            val id = group.identityId ?: continue
            if (id == EntityIds.NONE) return ResolvedIdentity(null, IdentitySource.NONE, group)
            identities[id]?.let { return ResolvedIdentity(it, IdentitySource.GROUP, group) }
        }
        return ResolvedIdentity(null, IdentitySource.NONE)
    }

    /**
     * A login the host states itself always wins: a non-blank user name, a specific key, or "never
     * use keys". The identity only fills in what the host leaves open.
     */
    fun effectiveLogin(host: Host, groups: Map<UUID, HostGroup>, identities: Map<UUID, Identity>): EffectiveLogin {
        val resolved = resolveIdentity(host.identityId, host.groupId, groups, identities)
        val identity = resolved.identity
        val keyId = when {
            host.pubkeyId == HostConstants.PUBKEYID_NEVER -> HostConstants.PUBKEYID_NEVER
            EntityIds.isKey(host.pubkeyId) -> host.pubkeyId
            else -> identity?.keyId ?: host.pubkeyId
        }
        return EffectiveLogin(
            username = host.username.ifBlank { identity?.username.orEmpty() },
            keyId = keyId,
            identity = resolved,
        )
    }
}
