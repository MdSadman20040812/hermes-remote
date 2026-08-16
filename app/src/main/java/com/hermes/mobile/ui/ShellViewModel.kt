package com.hermes.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    val deepLinkBus: DeepLinkBus,
) : ViewModel() {

    val connState: StateFlow<ConnState> = connectionManager.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnState.Probing)

    init {
        // Cold start: race saved profiles, bind the first that answers.
        viewModelScope.launch { connectionManager.autoConnect() }
    }
}
