package com.hermes.mobile.core.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/** Mirrors PtyConnectionState in pty-reconnect.ts. */
enum class PtyState { CONNECTING, OPEN, RECONNECTING, CLOSED, ENDED }

/**
 * `/api/pty` socket (Phase 0 probed): downstream is BINARY frames of raw PTY
 * output, upstream is raw bytes written to the PTY master. Child EOF closes
 * with 4410; auth/host failures close pre-accept with 4401/4403/4404/4408.
 *
 * Reconnect strategy constants are ported from pty-reconnect.ts; the ViewModel
 * owns the actual reconnect loop (it knows about lifecycles).
 */
class PtyChannel(
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PtyState.CLOSED)
    val state: StateFlow<PtyState> = _state.asStateFlow()

    private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val output: SharedFlow<ByteArray> = _output.asSharedFlow()

    /** Server-initiated close reason, when one arrives (4401/4410/…). */
    private val _closeReason = MutableStateFlow<String?>(null)
    val closeReason: StateFlow<String?> = _closeReason.asStateFlow()

    @Volatile
    private var socket: WebSocket? = null

    fun connect(url: String) {
        _state.value = PtyState.CONNECTING
        _closeReason.value = null
        socket = httpClient.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _state.value = PtyState.OPEN
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    _output.tryEmit(bytes.toByteArray())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // Server's pre-accept error banners arrive as text.
                    _output.tryEmit(text.toByteArray(Charsets.UTF_8))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _closeReason.value = "$code $reason".trim()
                    // 4410 = child EOF — the session ended, do not auto-reconnect.
                    _state.value = if (code == 4410) PtyState.ENDED else PtyState.CLOSED
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _closeReason.value = t.message
                    _state.value = PtyState.CLOSED
                }
            },
        )
    }

    /** Raw terminal input (already normalized by the caller). */
    fun send(data: String) {
        socket?.send(data.toByteArray(Charsets.UTF_8).toByteString())
    }

    fun close() {
        _state.value = PtyState.CLOSED
        socket?.close(1000, "client closing")
        socket = null
    }

    companion object {
        /** pty-reconnect.ts: PTY_RESUME_RECONNECT_THROTTLE_MS. */
        const val RESUME_RECONNECT_THROTTLE_MS = 1000L

        /** pty-reconnect.ts: PTY_CONNECTING_TIMEOUT_MS (wedged half-open sockets). */
        const val CONNECTING_TIMEOUT_MS = 8000L
    }
}
