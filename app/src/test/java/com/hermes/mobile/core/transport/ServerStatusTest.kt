package com.hermes.mobile.core.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pin for the status document.
 *
 * The payload below is a LITERAL capture from Hermes 0.21.0 on this LAN. The
 * previous model typed `gateway_platforms` as `List<String>` while the server
 * sends an object, so every `/api/status` decode threw and the connect path
 * reported "Can't reach your PC" on a dashboard that was answering fine.
 *
 * Any future field whose shape is guessed rather than captured belongs here
 * before it ships.
 */
class ServerStatusTest {

    private val live = """
        {"version":"0.21.0","release_date":"2026.8.31","config_version":41,
         "latest_config_version":41,"can_update_hermes":true,
         "gateway_running":true,"gateway_state":"running",
         "gateway_platforms":{"telegram":{"state":"connected","error_code":null,
           "error_message":null,"updated_at":"2026-09-07T12:47:34.593739+00:00",
           "needs_attention":false,"retrying_since":null}},
         "gateway_exit_reason":null,"active_agents":0,"gateway_busy":false,
         "gateway_drainable":true,"restart_drain_timeout":180.0,
         "active_sessions":1,"auth_required":true,"auth_providers":["basic"],
         "auth_flows":["cookie","native_pkce"],"nous_session_valid":"valid",
         "components":{"gateway":{"status":"ok","state":"running"}},
         "overall":"ok","disk":{"pressure":"ok","total_mb":166791},
         "profiles":["default","automator"],"gateway_mode":"single"}
    """.trimIndent()

    @Test
    fun `live payload decodes`() {
        val status = HermesJson.decodeFromString(ServerStatus.serializer(), live)
        assertEquals("0.21.0", status.version)
        assertTrue(status.gatewayRunning)
        assertEquals("running", status.gatewayState)
        assertEquals(1, status.activeSessions)
    }

    @Test
    fun `auth_required drives credential choice and must survive the parse`() {
        val status = HermesJson.decodeFromString(ServerStatus.serializer(), live)
        assertTrue(status.authRequired)
        assertEquals(listOf("basic"), status.authProviders)
    }

    @Test
    fun `object-shaped gateway_platforms yields its names`() {
        val status = HermesJson.decodeFromString(ServerStatus.serializer(), live)
        assertEquals(listOf("telegram"), status.platformNames)
    }

    @Test
    fun `list-shaped gateway_platforms still works`() {
        val json = """{"version":"0.21.0","gateway_platforms":["telegram","discord"]}"""
        val status = HermesJson.decodeFromString(ServerStatus.serializer(), json)
        assertEquals(listOf("telegram", "discord"), status.platformNames)
    }

    @Test
    fun `absent gateway_platforms is empty, not a crash`() {
        val status = HermesJson.decodeFromString(ServerStatus.serializer(), """{"version":"1"}""")
        assertEquals(emptyList<String>(), status.platformNames)
    }

    @Test
    fun `unknown future fields do not fail the parse`() {
        val json = """{"version":"9.9.9","some_field_from_2027":{"a":[1,2]},"auth_required":true}"""
        val status = HermesJson.decodeFromString(ServerStatus.serializer(), json)
        assertEquals("9.9.9", status.version)
        assertTrue(status.authRequired)
    }
}
