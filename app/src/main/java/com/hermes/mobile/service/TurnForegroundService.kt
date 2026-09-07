package com.hermes.mobile.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.hermes.mobile.HermesApplication

/**
 * Holds process importance while an agent turn is live (spec §E, Phase 3).
 * Started when a turn begins, stopped the moment it ends — nothing persistent,
 * no idle battery cost. The socket itself lives in ConnectionManager; this
 * service just stops Android from freezing the process mid-turn.
 */
class TurnForegroundService : Service() {

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Hermes is working"
                startForeground(NOTIF_ID, buildNotification(title))
            }
        }
        return START_STICKY
    }

    private fun buildNotification(title: String): Notification {
        val openIntent = Intent(this, com.hermes.mobile.ui.MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, HermesApplication.CHANNEL_SYSTEM)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText("Tap to watch the turn")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 0x484D54 // "HMT"
        private const val ACTION_STOP = "com.hermes.mobile.action.TURN_STOP"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, title: String) {
            context.startForegroundService(
                Intent(context, TurnForegroundService::class.java).putExtra(EXTRA_TITLE, title),
            )
        }

        fun stop(context: Context) {
            context.stopService(
                Intent(context, TurnForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
