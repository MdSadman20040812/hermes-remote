package com.hermes.mobile.core.connection

import kotlinx.serialization.Serializable

/**
 * A PC the app can attach to (spec §C.3). The credential secret itself lives
 * in the vault, keyed by profile [id] — never in this serializable record.
 */
@Serializable
data class ConnectionProfile(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 9119,
    val auth: AuthKind = AuthKind.TOKEN,
    val lastSeenAt: Long? = null,
    val isDefault: Boolean = false,
) {
    val httpBase: String get() = "http://$host:$port"
    val wsBase: String get() = "ws://$host:$port"
}

enum class AuthKind { TOKEN, GATED }

/**
 * Pairing payload scanned from the PC-side QR (spec §C.3; PC script renders it).
 * Phase 0 finding: on gated (non-loopback) deployments the WS credential is a
 * single-use ticket, so `token` here is the dashboard *session token* for
 * loopback profiles, or the basic-auth password for gated ones — see
 * [CredentialStrategy].
 */
@Serializable
data class QrPairingPayload(
    val v: Int = 1,
    val name: String = "",
    val host: String,
    val port: Int = 9119,
    val token: String,
    val fingerprint: String? = null,
    val tailnet: String? = null,
    val auth: String = "token",
) {
    fun toProfile(): ConnectionProfile = ConnectionProfile(
        id = "pc-${host.replace('.', '-')}-$port",
        label = name.ifBlank { host },
        host = host,
        port = port,
        auth = if (auth.equals("gated", true)) AuthKind.GATED else AuthKind.TOKEN,
        isDefault = true,
    )
}
