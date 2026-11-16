/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.snippets

import org.connectbot.data.keyboard.MacroAction

/**
 * What the snippet editor shows: plain text with `\n` between lines, and whether Enter is pressed
 * after the last line (so a one-line snippet either runs or just waits at the prompt).
 */
data class SnippetBody(val text: String, val pressEnter: Boolean) {
    /** More than one line: the first lines run as soon as they are sent. */
    val isScript: Boolean get() = '\n' in text

    val lineCount: Int get() = text.count { it == '\n' } + 1

    val firstLine: String get() = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
}

/**
 * Snippets are keyboard macros that consist of text only. The editor works on plain text; this
 * converts to and from the macro steps that the keyboard already knows how to send.
 *
 * A line break is sent as Enter, which is a carriage return (CR), not a line feed.
 */
object SnippetText {
    const val MAX_TEXT_LENGTH = 20_000
    private const val ENTER = '\r'

    /** Plain means what you see is what is sent: no control characters other than tab and line breaks. */
    fun isPlain(text: String): Boolean = text.none { (it < ' ' && it != '\t' && it != '\r' && it != '\n') || it == '\u007f' }

    /**
     * One canonical form per snippet: line endings are `\n`, and a line break at the very end becomes
     * the Enter switch, so that editing a snippet and saving it again changes nothing.
     */
    fun normalized(body: SnippetBody): SnippetBody {
        var text = body.text.replace("\r\n", "\n").replace('\r', '\n')
        var enter = body.pressEnter
        while (text.endsWith('\n')) {
            text = text.dropLast(1)
            enter = true
        }
        return SnippetBody(text, enter)
    }

    /** @return the steps to store, or null if there is nothing to send */
    fun toActions(body: SnippetBody): List<MacroAction>? {
        val clean = normalized(body)
        if (clean.text.isBlank()) return null
        val wire = clean.text.replace('\n', ENTER) + if (clean.pressEnter) ENTER.toString() else ""
        return listOf(MacroAction.Text(wire))
    }

    /** @return the snippet these steps stand for, or null if they are not a plain text snippet */
    fun fromActions(actions: List<MacroAction>): SnippetBody? {
        if (actions.isEmpty() || actions.any { it !is MacroAction.Text }) return null
        val wire = actions.joinToString("") { (it as MacroAction.Text).text }
        if (!isPlain(wire)) return null
        val body = normalized(SnippetBody(wire.replace("\r\n", "\n").replace('\r', '\n'), pressEnter = false))
        return if (body.text.isBlank()) null else body
    }
}
