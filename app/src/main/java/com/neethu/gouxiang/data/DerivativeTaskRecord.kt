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
    /** M0 收尾:失败原因分类([com.neethu.gouxiang.chat.DerivativeFailureKind.name]);非失败/历史行为 null。 */
    val failureKind: String? = null,
)

/**
 * 任务历史页一行(任务表 LEFT JOIN 结果表):结果 ID 由 `derivative_results`
 * 按 requestId 关联读出,不在任务表冗余(同一 requestId 只有一条结果行)。
 */
data class DerivativeTaskHistoryRow(
    val workId: String,
    val requestId: String,
    val kind: String,
    val source: String,
    val conversationId: Long,
    val characterId: Long,
    val state: String,
    val failureReason: String?,
    val failureKind: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long?,
    val resultIds: String?,
)

@Dao
interface DerivativeTaskDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(task: DerivativeTaskRecord): Long

    @Query("UPDATE derivative_tasks SET state = :state, failureReason = :reason, updatedAt = :at, finishedAt = :finishedAt WHERE workId = :workId")
    suspend fun setState(workId: String, state: String, reason: String?, at: Long, finishedAt: Long?)

    /** 失败收口:状态 + 原因 + 分类一并落库(历史行据此展示分类)。 */
    @Query("UPDATE derivative_tasks SET state = :state, failureReason = :reason, failureKind = :failureKind, updatedAt = :at, finishedAt = :finishedAt WHERE workId = :workId")
    suspend fun setStateWithKind(workId: String, state: String, reason: String?, failureKind: String?, at: Long, finishedAt: Long?)

    @Query("DELETE FROM derivative_tasks WHERE workId = :workId")
    suspend fun deleteById(workId: String)

    /**
     * 取消收口(任务历史页「取消」):条件更新——只有仍在排队/进行中的行会被
     * 标记,终态行(成功/失败/已取消/已跳过)不被改写;返回受影响行数供 UI 判断。
     */
    @Query("UPDATE derivative_tasks SET state = 'cancelled', failureReason = :reason, failureKind = :failureKind, updatedAt = :at, finishedAt = :at WHERE workId = :workId AND state IN ('queued', 'running')")
    suspend fun cancelIfActive(workId: String, reason: String, failureKind: String?, at: Long): Int

    @Query("SELECT * FROM derivative_tasks WHERE state IN ('queued', 'running')")
    suspend fun unfinished(): List<DerivativeTaskRecord>

    @Query("SELECT * FROM derivative_tasks WHERE workId = :workId")
    suspend fun byId(workId: String): DerivativeTaskRecord?

    /** 按请求 ID 查最近一条任务(番外/心动重启对账:记录行 generating 的关联面)。 */
    @Query("SELECT * FROM derivative_tasks WHERE requestId = :requestId ORDER BY createdAt DESC, rowid DESC LIMIT 1")
    suspend fun byRequestId(requestId: String): DerivativeTaskRecord?

    @Query("SELECT * FROM derivative_tasks WHERE characterId = :characterId AND kind = :kind ORDER BY createdAt DESC, rowid DESC LIMIT 1")
    fun observeLatest(characterId: Long, kind: String): Flow<DerivativeTaskRecord?>

    /** 任务历史(任务历史页;最新在前,含关联结果 ID)。 */
    @Query(
        "SELECT t.workId AS workId, t.requestId AS requestId, t.kind AS kind, t.source AS source, " +
            "t.conversationId AS conversationId, t.characterId AS characterId, t.state AS state, " +
            "t.failureReason AS failureReason, t.failureKind AS failureKind, t.createdAt AS createdAt, " +
            "t.updatedAt AS updatedAt, t.finishedAt AS finishedAt, r.resultIds AS resultIds " +
            "FROM derivative_tasks t LEFT JOIN derivative_results r ON r.requestId = t.requestId " +
            "ORDER BY t.createdAt DESC, t.rowid DESC LIMIT :limit",
    )
    fun observeHistory(limit: Int): Flow<List<DerivativeTaskHistoryRow>>
}
