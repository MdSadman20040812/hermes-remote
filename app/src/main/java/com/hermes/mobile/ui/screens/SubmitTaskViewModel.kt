package com.hermes.mobile.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.hermes.mobile.core.work.WorkModule
import com.hermes.mobile.domain.repository.TaskAttachment
import com.hermes.mobile.domain.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SubmitTaskViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val taskRepo: TaskRepository,
    private val workManager: WorkManager
) : ViewModel() {

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _submittedTaskId = MutableStateFlow<Long?>(null)
    val submittedTaskId: StateFlow<Long?> = _submittedTaskId.asStateFlow()

    fun submit(
        prompt: String,
        attachmentUris: List<Uri>,
        priority: Int,
        notifyOnComplete: Boolean
    ) = viewModelScope.launch {
        if (prompt.isBlank() || _isSubmitting.value) return@launch
        _isSubmitting.value = true
        try {
            val attachments = attachmentUris.mapNotNull { uri -> readAttachment(uri) }
            val id = taskRepo.submitTask(
                prompt = prompt,
                attachments = attachments,
                priority = priority,
                notifyOnComplete = notifyOnComplete
            )
            // Kick an immediate background sync so the PC sees it fast
            WorkModule.enqueueSyncNow(workManager)
            _submittedTaskId.value = id
        } finally {
            _isSubmitting.value = false
        }
    }

    fun consumeSubmitted() {
        _submittedTaskId.value = null
    }

    private suspend fun readAttachment(uri: Uri): TaskAttachment? = withContext(Dispatchers.IO) {
        runCatching {
            val name = appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            } ?: "attachment"
            val mime = appContext.contentResolver.getType(uri) ?: "application/octet-stream"
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            TaskAttachment(name, bytes, mime)
        }.getOrNull()
    }
}
