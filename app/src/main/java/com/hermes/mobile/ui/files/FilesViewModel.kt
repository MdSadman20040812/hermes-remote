package com.hermes.mobile.ui.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.data.repo.FileRepository
import com.hermes.mobile.data.repo.RemoteEntry
import com.hermes.mobile.data.repo.RemoteFileContent
import com.hermes.mobile.data.repo.RemoteListing
import com.hermes.mobile.data.repo.TransferRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A transfer in flight, with enough detail to render honest progress. */
data class TransferState(
    val name: String,
    val toPc: Boolean,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val transferRepository: TransferRepository,
) : ViewModel() {

    private val _listing = MutableStateFlow<RemoteListing?>(null)
    val listing: StateFlow<RemoteListing?> = _listing.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _preview = MutableStateFlow<RemoteFileContent?>(null)
    val preview: StateFlow<RemoteFileContent?> = _preview.asStateFlow()

    private val _transfer = MutableStateFlow<TransferState?>(null)
    val transfer: StateFlow<TransferState?> = _transfer.asStateFlow()

    /** Outcome of the last transfer; stays visible until dismissed or replaced. */
    private val _receipt = MutableStateFlow<TransferReceipt?>(null)
    val receipt: StateFlow<TransferReceipt?> = _receipt.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    fun browse(path: String?) {
        viewModelScope.launch {
            _loading.value = true
            try {
                _listing.value = fileRepository.list(path)
            } catch (e: Exception) {
                _userMessage.emit("Browse failed: ${e.message}")
            } finally {
                _loading.value = false
            }
        }
    }

    fun open(entry: RemoteEntry) {
        if (entry.isDirectory) {
            browse(entry.path)
        } else {
            viewModelScope.launch {
                try {
                    _preview.value = fileRepository.read(entry.path)
                } catch (e: Exception) {
                    _userMessage.emit("Read failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Open the phone-to-PC inbox, falling back to the server default if it
     * does not exist yet (first run, before anything has been sent).
     */
    fun browseInbox() {
        viewModelScope.launch {
            _loading.value = true
            try {
                _listing.value = fileRepository.list(TransferRepository.DEFAULT_INBOX)
            } catch (e: Exception) {
                _listing.value = runCatching { fileRepository.list(null) }.getOrNull()
            } finally {
                _loading.value = false
            }
        }
    }

    fun up() {
        _listing.value?.parent?.let { browse(it) }
    }

    fun closePreview() {
        _preview.value = null
    }

    fun refresh() = browse(_listing.value?.path)

    fun dismissReceipt() {
        _receipt.value = null
    }

    val inboxPath: String get() = TransferRepository.DEFAULT_INBOX

    /** True when the browser is showing the inbox, so the UI can say so. */
    val atInbox: Boolean
        get() = _listing.value?.path?.replace('\\', '/')
            ?.equals(TransferRepository.DEFAULT_INBOX, ignoreCase = true) == true

    // ------------------------------------------------------------- transfers

    /**
     * Send a picked/shared file to the PC.
     *
     * [intoCurrentDir] puts it where the user is looking, which is what they
     * mean when they hit "Send" while browsing a folder. Otherwise it lands in
     * the inbox, away from anything the agent might commit.
     */
    fun sendToPc(uri: Uri, intoCurrentDir: Boolean = false) {
        if (_transfer.value != null) return
        viewModelScope.launch {
            _receipt.value = null
            _transfer.value = TransferState(name = "Reading file...", toPc = true)
            try {
                val dir = _listing.value?.path?.takeIf { intoCurrentDir && it.isNotBlank() }
                val progress = { name: String, sent: Long, total: Long ->
                    _transfer.value = TransferState(name, toPc = true, doneBytes = sent, totalBytes = total)
                }
                val result = if (dir != null) {
                    transferRepository.uploadToPc(uri, remoteDir = dir, onProgress = progress)
                } else {
                    transferRepository.uploadToPc(uri, onProgress = progress)
                }
                _receipt.value = TransferReceipt(
                    name = result.name,
                    where = "Now on your PC at ${result.remotePath}",
                )
                refreshAfterUpload(dir)
            } catch (e: Exception) {
                _receipt.value = TransferReceipt(
                    name = "file",
                    where = "",
                    error = e.message ?: "Send failed",
                )
            } finally {
                _transfer.value = null
            }
        }
    }

    private fun refreshAfterUpload(dir: String?) {
        // Show the new file immediately when it landed where we are looking.
        if (dir != null) refresh() else if (atInbox) refresh()
    }

    /** Pull a file off the PC into the phone's Downloads folder. */
    fun saveToPhone(entry: RemoteEntry) {
        if (_transfer.value != null) return
        viewModelScope.launch {
            _receipt.value = null
            _transfer.value = TransferState(
                name = entry.name,
                toPc = false,
                totalBytes = entry.size ?: 0L,
            )
            try {
                val saved = transferRepository.saveToPhone(entry.path) { name, got, total ->
                    _transfer.value = TransferState(name, toPc = false, doneBytes = got, totalBytes = total)
                }
                _receipt.value = TransferReceipt(
                    name = saved.name,
                    where = "Saved to Downloads on this phone",
                )
            } catch (e: Exception) {
                _receipt.value = TransferReceipt(
                    name = entry.name,
                    where = "",
                    error = e.message ?: "Save failed",
                )
            } finally {
                _transfer.value = null
            }
        }
    }

    /** Save the file currently open in the preview dialog. */
    fun savePreviewToPhone() {
        val open = _preview.value ?: return
        saveToPhone(
            RemoteEntry(
                name = open.name,
                path = open.path,
                isDirectory = false,
                size = open.size,
            ),
        )
    }
}
