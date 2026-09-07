package com.hermes.mobile.data.repo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lenient readers shared by every repository.
 *
 * The dashboard payloads are wide, versioned independently of this app, and
 * frequently mix `null`, `""` and a missing key for the same "unknown". Every
 * accessor here answers null rather than throwing, so one unexpected field
 * degrades a single row instead of blanking a whole screen.
 */

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray

private fun JsonElement?.prim(): JsonPrimitive? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

fun JsonObject?.str(key: String): String? =
    this?.get(key).prim()?.content?.takeIf { it.isNotEmpty() && it != "null" }

fun JsonObject?.int(key: String): Int? = this?.get(key).prim()?.content?.toIntOrNull()

fun JsonObject?.long(key: String): Long? =
    this?.get(key).prim()?.content?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

fun JsonObject?.dbl(key: String): Double? = this?.get(key).prim()?.content?.toDoubleOrNull()

/** Absent, null, `false`, `0` and `""` all read as false. */
fun JsonObject?.bool(key: String): Boolean = when (val c = this?.get(key).prim()?.content) {
    null, "", "false", "0", "null" -> false
    else -> c == "true" || c.toIntOrNull()?.let { it != 0 } == true
}

fun JsonObject?.objAt(key: String): JsonObject? = this?.get(key).obj()
fun JsonObject?.arrAt(key: String): JsonArray? = this?.get(key).arr()

/** Objects of [key], skipping anything that isn't one. */
fun JsonObject?.objects(key: String): List<JsonObject> =
    arrAt(key)?.mapNotNull { it.obj() } ?: emptyList()

fun JsonElement?.objects(): List<JsonObject> = arr()?.mapNotNull { it.obj() } ?: emptyList()

/** Strings of [key], skipping nulls and non-primitives. */
fun JsonObject?.strings(key: String): List<String> =
    arrAt(key)?.mapNotNull { it.prim()?.content } ?: emptyList()

fun JsonElement?.strings(): List<String> = arr()?.mapNotNull { it.prim()?.content } ?: emptyList()

/** First non-null value among [keys] — for fields the server renamed. */
fun JsonObject?.firstStr(vararg keys: String): String? = keys.firstNotNullOfOrNull { str(it) }

fun JsonObject?.firstLong(vararg keys: String): Long? = keys.firstNotNullOfOrNull { long(it) }

fun JsonObject?.firstDbl(vararg keys: String): Double? = keys.firstNotNullOfOrNull { dbl(it) }
