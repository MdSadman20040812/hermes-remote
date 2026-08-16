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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * because the cache/server is authoritative and local state is derived.
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

        private val lock = Mutex()
        private val model = mutableListOf<TranscriptItem>()

        private val assistantBuf = StringBuilder()
        private val thinkingBuf = StringBuilder()
        private var streamingAssistantKey: String? = null
        private var liveThinkingKey: String? = null
        @Volatile private var dirty = false

        private var eventJob: Job? = null
        private var flushJob: Job? = null
        private var seq = 0L
        private fun nextKey(prefix: String) = "$prefix-${seq++}"

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
            scope.launch { hydrateFromHistory() }
        }

        fun stop() {
            eventJob?.cancel()
            flushJob?.cancel()
        }

        private suspend fun publish() {
            lock.withLock { _items.value = model.toList() }
        }

        private suspend fun upsert(item: TranscriptItem) {
            lock.withLock {
                val idx = model.indexOfFirst { it.key == item.key }
                if (idx >= 0) model[idx] = item else model.add(item)
            }
            dirty = true
        }

        private suspend fun handle(event: HermesEvent) {
            when (event) {
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
                        upsert(TranscriptItem.AssistantMessage(key, assistantBuf.toString(), streaming = true))
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
                        args = event.args?.toString(),
                        result = event.result?.toString(),
                        durationS = event.durationS,
                        running = false,
                        approvalNote = runCatching {
                            event.result?.jsonObject?.get("approval")?.jsonPrimitive?.content
                        }.getOrNull(),
                    ),
                )
                is HermesEvent.ApprovalRequest -> upsert(
                    TranscriptItem.ApprovalCard(
                        key = "approval-$sessionId-${event.command.hashCode()}",
                        sessionId = sessionId,
                        command = event.command,
                        description = event.description,
                        choices = event.choices,
                    ),
                )
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
                    liveThinkingKey?.let { k ->
                        lock.withLock {
                            model.indexOfFirst { it.key == k }.takeIf { it >= 0 }?.let { idx ->
                                val t = model[idx] as TranscriptItem.ThinkingBlock
                                model[idx] = t.copy(live = false)
                            }
                        }
                        dirty = true
                    }
                    streamingAssistantKey = null
                    liveThinkingKey = null
                    _turnPhase.value = TurnPhase.IDLE
                }
                is HermesEvent.SessionTitle -> { /* surfaced via sessions list */ }
                is HermesEvent.StatusUpdate -> event.text?.let {
                    upsert(TranscriptItem.StatusLine(nextKey("status"), it))
                }
                else -> { /* Ready/SessionInfo/Global/Unknown — not transcript items */ }
            }
        }

        /** Optimistic local echo of the user's own prompt. */
        suspend fun echoUser(text: String) {
            upsert(TranscriptItem.UserMessage(nextKey("user"), text))
        }

        suspend fun markApprovalResolved(cardKey: String, choice: String) {
            lock.withLock {
                model.indexOfFirst { it.key == cardKey }.takeIf { it >= 0 }?.let { idx ->
                    val c = model[idx] as TranscriptItem.ApprovalCard
                    model[idx] = c.copy(resolved = choice)
                }
            }
            dirty = true
        }

        /** Cold-open / post-reconnect reconcile from the authoritative server. */
        suspend fun hydrateFromHistory() {
            val result = runCatching { client.sessionHistory(sessionId) }.getOrNull() ?: return
            val messages = result.jsonObject["messages"]?.let {
                it as? kotlinx.serialization.json.JsonArray
            } ?: return
            val rebuilt = mutableListOf<TranscriptItem>()
            var n = 0L
            for (m in messages) {
                val o = runCatching { m.jsonObject }.getOrNull() ?: continue
                val role = o["role"]?.jsonPrimitive?.content ?: continue
                when (role) {
                    "user" -> rebuilt.add(
                        TranscriptItem.UserMessage("h-user-${n++}", o["content"]?.jsonPrimitive?.content ?: ""),
                    )
                    "assistant" -> rebuilt.add(
                        TranscriptItem.AssistantMessage("h-asst-${n++}", o["content"]?.jsonPrimitive?.content ?: ""),
                    )
                    "tool" -> rebuilt.add(
                        TranscriptItem.ToolCallItem(
                            key = "h-tool-${n++}",
                            toolId = "",
                            name = o["name"]?.jsonPrimitive?.content ?: "tool",
                            context = o["context"]?.jsonPrimitive?.content,
                            args = null, result = null, durationS = null,
                            running = false,
                        ),
                    )
                }
            }
            lock.withLock {
                model.clear()
                model.addAll(rebuilt)
            }
            dirty = true
            publish()
        }
    }

    fun attach(client: HermesClient, sessionId: String, scope: CoroutineScope): Engine =
        Engine(client, sessionId, scope).also { it.start() }
}
