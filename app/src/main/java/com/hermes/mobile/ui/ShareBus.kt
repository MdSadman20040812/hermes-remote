package com.hermes.mobile.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** "Send to Hermes" share-sheet payloads (Phase 4). */
@Singleton
class ShareBus @Inject constructor() {
    private val _shares = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val shares: SharedFlow<String> = _shares.asSharedFlow()

    fun offer(text: String) {
        _shares.tryEmit(text)
    }
}
