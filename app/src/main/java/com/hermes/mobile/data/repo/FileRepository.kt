package com.hermes.mobile.data.repo

import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long?,
)

data class RemoteListing(
    val path: String,
    val parent: String?,
    val entries: List<RemoteEntry>,
)

data class RemoteFileContent(
    val name: String,
    val path: String,
    val size: Long,
    val mimeType: String,
    val dataUrl: String,
)

/** PC file browser over /api/files (Phase 4). All errors surface to the UI. */
@Singleton
class FileRepository @Inject constructor(
    private val connectionManager: ConnectionManager,
) {
    private fun base(): String? =
        (connectionManager.state.value as? ConnState.Connected)?.profile?.httpBase

    private suspend fun client() =
        connectionManager.clientFlow.first { it != null }!!

    suspend fun list(path: String?): RemoteListing {
        val base = base() ?: error("Not connected")
        val query = if (path.isNullOrBlank()) "" else "?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
        val json = client().rest.getJson(base, "/api/files$query").jsonObject
        val entries = json["entries"]?.jsonArray?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            RemoteEntry(
                name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                path = o["path"]?.jsonPrimitive?.content ?: o["name"]!!.jsonPrimitive.content,
                isDirectory = o["is_directory"]?.jsonPrimitive?.booleanOrNull ?: false,
                size = o["size"]?.jsonPrimitive?.longOrNull,
            )
        } ?: emptyList()
        return RemoteListing(
            path = json["path"]?.jsonPrimitive?.content ?: (path ?: ""),
            parent = json["parent"]?.jsonPrimitive?.content,
            entries = entries,
        )
    }

    suspend fun read(path: String): RemoteFileContent {
        val base = base() ?: error("Not connected")
        val json = client().rest.getJson(
            base, "/api/files/read?path=${java.net.URLEncoder.encode(path, "UTF-8")}",
        ).jsonObject
        return RemoteFileContent(
            name = json["name"]?.jsonPrimitive?.content ?: "",
            path = json["path"]?.jsonPrimitive?.content ?: path,
            size = json["size"]?.jsonPrimitive?.longOrNull ?: 0,
            mimeType = json["mime_type"]?.jsonPrimitive?.content ?: "application/octet-stream",
            dataUrl = json["data_url"]?.jsonPrimitive?.content ?: "",
        )
    }

    suspend fun downloadBytes(path: String): ByteArray {
        val base = base() ?: error("Not connected")
        val content = read(path)
        val b64 = content.dataUrl.substringAfter("base64,", "")
        return android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    }
}
