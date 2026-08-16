package com.hermes.mobile.data.repo

import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ops surface (Phase 5): system stats, processes, gateway control, logs,
 * usage/cost, models, skills, cron. Everything parses leniently — the server
 * shapes are large and evolve; we render the fields we know and keep going.
 */
@Singleton
class OpsRepository @Inject constructor(
    private val connectionManager: ConnectionManager,
) {
    private fun base(): String =
        (connectionManager.state.value as? ConnState.Connected)?.profile?.httpBase
            ?: error("Not connected")

    private suspend fun client() = connectionManager.clientFlow.first { it != null }!!

    suspend fun systemStats(): JsonObject =
        client().rest.getJson(base(), "/api/system/stats").jsonObject

    suspend fun processList(): JsonElement? = client().rpc.call("process.list")

    suspend fun processKill(pid: Int): JsonElement? =
        client().rpc.call(
            "process.kill",
            com.hermes.mobile.core.transport.rpcParamsOf("pid" to pid),
        )

    suspend fun gatewayAction(action: String): JsonElement =
        client().rest.postJson(base(), "/api/gateway/$action", JsonObject(emptyMap()))

    suspend fun logsTail(): JsonElement =
        client().rest.getJson(base(), "/api/logs")

    suspend fun analyticsUsage(): JsonElement =
        client().rest.getJson(base(), "/api/analytics/usage")

    suspend fun modelOptions(): JsonElement =
        client().rest.getJson(base(), "/api/model/options")

    suspend fun setModel(model: String, provider: String? = null): JsonElement =
        client().rest.postJson(
            base(), "/api/model/set",
            com.hermes.mobile.core.transport.rpcParamsOf(
                "model" to model, "provider" to provider,
            ),
        )

    suspend fun skills(): JsonElement =
        client().rest.getJson(base(), "/api/skills")

    suspend fun cronJobs(): JsonElement =
        client().rest.getJson(base(), "/api/cron/jobs")
}

/** Lenient JSON helpers shared by ops screens. */
fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

fun JsonElement?.asArray(): JsonArray? = this as? JsonArray
fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
