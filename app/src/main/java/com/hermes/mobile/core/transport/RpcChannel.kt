package com.hermes.mobile.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Lifecycle of the RPC socket. */
sealed interface ChannelState {
    data object Disconnected : ChannelState
    data object Connecting : ChannelState
    data object Ready : ChannelState
    /** Socket dropped unexpectedly; a reconnect loop may be running above us. */
    data class Degraded(val reason: String) : ChannelState
}

/**
 * JSON-RPC 2.0 over NDJSON on an OkHttp WebSocket — the `/api/ws` spine.
 *
 * Contract (spec §C.2):
 *  - newline-delimited frames, both directions
 *  - responses correlated by `id` via a pending-map of CompletableDeferred
 *  - server events fan out on [events]
 *  - OkHttp pingInterval(20s) detects half-open sockets on mobile handoffs
 *
 * This class owns no reconnect policy — [com.hermes.mobile.core.connection.ConnectionManager]
 * drives reconnects so it can also re-run reachability racing across profiles.
 */
class RpcChannel(
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ChannelState>(ChannelState.Disconnected)
    val state: StateFlow<ChannelState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HermesEvent>(
        extraBufferCapacity = 512, // absorb delta bursts without suspending the reader
    )
    val events: SharedFlow<HermesEvent> = _events.asSharedFlow()

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<InboundFrame.Response>>()
    private val nextId = AtomicInteger(1)

    @Volatile
    private var socket: WebSocket? = null

    /** Called when gateway.ready arrives — ConnectionManager resets its backoff. */
    var onReady: (() -> Unit)? = null

    fun connect(url: String) {
        if (_state.value is ChannelState.Connecting || _state.value is ChannelState.Ready) return
        _state.value = ChannelState.Connecting
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Stay "Connecting" until gateway.ready lands — open ≠ authed.
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // NDJSON: a single WS text frame may carry multiple lines.
                for (line in text.split('\n')) {
                    if (line.isBlank()) continue
                    when (val frame = parseInboundFrame(line)) {
                        is InboundFrame.Response -> {
                            pending.remove(frame.id)?.let { deferred ->
                                if (!deferred.complete(frame)) {
                                    // already timed out — drop silently
                                }
                            }
                        }
                        is InboundFrame.Event -> {
                            if (frame.event is HermesEvent.Ready) {
                                _state.value = ChannelState.Ready
                                onReady?.invoke()
                            }
                            _events.tryEmit(frame.event)
                        }
                        null -> { /* unparsable line — ignore, never crash the reader */ }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleGone("closed $code ${reason.ifBlank { "" }}".trim())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleGone(t.message ?: "socket failure")
            }
        }
        socket = httpClient.newWebSocket(request, listener)
    }

    private fun handleGone(reason: String) {
        socket = null
        failAllPending("connection lost: $reason")
        val current = _state.value
        if (current !is ChannelState.Disconnected) {
            _state.value = ChannelState.Degraded(reason)
        }
    }

    private fun failAllPending(message: String) {
        val iter = pending.values.iterator()
        while (iter.hasNext()) {
            iter.next().completeExceptionally(RpcException(RpcError(-1, message)))
        }
        pending.clear()
    }

    /** Call an RPC method; returns the raw result element. Throws [RpcException] on error/timeout. */
    suspend fun call(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeout: Duration = 30.seconds,
    ): kotlinx.serialization.json.JsonElement? {
        val ws = socket ?: throw RpcException(RpcError(-1, "not connected"))
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<InboundFrame.Response>()
        pending[id] = deferred
        val sent = ws.send(RpcRequest(id = id, method = method, params = params).encode())
        if (!sent) {
            pending.remove(id)
            throw RpcException(RpcError(-1, "send failed — socket backpressured or closed"))
        }
        val response = try {
            withTimeout(timeout) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            pending.remove(id)
            throw RpcException(RpcError(-2, "timeout after $timeout calling $method"))
        }
        response.error?.let { throw RpcException(it) }
        return response.result
    }

    /** Graceful close. State → Disconnected; no Degraded blip. */
    fun disconnect() {
        _state.value = ChannelState.Disconnected
        val ws = socket
        socket = null
        failAllPending("disconnect")
        ws?.close(1000, "client disconnect")
    }
}
