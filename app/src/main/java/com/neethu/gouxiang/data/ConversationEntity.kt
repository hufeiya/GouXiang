package com.neethu.gouxiang.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 会话表(角色 ↔ 用户一条会话;首页推荐流 = 最新会话排序,消息列表 02 的会话行)。
 */
@Entity(
    tableName = "conversations",
    indices = [Index("characterId")],
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 关联 [CharacterEntity.id]。 */
    val characterId: Long,
    /** 聊天形态(本功能只有 im;phone = 手机聊天为下一功能的角标入口)。 */
    val mode: String = "im",
    /** 最近消息摘要(会话行预览)。 */
    val lastSummary: String = "",
    /** 最近消息时间(epoch millis,列表按此倒序)。 */
    val lastAt: Long = 0,
    /** 未读数。 */
    val unread: Int = 0,
    /** 创建时间。 */
    val createdAt: Long = 0,
)
