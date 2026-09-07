package com.hermes.mobile.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.core.connection.QrPairingPayload
import com.hermes.mobile.core.net.DiscoveredPc
import com.hermes.mobile.core.net.LinkKind
import com.hermes.mobile.core.net.NetworkMonitor
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
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    val connState: StateFlow<ConnState> = connectionManager.state
    val profiles = connectionManager.profiles
    val discovered: StateFlow<List<DiscoveredPc>> = connectionManager.discovered
    val scanning: StateFlow<Boolean> = connectionManager.scanning
    val link: StateFlow<LinkKind> = networkMonitor.link

    /** Visible, user-facing failures — never a silent catch (project rule). */
    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    /** Set while a QR payload / manual form is being processed (debounce). */
    private var busy = false

    init {
        viewModelScope.launch {
            connectionManager.notices.collect { _userMessage.tryEmit(it) }
        }
    }

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

    fun onManualSubmit(
        label: String,
        host: String,
        port: Int?,
        secret: String,
        secure: Boolean,
    ) {
        if (busy) return
        if (host.isBlank() || secret.isBlank()) {
            _userMessage.tryEmit("The address and a credential are both required")
            return
        }
        busy = true
        viewModelScope.launch {
            try {
                connectionManager.addManualProfile(
                    label = label,
                    host = host.trim(),
                    port = port ?: 9119,
                    secret = secret.trim(),
                    secure = secure,
                )
            } catch (e: Exception) {
                _userMessage.emit("Connect failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    /** Sweep the Wi-Fi for a dashboard. Cheap, credential-free, ~2 s. */
    fun scanLan() {
        if (busy) return
        viewModelScope.launch {
            val found = connectionManager.scanLan()
            if (found.isEmpty()) {
                _userMessage.emit(
                    "No Hermes on this Wi-Fi. Start the dashboard on your PC " +
                        "(hermes-remote.ps1) and make sure both are on the same router.",
                )
            }
        }
    }

    /** Attach to a PC the sweep found. Falls back to the manual form for a credential. */
    fun onDiscoveredPicked(pc: DiscoveredPc, onNeedsCredential: (DiscoveredPc) -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                if (!connectionManager.connectToDiscovered(pc)) onNeedsCredential(pc)
            } catch (e: Exception) {
                _userMessage.emit("Connect failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    fun retry() = connectionManager.retryNow()

    fun forgetCurrentProfile() {
        val current = (connState.value as? ConnState.Failed)?.profile
            ?: (connState.value as? ConnState.Reconnecting)?.profile
        if (current != null) connectionManager.forgetProfile(current.id)
    }

    fun connectToSaved(id: String) {
        viewModelScope.launch {
            val profile = profiles.value.firstOrNull { it.id == id } ?: return@launch
            connectionManager.connectTo(profile)
        }
    }
}
