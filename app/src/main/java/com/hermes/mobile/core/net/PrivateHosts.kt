package com.hermes.mobile.core.net

/**
 * Guard: plain HTTP is only ever acceptable to a private address.
 *
 * The app has `usesCleartextTraffic=true` because a home dashboard serves
 * plain HTTP on the LAN. Left unqualified that also lets a mistyped, stale, or
 * hostile profile POST the dashboard password to a public host in the clear.
 *
 * Android's `network-security-config` cannot express this — its `<domain>`
 * entries are hostnames, not CIDR ranges — so the check lives here and is
 * enforced at the one place a profile is created.
 */
object PrivateHosts {

    /** RFC1918, loopback, link-local, CGNAT (tailnet), and .local mDNS names. */
    fun isPrivate(host: String): Boolean {
        val h = host.trim().lowercase().removeSurrounding("[", "]")
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan")) return true
        if (h.startsWith("fe80:") || h == "::1") return true

        val octets = h.split('.')
        if (octets.size != 4) return false
        val n = octets.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false

        return when {
            n[0] == 10 -> true
            n[0] == 127 -> true
            n[0] == 192 && n[1] == 168 -> true
            n[0] == 172 && n[1] in 16..31 -> true
            n[0] == 169 && n[1] == 254 -> true
            // 100.64.0.0/10 — CGNAT, which is where a tailnet address lives.
            n[0] == 100 && n[1] in 64..127 -> true
            else -> false
        }
    }

    /**
     * Message to show when a profile would send a credential in the clear to a
     * public host, or null when the combination is safe.
     */
    fun rejectionReason(host: String, secure: Boolean): String? {
        if (secure || isPrivate(host)) return null
        return "$host is a public address. Hermes Remote won't send your " +
            "dashboard password to it over plain HTTP — turn on HTTPS, or use " +
            "a PC on your own network."
    }
}
