package com.hermes.mobile.core.connection

import com.hermes.mobile.core.transport.RestClient
import com.hermes.mobile.core.transport.RestException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A credential problem the user can act on. Never swallow these. */
class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * How the app proves itself to the dashboard.
 *
 * TOKEN — loopback / trusted bind: `?token=<session token>` on the WS. REST on
 *         loopback needs no credential at all (Phase 0 §0.5).
 *
 * GATED — every non-loopback bind. The `insecure` mode the spec assumed was
 *         removed in June 2026: `web_server.py` refuses to bind off-loopback
 *         without an auth provider and rejects `?token=` outright. The client
 *         must instead
 *           1. POST /auth/password-login   → sets hermes_session_* cookies
 *           2. POST /api/auth/ws-ticket    → {"ticket", "ttl_seconds": 30}
 *           3. open the WS with ?ticket=<ticket>
 *         Tickets are SINGLE USE with a 30 s TTL, so one is minted per socket
 *         and per reconnect — never cached.
 *
 * [wsUrl] is `suspend` for exactly that reason. The previous version had a
 * synchronous `wsUrl` that threw `UnsupportedOperationException` in gated mode,
 * which is what surfaced as "connect ends in auth failure".
 */
sealed interface CredentialStrategy {

    /** Perform any one-time login. Idempotent; safe to call before every connect. */
    suspend fun ensureAuthenticated() {}

    /** Absolute ws:// URL carrying a valid credential. Mints a fresh ticket in gated mode. */
    suspend fun wsUrl(base: String, path: String): String

    /** Value for a REST `Authorization` header, or null when cookies carry the session. */
    fun restAuthorization(): String? = null

    // -----------------------------------------------------------------------

    data class Token(val token: String) : CredentialStrategy {
        override suspend fun wsUrl(base: String, path: String): String =
            "$base$path?token=$token"

        override fun restAuthorization(): String = "Bearer $token"
    }

    // -----------------------------------------------------------------------

    class Gated(
        private val httpBase: String,
        private val username: String,
        private val password: String,
        private val rest: RestClient,
        private val cookies: HermesCookieJar,
        private val host: String,
        private val provider: String = DEFAULT_PROVIDER,
    ) : CredentialStrategy {

        private val loginLock = Mutex()

        override suspend fun ensureAuthenticated() {
            if (cookies.hasSessionFor(host)) return
            loginLock.withLock {
                if (cookies.hasSessionFor(host)) return
                login()
            }
        }

        override suspend fun wsUrl(base: String, path: String): String {
            ensureAuthenticated()
            return "$base$path?ticket=${mintTicket()}"
        }

        /**
         * POST /auth/password-login.
         *
         * The route binds a Pydantic model, so this must be a JSON body — a
         * form-encoded post gets 422.
         */
        private suspend fun login() {
            val body: JsonObject = buildJsonObject {
                put("provider", provider)
                put("username", username)
                put("password", password)
                put("next", "/")
            }
            val result = try {
                rest.postJson(httpBase, PATH_LOGIN, body)
            } catch (e: RestException) {
                throw AuthException(loginFailureMessage(e), e)
            }
            val ok = result.jsonObject["ok"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!ok) throw AuthException("Dashboard rejected the login.")
            if (!cookies.hasSessionFor(host)) {
                throw AuthException(
                    "Login succeeded but no session cookie was stored. " +
                        "Check that the HTTP client has a cookie jar installed.",
                )
            }
        }

        /**
         * Mint a single-use 30 s ticket. On 401 the cookie has expired
         * (12 h default TTL) — clear it, log in once more, and retry exactly
         * once so a stale session self-heals instead of surfacing as a
         * connection failure.
         */
        private suspend fun mintTicket(retrying: Boolean = false): String {
            val result = try {
                rest.postJson(httpBase, PATH_WS_TICKET, buildJsonObject { })
            } catch (e: RestException) {
                if (e.code == 401 && !retrying) {
                    cookies.clear()
                    loginLock.withLock { login() }
                    return mintTicket(retrying = true)
                }
                throw AuthException(ticketFailureMessage(e), e)
            }
            val ticket = result.jsonObject["ticket"]?.jsonPrimitive?.contentOrNull
            if (ticket.isNullOrBlank()) {
                throw AuthException("Dashboard returned no ws-ticket.")
            }
            return ticket
        }

        private fun loginFailureMessage(e: RestException): String = when (e.code) {
            401 -> "Wrong username or password for the dashboard."
            404 -> "This dashboard has no '$provider' password provider configured."
            429 -> "Too many login attempts — wait a minute and try again."
            503 -> "The dashboard's auth provider is unreachable."
            else -> "Login failed (HTTP ${e.code})."
        }

        private fun ticketFailureMessage(e: RestException): String = when (e.code) {
            401 -> "Session expired and could not be renewed."
            else -> "Could not mint a WebSocket ticket (HTTP ${e.code})."
        }

        private companion object {
            const val PATH_LOGIN = "/auth/password-login"
            const val PATH_WS_TICKET = "/api/auth/ws-ticket"
        }
    }

    companion object {
        /** Bundled username/password provider — plugins/dashboard_auth/basic. */
        const val DEFAULT_PROVIDER = "basic"
    }
}
