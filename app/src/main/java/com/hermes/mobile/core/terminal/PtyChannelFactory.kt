package com.hermes.mobile.core.terminal

import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PtyChannelFactory @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    fun newChannel(scope: CoroutineScope): PtyChannel = PtyChannel(okHttpClient, scope)
}
