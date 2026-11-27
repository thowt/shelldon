/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.sftp

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.connectbot.R
import org.connectbot.data.EntityIds
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalManager
import org.connectbot.shelldon.sftp.ChannelSource
import org.connectbot.shelldon.sftp.FilePartial
import org.connectbot.shelldon.sftp.RemoteVersion
import org.connectbot.shelldon.sftp.SftpBrowser
import org.connectbot.shelldon.sftp.SftpFailure
import org.connectbot.shelldon.sftp.SftpItem
import org.connectbot.shelldon.sftp.SftpPartials
import org.connectbot.shelldon.sftp.SftpPaths
import org.connectbot.shelldon.sftp.SftpProblem
import org.connectbot.shelldon.sftp.SftpResume
import org.connectbot.shelldon.sftp.SftpTransfers
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpResult
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

enum class SftpUnavailable { NO_SESSION, NOT_SUPPORTED, LOST, OPEN_FAILED }

enum class TransferStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class TransferUi(
    val id: Long,
    val name: String,
    val upload: Boolean,
    val done: Long = 0,
    val total: Long? = null,
    val status: TransferStatus = TransferStatus.QUEUED,
    /** Starting it again continues where it stopped, so the card says so when it did not finish. */
    val resumable: Boolean = false,
    /** Bytes kept from an earlier try, once the transfer has checked them. */
    val resumedFrom: Long = 0,
    /** Why it failed, as a message the screen can show. */
    @param:StringRes val problem: Int? = null,
    val problemDetail: String = "",
)

/** Files the user picked for upload, some of which are already on the server. */
data class UploadConflict(val names: List<String>, val targets: List<UploadTarget>)

data class UploadTarget(val uri: Uri, val name: String, val size: Long?, val path: String, val modified: Long? = null)

/** Something to tell the user once: what was being done, to what, and why it failed. [serial] makes two equal notices distinct. */
data class SftpNotice(
    val serial: Long,
    @param:StringRes val what: Int,
    val subject: String,
    @param:StringRes val problem: Int,
    val detail: String,
)

data class SftpUiState(
    val connecting: Boolean = true,
    val unavailable: SftpUnavailable? = null,
    val unavailableDetail: String = "",
    val hostName: String = "",
    val path: String = SftpPaths.ROOT,
    val items: List<SftpItem> = emptyList(),
    val loading: Boolean = false,
    val showHidden: Boolean = false,
    val transfers: List<TransferUi> = emptyList(),
    val conflict: UploadConflict? = null,
) {
    val visibleItems: List<SftpItem> get() = if (showHidden) items else items.filterNot { it.isHidden }
    val hiddenCount: Int get() = items.count { it.isHidden }
}

@HiltViewModel
class SftpViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext context: Context,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {
    private val hostId: UUID = EntityIds.parse(savedStateHandle.get<String>("hostId"))
    private val resolver: ContentResolver = context.contentResolver

    /** Unfinished downloads. In the cache, so "clear cache" removes them and the system may when space runs out. */
    private val partialFolder = File(context.cacheDir, "sftp-partial")

    private val _state = MutableStateFlow(SftpUiState())
    val uiState: StateFlow<SftpUiState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<SftpNotice?>(null)
    val notice: StateFlow<SftpNotice?> = _notice.asStateFlow()

    private var client: SftpClient? = null
    private var browser: SftpBrowser? = null
    private var started = false
    private var listJob: Job? = null
    private var nextTransferId = 1L
    private val transferJobs = HashMap<Long, Job>()
    private val transferLock = Mutex()

    /** Called by the screen once the connection service is bound. Opens the file channel once. */
    fun setTerminalManager(manager: TerminalManager) {
        if (started) return
        started = true
        viewModelScope.launch { connect(manager) }
    }

    private suspend fun connect(manager: TerminalManager) {
        val bridge = manager.bridgesFlow.value.find { it.host.id == hostId }
        val transport = bridge?.transport
        if (bridge == null || transport == null || !transport.isConnected()) {
            return unavailable(SftpUnavailable.NO_SESSION)
        }
        _state.update { it.copy(hostName = bridge.host.nickname.ifBlank { bridge.host.hostname }) }
        if (!transport.canOpenSftp()) return unavailable(SftpUnavailable.NOT_SUPPORTED)
        val opened = try {
            withContext(dispatchers.io) { transport.openSftp() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Opening SFTP failed")
            return unavailable(SftpUnavailable.OPEN_FAILED, e.message.orEmpty())
        }
        when (opened) {
            null -> unavailable(SftpUnavailable.NOT_SUPPORTED)

            is SftpResult.Success -> {
                client = opened.value
                viewModelScope.launch(dispatchers.io) { SftpPartials.prune(partialFolder, System.currentTimeMillis()) }
                val files = SftpBrowser(opened.value)
                browser = files
                try {
                    val home = withContext(dispatchers.io) { files.home() }
                    _state.update { it.copy(connecting = false) }
                    navigate(home)
                } catch (e: SftpFailure) {
                    unavailable(SftpUnavailable.OPEN_FAILED, e.detail)
                }
            }

            is SftpResult.ServerError -> unavailable(SftpUnavailable.OPEN_FAILED, opened.message)

            is SftpResult.ProtocolError -> unavailable(SftpUnavailable.OPEN_FAILED, opened.message)

            is SftpResult.IoError -> unavailable(SftpUnavailable.OPEN_FAILED, opened.cause.message.orEmpty())
        }
    }

    private fun unavailable(reason: SftpUnavailable, detail: String = "") {
        _state.update { it.copy(connecting = false, loading = false, unavailable = reason, unavailableDetail = detail) }
    }

    // --- Browsing ---

    fun navigate(path: String) {
        val files = browser ?: return
        listJob?.cancel()
        val target = SftpPaths.normalize(path)
        listJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val items = withContext(dispatchers.io) { files.list(target) }
                _state.update { it.copy(path = target, items = items, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SftpFailure) {
                _state.update { it.copy(loading = false) }
                fail(e, R.string.shelldon_sftp_err_list, SftpPaths.printable(target))
            }
        }
    }

    fun open(item: SftpItem) {
        if (item.opensAsFolder) navigate(item.path)
    }

    /** @return false if already at the top, so the screen can leave instead */
    fun up(): Boolean {
        val current = _state.value.path
        if (current == SftpPaths.ROOT) return false
        navigate(SftpPaths.parent(current))
        return true
    }

    fun refresh() = navigate(_state.value.path)

    fun toggleHidden() = _state.update { it.copy(showHidden = !it.showHidden) }

    // --- Changing things on the server ---

    fun createFolder(name: String) = change(R.string.shelldon_sftp_err_create) { it.createFolder(_state.value.path, name) }

    fun rename(item: SftpItem, newName: String) = change(R.string.shelldon_sftp_err_rename) { it.rename(item, newName) }

    fun delete(item: SftpItem) = change(R.string.shelldon_sftp_err_delete) { it.delete(item) }

    private fun change(@StringRes errorRes: Int, block: suspend (SftpBrowser) -> Unit) {
        val files = browser ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                withContext(dispatchers.io) { block(files) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SftpFailure) {
                fail(e, errorRes)
            }
            refresh()
        }
    }

    // --- Transfers ---

    fun download(item: SftpItem, target: Uri) {
        val files = client ?: return
        val size = item.size
        if (size == null || size <= 0) {
            // Without a size there is no telling a finished file from a cut-off one, so there is nothing to continue.
            launchTransfer(TransferUi(nextId(), item.name, upload = false, total = size), cleanUp = { deletePartial(target) }) { id ->
                val out = resolver.openOutputStream(target, "wt") ?: throw IOException("Cannot write to the chosen file")
                out.use { SftpTransfers.download(files, item.path, it, size, progressTo(id)) }
            }
            return
        }
        val version = RemoteVersion(size, item.modified)
        val partial = FilePartial(File(partialFolder, SftpResume.partialName(hostId.toString(), item.path, version)))
        // The half-finished copy stays in this app's cache when the transfer stops, so the next try continues
        // from it. The file the person chose is only written when the copy is whole.
        launchTransfer(TransferUi(nextId(), item.name, upload = false, total = size, resumable = true), cleanUp = { deletePartial(target) }) { id ->
            partialFolder.mkdirs()
            val missing = size - partial.length
            if (partialFolder.usableSpace < missing + FREE_SPACE_MARGIN) throw IOException("Not enough free space on this phone")
            SftpResume.download(files, item.path, version, partial, onStart = { kept -> setResumedFrom(id, kept) }, onProgress = progressTo(id))
            val out = resolver.openOutputStream(target, "wt") ?: throw IOException("Cannot write to the chosen file")
            out.use { sink -> partial.file.inputStream().use { it.copyTo(sink) } }
            partial.file.delete()
        }
    }

    /** Picked files go to the folder that is open now. Names that already exist are asked about first. */
    fun upload(uris: List<Uri>) {
        val files = browser ?: return
        val folder = _state.value.path
        viewModelScope.launch {
            val targets = withContext(dispatchers.io) {
                uris.map { uri ->
                    val (name, size) = describe(uri)
                    val remote = SftpPaths.remoteFileName(name)
                    UploadTarget(uri, remote, size, SftpPaths.child(folder, remote), lastModifiedOf(uri))
                }
            }
            val existing = try {
                withContext(dispatchers.io) { targets.filter { files.exists(it.path) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SftpFailure) {
                return@launch fail(e, R.string.shelldon_sftp_err_upload)
            }
            if (existing.isEmpty()) {
                targets.forEach { startUpload(it, overwrite = false) }
            } else {
                _state.update { it.copy(conflict = UploadConflict(existing.map { t -> t.name }, targets)) }
            }
        }
    }

    /** @param replace true to overwrite the files that exist, false to skip them */
    fun resolveConflict(replace: Boolean) {
        val conflict = _state.value.conflict ?: return
        _state.update { it.copy(conflict = null) }
        val existing = conflict.names.toSet()
        conflict.targets.forEach { target ->
            val exists = target.name in existing
            if (!exists) {
                startUpload(target, overwrite = false)
            } else if (replace) {
                startUpload(target, overwrite = true)
            }
        }
    }

    fun dismissConflict() = _state.update { it.copy(conflict = null) }

    private fun startUpload(target: UploadTarget, overwrite: Boolean) {
        val files = client ?: return
        val folder = SftpPaths.parent(target.path)
        launchTransfer(
            TransferUi(nextId(), target.name, upload = true, total = target.size, resumable = !overwrite),
            cleanUp = {},
            after = { if (_state.value.path == folder) refresh() },
        ) { id ->
            // A new file goes up under a hidden name and gets its real one when it is whole, so a stop leaves
            // something to continue from and never a half-written file under the real name. Replacing a file, or a
            // source that cannot be read from any point, is sent straight to the name as before.
            val source = if (overwrite) null else ChannelSource.openOrNull { openForUpload(target.uri) }
            if (source != null) {
                source.use {
                    val part = SftpPaths.child(folder, SftpResume.uploadPartName(target.name, it.length, target.modified))
                    SftpResume.upload(files, it, target.path, part, onStart = { kept -> setResumedFrom(id, kept) }, onProgress = progressTo(id))
                }
            } else {
                val input = resolver.openInputStream(target.uri) ?: throw IOException("Cannot read the chosen file")
                input.use { SftpTransfers.upload(files, it, target.path, overwrite, progressTo(id)) }
            }
        }
    }

    private fun openForUpload(uri: Uri): FileInputStream {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot read the chosen file")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    }

    private fun setResumedFrom(id: Long, kept: Long) {
        _state.update { s -> s.copy(transfers = s.transfers.map { if (it.id == id) it.copy(resumedFrom = kept) else it }) }
    }

    /** Transfers run one at a time: a single one already fills the link. */
    private fun launchTransfer(
        transfer: TransferUi,
        cleanUp: suspend () -> Unit,
        after: () -> Unit = {},
        work: suspend (id: Long) -> Unit,
    ) {
        val id = addTransfer(transfer)
        transferJobs[id] = viewModelScope.launch {
            try {
                transferLock.withLock { runTransfer(id, cleanUp) { work(id) } }
            } catch (e: CancellationException) {
                // Also reached while still waiting for the lock.
                setStatus(id, TransferStatus.CANCELLED)
                throw e
            } finally {
                transferJobs.remove(id)
            }
            after()
        }
    }

    fun cancelTransfer(id: Long) {
        transferJobs[id]?.cancel()
    }

    fun dismissTransfer(id: Long) {
        _state.update { s -> s.copy(transfers = s.transfers.filterNot { it.id == id && it.status != TransferStatus.RUNNING && it.status != TransferStatus.QUEUED }) }
    }

    private suspend fun runTransfer(id: Long, onFailureCleanUp: suspend () -> Unit, work: suspend () -> Unit) {
        setStatus(id, TransferStatus.RUNNING)
        try {
            withContext(dispatchers.io) { work() }
            _state.update { s -> s.copy(transfers = s.transfers.map { if (it.id == id) it.copy(status = TransferStatus.DONE, done = it.total ?: it.done) else it }) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { onFailureCleanUp() }
            setStatus(id, TransferStatus.CANCELLED)
            throw e
        } catch (e: SftpFailure) {
            withContext(NonCancellable) { onFailureCleanUp() }
            if (e.problem == SftpProblem.CONNECTION_LOST) unavailable(SftpUnavailable.LOST)
            setFailed(id, problemMessage(e.problem), e.detail)
        } catch (e: IOException) {
            withContext(NonCancellable) { onFailureCleanUp() }
            setFailed(id, R.string.shelldon_sftp_err_local, e.message.orEmpty())
        }
    }

    private fun deletePartial(target: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, target)
        } catch (e: Exception) {
            Timber.w(e, "Could not remove the unfinished download")
        }
    }

    private fun progressTo(id: Long): (Long) -> Unit {
        var last = 0L
        return { done ->
            val now = System.nanoTime()
            if (now - last >= PROGRESS_INTERVAL_NANOS) {
                last = now
                _state.update { s -> s.copy(transfers = s.transfers.map { if (it.id == id) it.copy(done = done) else it }) }
            }
        }
    }

    private fun addTransfer(transfer: TransferUi): Long {
        _state.update { it.copy(transfers = it.transfers + transfer) }
        return transfer.id
    }

    private fun nextId() = nextTransferId++

    private fun setStatus(id: Long, status: TransferStatus) {
        _state.update { s -> s.copy(transfers = s.transfers.map { if (it.id == id) it.copy(status = status) else it }) }
    }

    private fun setFailed(id: Long, @StringRes problem: Int, detail: String) {
        _state.update { s ->
            s.copy(transfers = s.transfers.map { if (it.id == id) it.copy(status = TransferStatus.FAILED, problem = problem, problemDetail = detail) else it })
        }
    }

    /** Seconds since 1970 of the last change, if the provider says; only used to tell two versions of a file apart. */
    private fun lastModifiedOf(uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) / 1000 else null
        }
    } catch (e: Exception) {
        Timber.d(e, "The provider does not say when the chosen file changed")
        null
    }

    private fun describe(uri: Uri): Pair<String, Long?> {
        var name: String? = null
        var size: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not read the name of the chosen file")
        }
        return (name ?: uri.lastPathSegment ?: "upload") to size
    }

    // --- Messages ---

    @StringRes
    private fun problemMessage(problem: SftpProblem): Int = when (problem) {
        SftpProblem.NOT_FOUND -> R.string.shelldon_sftp_problem_not_found
        SftpProblem.PERMISSION_DENIED -> R.string.shelldon_sftp_problem_permission
        SftpProblem.ALREADY_EXISTS -> R.string.shelldon_sftp_problem_exists
        SftpProblem.CONNECTION_LOST -> R.string.shelldon_sftp_problem_lost
        SftpProblem.UNSUPPORTED -> R.string.shelldon_sftp_problem_unsupported
        SftpProblem.TRUNCATED -> R.string.shelldon_sftp_problem_truncated
        SftpProblem.CHANGED -> R.string.shelldon_sftp_problem_changed
        SftpProblem.INVALID_NAME -> R.string.shelldon_sftp_problem_name
        SftpProblem.TOO_DEEP -> R.string.shelldon_sftp_problem_deep
        SftpProblem.OTHER -> R.string.shelldon_sftp_problem_other
    }

    /** One notice: what was being done, then why it failed. */
    private fun fail(failure: SftpFailure, @StringRes whatRes: Int, subject: String = "") {
        if (failure.problem == SftpProblem.CONNECTION_LOST) unavailable(SftpUnavailable.LOST)
        _notice.value = SftpNotice(
            serial = System.nanoTime(),
            what = whatRes,
            subject = subject,
            problem = problemMessage(failure.problem),
            detail = if (failure.problem == SftpProblem.OTHER) failure.detail else "",
        )
    }

    fun clearNotice() {
        _notice.value = null
    }

    override fun onCleared() {
        val closing = client
        client = null
        browser = null
        if (closing != null) {
            // The scope of this ViewModel is already cancelled.
            CoroutineScope(SupervisorJob() + dispatchers.io).launch {
                try {
                    closing.close()
                } catch (e: Exception) {
                    Timber.w(e, "Closing the file channel failed")
                }
            }
        }
        super.onCleared()
    }

    private companion object {
        const val PROGRESS_INTERVAL_NANOS = 100_000_000L
        const val FREE_SPACE_MARGIN = 8L * 1024 * 1024
    }
}
