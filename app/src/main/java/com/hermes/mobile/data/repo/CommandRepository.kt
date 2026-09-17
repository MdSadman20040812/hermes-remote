package com.hermes.mobile.data.repo

import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.core.transport.rpcParamsOf
import com.hermes.mobile.domain.model.SlashCommand
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Slash commands — the desktop TUI's whole command surface, in the pocket.
 *
 * `commands.catalog` returns categorised `[name, description]` pairs plus the
 * skill commands; `slash.exec` runs one against a live session and answers
 * `{output}`. Both are cheap enough to hit on demand, but the catalog is
 * cached for the process because it only changes when config/skills change.
 */
@Singleton
class CommandRepository @Inject constructor(
    private val connectionManager: ConnectionManager,
) {
    @Volatile
    private var cached: List<SlashCommand>? = null

    private suspend fun client() = connectionManager.clientFlow.first { it != null }!!

    suspend fun catalog(refresh: Boolean = false): List<SlashCommand> {
        cached?.takeIf { !refresh }?.let { return it }
        val result = client().rpc.call("commands.catalog").obj()

        // Prefer `categories`, which carries the grouping the TUI renders. Fall
        // back to the flat `pairs` list when a server predates it.
        val fromCategories = result.objects("categories").flatMap { cat ->
            val category = cat.str("name").orEmpty()
            cat.arrAt("pairs").toCommands(category)
        }
        val commands = fromCategories.ifEmpty {
            result.arrAt("pairs").toCommands("Commands")
        }
        return commands.distinctBy { it.name }.also { cached = it }
    }

    /** Run a slash command in [sessionId]; returns the pager text it produced. */
    suspend fun exec(sessionId: String, command: String): String {
        val result = client().rpc.call(
            "slash.exec",
            rpcParamsOf("session_id" to sessionId, "command" to command),
            timeout = 120.seconds,
        ).obj()
        return result.firstStr("output", "text", "message").orEmpty()
    }

    fun invalidate() {
        cached = null
    }
}

/** `pairs` is an array of two-element `[name, description]` arrays. */
private fun JsonArray?.toCommands(category: String): List<SlashCommand> =
    this?.mapNotNull { entry ->
        val pair = entry.strings()
        val name = pair.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SlashCommand(
            name = name,
            description = pair.getOrNull(1).orEmpty(),
            category = category,
        )
    } ?: emptyList()
