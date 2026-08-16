package com.hermes.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.domain.repository.HermesMessage
import com.hermes.mobile.domain.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MessagesViewModel @Inject constructor(
    messageRepo: MessageRepository
) : ViewModel() {

    val messages: StateFlow<List<HermesMessage>> = messageRepo.observeMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
