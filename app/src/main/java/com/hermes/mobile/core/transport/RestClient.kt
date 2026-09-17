package com.hermes.mobile.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Subset of `GET /api/status` we actually render.
 *
 * The wire is snake_case. Without these [SerialName]s every field below stayed
 * at its default — which is why `authRequired` read as `false` on a gated
 * server and the client kept choosing token auth against a dashboard that
 * rejects tokens. `ignoreUnknownKeys` in [HermesJson] hid the mismatch.
 *
 * Every field is either optional or lenient ON PURPOSE. `gateway_platforms`
 * was declared `List<String>` while the server sends
 * `{"telegram": {"state": ...}}`; that mismatch threw a JsonDecodingException
 * out of EVERY `/api/status` parse, which the connect path reports as
 * "Can't reach your PC" — a wrong type on a field nobody renders made the app
 * unusable. Status is a diagnostic document owned by the server: decode it
 * permissively, and never let an unread field fail the parse.
 */
@Serializable
data class ServerStatus(
    val version: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("gateway_running") val gatewayRunning: Boolean = false,
    @SerialName("gateway_state") val gatewayState: String? = null,
    @SerialName("gateway_platforms") val gatewayPlatforms: JsonElement? = null,
    @SerialName("active_sessions") val activeSessions: Int = 0,
    @SerialName("active_agents") val activeAgents: Int = 0,
    @SerialName("auth_required") val authRequired: Boolean = false,
    @SerialName("auth_providers") val authProviders: List<String> = emptyList(),
    @SerialName("auth_flows") val authFlows: List<String> = emptyList(),
    @SerialName("gateway_mode") val gatewayMode: String? = null,
    @SerialName("can_update_hermes") val canUpdate: Boolean = false,
    val overall: String? = null,
    @SerialName("hermes_home") val hermesHome: String? = null,
) {
    /** Platform names, whether the server sent an object or a list. */
    val platformNames: List<String>
        get() = when (val p = gatewayPlatforms) {
            is JsonObject -> p.keys.toList()
            is JsonArray -> p.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
}

class RestException(val code: Int, message: String) : Exception("HTTP $code: $message")

/** Percent-encode one query-string value. */
fun q(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

/**
 * REST half of the control plane (`/api/…`).
 *
 * Loopback needs no credential. A gated deployment authenticates with the
 * `hermes_session_*` cookies the OkHttp client's jar holds — see
 * [com.hermes.mobile.core.connection.HermesCookieJar]. This class never
 * touches credentials itself.
 */
class RestClient(
    private val httpClient: OkHttpClient,
) {
    suspend fun getStatus(baseUrl: String): ServerStatus =
        getJson(baseUrl, "/api/status").let {
            HermesJson.decodeFromJsonElement(ServerStatus.serializer(), it)
        }

    suspend fun getJson(baseUrl: String, path: String): JsonElement =
        execute(Request.Builder().url(baseUrl + path).get())

    suspend fun postJson(baseUrl: String, path: String, payload: JsonObject): JsonElement =
        execute(Request.Builder().url(baseUrl + path).post(payload.body()))

    suspend fun putJson(baseUrl: String, path: String, payload: JsonObject): JsonElement =
        execute(Request.Builder().url(baseUrl + path).put(payload.body()))

    suspend fun deleteJson(baseUrl: String, path: String): JsonElement =
        execute(Request.Builder().url(baseUrl + path).delete())

    /** Raw bytes — file downloads, where a JSON parse would be wrong. */
    suspend fun getBytes(baseUrl: String, path: String): ByteArray =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(baseUrl + path).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw RestException(resp.code, resp.body?.string()?.take(300).orEmpty())
                }
                resp.body?.bytes() ?: ByteArray(0)
            }
        }

    private suspend fun execute(builder: Request.Builder): JsonElement =
        withContext(Dispatchers.IO) {
            httpClient.newCall(builder.build()).execute().use { resp ->
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
        fun JsonObject.body() = toString().toRequestBody(JSON_MEDIA)
    }
}
