package com.hermes.mobile.data.remote

import com.hermes.mobile.data.local.SecureTokenStore
import com.hermes.mobile.data.remote.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Direct Telegram Bot API client.
 *
 * Two interaction modes:
 *  1. Push: Hermes PC pushes updates to this app via Drive-based outbox (offline-safe)
 *  2. Pull: This app polls getUpdates when it has network connectivity
 *
 * All network calls are forced onto [Dispatchers.IO] via a single OkHttpClient.
 * Secrets come from [SecureTokenStore] (encrypted), never from Room/logs.
 */
@Singleton
class TelegramRemoteDataSource @Inject constructor(
    private val client: OkHttpClient,
    private val secureStore: SecureTokenStore
) {
    private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val MEDIA_JSON = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val TG_API_BASE = "https://api.telegram.org/bot"
        private const val MAX_MESSAGE_LENGTH = 4096
    }

    private suspend fun requireConfigured() {
        val token = secureStore.telegramBotToken
            ?: throw IllegalStateException("Telegram bot token not configured")
        if (!token.matches(Regex("^[0-9]+:[A-Za-z0-9_-]{35,}$"))) {
            throw IllegalArgumentException("Telegram bot token format is invalid")
        }
    }

    private fun validateChatId(chatId: String) {
        if (chatId.isBlank() || !chatId.all { it.isDigit() || it == '-' }) {
            throw IllegalArgumentException("Invalid Telegram chat_id: $chatId")
        }
    }

    private fun validateMessageText(text: String) {
        if (text.isBlank()) throw IllegalArgumentException("Message text must not be blank")
        if (text.length > MAX_MESSAGE_LENGTH) {
            throw IllegalArgumentException("Message text exceeds $MAX_MESSAGE_LENGTH chars (${text.length})")
        }
    }

    /** Returns the full bot URL for a given method, e.g. "bot123:ABC/getMe" */
    private fun botMethodUrl(method: String): String {
        val token = secureStore.telegramBotToken
            ?: throw IllegalStateException("Telegram bot token not configured")
        return "$TG_API_BASE$token/$method"
    }

    suspend fun getMe(): TgUser {
        requireConfigured()
        return callPost("getMe", emptyMap(), TgUserResponse.serializer())
            ?.result
            ?: throw IOException("No result from getMe")
    }

    suspend fun sendMessage(
        chatId: String,
        text: String,
        parseMode: String = "HTML",
        inlineKeyboard: List<List<TgInlineKeyboardButton>>? = null
    ): TgMessage {
        requireConfigured()
        validateChatId(chatId)
        validateMessageText(text)
        val body = buildMap<String, Any?> {
            put("chat_id", chatId)
            put("text", text)
            put("parse_mode", parseMode)
            if (inlineKeyboard != null) {
                put("reply_markup", JSON.encodeToString(TgReplyMarkup(inlineKeyboard)))
            }
        }
        return callPost("sendMessage", body, TgResponse.serializer())
            ?.result
            ?: throw IOException("sendMessage returned null result")
    }

    suspend fun sendPhoto(
        chatId: String,
        imageBytes: ByteArray,
        caption: String? = null
    ): TgMessage = withContext(Dispatchers.IO) {
        requireConfigured()
        validateChatId(chatId)
        require(imageBytes.isNotEmpty()) { "Image bytes must not be empty" }
        val token = secureStore.telegramBotToken
            ?: throw IllegalStateException("Telegram bot token not configured")
        val url = "$TG_API_BASE$token/sendPhoto"

        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart(
                "photo", "upload.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaType())
            )
        caption?.let {
            if (it.length > 1024) throw IllegalArgumentException("Photo caption exceeds 1024 chars")
            multipart.addFormDataPart("caption", it)
        }
        multipart.addFormDataPart("parse_mode", "HTML")

        val request = Request.Builder()
            .url(url)
            .post(multipart.build())
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} sendPhoto")
            val bodyStr = response.body?.string() ?: throw IOException("Empty sendPhoto response")
            val parsed = JSON.decodeFromString(TgResponse.serializer(), bodyStr)
            parsed.result ?: throw IOException("sendPhoto result null")
        }
    }

    suspend fun answerCallbackQuery(callbackQueryId: String, text: String? = null) {
        requireConfigured()
        val body = mutableMapOf<String, Any?>("callback_query_id" to callbackQueryId)
        text?.let { body["text"] = it.take(200) }
        callPost("answerCallbackQuery", body, TgBooleanResponse.serializer())
    }

    /** Poll getUpdates — use with long-polling (timeout=30). */
    suspend fun getUpdates(offset: Long = 0, limit: Int = 50): List<TgUpdate> {
        requireConfigured()
        val body = buildMap<String, Any?> {
            put("offset", offset)
            put("limit", limit.coerceIn(1, 100))
            put("timeout", 30)
        }
        return callPost("getUpdates", body, TgUpdateResponse.serializer())
            ?.result
            ?: emptyList()
    }

    @Serializable
    private data class TgUpdateResponse(val ok: Boolean, val result: List<TgUpdate> = emptyList())

    @Serializable
    private data class TgBooleanResponse(val ok: Boolean, val result: Boolean? = null)

    private suspend fun <T> callPost(
        method: String,
        body: Map<String, Any?>,
        serializer: kotlinx.serialization.KSerializer<T>
    ): T? = withContext(Dispatchers.IO) {
        val json = JSON.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                body.forEach { (k, v) ->
                    when (v) {
                        is String -> put(k, JsonPrimitive(v))
                        is Number -> put(k, JsonPrimitive(v))
                        is Boolean -> put(k, JsonPrimitive(v))
                        null -> {} // omit nulls
                        else -> put(k, JsonPrimitive(v.toString()))
                    }
                }
            }
        )
        val request = Request.Builder()
            .url(botMethodUrl(method))
            .post(json.toRequestBody(MEDIA_JSON))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} $method")
            val bodyStr = response.body?.string() ?: return@withContext null
            JSON.decodeFromString(serializer, bodyStr)
        }
    }
}
