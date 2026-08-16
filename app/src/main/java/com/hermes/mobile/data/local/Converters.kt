package com.hermes.mobile.data.local

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

class Converters {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(String.serializer())

    @TypeConverter
    fun stringListToJson(list: List<String>): String = json.encodeToString(listSerializer, list)

    @TypeConverter
    fun jsonToStringList(raw: String?): List<String> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
}
