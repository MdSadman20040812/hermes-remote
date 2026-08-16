package com.hermes.mobile.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.domain.model.DriveFileInfo
import com.hermes.mobile.domain.repository.DriveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** State for the in-app file preview dialog. */
sealed interface PreviewState {
    data object Hidden : PreviewState
    data object Loading : PreviewState
    data class Ready(val file: DriveFileInfo, val localFile: File) : PreviewState
    data class Error(val message: String) : PreviewState
}

@HiltViewModel
class FilesViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val driveRepo: DriveRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _preview = MutableStateFlow<PreviewState>(PreviewState.Hidden)
    val preview: StateFlow<PreviewState> = _preview.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    val files: StateFlow<List<DriveFileInfo>> =
        combine(driveRepo.observeFiles(), _query) { files, query ->
            if (query.isBlank()) files
            else files.filter { it.name.contains(query, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refresh()
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun refresh() = viewModelScope.launch {
        _isRefreshing.value = true
        driveRepo.refreshDriveIndex()
        _isRefreshing.value = false
    }

    fun uploadUris(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        if (!driveRepo.isDriveSignedIn) {
            _userMessage.value = "Sign in to Google Drive first (Settings → Google Drive)"
            return@launch
        }
        var uploaded = 0
        for (uri in uris) {
            runCatching {
                val name = resolveDisplayName(uri) ?: "upload_${System.currentTimeMillis()}"
                val mime = appContext.contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalStateException("Cannot read $name")
                driveRepo.uploadFile(name, bytes, mime)
            }.onSuccess { uploaded++ }
        }
        _userMessage.value = if (uploaded == uris.size) "Uploaded $uploaded file(s) to HermesShared"
        else "Uploaded $uploaded of ${uris.size} file(s)"
    }

    fun openPreview(file: DriveFileInfo) = viewModelScope.launch {
        _preview.value = PreviewState.Loading
        runCatching { driveRepo.downloadToCache(file) }
            .onSuccess { _preview.value = PreviewState.Ready(file, it) }
            .onFailure { _preview.value = PreviewState.Error(it.message ?: "Download failed") }
    }

    fun dismissPreview() {
        _preview.value = PreviewState.Hidden
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    private fun resolveDisplayName(uri: Uri): String? =
        runCatching {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
}
