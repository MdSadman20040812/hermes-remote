package com.hermes.mobile.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentTaskDao {

    @Query("SELECT * FROM agent_tasks ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE status IN ('PENDING','QUEUED','RUNNING') ORDER BY priority DESC, createdAtMillis ASC")
    suspend fun getActive(): List<AgentTaskEntity>

    @Query("SELECT * FROM agent_tasks WHERE status = 'PENDING' ORDER BY priority DESC, createdAtMillis ASC")
    suspend fun getPending(): List<AgentTaskEntity>

    @Query("SELECT * FROM agent_tasks WHERE status = 'PENDING' ORDER BY priority DESC, createdAtMillis ASC LIMIT 1")
    suspend fun getNextPending(): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    suspend fun getById(id: Long): AgentTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: AgentTaskEntity): Long

    @Update
    suspend fun update(task: AgentTaskEntity)

    @Query("UPDATE agent_tasks SET status = :status, errorMessage = :err, pcProcessedAtMillis = :ts WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, err: String? = null, ts: Long? = null)

    @Query("UPDATE agent_tasks SET status = 'COMPLETED', resultJson = :summary, resultArtifactsJson = :artifactsJson, pcProcessedAtMillis = :ts WHERE id = :id")
    suspend fun setCompleted(id: Long, summary: String, artifactsJson: String, ts: Long)

    @Query("UPDATE agent_tasks SET status = 'CANCELLED' WHERE status IN ('PENDING','QUEUED')")
    suspend fun cancelAllPending()

    @Query("SELECT COUNT(*) FROM agent_tasks WHERE status IN ('PENDING','QUEUED','RUNNING')")
    suspend fun activeCount(): Int

    @Query("SELECT COUNT(*) FROM agent_tasks WHERE status = 'COMPLETED'")
    suspend fun completedCount(): Int

    @Query("DELETE FROM agent_tasks WHERE status IN ('COMPLETED','FAILED','CANCELLED') AND createdAtMillis < :cutoffMillis")
    suspend fun pruneCompleted(cutoffMillis: Long): Int
}

@Dao
interface DriveFileDao {

    @Query("SELECT * FROM drive_files ORDER BY modifiedTimeMillis DESC")
    fun observeAll(): Flow<List<DriveFileEntity>>

    @Query("SELECT * FROM drive_files WHERE id = :id")
    suspend fun getById(id: String): DriveFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: DriveFileEntity)

    @Query("UPDATE drive_files SET isSyncedLocally = :synced, localPath = :path WHERE id = :id")
    suspend fun setSyncState(id: String, synced: Boolean, path: String? = null)

    @Query("SELECT COUNT(*) FROM drive_files WHERE isSyncedLocally = 1")
    suspend fun syncedCount(): Int

    @Query("DELETE FROM drive_files WHERE isSyncedLocally = 0")
    suspend fun deleteUnsynced()
}

@Dao
interface PendingTransferDao {

    @Query("SELECT * FROM pending_transfers ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<PendingTransferEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transfer: PendingTransferEntity): Long

    @Query("UPDATE pending_transfers SET transferredBytes = :bytes, status = :status, errorMessage = :err WHERE id = :id")
    suspend fun updateProgress(id: Long, bytes: Long, status: String, err: String? = null)

    @Query("DELETE FROM pending_transfers WHERE status IN ('COMPLETED','FAILED','CANCELLED') AND createdAtMillis < :cutoffMillis")
    suspend fun pruneCompleted(cutoffMillis: Long): Int
}

@Dao
interface SettingsDao {

    @Query("SELECT * FROM settings WHERE key = :key")
    suspend fun get(key: String): SettingEntity?

    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: SettingEntity)

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun delete(key: String)
}

@Dao
interface CachedMessageDao {

    @Query("SELECT * FROM cached_messages ORDER BY timestampMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<CachedMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(msg: CachedMessageEntity): Long

    @Query("DELETE FROM cached_messages WHERE timestampMillis < :cutoff")
    suspend fun prune(cutoff: Long): Int
}
