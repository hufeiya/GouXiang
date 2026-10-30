package com.neethu.gouxiang.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * 主动消息账本 DAO(D 工作包 D1)。行级查询收在这里,事务边界收在
 * [ProactiveLedger](data/ProactiveLedger.kt)——本接口不做跨表/跨行组合写。
 */
@Dao
interface ProactiveDao {

    // ── outbox(批次行)────────────────────────────────────────────

    /** 某会话全部批次行(按插入序,时间正序)。 */
    @Query("SELECT * FROM proactive_outbox WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun forConversation(conversationId: Long): List<ProactiveOutboxEntity>

    /** 会话在途批次(pending/generating 各至多一行,预约门禁读它)。 */
    @Query(
        "SELECT * FROM proactive_outbox WHERE conversationId = :conversationId " +
            "AND state IN ('PENDING','GENERATING') ORDER BY id DESC LIMIT 1",
    )
    suspend fun inFlightFor(conversationId: Long): ProactiveOutboxEntity?

    /** 全库在途批次(租约恢复扫描用)。 */
    @Query("SELECT * FROM proactive_outbox WHERE state IN ('PENDING','GENERATING')")
    suspend fun inFlight(): List<ProactiveOutboxEntity>

    /** 按 requestId 取(幂等键直查)。 */
    @Query("SELECT * FROM proactive_outbox WHERE requestId = :requestId LIMIT 1")
    suspend fun byRequestId(requestId: String): ProactiveOutboxEntity?

    /**
     * 预约插入(IGNORE:requestId 唯一索引冲突 = 同请求已预约,返回 -1)。
     * 会话级「同时至多一个在途批次」的门禁由调用方在事务内查 [inFlightFor] 把守。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ProactiveOutboxEntity): Long

    @Update
    suspend fun update(row: ProactiveOutboxEntity)

    /** 生成收口:只在在途态生效(cancelled/终态行不被晚到的生成结果复活)。 */
    @Query(
        "UPDATE proactive_outbox SET turnId = :turnId, messageIds = :messageIds, count = :count, " +
            "state = :state, failureReason = :failureReason, updatedAt = :at " +
            "WHERE requestId = :requestId AND state IN ('PENDING','GENERATING')",
    )
    suspend fun finalize(
        requestId: String,
        state: String,
        turnId: String,
        messageIds: String,
        count: Int,
        failureReason: String,
        at: Long,
    ): Int

    /** 通知投递幂等门(0→1)。 */
    @Query("UPDATE proactive_outbox SET notified = 1, updatedAt = :at WHERE requestId = :requestId AND notified = 0")
    suspend fun markNotified(requestId: String, at: Long): Int

    /** 单批消费(前台进页生成即读场景)。 */
    @Query("UPDATE proactive_outbox SET seen = 1, seenAt = :at, updatedAt = :at WHERE requestId = :requestId AND seen = 0")
    suspend fun markSeen(requestId: String, at: Long): Int

    /** 会话整批消费(进页已读)。 */
    @Query("UPDATE proactive_outbox SET seen = 1, seenAt = :at, updatedAt = :at WHERE conversationId = :conversationId AND seen = 0 AND state IN ('READY','FALLBACK')")
    suspend fun markConversationSeen(conversationId: Long, at: Long): Int

    // ── 会话状态行 ────────────────────────────────────────────────

    @Query("SELECT * FROM proactive_conversation_state WHERE conversationId = :conversationId LIMIT 1")
    suspend fun stateFor(conversationId: Long): ProactiveConversationStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: ProactiveConversationStateEntity)

    /** 未读行(回归展示与未读重算)。 */
    @Query("SELECT * FROM proactive_outbox WHERE conversationId = :conversationId AND seen = 0 AND state IN ('READY','FALLBACK') ORDER BY id ASC")
    suspend fun unseenRows(conversationId: Long): List<ProactiveOutboxEntity>

    /** 未读条数合计(单查询,免全行加载)。 */
    @Query("SELECT IFNULL(SUM(count), 0) FROM proactive_outbox WHERE conversationId = :conversationId AND seen = 0 AND state IN ('READY','FALLBACK')")
    suspend fun unseenTotal(conversationId: Long): Int
}
