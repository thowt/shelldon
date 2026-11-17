/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.snippets

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.connectbot.data.ConnectBotDatabase
import org.connectbot.data.keyboard.KeyboardMacro
import org.connectbot.data.keyboard.KeyboardRepository
import org.connectbot.data.keyboard.MacroFormat
import org.connectbot.di.CoroutineDispatchers
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class Snippet(val id: UUID, val name: String, val body: SnippetBody) {
    /** The keyboard macro document that sends this snippet. */
    val document: String by lazy { MacroFormat.encode(requireNotNull(SnippetText.toActions(body))) }
}

enum class SnippetRejection {
    BLANK_NAME,
    NAME_TOO_LONG,
    NAME_TAKEN,
    BLANK_TEXT,
    TOO_LARGE,
    NOT_PLAIN,
    NOT_FOUND,
}

sealed interface SnippetResult<out T> {
    data class Ok<out T>(val value: T) : SnippetResult<T>

    data class Rejected(val reason: SnippetRejection) : SnippetResult<Nothing>

    /** A keyboard button or a post-login action still uses the snippet. [users] names them. */
    data class InUse(val users: List<String>) : SnippetResult<Nothing>
}

/**
 * Snippets live in the keyboard macro library, next to the macros that keyboard buttons and
 * post-login actions use, so one editor of the data, one backup and one sync cover both. This class
 * adds the rules a text snippet needs on top of [KeyboardRepository].
 */
@Singleton
class SnippetRepository @Inject constructor(
    private val database: ConnectBotDatabase,
    private val keyboard: KeyboardRepository,
    private val dispatchers: CoroutineDispatchers,
) {
    /** Every plain-text macro, by name. Key-press and raw-byte macros are not snippets. */
    fun observeSnippets(): Flow<List<Snippet>> = database.keyboardDao().observeMacros().map { macros -> macros.mapNotNull(::toSnippet) }

    /** @param existing the snippet being edited, or null for a new one */
    suspend fun save(existing: UUID?, name: String, body: SnippetBody): SnippetResult<UUID> = withContext(dispatchers.io) {
        val trimmed = name.trim()
        when {
            trimmed.isEmpty() -> return@withContext SnippetResult.Rejected(SnippetRejection.BLANK_NAME)
            trimmed.length > MAX_NAME_LENGTH -> return@withContext SnippetResult.Rejected(SnippetRejection.NAME_TOO_LONG)
            body.text.length > SnippetText.MAX_TEXT_LENGTH -> return@withContext SnippetResult.Rejected(SnippetRejection.TOO_LARGE)
            !SnippetText.isPlain(body.text) -> return@withContext SnippetResult.Rejected(SnippetRejection.NOT_PLAIN)
        }
        val actions = SnippetText.toActions(body) ?: return@withContext SnippetResult.Rejected(SnippetRejection.BLANK_TEXT)
        val macros = database.keyboardDao().macros()
        if (existing != null && macros.none { it.id == existing }) return@withContext SnippetResult.Rejected(SnippetRejection.NOT_FOUND)
        // Macro names are shared with the keyboard settings, where they are picked from a list.
        if (macros.any { it.id != existing && it.name.equals(trimmed, ignoreCase = true) }) {
            return@withContext SnippetResult.Rejected(SnippetRejection.NAME_TAKEN)
        }
        val document = try {
            MacroFormat.encode(actions)
        } catch (e: IllegalArgumentException) {
            return@withContext SnippetResult.Rejected(SnippetRejection.TOO_LARGE)
        }
        val id = existing ?: UUID.randomUUID()
        keyboard.saveMacro(KeyboardMacro(id = id, name = trimmed, document = document))
        SnippetResult.Ok(id)
    }

    /** Refuses, naming the users, if a keyboard button or an automation still uses the snippet. */
    suspend fun delete(id: UUID): SnippetResult<Unit> = withContext(dispatchers.io) {
        val users = keyboard.macroUsage(id).first()
        if (users.isNotEmpty()) return@withContext SnippetResult.InUse(users.map { it.name }.distinct())
        try {
            keyboard.deleteMacro(id)
            SnippetResult.Ok(Unit)
        } catch (e: IllegalArgumentException) {
            // Used between the check and the delete.
            SnippetResult.InUse(emptyList())
        }
    }

    private fun toSnippet(macro: KeyboardMacro): Snippet? {
        val actions = runCatching { MacroFormat.decode(macro.document) }.getOrNull() ?: return null
        return SnippetText.fromActions(actions)?.let { Snippet(macro.id, macro.name, it) }
    }

    companion object {
        const val MAX_NAME_LENGTH = 80
    }
}
