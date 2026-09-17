package com.hermes.mobile.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.hermes.mobile.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

/** What kind of link the phone is on. Wi-Fi is the only one that can reach a LAN PC. */
enum class LinkKind { NONE, WIFI, ETHERNET, CELLULAR, OTHER }

/**
 * Live view of the phone's connectivity.
 *
 * The reconnect loop used to be purely time-based: after a Wi-Fi drop it sat in
 * exponential backoff even once the phone was back on the network. Every real
 * "the app says reconnecting but my PC is right there" report traces to that.
 * [changes] fires on every link transition so [com.hermes.mobile.core.connection.ConnectionManager]
 * can retry immediately instead of waiting out a 15 s sleep.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _link = MutableStateFlow(LinkKind.NONE)
    val link: StateFlow<LinkKind> = _link.asStateFlow()

    /** Emits on every network gain/loss. Conflated: only the latest matters. */
    private val _changes = MutableSharedFlow<LinkKind>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<LinkKind> = _changes.asSharedFlow()

    /** IPv4 the phone holds on the current link, or null when offline. */
    @Volatile
    var localIpv4: String? = null
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh(network)

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            refresh(network, caps)

        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) {
            localIpv4 = props.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
            refresh(network)
        }

        override fun onLost(network: Network) {
            if (cm?.activeNetwork == null) {
                localIpv4 = null
                emit(LinkKind.NONE)
            } else {
                refresh(cm.activeNetwork!!)
            }
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm?.registerNetworkCallback(request, callback) }
        cm?.activeNetwork?.let { refresh(it) }
    }

    private fun refresh(network: Network, caps: NetworkCapabilities? = null) {
        val c = caps ?: cm?.getNetworkCapabilities(network)
        val kind = when {
            c == null -> LinkKind.NONE
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LinkKind.WIFI
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> LinkKind.ETHERNET
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> LinkKind.CELLULAR
            else -> LinkKind.OTHER
        }
        if (localIpv4 == null) {
            localIpv4 = cm?.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }
        emit(kind)
    }

    private fun emit(kind: LinkKind) {
        val previous = _link.value
        if (BuildConfig.DEBUG_MODE && previous != kind) {
            Log.i("HermesNet", "link=" + kind + " ip=" + localIpv4)
        }
        _link.value = kind
        if (previous != kind) _changes.tryEmit(kind)
    }

    /** True when the phone has a link that could plausibly reach a LAN PC. */
    val onLocalNetwork: Boolean
        get() = _link.value == LinkKind.WIFI || _link.value == LinkKind.ETHERNET
}
