package com.neethu.gouxiang.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** Durable request-to-result association; inserted atomically with its content. */
@Entity(tableName = "derivative_results")
data class DerivativeResultEntity(
    @PrimaryKey val requestId: String,
    val kind: String,
    val characterId: Long,
    val resultIds: String,
    val imageStatus: String = "pending",
    val sourceConversationId: Long = 0,
    val sourceStartMessageId: Long? = null,
    val sourceEndMessageId: Long? = null,
    val sourceVersion: Long = 0,
    val notificationDone: Boolean = false,
)

@Dao
interface DerivativeResultDao {
    /** 对账已终止但仍等待配图的结果；存在其他活动请求时不能抢先标失败。 */
    @Query("UPDATE derivative_results SET imageStatus = 'failed' WHERE imageStatus = 'pending' AND EXISTS (SELECT 1 FROM derivative_tasks t WHERE t.requestId = derivative_results.requestId AND t.state IN ('failed', 'cancelled', 'skipped', 'succeeded')) AND NOT EXISTS (SELECT 1 FROM derivative_tasks t WHERE t.requestId = derivative_results.requestId AND t.state IN ('queued', 'running'))")
    suspend fun recoverInterruptedImages(): Int

    @Query("SELECT * FROM derivative_tasks WHERE characterId = :characterId AND kind = :kind")
    fun observeImageTasks(characterId: Long, kind: String): kotlinx.coroutines.flow.Flow<List<DerivativeTaskRecord>>

    @Query("SELECT * FROM derivative_results WHERE characterId = :characterId AND kind = :kind ORDER BY rowid DESC")
    fun observeForCharacter(characterId: Long, kind: String): kotlinx.coroutines.flow.Flow<List<DerivativeResultEntity>>

    @Query("SELECT * FROM derivative_results WHERE requestId = :requestId")
    suspend fun byRequestId(requestId: String): DerivativeResultEntity?

    @Insert
    suspend fun insert(result: DerivativeResultEntity)

    @Query("UPDATE derivative_results SET imageStatus = :status WHERE requestId = :requestId")
    suspend fun setImageStatus(requestId: String, status: String)

    @Query("UPDATE moments SET isStale = 1 WHERE id IN (SELECT m.id FROM moments m JOIN derivative_results d ON instr(',' || d.resultIds || ',', ',' || m.id || ',') > 0 WHERE d.kind = 'MOMENT' AND d.sourceConversationId = :conversationId AND d.sourceEndMessageId >= :messageId)")
    suspend fun markMomentsStale(conversationId: Long, messageId: Long)

    @Query("UPDATE inner_voices SET isStale = 1 WHERE id IN (SELECT v.id FROM inner_voices v JOIN derivative_results d ON instr(',' || d.resultIds || ',', ',' || v.id || ',') > 0 WHERE d.kind = 'INNER_VOICE' AND d.sourceConversationId = :conversationId AND d.sourceEndMessageId >= :messageId)")
    suspend fun markInnerVoicesStale(conversationId: Long, messageId: Long)

    @Query("UPDATE derivative_results SET notificationDone = 1 WHERE requestId = :requestId")
    suspend fun markNotificationDone(requestId: String)
}
