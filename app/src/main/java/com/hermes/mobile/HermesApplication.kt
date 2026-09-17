package com.hermes.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.hermes.mobile.core.notify.HermesNotifier
import com.hermes.mobile.data.repo.OutboxRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HermesApplication : Application() {

    @Inject lateinit var notifier: HermesNotifier
    @Inject lateinit var outbox: OutboxRepository


    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Approval/turn notifications + offline outbox flush, app-scoped.
        notifier.start()
        outbox.start()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        // Approval requests: rare-but-critical (Phase 0 finding) — max urgency.
        val approvals = NotificationChannel(
            CHANNEL_APPROVALS,
            "Approvals",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Hermes is blocked waiting for your Allow/Deny"
            setShowBadge(true)
        }

        val turns = NotificationChannel(
            CHANNEL_TURNS,
            "Agent turns",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Turn completion and status"
            setShowBadge(true)
        }

        val system = NotificationChannel(
            CHANNEL_SYSTEM,
            "Connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Connection state alerts"
            setShowBadge(false)
        }

        nm.createNotificationChannels(listOf(approvals, turns, system))
    }

    companion object {
        const val CHANNEL_APPROVALS = "hermes.approvals"
        const val CHANNEL_TURNS = "hermes.turns"
        const val CHANNEL_SYSTEM = "hermes.system"
    }
}
