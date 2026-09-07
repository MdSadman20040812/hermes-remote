package com.hermes.mobile.core.connection

import com.hermes.mobile.core.transport.HermesClient
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One [HermesClient] plus the cookie jar backing it.
 *
 * The jar has to be per-connection, not per-app: the gated dashboard's session
 * lives in cookies, and REST + the ws-ticket mint must ride the SAME jar or the
 * ticket call is unauthenticated. Sharing one process-wide jar across profiles
 * would also leak one PC's session onto another.
 */
class HermesConnection(
    val client: HermesClient,
    val cookies: HermesCookieJar,
)

/** New connection per attempt (each owns one socket + one cookie session). */
@Singleton
class HermesClientFactory @Inject constructor(
    private val baseClient: OkHttpClient,
) {
    fun newConnection(scope: CoroutineScope): HermesConnection {
        val jar = HermesCookieJar()
        val scoped = baseClient.newBuilder()
            .cookieJar(jar)
            .build()
        return HermesConnection(HermesClient(scoped, scope), jar)
    }

    /**
     * Cookie-less client for cheap reachability probes. `GET /api/status` is a
     * public path on the dashboard, so this needs no session.
     */
    fun newProbeClient(scope: CoroutineScope): HermesClient =
        HermesClient(baseClient, scope)

    /** Back-compat shim for callers that only need the client. */
    fun newClient(scope: CoroutineScope): HermesClient = newConnection(scope).client
}
