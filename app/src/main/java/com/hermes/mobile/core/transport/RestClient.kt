package com.hermes.mobile.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Subset of GET /api/status we actually render (verified Phase 0). */
@Serializable
data class ServerStatus(
    val version: String? = null,
    val gatewayRunning: Boolean = false,
    val gatewayState: String? = null,
    val activeSessions: Int = 0,
    val authRequired: Boolean = false,
    val overall: String? = null,
    val hermesHome: String? = null,
)

class RestException(val code: Int, message: String) : Exception("HTTP $code: $message")

/**
 * REST half of the control plane (`/api/…` endpoints). Loopback mode needs no auth;
 * tailnet deployments authenticate per the active CredentialStrategy —
 * the Authorization header is injected by [HermesClient]'s interceptor,
 * so this class never touches credentials itself.
 */
class RestClient(
    private val httpClient: OkHttpClient,
) {
    suspend fun getStatus(baseUrl: String): ServerStatus =
        getJson(baseUrl, "/api/status").let {
            HermesJson.decodeFromJsonElement(ServerStatus.serializer(), it)
        }

    suspend fun getJson(baseUrl: String, path: String): kotlinx.serialization.json.JsonElement =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(baseUrl + path).get().build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RestException(resp.code, body.take(300))
                HermesJson.parseToJsonElement(body.ifBlank { "null" })
            }
        }

    suspend fun postJson(baseUrl: String, path: String, payload: JsonObject): kotlinx.serialization.json.JsonElement =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(baseUrl + path)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RestException(resp.code, body.take(300))
                HermesJson.parseToJsonElement(body.ifBlank { "null" })
            }
        }

    /** Reachability probe used by profile racing — fast timeout, no retries. */
    suspend fun probe(baseUrl: String, timeoutMs: Long = 1500): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = httpClient.newBuilder()
                    .connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                client.newCall(Request.Builder().url("$baseUrl/api/status").get().build())
                    .execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
