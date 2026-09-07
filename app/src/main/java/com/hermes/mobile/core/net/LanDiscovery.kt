package com.hermes.mobile.core.net

import com.hermes.mobile.core.transport.HermesJson
import com.hermes.mobile.core.transport.ServerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import android.util.Log
import com.hermes.mobile.BuildConfig
import javax.inject.Singleton

/** A Hermes dashboard answering on the local network. */
data class DiscoveredPc(
    val host: String,
    val port: Int,
    val status: ServerStatus,
) {
    val label: String get() = status.hermesHome?.let { "Hermes ${status.version ?: ""}".trim() }
        ?: "Hermes ${status.version ?: ""}".trim()
}

/**
 * Finds the PC on the same Wi-Fi by sweeping the phone's own /24 for
 * `GET /api/status`.
 *
 * Why this exists: on a home router the PC's address is a DHCP lease. It moves
 * after a reboot or a lease expiry, and when it does a paired phone points at a
 * dead address forever — the user's only recovery was to re-pair from the QR.
 * A sweep is cheap (254 probes, 400 ms each, 48 at a time ≈ 2 s) and turns that
 * into a self-heal.
 *
 * `/api/status` is a public path on every bind, so this needs no credential;
 * it also means discovery cannot leak one. Nothing is auto-trusted: a
 * rediscovered PC is only bound automatically when it is the *only* Hermes on
 * the network (see ConnectionManager.rediscoverAndRebind).
 */
@Singleton
class LanDiscovery @Inject constructor(
    baseClient: OkHttpClient,
    private val networkMonitor: NetworkMonitor,
) {
    private val probeClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(PROBE_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROBE_MS * 2, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    /** The phone's own IPv4 on the current link, from the monitor or the interfaces. */
    fun localIpv4(): String? = networkMonitor.localIpv4 ?: runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /**
     * Sweep the /24 the phone sits on. Returns every dashboard that answered,
     * in address order. Safe to call on any link — returns empty when the phone
     * has no site-local IPv4 (cellular).
     */
    suspend fun sweep(port: Int = DEFAULT_PORT): List<DiscoveredPc> {
        val own = localIpv4()
        if (BuildConfig.DEBUG_MODE) Log.i(TAG, "sweep start own=" + own + " port=" + port)
        if (own == null) return emptyList()
        val prefix = own.substringBeforeLast('.', "")
        if (prefix.isBlank() || own.startsWith("127.")) return emptyList()

        val io = Semaphore(CONCURRENCY)
        return coroutineScope {
            (1..254)
                .map { "$prefix.$it" }
                .filter { it != own }
                .map { host -> async(Dispatchers.IO) { io.withPermit { probe(host, port) } } }
                .awaitAll()
                .filterNotNull()
                .also { if (BuildConfig.DEBUG_MODE) Log.i(TAG, "sweep done found=" + it.size) }
        }
    }

    /** One host. Null unless it is a Hermes dashboard. */
    suspend fun probe(host: String, port: Int = DEFAULT_PORT): DiscoveredPc? =
        withContext(Dispatchers.IO) {
            val outcome = runCatching {
                val request = Request.Builder()
                    .url("http://$host:$port/api/status")
                    .get()
                    .build()
                probeClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    // A generic 200 from some other service on 9119 is not a
                    // Hermes: the status document always carries a version and
                    // the gateway keys. Check before decoding so a future field
                    // shape can never make a real PC undiscoverable.
                    if (!body.contains("\"version\"") || !body.contains("gateway_")) return@use null
                    val status = runCatching {
                        HermesJson.decodeFromString(ServerStatus.serializer(), body)
                    }.getOrElse {
                        Log.w(TAG, "status decode failed for " + host + ", accepting anyway", it)
                        ServerStatus()
                    }
                    DiscoveredPc(host, port, status)
                }
            }
            outcome.getOrNull()
        }

    private companion object {
        const val TAG = "HermesLan"
        const val DEFAULT_PORT = 9119
        const val PROBE_MS = 400L
        const val CONCURRENCY = 48
    }
}
