package com.hermes.mobile.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Single agent task sent from the phone to Hermes PC */
@Entity(tableName = "agent_tasks")
data class AgentTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val createdAtMillis: Long,
    val status: String,         // PENDING | QUEUED | RUNNING | COMPLETED | FAILED | CANCELLED
    val prompt: String,         // What the user asked Hermes to do
    val attachmentsJson: String,// JSON array of Drive file IDs
    val resultJson: String?,    // Hermes response summary
    val resultArtifactsJson: String? = null, // Drive file IDs produced by the task
    val errorMessage: String? = null,
    val pcProcessedAtMillis: Long? = null,
    val priority: Int = 0,
    val tagsJson: String = "[]",  // ["research","coding","image"]
    val notifyOnComplete: Boolean = true
)

/** One file record — mirrors Drive metadata locally for fast browsing */
@Entity(tableName = "drive_files")
data class DriveFileEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedTimeMillis: Long,
    val parentsJson: String,   // ["folderId1", ...]
    val thumbnailLink: String?,
    val webViewLink: String?,
    val isSyncedLocally: Boolean = false,
    val localPath: String? = null
)

@Entity(tableName = "pending_transfers")
data class PendingTransferEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val createdAtMillis: Long,
    val direction: String,     // UPLOAD | DOWNLOAD
    val localUri: String?,
    val driveFileId: String?,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val status: String,        // PENDING | RUNNING | PAUSED | COMPLETED | FAILED | CANCELLED
    val errorMessage: String? = null
)

@Entity(tableName = "cached_messages")
data class CachedMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val telegramMessageId: Long,
    val chatId: Long,
    val senderId: Long,
    val text: String,
    val timestampMillis: Long,
    val isFromHermes: Boolean
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey
    val key: String,
    val value: String,
    val updatedAtMillis: Long
)
