package com.hermes.mobile.domain.repository

import com.hermes.mobile.data.local.AgentTaskDao
import com.hermes.mobile.data.local.AgentTaskEntity
import com.hermes.mobile.data.local.PendingTransferDao
import com.hermes.mobile.data.local.toDomain
import com.hermes.mobile.data.remote.DriveRemoteDataSource
import com.hermes.mobile.data.remote.TelegramRemoteDataSource
import com.hermes.mobile.domain.model.AgentTask
import com.hermes.mobile.domain.model.ConnectionState
import com.hermes.mobile.domain.model.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** An attachment ready to be uploaded alongside a task prompt. */
data class TaskAttachment(
    val fileName: String,
    val bytes: ByteArray,
    val mimeType: String
)

@Singleton
class TaskRepository @Inject constructor(
    private val taskDao: AgentTaskDao,
    private val transferDao: PendingTransferDao,
    private val telegramDs: TelegramRemoteDataSource,
    private val driveDs: DriveRemoteDataSource,
    private val settingsRepo: SettingsRepository
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stringListSerializer = ListSerializer(String.serializer())

    fun observeTasks(): Flow<List<AgentTask>> =
        taskDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeActiveTaskCount(): Flow<Int> = taskDao.observeAll().map { list ->
        list.count { it.status in setOf("PENDING", "QUEUED", "RUNNING") }
    }

    suspend fun submitTask(
        prompt: String,
        attachments: List<TaskAttachment> = emptyList(),
        priority: Int = 0,
        notifyOnComplete: Boolean = true
    ): Long = withContext(Dispatchers.IO) {
        val entity = AgentTaskEntity(
            createdAtMillis = System.currentTimeMillis(),
            status = "PENDING",
            prompt = prompt.trim(),
            attachmentsJson = json.encodeToString(stringListSerializer, attachments.map { it.fileName }),
            resultJson = null,
            priority = priority,
            notifyOnComplete = notifyOnComplete
        )
        val id = taskDao.insert(entity)
        if (id <= 0) throw IOException("Failed to insert task locally")

        runCatching { uploadTaskToDrive(id, prompt.trim(), attachments, priority, notifyOnComplete) }
            .onSuccess {
                taskDao.setStatus(id, "QUEUED", ts = System.currentTimeMillis())
            }
            .onFailure {
                taskDao.setStatus(id, "PENDING", err = it.message, ts = System.currentTimeMillis())
            }

        runCatching { notifyTaskQueued(id, prompt.trim()) }

        id
    }

    suspend fun uploadPendingTasksToDrive(): Int = withContext(Dispatchers.IO) {
        if (!driveDs.isSignedIn) return@withContext 0
        val pending = taskDao.getPending()
        var uploaded = 0
        for (task in pending) {
            runCatching {
                uploadTaskToDrive(
                    id = task.id,
                    prompt = task.prompt,
                    attachments = emptyList(),
                    priority = task.priority,
                    notifyOnComplete = task.notifyOnComplete
                )
            }.onSuccess {
                taskDao.setStatus(task.id, "QUEUED", ts = System.currentTimeMillis())
                uploaded++
            }.onFailure {
                taskDao.setStatus(task.id, "PENDING", err = it.message, ts = System.currentTimeMillis())
            }
        }
        uploaded
    }

    private suspend fun uploadTaskToDrive(
        id: Long,
        prompt: String,
        attachments: List<TaskAttachment>,
        priority: Int,
        notifyOnComplete: Boolean
    ) {
        driveDs.uploadText(
            DriveRemoteDataSource.HERMES_INBOX_FOLDER,
            "task_${id}_prompt.txt",
            prompt
        )
        val attachmentNames = attachments.mapIndexed { index, att ->
            val safeName = "task_${id}_attachment_${index + 1}_${att.fileName}"
            driveDs.uploadToInbox(safeName, att.bytes, att.mimeType)
            safeName
        }
        val metaJson = buildJsonObject {
            put("id", id)
            put("prompt", prompt)
            put("attachments", json.encodeToJsonElement(stringListSerializer, attachmentNames))
            put("priority", priority)
            put("notify_on_complete", notifyOnComplete)
            put("created_at", java.time.Instant.now().toString())
            put("source", "hermes-mobile")
        }
        driveDs.uploadText(
            DriveRemoteDataSource.HERMES_INBOX_FOLDER,
            "task_${id}_meta.json",
            json.encodeToString(metaJson),
            "application/json"
        )
    }

    private suspend fun sendAllowedTelegram(chatId: String, text: String) {
        val userId = chatId.trim().toLongOrNull()
            ?: throw IllegalArgumentException("Invalid Telegram chat ID: $chatId")
        settingsRepo.requireAllowedUser(userId)
        telegramDs.sendMessage(chatId = chatId, text = text)
    }

    private suspend fun notifyTaskQueued(id: Long, prompt: String) {
        val chatId = settingsRepo.getAllowedUserIds().split(",")
            .firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        runCatching {
            sendAllowedTelegram(
                chatId,
                "📨 <b>New Task #$id</b>\n${prompt.take(300)}\n\n<i>Queued in Drive → HermesInbox</i>"
            )
        }
    }

    suspend fun processOutboxResults(): List<AgentTask> = withContext(Dispatchers.IO) {
        if (!driveDs.isSignedIn) return@withContext emptyList()
        val completed = mutableListOf<AgentTask>()
        val outbox = driveDs.listOutbox()
        val metaFiles = outbox.filter {
            it.name.startsWith("task_") && it.name.endsWith("_meta.json")
        }
        for (metaFile in metaFiles) {
            runCatching {
                val meta = json.parseToJsonElement(driveDs.downloadText(metaFile.id)) as JsonObject
                val taskId = meta["id"]?.jsonPrimitive?.longOrNull ?: return@runCatching
                val status = meta["status"]?.jsonPrimitive?.content ?: return@runCatching
                val local = taskDao.getById(taskId) ?: return@runCatching
                if (local.status == "COMPLETED" || local.status == "FAILED") return@runCatching

                val summary = meta["result_summary"]?.jsonPrimitive?.content
                    ?: meta["error"]?.jsonPrimitive?.content
                    ?: "Task finished"
                val artifacts = meta["artifacts"]?.jsonArray
                    ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                val now = System.currentTimeMillis()

                when {
                    status.equals("COMPLETED", ignoreCase = true) ->
                        taskDao.setCompleted(taskId, summary, json.encodeToString(stringListSerializer, artifacts), now)
                    status.equals("FAILED", ignoreCase = true) ->
                        taskDao.setStatus(taskId, "FAILED", err = summary, ts = now)
                    else -> return@runCatching
                }
                settingsRepo.recordPcSeen(now)
                taskDao.getById(taskId)?.toDomain()?.let { completed.add(it) }
            }
        }
        completed
    }

    suspend fun getNextPendingTask(): AgentTask? = withContext(Dispatchers.IO) {
        taskDao.getNextPending()?.toDomain()
    }

    suspend fun markTaskRunning(id: Long) = withContext(Dispatchers.IO) {
        taskDao.setStatus(id, "RUNNING", ts = System.currentTimeMillis())
    }

    suspend fun markTaskCompleted(id: Long, resultSummary: String, artifacts: List<String> = emptyList()) =
        withContext(Dispatchers.IO) {
            taskDao.setCompleted(
                id,
                resultSummary,
                json.encodeToString(stringListSerializer, artifacts),
                System.currentTimeMillis()
            )
        }

    suspend fun markTaskFailed(id: Long, error: String) = withContext(Dispatchers.IO) {
        taskDao.setStatus(id, "FAILED", err = error, ts = System.currentTimeMillis())
    }

    suspend fun cancelPendingTasks() = withContext(Dispatchers.IO) {
        taskDao.cancelAllPending()
    }

    suspend fun notifyTaskCompleted(task: AgentTask) {
        runCatching {
            val chatId = settingsRepo.getAllowedUserIds().split(",")
                .firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val emoji = if (task.status == TaskStatus.COMPLETED) "✅" else "❌"
            sendAllowedTelegram(
                chatId,
                "$emoji <b>Task #${task.id} ${task.status.name.lowercase()}</b>\n${task.resultSummary?.take(300) ?: ""}"
            )
        }
    }

    fun observeConnectionState(): Flow<ConnectionState> =
        combine(
            settingsRepo.telegramHasToken(),
            settingsRepo.driveHasAccount(),
            settingsRepo.pcLastSeen(),
            taskDao.observeAll(),
            transferDao.observeAll()
        ) { tgOk, driveOk, pcSeen, tasks, transfers ->
            ConnectionState(
                telegramConnected = tgOk,
                driveConnected = driveOk,
                pcOnline = pcSeen?.let { System.currentTimeMillis() - it < PC_ONLINE_WINDOW_MS } ?: false,
                pendingTasks = tasks.count { it.status in setOf("PENDING", "QUEUED", "RUNNING") },
                activeTransfers = transfers.count { it.status == "RUNNING" },
                lastSyncMillis = System.currentTimeMillis()
            )
        }

    companion object {
        private const val PC_ONLINE_WINDOW_MS = 20 * 60 * 1000L // 20 min > 15 min sync cadence
    }
}
