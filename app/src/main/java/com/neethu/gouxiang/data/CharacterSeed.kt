package com.neethu.gouxiang.data

/**
 * 播种载荷:角色卡 + 开场白消息 + 手机聊天会话摘要 + 初始记忆。
 */
data class CharacterSeed(
    val character: CharacterEntity,
    val openingMessage: MessageEntity,
    /** 手机聊天形态会话行(02 截图第二条,带手机角标)的预览文案。 */
    val phoneSummary: String = "",
    val memories: List<MemoryEntity> = emptyList(),
)
