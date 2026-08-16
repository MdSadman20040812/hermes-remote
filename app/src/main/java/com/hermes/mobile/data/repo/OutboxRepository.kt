package com.hermes.mobile.data.repo

import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.data.local.OutboxDao
import com.hermes.mobile.data.local.OutboxEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline outbox (Phase 3): a prompt composed with no connection is queued in
 * Room and flushed, in order, exactly once, on the next Connected transition.
 * Exactly-once = the row is deleted only after prompt.submit returns a result.
 */
@Singleton
class OutboxRepository @Inject constructor(
    private val outboxDao: OutboxDao,
    private val connectionManager: ConnectionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    val pendingCount: Flow<Int> = outboxDao.count()

    /** Emits a user-visible note after a flush attempt. */
    private val _notices = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: kotlinx.coroutines.flow.SharedFlow<String> = _notices

    suspend fun enqueue(sessionId: String, text: String) {
        outboxDao.enqueue(OutboxEntity(sessionId = sessionId, text = text))
    }

    /** Idempotent — start watching for reconnects and flush on each. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            connectionManager.state.collect { state ->
                if (state is ConnState.Connected) flush()
            }
        }
    }

    suspend fun flush() {
        val pending = outboxDao.pending()
        if (pending.isEmpty()) return
        val client = connectionManager.clientFlow.value ?: return
        var sent = 0
        for (item in pending) {
            try {
                val sid = item.sessionId.ifBlank {
                    val result = client.sessionCreate()
                    result?.jsonObjectString("session_id")
                        ?: error("session.create returned no id during outbox flush")
                }
                client.promptSubmit(sid, item.text)
                outboxDao.delete(item.rowId) // exactly-once: delete only after success
                sent++
            } catch (e: Exception) {
                _notices.tryEmit("Outbox flush paused: ${e.message}")
                return // keep order — later items wait for the next reconnect
            }
        }
        if (sent > 0) _notices.tryEmit("Sent $sent queued prompt${if (sent > 1) "s" else ""}")
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectString(key: String): String? =
    runCatching {
        this@jsonObjectString.jsonObject[key]?.jsonPrimitive?.content
    }.getOrNull()
