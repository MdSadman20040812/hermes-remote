package com.hermes.mobile.data.repo

import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.core.transport.HermesClient
import com.hermes.mobile.data.local.MessageEntity
import com.hermes.mobile.data.local.SessionDao
import com.hermes.mobile.data.local.SessionEntity
import com.hermes.mobile.domain.model.SessionSummary
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session lifecycle against the live server, with Room as a read-through cache.
 * Cache is derived state — the server's state.db is authoritative (spec §C.6).
 */
@Singleton
class SessionRepository @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val sessionDao: SessionDao,
) {
    val cachedSessions = sessionDao.observeAll()

    private suspend fun client(): HermesClient =
        connectionManager.clientFlow.first { it != null }!!

    /** session.list → typed summaries; refreshes the Room cache. */
    suspend fun listSessions(): List<SessionSummary> {
        val result = client().sessionList() ?: return emptyList()
        val sessions = result.jsonObject["sessions"]?.jsonArray ?: return emptyList()
        val parsed = sessions.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            SessionSummary(
                id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                title = o["title"]?.jsonPrimitive?.content ?: "",
                preview = o["preview"]?.jsonPrimitive?.content ?: "",
                startedAt = o["started_at"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                messageCount = o["message_count"]?.jsonPrimitive?.intOrNull ?: 0,
                source = o["source"]?.jsonPrimitive?.content ?: "",
            )
        }
        sessionDao.upsertAll(parsed.map {
            SessionEntity(it.id, it.title, it.preview, it.startedAt, it.messageCount, it.source)
        })
        return parsed
    }

    /** session.create → the LIVE handle (not the stored id — Phase 0 finding). */
    suspend fun createSession(title: String = ""): String {
        val result = client().sessionCreate(title)
        val sid = result?.jsonObject?.get("session_id")?.jsonPrimitive?.content
        return sid ?: error("session.create returned no session_id")
    }

    /** session.resume (stored id) → live handle. Server returns the new handle. */
    suspend fun resumeSession(storedId: String): String {
        val result = client().rpc.call(
            "session.resume",
            com.hermes.mobile.core.transport.rpcParamsOf(
                "session_id" to storedId,
                "omit_messages" to true, // we hydrate via session.history ourselves
            ),
        )
        val obj = result?.jsonObject
        return obj?.get("session_id")?.jsonPrimitive?.content
            ?: obj?.get("info")?.jsonObject?.get("session_id")?.jsonPrimitive?.content
            ?: storedId // fall back: some paths echo the target
    }
}
