package com.neethu.gouxiang.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 统一内容记录(F 工作包 F1;番外剧场模板与心动事件共用一张表):
 *
 * - 类型 [kind]:`sidestory`=番外模板产物、`heartbeat`=心动事件产物(06/09 号
 *   专项同构:心动事件复用剧场管线,只按 kind 分区展示);
 * - 模板 ID [templateKey] + 目录版本 [templateVersion](模板目录升级可据此
 *   识别旧产物);角色/会话按 [characterId]/[conversationId] 分片;
 * - 来源消息范围 [sourceStartMessageId]/[sourceEndMessageId] 与快照会话版本
 *   [sourceVersion](memory_conversation_versions;回溯 +1 即过期判据,受影响
 *   行由回溯事务标 [isStale],与心声/朋友圈 isStale 同语义);
 * - 状态机 [state]:`generating → succeeded | failed`(失败保留行,UI 可重试,
 *   重试把状态拨回 generating 再入队);失败原因 [failureReason] 展示用;
 * - 结构化文本 [bodyJson] = LLM 输出经 schema 校验后的
 *   `{"title":"..","pages":[{"title":"..","body":"..","imagePrompt":".."}]}`
 *   (未过 schema 的原始输出绝不落库);配图 [imageFile] 可选(空=纯文字),
 *   [imageStatus]:`skipped|pending|complete|failed`(文本先出、图片可选);
 * - 时间 [createdAt]/[updatedAt];[requestId] 关联 `derivative_tasks`
 *   (任务历史页可见;同 requestId 的收口幂等由 [state] 条件更新保证)。
 *
 * 心声(inner_voices)不在本表迁移:既有心声数据保持原表原样兼容,仅当
 * F/G 工作包需要统一历史时再并(本包不动)。
 */
@Entity(
    tableName = "sidestory_records",
    indices = [Index("characterId"), Index("conversationId")],
)
data class SidestoryRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 记录类型:记录 sidestory=番外模板;heartbeat=心动事件(避免魔法串散落)。 */
    val kind: String,
    /** 模板/事件 key(目录主键;未知 key 回落展示 key 本身)。 */
    val templateKey: String,
    /** 生成所用模板目录版本(目录升级后旧产物可识别)。 */
    val templateVersion: Int = 0,
    /** 展示标题(生成成功回填;生成中/失败展示模板名)。 */
    val title: String = "",
    val characterId: Long,
    val conversationId: Long,
    /** 来源消息范围(闭区间;创建时快照,生成事务复验版本)。 */
    val sourceStartMessageId: Long? = null,
    val sourceEndMessageId: Long? = null,
    /** 生成时快照的会话版本(回溯判据)。 */
    val sourceVersion: Long = 0,
    /** 任务幂等键(derivative_tasks.requestId 对齐;重试沿用同键)。 */
    val requestId: String = "",
    /** 状态机:generating | succeeded | failed。 */
    val state: String = STATE_GENERATING,
    /** 失败原因(展示用;非失败为空)。 */
    val failureReason: String = "",
    /** 结构化文本(schema 校验后的 JSON;未过校验不落库)。 */
    val bodyJson: String = "",
    /** 可选配图本地路径(空=纯文字)。 */
    val imageFile: String = "",
    /** 配图状态:skipped(无配图或开关关)| pending | complete | failed。 */
    val imageStatus: String = IMAGE_SKIPPED,
    /** 完成通知已投递(幂等门,与心声/朋友圈 notificationDone 同语义)。 */
    val notified: Boolean = false,
    /** 回溯失效标记(记录保留,详情页标注「可能过时」)。 */
    val isStale: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val isGenerating: Boolean get() = state == STATE_GENERATING
    val isFailed: Boolean get() = state == STATE_FAILED
    val isSucceeded: Boolean get() = state == STATE_SUCCEEDED

    companion object {
        const val KIND_SIDESTORY = "sidestory"
        const val KIND_HEARTBEAT = "heartbeat"

        const val STATE_GENERATING = "generating"
        const val STATE_SUCCEEDED = "succeeded"
        const val STATE_FAILED = "failed"

        const val IMAGE_SKIPPED = "skipped"
        const val IMAGE_PENDING = "pending"
        const val IMAGE_COMPLETE = "complete"
        const val IMAGE_FAILED = "failed"
    }
}

@Dao
interface SidestoryRecordDao {

    /** 会话内记录(最新在前;列表页数据源,落库即上屏)。 */
    @Query("SELECT * FROM sidestory_records WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC")
    fun observeForConversation(conversationId: Long): Flow<List<SidestoryRecordEntity>>

    @Query("SELECT * FROM sidestory_records WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC")
    suspend fun forConversation(conversationId: Long): List<SidestoryRecordEntity>

    /** 某角色的记录数(角色主页「番外剧场」尾注「全部 N」)。 */
    @Query("SELECT COUNT(*) FROM sidestory_records WHERE characterId = :characterId")
    suspend fun countForCharacter(characterId: Long): Int

    @Query("SELECT * FROM sidestory_records WHERE id = :id")
    suspend fun byId(id: Long): SidestoryRecordEntity?

    @Query("SELECT * FROM sidestory_records WHERE requestId = :requestId LIMIT 1")
    suspend fun byRequestId(requestId: String): SidestoryRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: SidestoryRecordEntity): Long

    @Query("DELETE FROM sidestory_records WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /** 生成中 → 失败(Worker 失败收口;只在 generating 态改写,终态不被覆盖)。 */
    @Query("UPDATE sidestory_records SET state = 'failed', failureReason = :reason, updatedAt = :at WHERE id = :id AND state = 'generating'")
    suspend fun markFailedIfGenerating(id: Long, reason: String, at: Long): Int

    /** 成功收口(结构化文本 + 标题;只在 generating 态改写,重复收口幂等)。 */
    @Query("UPDATE sidestory_records SET state = 'succeeded', title = :title, bodyJson = :bodyJson, failureReason = '', updatedAt = :at WHERE id = :id AND state = 'generating'")
    suspend fun markSucceededIfGenerating(id: Long, title: String, bodyJson: String, at: Long): Int

    /** 配图回填(异步落盘后;不触碰其他列)。 */
    @Query("UPDATE sidestory_records SET imageFile = :file, imageStatus = 'complete', updatedAt = :at WHERE id = :id")
    suspend fun setImageFile(id: Long, file: String, at: Long)

    @Query("UPDATE sidestory_records SET imageStatus = :status, updatedAt = :at WHERE id = :id AND state = 'succeeded'")
    suspend fun setImageStatus(id: Long, status: String, at: Long): Int

    /** 失败重试:拨回生成中并清失败原因(调用方随后重新入队)。 */
    @Query("UPDATE sidestory_records SET state = 'generating', failureReason = '', updatedAt = :at WHERE id = :id AND state = 'failed'")
    suspend fun markGeneratingForRetry(id: Long, at: Long): Int

    /** 通知幂等门(与心声 notificationDone 同语义:投递与标记尽量同事务)。 */
    @Query("UPDATE sidestory_records SET notified = 1 WHERE id = :id")
    suspend fun markNotified(id: Long)

    /** 回溯联动:来源区间命中被回溯消息的成功记录标失效(不删除不重写)。 */
    @Query("UPDATE sidestory_records SET isStale = 1 WHERE conversationId = :conversationId AND sourceEndMessageId >= :messageId AND isStale = 0")
    suspend fun markStaleFrom(conversationId: Long, messageId: Long): Int

    /** 生成中记录(重启后对账:任务已终态而记录仍 generating 的行)。 */
    @Query("SELECT * FROM sidestory_records WHERE state = 'generating'")
    suspend fun generating(): List<SidestoryRecordEntity>
}
