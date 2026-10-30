package com.neethu.gouxiang.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 记忆表(对齐 03-memory-upgrade.md §1.2 猫箱 BotMemoryData 两类记忆:
 * 用户画像 UserName/UserProfile + 事件 Event)。
 *
 * 分带说明:容量渐忘(旧记忆降权为「逐渐模糊」)不落库——库内永远全量,
 * 检索/渲染时按 [MemoryCapacity] 现场打带(03 §3.2a:门禁在容量计算侧,
 * 与猫箱「服务端算容量、客户端只渲染」口径一致,容量调整即时生效)。
 */
@Entity(
    tableName = "memories",
    indices = [Index("characterId")],
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 关联 [CharacterEntity.id](按角色分片,换卡不泄漏)。 */
    val characterId: Long,
    /** profile(我的称呼/身份)/ relationship(关系状态)/ event(经历过的事)。 */
    val category: String,
    /**
     * 画像细类:category=profile 时 "name"(我的称呼)|"identity"(我的身份);
     * 其余分类恒空串。ProfileSheet 依此原地覆盖同名画像条,不重复入库。
     */
    val subtype: String = "",
    /** 记忆内容。 */
    val content: String,
    /** 重要性(常驻槽判定用,1~10;自动记忆默认 5)。 */
    val importance: Int = 5,
    /** 时间(epoch millis;事件区按此倒序)。 */
    val at: Long = 0,
    val sourceConversationId: Long? = null,
    val sourceEndMessageId: Long? = null,
)

/**
 * 待提炼窗口的持久化行(单行表):force-stop 不丢、跨进程连续计数。
 * 03 §3.2c 触发与快照语义的数据底座。
 */
@Entity(tableName = "pending_memory_window")
data class PendingMemoryWindowEntity(
    @PrimaryKey val id: Int = 0,
    /** characterId|turn 分隔的序列化回合(user|assistant 各一行)。 */
    val payload: String,
)

/**
 * 记忆窗口的单回合(用户原文 + 助手合并文本),与持久化行互转。
 */
data class MemoryTurn(val user: String, val assistant: String)

/** One durable turn; each extraction channel acknowledges it independently. */
@Entity(tableName = "memory_turns", indices = [Index("conversationId"), Index(value = ["conversationId", "turnId"], unique = true)])
data class PendingMemoryTurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val characterId: Long,
    val turnId: String,
    val startMessageId: Long,
    val endMessageId: Long,
    val user: String,
    val assistant: String,
    val completed: Boolean = false,
    val profileDone: Boolean = false,
    val eventDone: Boolean = false,
    val profileLease: String = "",
    val eventLease: String = "",
    val profileLeaseAt: Long = 0,
    val eventLeaseAt: Long = 0,
)

@Entity(tableName = "memory_conversation_versions")
data class MemoryConversationVersion(@PrimaryKey val conversationId: Long, val version: Long)
