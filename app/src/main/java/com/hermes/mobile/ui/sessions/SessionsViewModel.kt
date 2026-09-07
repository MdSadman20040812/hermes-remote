package com.hermes.mobile.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.core.transport.HermesEvent
import com.hermes.mobile.data.repo.SessionRepository
import com.hermes.mobile.domain.model.SessionSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** True until the first successful load, so the list can show skeletons once. */
    private val _hydrated = MutableStateFlow(false)
    val hydrated: StateFlow<Boolean> = _hydrated.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    /** Filtered client-side: the list is small and the server round-trip is not worth it. */
    val sessions: StateFlow<List<SessionSummary>> =
        combine(_sessions, _query) { all, q ->
            if (q.isBlank()) all else all.filter { s ->
                s.title.contains(q, true) || s.preview.contains(q, true)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Show whatever was cached last time immediately — a list that opens
        // blank and fills in half a second later reads as broken.
        viewModelScope.launch {
            sessionRepository.cachedSessions.collect { cached ->
                if (_sessions.value.isEmpty()) _sessions.value = cached
            }
        }
        // The previous version refreshed once on first composition and gave up
        // if the socket wasn't up yet, which is exactly when it usually isn't.
        viewModelScope.launch {
            connectionManager.state.collect { if (it is ConnState.Connected) refresh() }
        }
        // The desktop creating, renaming or deleting a session broadcasts this.
        viewModelScope.launch {
            connectionManager.globalEvents.collect { event ->
                if (event is HermesEvent.Global && event.type == "sessions.changed") refresh()
                if (event is HermesEvent.SessionTitle) refresh()
            }
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun refresh() {
        if (connectionManager.clientFlow.value == null) return
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                _sessions.value = sessionRepository.listSessions()
                _hydrated.value = true
            } catch (e: Exception) {
                _userMessage.emit("Couldn't load sessions: ${e.message}")
            } finally {
                _loading.value = false
            }
        }
    }

    fun delete(session: SessionSummary) {
        viewModelScope.launch {
            runCatching { sessionRepository.delete(session.id) }
                .onSuccess {
                    _sessions.value = _sessions.value.filterNot { it.id == session.id }
                    _userMessage.emit("Deleted \"${session.title.ifBlank { "untitled" }}\"")
                }
                .onFailure { _userMessage.emit("Delete failed: ${it.message}") }
        }
    }
}
