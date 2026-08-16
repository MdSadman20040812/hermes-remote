package com.hermes.mobile.domain.repository

import com.hermes.mobile.data.local.SecureTokenStore
import com.hermes.mobile.data.local.SettingEntity
import com.hermes.mobile.data.local.SettingsDao
import com.hermes.mobile.data.remote.DriveRemoteDataSource
import com.hermes.mobile.data.remote.TelegramRemoteDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Canonical place all app state flows through.
 *
 * Design rule:
 *  • UI observes only Flow / StateFlow from this repo
 *  • All network calls happen inside repository (single source of truth)
 *  • Local Room cache is the offline fallback
 *  • Secrets (bot token, user IDs) live in [SecureTokenStore] (encrypted)
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val settingsDao: SettingsDao,
    private val secureStore: SecureTokenStore,
    private val telegramDs: TelegramRemoteDataSource,
    private val driveDs: DriveRemoteDataSource
) {

    // ---- Telegram Bot Token (encrypted via Jetpack Security) ----
    fun setTelegramBotToken(token: String) {
        secureStore.telegramBotToken = token
    }

    fun getTelegramBotToken(): String? = secureStore.telegramBotToken

    // ---- Telegram Allowed User IDs (comma-separated, for access control) ----
    fun setAllowedUserIds(ids: String) {
        secureStore.allowedUserIds = ids
    }

    fun getAllowedUserIds(): String = secureStore.allowedUserIds

    fun getAllowedUserIdsSet(): Set<Long> =
        secureStore.allowedUserIds.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()

    fun isAllowedUser(userId: Long): Boolean {
        val allowed = getAllowedUserIdsSet()
        return allowed.isEmpty() || allowed.contains(userId)
    }

    fun requireAllowedUser(userId: Long) {
        if (!isAllowedUser(userId)) {
            throw SecurityException("Telegram user $userId is not authorized for Hermes Mobile")
        }
    }

    // ---- GDrive Account Name (the signed-in Google account) ----
    suspend fun setGDriveAccount(account: String) {
        upsert(KEY_GDRIVE_ACCOUNT, account)
        driveDs.setSignedInAccount(account)
    }

    suspend fun getGDriveAccount(): String? = read(KEY_GDRIVE_ACCOUNT)

    fun observeGDriveAccount(): Flow<String?> = observeKey(KEY_GDRIVE_ACCOUNT) { it }

    /** Restores the Drive session on process start (call once from Application/VM init). */
    suspend fun restoreDriveSession() {
        read(KEY_GDRIVE_ACCOUNT)?.takeIf { it.isNotBlank() }?.let {
            driveDs.setSignedInAccount(it)
        }
    }

    suspend fun signOutOfDrive() {
        driveDs.setSignedInAccount(null)
        settingsDao.delete(KEY_GDRIVE_ACCOUNT)
    }

    // ---- Theme (dark-first Hermes design) ----
    suspend fun setDarkTheme(isDark: Boolean) = upsert(KEY_DARK_THEME, isDark.toString())
    fun isDarkTheme(): Flow<Boolean> = observeKey(KEY_DARK_THEME) { it?.toBooleanStrictOrNull() ?: true }

    // ---- Onboarding ----
    suspend fun setOnboarded() = upsert(KEY_ONBOARDED, "true")
    fun isOnboarded(): Flow<Boolean> = observeKey(KEY_ONBOARDED) { it == "true" }

    // ---- Telegram bot info ----
    suspend fun fetchAndCacheBotInfo() {
        try {
            val me = telegramDs.getMe()
            upsert("tg_bot_username", me.username ?: "")
            upsert("tg_bot_name", listOfNotNull(me.firstName, me.lastName).joinToString(" ").trim())
        } catch (_: Exception) { /* best-effort */ }
    }

    // ---- Drive folder provisioning ----
    suspend fun ensureHermesFolders() {
        driveDs.ensureHermesFolder(DriveRemoteDataSource.HERMES_INBOX_FOLDER)
        driveDs.ensureHermesFolder(DriveRemoteDataSource.HERMES_OUTBOX_FOLDER)
        driveDs.ensureHermesFolder(DriveRemoteDataSource.HERMES_SHARED_FOLDER)
    }

    // ---- PC heartbeat (written by HermesSyncWorker when outbox shows activity) ----
    suspend fun recordPcSeen(tsMillis: Long = System.currentTimeMillis()) =
        upsert(KEY_PC_LAST_SEEN, tsMillis.toString())

    fun pcLastSeen(): Flow<Long?> = observeKey(KEY_PC_LAST_SEEN) { it?.toLongOrNull() }

    // ---- Auth status helpers ----
    fun telegramHasToken(): Flow<Boolean> = secureStore.hasToken
    fun driveHasAccount(): Flow<Boolean> =
        observeKey(KEY_GDRIVE_ACCOUNT) { !it.isNullOrBlank() }

    // ---- Internal helpers ----
    private suspend fun read(key: String): String? = settingsDao.get(key)?.value

    private fun <T> observeKey(key: String, transform: (String?) -> T): Flow<T> =
        settingsDao.observeAll().map { list -> transform(list.firstOrNull { it.key == key }?.value) }

    private suspend fun upsert(key: String, value: String) {
        settingsDao.put(
            SettingEntity(
                key = key,
                value = value,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    companion object {
        private const val KEY_GDRIVE_ACCOUNT = "gdrive_account"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_PC_LAST_SEEN = "pc_last_seen"
    }
}
