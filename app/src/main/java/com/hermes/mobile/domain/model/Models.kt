package com.hermes.mobile.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TaskStatus { PENDING, QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

@Serializable
data class AgentTask(
    val id: Long = 0,
    val createdAtMillis: Long,
    val status: TaskStatus,
    val prompt: String,
    val attachments: List<String> = emptyList(),
    val resultSummary: String? = null,
    val resultArtifacts: List<String> = emptyList(),
    val errorMessage: String? = null,
    val pcProcessedAtMillis: Long? = null,
    val priority: Int = 0,
    val tags: List<String> = emptyList(),
    val notifyOnComplete: Boolean = true
)

@Serializable
data class DriveFileInfo(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedTimeMillis: Long,
    val parentIds: List<String> = emptyList(),
    val thumbnailLink: String? = null,
    val webViewLink: String? = null,
    val isSyncedLocally: Boolean = false,
    val localPath: String? = null
)

@Serializable
data class TransferProgress(
    val id: Long,
    val direction: TransferDirection,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val status: TransferStatus,
    val errorMessage: String? = null
)

enum class TransferDirection { UPLOAD, DOWNLOAD }
enum class TransferStatus { PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

@Serializable
data class ConnectionState(
    val telegramConnected: Boolean = false,
    val driveConnected: Boolean = false,
    val pcOnline: Boolean = false,
    val lastSyncMillis: Long? = null,
    val activeTransfers: Int = 0,
    val pendingTasks: Int = 0
)

@Serializable
data class NotificationItem(
    val id: Long,
    val title: String,
    val body: String,
    val timestampMillis: Long,
    val channel: String,
    val deepLink: String? = null
)
