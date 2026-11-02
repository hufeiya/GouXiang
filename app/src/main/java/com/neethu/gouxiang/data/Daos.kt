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
    /** 所有预建会话，供角色入口使用。 */
    @Query("SELECT * FROM conversations ORDER BY lastAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    /** 仅用户发过消息的会话进入聊天历史；开场白不算对话。 */
    @Query("SELECT * FROM conversations AS c WHERE EXISTS (" +
        "SELECT 1 FROM messages AS m WHERE m.conversationId = c.id AND m.role = 'user') " +
        "ORDER BY c.lastAt DESC, c.id DESC")
    fun observeChatted(): Flow<List<ConversationEntity>>

    /** 首页推荐流 01:取最新会话对应的角色卡。 */
    @Query("SELECT * FROM conversations ORDER BY lastAt DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<ConversationEntity>

    /** 会话总数(D2 扫描轮转的分页分母)。 */
    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun countAll(): Int

    /**
     * D2 扫描轮转分页:按最近时间倒序取 [limit] 条、偏移 [offset](各扫描轮
     * 轮转 offset,会话数超过单轮扫描上限时旧会话不长期漏扫)。
     */
    @Query("SELECT * FROM conversations ORDER BY lastAt DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun latestPaged(limit: Int, offset: Int): List<ConversationEntity>

    /** 主动消息只扫描用户发过言的会话。 */
    @Query("SELECT COUNT(*) FROM conversations AS c WHERE EXISTS (" +
        "SELECT 1 FROM messages AS m WHERE m.conversationId = c.id AND m.role = 'user')")
    suspend fun countProactiveEligible(): Int

    @Query("SELECT * FROM conversations AS c WHERE EXISTS (" +
        "SELECT 1 FROM messages AS m WHERE m.conversationId = c.id AND m.role = 'user') " +
        "ORDER BY c.lastAt DESC, c.id DESC LIMIT :limit OFFSET :offset")
    suspend fun proactiveEligiblePaged(limit: Int, offset: Int): List<ConversationEntity>

    /** 未读角标累加(主动消息批次收口;负数自减由 setUnread 收口)。 */
    @Query("UPDATE conversations SET unread = unread + :n WHERE id = :id")
    suspend fun bumpUnread(id: Long, n: Int)

    /** 未读角标直接设值(进页消费清零/回溯重算)。 */
    @Query("UPDATE conversations SET unread = :unread WHERE id = :id")
    suspend fun setUnread(id: Long, unread: Int)

    /** Each character's most recently active IM conversation, without a scan limit. */
    @Query("SELECT * FROM conversations AS c WHERE c.mode = 'im' AND NOT EXISTS (" +
        "SELECT 1 FROM conversations AS newer WHERE newer.characterId = c.characterId AND newer.mode = 'im' " +
        "AND (newer.lastAt > c.lastAt OR (newer.lastAt = c.lastAt AND newer.id > c.id))) " +
        "ORDER BY c.lastAt DESC, c.id DESC")
    suspend fun latestImPerCharacter(): List<ConversationEntity>

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

    /** 用户活跃时间(D1:最近一条用户消息时刻;null=该会话尚未发言)。 */
    @Query("SELECT MAX(at) FROM messages WHERE conversationId = :conversationId AND role = 'user'")
    suspend fun lastUserMessageAt(conversationId: Long): Long?

    /** 是否有过主动消息回合(D1「everProactive」;按 turnId 前缀判)。 */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND turnId LIKE 'proactive-%'")
    suspend fun countProactiveTurnMessages(conversationId: Long): Int

    @Query("SELECT MIN(id) FROM messages WHERE conversationId = :conversationId AND turnId = :turnId")
    suspend fun firstIdOfTurn(conversationId: Long, turnId: String): Long?

    /** 回合尾消息 id(状态记录锚定来源区间落库前现查;空回合为 null)。 */
    @Query("SELECT MAX(id) FROM messages WHERE conversationId = :conversationId AND turnId = :turnId")
    suspend fun lastIdOfTurn(conversationId: Long, turnId: String): Long?

    /** 重生成(F4 长按操作):回合最后一条消息(id 倒序取一)。 */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND turnId = :turnId " +
            "AND role = 'assistant' ORDER BY at DESC, id DESC LIMIT 1",
    )
    suspend fun lastAssistantOfTurn(conversationId: Long, turnId: String): MessageEntity?

    /**
     * 手帐来源区间(G1):按消息 id 闭区间取区间内全部消息(时间正序)。
     * 区间由 [com.neethu.gouxiang.derivatives.DerivativeRange] 按 turn 分组
     * 预设算出,创建记录时快照,生成时按同区间重读素材。
     */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "AND id >= :startId AND id <= :endId ORDER BY at ASC, id ASC",
    )
    suspend fun rangeForConversation(conversationId: Long, startId: Long, endId: Long): List<MessageEntity>

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

    @Query("DELETE FROM memories WHERE category = 'event' AND sourceConversationId = :conversationId AND sourceEndMessageId >= :messageId")
    suspend fun deleteFromSource(conversationId: Long, messageId: Long): Int

    @Query("DELETE FROM memories WHERE characterId = :characterId")
    suspend fun deleteForCharacter(characterId: Long)

    /**
     * 回溯联动删除(03 任务 7):删该角色指定分类、时间不早于回溯点的记忆行,
     * 返回删除条数(回溯语义只传 event;画像/关系类不动)。
     */
    @Query("DELETE FROM memories WHERE characterId = :characterId AND category IN (:categories) AND at >= :sinceMs")
    suspend fun deleteSince(characterId: Long, categories: Set<String>, sinceMs: Long): Int
}

@Dao
interface MemoryWindowDao {
    @Query("SELECT payload FROM pending_memory_window WHERE id = 0")
    suspend fun loadPayload(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: PendingMemoryWindowEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTurn(turn: PendingMemoryTurnEntity): Long

    @Query("SELECT DISTINCT conversationId FROM memory_turns WHERE completed = 1 AND (profileDone = 0 OR eventDone = 0)")
    suspend fun pendingConversationIds(): List<Long>

    @Query("SELECT * FROM memory_turns WHERE conversationId = :conversationId AND (profileDone = 0 OR eventDone = 0) ORDER BY id")
    suspend fun turns(conversationId: Long): List<PendingMemoryTurnEntity>

    /** Retain consumed source snapshots so a rewind inside a turn can reconstruct its prefix. */
    @Query("SELECT * FROM memory_turns WHERE conversationId = :conversationId ORDER BY id")
    suspend fun allTurns(conversationId: Long): List<PendingMemoryTurnEntity>

    @Query("SELECT * FROM memory_turns WHERE conversationId = :conversationId AND turnId = :turnId LIMIT 1")
    suspend fun turn(conversationId: Long, turnId: String): PendingMemoryTurnEntity?

    @Query("UPDATE memory_turns SET completed = 1 WHERE completed = 0")
    suspend fun recoverInterruptedTurns()

    @Query("UPDATE memory_turns SET completed = 1 WHERE conversationId = :conversationId AND turnId = :turnId AND completed = 0")
    suspend fun completeTurn(conversationId: Long, turnId: String)

    @Update
    suspend fun updateTurns(turns: List<PendingMemoryTurnEntity>)

    @Query("DELETE FROM memory_turns WHERE conversationId = :conversationId AND startMessageId >= :messageId OR conversationId = :conversationId AND endMessageId >= :messageId")
    suspend fun deleteFrom(conversationId: Long, messageId: Long)

    @Query("UPDATE memory_turns SET profileLease = '', eventLease = '', profileLeaseAt = 0, eventLeaseAt = 0 WHERE conversationId = :conversationId")
    suspend fun clearLeases(conversationId: Long)

    @Query("SELECT version FROM memory_conversation_versions WHERE conversationId = :conversationId")
    suspend fun version(conversationId: Long): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setVersion(version: MemoryConversationVersion)

    @Query("DELETE FROM pending_memory_window WHERE id = 0")
    suspend fun discardLegacy()
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

    /**
     * 多角色合并时间线源(H1):全部动态按时间倒序,作者/参与过滤在
     * [com.neethu.gouxiang.data.MomentFeed] 按角色权限执行(查询层权限)。
     */
    @Query("SELECT * FROM moments ORDER BY at DESC, id DESC")
    fun observeAll(): Flow<List<MomentEntity>>

    @Query("SELECT * FROM moments ORDER BY at DESC, id DESC")
    suspend fun all(): List<MomentEntity>

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

    /** 删除一条评论(H3:用户删自己的评论;返回删除行数)。 */
    @Query("DELETE FROM moment_comments WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /** 删帖联动(H3:删用户动态时清空其评论区;事务内由 Repo 收口)。 */
    @Query("DELETE FROM moment_comments WHERE postId = :postId")
    suspend fun deleteForPost(postId: Long): Int
}

@Dao
interface MomentInteractionDao {
    /**
     * 幂等认领(H3):(postId, characterId) 唯一索引兜底;IGNORE 语义下
     * 已有认领返回 -1,调用方跳过该候选(同角色对同动态至多一条互评)。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun claim(entity: MomentInteractionEntity): Long

    /** 认领行是否已被占(-1 = 未认领成功)。 */
    @Query("SELECT EXISTS(SELECT 1 FROM moment_interactions WHERE postId = :postId AND characterId = :characterId)")
    suspend fun isClaimed(postId: Long, characterId: Long): Boolean

    /** 已认领的角色 id 集(互评候选过滤的查询层依据)。 */
    @Query("SELECT characterId FROM moment_interactions WHERE postId = :postId")
    suspend fun claimedCharacterIds(postId: Long): List<Long>

    /** 生成成功收口:评论行与认领行同一事务置终态(调用方 withTransaction)。 */
    @Query("UPDATE moment_interactions SET state = :state WHERE postId = :postId AND characterId = :characterId")
    suspend fun markState(postId: Long, characterId: Long, state: String)

    /** 生成失败释放认领(可重试;同 requestId 幂等不适用——认领即独占)。 */
    @Query("DELETE FROM moment_interactions WHERE postId = :postId AND characterId = :characterId")
    suspend fun release(postId: Long, characterId: Long)

    /** 调试/排查:一帖的认领明细。 */
    @Query("SELECT * FROM moment_interactions WHERE postId = :postId ORDER BY id ASC")
    suspend fun forPost(postId: Long): List<MomentInteractionEntity>
}

/** 同名角色冲突(创作保存的语义化失败,C 工作包):调用方映射为可读提示。 */
class CharacterNameConflictException(val name: String) :
    Exception("character name conflict: $name")

/**
 * 角色保存事务的产出:角色 id + 关联的 IM 会话 id(保存后可直接进入续聊)。
 */
data class SavedCharacter(
    val characterId: Long,
    val conversationId: Long,
    /** true=本次新建角色行;false=编辑既有角色。 */
    val created: Boolean,
    /** 手机聊天会话 id(缺失时本次已补建;不再为 null)。 */
    val phoneConversationId: Long,
    /** 事务落库后的头像版本(形象改写时 +1,驱动版本化缓存键)。 */
    val avatarVersion: Long,
)

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

    @Query("SELECT * FROM characters WHERE id = :id LIMIT 1")
    abstract suspend fun characterByIdRow(id: Long): CharacterEntity?

    @Query("SELECT * FROM characters WHERE source = :source LIMIT 1")
    abstract suspend fun characterBySource(source: String): CharacterEntity?

    @Update
    abstract suspend fun updateCharacter(entity: CharacterEntity)

    /** Catalog IDs, unlike names, identify a source character without conflating namesakes. */
    @Transaction
    open suspend fun startCatalogChat(character: CharacterEntity, openingAt: Long): SavedCharacter {
        val existing = characterBySource(character.source)
        val characterId = existing?.id ?: insertCharacter(character)
        var im = conversationByCharacter(characterId, "im")?.id
        if (im == null) {
            im = insertConversation(ConversationEntity(
                characterId = characterId, mode = "im", lastSummary = character.openingLine,
                lastAt = openingAt, createdAt = openingAt,
            ))
            if (character.openingLine.isNotBlank()) {
                insertMessages(listOf(MessageEntity(
                    conversationId = im, turnId = "turn-open-$openingAt", role = "assistant",
                    content = character.openingLine, at = openingAt,
                )))
            }
        }
        var phone = conversationByCharacter(characterId, "phone")?.id
        if (phone == null) {
            phone = insertConversation(ConversationEntity(
                characterId = characterId, mode = "phone", lastSummary = character.openingLine,
                lastAt = openingAt, createdAt = openingAt,
            ))
        }
        return SavedCharacter(characterId, im, existing == null, phone, existing?.avatarVersion ?: 0)
    }

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

    /**
     * 角色创建/编辑的原子保存(C 工作包 C2,单事务收口):
     *
     * - 新建([character.id] = 0):插角色行 → 补建 IM 会话 + 开场白消息行 +
     *   手机聊天会话行(有则跳过,「连续两次保存不产生重复会话」的事务内幂等);
     * - 编辑(id > 0):更新角色行;会话与其历史一律不动(开场白只在建会话时
     *   落一次);[appearanceChanged] = 形象描述/风格有改写时把 `avatarVersion`
     *   +1 并清空 `avatarFile`,驱动版本化缓存键生成新头像;
     * - 同名冲突(与他人同名)抛 [CharacterNameConflictException],整个事务
     *   回滚 —— 新建双重保存的第二笔也在此被拦截,不产生半套数据。
     *
     * 返回 [SavedCharacter](IM 会话 id 供保存后直接进入续聊)。
     */
    @Transaction
    open suspend fun saveCharacterAtomic(
        character: CharacterEntity,
        openingContent: String,
        openingActions: List<String>,
        openingAt: Long,
        appearanceChanged: Boolean,
    ): SavedCharacter {
        val existing = if (character.id > 0) characterByIdRow(character.id) else null
        // Existing catalog characters can share a name. Keeping one's own name is
        // always allowed; creating or renaming still rejects a conflicting name.
        if (existing?.name != character.name && characterByName(character.name) != null) {
            throw CharacterNameConflictException(character.name)
        }
        val charId: Long
        val created: Boolean
        var avatarVersion = character.avatarVersion
        if (existing == null) {
            charId = insertCharacter(character.copy(id = 0))
            created = true
        } else {
            // 编辑:形象/风格改写 → 版本 +1 并清当前头像文件,让生成链走新缓存键
            // The editor only owns its form fields. Preserve the latest stored metadata,
            // permissions and avatar instead of replacing them with form defaults.
            val bumped = existing.copy(
                name = character.name,
                profile = character.profile,
                intro = character.intro,
                exampleLines = character.exampleLines,
                openingLine = character.openingLine,
                appearance = character.appearance,
                appearanceStyle = character.appearanceStyle,
                avatarVersion = if (appearanceChanged) existing.avatarVersion + 1 else existing.avatarVersion,
                avatarFile = if (appearanceChanged) "" else existing.avatarFile,
            )
            updateCharacter(bumped.copy(id = character.id))
            charId = character.id
            created = false
            avatarVersion = bumped.avatarVersion
        }
        // IM 会话:缺失才建(新建必建;编辑兜底补建,不重复)
        var imConvId = conversationByCharacter(charId, "im")?.id
        if (imConvId == null) {
            imConvId = insertConversation(
                ConversationEntity(
                    characterId = charId,
                    mode = "im",
                    lastSummary = openingContent,
                    lastAt = openingAt,
                    createdAt = openingAt,
                ),
            )
            insertMessages(
                listOf(
                    MessageEntity(
                        conversationId = imConvId,
                        turnId = "turn-open-$openingAt",
                        role = "assistant",
                        kind = "text",
                        content = openingContent,
                        actionsJson = com.neethu.gouxiang.protocol.ActionsJson.encode(openingActions),
                        at = openingAt,
                    ),
                ),
            )
        }
        // 手机聊天会话(C2 的手机入口路径):缺失才建,行为对齐种子(行先在,
        // 进入页面时走主动消息开场)
        var phoneConvId = conversationByCharacter(charId, "phone")?.id
        if (phoneConvId == null) {
            phoneConvId = insertConversation(
                ConversationEntity(
                    characterId = charId,
                    mode = "phone",
                    lastSummary = openingContent,
                    lastAt = openingAt,
                    createdAt = openingAt,
                ),
            )
        }
        return SavedCharacter(charId, imConvId, created, phoneConvId, avatarVersion)
    }

    @Query("SELECT * FROM conversations WHERE characterId = :characterId AND mode = :mode LIMIT 1")
    abstract suspend fun conversationByCharacter(characterId: Long, mode: String): ConversationEntity?

    companion object {
        /** 手机聊天会话行的时间差(展示层错开,贴近 02 截图「4小时前」节奏)。 */
        const val PHONE_GAP_MS = 2 * 60 * 60 * 1000L
    }
}
