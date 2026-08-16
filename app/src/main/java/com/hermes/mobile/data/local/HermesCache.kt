package com.hermes.mobile.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * v2 cache schema (spec §C.6). DERIVED STATE ONLY — the server's state.db is
 * authoritative; this exists so the cockpit has something to show in the
 * 200 ms before session.history answers, and so the session list opens
 * instantly. Reconciled from the server on every connect.
 */

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String, // stored_session_id
    val title: String,
    val preview: String,
    val startedAt: Double,
    val messageCount: Int,
    val source: String,
    val cachedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: String,
    val ordinal: Int,
    val role: String, // user | assistant | tool
    val content: String,
    val cachedAt: Long = System.currentTimeMillis(),
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<SessionEntity>)

    @Query("DELETE FROM sessions")
    suspend fun clear()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY ordinal ASC")
    suspend fun forSession(sessionId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)
}

/** Offline outbox (spec §E.1.5, Phase 3): prompts composed while disconnected. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: String, // live handle; empty means "create a session first"
    val text: String,
    val queuedAt: Long = System.currentTimeMillis(),
)

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY queuedAt ASC")
    suspend fun pending(): List<OutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: OutboxEntity): Long

    @Query("DELETE FROM outbox WHERE rowId = :rowId")
    suspend fun delete(rowId: Long)

    @Query("SELECT COUNT(*) FROM outbox")
    fun count(): Flow<Int>
}

@Database(
    entities = [SessionEntity::class, MessageEntity::class, OutboxEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao
}
