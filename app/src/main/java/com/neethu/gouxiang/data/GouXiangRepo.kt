package com.neethu.gouxiang.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import com.neethu.gouxiang.protocol.conversationSummary

/**
 * 数据底座门面:单例库 + 首启播种 + UI 用流。
 */
class GouXiangRepo private constructor(context: Context) {

    internal val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val db: GouXiangDatabase = Room.databaseBuilder(
        context.applicationContext,
        GouXiangDatabase::class.java,
        GouXiangDatabase.NAME,
    ).addCallback(object : androidx.room.RoomDatabase.Callback() {
        override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            // This runs before any DAO operation in a newly opened process.
            db.execSQL("UPDATE memory_turns SET completed = 1 WHERE completed = 0")
        }
    }).addMigrations(
        GouXiangDatabase.MIGRATION_1_2,
        GouXiangDatabase.MIGRATION_2_3,
        GouXiangDatabase.MIGRATION_3_4,
        GouXiangDatabase.MIGRATION_4_5,
        GouXiangDatabase.MIGRATION_5_6,
        GouXiangDatabase.MIGRATION_6_7,
        GouXiangDatabase.MIGRATION_7_8,
        GouXiangDatabase.MIGRATION_8_9,
        GouXiangDatabase.MIGRATION_9_10,
        GouXiangDatabase.MIGRATION_10_11,
        GouXiangDatabase.MIGRATION_11_12,
        GouXiangDatabase.MIGRATION_12_13,
        GouXiangDatabase.MIGRATION_13_14,
        GouXiangDatabase.MIGRATION_14_15,
    ).build()

    init {
        scope.launch { seedOnFirstRun() }
    }

    /** 首启播种种子角色(幂等:同名角色存在即跳过)。 */
    internal suspend fun seedOnFirstRun() {
        SeedData.ALL.forEach { seed -> db.seedDao().seedIfAbsent(seed) }
    }

    // ── 首页 01:推荐流(剧卡)──────────────────────────────────────
    /** 推荐流卡片:角色 + 对应 im 会话的最新开场白/摘要。 */
    val homeFeed: Flow<List<HomeFeedCard>> = db.characterDao().observeAll()
        .map { characters ->
            characters.map { char ->
                val conv = db.conversationDao().byCharacter(char.id, "im")
                HomeFeedCard(
                    character = char,
                    conversationId = conv?.id ?: -1,
                    openingLine = conv?.lastSummary ?: char.openingLine,
                )
            }
        }
        .shareIn(scope, SharingStarted.Lazily, replay = 1)

    // ── 消息列表 02:聊过会话行 ─────────────────────────────────────
    val conversations: Flow<List<ConversationRow>> = db.conversationDao().observeAll()
        .map { convs ->
            convs.map { conv ->
                ConversationRow(
                    conversation = conv,
                    character = db.characterDao().byId(conv.characterId),
                )
            }
        }
        .shareIn(scope, SharingStarted.Lazily, replay = 1)

    suspend fun characterByName(name: String): CharacterEntity? = db.characterDao().byName(name)

    suspend fun characterById(id: Long): CharacterEntity? = db.characterDao().byId(id)

    fun observeCharacter(id: Long): Flow<CharacterEntity?> = db.characterDao().observeById(id)

    fun observeConversation(id: Long): Flow<ConversationEntity?> = db.conversationDao().observeById(id)

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        db.messageDao().observeForConversation(conversationId)

    suspend fun allMessages(conversationId: Long): List<MessageEntity> =
        db.messageDao().allForConversation(conversationId)

    suspend fun recentMessages(conversationId: Long, limit: Int): List<MessageEntity> =
        db.messageDao().recentForConversation(conversationId, limit)

    /** 历史页 11 分段:早于游标 (at,id) 的最近 limit 条,时间正序输出。 */
    suspend fun recentMessagesBefore(
        conversationId: Long,
        beforeAt: Long,
        beforeId: Long,
        limit: Int,
    ): List<MessageEntity> =
        db.messageDao().recentBefore(conversationId, beforeAt, beforeId, limit)

    /** 历史页 11 到底判定:该会话消息总数。 */
    suspend fun countMessages(conversationId: Long): Int =
        db.messageDao().countForConversation(conversationId)

    suspend fun memoriesFor(characterId: Long): List<MemoryEntity> =
        db.memoryDao().forCharacter(characterId)

    suspend fun profileMemories(characterId: Long): List<MemoryEntity> =
        db.memoryDao().profileFor(characterId)

    suspend fun eventMemories(characterId: Long): List<MemoryEntity> =
        db.memoryDao().eventsFor(characterId)

    /** 单条记忆更新/删除(记忆页编辑与遗忘;自动通道经 MemoryBook 走去重)。 */
    suspend fun updateMemory(memory: MemoryEntity) = db.memoryDao().update(memory)

    suspend fun deleteMemory(id: Long) = db.memoryDao().deleteById(id)

    suspend fun insertMemory(memory: MemoryEntity): Long = db.memoryDao().insert(memory)

    /** 回溯联动删除(03 任务 7):删该角色晚于回溯点且分类命中的记忆行,返回删除条数。 */
    suspend fun deleteMemoriesSince(characterId: Long, categories: Set<String>, sinceMs: Long): Int =
        db.memoryDao().deleteSince(characterId, categories, sinceMs)

    /** 手动管理路径的批量写入(全部 REPLACE 语义)。 */
    suspend fun insertMemories(memories: List<MemoryEntity>) {
        if (memories.isNotEmpty()) db.memoryDao().insertAll(memories)
    }

    suspend fun insertMessage(message: MessageEntity): Long = db.messageDao().insert(message)

    // ── 心声卡(F8「Ta 的心声」)──────────────────────────────────────

    /** 心声列表流(角色主页占位卡与心声页共用,落库即上屏)。 */
    fun observeInnerVoices(characterId: Long): Flow<List<InnerVoiceEntity>> =
        db.innerVoiceDao().observeForCharacter(characterId)

    suspend fun innerVoices(characterId: Long): List<InnerVoiceEntity> =
        db.innerVoiceDao().forCharacter(characterId)

    /** 角色主页占位卡尾注(「全部 N」)。 */
    suspend fun countInnerVoices(characterId: Long): Int =
        db.innerVoiceDao().countForCharacter(characterId)

    /** 生成链落库:一批(2~4 条)心声按 [baseAt] 起依次错开 1ms(倒序稳定)。 */
    suspend fun insertInnerVoices(voices: List<InnerVoiceEntity>): List<Long> =
        if (voices.isEmpty()) emptyList() else db.innerVoiceDao().insertAll(voices)

    /** 配图异步落盘后回填(纯文字卡兜底路径不触碰行)。 */
    suspend fun setInnerVoiceImage(id: Long, file: String) =
        db.innerVoiceDao().setImageFile(id, file)

    // ── 朋友圈(F9「Ta 的朋友圈」→ H 工作包 H1 多角色时间线)───────────

    /** 动态时间线流(角色主页按角色分片查询,不受权限过滤——用户主动查看
     *  某角色时全量可见;多角色合并时间线走 [observeMomentsFeed])。 */
    fun observeMoments(characterId: Long): Flow<List<MomentEntity>> =
        db.momentDao().observeForCharacter(characterId)

    suspend fun moments(characterId: Long): List<MomentEntity> =
        db.momentDao().forCharacter(characterId)

    /**
     * 多角色合并时间线流(H1):用户帖 + 参与朋友圈([CharacterEntity.momentsJoined])
     * 角色的动态,按时间倒序;查询层权限过滤(非参与角色的动态不进合并流,
     * 但数据不删,角色主页分片查询仍可见)。作者名随行解析(角色改名即刷新)。
     */
    val momentsFeed: Flow<List<MomentFeed.MomentFeedItem>> =
        kotlinx.coroutines.flow.combine(
            db.momentDao().observeAll(),
            db.characterDao().observeAll(),
        ) { posts, characters ->
            MomentFeed.assemble(posts, characters.associateBy { it.id })
        }

    /** 角色主页占位卡尾注(「全部 N」;N=0 时不带尾注,与心声同约定)。 */
    suspend fun countMoments(characterId: Long): Int =
        db.momentDao().countForCharacter(characterId)

    /** 生成链落库(一条动态 = 一行;id 供配图回填与评论挂靠)。 */
    suspend fun insertMoment(moment: MomentEntity): Long = db.momentDao().insert(moment)

    /** 配图异步落盘后回填(纯文字卡兜底路径不触碰行)。 */
    suspend fun setMomentImage(id: Long, file: String) =
        db.momentDao().setImageFile(id, file)

    /** 点赞/取消(F9 互动:本地状态,写库即上屏)。 */
    suspend fun setMomentLiked(id: Long, liked: Boolean) =
        db.momentDao().setLiked(id, liked)

    /** 评论线程流(动态卡下方展开显示)。 */
    fun observeMomentComments(postId: Long): Flow<List<MomentCommentEntity>> =
        db.momentCommentDao().observeForPost(postId)

    suspend fun momentComments(postId: Long): List<MomentCommentEntity> =
        db.momentCommentDao().forPost(postId)

    suspend fun countMomentComments(postId: Long): Int =
        db.momentCommentDao().countForPost(postId)

    /** 评论落库(用户评论与角色回复同一通道;id 供楼中楼 replyToId 挂靠)。 */
    suspend fun insertMomentComment(comment: MomentCommentEntity): Long =
        db.momentCommentDao().insert(comment)

    /** 删除用户自己的评论(H3)。 */
    suspend fun deleteUserMomentComment(id: Long): Int = db.momentCommentDao().deleteById(id)

    /**
     * 删除用户自己的动态(H3):评论区与动态行同一事务清空(不留孤儿评论);
     * 角色动态不开放删除入口(用户只删自己的内容)。
     */
    suspend fun deleteUserMoment(postId: Long): Int = db.withTransaction {
        val removed = db.momentCommentDao().deleteForPost(postId)
        val post = db.momentDao().byId(postId)
        // 只允许删用户帖(角色动态走管理路径);幂等:已删返回 0 不抛。
        if (post != null && post.authorKind == MomentAuthorKind.USER) {
            db.momentDao().deleteById(postId)
            removed + 1
        } else {
            0
        }
    }

    // ── 互评认领账本(H3;引擎在 chat/MomentInteractions.kt 直用 DAO,
    //    此处仅暴露调试观测面)────────────────────────────────────────

    /** 调试/排查:一帖的互评认领明细(ai_cmd 观测用)。 */
    suspend fun momentInteractionsForPost(postId: Long): List<MomentInteractionEntity> =
        db.momentInteractionDao().forPost(postId)

    /** 消息落库后刷新会话行摘要(最近时间/预览文案,消息列表 02 同步可见)。 */
    suspend fun touchConversation(conversationId: Long, summary: String, at: Long) =
        db.conversationDao().touch(conversationId, summary, at)

    // ── 番外剧场与心动事件(F 工作包 F1-F4;统一内容记录)────────────

    /** 会话内番外/心动记录流(番外页列表,落库即上屏)。 */
    fun observeSidestoryRecords(conversationId: Long): Flow<List<SidestoryRecordEntity>> =
        db.sidestoryRecordDao().observeForConversation(conversationId)

    suspend fun sidestoryRecords(conversationId: Long): List<SidestoryRecordEntity> =
        db.sidestoryRecordDao().forConversation(conversationId)

    /** 角色主页「番外剧场」尾注(「全部 N」;心声/朋友圈同约定)。 */
    suspend fun countSidestoryRecords(characterId: Long): Int =
        db.sidestoryRecordDao().countForCharacter(characterId)

    suspend fun sidestoryRecordById(id: Long): SidestoryRecordEntity? =
        db.sidestoryRecordDao().byId(id)

    /** 删除一条记录(列表/详情管理;配图文件由调用方按需清理)。 */
    suspend fun deleteSidestoryRecord(id: Long): Int = db.sidestoryRecordDao().deleteById(id)

    /** 回溯联动标记(与心声/朋友圈 isStale 同语义;不删除不重写)。 */
    suspend fun markSidestoryStaleFrom(conversationId: Long, messageId: Long): Int =
        db.sidestoryRecordDao().markStaleFrom(conversationId, messageId)

    // ── 聊天衍生内容(G 工作包 G1-G3;手帐/小说/合照统一记录)───────

    /** 会话内手帐/小说/合照记录流(各自页面按 kind 过滤,落库即上屏)。 */
    fun observeChatDerivatives(conversationId: Long): Flow<List<ChatDerivativeEntity>> =
        db.chatDerivativeDao().observeForConversation(conversationId)

    suspend fun chatDerivatives(conversationId: Long): List<ChatDerivativeEntity> =
        db.chatDerivativeDao().forConversation(conversationId)

    /** 角色主页入口卡尾注(按类型「全部 N」)。 */
    suspend fun countChatDerivatives(characterId: Long, kind: String): Int =
        db.chatDerivativeDao().countForKind(characterId, kind)

    suspend fun chatDerivativeById(id: Long): ChatDerivativeEntity? =
        db.chatDerivativeDao().byId(id)

    /** 删除一条记录(列表/详情管理;图片文件由调用方按需清理)。 */
    suspend fun deleteChatDerivative(id: Long): Int = db.chatDerivativeDao().deleteById(id)

    // ── 心声角色开关(G4)/角色行更新 ─────────────────────────────────

    /** 更新角色行(心声页角色开关等字段级编辑;整行 REPLACE 语义)。 */
    suspend fun updateCharacter(character: CharacterEntity) = db.characterDao().update(character)

    // ── 主动消息账本(D 工作包 D1-D4)────────────────────────────────

    /** 事务收口层单例(挂在 repo 生命周期;服务层与 VM 共用同一实例)。 */
    val proactiveLedger: ProactiveLedger by lazy { ProactiveLedger(db) }

    /** 回溯联动清账(VM.rewindToMessage / regenerateTurn 调):见 [ProactiveLedger.rewindFrom]。 */
    suspend fun rewindProactiveLedger(conversationId: Long, anchorMessageId: Long): Int =
        proactiveLedger.rewindFrom(conversationId, anchorMessageId)

    /** 用户消息落库后回写会话活跃时间(D1;解除调试覆盖,消息表仍是写入源头)。 */
    suspend fun noteUserActivity(conversationId: Long, characterId: Long, at: Long) =
        proactiveLedger.noteUserActivity(conversationId, characterId, at)

    // ── 手机聊天(F4):回溯/重生成的数据路径 ─────────────────────────

    /** 删除整个回复回合(长按 assistant 泡「回溯」),返回删除行数。 */
    suspend fun deleteTurn(conversationId: Long, turnId: String): Int =
        db.messageDao().deleteTurn(conversationId, turnId)

    /**
     * 删除该消息及其之后的所有消息(长按泡「回溯=回到该点」,F4 收尾轮)。
     * 返回删除行数(供日志);调用方负责随后刷新会话行摘要。
     */
    suspend fun deleteFromMessage(conversationId: Long, messageId: Long): Int =
        db.messageDao().deleteFromMessage(conversationId, messageId)

    /** 删除单条消息(长按用户消息「回溯到此条前」)。 */
    suspend fun deleteMessage(id: Long): Int = db.messageDao().deleteById(id)

    suspend fun messageById(id: Long): MessageEntity? = db.messageDao().byId(id)

    suspend fun lastAssistantOfTurn(conversationId: Long, turnId: String): MessageEntity? =
        db.messageDao().lastAssistantOfTurn(conversationId, turnId)

    /**
     * 删除后刷新会话行:取剩余最后一条消息回填摘要/时间;会话空了摘要清空、
     * 时间保持不动(列表仍按旧时间排序,避免行消失跳位)。摘要与发送路径
     * 同源按泡型降级(附录 C-I3):回溯/重新生成后剩余尾条为语音/贴纸/卡片
     * 泡时,转写全文/占位描述/行内标记不重回消息列表。
     */
    suspend fun refreshConversationTail(conversationId: Long) {
        val latest = db.messageDao().latestForConversation(conversationId)
        val conv = db.conversationDao().byId(conversationId) ?: return
        db.conversationDao().touch(
            conversationId,
            conversationTailSummary(latest),
            latest?.at ?: conv.lastAt,
        )
    }

    /** 消息行时间格式化:2小时前 / 2天前(对齐 02 截图节奏)。 */
    companion object {
        @Volatile private var instance: GouXiangRepo? = null

        fun get(context: Context): GouXiangRepo =
            instance ?: synchronized(this) {
                instance ?: GouXiangRepo(context).also { instance = it }
            }
    }
}

/**
 * 会话尾条 → 行摘要(纯函数,PhoneChatTest 锁定):回溯/重新生成后的会话行
 * ([GouXiangRepo.refreshConversationTail])与发送路径(ChatOrchestrator/
 * MainViewModel)共用同一降级规则(附录 C-I3)——尾条为语音/贴纸/卡片泡时,
 * 转写全文/占位描述/行内标记不重回消息列表;尾条为 null(整会话删空)返回空串。
 */
internal fun conversationTailSummary(latest: MessageEntity?): String =
    if (latest == null) "" else conversationSummary(latest.kind, latest.content, latest.actionsJson)

/** 首页推荐流卡片。 */
data class HomeFeedCard(
    val character: CharacterEntity,
    val conversationId: Long,
    val openingLine: String,
)

/** 消息列表会话行。 */
data class ConversationRow(
    val conversation: ConversationEntity,
    val character: CharacterEntity?,
) {
    /** 是否手机聊天形态(头像右下黄色小手机角标)。 */
    val isPhoneChat: Boolean get() = conversation.mode == "phone"
}
