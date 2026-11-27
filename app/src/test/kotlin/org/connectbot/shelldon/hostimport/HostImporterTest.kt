/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.hostimport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.ConnectBotDatabase
import org.connectbot.data.entity.Host
import org.connectbot.data.sync.SyncDatabase
import org.connectbot.shelldon.data.groups.ShelldonGroupRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostImporterTest {
    private lateinit var database: ConnectBotDatabase
    private lateinit var importer: HostImporter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ConnectBotDatabase::class.java).allowMainThreadQueries().build()
        SyncDatabase.install(database.openHelper.writableDatabase)
        importer = HostImporter(database, ShelldonGroupRepository(database))
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun csvSource(text: String): HostSource = checkNotNull(CsvImport.open(text))

    private suspend fun importCsv(text: String, options: HostImportOptions = HostImportOptions()): Pair<HostImportPlan, HostImportResult> {
        val plan = importer.plan(csvSource(text), importer.saved(), options)
        return plan to importer.import(plan)
    }

    private suspend fun hostsByName() = database.hostDao().getAll().associateBy { it.nickname }

    @Test
    fun savesHostsInNestedFoldersWithTags() = runTest {
        val (_, result) = importCsv(
            "name,host,port,user,folder,tags\n" +
                "web1,10.0.0.1,2222,root,Prod/EU,\"web, edge\"\n" +
                "web2,10.0.0.2,,deploy,Prod/EU,web\n" +
                "db1,10.0.0.3,,,Prod,db\n" +
                "lab,10.0.0.4,,,,\n",
        )
        assertThat(result.hostsAdded).isEqualTo(4)
        assertThat(result.foldersCreated).isEqualTo(2)
        assertThat(result.tagsCreated).isEqualTo(3)

        val hosts = hostsByName()
        val web1 = hosts.getValue("web1")
        assertThat(web1.hostname).isEqualTo("10.0.0.1")
        assertThat(web1.port).isEqualTo(2222)
        assertThat(web1.username).isEqualTo("root")
        assertThat(web1.protocol).isEqualTo("ssh")

        val groups = database.shelldonGroupDao().getGroups()
        val prod = groups.single { it.name == "Prod" }
        val eu = groups.single { it.name == "EU" }
        assertThat(prod.parentId).isNull()
        assertThat(eu.parentId).isEqualTo(prod.id)
        assertThat(web1.groupId).isEqualTo(eu.id)
        assertThat(hosts.getValue("web2").groupId).isEqualTo(eu.id)
        assertThat(hosts.getValue("db1").groupId).isEqualTo(prod.id)
        assertThat(hosts.getValue("lab").groupId).isNull()

        val tagsById = database.shelldonGroupDao().getTags().associateBy { it.id }
        val tagsOf = database.shelldonGroupDao().getAllHostTags().groupBy({ it.hostId }, { tagsById.getValue(it.tagId).name })
        assertThat(tagsOf.getValue(web1.id)).containsExactlyInAnyOrder("web", "edge")
        assertThat(tagsOf.getValue(hosts.getValue("web2").id)).containsExactly("web")
        assertThat(tagsOf.containsKey(hosts.getValue("lab").id)).isFalse()
    }

    @Test
    fun existingFoldersAndTagsAreReusedIgnoringCase() = runTest {
        importCsv("name,host,folder,tags\nweb1,10.0.0.1,Prod,Web\n")
        val before = database.shelldonGroupDao().getGroups().size
        val (_, result) = importCsv("name,host,folder,tags\nweb2,10.0.0.2,prod,WEB\n")
        assertThat(result.hostsAdded).isEqualTo(1)
        assertThat(result.foldersCreated).isEqualTo(0)
        assertThat(result.tagsCreated).isEqualTo(0)
        assertThat(database.shelldonGroupDao().getGroups().size).isEqualTo(before)
        assertThat(database.shelldonGroupDao().getTags().size).isEqualTo(1)
    }

    @Test
    fun importingTheSameFileTwiceAddsNothingTheSecondTime() = runTest {
        val text = "name,host,folder\nweb1,10.0.0.1,Prod\ndb1,10.0.0.2,Prod\n"
        importCsv(text)
        val (plan, result) = importCsv(text)
        assertThat(plan.newHosts).isEmpty()
        assertThat(result.hostsAdded).isEqualTo(0)
        assertThat(result.hostsSkipped).isEqualTo(2)
        assertThat(database.hostDao().getAll()).hasSize(2)
    }

    @Test
    fun aClashingNameKeepsTheExistingHostUntouched() = runTest {
        database.hostDao().insert(Host(nickname = "db", hostname = "10.9.9.9", username = "old", port = 22))
        importCsv("name,host,user\ndb,10.0.0.2,new\n")
        val hosts = hostsByName()
        assertThat(hosts.getValue("db").hostname).isEqualTo("10.9.9.9")
        assertThat(hosts.getValue("db").username).isEqualTo("old")
        assertThat(hosts.getValue("db (2)").hostname).isEqualTo("10.0.0.2")
    }

    @Test
    fun telnetHostsAreSavedWithoutAUserNameAndOnlyOnRequest() = runTest {
        val text = "name,host,user,protocol\nrouter,192.168.1.1,admin,telnet\nweb,10.0.0.1,root,ssh\n"
        importCsv(text)
        assertThat(hostsByName().keys).containsExactly("web")
        database.hostDao().getAll().forEach { database.hostDao().delete(it) }

        importCsv(text, HostImportOptions(includeTelnet = true))
        val router = hostsByName().getValue("router")
        assertThat(router.protocol).isEqualTo("telnet")
        assertThat(router.port).isEqualTo(23)
        assertThat(router.username).isEmpty()
    }

    @Test
    fun aNameTakenSincePlanningIsSkippedInsteadOfFailingTheWholeImport() = runTest {
        val plan = importer.plan(csvSource("name,host\nweb1,10.0.0.1\nweb2,10.0.0.2\n"), importer.saved(), HostImportOptions())
        database.hostDao().insert(Host(nickname = "web1", hostname = "10.5.5.5", port = 22))
        val result = importer.import(plan)
        assertThat(result.hostsAdded).isEqualTo(1)
        assertThat(result.hostsSkipped).isEqualTo(1)
        assertThat(hostsByName().keys).containsExactlyInAnyOrder("web1", "web2")
    }

    @Test
    fun folderPathsAreCutAtTheMaximumDepth() = runTest {
        val deep = (1..20).joinToString("/") { "f$it" }
        val (_, result) = importCsv("name,host,folder\nx,10.0.0.1,$deep\n")
        assertThat(result.hostsAdded).isEqualTo(1)
        assertThat(database.shelldonGroupDao().getGroups()).hasSize(16)
    }

    @Test
    fun nothingIsWrittenForAnEmptyPlan() = runTest {
        val (plan, result) = importCsv("name,host\n")
        assertThat(plan.hosts).isEmpty()
        assertThat(result.hostsAdded).isEqualTo(0)
        assertThat(database.hostDao().getAll()).isEmpty()
    }
}
