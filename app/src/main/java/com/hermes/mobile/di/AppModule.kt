package com.hermes.mobile.di

import com.hermes.mobile.BuildConfig
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.core.connection.HermesClientFactory
import com.hermes.mobile.core.vault.SecureVault
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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

    @Provides
    @Singleton
    fun provideConnectionManager(
        vault: SecureVault,
        clientFactory: HermesClientFactory,
    ): ConnectionManager = ConnectionManager(vault, clientFactory)
}
