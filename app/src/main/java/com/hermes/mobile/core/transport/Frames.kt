package com.hermes.mobile.core.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Wire-format DTOs for the Hermes dashboard JSON-RPC 2.0 socket (`/api/ws`).
 *
 * Frame shapes here were captured empirically in Phase 0 against the live
 * server (see pc/probe_output.txt and PHASE0-FINDINGS.md) — not from the spec.
 *
 * Transport: newline-delimited JSON, one frame per line, text frames.
 * Requests correlate to responses by `id`. Server-pushed events arrive as
 * {"jsonrpc":"2.0","method":"event","params":{"type":…,"session_id":…,"payload":{…}}}.
 */

val HermesJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

// ---------------------------------------------------------------------------
// Outbound
// ---------------------------------------------------------------------------

@Serializable
data class RpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonObject = buildJsonObject {},
) {
    fun encode(): String = HermesJson.encodeToString(serializer(), this) + "\n"
}

fun rpcParamsOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) {
        when (v) {
            null -> {} // explicitNulls=false semantics: omit
            is String -> put(k, v)
            is Int -> put(k, v)
            is Long -> put(k, v)
            is Boolean -> put(k, v)
            is Double -> put(k, v)
            is JsonElement -> put(k, v)
            else -> put(k, v.toString())
        }
    }
}

// ---------------------------------------------------------------------------
// Inbound — responses
// ---------------------------------------------------------------------------

@Serializable
data class RpcError(
    val code: Int,
    val message: String,
)

class RpcException(val error: RpcError) : Exception("RPC ${error.code}: ${error.message}")

/** Raw inbound frame, discriminated by shape. */
sealed interface InboundFrame {
    data class Response(val id: Int, val result: JsonElement?, val error: RpcError?) : InboundFrame
    data class Event(val event: HermesEvent) : InboundFrame
}

// ---------------------------------------------------------------------------
// Inbound — events (typed, matching Phase-0 captures)
// ---------------------------------------------------------------------------

sealed interface HermesEvent {
    /** Present on session-scoped events; empty for global broadcasts. */
    val sessionId: String

    data class Ready(val raw: JsonObject) : HermesEvent {
        override val sessionId: String = ""
        val changeEvents: Boolean
            get() = raw["change_events"]?.jsonPrimitive?.contentOrNull == "true" ||
                (raw["change_events"] as? JsonPrimitive)?.content == "true"
    }

    data class SessionInfo(
        override val sessionId: String,
        val model: String?,
        val provider: String?,
        val approvalMode: String?,
        val contextPercent: Int?,
        val raw: JsonObject,
    ) : HermesEvent

    data class MessageStart(override val sessionId: String) : HermesEvent

    /** Streaming text delta. kind: message | reasoning | thinking. */
    data class Delta(
        override val sessionId: String,
        val kind: DeltaKind,
        val text: String,
    ) : HermesEvent

    data class MessageComplete(
        override val sessionId: String,
        val text: String,
        val status: String?,
        val reasoning: String?,
        val usage: Usage?,
    ) : HermesEvent

    data class ToolStart(
        override val sessionId: String,
        val toolId: String,
        val name: String,
        val context: String?,
    ) : HermesEvent

    data class ToolComplete(
        override val sessionId: String,
        val toolId: String,
        val name: String,
        val args: JsonObject?,
        val durationS: Double?,
        val result: JsonElement?,
    ) : HermesEvent

    /** approval.request — literal payload shape from Phase 0 probe. */
    data class ApprovalRequest(
        override val sessionId: String,
        val command: String?,
        val description: String?,
        val patternKey: String?,
        val choices: List<String>,
        val allowPermanent: Boolean,
        val allowSession: Boolean,
    ) : HermesEvent

    data class SessionTitle(override val sessionId: String, val title: String) : HermesEvent

    data class StatusUpdate(override val sessionId: String, val text: String?) : HermesEvent

    /** Global broadcast (sessions.changed, session.reclaimed, cron.changed…). */
    data class Global(val type: String, val raw: JsonObject?) : HermesEvent {
        override val sessionId: String = ""
    }

    /** Anything not yet typed — kept, never dropped. */
    data class Unknown(val type: String, override val sessionId: String, val raw: JsonObject?) : HermesEvent
}

enum class DeltaKind { MESSAGE, REASONING, THINKING }

@Serializable
data class Usage(
    val model: String? = null,
    val input: Long? = null,
    val output: Long? = null,
    val total: Long? = null,
    val calls: Int? = null,
    val contextPercent: Int? = null,
    val contextUsed: Long? = null,
    val contextMax: Long? = null,
)

// ---------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------

private fun JsonObject?.str(key: String): String? =
    this?.get(key)?.jsonPrimitive?.contentOrNull

private fun JsonObject?.bool(key: String): Boolean =
    this?.get(key)?.jsonPrimitive?.contentOrNull == "true"

/** Parse one NDJSON line into a typed frame. Never throws on unknown shapes. */
fun parseInboundFrame(line: String): InboundFrame? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null
    val obj = runCatching { HermesJson.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null

    // Response: has "id" and no "method"
    if ("method" !in obj && obj["id"] != null) {
        val id = obj["id"]!!.jsonPrimitive.intOrNull ?: return null
        val err = (obj["error"] as? JsonObject)?.let {
            RpcError(code = it.str("code")?.toIntOrNull() ?: -1, message = it.str("message") ?: "unknown")
        }
        return InboundFrame.Response(id, obj["result"], err)
    }

    // Event: method == "event", params.type discriminates
    val params = obj["params"] as? JsonObject ?: return null
    val type = params.str("type") ?: return null
    val sid = params.str("session_id") ?: ""
    val payload = params["payload"] as? JsonObject

    val event: HermesEvent = when (type) {
        "gateway.ready" -> HermesEvent.Ready(payload ?: buildJsonObject {})
        "session.info" -> HermesEvent.SessionInfo(
            sessionId = sid,
            model = payload.str("model"),
            provider = payload.str("provider"),
            approvalMode = payload.str("approval_mode"),
            contextPercent = payload?.get("context_percent")?.jsonPrimitive?.intOrNull,
            raw = payload ?: buildJsonObject {},
        )
        "message.start" -> HermesEvent.MessageStart(sid)
        "message.delta" -> HermesEvent.Delta(sid, DeltaKind.MESSAGE, payload.str("text") ?: "")
        "reasoning.delta" -> HermesEvent.Delta(sid, DeltaKind.REASONING, payload.str("text") ?: "")
        "thinking.delta" -> HermesEvent.Delta(sid, DeltaKind.THINKING, payload.str("text") ?: "")
        "message.complete" -> HermesEvent.MessageComplete(
            sessionId = sid,
            text = payload.str("text") ?: "",
            status = payload.str("status"),
            reasoning = payload.str("reasoning"),
            usage = (payload?.get("usage") as? JsonObject)?.let { u ->
                Usage(
                    model = u.str("model"),
                    input = u["input"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                    output = u["output"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                    total = u["total"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                    calls = u["calls"]?.jsonPrimitive?.intOrNull,
                    contextPercent = u["context_percent"]?.jsonPrimitive?.intOrNull,
                    contextUsed = u["context_used"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                    contextMax = u["context_max"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                )
            },
        )
        "tool.start" -> HermesEvent.ToolStart(
            sessionId = sid,
            toolId = payload.str("tool_id") ?: "",
            name = payload.str("name") ?: "",
            context = payload.str("context"),
        )
        "tool.complete" -> HermesEvent.ToolComplete(
            sessionId = sid,
            toolId = payload.str("tool_id") ?: "",
            name = payload.str("name") ?: "",
            args = payload?.get("args") as? JsonObject,
            durationS = payload?.get("duration_s")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            result = payload?.get("result"),
        )
        "approval.request" -> HermesEvent.ApprovalRequest(
            sessionId = sid,
            command = payload.str("command"),
            description = payload.str("description"),
            patternKey = payload.str("pattern_key"),
            choices = (payload?.get("choices") as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            allowPermanent = payload.bool("allow_permanent"),
            allowSession = payload.bool("allow_session"),
        )
        "session.title" -> HermesEvent.SessionTitle(sid, payload.str("title") ?: "")
        "status.update" -> HermesEvent.StatusUpdate(sid, payload.str("text") ?: payload.str("kind"))
        "sessions.changed", "session.reclaimed", "cron.changed", "pet.changed" ->
            HermesEvent.Global(type, payload)
        else -> HermesEvent.Unknown(type, sid, payload)
    }
    return InboundFrame.Event(event)
}
