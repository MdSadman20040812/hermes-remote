package com.hermes.mobile.core.connection

/**
 * How the app proves itself to the dashboard (spec §A.3 + Phase 0 correction).
 *
 * TOKEN  — loopback/trusted deployment: `?token=<session token>` on the WS,
 *          `Authorization: Bearer` on REST. This is today's working mode.
 * GATED  — non-loopback deployment post-June-2026-hardening: the server
 *          requires an auth provider and rejects `?token=`. The client logs in
 *          over REST and mints a single-use 30 s ticket per WS connect via
 *          POST /api/auth/ws-ticket. Modeled now so switching deployments is a
 *          one-class change; the REST login flow lands with the tailnet build.
 */
sealed interface CredentialStrategy {
    /** Append the WS credential to a base ws:// URL. */
    fun wsUrl(base: String, path: String): String

    /** Value for the REST Authorization header, or null when none is needed. */
    fun restAuthorization(): String?

    data class Token(val token: String) : CredentialStrategy {
        override fun wsUrl(base: String, path: String): String = "$base$path?token=$token"
        override fun restAuthorization(): String = "Bearer $token"
    }

    /**
     * Gated/ticket mode (Phase 0 finding: mandatory for any non-loopback bind).
     * [ticketSupplier] must return a fresh single-use ticket per call.
     */
    class Ticket(
        private val ticketSupplier: suspend () -> String,
    ) : CredentialStrategy {
        override fun wsUrl(base: String, path: String): String {
            throw UnsupportedOperationException(
                "Ticket mode is async — call mintWsUrl() instead",
            )
        }

        suspend fun mintWsUrl(base: String, path: String): String =
            "$base$path?ticket=${ticketSupplier()}"

        override fun restAuthorization(): String? = null // cookie-session based
    }
}
