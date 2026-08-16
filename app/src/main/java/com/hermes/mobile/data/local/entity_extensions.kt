package com.hermes.mobile.data.local

import com.hermes.mobile.domain.model.AgentTask
import com.hermes.mobile.domain.model.TaskStatus
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

private fun parseStringList(raw: String?): List<String> =
    if (raw.isNullOrBlank()) emptyList()
    else runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

fun AgentTaskEntity.toDomain() = AgentTask(
    id = id,
    createdAtMillis = createdAtMillis,
    status = runCatching { TaskStatus.valueOf(status) }.getOrDefault(TaskStatus.PENDING),
    prompt = prompt,
    attachments = parseStringList(attachmentsJson),
    resultSummary = resultJson,
    resultArtifacts = parseStringList(resultArtifactsJson),
    errorMessage = errorMessage,
    pcProcessedAtMillis = pcProcessedAtMillis,
    priority = priority,
    tags = parseStringList(tagsJson),
    notifyOnComplete = notifyOnComplete
)
