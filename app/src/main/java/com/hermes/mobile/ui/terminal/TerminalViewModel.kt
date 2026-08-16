package com.hermes.mobile.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.core.connection.CredentialStrategy
import com.hermes.mobile.core.terminal.PtyChannel
import com.hermes.mobile.core.terminal.PtyChannelFactory
import com.hermes.mobile.core.terminal.PtySanitizer
import com.hermes.mobile.core.terminal.PtyState
import com.hermes.mobile.core.terminal.normalizePtyMobileInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Terminal tab: full Hermes TUI in the pocket (Phase 0 finding — /api/pty is
 * the Hermes chat TUI, not a bare shell). Scrollback is ANSI-stripped plain
 * text; the sanitizer + mobile-input normalizer are the ported web modules.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val ptyChannelFactory: PtyChannelFactory,
) : ViewModel() {

    private val _scrollback = MutableStateFlow("")
    val scrollback: StateFlow<String> = _scrollback.asStateFlow()

    private val _state = MutableStateFlow(PtyState.CLOSED)
    val state: StateFlow<PtyState> = _state.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    private var channel: PtyChannel? = null
    private var pumpJob: Job? = null
    private var eraseWindowJob: Job? = null
    private var reconnectJob: Job? = null
    private var lastReconnectAt = 0L
    private var currentLine = ""
    private var lastImeReplacementAt = 0L

    private val scrollbackBuf = StringBuilder()
    private val sanitizer = PtySanitizer()

    fun connect() {
        val profile = (connectionManager.state.value as? ConnState.Connected)?.profile
        if (profile == null) {
            _userMessage.tryEmit("Not connected to your PC")
            return
        }
        val secret = connectionManager.vaultSecret(profile.id)
        if (secret == null) {
            _userMessage.tryEmit("No credential for ${profile.label}")
            return
        }
        reconnectJob?.cancel()
        pumpJob?.cancel()
        channel?.close()

        scrollbackBuf.clear()
        _scrollback.value = ""

        val ch = ptyChannelFactory.newChannel(viewModelScope)
        channel = ch

        val url = CredentialStrategy.Token(secret).wsUrl(profile.wsBase, "/api/pty")
        ch.connect(url)

        pumpJob = viewModelScope.launch {
            launch {
                ch.output.collect { bytes ->
                    val text = sanitizer.next(String(bytes, Charsets.UTF_8))
                    if (text.isNotEmpty()) {
                        scrollbackBuf.append(stripAnsi(text))
                        // Cap scrollback at ~256KB so long sessions don't OOM the UI.
                        if (scrollbackBuf.length > 256 * 1024) {
                            scrollbackBuf.delete(0, scrollbackBuf.length - 128 * 1024)
                        }
                        _scrollback.value = scrollbackBuf.toString()
                    }
                }
            }
            launch {
                ch.state.collect { st ->
                    _state.value = st
                    if (st == PtyState.CLOSED) scheduleReconnect()
                }
            }
            launch {
                ch.closeReason.collect { reason ->
                    if (reason != null && reason.isNotBlank()) {
                        _userMessage.tryEmit("Terminal: $reason")
                    }
                }
            }
        }

        // pty-reconnect.ts: erase-code suppression only during the resume window.
        eraseWindowJob = viewModelScope.launch {
            delay(PtySanitizer.RESUME_SANITIZE_WINDOW_MS)
            sanitizer.endEraseSuppression()
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            val wait = PtyChannel.RESUME_RECONNECT_THROTTLE_MS - (now - lastReconnectAt)
            if (wait > 0) delay(wait)
            lastReconnectAt = System.currentTimeMillis()
            _state.value = PtyState.RECONNECTING
            connect()
        }
    }

    /** Send terminal input, normalizing mobile-IME line replacements. */
    fun sendInput(data: String) {
        if (_state.value != PtyState.OPEN) return // shouldBlockPtyInput port
        val replacementActive =
            System.currentTimeMillis() - lastImeReplacementAt < 350
        val normalized = normalizePtyMobileInput(data, currentLine, replacementActive)
        currentLine = normalized.nextLine
        channel?.send(normalized.data)
    }

    /** Compose TextFields give us whole-value replacements — mark the window. */
    fun noteImeReplacement() {
        lastImeReplacementAt = System.currentTimeMillis()
    }

    fun disconnect() {
        reconnectJob?.cancel()
        pumpJob?.cancel()
        eraseWindowJob?.cancel()
        channel?.close()
    }

    override fun onCleared() {
        disconnect()
    }

    companion object {
        /** Minimal ANSI stripper for the plain-text scrollback view. */
        private val ANSI_RE = Regex("\u001b\\[[0-9;?]*[A-Za-z]")

        fun stripAnsi(s: String): String = s.replace(ANSI_RE, "")
    }
}
