package com.hermes.mobile.data.local

import com.hermes.mobile.data.remote.dto.DriveFile
import com.hermes.mobile.domain.model.DriveFileInfo

fun DriveFileEntity.toDomain() = DriveFileInfo(
    id = id,
    name = name,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    modifiedTimeMillis = modifiedTimeMillis,
    parentIds = emptyList(),
    thumbnailLink = thumbnailLink,
    webViewLink = webViewLink,
    isSyncedLocally = isSyncedLocally,
    localPath = localPath
)

fun DriveFileInfo.toEntity() = DriveFileEntity(
    id = id,
    name = name,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    modifiedTimeMillis = modifiedTimeMillis,
    parentsJson = "",
    thumbnailLink = thumbnailLink,
    webViewLink = webViewLink,
    isSyncedLocally = isSyncedLocally,
    localPath = localPath
)

/** RFC-3339 timestamp from the Drive API → epoch millis (best-effort). */
private fun parseDriveTime(raw: String?): Long =
    raw?.let {
        runCatching {
            java.time.Instant.parse(it).toEpochMilli()
        }.getOrNull()
    } ?: System.currentTimeMillis()

/** Drive REST DTO → local cache entity */
fun DriveFile.toEntity() = DriveFileEntity(
    id = id,
    name = name,
    mimeType = mimeType,
    sizeBytes = size?.toLongOrNull() ?: 0L,
    modifiedTimeMillis = parseDriveTime(modifiedTime),
    parentsJson = parents?.joinToString(",") ?: "",
    thumbnailLink = thumbnailLink,
    webViewLink = webViewLink,
    isSyncedLocally = false,
    localPath = null
)

/** Drive REST DTO → domain model */
fun DriveFile.toDomain() = toEntity().toDomain()
