package com.neethu.gouxiang.data

import androidx.room.withTransaction
import com.neethu.gouxiang.chat.ChatOrchestrator
import com.neethu.gouxiang.chat.MemorySource
import com.neethu.gouxiang.chat.MemoryWindowStore

/**
 * [ChatOrchestrator.DaoAccess] 的 Room 实现:编排器只面向该抽象,
 * 测试可注入 fake(单测里已验证),UI 层经 Repo 单例接线。
 */
class ChatDaoAccess(private val db: GouXiangDatabase, private val context: android.content.Context? = null) : ChatOrchestrator.DaoAccess {
    constructor(repo: GouXiangRepo) : this(repo.db, repo.appContext)

    override suspend fun history(conversationId: Long, limit: Int): List<MessageEntity> =
        db.messageDao().recentForConversation(conversationId, limit)

    override suspend fun memories(characterId: Long): List<MemoryEntity> =
        db.memoryDao().forCharacter(characterId)

    override suspend fun character(characterId: Long): CharacterEntity? = db.characterDao().byId(characterId)

    override suspend fun insertMessage(message: MessageEntity) { db.messageDao().insert(message) }

    override suspend fun conversationVersion(conversationId: Long): Long = db.memoryWindowDao().version(conversationId) ?: 0L

    override suspend fun insertMessageIfVersion(message: MessageEntity, version: Long): Boolean = db.withTransaction {
        if ((db.memoryWindowDao().version(message.conversationId) ?: 0L) != version) false
        else {
            db.messageDao().insert(message)
            db.conversationDao().touch(message.conversationId,
                com.neethu.gouxiang.protocol.conversationSummary(message.kind, message.content, message.actionsJson), message.at)
            true
        }
    }

    override suspend fun insertMessageWithTurn(
        message: MessageEntity,
        version: Long,
        turn: ChatOrchestrator.CompletedMemoryTurn,
        completed: Boolean,
    ): Boolean {
        val saved = db.withTransaction {
        if ((db.memoryWindowDao().version(message.conversationId) ?: 0L) != version) return@withTransaction false
        val userMessage = turn.userMessageId?.let { db.messageDao().byId(it) }
        if (turn.userMessageId != null && (userMessage == null || userMessage.conversationId != message.conversationId || userMessage.role != "user")) {
            return@withTransaction false
        }
        val messageId = db.messageDao().insert(message)
        db.conversationDao().touch(message.conversationId,
            com.neethu.gouxiang.protocol.conversationSummary(message.kind, message.content, message.actionsJson), message.at)
        val firstAssistantId = db.messageDao().firstIdOfTurn(message.conversationId, message.turnId) ?: messageId
        val current = db.memoryWindowDao().turn(message.conversationId, message.turnId)
        check(current == null || !current.completed) { "Duplicate completed turn" }
        val pending = PendingMemoryTurnEntity(
            id = current?.id ?: 0,
            conversationId = message.conversationId,
            characterId = turn.characterId,
            turnId = message.turnId,
            startMessageId = turn.userMessageId ?: firstAssistantId,
            endMessageId = messageId,
            user = turn.user,
            assistant = turn.assistant,
            completed = completed,
        )
        if (current == null) {
            check(db.memoryWindowDao().insertTurn(pending) != -1L) { "Duplicate turn" }
        } else db.memoryWindowDao().updateTurns(listOf(pending))
        if (completed && turn.discoveryEligible && userMessage != null && turn.assistant.isNotBlank()) {
            db.contentDiscoveryDao().insertTurn(ContentTurnEntity(
                conversationId = message.conversationId, characterId = turn.characterId,
                replyTurnId = message.turnId, userMessageId = userMessage.id, endMessageId = messageId,
                sourceVersion = version, user = turn.user, assistant = turn.assistant, completedAt = message.at,
            ))
        }
        true
        }
        val appContext = context
        if (saved && completed && turn.discoveryEligible && turn.userMessageId != null && appContext != null) {
            runCatching { com.neethu.gouxiang.work.ConversationContentCoordinator.onTurnCompleted(appContext, message.conversationId) }
        }
        return saved
    }

    override suspend fun completePartialTurn(conversationId: Long, turnId: String, version: Long) {
        db.withTransaction {
            if ((db.memoryWindowDao().version(conversationId) ?: 0L) == version) {
                db.memoryWindowDao().completeTurn(conversationId, turnId)
            }
        }
    }

    override suspend fun touchConversation(conversationId: Long, summary: String, at: Long) {
        db.conversationDao().touch(conversationId, summary, at)
    }
}

/** [MemorySource] 的 Room 实现(memories 表读写)。 */
class RoomMemorySource(private val repo: GouXiangRepo) : MemorySource {

    override suspend fun all(characterId: Long): List<MemoryEntity> =
        repo.memoriesFor(characterId)

    override suspend fun update(memory: MemoryEntity) = repo.updateMemory(memory)

    override suspend fun insert(memory: MemoryEntity): Long = repo.insertMemory(memory)

    override suspend fun deleteById(id: Long) = repo.deleteMemory(id)

    override suspend fun deleteSince(characterId: Long, categories: Set<String>, sinceMs: Long): Int =
        repo.deleteMemoriesSince(characterId, categories, sinceMs)
}

/** [MemoryWindowStore] 的 Room 实现(pending_memory_window 单行表)。 */
class RoomMemoryWindowStore(private val repo: GouXiangRepo) : MemoryWindowStore {

    override suspend fun loadPayload(): String? = repo.db.memoryWindowDao().loadPayload()

    override suspend fun save(payload: String) {
        repo.db.memoryWindowDao().save(PendingMemoryWindowEntity(payload = payload))
    }
}
