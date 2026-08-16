package com.hermes.mobile.core.connection

import com.hermes.mobile.core.transport.HermesClient
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** New [HermesClient] per connection attempt (each owns one socket lifecycle). */
@Singleton
class HermesClientFactory @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    fun newClient(scope: CoroutineScope): HermesClient = HermesClient(okHttpClient, scope)
}
