package com.hermes.mobile.data.remote

import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.hermes.mobile.data.remote.dto.DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin coroutine wrapper around the Google Drive REST client.
 *
 * Drive is used as the persistent shared-data layer between phone and PC:
 *  • Phone uploads task prompts + attachments → Drive "HermesInbox" folder
 *  • PC watches that folder, runs tasks, writes results → Drive "HermesOutbox"
 *  • App syncs Drive → local DB for instant browsing
 *
 * No third-party server required. Google Drive IS the transport.
 */
@Singleton
class DriveRemoteDataSource @Inject constructor(
    private val credential: GoogleAccountCredential
) {
    companion object {
        const val HERMES_ROOT_FOLDER = "HermesMobile"
        const val HERMES_INBOX_FOLDER = "HermesInbox"
        const val HERMES_OUTBOX_FOLDER = "HermesOutbox"
        const val HERMES_SHARED_FOLDER = "HermesShared"
        const val GDRIVE_MIME_FOLDER = "application/vnd.google-apps.folder"
        private const val FILE_FIELDS =
            "id,name,mimeType,size,modifiedTime,parents,thumbnailLink,webViewLink"
        private const val MAX_RETRIES = 3
    }

    val isSignedIn: Boolean
        get() = credential.selectedAccountName != null

    fun setSignedInAccount(accountName: String?) {
        credential.selectedAccountName = accountName
    }

    private fun drive(): Drive {
        credential.selectedAccountName ?: throw IllegalStateException("Drive not signed in")
        val transport = NetHttpTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()
        return Drive.Builder(transport, jsonFactory, credential)
            .setApplicationName("HermesMobile")
            .build()
    }

    private suspend fun <T> retry(
        times: Int = MAX_RETRIES,
        initialDelayMs: Long = 500,
        block: suspend () -> T
    ): T {
        var last: Throwable? = null
        var delayMs = initialDelayMs
        repeat(times - 1) { attempt ->
            runCatching { return block() }.onFailure { last = it; delayMs *= 2 }
            kotlinx.coroutines.delay(delayMs)
        }
        runCatching { return block() }.onFailure { throw it }
        throw last ?: IllegalStateException("Drive operation failed")
    }

    /** Finds (or creates) a named subfolder inside the Hermes root */
    suspend fun ensureHermesFolder(name: String): String = withContext(Dispatchers.IO) {
        retry {
            val d = drive()
            val rootId = findOrCreateRootFolder()
            val q = "name='$name' and mimeType='$GDRIVE_MIME_FOLDER' and '$rootId' in parents and trashed=false"
            val existing = d.files().list().setQ(q).setFields("files(id,name)").execute()
            existing.files.firstOrNull()?.id ?: run {
                val meta = File().apply {
                    this.name = name
                    mimeType = GDRIVE_MIME_FOLDER
                    parents = listOf(rootId)
                }
                d.files().create(meta).setFields("id").execute().id
            }
        }
    }

    /** Uploads bytes to the Hermes Inbox (phone → PC channel) */
    suspend fun uploadToInbox(
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
        description: String? = null
    ): DriveFile = uploadToFolder(HERMES_INBOX_FOLDER, fileName, bytes, mimeType, description)

    /** Uploads bytes to the Hermes Shared folder (bidirectional storage) */
    suspend fun uploadToShared(
        fileName: String,
        bytes: ByteArray,
        mimeType: String
    ): DriveFile = uploadToFolder(HERMES_SHARED_FOLDER, fileName, bytes, mimeType, null)

    /** Uploads UTF-8 text (prompt / meta JSON) to a named Hermes folder */
    suspend fun uploadText(
        folderName: String,
        fileName: String,
        text: String,
        mimeType: String = "text/plain"
    ): DriveFile = uploadToFolder(folderName, fileName, text.toByteArray(Charsets.UTF_8), mimeType, null)

    private suspend fun uploadToFolder(
        folderName: String,
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
        description: String?
    ): DriveFile = withContext(Dispatchers.IO) {
        retry {
            val d = drive()
            val folderId = ensureHermesFolder(folderName)

            val meta = File().apply {
                this.name = fileName
                this.mimeType = mimeType
                parents = listOf(folderId)
                description?.let { this.description = it }
            }
            val content = ByteArrayContent(mimeType, bytes)
            val uploaded = d.files().create(meta, content)
                .setFields(FILE_FIELDS)
                .execute()

            toDriveFile(uploaded)
        }
    }

    /** Lists recent files in the Hermes Inbox (phone writes here) */
    suspend fun listInbox(maxResults: Int = 50): List<DriveFile> =
        listFolder(HERMES_INBOX_FOLDER, maxResults)

    /** Lists recent files in the Hermes Outbox (PC writes here) */
    suspend fun listOutbox(maxResults: Int = 50): List<DriveFile> =
        listFolder(HERMES_OUTBOX_FOLDER, maxResults)

    /** Lists all files under the Hermes Shared folder */
    suspend fun listShared(maxResults: Int = 100): List<DriveFile> =
        listFolder(HERMES_SHARED_FOLDER, maxResults)

    private suspend fun listFolder(folderName: String, maxResults: Int): List<DriveFile> =
        retry {
            val d = drive()
            val folderId = ensureHermesFolder(folderName)
            val list = d.files().list()
                .setQ("'$folderId' in parents and trashed=false")
                .setOrderBy("modifiedTime desc")
                .setPageSize(maxResults)
                .setFields("files($FILE_FIELDS)")
                .execute()
            (list.files ?: emptyList()).map { toDriveFile(it) }
        }

    /** Downloads a file by Drive ID */
    suspend fun downloadFile(fileId: String): ByteArray = retry {
        val out = ByteArrayOutputStream()
        drive().files().get(fileId).executeMediaAndDownloadTo(out)
        out.toByteArray()
    }

    /** Downloads UTF-8 text content of a file */
    suspend fun downloadText(fileId: String): String =
        downloadFile(fileId).toString(Charsets.UTF_8)

    /** Deletes a file from Drive (moves to trash) */
    suspend fun deleteFile(fileId: String) = retry {
        drive().files().delete(fileId).execute()
    }

    private fun findOrCreateRootFolder(): String = retry {
        val d = drive()
        val q = "name='$HERMES_ROOT_FOLDER' and mimeType='$GDRIVE_MIME_FOLDER' and trashed=false"
        val list = d.files().list().setQ(q).setFields("files(id)").execute()
        list.files.firstOrNull()?.id ?: run {
            val meta = File().apply {
                name = HERMES_ROOT_FOLDER
                mimeType = GDRIVE_MIME_FOLDER
            }
            d.files().create(meta).setFields("id").execute().id
        }
    }

    private fun toDriveFile(f: File): DriveFile = DriveFile(
        id = f.id,
        name = f.name ?: "untitled",
        mimeType = f.mimeType ?: "application/octet-stream",
        size = f.size?.toString(),
        modifiedTime = f.modifiedTime?.toString(),
        parents = f.parents,
        thumbnailLink = f.thumbnailLink,
        webViewLink = f.webViewLink
    )
}
