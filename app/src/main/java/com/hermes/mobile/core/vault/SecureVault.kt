package com.hermes.mobile.core.vault

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hermes.mobile.core.connection.ConnectionProfile
import com.hermes.mobile.core.transport.HermesJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Credential vault — connection profiles and their secrets.
 *
 * Backed by EncryptedSharedPreferences (AES-256 SIV keys / AES-256 GCM values,
 * master key in Android Keystore, StrongBox when the device has one).
 * Profile metadata and secrets are both stored here: the metadata is not
 * sensitive and this keeps one obvious place to audit. Never log contents.
 */
@Singleton
class SecureVault @Inject constructor(
    @ApplicationContext context: Context,
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
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<ConnectionProfile>> = _profiles.asStateFlow()

    fun saveProfile(profile: ConnectionProfile, secret: String) {
        val updated = (_profiles.value.filterNot { it.id == profile.id } + profile)
        prefs.edit()
            .putString(KEY_PROFILES, HermesJson.encodeToString(ListSerializer(ConnectionProfile.serializer()), updated))
            .putString(secretKey(profile.id), secret)
            .apply()
        _profiles.value = updated
    }

    fun removeProfile(id: String) {
        val updated = _profiles.value.filterNot { it.id == id }
        prefs.edit()
            .putString(KEY_PROFILES, HermesJson.encodeToString(ListSerializer(ConnectionProfile.serializer()), updated))
            .remove(secretKey(id))
            .apply()
        _profiles.value = updated
    }

    fun secretFor(profileId: String): String? =
        prefs.getString(secretKey(profileId), null)?.takeIf { it.isNotBlank() }

    fun markSeen(id: String, atMs: Long = System.currentTimeMillis()) {
        val updated = _profiles.value.map { if (it.id == id) it.copy(lastSeenAt = atMs) else it }
        prefs.edit()
            .putString(KEY_PROFILES, HermesJson.encodeToString(ListSerializer(ConnectionProfile.serializer()), updated))
            .apply()
        _profiles.value = updated
    }

    private fun loadProfiles(): List<ConnectionProfile> =
        prefs.getString(KEY_PROFILES, null)?.let { raw ->
            runCatching {
                HermesJson.decodeFromString(ListSerializer(ConnectionProfile.serializer()), raw)
            }.getOrNull()
        } ?: emptyList()

    private fun secretKey(profileId: String) = "secret_$profileId"

    private companion object {
        const val PREFS_FILE = "hermes_vault"
        const val KEY_PROFILES = "connection_profiles"
    }
}
