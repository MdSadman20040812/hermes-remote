package com.hermes.mobile.data.repo

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Base64
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** Where an incoming file landed on the PC, and what to say about it. */
data class UploadResult(
    val remotePath: String,
    val name: String,
    val size: Long,
)

/** Where an outgoing file landed on the phone. */
data class SaveResult(
    val name: String,
    val localPath: String,
    val size: Long,
)

/** A PC file mirrored into app cache so a renderer can point at it. */
data class CachedFile(
    val uri: String,
    val size: Long,
)

/**
 * File movement in both directions.
 *
 * Wire format is the server's, verified live before this was written:
 *  - up   `POST /api/files/upload` {path, data_url, overwrite}
 *  - down `GET  /api/files/download?path=` → raw bytes + Content-Disposition
 *
 * The size ceiling is deliberate and low-ish. `data_url` means base64 in a JSON
 * body: the whole file exists in memory at least twice on the phone, again in
 * the request buffer, and again server-side while decoding. A 200 MB video
 * would OOM the app rather than fail cleanly, so it is refused up front with a
 * reason instead of dying halfway.
 */
@Singleton
class TransferRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: ConnectionManager,
) {
    private fun base(): String? =
        (connectionManager.state.value as? ConnState.Connected)?.profile?.httpBase

    private suspend fun client() = connectionManager.clientFlow.first { it != null }!!

    // ------------------------------------------------------------ phone → PC

    /**
     * Send a file the user picked or shared into the PC's inbox directory.
     *
     * [remoteDir] defaults to the inbox rather than the session's working
     * directory: a phone-shared file dropped straight into a repo is a file
     * that silently joins the next commit.
     */
    suspend fun uploadToPc(
        uri: Uri,
        remoteDir: String = DEFAULT_INBOX,
        overwrite: Boolean = false,
        onProgress: (name: String, sent: Long, total: Long) -> Unit = { _, _, _ -> },
    ): UploadResult = withContext(Dispatchers.IO) {
        val base = base() ?: error("Not connected to your PC")
        val resolver = context.contentResolver
        val name = displayName(resolver, uri)
        val size = sizeOf(resolver, uri)

        if (size != null && size > MAX_BYTES) {
            error(
                "$name is ${humanSize(size)}. The limit is ${humanSize(MAX_BYTES)} — " +
                    "files travel base64-encoded in one request, and larger ones " +
                    "would run the phone out of memory mid-transfer.",
            )
        }

        onProgress(name, 0L, size ?: 0L)
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Couldn't read $name from this app")
        // Reading is the only phase with a real byte count to report; the POST
        // itself is one request, so claiming smooth progress across it would be
        // invented. Report the read, then hold at "sending".
        onProgress(name, bytes.size.toLong(), bytes.size.toLong())
        if (bytes.size > MAX_BYTES) {
            error("$name is ${humanSize(bytes.size.toLong())}, over the ${humanSize(MAX_BYTES)} limit.")
        }

        val mime = resolver.getType(uri) ?: "application/octet-stream"
        // NO_WRAP: Base64 with newlines is invalid inside a JSON string value.
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val remotePath = "${remoteDir.trimEnd('/', '\\')}/$name"

        val body = buildJsonObject {
            put("path", remotePath)
            put("data_url", "data:$mime;base64,$b64")
            put("overwrite", overwrite)
        }
        val response = client().rest.postJson(base, "/api/files/upload", body).jsonObject
        val entry = response["entry"]?.jsonObject
        UploadResult(
            remotePath = entry?.get("path")?.jsonPrimitive?.content
                ?: response["path"]?.jsonPrimitive?.content ?: remotePath,
            name = entry?.get("name")?.jsonPrimitive?.content ?: name,
            size = bytes.size.toLong(),
        )
    }

    // ------------------------------------------------------------ PC → phone

    /**
     * Pull a file off the PC into the phone's Downloads folder.
     *
     * Downloads is the one directory every Android file manager, gallery and
     * share sheet already looks in, and writing there needs no permission on
     * any supported API level. A name collision gets a numeric suffix rather
     * than clobbering something the user already had.
     */
    suspend fun saveToPhone(
        remotePath: String,
        onProgress: (name: String, got: Long, total: Long) -> Unit = { _, _, _ -> },
    ): SaveResult = withContext(Dispatchers.IO) {
        val base = base() ?: error("Not connected to your PC")
        val encoded = URLEncoder.encode(remotePath, "UTF-8")
        val name = remotePath.substringAfterLast('/').substringAfterLast('\\')
        onProgress(name, 0L, 0L)
        val bytes = client().rest.getBytes(base, "/api/files/download?path=$encoded")
        onProgress(name, bytes.size.toLong(), bytes.size.toLong())

        val downloads = Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists()) downloads.mkdirs()

        val target = uniqueFile(downloads, remotePath.substringAfterLast('/').substringAfterLast('\\'))
        target.writeBytes(bytes)
        SaveResult(target.name, target.absolutePath, bytes.size.toLong())
    }

    /**
     * Mirror a PC file into app cache so a Compose renderer can point at it.
     *
     * An image the agent produced lives only on the desktop, and Coil cannot
     * load `D:/out/chart.png` — without this, every file the PC sends is an
     * empty box with a filename under it. Cached per remote path so scrolling
     * past the same image twice costs one fetch.
     *
     * Returns a `file://` URI; the caller stores it on the attachment.
     */
    suspend fun cacheRemote(remotePath: String, displayName: String): CachedFile =
        withContext(Dispatchers.IO) {
            val base = base() ?: error("Not connected to your PC")
            val dir = File(context.cacheDir, "remote").apply { mkdirs() }
            // Hash the remote path so two files with the same basename in
            // different directories do not collide in the cache.
            val stamp = Integer.toHexString(remotePath.hashCode())
            val safe = sanitize(displayName)
            val target = File(dir, "$stamp-$safe")
            if (target.exists() && target.length() > 0) {
                return@withContext CachedFile(Uri.fromFile(target).toString(), target.length())
            }
            val encoded = URLEncoder.encode(remotePath, "UTF-8")
            val bytes = client().rest.getBytes(base, "/api/files/download?path=$encoded")
            // Write to a sibling .part and rename, so an interrupted fetch
            // never leaves something that looks complete in the cache.
            val part = File(dir, "$stamp-$safe.part")
            part.writeBytes(bytes)
            if (target.exists()) target.delete()
            part.renameTo(target)
            CachedFile(Uri.fromFile(target).toString(), bytes.size.toLong())
        }

    /** `report.pdf` → `report (2).pdf` when the first is taken. */
    private fun uniqueFile(dir: File, rawName: String): File {
        val name = rawName.ifBlank { "hermes-file" }
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        val dot = if (ext.isBlank()) "" else ".$ext"
        var n = 2
        while (n < 1000) {
            val next = File(dir, "$stem ($n)$dot")
            if (!next.exists()) return next
            n++
        }
        return File(dir, "$stem-${System.currentTimeMillis()}$dot")
    }

    // ---------------------------------------------------------------- helpers

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return sanitize(it) }
            }
        }
        return sanitize(uri.lastPathSegment?.substringAfterLast('/') ?: "shared-file")
    }

    private fun sizeOf(resolver: ContentResolver, uri: Uri): Long? {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
        }
        return null
    }

    /**
     * Strip anything that could make the server write outside [DEFAULT_INBOX].
     * The name comes from another app's ContentProvider, so it is untrusted
     * input: `../../` in a display name would otherwise become a path traversal
     * on the PC.
     */
    private fun sanitize(name: String): String =
        name.replace('\\', '/')
            .substringAfterLast('/')
            .replace(Regex("""[<>:"|?*\u0000-\u001F]"""), "_")
            .trim()
            .ifBlank { "shared-file" }
            .take(120)

    companion object {
        /** Files from the phone land here, not in a repo the agent may commit. */
        const val DEFAULT_INBOX = "D:/HermesInbox"

        /** Base64-in-JSON means several copies in RAM; refuse rather than OOM. */
        const val MAX_BYTES = 48L * 1024 * 1024

        fun humanSize(bytes: Long): String = when {
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
