package com.neethu.gouxiang.data

import android.content.Context
import androidx.room.Room
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val db: GouXiangDatabase = Room.databaseBuilder(
        context.applicationContext,
        GouXiangDatabase::class.java,
        GouXiangDatabase.NAME,
    ).addMigrations(
        GouXiangDatabase.MIGRATION_1_2,
        GouXiangDatabase.MIGRATION_2_3,
        GouXiangDatabase.MIGRATION_3_4,
        GouXiangDatabase.MIGRATION_4_5,
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

    // ── 朋友圈(F9「Ta 的朋友圈」)────────────────────────────────────

    /** 动态时间线流(朋友圈页与角色主页占位卡共用,落库即上屏)。 */
    fun observeMoments(characterId: Long): Flow<List<MomentEntity>> =
        db.momentDao().observeForCharacter(characterId)

    suspend fun moments(characterId: Long): List<MomentEntity> =
        db.momentDao().forCharacter(characterId)

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

    /** 消息落库后刷新会话行摘要(最近时间/预览文案,消息列表 02 同步可见)。 */
    suspend fun touchConversation(conversationId: Long, summary: String, at: Long) =
        db.conversationDao().touch(conversationId, summary, at)

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
