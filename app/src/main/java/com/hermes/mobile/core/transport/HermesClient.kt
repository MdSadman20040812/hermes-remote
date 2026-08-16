package com.hermes.mobile.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/**
 * The one thing that talks to Hermes. Repositories see only this facade —
 * nothing above the transport layer knows the wire format (spec §C.2).
 *
 * Owns one [RpcChannel] (the `/api/ws` spine) and one [RestClient].
 * Credential handling lives in [com.hermes.mobile.core.connection.CredentialStrategy];
 * reconnect orchestration in ConnectionManager.
 */
class HermesClient(
    httpClient: OkHttpClient,
    scope: CoroutineScope,
) {
    val rpc = RpcChannel(httpClient, scope)
    val rest = RestClient(httpClient)

    // ---- typed convenience wrappers over the probed method surface ----

    suspend fun serverStatus(baseUrl: String): ServerStatus = rest.getStatus(baseUrl)

    suspend fun sessionList(): JsonElement? = rpc.call("session.list")

    suspend fun sessionCreate(title: String = ""): JsonElement? =
        rpc.call("session.create", rpcParamsOf("title" to title))

    suspend fun promptSubmit(sessionId: String, text: String): JsonElement? =
        rpc.call("prompt.submit", rpcParamsOf("session_id" to sessionId, "text" to text))

    suspend fun sessionInterrupt(sessionId: String): JsonElement? =
        rpc.call("session.interrupt", rpcParamsOf("session_id" to sessionId))

    suspend fun sessionSteer(sessionId: String, text: String): JsonElement? =
        rpc.call("session.steer", rpcParamsOf("session_id" to sessionId, "text" to text))

    suspend fun approvalRespond(sessionId: String, choice: String, all: Boolean = false): JsonElement? =
        rpc.call(
            "approval.respond",
            rpcParamsOf("session_id" to sessionId, "choice" to choice, "all" to all),
        )

    suspend fun sessionHistory(sessionId: String): JsonElement? =
        rpc.call("session.history", rpcParamsOf("session_id" to sessionId))

    suspend fun sessionResume(storedSessionId: String): JsonElement? =
        rpc.call("session.resume", rpcParamsOf("session_id" to storedSessionId))

    suspend fun sessionActivate(sessionId: String): JsonElement? =
        rpc.call("session.activate", rpcParamsOf("session_id" to sessionId))
}
