package com.hermes.mobile.core.connection

import kotlinx.serialization.Serializable

/**
 * A PC the app can attach to (spec §C.3). The credential secret itself lives
 * in the vault, keyed by profile [id] — never in this serializable record.
 *
 * [secure] selects https/wss. It defaults to false because the canonical
 * deployment is a plain-HTTP bind on a tailnet address where WireGuard is the
 * encryption; a dashboard behind a TLS reverse proxy sets it true.
 */
@Serializable
data class ConnectionProfile(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 9119,
    val auth: AuthKind = AuthKind.TOKEN,
    val secure: Boolean = false,
    val lastSeenAt: Long? = null,
    val isDefault: Boolean = false,
) {
    val httpBase: String get() = "${if (secure) "https" else "http"}://$host:$port"
    val wsBase: String get() = "${if (secure) "wss" else "ws"}://$host:$port"
    val displayAddress: String get() = "$host:$port"
}

/**
 * TOKEN — loopback / trusted bind: `?token=<dashboard session token>`.
 * GATED — any non-loopback bind (post-June-2026 hardening): the server refuses
 *         `?token=` and requires a password login + a single-use WS ticket.
 *
 * The app does not have to be told which one applies: [ConnectionManager]
 * reads `auth_required` off `GET /api/status` and picks.
 */
enum class AuthKind { TOKEN, GATED }

/** Stable profile id for a host/port pair. */
fun profileIdFor(host: String, port: Int): String =
    "pc-${host.replace('.', '-')}-$port"

/**
 * Split a stored vault secret of the form `user:password` used by GATED
 * profiles. Returns null when the secret is not in that form (i.e. it is a
 * bare session token). A password may itself contain ':' — only the FIRST
 * colon separates.
 */
fun splitBasicSecret(secret: String): Pair<String, String>? {
    val i = secret.indexOf(':')
    if (i <= 0 || i == secret.lastIndex) return null
    return secret.substring(0, i) to secret.substring(i + 1)
}

/**
 * Pairing payload scanned from the PC-side QR (spec §C.3; `pc/hermes-remote.ps1`
 * renders it).
 *
 * Phase 0 finding: a non-loopback dashboard is always gated, so for a tailnet
 * deployment the QR carries the basic-auth [username]/[password] rather than a
 * session [token]. Both shapes are accepted so an old QR still pairs against a
 * loopback bind.
 */
@Serializable
data class QrPairingPayload(
    val v: Int = 1,
    val name: String = "",
    val host: String,
    val port: Int = 9119,
    val token: String = "",
    val username: String = "",
    val password: String = "",
    /** Dashboard auth provider name; the bundled password provider is "basic". */
    val provider: String = "basic",
    val fingerprint: String? = null,
    val tailnet: String? = null,
    val auth: String = "",
    /** "https"/"wss" deployments set this; absent QRs stay on plain HTTP. */
    val tls: Boolean = false,
    val scheme: String = "",
) {
    val kind: AuthKind
        get() = when {
            auth.equals("gated", ignoreCase = true) -> AuthKind.GATED
            username.isNotBlank() -> AuthKind.GATED
            else -> AuthKind.TOKEN
        }

    val isSecure: Boolean
        get() = tls || scheme.equals("https", ignoreCase = true) ||
            scheme.equals("wss", ignoreCase = true)

    /** What gets written to the vault for this profile. */
    fun vaultSecret(): String =
        if (kind == AuthKind.GATED) "$username:$password" else token

    fun isUsable(): Boolean =
        host.isNotBlank() && vaultSecret().isNotBlank() &&
            (kind == AuthKind.TOKEN || username.isNotBlank())

    fun toProfile(): ConnectionProfile = ConnectionProfile(
        id = profileIdFor(host, port),
        label = name.ifBlank { host },
        host = host,
        port = port,
        auth = kind,
        secure = isSecure,
        isDefault = true,
    )
}
