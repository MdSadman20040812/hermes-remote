package com.hermes.mobile.data.repo

import com.hermes.mobile.core.transport.HermesJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the `session.history` wire shape.
 *
 * This is the regression that made chat history invisible on the phone. The
 * gateway's `_history_to_messages` projection emits `{"role","text"}` — the
 * reader looked for `content` first, so every row decoded to "" and was then
 * dropped by the blank filter. Opening any existing conversation showed an
 * empty screen, which read as "history doesn't sync" when in fact the rows
 * arrived and were discarded.
 *
 * The literals below are the server's own projection (tui_gateway/
 * session_history.py), not an invented shape.
 */
class HistoryMessageTextTest {

    private fun obj(json: String): JsonObject = HermesJson.parseToJsonElement(json).jsonObject

    @Test
    fun `reads the gateway projection's text field`() {
        val m = obj("""{"role":"user","text":"deploy the site","timestamp":1.0}""")
        assertEquals("deploy the site", m.messageText())
    }

    @Test
    fun `text wins over content when both are present`() {
        // A row can carry both after compaction; `text` is the display
        // projection and `content` the model-facing body.
        val m = obj("""{"role":"assistant","text":"Done.","content":"internal scaffold"}""")
        assertEquals("Done.", m.messageText())
    }

    @Test
    fun `still reads a legacy plain-string content`() {
        val m = obj("""{"role":"assistant","content":"older server"}""")
        assertEquals("older server", m.messageText())
    }

    @Test
    fun `flattens multi-part content blocks`() {
        val m = obj(
            """{"role":"user","content":[
                 {"type":"text","text":"look at this"},
                 {"type":"image_url","image_url":{"url":"file:///a.png"}}
               ]}""",
        )
        assertEquals("look at this\n[image_url]", m.messageText())
    }

    @Test
    fun `a row with neither field is empty, not a crash`() {
        assertEquals("", obj("""{"role":"tool","name":"terminal"}""").messageText())
    }

    @Test
    fun `skill invocation rows keep their displayed text`() {
        val m = obj("""{"role":"user","text":"/work fix the leak","display_kind":"skill_invocation"}""")
        assertEquals("/work fix the leak", m.messageText())
    }
}
