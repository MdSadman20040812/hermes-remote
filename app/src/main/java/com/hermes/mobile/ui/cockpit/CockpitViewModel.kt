package com.hermes.mobile.ui.cockpit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.data.repo.OutboxRepository
import com.hermes.mobile.data.repo.SessionRepository
import com.hermes.mobile.data.repo.TranscriptRepository
import com.hermes.mobile.domain.model.TranscriptItem
import com.hermes.mobile.domain.model.TurnPhase
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
class CockpitViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val sessionRepository: SessionRepository,
    private val transcriptRepository: TranscriptRepository,
    private val outboxRepository: OutboxRepository,
    private val notifier: com.hermes.mobile.core.notify.HermesNotifier,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val _items = MutableStateFlow<List<TranscriptItem>>(emptyList())
    val items: StateFlow<List<TranscriptItem>> = _items.asStateFlow()

    private val _turnPhase = MutableStateFlow(TurnPhase.IDLE)
    val turnPhase: StateFlow<TurnPhase> = _turnPhase.asStateFlow()

    private val _activeTitle = MutableStateFlow("New session")
    val activeTitle: StateFlow<String> = _activeTitle.asStateFlow()

    private val _contextPercent = MutableStateFlow<Int?>(null)
    val contextPercent: StateFlow<Int?> = _contextPercent.asStateFlow()

    val connState: StateFlow<ConnState> = connectionManager.state

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    private var engine: TranscriptRepository.Engine? = null
    private var liveSessionId: String? = null

    init {
        // Hold process importance while a turn runs; release immediately after.
        // Also relay outbox flush notices to the snackbar channel.
        viewModelScope.launch {
            turnPhase.collect { phase ->
                when (phase) {
                    TurnPhase.RUNNING -> com.hermes.mobile.service.TurnForegroundService.start(
                        appContext, "Hermes turn running",
                    )
                    TurnPhase.IDLE -> com.hermes.mobile.service.TurnForegroundService.stop(appContext)
                }
            }
        }
        viewModelScope.launch {
            outboxRepository.notices.collect { _userMessage.emit(it) }
        }
    }

    /** Open a session by live handle (from create/resume) and attach the engine. */
    fun openLiveSession(sessionId: String, title: String? = null) {
        val client = connectionManager.clientFlow.value
        if (client == null) {
            _userMessage.tryEmit("Not connected to your PC")
            return
        }
        engine?.stop()
        liveSessionId = sessionId
        _activeTitle.value = title ?: _activeTitle.value
        _items.value = emptyList()
        engine = transcriptRepository.attach(client, sessionId, viewModelScope).also { e ->
            viewModelScope.launch { e.items.collect { _items.value = it } }
            viewModelScope.launch { e.turnPhase.collect { _turnPhase.value = it } }
        }
    }

    /** New chat. */
    fun createAndOpen() {
        viewModelScope.launch {
            try {
                val sid = sessionRepository.createSession()
                _activeTitle.value = "New session"
                openLiveSession(sid, "New session")
            } catch (e: Exception) {
                _userMessage.emit("Couldn't create session: ${e.message}")
            }
        }
    }

    /** Resume a stored session (from the sessions list). */
    fun resumeAndOpen(storedId: String, title: String) {
        viewModelScope.launch {
            try {
                val live = sessionRepository.resumeSession(storedId)
                _activeTitle.value = title.ifBlank { "Session" }
                openLiveSession(live, title)
            } catch (e: Exception) {
                _userMessage.emit("Couldn't resume: ${e.message}")
            }
        }
    }

    fun send(text: String) {
        val sid = liveSessionId
        if (sid == null) {
            // No session yet — create one, then send.
            viewModelScope.launch {
                try {
                    val newSid = sessionRepository.createSession()
                    openLiveSession(newSid, "New session")
                    doSend(newSid, text)
                } catch (e: Exception) {
                    _userMessage.emit("Couldn't start a session: ${e.message}")
                }
            }
            return
        }
        viewModelScope.launch { doSend(sid, text) }
    }

    private suspend fun doSend(sid: String, text: String) {
        val client = connectionManager.clientFlow.value
        if (client == null || connectionManager.state.value !is ConnState.Connected) {
            outboxRepository.enqueue(sid, text)
            engine?.echoUser(text)
            _userMessage.emit("Offline — prompt queued, will send on reconnect")
            return
        }
        engine?.echoUser(text)
        _turnPhase.value = TurnPhase.RUNNING
        try {
            client.promptSubmit(sid, text)
        } catch (e: Exception) {
            _turnPhase.value = TurnPhase.IDLE
            outboxRepository.enqueue(sid, text)
            _userMessage.emit("Send failed — queued for reconnect (${e.message})")
        }
    }

    fun interrupt() {
        val sid = liveSessionId ?: return
        viewModelScope.launch {
            try {
                connectionManager.clientFlow.value?.sessionInterrupt(sid)
            } catch (e: Exception) {
                _userMessage.emit("Interrupt failed: ${e.message}")
            }
        }
    }

    fun steer(text: String) {
        val sid = liveSessionId ?: return
        viewModelScope.launch {
            try {
                connectionManager.clientFlow.value?.sessionSteer(sid, text)
                _userMessage.emit("Steer queued")
            } catch (e: Exception) {
                _userMessage.emit("Steer failed: ${e.message}")
            }
        }
    }

    fun respondApproval(card: TranscriptItem.ApprovalCard, choice: String) {
        viewModelScope.launch {
            try {
                connectionManager.clientFlow.value?.approvalRespond(card.sessionId, choice)
                engine?.markApprovalResolved(card.key, choice)
                notifier.dismissForSession(card.sessionId)
            } catch (e: Exception) {
                _userMessage.emit("Approval response failed: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        engine?.stop()
    }
}
