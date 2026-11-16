/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.data.groups

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.ConnectBotDatabase
import org.connectbot.data.sync.SyncDatabase
import org.connectbot.util.HostConstants
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class GroupsMigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun version20UpgradeKeepsHostsAddsTablesAndRefreshesTheHostSyncTrigger() = runTest {
        val name = "groups-migration-test"
        context.deleteDatabase(name)
        val hostId = UUID.randomUUID()
        val schema = JSONObject(File("schemas/org.connectbot.data.ConnectBotDatabase/20.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            db.execSQL(
                "INSERT INTO hosts (id,nickname,protocol,hostname,username,port,last_connect,use_keys,pubkey_id,want_session,compression,stay_connected,quick_disconnect,scrollback_lines,use_ctrl_alt_as_meta_key) " +
                    "VALUES (?, 'Existing', 'ssh', 'example.com', 'alice', 22, 0, 1, ?, 1, 0, 0, 0, 140, 0)",
                arrayOf(hostId.toString(), HostConstants.PUBKEYID_ANY.toString()),
            )
            // An old trigger that does not know the new columns: the migration must drop it so it is rebuilt.
            db.execSQL("CREATE TRIGGER sync_hosts_update AFTER UPDATE ON hosts BEGIN SELECT 1; END")
            db.version = 20
        }
        val database = Room.databaseBuilder(context, ConnectBotDatabase::class.java, name)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    SyncDatabase.install(db)
                }
            })
            .build()
        try {
            val host = database.hostDao().getById(hostId)!!
            assertThat(host.nickname).isEqualTo("Existing")
            assertThat(host.groupId).isNull()
            assertThat(host.identityId).isNull()

            val db = database.openHelper.writableDatabase
            for (table in listOf("host_groups", "tags", "host_tags", "identities")) {
                db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
                    assertThat(it.moveToFirst()).`as`("table $table exists").isTrue()
                }
            }
            db.query("SELECT sql FROM sqlite_master WHERE name='sync_hosts_update'").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).contains("group_id").contains("identity_id")
            }

            // The upgraded database works end to end, and group changes reach the sync outbox.
            val repository = ShelldonGroupRepository(database)
            val group = (repository.createGroup("Lab", null) as Outcome.Ok).value
            assertThat(repository.setHostGroup(hostId, group.id)).isEqualTo(Outcome.Ok(Unit))
            assertThat(database.hostDao().getById(hostId)!!.groupId).isEqualTo(group.id)
            db.query("SELECT operation FROM sync_outbox WHERE kind='hosts' AND object_id=?", arrayOf(hostId.toString())).use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("put")
            }
            db.query("SELECT operation FROM sync_outbox WHERE kind='host_groups' AND object_id=?", arrayOf(group.id.toString())).use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("put")
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
