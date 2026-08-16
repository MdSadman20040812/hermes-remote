package com.hermes.mobile.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.core.connection.QrPairingPayload
import com.hermes.mobile.core.transport.HermesJson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    val connState: StateFlow<ConnState> = connectionManager.state

    /** Visible, user-facing failures — never a silent catch (project rule). */
    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    /** Set while a QR payload / manual form is being processed (debounce). */
    private var busy = false

    fun onQrScanned(raw: String) {
        if (busy) return
        val payload = runCatching {
            HermesJson.decodeFromString(QrPairingPayload.serializer(), raw)
        }.getOrNull()
        if (payload == null) {
            _userMessage.tryEmit("That QR isn't a Hermes pairing code")
            return
        }
        busy = true
        viewModelScope.launch {
            try {
                connectionManager.pairAndConnect(payload)
            } catch (e: Exception) {
                _userMessage.emit("Pairing failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    fun onManualSubmit(label: String, host: String, port: Int?, token: String) {
        if (busy) return
        if (host.isBlank() || token.isBlank()) {
            _userMessage.tryEmit("Host and token are required")
            return
        }
        busy = true
        viewModelScope.launch {
            try {
                connectionManager.addManualProfile(label, host.trim(), port ?: 9119, token.trim())
            } catch (e: Exception) {
                _userMessage.emit("Connect failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    fun retry() {
        viewModelScope.launch { connectionManager.autoConnect() }
    }

    fun forgetCurrentProfile() {
        val current = (connState.value as? ConnState.Failed)?.profile
            ?: (connState.value as? ConnState.Reconnecting)?.profile
        if (current != null) connectionManager.forgetProfile(current.id)
    }
}
