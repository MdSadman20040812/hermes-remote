package com.hermes.mobile.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.data.repo.FileRepository
import com.hermes.mobile.data.repo.RemoteEntry
import com.hermes.mobile.data.repo.RemoteFileContent
import com.hermes.mobile.data.repo.RemoteListing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val fileRepository: FileRepository,
) : ViewModel() {

    private val _listing = MutableStateFlow<RemoteListing?>(null)
    val listing: StateFlow<RemoteListing?> = _listing.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _preview = MutableStateFlow<RemoteFileContent?>(null)
    val preview: StateFlow<RemoteFileContent?> = _preview.asStateFlow()

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

    fun up() {
        _listing.value?.parent?.let { browse(it) }
    }

    fun closePreview() {
        _preview.value = null
    }

    fun refresh() = browse(_listing.value?.path)
}
