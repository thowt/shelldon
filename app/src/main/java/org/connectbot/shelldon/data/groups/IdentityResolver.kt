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
    fun effectiveLogin(host: Host, groups: Map<UUID, HostGroup>, identities: Map<UUID, Identity>): EffectiveLogin = effectiveLogin(host.username, host.pubkeyId, host.identityId, host.groupId, groups, identities)

    /** Same as above for values that are not a saved host yet, such as the fields of the host editor. */
    fun effectiveLogin(
        username: String,
        pubkeyId: UUID,
        identityId: UUID?,
        groupId: UUID?,
        groups: Map<UUID, HostGroup>,
        identities: Map<UUID, Identity>,
    ): EffectiveLogin {
        val resolved = resolveIdentity(identityId, groupId, groups, identities)
        val identity = resolved.identity
        val keyId = when {
            pubkeyId == HostConstants.PUBKEYID_NEVER -> HostConstants.PUBKEYID_NEVER
            EntityIds.isKey(pubkeyId) -> pubkeyId
            else -> identity?.keyId ?: pubkeyId
        }
        return EffectiveLogin(
            username = username.ifBlank { identity?.username.orEmpty() },
            keyId = keyId,
            identity = resolved,
        )
    }

    /**
     * The host as a connection should see it: the user name and key filled in from the identity that
     * applies. The result is only used to open the connection. It is never written back, so the saved
     * host keeps pointing at its group or identity and follows later changes to them.
     */
    fun resolvedHost(host: Host, groups: Map<UUID, HostGroup>, identities: Map<UUID, Identity>): Host {
        if (host.groupId == null && host.identityId == null) return host
        val login = effectiveLogin(host, groups, identities)
        return if (login.username == host.username && login.keyId == host.pubkeyId) {
            host
        } else {
            host.copy(username = login.username, pubkeyId = login.keyId)
        }
    }
}
