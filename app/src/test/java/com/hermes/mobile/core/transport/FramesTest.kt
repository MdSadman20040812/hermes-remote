package com.hermes.mobile.core.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame parsing tests use LITERAL frames captured from the live server in
 * Phase 0 (pc/probe_output.txt) — if the server changes shape, these break.
 */
class FramesTest {

    @Test
    fun `request serializes as single-line JSON-RPC with id and method`() {
        val line = RpcRequest(id = 17, method = "prompt.submit", params = rpcParamsOf(
            "session_id" to "abc123", "text" to "hi",
        )).encode()
        assertTrue(line.endsWith("\n"))
        assertFalse(line.trim().contains("\n"))
        val parsed = HermesJson.parseToJsonElement(line).jsonObjectOrNull()
        assertNotNull(parsed)
        assertEquals("2.0", parsed!!["jsonrpc"]!!.jsonPrimitiveContent())
        assertEquals("17", parsed["id"]!!.jsonPrimitiveContent())
        assertEquals("prompt.submit", parsed["method"]!!.jsonPrimitiveContent())
    }

    @Test
    fun `gateway ready parses with change_events flag`() {
        val literal = """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"change_events":true,"skin":{"name":"default"}}}}"""
        val frame = parseInboundFrame(literal)
        assertTrue(frame is InboundFrame.Event)
        val event = (frame as InboundFrame.Event).event
        assertTrue(event is HermesEvent.Ready)
        assertTrue((event as HermesEvent.Ready).changeEvents)
    }

    @Test
    fun `message delta literal frame`() {
        val literal = """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"aa14aae1","payload":{"text":"PROBE"}}}"""
        val event = ((parseInboundFrame(literal) as InboundFrame.Event).event)
        assertTrue(event is HermesEvent.Delta)
        event as HermesEvent.Delta
        assertEquals(DeltaKind.MESSAGE, event.kind)
        assertEquals("PROBE", event.text)
        assertEquals("aa14aae1", event.sessionId)
    }

    @Test
    fun `tool start and complete literal frames`() {
        val start = """{"jsonrpc":"2.0","method":"event","params":{"type":"tool.start","session_id":"c9a89b1d","payload":{"tool_id":"tool_fTBx","name":"terminal","context":"echo X"}}}"""
        val s = (parseInboundFrame(start) as InboundFrame.Event).event as HermesEvent.ToolStart
        assertEquals("terminal", s.name)
        assertEquals("echo X", s.context)

        val complete = """{"jsonrpc":"2.0","method":"event","params":{"type":"tool.complete","session_id":"c9a89b1d","payload":{"tool_id":"tool_fTBx","name":"terminal","args":{"command":"echo X","timeout":30},"duration_s":0.1846,"result":{"output":"X","exit_code":0,"error":null}}}}"""
        val c = (parseInboundFrame(complete) as InboundFrame.Event).event as HermesEvent.ToolComplete
        assertEquals("tool_fTBx", c.toolId)
        assertEquals(0.1846, c.durationS!!, 0.0001)
        assertNotNull(c.result)
    }

    @Test
    fun `approval request literal frame`() {
        val literal = """{"jsonrpc":"2.0","method":"event","params":{"type":"approval.request","session_id":"c81c92be","payload":{"command":"rm -rf D:/scratch","pattern_key":"recursive delete","pattern_keys":["recursive delete"],"description":"recursive delete","allow_permanent":true,"allow_session":true,"choices":["once","session","always","deny"]}}}"""
        val a = (parseInboundFrame(literal) as InboundFrame.Event).event as HermesEvent.ApprovalRequest
        assertEquals("recursive delete", a.patternKey)
        assertEquals(listOf("once", "session", "always", "deny"), a.choices)
        assertTrue(a.allowPermanent)
        assertTrue(a.allowSession)
    }

    @Test
    fun `message complete literal frame with usage`() {
        val literal = """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"aa14aae1","payload":{"text":"PROBE OK","usage":{"model":"kimi-k3","input":1418,"output":35,"total":28333,"calls":1,"context_percent":3},"status":"complete","reasoning":"…"}}}"""
        val m = (parseInboundFrame(literal) as InboundFrame.Event).event as HermesEvent.MessageComplete
        assertEquals("PROBE OK", m.text)
        assertEquals("complete", m.status)
        assertEquals("kimi-k3", m.usage?.model)
        assertEquals(3, m.usage?.contextPercent)
    }

    @Test
    fun `response frame correlates by id and surfaces errors`() {
        val ok = """{"jsonrpc":"2.0","id":5,"result":{"status":"interrupted"}}"""
        val r = parseInboundFrame(ok) as InboundFrame.Response
        assertEquals(5, r.id)
        assertNull(r.error)
        assertNotNull(r.result)

        val err = """{"jsonrpc":"2.0","id":6,"error":{"code":4009,"message":"session busy"}}"""
        val e = parseInboundFrame(err) as InboundFrame.Response
        assertEquals(6, e.id)
        assertEquals(4009, e.error?.code)
        assertEquals("session busy", e.error?.message)
    }

    @Test
    fun `global broadcast and unknown types never crash the parser`() {
        val global = """{"jsonrpc":"2.0","method":"event","params":{"type":"sessions.changed","session_id":"","payload":{}}}"""
        assertTrue((parseInboundFrame(global) as InboundFrame.Event).event is HermesEvent.Global)

        val unknown = """{"jsonrpc":"2.0","method":"event","params":{"type":"pet.hatched","session_id":"","payload":{"species":"dragon"}}}"""
        val u = (parseInboundFrame(unknown) as InboundFrame.Event).event as HermesEvent.Unknown
        assertEquals("pet.hatched", u.type)

        assertNull(parseInboundFrame(""))
        assertNull(parseInboundFrame("not json at all"))
        assertNull(parseInboundFrame("{broken"))
    }
}

// --- tiny test helpers over kotlinx.json's verbose accessors ---
private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull() =
    this as? kotlinx.serialization.json.JsonObject

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String =
    (this as kotlinx.serialization.json.JsonPrimitive).content
