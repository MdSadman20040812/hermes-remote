package com.hermes.mobile.ui

import android.net.Uri
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

    /**
     * Files shared into Hermes from any other app.
     *
     * Kept separate from [shares] because the destinations differ: text becomes
     * a prompt in the cockpit, whereas a file has to be uploaded before it
     * means anything. Replay is 1 so a share that arrives during a cold start
     * still reaches the collector once the UI is composed - otherwise sharing
     * into a not-yet-running app silently drops the file.
     */
    private val _files = MutableSharedFlow<List<Uri>>(replay = 1, extraBufferCapacity = 4)
    val files: SharedFlow<List<Uri>> = _files.asSharedFlow()

    fun offer(text: String) {
        _shares.tryEmit(text)
    }

    fun offerFiles(uris: List<Uri>) {
        if (uris.isNotEmpty()) _files.tryEmit(uris)
    }

    /** Consumed by the collector so a rotation does not re-upload. */
    fun clearFiles() {
        _files.resetReplayCache()
    }
}
