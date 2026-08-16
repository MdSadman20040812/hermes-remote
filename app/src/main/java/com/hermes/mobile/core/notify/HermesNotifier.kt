package com.hermes.mobile.core.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.hermes.mobile.HermesApplication
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.core.transport.HermesEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Approval + turn notifications (spec §E.1.3, Phase 3).
 *
 * approval.request is rare-but-critical (Phase 0 finding) → IMPORTANCE_HIGH,
 * Allow/Deny actions. Tapping the body deep-links into the app; tapping an
 * action resolves the turn over the socket from [ApprovalActionReceiver].
 * Turn completion posts a quiet notification on the turns channel.
 *
 * Answering in-app dismisses the matching notification via [dismissForSession].
 */
@Singleton
class HermesNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: ConnectionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nm = context.getSystemService(NotificationManager::class.java)
    private var started = false

    /** Idempotent — called from Application.onCreate via EntryPoint-free wiring in MainActivity. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            connectionManager.globalEvents.collect { event ->
                when (event) {
                    is HermesEvent.ApprovalRequest -> postApproval(event)
                    is HermesEvent.MessageComplete -> postTurnComplete(event)
                    else -> {}
                }
            }
        }
    }

    private fun notifId(sessionId: String, seed: String?) =
        (sessionId + (seed ?: "")).hashCode()

    private fun postApproval(event: HermesEvent.ApprovalRequest) {
        val id = notifId(event.sessionId, event.command)
        postedApprovalIds[event.sessionId] = id
        val title = "Hermes needs approval"
        val body = event.description ?: event.command ?: "Action requires your decision"

        val openIntent = Intent(context, com.hermes.mobile.ui.MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("hermes://session/${event.sessionId}")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, id, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun actionPi(choice: String) = PendingIntent.getBroadcast(
            context, id + choice.hashCode(),
            Intent(context, ApprovalActionReceiver::class.java).apply {
                action = ApprovalActionReceiver.ACTION_RESPOND
                putExtra(ApprovalActionReceiver.EXTRA_SESSION_ID, event.sessionId)
                putExtra(ApprovalActionReceiver.EXTRA_CHOICE, choice)
                putExtra(ApprovalActionReceiver.EXTRA_NOTIF_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, HermesApplication.CHANNEL_APPROVALS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                listOfNotNull(event.description, event.command).joinToString("\n"),
            ))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)

        // Notification actions: once (allow) + deny are the two that matter.
        if (event.choices.contains("once")) {
            builder.addAction(0, "Allow once", actionPi("once"))
        }
        if (event.choices.contains("deny")) {
            builder.addAction(0, "Deny", actionPi("deny"))
        }

        runCatching { nm.notify(id, builder.build()) }
    }

    private fun postTurnComplete(event: HermesEvent.MessageComplete) {
        if (event.status != "complete") return
        val builder = NotificationCompat.Builder(context, HermesApplication.CHANNEL_TURNS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Turn finished")
            .setContentText(event.text.take(120))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        runCatching { nm.notify(notifId(event.sessionId, "done"), builder.build()) }
    }

    /** In-app answer path: clear the matching approval notification. */
    fun dismissForSession(sessionId: String) {
        // We keyed by (sessionId + command) hash; sweep by cancelling nothing
        // specific is wrong — so track posted ids:
        postedApprovalIds[sessionId]?.let { runCatching { nm.cancel(it) } }
    }

    private val postedApprovalIds = mutableMapOf<String, Int>()
}
