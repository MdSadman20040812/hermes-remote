package com.hermes.mobile.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted store for secrets (Telegram bot token, allowed user IDs).
 *
 * Backed by EncryptedSharedPreferences (AES-256 SIV key / AES-256 GCM value,
 * master key in Android Keystore). Plaintext secrets never touch Room,
 * DataStore, backups, or logs.
 */
@Singleton
class SecureTokenStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _hasToken = MutableStateFlow(telegramBotToken != null)
    val hasToken: StateFlow<Boolean> = _hasToken.asStateFlow()

    var telegramBotToken: String?
        get() = prefs.getString(KEY_TG_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_TG_TOKEN, value?.trim()).apply()
            _hasToken.value = value != null
        }

    var allowedUserIds: String
        get() = prefs.getString(KEY_ALLOWED_IDS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ALLOWED_IDS, value.trim()).apply()

    fun clear() {
        prefs.edit().clear().apply()
        _hasToken.value = false
    }

    private companion object {
        const val PREFS_FILE = "hermes_secure_prefs"
        const val KEY_TG_TOKEN = "tg_bot_token"
        const val KEY_ALLOWED_IDS = "tg_allowed_user_ids"
    }
}
