package com.hermes.mobile.domain.repository

import com.hermes.mobile.data.local.CachedMessageDao
import com.hermes.mobile.data.local.CachedMessageEntity
import com.hermes.mobile.domain.model.AgentTask
import com.hermes.mobile.domain.model.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class HermesMessage(
    val id: Long,
    val sender: String,
    val text: String,
    val timestampMillis: Long,
    val isFromHermes: Boolean
)

/**
 * Message feed shown in the Messages tab. Hermes PC never talks to the phone
 * directly — task results arriving in the Drive outbox become feed entries here.
 */
@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: CachedMessageDao
) {
    fun observeMessages(limit: Int = 200): Flow<List<HermesMessage>> =
        messageDao.observeRecent(limit).map { list ->
            list.map {
                HermesMessage(
                    id = it.id,
                    sender = if (it.isFromHermes) "Hermes" else "PC Hub",
                    text = it.text,
                    timestampMillis = it.timestampMillis,
                    isFromHermes = it.isFromHermes
                )
            }
        }

    suspend fun recordTaskResult(task: AgentTask) = withContext(Dispatchers.IO) {
        val done = task.status == TaskStatus.COMPLETED
        messageDao.insert(
            CachedMessageEntity(
                telegramMessageId = task.id,
                chatId = 0,
                senderId = 0,
                text = buildString {
                    append("Task #${task.id} ${if (done) "completed" else "failed"}")
                    task.resultSummary?.let { append(": ${it.take(200)}") }
                },
                timestampMillis = System.currentTimeMillis(),
                isFromHermes = true
            )
        )
    }

    suspend fun pruneOlderThan(cutoffMillis: Long) = withContext(Dispatchers.IO) {
        messageDao.prune(cutoffMillis)
    }
}
