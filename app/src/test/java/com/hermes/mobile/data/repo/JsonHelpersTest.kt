package com.hermes.mobile.data.repo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lenient readers exist because the dashboard payloads mix `null`, `""`
 * and a missing key for the same "unknown", and because a single unexpected
 * field used to blank a whole panel. These pin that behaviour down.
 */
class JsonHelpersTest {

    private fun obj(raw: String): JsonObject =
        Json.parseToJsonElement(raw) as JsonObject

    @Test
    fun `str treats null empty and missing alike`() {
        val o = obj("""{"a":"x","b":null,"c":"","d":"null"}""")
        assertEquals("x", o.str("a"))
        assertNull(o.str("b"))
        assertNull(o.str("c"))
        assertNull(o.str("d"))
        assertNull(o.str("missing"))
    }

    @Test
    fun `bool accepts json booleans numbers and strings`() {
        val o = obj("""{"t":true,"f":false,"one":1,"zero":0,"s":"true","empty":"","n":null}""")
        assertTrue(o.bool("t"))
        assertFalse(o.bool("f"))
        assertTrue(o.bool("one"))
        assertFalse(o.bool("zero"))
        assertTrue(o.bool("s"))
        assertFalse(o.bool("empty"))
        assertFalse(o.bool("n"))
        assertFalse(o.bool("missing"))
    }

    @Test
    fun `long survives a value the server sent as a float`() {
        // SQLite SUM() comes back as a float often enough to matter.
        val o = obj("""{"tokens":1234.0,"exact":99}""")
        assertEquals(1234L, o.long("tokens"))
        assertEquals(99L, o.long("exact"))
    }

    @Test
    fun `objects skips entries that are not objects`() {
        val o = obj("""{"rows":[{"a":1},"junk",null,{"b":2}]}""")
        assertEquals(2, o.objects("rows").size)
    }

    @Test
    fun `strings skips nulls`() {
        val o = obj("""{"models":["a",null,"b"]}""")
        assertEquals(listOf("a", "b"), o.strings("models"))
    }

    @Test
    fun `firstStr falls through renamed fields`() {
        val o = obj("""{"display_name":"Anthropic"}""")
        assertEquals("Anthropic", o.firstStr("label", "display_name", "name"))
        assertNull(o.firstStr("nope", "also_nope"))
    }

    @Test
    fun `accessors on a null receiver do not throw`() {
        val nothing: JsonObject? = null
        assertNull(nothing.str("a"))
        assertFalse(nothing.bool("a"))
        assertEquals(emptyList<JsonObject>(), nothing.objects("a"))
    }
}
