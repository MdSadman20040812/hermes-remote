package com.hermes.mobile.core.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hermes.mobile.HermesApplication
import com.hermes.mobile.R
import com.hermes.mobile.domain.model.TaskStatus
import com.hermes.mobile.domain.repository.DriveRepository
import com.hermes.mobile.domain.repository.MessageRepository
import com.hermes.mobile.domain.repository.SettingsRepository
import com.hermes.mobile.domain.repository.TaskRepository
import com.hermes.mobile.ui.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic background worker:
 *  1. Uploads every PENDING task to Drive HermesInbox (so the PC sees it)
 *  2. Refreshes the Drive file index → Room
 *  3. Processes HermesOutbox results → Room + message feed + notifications
 *
 * Runs every 15 min via WorkManager, plus one-shot after task submission.
 */
@HiltWorker
class HermesSyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val taskRepo: TaskRepository,
    private val driveRepo: DriveRepository,
    private val messageRepo: MessageRepository,
    private val settingsRepo: SettingsRepository
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            settingsRepo.restoreDriveSession()

            // 1) Phone → PC: pending tasks to Drive inbox
            taskRepo.uploadPendingTasksToDrive()

            // 2) Drive → Room: file index for the Files tab
            driveRepo.refreshDriveIndex()

            // 3) PC → Phone: task results, message feed, notifications
            val finished = taskRepo.processOutboxResults()
            for (task in finished) {
                messageRepo.recordTaskResult(task)
                if (task.notifyOnComplete) {
                    postCompletionNotification(task.id, task.status, task.resultSummary)
                }
                taskRepo.notifyTaskCompleted(task)
            }

            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun postCompletionNotification(taskId: Long, status: TaskStatus, summary: String?) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
        val intent = PendingIntent.getActivity(
            applicationContext, taskId.toInt(),
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val done = status == TaskStatus.COMPLETED
        val notification = NotificationCompat.Builder(applicationContext, HermesApplication.CHANNEL_TASK)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Task #$taskId ${if (done) "completed" else "failed"}")
            .setContentText(summary?.take(120) ?: "")
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        nm.notify(taskId.toInt(), notification)
    }
}
