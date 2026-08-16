package com.hermes.mobile.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.hermes.mobile.core.work.WorkModule
import com.hermes.mobile.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val workManager: WorkManager
) : ViewModel() {

    val isDarkTheme: StateFlow<Boolean> = settingsRepo.isDarkTheme()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val isOnboarded: StateFlow<Boolean> = settingsRepo.isOnboarded()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val telegramHasToken: StateFlow<Boolean> = settingsRepo.telegramHasToken()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val driveAccount: StateFlow<String?> = settingsRepo.observeGDriveAccount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _authMessage = MutableStateFlow<String?>(null)
    val authMessage: StateFlow<String?> = _authMessage.asStateFlow()

    init {
        // Restore a persisted Drive session into the singleton credential
        viewModelScope.launch { settingsRepo.restoreDriveSession() }
    }

    fun toggleTheme() = viewModelScope.launch {
        settingsRepo.setDarkTheme(!isDarkTheme.value)
    }

    fun setOnboarded() = viewModelScope.launch {
        settingsRepo.setOnboarded()
    }

    fun saveTelegramBotToken(token: String, allowedUserIds: String = "") = viewModelScope.launch {
        settingsRepo.setTelegramBotToken(token)
        if (allowedUserIds.isNotBlank()) settingsRepo.setAllowedUserIds(allowedUserIds)
        // Validate + cache bot identity in the background
        settingsRepo.fetchAndCacheBotInfo()
        _authMessage.value = "Bot token saved"
    }

    /** Called after GoogleSignIn succeeds with the chosen account email. */
    fun onGoogleSignInSuccess(accountEmail: String?) {
        if (accountEmail.isNullOrBlank()) {
            _authMessage.value = "Sign-in failed: no account returned"
            return
        }
        viewModelScope.launch {
            runCatching {
                settingsRepo.setGDriveAccount(accountEmail)
                settingsRepo.ensureHermesFolders()
            }.onSuccess {
                _authMessage.value = "Drive connected as $accountEmail"
                WorkModule.enqueueSyncNow(workManager)
            }.onFailure {
                _authMessage.value = "Drive folders will be created on first sync"
            }
        }
    }

    fun onGoogleSignInFailed(message: String?) {
        _authMessage.value = "Sign-in failed: ${message ?: "unknown error"}"
    }

    fun clearAuthMessage() {
        _authMessage.value = null
    }
}
