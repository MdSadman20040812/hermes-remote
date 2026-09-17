package com.hermes.mobile.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries hermes://session/<id> deep links from MainActivity into the shell.
 * The id is the STORED session id (state.db), resolved via session.resume.
 */
@Singleton
class DeepLinkBus @Inject constructor() {
    private val _links = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val links: SharedFlow<String> = _links.asSharedFlow()

    fun offer(storedSessionId: String) {
        _links.tryEmit(storedSessionId)
    }
}
