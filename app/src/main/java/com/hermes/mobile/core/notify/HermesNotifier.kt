package com.hermes.mobile.core.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.hermes.mobile.HermesApplication
import com.hermes.mobile.R
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.core.transport.HermesEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Approval + turn notifications (spec §E.1.3, Phase 3).
 *
 * approval.request is rare-but-critical (Phase 0 finding) → IMPORTANCE_HIGH,
 * with Allow/Deny actions so the common case never needs the app opened.
 * Tapping the body deep-links into the session; tapping an action resolves the
 * turn over the socket from [ApprovalActionReceiver].
 *
 * Turn completion is quieter, and is suppressed entirely while the app is in
 * the foreground: telling someone a turn finished when they are watching it
 * finish is how a useful channel gets muted.
 */
@Singleton
class HermesNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: ConnectionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nm = context.getSystemService(NotificationManager::class.java)
    private var started = false

    /** Notification ids we posted, per session, so an in-app answer can clear them. */
    private val postedApprovalIds = ConcurrentHashMap<String, Int>()

    /** Set by the Activity: notifications for a visible app are noise. */
    @Volatile
    var appInForeground: Boolean = false

    /** Idempotent — called from Application.onCreate. */
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

    private fun notifId(sessionId: String, seed: String?) = (sessionId + (seed ?: "")).hashCode()

    private fun postApproval(event: HermesEvent.ApprovalRequest) {
        val id = notifId(event.sessionId, event.command)
        postedApprovalIds[event.sessionId] = id
        val body = event.description ?: event.command ?: "An action needs your decision"

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
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle("Hermes needs approval")
            .setContentText(body)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    listOfNotNull(event.description, event.command).joinToString("\n\n"),
                ),
            )
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)

        // Offer only choices the server actually accepts for this request.
        val choices = event.choices.ifEmpty { listOf("once", "deny") }
        choices.firstOrNull { it.equals("once", true) || it.equals("yes", true) }?.let {
            builder.addAction(0, "Allow once", actionPi(it))
        }
        choices.firstOrNull { it.equals("deny", true) || it.equals("no", true) }?.let {
            builder.addAction(0, "Deny", actionPi(it))
        }

        runCatching { nm.notify(id, builder.build()) }
    }

    private fun postTurnComplete(event: HermesEvent.MessageComplete) {
        if (appInForeground) return
        if (event.status != null && event.status != "complete") return
        val builder = NotificationCompat.Builder(context, HermesApplication.CHANNEL_TURNS)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle("Turn finished")
            .setContentText(event.text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.text.take(600)))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    notifId(event.sessionId, "done"),
                    Intent(context, com.hermes.mobile.ui.MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = android.net.Uri.parse("hermes://session/${event.sessionId}")
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        runCatching { nm.notify(notifId(event.sessionId, "done"), builder.build()) }
    }

    /** In-app answer path: clear the matching approval notification. */
    fun dismissForSession(sessionId: String) {
        postedApprovalIds.remove(sessionId)?.let { runCatching { nm.cancel(it) } }
    }
}
