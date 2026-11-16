/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.data.groups

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.ConnectBotDatabase
import org.connectbot.data.EntityIds
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.KnownHost
import org.connectbot.data.sync.SyncDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ShelldonGroupRepositoryTest {
    private lateinit var database: ConnectBotDatabase
    private lateinit var repository: ShelldonGroupRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ConnectBotDatabase::class.java).allowMainThreadQueries().build()
        SyncDatabase.install(database.openHelper.writableDatabase)
        repository = ShelldonGroupRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun host(nickname: String): Host = Host(nickname = nickname, hostname = "$nickname.lan", username = "").also { database.hostDao().insert(it) }

    private suspend fun group(name: String, parent: UUID? = null): HostGroup = (repository.createGroup(name, parent) as Outcome.Ok).value

    private fun rejected(outcome: Outcome<*>): Rejection? = (outcome as? Outcome.Rejected)?.reason

    // Groups

    @Test
    fun createsNestedGroupsInOrder() = runTest {
        val work = group("Work")
        val a = group("Switches", work.id)
        val b = group("Routers", work.id)
        assertThat(a.parentId).isEqualTo(work.id)
        assertThat(b.sortOrder).isGreaterThan(a.sortOrder)
        assertThat(repository.snapshot().groups).hasSize(3)
    }

    @Test
    fun siblingNamesAreUniqueIgnoringCaseButCanRepeatElsewhere() = runTest {
        val work = group("Work")
        val home = group("Home")
        group("Lab", work.id)
        assertThat(rejected(repository.createGroup("lab", work.id))).isEqualTo(Rejection.NAME_TAKEN)
        assertThat(repository.createGroup("Lab", home.id)).isInstanceOf(Outcome.Ok::class.java)
        assertThat(rejected(repository.createGroup("WORK", null))).isEqualTo(Rejection.NAME_TAKEN)
    }

    @Test
    fun refusesBlankLongAndOrphanedGroups() = runTest {
        assertThat(rejected(repository.createGroup("   ", null))).isEqualTo(Rejection.BLANK_NAME)
        assertThat(rejected(repository.createGroup("x".repeat(ShelldonGroupRepository.MAX_NAME_LENGTH + 1), null))).isEqualTo(Rejection.NAME_TOO_LONG)
        assertThat(rejected(repository.createGroup("Orphan", UUID.randomUUID()))).isEqualTo(Rejection.NOT_FOUND)
    }

    @Test
    fun movingAGroupIntoItsOwnSubtreeIsRefused() = runTest {
        val a = group("A")
        val b = group("B", a.id)
        val c = group("C", b.id)
        assertThat(rejected(repository.moveGroup(a.id, c.id))).isEqualTo(Rejection.WOULD_LOOP)
        assertThat(rejected(repository.moveGroup(a.id, a.id))).isEqualTo(Rejection.WOULD_LOOP)
        assertThat(repository.moveGroup(c.id, null)).isInstanceOf(Outcome.Ok::class.java)
        assertThat(repository.snapshot().groupsById[c.id]!!.parentId).isNull()
    }

    @Test
    fun movingIntoAFolderWithTheSameNameIsRefused() = runTest {
        val a = group("A")
        val b = group("B")
        group("Same", a.id)
        val other = group("same", b.id)
        assertThat(rejected(repository.moveGroup(other.id, a.id))).isEqualTo(Rejection.NAME_TAKEN)
    }

    @Test
    fun nestingIsLimited() = runTest {
        var parent: UUID? = null
        repeat(GroupTree.MAX_DEPTH) { parent = group("level$it", parent).id }
        assertThat(rejected(repository.createGroup("too deep", parent))).isEqualTo(Rejection.TOO_DEEP)
    }

    @Test
    fun deletingAGroupMovesChildrenAndHostsUpAndKeepsEverything() = runTest {
        val root = group("Root")
        val mid = group("Mid", root.id)
        val child = group("Clash", mid.id)
        group("Clash", root.id) // already a sibling of Mid at the destination
        val h = host("h1")
        assertThat(repository.setHostGroup(h.id, mid.id)).isInstanceOf(Outcome.Ok::class.java)

        assertThat(repository.deleteGroup(mid.id)).isInstanceOf(Outcome.Ok::class.java)

        val snapshot = repository.snapshot()
        assertThat(snapshot.groupsById).doesNotContainKey(mid.id)
        val moved = snapshot.groupsById[child.id]!!
        assertThat(moved.parentId).isEqualTo(root.id)
        assertThat(moved.name).isEqualTo("Clash (2)")
        assertThat(database.hostDao().getById(h.id)!!.groupId).isEqualTo(root.id)
    }

    @Test
    fun deletingATopLevelGroupMovesHostsToTheTopLevel() = runTest {
        val g = group("Only")
        val h = host("h1")
        repository.setHostGroup(h.id, g.id)
        repository.deleteGroup(g.id)
        assertThat(database.hostDao().getById(h.id)!!.groupId).isNull()
    }

    @Test
    fun hostCanOnlyJoinAnExistingGroup() = runTest {
        val h = host("h1")
        assertThat(rejected(repository.setHostGroup(h.id, UUID.randomUUID()))).isEqualTo(Rejection.NOT_FOUND)
        assertThat(rejected(repository.setHostGroup(UUID.randomUUID(), null))).isEqualTo(Rejection.NOT_FOUND)
    }

    @Test
    fun editingAGroupKeepsItsPlaceAndChecksTheName() = runTest {
        val a = group("A")
        group("B")
        assertThat(rejected(repository.editGroup(a.id, "b", null, null))).isEqualTo(Rejection.NAME_TAKEN)
        val edited = (repository.editGroup(a.id, "  Alpha ", "#5FD38D", null) as Outcome.Ok).value
        assertThat(edited.name).isEqualTo("Alpha")
        assertThat(edited.parentId).isNull()
        assertThat(rejected(repository.editGroup(a.id, "Alpha", null, UUID.randomUUID()))).isEqualTo(Rejection.NOT_FOUND)
    }

    // Tags

    @Test
    fun tagNamesAreUniqueIgnoringCase() = runTest {
        repository.createTag("prod")
        assertThat(rejected(repository.createTag("PROD"))).isEqualTo(Rejection.NAME_TAKEN)
        val staging = (repository.createTag("staging") as Outcome.Ok).value
        assertThat(rejected(repository.editTag(staging.id, "Prod", null))).isEqualTo(Rejection.NAME_TAKEN)
    }

    @Test
    fun hostTagsFollowTheRequestedSetAndIgnoreUnknownTags() = runTest {
        val h = host("h1")
        val prod = (repository.createTag("prod") as Outcome.Ok).value
        val db = (repository.createTag("db") as Outcome.Ok).value
        repository.setHostTags(h.id, setOf(prod.id, db.id, UUID.randomUUID()))
        assertThat(repository.snapshot().tagIdsByHost[h.id]).containsExactlyInAnyOrder(prod.id, db.id)
        repository.setHostTags(h.id, setOf(db.id))
        assertThat(repository.snapshot().tagIdsByHost[h.id]).containsExactly(db.id)
        repository.setHostTags(h.id, emptySet())
        assertThat(repository.snapshot().tagIdsByHost[h.id]).isNull()
    }

    @Test
    fun tagsWorkAcrossGroups() = runTest {
        val a = group("A")
        val b = group("B")
        val h1 = host("h1")
        val h2 = host("h2")
        repository.setHostGroup(h1.id, a.id)
        repository.setHostGroup(h2.id, b.id)
        val prod = (repository.createTag("prod") as Outcome.Ok).value
        repository.setHostTags(h1.id, setOf(prod.id))
        repository.setHostTags(h2.id, setOf(prod.id))
        val tagged = repository.snapshot().tagIdsByHost.filterValues { prod.id in it }.keys
        assertThat(tagged).containsExactlyInAnyOrder(h1.id, h2.id)
    }

    @Test
    fun deletingATagOrAHostRemovesTheirLinksOnly() = runTest {
        val h1 = host("h1")
        val h2 = host("h2")
        val prod = (repository.createTag("prod") as Outcome.Ok).value
        val db = (repository.createTag("db") as Outcome.Ok).value
        repository.setHostTags(h1.id, setOf(prod.id, db.id))
        repository.setHostTags(h2.id, setOf(prod.id))

        repository.deleteTag(prod.id)
        assertThat(repository.snapshot().tagIdsByHost[h1.id]).containsExactly(db.id)
        assertThat(repository.snapshot().tagIdsByHost[h2.id]).isNull()
        assertThat(database.hostDao().getById(h2.id)).isNotNull()

        database.hostDao().delete(h1)
        assertThat(repository.snapshot().hostTags).isEmpty()
        assertThat(repository.snapshot().tags.map { it.id }).containsExactly(db.id)
    }

    // Identities

    @Test
    fun identitiesNeedANameAndAUsername() = runTest {
        assertThat(rejected(repository.createIdentity("", "root", null))).isEqualTo(Rejection.BLANK_NAME)
        assertThat(rejected(repository.createIdentity("admin", "  ", null))).isEqualTo(Rejection.BLANK_USERNAME)
        repository.createIdentity("admin", "root", null)
        assertThat(rejected(repository.createIdentity("ADMIN", "root", null))).isEqualTo(Rejection.NAME_TAKEN)
    }

    @Test
    fun deletingAnIdentityReleasesHostsAndGroups() = runTest {
        val identity = (repository.createIdentity("admin", "root", null) as Outcome.Ok).value
        val g = (repository.createGroup("Lab", null, identityId = identity.id) as Outcome.Ok).value
        val h = host("h1")
        repository.setHostIdentity(h.id, identity.id)

        repository.deleteIdentity(identity.id)

        assertThat(database.hostDao().getById(h.id)!!.identityId).isNull()
        assertThat(repository.snapshot().groupsById[g.id]!!.identityId).isNull()
        assertThat(repository.snapshot().identities).isEmpty()
    }

    @Test
    fun hostIdentityMustExistUnlessOptingOut() = runTest {
        val h = host("h1")
        assertThat(rejected(repository.setHostIdentity(h.id, UUID.randomUUID()))).isEqualTo(Rejection.NOT_FOUND)
        assertThat(repository.setHostIdentity(h.id, EntityIds.NONE)).isInstanceOf(Outcome.Ok::class.java)
        assertThat(database.hostDao().getById(h.id)!!.identityId).isEqualTo(EntityIds.NONE)
    }

    @Test
    fun effectiveLoginFollowsTheGroupChainThroughTheSnapshot() = runTest {
        val identity = (repository.createIdentity("admin", "root", null) as Outcome.Ok).value
        val top = (repository.createGroup("Top", null, identityId = identity.id) as Outcome.Ok).value
        val sub = group("Sub", top.id)
        val h = host("h1")
        repository.setHostGroup(h.id, sub.id)

        val login = repository.snapshot().effectiveLogin(database.hostDao().getById(h.id)!!)

        assertThat(login.username).isEqualTo("root")
        assertThat(login.identity.source).isEqualTo(IdentitySource.GROUP)
        assertThat(login.identity.group?.id).isEqualTo(top.id)
    }

    @Test
    fun resolveHostUsesTheGroupIdentityAndNeverWritesItBack() = runTest {
        val identity = (repository.createIdentity("admin", "root", null) as Outcome.Ok).value
        val lab = (repository.createGroup("Lab", null, identityId = identity.id) as Outcome.Ok).value
        val h = host("h1")
        repository.setHostGroup(h.id, lab.id)

        val resolved = repository.resolveHost(database.hostDao().getById(h.id)!!)

        assertThat(resolved.username).isEqualTo("root")
        assertThat(database.hostDao().getById(h.id)!!.username).isEmpty()
    }

    @Test
    fun resolveHostSkipsPlainHostsWithoutReadingAnything() = runTest {
        val plain = host("h2")
        assertThat(repository.resolveHost(plain)).isSameAs(plain)
    }

    // Known hosts

    @Test
    fun allKnownHostsAreListedByHostName() = runTest {
        val h = host("h1")
        database.knownHostDao().insert(KnownHost(hostId = h.id, hostname = "b.lan", port = 22, hostKeyAlgo = "ssh-ed25519", hostKey = byteArrayOf(1)))
        database.knownHostDao().insert(KnownHost(hostId = h.id, hostname = "A.lan", port = 22, hostKeyAlgo = "ssh-ed25519", hostKey = byteArrayOf(2)))

        assertThat(repository.observeAllKnownHosts().first().map { it.hostname }).containsExactly("A.lan", "b.lan")
    }

    // Sync

    @Test
    fun changesReachTheSyncOutbox() = runTest {
        val g = group("Lab")
        val tag = (repository.createTag("prod") as Outcome.Ok).value
        val identity = (repository.createIdentity("admin", "root", null) as Outcome.Ok).value
        val db = database.openHelper.writableDatabase
        for ((kind, id) in listOf("host_groups" to g.id, "tags" to tag.id, "identities" to identity.id)) {
            db.query("SELECT operation FROM sync_outbox WHERE kind=? AND object_id=?", arrayOf(kind, id.toString())).use {
                assertThat(it.moveToFirst()).`as`("$kind queued").isTrue()
                assertThat(it.getString(0)).isEqualTo("put")
            }
        }
        repository.deleteTag(tag.id)
        db.query("SELECT operation FROM sync_outbox WHERE kind='tags' AND object_id=?", arrayOf(tag.id.toString())).use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("delete")
        }
    }
}
