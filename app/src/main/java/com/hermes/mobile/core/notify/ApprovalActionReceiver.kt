package com.hermes.mobile.core.notify

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.hermes.mobile.core.connection.ConnectionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles Allow/Deny from an approval notification action — resolves the
 * pending approval over the live socket without opening the app (Phase 3).
 */
@AndroidEntryPoint
class ApprovalActionReceiver : BroadcastReceiver() {

    @Inject lateinit var connectionManager: ConnectionManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESPOND) return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val choice = intent.getStringExtra(EXTRA_CHOICE) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)

        val client = connectionManager.clientFlow.value
        if (client == null) {
            Toast.makeText(context, "Not connected — open Hermes Remote to answer", Toast.LENGTH_LONG).show()
            return
        }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                client.approvalRespond(sessionId, choice)
                if (notifId != 0) {
                    context.getSystemService(NotificationManager::class.java)?.cancel(notifId)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Approval response failed: ${e.message}",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RESPOND = "com.hermes.mobile.action.APPROVAL_RESPOND"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_CHOICE = "choice"
        const val EXTRA_NOTIF_ID = "notif_id"
    }
}
