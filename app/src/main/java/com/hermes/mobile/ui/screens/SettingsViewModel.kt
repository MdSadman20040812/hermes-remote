package com.hermes.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.hermes.mobile.core.work.WorkModule
import com.hermes.mobile.domain.model.ConnectionState
import com.hermes.mobile.domain.repository.DriveRepository
import com.hermes.mobile.domain.repository.SettingsRepository
import com.hermes.mobile.domain.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val driveRepo: DriveRepository,
    private val taskRepo: TaskRepository,
    private val workManager: WorkManager
) : ViewModel() {

    val isDarkTheme: StateFlow<Boolean> = settingsRepo.isDarkTheme()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val driveAccount: StateFlow<String?> = settingsRepo.observeGDriveAccount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val connectionState: StateFlow<ConnectionState> = taskRepo.observeConnectionState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionState())

    private val _syncedCount = MutableStateFlow(0)
    val syncedCount: StateFlow<Int> = _syncedCount.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    init {
        refreshSyncedCount()
    }

    fun setDarkTheme(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setDarkTheme(enabled)
    }

    fun saveTelegramBotToken(token: String, allowedUserIds: String) = viewModelScope.launch {
        if (token.isNotBlank()) {
            settingsRepo.setTelegramBotToken(token)
            settingsRepo.fetchAndCacheBotInfo()
        }
        if (allowedUserIds.isNotBlank()) settingsRepo.setAllowedUserIds(allowedUserIds)
        _userMessage.value = "Telegram settings saved"
    }

    fun getAllowedUserIds(): String = settingsRepo.getAllowedUserIds()

    fun onGoogleSignInSuccess(accountEmail: String?) = viewModelScope.launch {
        if (accountEmail.isNullOrBlank()) {
            _userMessage.value = "Sign-in failed: no account returned"
            return@launch
        }
        settingsRepo.setGDriveAccount(accountEmail)
        runCatching { settingsRepo.ensureHermesFolders() }
        WorkModule.enqueueSyncNow(workManager)
        _userMessage.value = "Drive connected as $accountEmail"
    }

    fun onGoogleSignInFailed(message: String?) {
        _userMessage.value = "Sign-in failed: ${message ?: "unknown error"}"
    }

    fun forceSync() {
        WorkModule.enqueueSyncNow(workManager)
        _userMessage.value = "Sync scheduled"
    }

    fun clearLocalCache() = viewModelScope.launch {
        driveRepo.clearLocalCache()
        refreshSyncedCount()
        _userMessage.value = "Local cache cleared"
    }

    fun signOutOfDrive() = viewModelScope.launch {
        settingsRepo.signOutOfDrive()
        _userMessage.value = "Signed out of Drive"
    }

    fun refreshSyncedCount() = viewModelScope.launch {
        _syncedCount.value = driveRepo.getSyncedCount()
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }
}
