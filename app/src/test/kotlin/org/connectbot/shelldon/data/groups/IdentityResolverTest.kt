/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.data.groups

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.EntityIds
import org.connectbot.data.entity.Host
import org.connectbot.util.HostConstants
import org.junit.Test
import java.util.UUID

class IdentityResolverTest {
    private fun id(n: Long) = UUID(0, n)

    private val admin = Identity(id = id(101), name = "admin", username = "admin", keyId = id(201))
    private val ops = Identity(id = id(102), name = "ops", username = "ops", keyId = null)
    private val identities = listOf(admin, ops).associateBy { it.id }

    private fun group(n: Long, parent: Long? = null, identity: UUID? = null) = HostGroup(id = id(n), parentId = parent?.let { id(it) }, name = "g$n", identityId = identity)

    private fun groups(vararg g: HostGroup) = g.associateBy { it.id }

    private fun host(
        groupId: UUID? = null,
        identityId: UUID? = null,
        username: String = "",
        pubkeyId: UUID = HostConstants.PUBKEYID_ANY,
    ) = Host(nickname = "h", hostname = "h.lan", username = username, groupId = groupId, identityId = identityId, pubkeyId = pubkeyId)

    @Test
    fun nothingConfiguredResolvesToNone() {
        val r = IdentityResolver.resolveIdentity(null, null, emptyMap(), identities)
        assertThat(r.identity).isNull()
        assertThat(r.source).isEqualTo(IdentitySource.NONE)
    }

    @Test
    fun hostIdentityBeatsGroups() {
        val g = groups(group(1, identity = admin.id))
        val r = IdentityResolver.resolveIdentity(ops.id, id(1), g, identities)
        assertThat(r.identity).isEqualTo(ops)
        assertThat(r.source).isEqualTo(IdentitySource.HOST)
    }

    @Test
    fun nearestGroupWins() {
        val g = groups(group(1, identity = admin.id), group(2, parent = 1, identity = ops.id), group(3, parent = 2))
        val r = IdentityResolver.resolveIdentity(null, id(3), g, identities)
        assertThat(r.identity).isEqualTo(ops)
        assertThat(r.source).isEqualTo(IdentitySource.GROUP)
        assertThat(r.group?.id).isEqualTo(id(2))
    }

    @Test
    fun groupWithoutIdentityInheritsFromItsParent() {
        val g = groups(group(1, identity = admin.id), group(2, parent = 1))
        assertThat(IdentityResolver.resolveIdentity(null, id(2), g, identities).identity).isEqualTo(admin)
    }

    @Test
    fun hostCanOptOutOfInheritance() {
        val g = groups(group(1, identity = admin.id))
        val r = IdentityResolver.resolveIdentity(EntityIds.NONE, id(1), g, identities)
        assertThat(r.identity).isNull()
        assertThat(r.source).isEqualTo(IdentitySource.NONE)
    }

    @Test
    fun groupCanStopInheritanceForItsSubtree() {
        val g = groups(group(1, identity = admin.id), group(2, parent = 1, identity = EntityIds.NONE), group(3, parent = 2))
        assertThat(IdentityResolver.resolveIdentity(null, id(3), g, identities).identity).isNull()
        // ...but a host below can still choose its own.
        assertThat(IdentityResolver.resolveIdentity(ops.id, id(3), g, identities).identity).isEqualTo(ops)
    }

    @Test
    fun deletedIdentityCountsAsUnsetAndInheritanceContinues() {
        val g = groups(group(1, identity = admin.id))
        val r = IdentityResolver.resolveIdentity(id(999), id(1), g, identities)
        assertThat(r.identity).isEqualTo(admin)
        assertThat(r.source).isEqualTo(IdentitySource.GROUP)
    }

    @Test
    fun loopedGroupsDoNotHangResolution() {
        val g = groups(group(1, parent = 2), group(2, parent = 1))
        assertThat(IdentityResolver.resolveIdentity(null, id(1), g, identities).identity).isNull()
    }

    @Test
    fun blankHostUsernameTakesTheIdentityUsernameAndKey() {
        val g = groups(group(1, identity = admin.id))
        val login = IdentityResolver.effectiveLogin(host(groupId = id(1)), g, identities)
        assertThat(login.username).isEqualTo("admin")
        assertThat(login.keyId).isEqualTo(id(201))
    }

    @Test
    fun hostUsernameAndKeyAlwaysWin() {
        val g = groups(group(1, identity = admin.id))
        val login = IdentityResolver.effectiveLogin(host(groupId = id(1), username = "root", pubkeyId = id(300)), g, identities)
        assertThat(login.username).isEqualTo("root")
        assertThat(login.keyId).isEqualTo(id(300))
    }

    @Test
    fun hostNeverUseKeysIsNotOverriddenByTheIdentity() {
        val g = groups(group(1, identity = admin.id))
        val login = IdentityResolver.effectiveLogin(host(groupId = id(1), pubkeyId = HostConstants.PUBKEYID_NEVER), g, identities)
        assertThat(login.keyId).isEqualTo(HostConstants.PUBKEYID_NEVER)
        assertThat(login.username).isEqualTo("admin")
    }

    @Test
    fun identityWithoutKeyLeavesTheHostKeyChoice() {
        val login = IdentityResolver.effectiveLogin(host(identityId = ops.id), emptyMap(), identities)
        assertThat(login.username).isEqualTo("ops")
        assertThat(login.keyId).isEqualTo(HostConstants.PUBKEYID_ANY)
    }

    @Test
    fun hostWithoutAnyIdentityKeepsItsOwnLogin() {
        val login = IdentityResolver.effectiveLogin(host(username = "me"), emptyMap(), identities)
        assertThat(login.username).isEqualTo("me")
        assertThat(login.keyId).isEqualTo(HostConstants.PUBKEYID_ANY)
        assertThat(login.identity.source).isEqualTo(IdentitySource.NONE)
    }
}
