package com.hermes.mobile.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        AgentTaskEntity::class,
        DriveFileEntity::class,
        PendingTransferEntity::class,
        CachedMessageEntity::class,
        SettingEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun driveFileDao(): DriveFileDao
    abstract fun pendingTransferDao(): PendingTransferDao
    abstract fun settingsDao(): SettingsDao
    abstract fun cachedMessageDao(): CachedMessageDao
}
