package com.hermes.mobile.data.repo

import com.hermes.mobile.core.transport.DeltaKind
import com.hermes.mobile.core.transport.HermesClient
import com.hermes.mobile.core.transport.HermesEvent
import com.hermes.mobile.domain.model.TranscriptItem
import com.hermes.mobile.domain.model.TurnPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles the live cockpit transcript from the event stream (spec §D.2).
 *
 * Jank rule: deltas are written into StringBuilders and published to the
 * StateFlow by a ~20fps flush ticker — recomposition never happens per token,
 * and the server already coalesces to ~30fps upstream (Phase 0 verified).
 *
 * One engine instance per ViewModel scope via [attach]; a fresh attach (new
 * session or post-reconnect reconcile) re-hydrates from session.history,
 * because the server is authoritative and local state is derived.
 */
@Singleton
class TranscriptRepository @Inject constructor() {

    inner class Engine(
        private val client: HermesClient,
        private val sessionId: String,
        private val scope: CoroutineScope,
    ) {
        private val _items = MutableStateFlow<List<TranscriptItem>>(emptyList())
        val items: StateFlow<List<TranscriptItem>> = _items.asStateFlow()

        private val _turnPhase = MutableStateFlow(TurnPhase.IDLE)
        val turnPhase: StateFlow<TurnPhase> = _turnPhase.asStateFlow()

        /** Latest context-window fill from usage, for the cockpit meter. */
        private val _contextPercent = MutableStateFlow<Int?>(null)
        val contextPercent: StateFlow<Int?> = _contextPercent.asStateFlow()

        /** Model the server says is answering, from session.info. */
        private val _model = MutableStateFlow<String?>(null)
        val model: StateFlow<String?> = _model.asStateFlow()

        private val lock = Mutex()
        private val rows = mutableListOf<TranscriptItem>()

        private val assistantBuf = StringBuilder()
        private val thinkingBuf = StringBuilder()
        private var streamingAssistantKey: String? = null
        private var liveThinkingKey: String? = null

        @Volatile
        private var dirty = false

        private var eventJob: Job? = null
        private var flushJob: Job? = null
        private var seq = 0L
        private fun nextKey(prefix: String) = "$prefix-${seq++}"

        /**
         * Approval cards are keyed by an incrementing ordinal, not by a hash of
         * the command. Hashing meant a second, identical approval request in
         * the same session collided with the first — it landed on the already
         * answered card, which rendered "Answered: …" with no buttons and left
         * the turn blocked with no way to respond.
         */
        private var approvalOrdinal = 0
        private var openApprovalKey: String? = null

        fun start() {
            eventJob = scope.launch(Dispatchers.Default) {
                client.rpc.events.collect { event ->
                    if (event.sessionId.isNotEmpty() && event.sessionId != sessionId) return@collect
                    handle(event)
                }
            }
            flushJob = scope.launch(Dispatchers.Default) {
                while (isActive) {
                    delay(50) // ~20fps publish — deltas are already coalesced server-side
                    if (dirty) {
                        dirty = false
                        publish()
                    }
                }
            }
            scope.launch {
                hydrateFromHistory()
                replayPendingApproval()
            }
        }

        fun stop() {
            eventJob?.cancel()
            flushJob?.cancel()
        }

        /**
         * Whether this engine is still listening to [candidate].
         *
         * A reconnect builds a whole new client, and an engine bound to the
         * old one keeps running against a dead event flow — the cockpit looks
         * connected and receives nothing. The ViewModel asks this on every
         * client change so it can re-attach instead.
         */
        fun isBoundTo(candidate: HermesClient): Boolean = client === candidate

        private suspend fun publish() {
            lock.withLock { _items.value = rows.toList() }
        }

        private suspend fun upsert(item: TranscriptItem) {
            lock.withLock {
                val idx = rows.indexOfFirst { it.key == item.key }
                if (idx >= 0) rows[idx] = item else rows.add(item)
            }
            dirty = true
        }

        private suspend fun handle(event: HermesEvent) {
            when (event) {
                is HermesEvent.SessionInfo -> {
                    event.model?.let { _model.value = it }
                    event.contextPercent?.let { _contextPercent.value = it }
                }
                is HermesEvent.MessageStart -> {
                    _turnPhase.value = TurnPhase.RUNNING
                    assistantBuf.clear()
                    thinkingBuf.clear()
                    streamingAssistantKey = null
                    liveThinkingKey = null
                }
                is HermesEvent.Delta -> when (event.kind) {
                    DeltaKind.MESSAGE -> {
                        assistantBuf.append(event.text)
                        val key = streamingAssistantKey ?: nextKey("asst").also {
                            streamingAssistantKey = it
                        }
                        upsert(
                            TranscriptItem.AssistantMessage(
                                key,
                                assistantBuf.toString(),
                                streaming = true,
                            ),
                        )
                    }
                    DeltaKind.THINKING, DeltaKind.REASONING -> {
                        if (event.text.isBlank()) return
                        thinkingBuf.append(event.text)
                        val key = liveThinkingKey ?: nextKey("think").also { liveThinkingKey = it }
                        upsert(TranscriptItem.ThinkingBlock(key, thinkingBuf.toString(), live = true))
                    }
                }
                is HermesEvent.ToolStart -> upsert(
                    TranscriptItem.ToolCallItem(
                        key = "tool-${event.toolId}",
                        toolId = event.toolId,
                        name = event.name,
                        context = event.context,
                        args = null, result = null, durationS = null,
                        running = true,
                    ),
                )
                is HermesEvent.ToolComplete -> upsert(
                    TranscriptItem.ToolCallItem(
                        key = "tool-${event.toolId}",
                        toolId = event.toolId,
                        name = event.name,
                        context = null,
                        args = event.args?.let { prettyArgs(it) },
                        result = event.result?.let { prettyResult(it) },
                        durationS = event.durationS,
                        running = false,
                        approvalNote = event.result.obj().str("approval"),
                    ),
                )
                is HermesEvent.ApprovalRequest -> addApproval(event)
                is HermesEvent.MessageComplete -> {
                    // The server sends the authoritative full text — replace the buffer.
                    val key = streamingAssistantKey ?: nextKey("asst")
                    upsert(
                        TranscriptItem.AssistantMessage(
                            key = key,
                            text = event.text.ifBlank { assistantBuf.toString() },
                            streaming = false,
                            usageContextPercent = event.usage?.contextPercent,
                        ),
                    )
                    event.usage?.contextPercent?.let { _contextPercent.value = it }
                    event.usage?.model?.let { _model.value = it }
                    liveThinkingKey?.let { k ->
                        lock.withLock {
                            rows.indexOfFirst { it.key == k }.takeIf { it >= 0 }?.let { idx ->
                                (rows[idx] as? TranscriptItem.ThinkingBlock)?.let {
                                    rows[idx] = it.copy(live = false)
                                }
                            }
                        }
                        dirty = true
                    }
                    streamingAssistantKey = null
                    liveThinkingKey = null
                    _turnPhase.value = TurnPhase.IDLE
                }
                is HermesEvent.SessionTitle -> { /* surfaced via the sessions list */ }
                is HermesEvent.StatusUpdate -> event.text?.let {
                    upsert(TranscriptItem.StatusLine(nextKey("status"), it))
                }
                else -> { /* Ready/Global/Unknown — not transcript items */ }
            }
        }

        private suspend fun addApproval(event: HermesEvent.ApprovalRequest) {
            val key = "approval-${approvalOrdinal++}"
            openApprovalKey = key
            upsert(
                TranscriptItem.ApprovalCard(
                    key = key,
                    sessionId = sessionId,
                    command = event.command,
                    description = event.description,
                    choices = event.choices.ifEmpty { DEFAULT_CHOICES },
                    allowSession = event.allowSession,
                    allowPermanent = event.allowPermanent,
                ),
            )
        }

        /**
         * An approval raised while the app was backgrounded is not re-emitted on
         * reattach — the event already fired. Without this the turn looks hung.
         */
        private suspend fun replayPendingApproval() {
            val pending = runCatching { client.approvalPending(sessionId) }.getOrNull().obj()
                ?: return
            val command = pending.firstStr("command", "cmd") ?: return
            _turnPhase.value = TurnPhase.RUNNING
            addApproval(
                HermesEvent.ApprovalRequest(
                    sessionId = sessionId,
                    command = command,
                    description = pending.str("description"),
                    patternKey = pending.str("pattern_key"),
                    choices = pending.strings("choices"),
                    allowPermanent = pending.bool("allow_permanent"),
                    allowSession = pending.bool("allow_session"),
                ),
            )
        }

        /** Optimistic local echo of the user's own prompt. */
        suspend fun echoUser(text: String) {
            upsert(TranscriptItem.UserMessage(nextKey("user"), text))
        }

        /** A slash command's pager output, rendered inline in the transcript. */
        suspend fun addCommandOutput(command: String, output: String) {
            upsert(TranscriptItem.CommandOutput(nextKey("cmd"), command, output))
        }

        suspend fun markApprovalResolved(cardKey: String, choice: String) {
            lock.withLock {
                rows.indexOfFirst { it.key == cardKey }.takeIf { it >= 0 }?.let { idx ->
                    (rows[idx] as? TranscriptItem.ApprovalCard)?.let {
                        rows[idx] = it.copy(resolved = choice)
                    }
                }
            }
            if (openApprovalKey == cardKey) openApprovalKey = null
            dirty = true
        }

        /** Cold-open / post-reconnect reconcile from the authoritative server. */
        suspend fun hydrateFromHistory() {
            val result = runCatching { client.sessionHistory(sessionId) }.getOrNull() ?: return
            val messages = result.obj().objects("messages")
            if (messages.isEmpty()) return
            val rebuilt = mutableListOf<TranscriptItem>()
            var n = 0L
            for (o in messages) {
                when (o.str("role")) {
                    "user" -> rebuilt.add(
                        TranscriptItem.UserMessage("h-user-${n++}", o.messageText()),
                    )
                    "assistant" -> rebuilt.add(
                        TranscriptItem.AssistantMessage("h-asst-${n++}", o.messageText()),
                    )
                    "tool" -> rebuilt.add(
                        TranscriptItem.ToolCallItem(
                            key = "h-tool-${n++}",
                            toolId = "",
                            name = o.firstStr("name", "tool_name") ?: "tool",
                            context = o.str("context"),
                            args = null,
                            result = o.messageText().takeIf { it.isNotBlank() },
                            durationS = null,
                            running = false,
                        ),
                    )
                }
            }
            lock.withLock {
                rows.clear()
                rows.addAll(rebuilt)
            }
            dirty = true
            publish()
        }
    }

    fun attach(client: HermesClient, sessionId: String, scope: CoroutineScope): Engine =
        Engine(client, sessionId, scope).also { it.start() }

    private companion object {
        val DEFAULT_CHOICES = listOf("once", "session", "deny")
    }
}

/**
 * History content is either a plain string or the multi-part content blocks the
 * provider returned. Flatten the text parts; anything else (images, tool
 * results) is represented by its type so the row is never silently empty.
 */
private fun kotlinx.serialization.json.JsonObject?.messageText(): String {
    this.str("content")?.let { return it }
    val parts = this.arrAt("content") ?: return ""
    return parts.mapNotNull { part ->
        part.obj()?.let { it.str("text") ?: it.str("type")?.let { t -> "[$t]" } }
    }.joinToString("\n")
}

/** Tool args as compact `key=value` text — the raw JSON object is unreadable on a phone. */
private fun prettyArgs(args: kotlinx.serialization.json.JsonObject): String =
    args.entries.joinToString("  ") { (k, v) ->
        "$k=" + v.toString().removeSurrounding("\"").take(120)
    }

private fun prettyResult(result: kotlinx.serialization.json.JsonElement): String {
    val o = result.obj() ?: return result.toString().removeSurrounding("\"")
    return o.firstStr("output", "text", "content", "result", "stdout") ?: o.toString()
}
