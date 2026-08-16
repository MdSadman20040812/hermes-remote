package com.hermes.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.hermes.mobile.core.work.WorkModule
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class HermesApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workManager: androidx.work.WorkManager
    @Inject lateinit var settingsRepository: com.hermes.mobile.domain.repository.SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        WorkModule.schedulePeriodicSync(workManager)
        // Restore the persisted Google account into the Drive credential
        appScope.launch { settingsRepository.restoreDriveSession() }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setWorkerFactory(workerFactory)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        val transferChannel = NotificationChannel(
            CHANNEL_TRANSFER,
            "File Transfers",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active file upload and download progress"
            setShowBadge(false)
        }

        val taskChannel = NotificationChannel(
            CHANNEL_TASK,
            "Agent Tasks",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Task execution and completion notifications"
            setShowBadge(true)
        }

        val systemChannel = NotificationChannel(
            CHANNEL_SYSTEM,
            "System",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Connection, auth and system alerts"
            setShowBadge(true)
        }

        nm.createNotificationChannels(listOf(transferChannel, taskChannel, systemChannel))
    }

    companion object {
        const val CHANNEL_TRANSFER = "hermes.transfer"
        const val CHANNEL_TASK = "hermes.task"
        const val CHANNEL_SYSTEM = "hermes.system"
    }
}
