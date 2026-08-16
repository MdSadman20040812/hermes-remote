package com.hermes.mobile.di

import android.content.Context
import androidx.room.Room
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
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

    // ---- Room ----
    @Provides @Singleton
    fun provideRoomDatabase(@ApplicationContext ctx: Context): HermesDatabase =
        Room.databaseBuilder(ctx, HermesDatabase::class.java, "hermes.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides @Singleton fun provideAgentTaskDao(db: HermesDatabase) = db.agentTaskDao()
    @Provides @Singleton fun provideDriveFileDao(db: HermesDatabase) = db.driveFileDao()
    @Provides @Singleton fun providePendingTransferDao(db: HermesDatabase) = db.pendingTransferDao()
    @Provides @Singleton fun provideSettingsDao(db: HermesDatabase) = db.settingsDao()
    @Provides @Singleton fun provideCachedMessageDao(db: HermesDatabase) = db.cachedMessageDao()

    // ---- OkHttp ----
    @Provides @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG_MODE)
                HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor { chain ->
                if (!chain.request().url.toString().startsWith("https://")) {
                    throw SecurityException("Cleartext HTTP is disabled for HermesMobile")
                }
                chain.proceed(chain.request())
            }
            .build()
    }

    // ---- Drive credential (singleton so sign-in state is shared app-wide) ----
    @Provides @Singleton
    fun provideDriveCredential(@ApplicationContext ctx: Context): GoogleAccountCredential =
        GoogleAccountCredential.usingOAuth2(ctx, listOf(DriveScopes.DRIVE_FILE))

    // ---- Google Sign-In options for the auth screens ----
    @Provides @Singleton
    fun provideGoogleSignInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
}
