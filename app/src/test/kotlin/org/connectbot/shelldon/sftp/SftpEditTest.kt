/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.sshlib.SftpAttributes
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpFileHandle
import org.connectbot.sshlib.SftpOpenFlag
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode
import org.junit.Test

class SftpEditTest {
    /** The fake server with hooks for the ways a real one misbehaves while a file is being edited. */
    private class Spy(val inner: FakeSftpClient, val target: String) : SftpClient by inner {
        val opened = mutableListOf<Triple<String, Set<SftpOpenFlag>, SftpAttributes>>()
        var mtime = 1_700_000_000

        /** After this many lstat calls on [target], somebody else changes the file. */
        var changeAfterTargetLstats: Int? = null
        var failOpen: ((String, Set<SftpOpenFlag>) -> SftpStatusCode?)? = null
        var failWrite: ((String) -> SftpStatusCode?)? = null
        var reportedSize: ((Long?) -> Long?)? = null
        private var targetLstats = 0
        private val paths = HashMap<SftpFileHandle, String>()

        override suspend fun open(path: String, flags: Set<SftpOpenFlag>, attrs: SftpAttributes): SftpResult<SftpFileHandle> {
            opened += Triple(path, flags, attrs)
            failOpen?.invoke(path, flags)?.let { return SftpResult.ServerError(it, it.name) }
            return inner.open(path, flags, attrs).also { if (it is SftpResult.Success) paths[it.value] = path }
        }

        override suspend fun write(handle: SftpFileHandle, offset: Long, data: ByteArray): SftpResult<Unit> {
            failWrite?.invoke(paths[handle].orEmpty())?.let { return SftpResult.ServerError(it, it.name) }
            return inner.write(handle, offset, data)
        }

        override suspend fun lstat(path: String): SftpResult<SftpAttributes> {
            val result = inner.lstat(path)
            val shown = (result as? SftpResult.Success)?.value?.copy(mtime = mtime, size = reportedSize?.invoke((result as SftpResult.Success).value.size) ?: (result as SftpResult.Success).value.size)
            if (path == target && ++targetLstats == changeAfterTargetLstats) mtime += 7
            return if (shown != null) SftpResult.Success(shown) else result
        }
    }

    private val fake = FakeSftpClient().apply { folders += "/home/test" }
    private val path = "/home/test/sshd_config"
    private val original = "PermitRootLogin no\nPort 22\n".toByteArray()
    private val spy = Spy(fake, path)

    init {
        fake.files[path] = original
    }

    private fun open(): EditOpen.Opened = runBlocking { SftpEdit.open(spy, path) as EditOpen.Opened }

    private fun save(snapshot: EditSnapshot, text: String, force: Boolean = false): EditSave = runBlocking { SftpEdit.save(spy, snapshot, text.toByteArray(), force) }

    private fun leftovers() = fake.files.keys.filter { it.endsWith(".shelldon-edit") }

    private fun failureOf(block: suspend () -> Unit): SftpFailure? = try {
        runBlocking { block() }
        null
    } catch (e: SftpFailure) {
        e
    }

    // --- Opening ---

    @Test
    fun aTextFileOpensWithItsTextAndASnapshot() {
        val opened = open()

        assertThat(opened.file.text).isEqualTo("PermitRootLogin no\nPort 22\n")
        assertThat(opened.snapshot).isEqualTo(EditSnapshot(path, original.size.toLong(), 1_700_000_000))
    }

    @Test
    fun aBigFileIsRefusedWithoutReadingIt() {
        fake.files[path] = ByteArray(SftpEditText.MAX_BYTES + 1) { 'x'.code.toByte() }

        val result = runBlocking { SftpEdit.open(spy, path) }

        assertThat(result).isEqualTo(EditOpen.Refused(EditRefusal.TOO_LARGE))
        assertThat(fake.readRequests.get()).isEqualTo(0)
    }

    @Test
    fun aFileThatGrewPastTheLimitWhileBeingReadIsRefused() {
        fake.files[path] = ByteArray(SftpEditText.MAX_BYTES + 5) { 'x'.code.toByte() }
        spy.reportedSize = { null } // a server that does not say how big the file is

        val result = runBlocking { SftpEdit.open(spy, path) }

        assertThat(result).isEqualTo(EditOpen.Refused(EditRefusal.TOO_LARGE))
    }

    @Test
    fun aBinaryFileIsRefused() {
        fake.files[path] = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0, 0, 1)

        assertThat(runBlocking { SftpEdit.open(spy, path) }).isEqualTo(EditOpen.Refused(EditRefusal.BINARY))
    }

    @Test
    fun aLinkIsNotFollowedOrEdited() {
        fake.links["/home/test/link"] = path

        val failure = failureOf { SftpEdit.open(spy, "/home/test/link") }

        assertThat(failure).isNotNull()
    }

    @Test
    fun aMissingFileIsNotFound() {
        assertThat(failureOf { SftpEdit.open(spy, "/home/test/nope") }?.problem).isEqualTo(SftpProblem.NOT_FOUND)
    }

    // --- Saving ---

    @Test
    fun aSaveWritesTheNewContentAndLeavesNothingElseBehind() {
        val opened = open()

        val result = save(opened.snapshot, "PermitRootLogin no\nPort 2222\n")

        assertThat(result).isInstanceOf(EditSave.Saved::class.java)
        assertThat(String(fake.files.getValue(path))).isEqualTo("PermitRootLogin no\nPort 2222\n")
        assertThat(leftovers()).isEmpty()
        assertThat((result as EditSave.Saved).snapshot.size).isEqualTo(29L)
    }

    @Test
    fun theCopyOnTheServerIsCreatedForTheOwnerOnlyAndNeverReplacesAFile() {
        val opened = open()

        save(opened.snapshot, "x\n")

        val creation = spy.opened.single { it.first.endsWith(".shelldon-edit") && SftpOpenFlag.CREATE in it.second && SftpOpenFlag.EXCLUDE in it.second }
        assertThat(creation.third.permissions).isEqualTo(0x180)
        assertThat(creation.first).startsWith("/home/test/.sshd_config.")
    }

    @Test
    fun theOriginalIsOpenedWithoutTruncatingUntilTheCopyIsComplete() {
        val opened = open()
        var originalWhenTruncated: ByteArray? = null
        spy.failOpen = { p, flags ->
            if (p == path && SftpOpenFlag.TRUNCATE in flags) originalWhenTruncated = fake.files[path]
            null
        }

        save(opened.snapshot, "new\n")

        // At the moment the original is cut, the complete new content already exists next to it.
        assertThat(originalWhenTruncated).isEqualTo(original)
        val copyOpens = spy.opened.filter { it.first.endsWith(".shelldon-edit") }
        assertThat(copyOpens).isNotEmpty()
    }

    @Test
    fun aFullDiskWhileWritingTheCopyLeavesTheOriginalAlone() {
        val opened = open()
        spy.failWrite = { p -> if (p.endsWith(".shelldon-edit")) SftpStatusCode.FAILURE else null }

        val failure = failureOf { SftpEdit.save(spy, opened.snapshot, "new\n".toByteArray(), false) }

        assertThat(failure).isNotNull()
        assertThat(fake.files.getValue(path)).isEqualTo(original)
        assertThat(leftovers()).isEmpty()
    }

    @Test
    fun aFileThatChangedSinceItWasOpenedIsNotOverwritten() {
        val opened = open()
        spy.mtime += 30

        val result = save(opened.snapshot, "new\n")

        assertThat(result).isEqualTo(EditSave.ChangedOnServer)
        assertThat(fake.files.getValue(path)).isEqualTo(original)
        assertThat(leftovers()).isEmpty()
    }

    @Test
    fun aFileThatChangedInTheSizeIsNotOverwrittenEvenWithTheSameTime() {
        val opened = open()
        spy.reportedSize = { (it ?: 0) + 1 }

        assertThat(save(opened.snapshot, "new\n")).isEqualTo(EditSave.ChangedOnServer)
        assertThat(fake.files.getValue(path)).isEqualTo(original)
    }

    @Test
    fun aChangeWhileTheCopyIsBeingWrittenIsCaughtBeforeTheOverwrite() {
        val opened = open()
        // Opening looked at the file once, the first check of the save is the second look; somebody saves right after it.
        spy.changeAfterTargetLstats = 2

        val result = save(opened.snapshot, "new\n")

        assertThat(result).isEqualTo(EditSave.ChangedOnServer)
        assertThat(fake.files.getValue(path)).isEqualTo(original)
        assertThat(leftovers()).isEmpty()
    }

    @Test
    fun forceOverwritesAChangedFileOnPurpose() {
        val opened = open()
        spy.mtime += 30

        val result = save(opened.snapshot, "mine\n", force = true)

        assertThat(result).isInstanceOf(EditSave.Saved::class.java)
        assertThat(String(fake.files.getValue(path))).isEqualTo("mine\n")
    }

    @Test
    fun aFileWeMayNotWriteIsReportedBeforeAnythingIsCutAndTheCopyIsRemoved() {
        val opened = open()
        spy.failOpen = { p, flags -> if (p == path && SftpOpenFlag.WRITE in flags) SftpStatusCode.PERMISSION_DENIED else null }

        val failure = failureOf { SftpEdit.save(spy, opened.snapshot, "new\n".toByteArray(), false) }

        assertThat(failure?.problem).isEqualTo(SftpProblem.PERMISSION_DENIED)
        assertThat(fake.files.getValue(path)).isEqualTo(original)
        assertThat(leftovers()).isEmpty()
    }

    @Test
    fun aFailureWhileOverwritingKeepsTheCompleteCopyAndSaysWhere() {
        val opened = open()
        spy.failWrite = { p -> if (p == path) SftpStatusCode.CONNECTION_LOST else null }

        val result = save(opened.snapshot, "the new text\n")

        assertThat(result).isInstanceOf(EditSave.Interrupted::class.java)
        val interrupted = result as EditSave.Interrupted
        assertThat(interrupted.failure.problem).isEqualTo(SftpProblem.CONNECTION_LOST)
        assertThat(String(fake.files.getValue(interrupted.completeCopy))).isEqualTo("the new text\n")
        assertThat(leftovers()).containsExactly(interrupted.completeCopy)
    }

    @Test
    fun aServerThatDoesNotSendATimeIsComparedBySizeOnly() {
        val opened = open()
        val noTime = opened.snapshot.copy(modified = null)
        spy.mtime += 30

        assertThat(save(noTime, "new\n")).isInstanceOf(EditSave.Saved::class.java)
    }

    // --- Saving as a copy ---

    @Test
    fun aCopyGetsANewNameNextToTheOriginalWhichIsNotTouched() {
        val opened = open()

        val first = runBlocking { SftpEdit.saveCopy(spy, opened.snapshot, "one\n".toByteArray()) }
        val second = runBlocking { SftpEdit.saveCopy(spy, opened.snapshot, "two\n".toByteArray()) }

        assertThat(first).isEqualTo("/home/test/sshd_config (edited)")
        assertThat(second).isEqualTo("/home/test/sshd_config (edited 2)")
        assertThat(String(fake.files.getValue(first))).isEqualTo("one\n")
        assertThat(String(fake.files.getValue(second))).isEqualTo("two\n")
        assertThat(fake.files.getValue(path)).isEqualTo(original)
    }

    @Test
    fun theNameOfACopyKeepsTheExtension() {
        fake.files["/home/test/notes.txt"] = "a\n".toByteArray()
        val snapshot = EditSnapshot("/home/test/notes.txt", 2, 1_700_000_000)

        assertThat(runBlocking { SftpEdit.saveCopy(spy, snapshot, "b\n".toByteArray()) }).isEqualTo("/home/test/notes (edited).txt")
    }
}
