package com.neethu.gouxiang.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 消息表。多泡协议下:一次 LLM 回复拆成多条 [MessageEntity](同一 turnId),
 * 每条一个气泡;kind 按 [com.neethu.gouxiang.protocol.Bubble.Kind] 小写存。
 */
@Entity(
    tableName = "messages",
    indices = [Index("conversationId"), Index("turnId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 关联 [ConversationEntity.id]。 */
    val conversationId: Long,
    /** 一组消息的分组 id；用户消息与角色回复使用不同 turnId。 */
    val turnId: String,
    /** "user" / "assistant"。 */
    val role: String,
    /**
     * 气泡类型:text / sticker(存量,2026-10-31 起协议不再新产) / img /
     * image(用户真实选图,2026-11-02「+」面板) / voice(默认 text)。
     */
    val kind: String = "text",
    /** 正文;sticker/img = 占位描述;voice = 语音条转写;image = 上下文提示行。 */
    val content: String,
    /** 内嵌 () 动作,JSON 数组字符串(仅 assistant text 泡)。 */
    val actionsJson: String = "[]",
    /** 真实图片文件路径(kind=image 时非空,app filesDir 内;其余为空串)。 */
    val mediaPath: String = "",
    /** 时间(epoch millis)。 */
    val at: Long = 0,
)
