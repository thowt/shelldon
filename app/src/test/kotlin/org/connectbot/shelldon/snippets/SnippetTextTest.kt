/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.snippets

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.keyboard.MacroAction
import org.junit.Test

class SnippetTextTest {
    private fun roundTrip(body: SnippetBody) = SnippetText.fromActions(SnippetText.toActions(body)!!)

    @Test
    fun aSingleCommandWithEnterIsOneTextStepEndingInCarriageReturn() {
        val actions = SnippetText.toActions(SnippetBody("uptime", pressEnter = true))
        assertThat(actions).isEqualTo(listOf<MacroAction>(MacroAction.Text("uptime\r")))
    }

    @Test
    fun withoutEnterNothingIsAppended() {
        assertThat(SnippetText.toActions(SnippetBody("ls -la", pressEnter = false))).isEqualTo(listOf<MacroAction>(MacroAction.Text("ls -la")))
    }

    @Test
    fun lineBreaksAreSentAsEnter() {
        val actions = SnippetText.toActions(SnippetBody("cd /var/log\ntail -n 50 syslog", pressEnter = true))
        assertThat(actions).isEqualTo(listOf<MacroAction>(MacroAction.Text("cd /var/log\rtail -n 50 syslog\r")))
    }

    @Test
    fun windowsAndOldMacLineEndingsAreTreatedAsLineBreaks() {
        val actions = SnippetText.toActions(SnippetBody("a\r\nb\rc", pressEnter = false))
        assertThat(actions).isEqualTo(listOf<MacroAction>(MacroAction.Text("a\rb\rc")))
    }

    @Test
    fun aTrailingLineBreakBecomesTheEnterSwitch() {
        assertThat(SnippetText.normalized(SnippetBody("ls\n\n", pressEnter = false))).isEqualTo(SnippetBody("ls", pressEnter = true))
    }

    @Test
    fun editingAndSavingAgainChangesNothing() {
        for (body in listOf(
            SnippetBody("uptime", true),
            SnippetBody("uptime", false),
            SnippetBody("a\nb\nc", true),
            SnippetBody("a\n\nb", false),
            SnippetBody("tab\tseparated", false),
        )) {
            assertThat(roundTrip(body)).isEqualTo(body)
        }
    }

    @Test
    fun blankTextIsNotASnippet() {
        assertThat(SnippetText.toActions(SnippetBody("", true))).isNull()
        assertThat(SnippetText.toActions(SnippetBody("  \n ", true))).isNull()
    }

    @Test
    fun onlyTextOnlyMacrosAreSnippets() {
        assertThat(SnippetText.fromActions(listOf(MacroAction.Key(key = "enter")))).isNull()
        assertThat(SnippetText.fromActions(listOf(MacroAction.Text("ls"), MacroAction.Key(key = "enter")))).isNull()
        assertThat(SnippetText.fromActions(listOf(MacroAction.Bytes("0d")))).isNull()
        assertThat(SnippetText.fromActions(emptyList())).isNull()
    }

    @Test
    fun severalTextStepsAreJoined() {
        val body = SnippetText.fromActions(listOf(MacroAction.Text("cd /tmp\r"), MacroAction.Text("ls\r")))
        assertThat(body).isEqualTo(SnippetBody("cd /tmp\nls", true))
    }

    @Test
    fun aMacroThatSendsControlCharactersIsNotShownAsPlainText() {
        assertThat(SnippetText.fromActions(listOf(MacroAction.Text("\u0003")))).isNull()
        assertThat(SnippetText.fromActions(listOf(MacroAction.Text("echo \u001b[31mred")))).isNull()
        assertThat(SnippetText.fromActions(listOf(MacroAction.Text("rm\u007f")))).isNull()
        assertThat(SnippetText.isPlain("tab\tand\nnewline\r")).isTrue()
    }

    @Test
    fun aMacroThatIsOnlyEnterIsNotASnippet() {
        assertThat(SnippetText.fromActions(listOf(MacroAction.Text("\r")))).isNull()
    }

    @Test
    fun scriptsAreTheOnesWithMoreThanOneLine() {
        assertThat(SnippetBody("ls", true).isScript).isFalse()
        assertThat(SnippetBody("ls\npwd", false).isScript).isTrue()
        assertThat(SnippetBody("a\nb\nc", false).lineCount).isEqualTo(3)
        assertThat(SnippetBody("\n\n  first real line \nsecond", false).firstLine).isEqualTo("first real line")
    }
}
