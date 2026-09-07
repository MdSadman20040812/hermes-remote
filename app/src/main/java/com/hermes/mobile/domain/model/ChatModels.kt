package com.hermes.mobile.domain.model

import kotlinx.serialization.Serializable

/** One row in session.list (Phase 0 literal shape). */
@Serializable
data class SessionSummary(
    val id: String,
    val title: String = "",
    val preview: String = "",
    val startedAt: Double = 0.0,
    val messageCount: Int = 0,
    val source: String = "",
)

/** A rendered unit of the cockpit transcript. */
sealed interface TranscriptItem {
    val key: String

    data class UserMessage(override val key: String, val text: String) : TranscriptItem

    data class AssistantMessage(
        override val key: String,
        val text: String,
        val streaming: Boolean = false,
        val usageContextPercent: Int? = null,
    ) : TranscriptItem

    data class ThinkingBlock(
        override val key: String,
        val text: String,
        val live: Boolean = false,
    ) : TranscriptItem

    data class ToolCallItem(
        override val key: String,
        val toolId: String,
        val name: String,
        val context: String?,
        val args: String?,
        val result: String?,
        val durationS: Double?,
        val running: Boolean,
        val approvalNote: String? = null,
    ) : TranscriptItem

    data class ApprovalCard(
        override val key: String,
        val sessionId: String,
        val command: String?,
        val description: String?,
        val choices: List<String>,
        /** Server allows "…and don't ask again this session". */
        val allowSession: Boolean = false,
        /** Server allows "…and remember permanently". */
        val allowPermanent: Boolean = false,
        val resolved: String? = null, // the choice made, once answered
    ) : TranscriptItem

    /** Output of a slash command run from the composer. */
    data class CommandOutput(
        override val key: String,
        val command: String,
        val output: String,
    ) : TranscriptItem

    data class StatusLine(override val key: String, val text: String) : TranscriptItem
}

/** Whether the agent is mid-turn on the active session. */
enum class TurnPhase { IDLE, RUNNING }
