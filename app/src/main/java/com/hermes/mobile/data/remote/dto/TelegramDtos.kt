package com.hermes.mobile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Telegram Bot API: https://core.telegram.org/bots/api

@Serializable
data class TgUser(
    val id: Long,
    val isBot: Boolean,
    val firstName: String,
    val lastName: String? = null,
    val username: String? = null,
    val languageCode: String? = null,
    @SerialName("allows_write_to_pm") val allowsWriteToPm: Boolean? = null
)

@Serializable
data class TgChat(
    val id: Long,
    val type: String,               // private | group | supergroup | channel
    val title: String? = null,
    val username: String? = null,
    val firstName: String? = null,
    val lastName: String? = null
)

@Serializable
data class TgMessage(
    @SerialName("message_id") val messageId: Long,
    @SerialName("from") val from: TgUser? = null,
    val date: Long,                  // unix seconds
    val chat: TgChat,
    val text: String? = null,
    @SerialName("photo") val photoSizes: List<TgPhotoSize>? = null,
    val document: TgDocument? = null,
    @SerialName("reply_to_message") val replyToMessage: TgMessage? = null,
    @SerialName("edit_date") val editDate: Long? = null
)

@Serializable
data class TgPhotoSize(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_unique_id") val fileUniqueId: String,
    val width: Int,
    val height: Int,
    val fileSize: Int? = null
)

@Serializable
data class TgDocument(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_unique_id") val fileUniqueId: String,
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSize: Int? = null
)

@Serializable
data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
    @SerialName("edited_message") val editedMessage: TgMessage? = null,
    @SerialName("callback_query") val callbackQuery: TgCallbackQuery? = null
)

@Serializable
data class TgCallbackQuery(
    @SerialName("id") val id: String,
    @SerialName("from") val from: TgUser,
    @SerialName("message") val message: TgMessage? = null,
    @SerialName("data") val data: String? = null
)

@Serializable
data class TgSendMessageRequest(
    @SerialName("chat_id") val chatId: String,
    val text: String,
    @SerialName("parse_mode") val parseMode: String? = "HTML",
    @SerialName("reply_markup") val replyMarkup: TgReplyMarkup? = null
)

@Serializable
data class TgSendPhotoRequest(
    @SerialName("chat_id") val chatId: String,
    @SerialName("photo") val photo: String,
    val caption: String? = null,
    @SerialName("parse_mode") val parseMode: String? = "HTML"
)

@Serializable
data class TgReplyMarkup(
    @SerialName("inline_keyboard") val inlineKeyboard: List<List<TgInlineKeyboardButton>>
)

@Serializable
data class TgInlineKeyboardButton(
    val text: String,
    @SerialName("callback_data") val callbackData: String? = null,
    val url: String? = null
)

@Serializable
data class TgResponse(
    val ok: Boolean,
    val result: TgMessage? = null
)

@Serializable
data class TgUserResponse(
    val ok: Boolean,
    val result: TgUser? = null
)
