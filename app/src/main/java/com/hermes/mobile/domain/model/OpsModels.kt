package com.hermes.mobile.domain.model

/**
 * Typed views of the dashboard's ops surfaces.
 *
 * Every one of these is parsed leniently in the repository layer: the server
 * payloads are large and evolve, so a missing field renders as "unknown"
 * rather than failing the whole screen. Only the fields the UI actually draws
 * are modelled.
 */

/** One row of `GET /api/model/options` → `providers[]`. */
data class ProviderModels(
    val slug: String,
    val label: String,
    val models: List<String>,
    val authenticated: Boolean,
    /**
     * Models the server lists but will refuse. `nous` returns all 48 of its
     * models here when the portal has no entitlement for them; offering those
     * as tappable rows is offering 48 guaranteed failures.
     */
    val unavailable: Set<String> = emptySet(),
) {
    fun isUsable(model: String): Boolean = model !in unavailable
}

data class ModelCatalog(
    val providers: List<ProviderModels>,
    val currentModel: String?,
    val currentProvider: String?,
) {
    val isEmpty: Boolean get() = providers.all { it.models.isEmpty() }
}

/** One row of `GET /api/skills` (a bare JSON array). */
data class SkillInfo(
    val name: String,
    val description: String,
    val enabled: Boolean,
    val provenance: String,
    val usage: Int,
)

/** One row of `GET /api/cron/jobs`. */
data class CronJob(
    val id: String,
    val name: String,
    val schedule: String,
    val prompt: String,
    val enabled: Boolean,
    val deliver: String,
    val nextRun: String?,
    val lastRun: String?,
    val profile: String?,
)

/** `GET /api/git/status?path=…`. */
data class GitStatus(
    val branch: String?,
    val defaultBranch: String?,
    val detached: Boolean,
    val ahead: Int,
    val behind: Int,
    val staged: Int,
    val unstaged: Int,
    val untracked: Int,
    val conflicted: Int,
    val changed: Int,
    val added: Int,
    val removed: Int,
    val files: List<GitFile>,
) {
    val clean: Boolean get() = changed == 0
}

data class GitFile(
    val path: String,
    val staged: Boolean,
    val unstaged: Boolean,
    val untracked: Boolean,
    val conflicted: Boolean,
)

/** `GET /api/analytics/usage` — totals + the per-model breakdown. */
data class UsageReport(
    val periodDays: Int,
    val totalInput: Long,
    val totalOutput: Long,
    val totalCacheRead: Long,
    val totalSessions: Long,
    val totalApiCalls: Long,
    val estimatedCostUsd: Double,
    val byModel: List<ModelUsage>,
    val daily: List<DailyUsage>,
) {
    val totalTokens: Long get() = totalInput + totalOutput
}

data class ModelUsage(
    val model: String,
    val input: Long,
    val output: Long,
    val sessions: Long,
    val estimatedCostUsd: Double,
) {
    val total: Long get() = input + output
}

data class DailyUsage(
    val day: String,
    val input: Long,
    val output: Long,
    val estimatedCostUsd: Double,
)

/** A slash command from `commands.catalog`. */
data class SlashCommand(
    val name: String,
    val description: String,
    val category: String,
)

/** Live snapshot of the machine, from `GET /api/system/stats`. */
data class SystemStats(
    val cpuPercent: Double?,
    val memoryPercent: Double?,
    val memoryUsedGb: Double?,
    val memoryTotalGb: Double?,
    val diskPercent: Double?,
)

/** `session.usage` — token accounting for the open session. */
data class SessionUsage(
    val model: String?,
    val calls: Int,
    val input: Long,
    val output: Long,
    val total: Long,
    val contextPercent: Int?,
    val contextUsed: Long?,
    val contextMax: Long?,
    val creditLines: List<String>,
)
