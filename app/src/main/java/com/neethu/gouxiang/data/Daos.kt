package com.neethu.gouxiang.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun byId(id: Long): CharacterEntity?

    @Query("SELECT * FROM characters WHERE id = :id")
    fun observeById(id: Long): Flow<CharacterEntity?>

    @Query("SELECT * FROM characters WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): CharacterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CharacterEntity): Long

    @Update
    suspend fun update(entity: CharacterEntity)

    @Query("UPDATE characters SET avatarFile = :file WHERE id = :id")
    suspend fun setAvatarFile(id: Long, file: String)

    @Query("SELECT COUNT(*) FROM characters")
    suspend fun count(): Int
}

@Dao
interface ConversationDao {
    /** 消息列表 02:按最近时间倒序的会话行。 */
    @Query("SELECT * FROM conversations ORDER BY lastAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    /** 首页推荐流 01:取最新会话对应的角色卡。 */
    @Query("SELECT * FROM conversations ORDER BY lastAt DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE characterId = :characterId AND mode = :mode LIMIT 1")
    suspend fun byCharacter(characterId: Long, mode: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: Long): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversationEntity): Long

    @Query("UPDATE conversations SET lastSummary = :summary, lastAt = :at WHERE id = :id")
    suspend fun touch(id: Long, summary: String, at: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY at ASC, id ASC")
    fun observeForConversation(conversationId: Long): Flow<List<MessageEntity>>

    /** 聊天页全量列表:同步一次性读取(进页首帧用,流更新走 observe)。 */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY at ASC, id ASC")
    suspend fun allForConversation(conversationId: Long): List<MessageEntity>

    /** 上下文组装窗口:最近 limit 条,按时间正序输出。 */
    @Query(
        "SELECT * FROM (SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY at DESC, id DESC LIMIT :limit) ORDER BY at ASC, id ASC",
    )
    suspend fun recentForConversation(conversationId: Long, limit: Int): List<MessageEntity>

    /** 历史页 11 分段:早于游标 (beforeAt, beforeId) 的最近 limit 条,按时间正序输出。 */
    @Query(
        "SELECT * FROM (SELECT * FROM messages WHERE conversationId = :conversationId " +
            "AND (at < :beforeAt OR (at = :beforeAt AND id < :beforeId)) " +
            "ORDER BY at DESC, id DESC LIMIT :limit) ORDER BY at ASC, id ASC",
    )
    suspend fun recentBefore(
        conversationId: Long,
        beforeAt: Long,
        beforeId: Long,
        limit: Int,
    ): List<MessageEntity>

    /** 历史页 11 到底判定:该会话消息总数。 */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countForConversation(conversationId: Long): Int

    /** 会话行摘要:该会话最后一条消息。 */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY at DESC, id DESC LIMIT 1")
    suspend fun latestForConversation(conversationId: Long): MessageEntity?

    /** 回溯(长按泡操作):删除整个回复回合(同 turnId 全部行)。 */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND turnId = :turnId")
    suspend fun deleteTurn(conversationId: Long, turnId: String): Int

    /**
     * 回溯(长按泡操作,F4 收尾轮):删除该消息及其之后的所有行(「回到该点」)。
     * id 单调自增,`id >= :id` 即该条起向后的全部消息(含同回合更晚的泡与后续回合)。
     */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id >= :id")
    suspend fun deleteFromMessage(conversationId: Long, id: Long): Int

    /** 回溯(只删一条):长按用户消息回溯到该条之前。 */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /** 单条消息(长按操作前读取,防并发下 id 失效)。 */
    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: Long): MessageEntity?

    /** 重生成(F4 长按操作):回合最后一条消息(id 倒序取一)。 */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND turnId = :turnId " +
            "AND role = 'assistant' ORDER BY at DESC, id DESC LIMIT 1",
    )
    suspend fun lastAssistantOfTurn(conversationId: Long, turnId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long
}

@Dao
interface MemoryDao {
    /** 注入与面板共用:全量(渐忘分带在检索/渲染层现场算,不落库)。 */
    @Query("SELECT * FROM memories WHERE characterId = :characterId ORDER BY importance DESC, at DESC")
    suspend fun forCharacter(characterId: Long): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE characterId = :characterId AND category = 'profile' ORDER BY at DESC")
    suspend fun profileFor(characterId: Long): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE characterId = :characterId AND category = 'event' ORDER BY at DESC")
    suspend fun eventsFor(characterId: Long): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(memories: List<MemoryEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity): Long

    @Update
    suspend fun update(memory: MemoryEntity)

    /** 遗忘(主动删除,03 §1.9:自由操作不走配额)。 */
    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memories WHERE characterId = :characterId")
    suspend fun deleteForCharacter(characterId: Long)
}

@Dao
interface MemoryWindowDao {
    @Query("SELECT payload FROM pending_memory_window WHERE id = 0")
    suspend fun loadPayload(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: PendingMemoryWindowEntity)
}

@Dao
interface InnerVoiceDao {
    /** 心声列表(按生成时间倒序,最新置顶;dump 07 列表排序同向)。 */
    @Query("SELECT * FROM inner_voices WHERE characterId = :characterId ORDER BY at DESC, id DESC")
    fun observeForCharacter(characterId: Long): Flow<List<InnerVoiceEntity>>

    @Query("SELECT * FROM inner_voices WHERE characterId = :characterId ORDER BY at DESC, id DESC")
    suspend fun forCharacter(characterId: Long): List<InnerVoiceEntity>

    /** 角色主页「Ta 的心声」占位卡尾注(「全部 N」)。 */
    @Query("SELECT COUNT(*) FROM inner_voices WHERE characterId = :characterId")
    suspend fun countForCharacter(characterId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(voices: List<InnerVoiceEntity>): List<Long>

    /** 配图回填(生成链的异步落盘;其他列不触碰)。 */
    @Query("UPDATE inner_voices SET imageFile = :file WHERE id = :id")
    suspend fun setImageFile(id: Long, file: String)

    @Query("DELETE FROM inner_voices WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface MomentDao {
    /** 朋友圈时间线(按发布时间倒序,最新在上;dump 06 信息流排序同向)。 */
    @Query("SELECT * FROM moments WHERE characterId = :characterId ORDER BY at DESC, id DESC")
    fun observeForCharacter(characterId: Long): Flow<List<MomentEntity>>

    @Query("SELECT * FROM moments WHERE characterId = :characterId ORDER BY at DESC, id DESC")
    suspend fun forCharacter(characterId: Long): List<MomentEntity>

    /** 角色主页「Ta 的朋友圈」占位卡尾注(「全部 N」)。 */
    @Query("SELECT COUNT(*) FROM moments WHERE characterId = :characterId")
    suspend fun countForCharacter(characterId: Long): Int

    /** 角色主页占位卡角标(角色主页是否已关闭展示位,详情页直接打开时间线)。 */
    @Query("SELECT * FROM moments WHERE id = :id")
    suspend fun byId(id: Long): MomentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(moment: MomentEntity): Long

    /** 点赞/取消(F9 互动:本地状态,写库即上屏)。 */
    @Query("UPDATE moments SET likedByMe = :liked WHERE id = :id")
    suspend fun setLiked(id: Long, liked: Boolean)

    /** 配图回填(生成链的异步落盘;其他列不触碰)。 */
    @Query("UPDATE moments SET imageFile = :file WHERE id = :id")
    suspend fun setImageFile(id: Long, file: String)

    @Query("DELETE FROM moments WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface MomentCommentDao {
    /** 某条动态的评论线程(时间正序,楼中楼跟随主评,回复渲染按 replyToId 归位)。 */
    @Query("SELECT * FROM moment_comments WHERE postId = :postId ORDER BY at ASC, id ASC")
    fun observeForPost(postId: Long): Flow<List<MomentCommentEntity>>

    @Query("SELECT * FROM moment_comments WHERE postId = :postId ORDER BY at ASC, id ASC")
    suspend fun forPost(postId: Long): List<MomentCommentEntity>

    /** 一条动态的评论总数(动态卡「评论 N」)。 */
    @Query("SELECT COUNT(*) FROM moment_comments WHERE postId = :postId")
    suspend fun countForPost(postId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(comment: MomentCommentEntity): Long
}

/**
 * 聚合事务:创建会话时一并落种子消息(开场白)与初始记忆,保证原子性。
 */
@Dao
abstract class SeedDao {
    @Insert
    abstract suspend fun insertCharacter(entity: CharacterEntity): Long

    @Insert
    abstract suspend fun insertConversation(entity: ConversationEntity): Long

    @Insert
    abstract suspend fun insertMessages(messages: List<MessageEntity>): List<Long>

    @Insert
    abstract suspend fun insertMemories(memories: List<MemoryEntity>): List<Long>

    @Query("SELECT * FROM characters WHERE name = :name LIMIT 1")
    abstract suspend fun characterByName(name: String): CharacterEntity?

    /** 若同名角色不存在则播种(角色 + 会话 + 开场白消息 + 用户画像记忆)。 */
    @Transaction
    open suspend fun seedIfAbsent(seed: CharacterSeed) {
        if (characterByName(seed.character.name) != null) return
        val charId = insertCharacter(seed.character)
        val convId = insertConversation(
            ConversationEntity(
                characterId = charId,
                mode = "im",
                lastSummary = seed.openingMessage.content,
                // 会话行时间取开场白消息时间(洛瑶类种子可设为几天前,对齐 02 基准节奏)
                lastAt = seed.openingMessage.at,
                createdAt = seed.character.createdAt,
            ),
        )
        insertMessages(
            listOf(seed.openingMessage.copy(conversationId = convId)),
        )
        if (seed.phoneSummary.isNotBlank()) {
            insertConversation(
                ConversationEntity(
                    characterId = charId,
                    mode = "phone",
                    lastSummary = seed.phoneSummary,
                    lastAt = seed.character.createdAt - PHONE_GAP_MS,
                    createdAt = seed.character.createdAt - PHONE_GAP_MS,
                ),
            )
        }
        insertMemories(seed.memories.map { it.copy(characterId = charId) })
    }

    companion object {
        /** 手机聊天会话行的时间差(展示层错开,贴近 02 截图「4小时前」节奏)。 */
        const val PHONE_GAP_MS = 2 * 60 * 60 * 1000L
    }
}
