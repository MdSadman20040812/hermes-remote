package com.hermes.mobile.di

import android.content.Context
import androidx.room.Room
import com.hermes.mobile.BuildConfig
import com.hermes.mobile.data.local.HermesDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ---- OkHttp ----
    // Tokens must never reach logcat: the logging interceptor redacts
    // `token=`/`ticket=` query params, and release builds log nothing.
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor { message ->
            val redacted = message
                .replace(Regex("([?&](?:token|ticket)=)[^&\\s]+"), "$1•••")
            okhttp3.internal.platform.Platform.get().log(redacted)
        }.apply {
            level = if (BuildConfig.DEBUG_MODE)
                HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
            redactHeader("Authorization")
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Half-open detection on mobile network handoffs (spec §C.2).
            .pingInterval(20, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    // ---- Room (v2 derived-state cache — server is authoritative) ----
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): HermesDatabase =
        Room.databaseBuilder(ctx, HermesDatabase::class.java, "hermes-v2.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideSessionDao(db: HermesDatabase) = db.sessionDao()

    @Provides
    @Singleton
    fun provideMessageDao(db: HermesDatabase) = db.messageDao()

    @Provides
    @Singleton
    fun provideOutboxDao(db: HermesDatabase) = db.outboxDao()
}
