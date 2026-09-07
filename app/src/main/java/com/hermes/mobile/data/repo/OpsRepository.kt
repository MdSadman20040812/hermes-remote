package com.hermes.mobile.data.repo

import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.core.transport.HermesClient
import com.hermes.mobile.core.transport.q
import com.hermes.mobile.core.transport.rpcParamsOf
import com.hermes.mobile.domain.model.CronJob
import com.hermes.mobile.domain.model.DailyUsage
import com.hermes.mobile.domain.model.GitFile
import com.hermes.mobile.domain.model.GitStatus
import com.hermes.mobile.domain.model.ModelCatalog
import com.hermes.mobile.domain.model.ModelUsage
import com.hermes.mobile.domain.model.ProviderModels
import com.hermes.mobile.domain.model.SkillInfo
import com.hermes.mobile.domain.model.SystemStats
import com.hermes.mobile.domain.model.UsageReport
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ops surface: system stats, gateway control, logs, usage, models, skills,
 * cron and git.
 *
 * Reading is lenient — the server payloads are large and evolve, so we render
 * the fields we know and keep going. Writing is not: POST /api/model/set
 * rejects a body without "scope", and /api/skills/toggle is a PUT, so those
 * request shapes are spelled out exactly.
 */
@Singleton
class OpsRepository @Inject constructor(
    private val connectionManager: ConnectionManager,
) {
    private fun base(): String =
        (connectionManager.state.value as? ConnState.Connected)?.profile?.httpBase
            ?: error("Not connected")

    private suspend fun client(): HermesClient = connectionManager.clientFlow.first { it != null }!!

    // ------------------------------------------------------------- machine

    suspend fun systemStats(): SystemStats {
        val o = client().rest.getJson(base(), "/api/system/stats").obj()
        val mem = o.objAt("memory")
        return SystemStats(
            cpuPercent = o.firstDbl("cpu_percent", "cpu") ?: o.objAt("cpu").dbl("percent"),
            memoryPercent = o.firstDbl("memory_percent", "ram") ?: mem.dbl("percent"),
            memoryUsedGb = mem.firstDbl("used_gb", "used"),
            memoryTotalGb = mem.firstDbl("total_gb", "total"),
            diskPercent = o.dbl("disk_percent") ?: o.objAt("disk").dbl("percent"),
        )
    }

    suspend fun processList(): JsonElement? = client().rpc.call("process.list")

    suspend fun processKill(pid: Int): JsonElement? =
        client().rpc.call("process.kill", rpcParamsOf("pid" to pid))

    /** [action] is one of start / stop / restart / drain. */
    suspend fun gatewayAction(action: String): JsonElement =
        client().rest.postJson(base(), "/api/gateway/" + action, JsonObject(emptyMap()))

    suspend fun logsTail(file: String = "agent", lines: Int = 200): List<String> {
        val o = client().rest.getJson(base(), "/api/logs?file=" + q(file) + "&lines=" + lines).obj()
        // Rows are plain strings or {message,...} objects depending on filtering.
        return o.arrAt("lines")?.map { el ->
            el.obj()?.let { row -> row.firstStr("message", "line", "text") ?: row.toString() }
                ?: el.toString().trim('"')
        } ?: emptyList()
    }

    // -------------------------------------------------------------- models

    /**
     * GET /api/model/options answers {providers:[{slug,models:[...]}], model,
     * provider}. The previous reader looked for "options"/"models" at the top
     * level, so the picker was always empty.
     */
    suspend fun modelCatalog(): ModelCatalog {
        val o = client().rest.getJson(base(), "/api/model/options").obj()
        val providers = o.objects("providers").map { p ->
            ProviderModels(
                slug = p.firstStr("slug", "id", "name").orEmpty(),
                label = p.firstStr("label", "display_name", "name", "slug").orEmpty(),
                models = p.strings("models"),
                authenticated = p.bool("authenticated") || p.bool("is_authenticated"),
            )
        }.filter { it.slug.isNotBlank() }
        return ModelCatalog(
            providers = providers,
            currentModel = o.firstStr("model", "current_model", "current"),
            currentProvider = o.firstStr("provider", "current_provider"),
        )
    }

    /**
     * "scope" is required by the server and was previously omitted, so every
     * model switch came back 400. The expensive-model gate is surfaced rather
     * than swallowed: the server answers ok=false with confirm_required=true
     * and a message the user has to accept before the switch applies.
     */
    suspend fun setModel(
        model: String,
        provider: String,
        confirmExpensive: Boolean = false,
    ): ModelSetResult {
        val body = buildJsonObject {
            put("scope", "main")
            put("provider", provider)
            put("model", model)
            put("confirm_expensive_model", confirmExpensive)
        }
        val o = client().rest.postJson(base(), "/api/model/set", body).obj()
        return ModelSetResult(
            ok = o.bool("ok"),
            confirmRequired = o.bool("confirm_required"),
            message = o.firstStr("confirm_message", "message", "detail"),
        )
    }

    // -------------------------------------------------------------- skills

    suspend fun skills(): List<SkillInfo> =
        client().rest.getJson(base(), "/api/skills").objects().map { s ->
            SkillInfo(
                name = s.str("name").orEmpty(),
                description = s.firstStr("description", "summary").orEmpty(),
                enabled = s.bool("enabled"),
                provenance = s.str("provenance").orEmpty(),
                usage = s.int("usage") ?: 0,
            )
        }.filter { it.name.isNotBlank() }

    /** PUT, not POST — a POST here is a 405. */
    suspend fun toggleSkill(name: String, enabled: Boolean) {
        client().rest.putJson(
            base(), "/api/skills/toggle",
            buildJsonObject {
                put("name", name)
                put("enabled", enabled)
            },
        )
    }

    // ---------------------------------------------------------------- cron

    suspend fun cronJobs(): List<CronJob> =
        client().rest.getJson(base(), "/api/cron/jobs").objects().map { j ->
            CronJob(
                id = j.firstStr("id", "job_id", "name").orEmpty(),
                name = j.firstStr("name", "id").orEmpty(),
                schedule = j.firstStr("schedule", "cron").orEmpty(),
                prompt = j.firstStr("prompt", "script").orEmpty(),
                enabled = j.bool("enabled") || !j.bool("paused"),
                deliver = j.str("deliver").orEmpty(),
                nextRun = j.firstStr("next_run", "next_run_at"),
                lastRun = j.firstStr("last_run", "last_run_at"),
                profile = j.str("profile"),
            )
        }.filter { it.id.isNotBlank() }

    suspend fun cronPause(id: String): JsonElement =
        client().rest.postJson(base(), "/api/cron/jobs/" + q(id) + "/pause", JsonObject(emptyMap()))

    suspend fun cronResume(id: String): JsonElement =
        client().rest.postJson(base(), "/api/cron/jobs/" + q(id) + "/resume", JsonObject(emptyMap()))

    suspend fun cronTrigger(id: String): JsonElement =
        client().rest.postJson(base(), "/api/cron/jobs/" + q(id) + "/trigger", JsonObject(emptyMap()))

    // ----------------------------------------------------------------- git

    /** Null when [path] is not inside a git repo — the server answers null too. */
    suspend fun gitStatus(path: String): GitStatus? {
        val o = client().rest.getJson(base(), "/api/git/status?path=" + q(path)).obj() ?: return null
        return GitStatus(
            branch = o.str("branch"),
            defaultBranch = o.str("defaultBranch"),
            detached = o.bool("detached"),
            ahead = o.int("ahead") ?: 0,
            behind = o.int("behind") ?: 0,
            staged = o.int("staged") ?: 0,
            unstaged = o.int("unstaged") ?: 0,
            untracked = o.int("untracked") ?: 0,
            conflicted = o.int("conflicted") ?: 0,
            changed = o.int("changed") ?: 0,
            added = o.int("added") ?: 0,
            removed = o.int("removed") ?: 0,
            files = o.objects("files").map { f ->
                GitFile(
                    path = f.str("path").orEmpty(),
                    staged = f.bool("staged"),
                    unstaged = f.bool("unstaged"),
                    untracked = f.bool("untracked"),
                    conflicted = f.bool("conflicted"),
                )
            }.filter { it.path.isNotBlank() },
        )
    }

    /** Working directory the dashboard would open in — the git panel's root. */
    suspend fun defaultCwd(): String? =
        client().rest.getJson(base(), "/api/fs/default-cwd").obj()
            .firstStr("cwd", "path", "default_cwd")

    // --------------------------------------------------------------- usage

    suspend fun usage(days: Int = 30): UsageReport {
        val o = client().rest.getJson(base(), "/api/analytics/usage?days=" + days).obj()
        val totals = o.objAt("totals")
        return UsageReport(
            periodDays = o.int("period_days") ?: days,
            totalInput = totals.long("total_input") ?: 0,
            totalOutput = totals.long("total_output") ?: 0,
            totalCacheRead = totals.long("total_cache_read") ?: 0,
            totalSessions = totals.long("total_sessions") ?: 0,
            totalApiCalls = totals.long("total_api_calls") ?: 0,
            estimatedCostUsd = totals.firstDbl("total_actual_cost", "total_estimated_cost") ?: 0.0,
            byModel = o.objects("by_model").map { m ->
                ModelUsage(
                    model = m.str("model").orEmpty(),
                    input = m.long("input_tokens") ?: 0,
                    output = m.long("output_tokens") ?: 0,
                    sessions = m.long("sessions") ?: 0,
                    estimatedCostUsd = m.dbl("estimated_cost") ?: 0.0,
                )
            }.filter { it.model.isNotBlank() },
            daily = o.objects("daily").map { d ->
                DailyUsage(
                    day = d.str("day").orEmpty(),
                    input = d.long("input_tokens") ?: 0,
                    output = d.long("output_tokens") ?: 0,
                    estimatedCostUsd = d.dbl("estimated_cost") ?: 0.0,
                )
            }.filter { it.day.isNotBlank() },
        )
    }
}

/** Outcome of a model switch, including the server's price-confirmation gate. */
data class ModelSetResult(
    val ok: Boolean,
    val confirmRequired: Boolean,
    val message: String?,
)
