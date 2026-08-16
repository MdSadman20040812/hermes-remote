package com.hermes.mobile.core.connection

import com.hermes.mobile.core.transport.ChannelState
import com.hermes.mobile.core.transport.HermesClient
import com.hermes.mobile.core.transport.ReconnectPolicy
import com.hermes.mobile.core.transport.ServerStatus
import com.hermes.mobile.core.vault.SecureVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What the UI banner renders. */
sealed interface ConnState {
    data object NoProfile : ConnState
    data object Probing : ConnState
    data class Connecting(val profile: ConnectionProfile) : ConnState
    data class Connected(val profile: ConnectionProfile, val status: ServerStatus?) : ConnState
    data class Reconnecting(val profile: ConnectionProfile, val attempt: Int) : ConnState
    data class Failed(val profile: ConnectionProfile?, val message: String) : ConnState
}

/**
 * Owns the active [HermesClient], profile selection, reachability racing and
 * the reconnect loop (spec §C.2/C.3).
 *
 * On app start: race `GET /api/status` (1.5 s timeout) across saved profiles,
 * bind the first that answers. While connected, a watcher follows the socket
 * state; on drop it re-races and reconnects with [ReconnectPolicy] backoff
 * until told to stop. Every socket lives in [managerScope] — no GlobalScope.
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val vault: SecureVault,
    private val clientFactory: HermesClientFactory,
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<ConnState>(ConnState.NoProfile)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    /** Null until first connect. */
    var client: HermesClient? = null
        private set

    /** Reactive view of [client] — repositories collect this to survive reconnects. */
    private val _clientFlow = MutableStateFlow<HermesClient?>(null)
    val clientFlow: StateFlow<HermesClient?> = _clientFlow.asStateFlow()

    /** Live socket state of the active client (Disconnected when none). */
    private val _channelState = MutableStateFlow<ChannelState>(ChannelState.Disconnected)
    val channelState: StateFlow<ChannelState> = _channelState.asStateFlow()

    /**
     * Every event from the active socket, app-wide (approvals, completion
     * notices for sessions nobody is watching, change broadcasts). The
     * notifier and the outbox collect this; screens should prefer their own
     * scoped engine.
     */
    private val _globalEvents = MutableSharedFlow<com.hermes.mobile.core.transport.HermesEvent>(
        extraBufferCapacity = 256,
    )
    val globalEvents: SharedFlow<com.hermes.mobile.core.transport.HermesEvent> =
        _globalEvents.asSharedFlow()

    private var activeProfile: ConnectionProfile? = null
    private var watchJob: Job? = null
    private val backoff = ReconnectPolicy()

    /** Entry point from the UI layer: pair → save → connect. */
    suspend fun pairAndConnect(payload: QrPairingPayload) {
        val profile = payload.toProfile()
        vault.saveProfile(profile, payload.token)
        connectTo(profile)
    }

    /** Manual entry path (same as QR, minus the camera). */
    suspend fun addManualProfile(label: String, host: String, port: Int, token: String) {
        val profile = ConnectionProfile(
            id = "pc-${host.replace('.', '-')}-$port",
            label = label.ifBlank { host },
            host = host,
            port = port,
            isDefault = true,
        )
        vault.saveProfile(profile, token)
        connectTo(profile)
    }

    /** Race all saved profiles; connect to the first reachable one. */
    suspend fun autoConnect() {
        val profiles = vault.profiles.value
        if (profiles.isEmpty()) {
            _state.value = ConnState.NoProfile
            return
        }
        _state.value = ConnState.Probing
        val winner = raceProfiles(profiles)
        if (winner == null) {
            _state.value = ConnState.Failed(profiles.firstOrNull(), "no saved PC is reachable")
            return
        }
        connectTo(winner)
    }

    /** First profile whose /api/status answers within the probe timeout wins. */
    suspend fun raceProfiles(profiles: List<ConnectionProfile>): ConnectionProfile? = coroutineScope {
        val probes = profiles.map { profile ->
            async { profile to probeProfile(profile) }
        }
        var winner: ConnectionProfile? = null
        // Resolve in completion order without a complex select: short-circuit loop.
        val remaining = probes.toMutableList()
        while (winner == null && remaining.isNotEmpty()) {
            val done = remaining.firstOrNull { it.isCompleted }
            if (done == null) {
                kotlinx.coroutines.yield()
                delay(50)
                continue
            }
            remaining.remove(done)
            val (profile, ok) = done.await()
            if (ok) {
                winner = profile
                remaining.forEach { it.cancel() }
            }
        }
        winner
    }

    private suspend fun probeProfile(profile: ConnectionProfile): Boolean {
        val probe = clientFactory.newClient(managerScope)
        return probe.rest.probe(profile.httpBase)
    }

    suspend fun connectTo(profile: ConnectionProfile) {
        val secret = vault.secretFor(profile.id)
        if (secret == null) {
            _state.value = ConnState.Failed(profile, "credential missing for ${profile.label}")
            return
        }
        reconnectJob?.cancel()
        disconnect()
        _state.value = ConnState.Connecting(profile)

        val newClient = clientFactory.newClient(managerScope)
        client = newClient
        _clientFlow.value = newClient
        activeProfile = profile

        // REST first: proves reachability + gives us /api/status for the banner.
        val status = runCatching { newClient.rest.getStatus(profile.httpBase) }.getOrNull()

        val strategy: CredentialStrategy = when (profile.auth) {
            AuthKind.TOKEN -> CredentialStrategy.Token(secret)
            AuthKind.GATED -> {
                _state.value = ConnState.Failed(
                    profile,
                    "gated auth not wired yet — bind the dashboard to loopback/Tailscale token mode",
                )
                return
            }
        }
        newClient.rpc.onReady = {
            backoff.reset()
            vault.markSeen(profile.id)
            _state.value = ConnState.Connected(profile, status)
        }
        newClient.rpc.connect(strategy.wsUrl(profile.wsBase, "/api/ws"))
        startWatching(profile, newClient)
    }

    private fun startWatching(profile: ConnectionProfile, watched: HermesClient) {
        watchJob?.cancel()
        watchJob = managerScope.launch {
            launch {
                watched.rpc.events.collect { _globalEvents.tryEmit(it) }
            }
            watched.rpc.state.collect { channelState ->
                _channelState.value = channelState
                when (channelState) {
                    is ChannelState.Degraded -> reconnectLoop(profile)
                    else -> { /* Connecting/Ready handled via onReady */ }
                }
            }
        }
    }

    private var reconnectJob: Job? = null

    private fun reconnectLoop(profile: ConnectionProfile) {
        if (reconnectJob?.isActive == true) return
        reconnectJob = managerScope.launch {
            while (true) {
                val attempt = backoff.attempt + 1
                _state.value = ConnState.Reconnecting(profile, attempt)
                delay(backoff.nextDelay())
                val reachable = probeProfile(profile)
                if (!reachable) continue
                connectTo(profile)
                return@launch
            }
        }
    }

    fun disconnect() {
        watchJob?.cancel()
        reconnectJob?.cancel()
        client?.rpc?.disconnect()
        client = null
        _clientFlow.value = null
    }

    /** Secret lookup for ancillary channels (PTY socket auth). Never log the result. */
    fun vaultSecret(profileId: String): String? = vault.secretFor(profileId)

    fun forgetProfile(id: String) {
        if (activeProfile?.id == id) {
            disconnect()
            activeProfile = null
            _state.value = ConnState.NoProfile
        }
        vault.removeProfile(id)
    }
}
