package com.hermes.mobile.core.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WorkModule {

    const val UNIQUE_PERIODIC_SYNC = "hermes.sync.periodic"
    const val UNIQUE_ONE_SHOT_SYNC = "hermes.sync.now"

    /**
     * WorkManager is configured once by HermesApplication (Configuration.Provider
     * with the Hilt worker factory). getInstance lazily applies that config.
     */
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext ctx: Context): WorkManager =
        WorkManager.getInstance(ctx)

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Periodic 15-minute sync: pending tasks → Drive, outbox → Room. */
    fun schedulePeriodicSync(workManager: WorkManager) {
        val request = PeriodicWorkRequestBuilder<HermesSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_SYNC,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Immediate sync after task submission / manual refresh. */
    fun enqueueSyncNow(workManager: WorkManager) {
        val request = OneTimeWorkRequestBuilder<HermesSyncWorker>()
            .setConstraints(networkConstraint)
            .build()
        workManager.enqueueUniqueWork(
            UNIQUE_ONE_SHOT_SYNC,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
