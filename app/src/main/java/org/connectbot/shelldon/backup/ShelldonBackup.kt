/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.backup

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import org.connectbot.data.ConnectBotDatabase
import org.connectbot.data.EntityIds
import org.connectbot.data.HostConfigJson
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Pubkey
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.shelldon.data.groups.HostGroup
import org.connectbot.shelldon.data.groups.HostTag
import org.connectbot.shelldon.data.groups.Identity
import org.connectbot.shelldon.data.groups.Tag
import org.connectbot.shelldon.vault.KdfParams
import org.connectbot.util.SecurePasswordStorage
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.encoding.Base64

/** What goes into a backup besides hosts and their settings. */
data class BackupOptions(
    /** Private keys that can be exported (not hardware-backed ones). */
    val includeKeys: Boolean = true,
    /** Saved host passwords. Off by default: a password in a file is a password in a file. */
    val includePasswords: Boolean = false,
)

data class BackupImportResult(
    val hostsImported: Int,
    val hostsSkipped: Int,
    val keysImported: Int,
    val keysSkipped: Int,
    val foldersImported: Int,
    val tagsImported: Int,
    val identitiesImported: Int,
    val passwordsRestored: Int,
)

/**
 * Encrypted backup and restore of everything Shelldon holds: hosts with their settings, port
 * forwards, folders, tags, identities and, if asked for, private keys and saved passwords.
 *
 * Restoring merges. Nothing that already exists is changed or replaced: a host, key, folder, tag or
 * identity with the same name is kept as it is, and only what is missing is added, under new ids.
 * Folders, tags, identities, keys and passwords are attached only to hosts that the restore itself
 * created, so a restore can never alter a host you already had.
 *
 * Needs the vault unlocked, because keys and passwords are read from it and written back into it.
 */
@Singleton
class ShelldonBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ConnectBotDatabase,
    private val passwords: SecurePasswordStorage,
    private val pubkeys: PubkeyRepository,
    private val dispatchers: CoroutineDispatchers,
) {
    /** Key-derivation cost for new backups. Only tests lower it. */
    @VisibleForTesting
    internal var kdfParams: KdfParams = BackupCrypto.DEFAULT_PARAMS

    /** @return the encrypted backup file contents */
    suspend fun export(backupPassword: String, options: BackupOptions): ByteArray {
        val document = withContext(dispatchers.io) { buildDocument(options) }
        return withContext(dispatchers.default) {
            val bytes = document.toByteArray(Charsets.UTF_8)
            try {
                BackupCrypto.encrypt(bytes, backupPassword, kdfParams)
            } finally {
                bytes.fill(0)
            }
        }
    }

    /**
     * @throws BackupException if the file is not a backup, is too new, or the password is wrong
     */
    suspend fun import(file: ByteArray, backupPassword: String): BackupImportResult {
        val plain = withContext(dispatchers.default) { BackupCrypto.decrypt(file, backupPassword) }
        val root = try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw BackupException(BackupProblem.NOT_A_BACKUP, e)
        } finally {
            plain.fill(0)
        }
        if (root.optString("app") != APP) throw BackupException(BackupProblem.NOT_A_BACKUP)
        if (root.optInt("format", 0) !in 1..FORMAT) throw BackupException(BackupProblem.UNSUPPORTED_VERSION)
        return withContext(dispatchers.io) { database.withTransaction { restore(root) } }
    }

    private suspend fun buildDocument(options: BackupOptions): String {
        val groupDao = database.shelldonGroupDao()
        val hosts = database.hostDao().getAll().filterNot { it.isTemporary }
        val root = JSONObject()
        root.put("app", APP)
        root.put("format", FORMAT)
        root.put("createdAt", System.currentTimeMillis())
        root.put("config", JSONObject(HostConfigJson.exportToJson(context, database, pretty = false).first))

        root.put(
            "hostLinks",
            JSONArray(
                hosts.map {
                    JSONObject()
                        .put("hostId", it.id.toString())
                        .put("groupId", it.groupId?.toString())
                        .put("identityId", it.identityId?.toString())
                        .put("pubkeyId", it.pubkeyId.toString())
                },
            ),
        )
        root.put(
            "groups",
            JSONArray(
                groupDao.getGroups().map {
                    JSONObject()
                        .put("id", it.id.toString())
                        .put("parentId", it.parentId?.toString())
                        .put("name", it.name)
                        .put("color", it.color)
                        .put("sortOrder", it.sortOrder)
                        .put("identityId", it.identityId?.toString())
                },
            ),
        )
        root.put("tags", JSONArray(groupDao.getTags().map { JSONObject().put("id", it.id.toString()).put("name", it.name).put("color", it.color) }))
        root.put("hostTags", JSONArray(groupDao.getAllHostTags().map { JSONObject().put("hostId", it.hostId.toString()).put("tagId", it.tagId.toString()) }))
        root.put(
            "identities",
            JSONArray(
                groupDao.getIdentities().map {
                    JSONObject().put("id", it.id.toString()).put("name", it.name).put("username", it.username).put("keyId", it.keyId?.toString())
                },
            ),
        )

        if (options.includeKeys) {
            root.put(
                "keys",
                JSONArray(
                    pubkeys.getExportable().filter { it.privateKey != null }.map {
                        JSONObject()
                            .put("id", it.id.toString())
                            .put("nickname", it.nickname)
                            .put("type", it.type)
                            .put("privateKey", Base64.encode(it.privateKey!!))
                            .put("publicKey", Base64.encode(it.publicKey))
                            .put("encrypted", it.encrypted)
                            .put("startup", it.startup)
                            .put("confirmation", it.confirmation)
                            .put("createdDate", it.createdDate)
                    },
                ),
            )
        }
        if (options.includePasswords) {
            root.put(
                "passwords",
                JSONArray(
                    hosts.mapNotNull { host ->
                        passwords.getPassword(host.id)?.let { JSONObject().put("hostId", host.id.toString()).put("password", it) }
                    },
                ),
            )
        }
        return root.toString()
    }

    private suspend fun restore(root: JSONObject): BackupImportResult {
        val groupDao = database.shelldonGroupDao()

        // Keys first: identities and hosts refer to them.
        val keyMap = mutableMapOf<String, UUID>()
        var keysImported = 0
        var keysSkipped = 0
        val existingKeys = database.pubkeyDao().getAll().associateBy { it.nickname }
        for (key in root.objects("keys")) {
            val existing = existingKeys[key.getString("nickname")]
            if (existing != null) {
                keyMap[key.getString("id")] = existing.id
                keysSkipped++
                continue
            }
            val restored = Pubkey(
                nickname = key.getString("nickname"),
                type = key.getString("type"),
                privateKey = Base64.decode(key.getString("privateKey")),
                publicKey = Base64.decode(key.getString("publicKey")),
                encrypted = key.optBoolean("encrypted"),
                startup = key.optBoolean("startup"),
                confirmation = key.optBoolean("confirmation"),
                createdDate = key.optLong("createdDate"),
            )
            pubkeys.save(restored)
            keyMap[key.getString("id")] = restored.id
            keysImported++
        }

        val identityMap = mutableMapOf<String, UUID>()
        var identitiesImported = 0
        val identitiesByName = groupDao.getIdentities().associateBy { it.name.lowercase() }.toMutableMap()
        for (item in root.objects("identities")) {
            val name = item.getString("name")
            val existing = identitiesByName[name.lowercase()]
            if (existing != null) {
                identityMap[item.getString("id")] = existing.id
                continue
            }
            val identity = Identity(
                name = name,
                username = item.optString("username", ""),
                keyId = item.optString("keyId").takeIf { it.isNotEmpty() }?.let { keyMap[it] },
            )
            groupDao.insertIdentity(identity)
            identitiesByName[name.lowercase()] = identity
            identityMap[item.getString("id")] = identity.id
            identitiesImported++
        }

        // A reference to an identity from the file: none, "explicitly none", or one we know.
        fun identityRef(old: String?): UUID? = when {
            old.isNullOrEmpty() -> null
            old == EntityIds.NONE.toString() -> EntityIds.NONE
            else -> identityMap[old]
        }

        // Folders, parents before children. Anything left over (damaged parent links) goes to the top level.
        val folderMap = mutableMapOf<String, UUID>()
        var foldersImported = 0
        val knownFolders = groupDao.getGroups().toMutableList()
        val pending = root.objects("groups").toMutableList()
        var forceTopLevel = false
        while (pending.isNotEmpty()) {
            val ready = pending.filter { forceTopLevel || it.optString("parentId").isEmpty() || folderMap.containsKey(it.optString("parentId")) }
            if (ready.isEmpty()) {
                forceTopLevel = true
                continue
            }
            for (item in ready) {
                pending.remove(item)
                val parent = item.optString("parentId").takeIf { it.isNotEmpty() }?.let { folderMap[it] }
                val name = item.getString("name")
                val sibling = knownFolders.firstOrNull { it.parentId == parent && it.name.equals(name, ignoreCase = true) }
                if (sibling != null) {
                    folderMap[item.getString("id")] = sibling.id
                    continue
                }
                val folder = HostGroup(
                    parentId = parent,
                    name = name,
                    color = item.optString("color").takeIf { it.isNotEmpty() },
                    sortOrder = item.optInt("sortOrder", 0),
                    identityId = identityRef(item.optString("identityId")),
                )
                groupDao.insertGroup(folder)
                knownFolders.add(folder)
                folderMap[item.getString("id")] = folder.id
                foldersImported++
            }
        }

        val tagMap = mutableMapOf<String, UUID>()
        var tagsImported = 0
        val tagsByName = groupDao.getTags().associateBy { it.name.lowercase() }.toMutableMap()
        for (item in root.objects("tags")) {
            val name = item.getString("name")
            val existing = tagsByName[name.lowercase()]
            if (existing != null) {
                tagMap[item.getString("id")] = existing.id
                continue
            }
            val tag = Tag(name = name, color = item.optString("color").takeIf { it.isNotEmpty() })
            groupDao.insertTag(tag)
            tagsByName[name.lowercase()] = tag
            tagMap[item.getString("id")] = tag.id
            tagsImported++
        }

        // Hosts, profiles, port forwards and the rest go through the regular importer.
        val (counts, details) = HostConfigJson.importWithDetails(context, database, root.getJSONObject("config").toString())
        val hostMap = details.idMappings["hosts"].orEmpty()
        val newHosts = details.insertedIds["hosts"].orEmpty()
        fun createdHost(old: String): String? = hostMap[old]?.takeIf { it in newHosts }

        val db = database.openHelper.writableDatabase
        for (link in root.objects("hostLinks")) {
            val host = createdHost(link.getString("hostId")) ?: continue
            val folder = link.optString("groupId").takeIf { it.isNotEmpty() }?.let { folderMap[it] }
            val identity = identityRef(link.optString("identityId"))
            val key = link.optString("pubkeyId").takeIf { it.isNotEmpty() }?.let { keyMap[it] }
            db.execSQL(
                "UPDATE hosts SET group_id = ?, identity_id = ?, pubkey_id = COALESCE(?, pubkey_id) WHERE id = ?",
                arrayOf<Any?>(folder?.toString(), identity?.toString(), key?.toString(), host),
            )
        }

        val tagLinks = root.objects("hostTags").mapNotNull { item ->
            val host = createdHost(item.getString("hostId")) ?: return@mapNotNull null
            val tag = tagMap[item.getString("tagId")] ?: return@mapNotNull null
            HostTag.of(UUID.fromString(host), tag)
        }.distinctBy { it.id }
        if (tagLinks.isNotEmpty()) groupDao.insertHostTags(tagLinks)

        var passwordsRestored = 0
        for (item in root.objects("passwords")) {
            val host = createdHost(item.getString("hostId")) ?: continue
            passwords.savePassword(UUID.fromString(host), item.getString("password"))
            passwordsRestored++
        }

        return BackupImportResult(
            hostsImported = counts.hostsImported,
            hostsSkipped = counts.hostsSkipped,
            keysImported = keysImported,
            keysSkipped = keysSkipped,
            foldersImported = foldersImported,
            tagsImported = tagsImported,
            identitiesImported = identitiesImported,
            passwordsRestored = passwordsRestored,
        )
    }

    private fun JSONObject.objects(name: String): List<JSONObject> {
        val array = optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private companion object {
        const val APP = "shelldon"
        const val FORMAT = 1
    }
}
