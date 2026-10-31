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
 * 聊天衍生内容记录(G 工作包 G1/G2/G3;与 F 的 `sidestory_records` 同构的
 * 记录-任务模型,不并表:sidestory 是模板目录产物,本表是手帐/小说/合照三
 * 类以「用户输入 + 来源区间」为参数的产物,结构化文本形状也不同):
 *
 * - 类型 [kind]:`handbook`=手帐、`novel`=小说、`photo`=合照(对齐
 *   [com.neethu.gouxiang.chat.DerivativeKind] 的 HANDBOOK/NOVEL/PHOTO);
 * - 用户输入 [optionJson](创建时校验后快照):手帐=选中的聊天范围 preset、
 *   小说=长度档、合照=用户描述;生成中不重算,重试沿用同输入(幂等);
 * - 来源消息范围 [sourceStartMessageId]/[sourceEndMessageId] 与快照会话版本
 *   [sourceVersion](memory_conversation_versions;回溯 +1 即过期判据,受影响
 *   行由回溯事务标 [isStale],与番外/心声 isStale 同语义);
 * - 状态机 [state]:`generating → succeeded | failed`(失败保留行可重试,
 *   重试把状态拨回 generating 再入队);失败原因 [failureReason] 展示用;
 * - 结构化文本 [bodyJson] = LLM 输出经 schema 校验后的 JSON(手帐=
 *   `{"title","mood","paragraphs":[..]}`;小说=`{"title","introduction","chapters":[..]}`;
 *   合照无 LLM 文本,存用户描述与最终 prompt,未过 schema 的原始输出绝不落库);
 * - 图片 [imageFile] + [imageStatus]:手帐配图可选(`skipped|pending|complete|failed`,
 *   文本先出、图片可选);合照以图为主产物,失败整条记 failed 可重试。
 * - 时间 [createdAt]/[updatedAt];[requestId] 关联 `derivative_tasks`(任务
 *   历史页可见;同 requestId 的收口幂等由 [state] 条件更新保证)。
 *
 * 心声(inner_voices)仍保持原表(G4 在原表上补交互,迁移不丢记录)。
 */
@Entity(
    tableName = "chat_derivative_records",
    indices = [Index("characterId"), Index("conversationId")],
)
data class ChatDerivativeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 记录类型:handbook=手帐;novel=小说;photo=合照(避免魔法串散落)。 */
    val kind: String,
    /** 展示标题(生成成功回填;生成中/失败回落类型名)。 */
    val title: String = "",
    val characterId: Long,
    val conversationId: Long,
    /** 用户输入快照(校验后的 JSON;重试沿用)。 */
    val optionJson: String = "",
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
    /** 图片本地路径(手帐=可选配图;合照=主产物;空=无)。 */
    val imageFile: String = "",
    /** 图片状态:skipped(无图或开关关)| pending | complete | failed。 */
    val imageStatus: String = IMAGE_SKIPPED,
    /** 完成通知已投递(幂等门,与番外 notified 同语义)。 */
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
        const val KIND_HANDBOOK = "handbook"
        const val KIND_NOVEL = "novel"
        const val KIND_PHOTO = "photo"

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
interface ChatDerivativeDao {

    /** 会话内记录(最新在前;列表页数据源,落库即上屏)。 */
    @Query("SELECT * FROM chat_derivative_records WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC")
    fun observeForConversation(conversationId: Long): Flow<List<ChatDerivativeEntity>>

    @Query("SELECT * FROM chat_derivative_records WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC")
    suspend fun forConversation(conversationId: Long): List<ChatDerivativeEntity>

    /** 某角色某类型的记录数(角色主页入口卡尾注「全部 N」)。 */
    @Query("SELECT COUNT(*) FROM chat_derivative_records WHERE characterId = :characterId AND kind = :kind")
    suspend fun countForKind(characterId: Long, kind: String): Int

    @Query("SELECT * FROM chat_derivative_records WHERE id = :id")
    suspend fun byId(id: Long): ChatDerivativeEntity?

    @Query("SELECT * FROM chat_derivative_records WHERE requestId = :requestId LIMIT 1")
    suspend fun byRequestId(requestId: String): ChatDerivativeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ChatDerivativeEntity): Long

    @Query("DELETE FROM chat_derivative_records WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /** Rebase an unfinished record whose source predates an unrelated rewind. */
    @Query("UPDATE chat_derivative_records SET sourceVersion = :version WHERE id = :id AND state IN ('generating', 'failed')")
    suspend fun rebaseUnfinished(id: Long, version: Long): Int

    @Query("UPDATE chat_derivative_records SET sourceVersion = :version WHERE requestId IN (SELECT COALESCE(requestId, 'candidate-' || id) FROM content_candidates WHERE conversationId = :conversationId AND endMessageId < :messageId AND sourceStale = 0)")
    suspend fun rebaseUnaffectedCandidates(conversationId: Long, messageId: Long, version: Long)

    /** 生成中 → 失败(Worker 失败收口;只在 generating 态改写,终态不被覆盖)。 */
    @Query("UPDATE chat_derivative_records SET state = 'failed', failureReason = :reason, updatedAt = :at WHERE id = :id AND state = 'generating'")
    suspend fun markFailedIfGenerating(id: Long, reason: String, at: Long): Int

    /** 成功收口(结构化文本 + 标题;只在 generating 态改写,重复收口幂等)。 */
    @Query("UPDATE chat_derivative_records SET state = 'succeeded', title = :title, bodyJson = :bodyJson, failureReason = '', updatedAt = :at WHERE id = :id AND state = 'generating'")
    suspend fun markSucceededIfGenerating(id: Long, title: String, bodyJson: String, at: Long): Int

    /** 配图回填(异步落盘后;不触碰其他列)。 */
    @Query("UPDATE chat_derivative_records SET imageFile = :file, imageStatus = 'complete', updatedAt = :at WHERE id = :id")
    suspend fun setImageFile(id: Long, file: String, at: Long)

    @Query("UPDATE chat_derivative_records SET imageStatus = :status, updatedAt = :at WHERE id = :id AND state = 'succeeded'")
    suspend fun setImageStatus(id: Long, status: String, at: Long): Int

    /** 失败重试:拨回生成中并清失败原因(调用方随后重新入队)。 */
    @Query("UPDATE chat_derivative_records SET state = 'generating', failureReason = '', updatedAt = :at WHERE id = :id AND state = 'failed'")
    suspend fun markGeneratingForRetry(id: Long, at: Long): Int

    /** 通知幂等门(投递与标记尽量同事务)。 */
    @Query("UPDATE chat_derivative_records SET notified = 1 WHERE id = :id")
    suspend fun markNotified(id: Long)

    /** 回溯联动:来源区间命中被回溯消息的成功记录标失效(不删除不重写)。 */
    @Query("UPDATE chat_derivative_records SET isStale = 1 WHERE conversationId = :conversationId AND sourceEndMessageId >= :messageId AND isStale = 0")
    suspend fun markStaleFrom(conversationId: Long, messageId: Long): Int

    /** 生成中记录(重启后对账:任务已终态而记录仍 generating 的行)。 */
    @Query("SELECT * FROM chat_derivative_records WHERE state = 'generating'")
    suspend fun generating(): List<ChatDerivativeEntity>
}
