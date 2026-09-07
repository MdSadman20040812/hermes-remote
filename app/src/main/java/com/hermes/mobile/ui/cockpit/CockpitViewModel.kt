package com.hermes.mobile.ui.cockpit

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.core.notify.HermesNotifier
import com.hermes.mobile.data.repo.CommandRepository
import com.hermes.mobile.data.repo.OutboxRepository
import com.hermes.mobile.data.repo.firstStr
import com.hermes.mobile.data.repo.obj
import com.hermes.mobile.data.repo.objects
import com.hermes.mobile.data.repo.str
import com.hermes.mobile.data.repo.SessionRepository
import com.hermes.mobile.data.repo.TranscriptRepository
import com.hermes.mobile.domain.model.SessionUsage
import com.hermes.mobile.domain.model.SlashCommand
import com.hermes.mobile.domain.model.TranscriptItem
import com.hermes.mobile.domain.model.TurnPhase
import com.hermes.mobile.service.TurnForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val commandRepository: CommandRepository,
    private val notifier: HermesNotifier,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _items = MutableStateFlow<List<TranscriptItem>>(emptyList())
    val items: StateFlow<List<TranscriptItem>> = _items.asStateFlow()

    private val _turnPhase = MutableStateFlow(TurnPhase.IDLE)
    val turnPhase: StateFlow<TurnPhase> = _turnPhase.asStateFlow()

    private val _activeTitle = MutableStateFlow("New session")
    val activeTitle: StateFlow<String> = _activeTitle.asStateFlow()

    private val _contextPercent = MutableStateFlow<Int?>(null)
    val contextPercent: StateFlow<Int?> = _contextPercent.asStateFlow()

    private val _model = MutableStateFlow<String?>(null)
    val model: StateFlow<String?> = _model.asStateFlow()

    private val _commands = MutableStateFlow<List<SlashCommand>>(emptyList())
    val commands: StateFlow<List<SlashCommand>> = _commands.asStateFlow()

    private val _usage = MutableStateFlow<SessionUsage?>(null)
    val usage: StateFlow<SessionUsage?> = _usage.asStateFlow()

    val connState: StateFlow<ConnState> = connectionManager.state

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    private var engine: TranscriptRepository.Engine? = null
    private var liveSessionId: String? = null

    /** The stored id the open session came from — session.delete needs it, not the live handle. */
    private var storedSessionId: String? = null

    val hasSession: Boolean get() = liveSessionId != null

    init {
        // Hold process importance while a turn runs; release immediately after.
        viewModelScope.launch {
            turnPhase.collect { phase ->
                when (phase) {
                    TurnPhase.RUNNING ->
                        TurnForegroundService.start(appContext, "Hermes turn running")
                    TurnPhase.IDLE -> TurnForegroundService.stop(appContext)
                }
            }
        }
        viewModelScope.launch {
            outboxRepository.notices.collect { _userMessage.emit(it) }
        }
        // Every reconnect builds a NEW HermesClient — a fresh socket with a
        // fresh event flow. The engine holds the client it was created with,
        // so without re-attaching here the cockpit stays on screen looking
        // connected while receiving nothing, which is worse than showing an
        // error. With no session open yet, adopt whatever the desktop has
        // live instead of opening an empty cockpit next to a running turn.
        viewModelScope.launch {
            connectionManager.clientFlow.collect { client ->
                if (client == null) return@collect
                val sid = liveSessionId
                if (sid == null) {
                    adoptMostRecent()
                } else if (engine?.isBoundTo(client) == false) {
                    openLiveSession(sid, _activeTitle.value, storedSessionId)
                }
            }
        }
    }

    // ------------------------------------------------------------- sessions

    /** Open a session by live handle (from create/resume) and attach the engine. */
    fun openLiveSession(sessionId: String, title: String? = null, storedId: String? = null) {
        val client = connectionManager.clientFlow.value
        if (client == null) {
            _userMessage.tryEmit("Not connected to your PC")
            return
        }
        engine?.stop()
        liveSessionId = sessionId
        storedSessionId = storedId ?: storedSessionId
        _activeTitle.value = title ?: _activeTitle.value
        _items.value = emptyList()
        _usage.value = null
        engine = transcriptRepository.attach(client, sessionId, viewModelScope).also { e ->
            viewModelScope.launch { e.items.collect { _items.value = it } }
            viewModelScope.launch { e.turnPhase.collect { _turnPhase.value = it } }
            viewModelScope.launch { e.contextPercent.collect { _contextPercent.value = it } }
            viewModelScope.launch { e.model.collect { if (it != null) _model.value = it } }
        }
    }

    /** New chat. */
    fun createAndOpen() {
        viewModelScope.launch {
            try {
                val sid = sessionRepository.createSession()
                storedSessionId = null
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
                openLiveSession(live, title.ifBlank { "Session" }, storedId = storedId)
            } catch (e: Exception) {
                _userMessage.emit("Couldn't resume: ${e.message}")
            }
        }
    }

    /**
     * Bind to whatever the desktop already has open, if anything.
     *
     * Opening the app mid-turn and being shown an empty cockpit beside a
     * running session is the wrong default — the session you want is almost
     * always the one already live.
     */
    private suspend fun adoptMostRecent() {
        val client = connectionManager.clientFlow.value ?: return
        val active = runCatching { client.sessionActiveList() }.getOrNull().obj() ?: return
        val first = active.objects("sessions").firstOrNull() ?: return
        val sid = first.firstStr("session_id", "id") ?: return
        openLiveSession(sid, first.str("title") ?: "Live session")
    }

    // ------------------------------------------------------------- prompting

    fun send(text: String) {
        val sid = liveSessionId
        if (sid == null) {
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
        try {
            client.promptSubmit(sid, text)
        } catch (e: Exception) {
            outboxRepository.enqueue(sid, text)
            _userMessage.emit("Send failed — queued for reconnect (${e.message})")
        }
    }

    // ------------------------------------------------------- slash commands

    /** Cheap and cached; safe to call every time the composer sees a leading "/". */
    fun loadCommands() {
        if (_commands.value.isNotEmpty()) return
        viewModelScope.launch {
            runCatching { commandRepository.catalog() }
                .onSuccess { _commands.value = it }
                .onFailure { _userMessage.emit("Couldn't load commands: ${it.message}") }
        }
    }

    /**
     * Run a slash command against the open session and render its pager output
     * inline. Some commands (/model, /compress) change server state, so the
     * transcript is left to the event stream to update itself.
     */
    fun runSlashCommand(command: String) {
        val sid = liveSessionId
        if (sid == null) {
            _userMessage.tryEmit("Open or start a session first")
            return
        }
        viewModelScope.launch {
            engine?.echoUser(command)
            runCatching { commandRepository.exec(sid, command) }
                .onSuccess { engine?.addCommandOutput(command, it) }
                .onFailure { _userMessage.emit("$command failed: ${it.message}") }
        }
    }

    // ------------------------------------------------------------ live turn

    fun interrupt() {
        val sid = liveSessionId ?: return
        viewModelScope.launch {
            runCatching { connectionManager.clientFlow.value?.sessionInterrupt(sid) }
                .onFailure { _userMessage.emit("Interrupt failed: ${it.message}") }
        }
    }

    fun steer(text: String) {
        val sid = liveSessionId ?: return
        viewModelScope.launch {
            runCatching { connectionManager.clientFlow.value?.sessionSteer(sid, text) }
                .onSuccess { _userMessage.emit("Steer queued") }
                .onFailure { _userMessage.emit("Steer failed: ${it.message}") }
        }
    }

    /**
     * [alsoRemember] maps to the server's `all` flag — "apply this answer to
     * every matching request", which is what the card's checkbox offers.
     */
    fun respondApproval(card: TranscriptItem.ApprovalCard, choice: String, alsoRemember: Boolean) {
        viewModelScope.launch {
            runCatching {
                connectionManager.clientFlow.value?.approvalRespond(
                    card.sessionId, choice, alsoRemember,
                )
            }
                .onSuccess {
                    engine?.markApprovalResolved(card.key, choice)
                    notifier.dismissForSession(card.sessionId)
                }
                .onFailure { _userMessage.emit("Approval response failed: ${it.message}") }
        }
    }

    // -------------------------------------------------------- session admin

    fun rename(title: String) {
        val sid = liveSessionId ?: return
        viewModelScope.launch {
            runCatching { sessionRepository.rename(sid, title) }
                .onSuccess { _activeTitle.value = title }
                .onFailure { _userMessage.emit("Rename failed: ${it.message}") }
        }
    }

    fun compress() {
        val sid = liveSessionId ?: return
        viewModelScope.launch {
            _userMessage.emit("Compressing history — this can take a minute")
            runCatching { sessionRepository.compress(sid) }
                .onSuccess {
                    _userMessage.emit("History compressed")
                    engine?.hydrateFromHistory()
                }
                .onFailure { _userMessage.emit("Compress failed: ${it.message}") }
        }
    }

    fun undo() {
        val sid = liveSessionId ?: return
        viewModelScope.launch {
            runCatching { sessionRepository.undo(sid) }
                .onSuccess {
                    _userMessage.emit(if (it > 0) "Undid the last exchange" else "Nothing to undo")
                    engine?.hydrateFromHistory()
                }
                .onFailure { _userMessage.emit("Undo failed: ${it.message}") }
        }
    }

    fun branch() {
        val sid = liveSessionId ?: return
        viewModelScope.launch {
            runCatching { sessionRepository.branch(sid) }
                .onSuccess { newSid ->
                    if (newSid == null) {
                        _userMessage.emit("Branch returned no session")
                    } else {
                        openLiveSession(newSid, "${_activeTitle.value} (branch)")
                        _userMessage.emit("Branched — you're now on the copy")
                    }
                }
                .onFailure { _userMessage.emit("Branch failed: ${it.message}") }
        }
    }

    fun refreshUsage() {
        val sid = liveSessionId ?: return
        viewModelScope.launch {
            runCatching { sessionRepository.usage(sid) }
                .onSuccess {
                    _usage.value = it
                    it.contextPercent?.let { p -> _contextPercent.value = p }
                }
                .onFailure { _userMessage.emit("Usage unavailable: ${it.message}") }
        }
    }

    override fun onCleared() {
        engine?.stop()
    }
}
