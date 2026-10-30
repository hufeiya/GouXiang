package com.neethu.gouxiang.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A durable account of a WorkManager execution. Does not contain prompts or secrets. */
@Entity(tableName = "derivative_tasks")
data class DerivativeTaskRecord(
    @PrimaryKey val workId: String,
    val requestId: String,
    val kind: String,
    val source: String,
    val conversationId: Long,
    val characterId: Long,
    val state: String,
    val failureReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long?,
)

@Dao
interface DerivativeTaskDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(task: DerivativeTaskRecord): Long

    @Query("UPDATE derivative_tasks SET state = :state, failureReason = :reason, updatedAt = :at, finishedAt = :finishedAt WHERE workId = :workId")
    suspend fun setState(workId: String, state: String, reason: String?, at: Long, finishedAt: Long?)

    @Query("DELETE FROM derivative_tasks WHERE workId = :workId")
    suspend fun deleteById(workId: String)

    @Query("SELECT * FROM derivative_tasks WHERE state IN ('queued', 'running')")
    suspend fun unfinished(): List<DerivativeTaskRecord>

    @Query("SELECT * FROM derivative_tasks WHERE workId = :workId")
    suspend fun byId(workId: String): DerivativeTaskRecord?

    @Query("SELECT * FROM derivative_tasks WHERE characterId = :characterId AND kind = :kind ORDER BY createdAt DESC, rowid DESC LIMIT 1")
    fun observeLatest(characterId: Long, kind: String): Flow<DerivativeTaskRecord?>
}
