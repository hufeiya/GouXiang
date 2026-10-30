package com.neethu.gouxiang.work

import androidx.room.withTransaction
import com.neethu.gouxiang.chat.ChatOrchestrator
import com.neethu.gouxiang.data.GouXiangDatabase
import com.neethu.gouxiang.data.MessageEntity
import com.neethu.gouxiang.data.MemoryEntity

internal data class DerivativeSourceSnapshot(
    val conversationId: Long,
    val version: Long,
    val messages: List<MessageEntity>,
    val memories: List<MemoryEntity>,
) {
    val firstMessageId: Long? get() = messages.minOfOrNull { it.id }
    val lastMessageId: Long? get() = messages.maxOfOrNull { it.id }
}

internal class StaleDerivativeSourceException : Exception("聊天历史已更改，请重新生成")

/** Read version and source messages in the same transaction, before any network call. */
internal suspend fun captureDerivativeSource(db: GouXiangDatabase, conversationId: Long, characterId: Long): DerivativeSourceSnapshot =
    db.withTransaction {
        DerivativeSourceSnapshot(
            conversationId,
            db.memoryWindowDao().version(conversationId) ?: 0L,
            db.messageDao().recentForConversation(conversationId, ChatOrchestrator.HISTORY_LIMIT),
            db.memoryDao().forCharacter(characterId),
        )
    }

/** Call from the same transaction as the result mutation. */
internal suspend fun requireCurrentDerivativeSource(db: GouXiangDatabase, conversationId: Long, version: Long) {
    if ((db.memoryWindowDao().version(conversationId) ?: 0L) != version ||
        db.conversationDao().byId(conversationId) == null
    ) throw StaleDerivativeSourceException()
}
