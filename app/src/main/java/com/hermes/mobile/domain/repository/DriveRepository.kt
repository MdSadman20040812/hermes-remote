package com.hermes.mobile.domain.repository

import android.content.Context
import com.hermes.mobile.data.local.DriveFileDao
import com.hermes.mobile.data.local.toDomain
import com.hermes.mobile.data.local.toEntity
import com.hermes.mobile.data.remote.DriveRemoteDataSource
import com.hermes.mobile.domain.model.DriveFileInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val driveFileDao: DriveFileDao,
    private val driveDs: DriveRemoteDataSource,
    private val settingsRepo: SettingsRepository
) {
    fun observeFiles(): Flow<List<DriveFileInfo>> =
        driveFileDao.observeAll().map { list -> list.map { it.toDomain() } }

    val isDriveSignedIn: Boolean
        get() = driveDs.isSignedIn

    suspend fun refreshDriveIndex() = withContext(Dispatchers.IO) {
        try {
            val shared = driveDs.listShared()
            val outbox = driveDs.listOutbox()
            (shared + outbox).forEach { driveFileDao.upsert(it.toEntity()) }
        } catch (_: Exception) { /* best-effort; UI shows stale data */ }
    }

    /** Uploads user-picked content to HermesShared (visible to phone + PC). */
    suspend fun uploadFile(fileName: String, bytes: ByteArray, mimeType: String): DriveFileInfo =
        withContext(Dispatchers.IO) {
            val uploaded = driveDs.uploadToShared(fileName, bytes, mimeType)
            driveFileDao.upsert(uploaded.toEntity())
            uploaded.toDomain()
        }

    suspend fun downloadFile(fileId: String): ByteArray = withContext(Dispatchers.IO) {
        driveDs.downloadFile(fileId)
    }

    /** Downloads a Drive file into the app cache and returns the local file. */
    suspend fun downloadToCache(file: DriveFileInfo): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "hermes_downloads").apply { mkdirs() }
        val safeName = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(dir, "${file.id}_$safeName")
        if (!target.exists()) {
            target.writeBytes(driveDs.downloadFile(file.id))
        }
        driveFileDao.setSyncState(file.id, synced = true, path = target.absolutePath)
        target
    }

    suspend fun clearLocalCache() = withContext(Dispatchers.IO) {
        File(appContext.cacheDir, "hermes_downloads").deleteRecursively()
        driveFileDao.deleteUnsynced()
    }

    suspend fun getSyncedCount(): Int = withContext(Dispatchers.IO) {
        driveFileDao.syncedCount()
    }
}
