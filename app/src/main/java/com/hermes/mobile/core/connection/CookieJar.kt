package com.hermes.mobile.core.connection

import okhttp3.Cookie
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-connection in-memory cookie store.
 *
 * The gated dashboard authorises REST with the `hermes_session_*` cookies that
 * `POST /auth/password-login` sets, and `POST /api/auth/ws-ticket` only works
 * on a request that carries them. OkHttp drops Set-Cookie entirely unless a
 * jar is installed, which is why the login "succeeded" but every later call
 * came back 401.
 *
 * Deliberately NOT named `CookieJar` — that shadowed [okhttp3.CookieJar] and
 * made `.cookieJar(...)` unresolvable. It implements the OkHttp interface by
 * its fully-qualified name.
 *
 * Memory-only by design: session cookies must not outlive the process, and the
 * long-lived credential already lives in the encrypted vault.
 */
class HermesCookieJar : okhttp3.CookieJar {

    private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val forHost = store.getOrPut(url.host) { ConcurrentHashMap() }
        val now = System.currentTimeMillis()
        for (cookie in cookies) {
            if (cookie.expiresAt <= now) {
                forHost.remove(cookie.name)
            } else {
                forHost[cookie.name] = cookie
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val forHost = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        val expired = ArrayList<String>()
        val live = ArrayList<Cookie>(forHost.size)
        for (cookie in forHost.values) {
            if (cookie.expiresAt <= now) {
                expired.add(cookie.name)
            } else if (cookie.matches(url)) {
                live.add(cookie)
            }
        }
        expired.forEach { forHost.remove(it) }
        return live
    }

    /** True once a dashboard session cookie is held for [host]. */
    fun hasSessionFor(host: String): Boolean =
        store[host]?.keys?.any { it.startsWith(SESSION_COOKIE_PREFIX) } == true

    /** Drop everything — used when a login is retried after a 401. */
    fun clear() = store.clear()

    private companion object {
        /** `hermes_session_at` / `_rt` / `_provider` — see dashboard_auth/cookies.py. */
        const val SESSION_COOKIE_PREFIX = "hermes_session"
    }
}
