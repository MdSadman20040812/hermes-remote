package com.hermes.mobile.ui.home

import androidx.lifecycle.ViewModel
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.core.transport.ChannelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    val connState: StateFlow<ConnState> = connectionManager.state
    val channelState: StateFlow<ChannelState> = connectionManager.channelState

    fun disconnect() {
        val current = connectionManager.state.value
        val profile = (current as? ConnState.Connected)?.profile
        if (profile != null) connectionManager.forgetProfile(profile.id)
    }
}
