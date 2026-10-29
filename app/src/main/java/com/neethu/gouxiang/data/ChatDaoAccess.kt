package com.neethu.gouxiang.data

import com.neethu.gouxiang.chat.ChatOrchestrator
import com.neethu.gouxiang.chat.MemorySource
import com.neethu.gouxiang.chat.MemoryWindowStore

/**
 * [ChatOrchestrator.DaoAccess] 的 Room 实现:编排器只面向该抽象,
 * 测试可注入 fake(单测里已验证),UI 层经 Repo 单例接线。
 */
class ChatDaoAccess(private val repo: GouXiangRepo) : ChatOrchestrator.DaoAccess {

    override suspend fun history(conversationId: Long, limit: Int): List<MessageEntity> =
        repo.recentMessages(conversationId, limit)

    override suspend fun memories(characterId: Long): List<MemoryEntity> =
        repo.memoriesFor(characterId)

    override suspend fun character(characterId: Long): CharacterEntity? =
        repo.characterById(characterId)

    override suspend fun insertMessage(message: MessageEntity) {
        repo.insertMessage(message)
    }

    override suspend fun touchConversation(conversationId: Long, summary: String, at: Long) {
        repo.touchConversation(conversationId, summary, at)
    }
}

/** [MemorySource] 的 Room 实现(memories 表读写)。 */
class RoomMemorySource(private val repo: GouXiangRepo) : MemorySource {

    override suspend fun all(characterId: Long): List<MemoryEntity> =
        repo.memoriesFor(characterId)

    override suspend fun update(memory: MemoryEntity) = repo.updateMemory(memory)

    override suspend fun insert(memory: MemoryEntity): Long = repo.insertMemory(memory)

    override suspend fun deleteById(id: Long) = repo.deleteMemory(id)
}

/** [MemoryWindowStore] 的 Room 实现(pending_memory_window 单行表)。 */
class RoomMemoryWindowStore(private val repo: GouXiangRepo) : MemoryWindowStore {

    override suspend fun loadPayload(): String? = repo.db.memoryWindowDao().loadPayload()

    override suspend fun save(payload: String) {
        repo.db.memoryWindowDao().save(PendingMemoryWindowEntity(payload = payload))
    }
}
