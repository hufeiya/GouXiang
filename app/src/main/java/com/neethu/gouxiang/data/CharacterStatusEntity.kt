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
 * 角色状态记录(E 工作包 E2,对齐 08-status-system.md 的状态快照语义、去商业化字段):
 *
 * 一条 = 某角色某时刻的「心情 + 正在做的事 + 内心想法」三要素快照,锚定来源
 * (会话/回合/消息区间)与生成时快照的会话版本 —— 回溯把会话版本 +1,生成中的
 * 旧候选凭版本不等拒绝落库(过期任务不回写),已落库的受影响行由回溯事务标记
 * [state]=stale(面板标记,不再作为当前状态)。
 *
 * 生成失败/网络中断不写行(最近一次有效状态自然保留);规则状态条
 * ([com.neethu.gouxiang.chat.StatusRule])只在无 active 记录时兜底(E2 口径)。
 */
@Entity(
    tableName = "character_status_records",
    indices = [Index("characterId"), Index("conversationId")],
)
data class CharacterStatusEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 关联角色(状态按角色隔离;两角色各查各的,不互串)。 */
    val characterId: Long,
    /** 生成来源会话(手动注入可为空=不锚定会话)。 */
    val conversationId: Long,
    /** 状态表情(单字素;LLM 输出非法时客户端盖章 "✨")。 */
    val emoji: String,
    /** 心情(如「开心」「有点低落」)。 */
    val mood: String,
    /** 正在做的事(第三人称短句,状态条正文)。 */
    val doing: String,
    /** 内心想法(未说出口的心里话)。 */
    val thought: String,
    /** 来源:turn=回合收尾生成 manual=调试/手动注入 rule=规则兜底(预留)。 */
    val sourceKind: String,
    /** 来源范围:锚定的会话 id。 */
    val sourceConversationId: Long? = null,
    /** 来源范围:锚定的回合 id(空=未锚定回合,如手动注入)。 */
    val sourceTurnId: String = "",
    /** 来源范围:锚定消息区间(闭区间;手动注入为 null)。 */
    val sourceStartMessageId: Long? = null,
    val sourceEndMessageId: Long? = null,
    /** 生成时快照的会话版本(memory_conversation_versions;回溯 +1 即失效判据)。 */
    val sourceVersion: Long = 0,
    /** active=生效;stale=已随回溯失效(标记保留,不回写不展示为当前状态)。 */
    val state: String = STATE_ACTIVE,
    /** 生成时间(epoch millis;面板时间轴排序键)。 */
    val generatedAt: Long,
) {
    val isActive: Boolean get() = state == STATE_ACTIVE
    val isStale: Boolean get() = state == STATE_STALE

    companion object {
        const val STATE_ACTIVE = "active"
        const val STATE_STALE = "stale"

        const val SOURCE_TURN = "turn"
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_RULE = "rule"
    }
}

@Dao
interface CharacterStatusDao {

    /** 面板近期记录(生成时间倒序,面板分页上限 [STATUS_PANEL_LIMIT] 条)。 */
    @Query(
        "SELECT * FROM character_status_records WHERE characterId = :characterId " +
            "ORDER BY generatedAt DESC, id DESC LIMIT :limit",
    )
    fun observeForCharacter(characterId: Long, limit: Int = STATUS_PANEL_LIMIT): Flow<List<CharacterStatusEntity>>

    /** 同步一次性读取(调试命令/落库后核对)。 */
    @Query(
        "SELECT * FROM character_status_records WHERE characterId = :characterId " +
            "ORDER BY generatedAt DESC, id DESC LIMIT :limit",
    )
    suspend fun forCharacter(characterId: Long, limit: Int = STATUS_PANEL_LIMIT): List<CharacterStatusEntity>

    /** 当前生效状态(最新 active 一条;null = 无记录 → 规则兜底)。 */
    @Query(
        "SELECT * FROM character_status_records WHERE characterId = :characterId AND state = 'active' " +
            "ORDER BY generatedAt DESC, id DESC LIMIT 1",
    )
    suspend fun latestActive(characterId: Long): CharacterStatusEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: CharacterStatusEntity): Long

    /**
     * 回溯联动标记:锚定区间命中被回溯消息(`sourceEndMessageId >= messageId`)
     * 的 active 记录整体转 stale(闭区间同消息回溯删除的 `id >= :id` 语义)。
     * 返回标记条数。
     */
    @Query(
        "UPDATE character_status_records SET state = 'stale' WHERE conversationId = :conversationId " +
            "AND sourceEndMessageId >= :messageId AND state = 'active'",
    )
    suspend fun markStaleFrom(conversationId: Long, messageId: Long): Int

    /** 清理某角色的失效记录(面板「清理失效记录」动作)。 */
    @Query("DELETE FROM character_status_records WHERE characterId = :characterId AND state = 'stale'")
    suspend fun clearStale(characterId: Long): Int

    companion object {
        /** 面板近期记录上限(防无限膨胀)。 */
        const val STATUS_PANEL_LIMIT = 30
    }
}
