package com.hermes.mobile.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.data.repo.SessionRepository
import com.hermes.mobile.domain.model.SessionSummary
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
class SessionsViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    /** Local echo of an open request so the cockpit can navigate immediately. */
    private val _openRequest = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 1)
    val openRequest: SharedFlow<Pair<String, String>> = _openRequest.asSharedFlow()

    fun refresh() {
        if (connectionManager.clientFlow.value == null) return
        viewModelScope.launch {
            _loading.value = true
            try {
                _sessions.value = sessionRepository.listSessions()
            } catch (e: Exception) {
                _userMessage.emit("Couldn't load sessions: ${e.message}")
            } finally {
                _loading.value = false
            }
        }
    }

    fun open(session: SessionSummary) {
        _openRequest.tryEmit(session.id to session.title)
    }
}
