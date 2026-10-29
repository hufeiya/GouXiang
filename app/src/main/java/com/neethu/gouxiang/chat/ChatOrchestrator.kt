package com.neethu.gouxiang.chat

import com.neethu.gouxiang.BuildConfig
import com.neethu.gouxiang.ai.adapter.ChatMessage
import com.neethu.gouxiang.ai.adapter.ChatRole
import com.neethu.gouxiang.ai.adapter.LlmConfig
import com.neethu.gouxiang.ai.adapter.LlmStreamEvent
import com.neethu.gouxiang.ai.adapter.OpenAiCompatibleLlmAdapter
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.MessageEntity
import com.neethu.gouxiang.protocol.Bubble
import com.neethu.gouxiang.protocol.MultiBubbleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 陪伴聊天编排器(聊天主页 03 的"大脑"):
 *
 * 一回合 = 用户消息落库 → 组上下文(ChatContextBuilder)→ Ark 流式请求
 * → 流文本增量合并 → 回合结束时 MultiBubbleParser 解析为 1~4 条泡
 * → 泡间带间隔地依次入库,回调层逐条上屏(连发节奏)。
 *
 * 解析放流末而非逐 delta:占位符 [sticker:…] 可能被任意拆分,边收边判
 * 会把半截占位符当正文渲染(闪错泡);泡间节奏在回调侧补齐即时感。
 *
 * 全链路失败安全:LLM 未配置/流失败/解析为空,均产一条兜底 assistant 消息,
 * 会话永远有回复、界面永远不崩。
 */
class ChatOrchestrator(
    private val daoAccess: DaoAccess,
    /** 可注入假 LLM 测试;生产默认 Ark(doubao-seed-2-0-mini,冒烟已实测连通)。 */
    private val llm: Llm,
    /** 泡间连发间隔(毫秒),对齐"短消息连发"的节奏感。 */
    private val bubbleGapMs: Long = 900L,
) {

    /** 数据侧抽象(Repo 实现;测试用 fake)。 */
    interface DaoAccess {
        suspend fun history(conversationId: Long, limit: Int): List<MessageEntity>
        suspend fun memories(characterId: Long): List<com.neethu.gouxiang.data.MemoryEntity>
        suspend fun character(characterId: Long): CharacterEntity?
        suspend fun insertMessage(message: MessageEntity)
        suspend fun touchConversation(conversationId: Long, summary: String, at: Long)
    }

    /** LLM 适配(生产 Ark;测试注入假实现)。 */
    fun interface Llm {
        fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent>
    }

    /**
     * 记忆缝(F3):回合收尾的总结通道挂点(画像判定+事件总结)。
     * null = 未装配记忆(纯聊天行为不变);由装配方(MainViewModel)注入。
     */
    fun interface TurnMemoryHook {
        suspend fun onTurnCompleted(character: CharacterEntity, userText: String, assistantText: String)
    }

    /** 记忆收尾钩子(装配方注入;默认空)。 */
    @Volatile var memoryHook: TurnMemoryHook? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * 流式草稿:回复的实时累积文本(尚未定稿入库)。
     * UI 层 collect 后显示"正在打字"的动态内容(打字气泡/预览),
     * 保证从首字到流末都有可见的逐字流式过程 —— 实机 F2 验收记录到
     * 「正在输入→整条一次性出现」的断档,即靠此通道补上中间态。
     */
    private val _streamDraft = MutableStateFlow("")
    val streamDraft: StateFlow<String> = _streamDraft.asStateFlow()

    /**
     * 一轮回复。依次发射数据层确认落库的 assistant 消息(每条=一个气泡)。
     * Flow 内完成所有副作用(发送方只 collect 渲染);单条失败不吞错——
     * 由 collect 侧决定降级提示。
     *
     * 返回本轮全部回复泡(F6 语音通话:TTS 念稿的来源;IM 聊天调用方不消费)。
     */
    suspend fun respondTo(
        conversationId: Long,
        characterId: Long,
        userText: String,
        onAssistantSaved: suspend (MessageEntity) -> Unit,
        burst: BurstParams = BurstParams.resolve(ReplyLength.DEFAULT, phone = false, DialogMode.DEFAULT),
        mode: DialogMode = DialogMode.DEFAULT,
    ): List<Bubble> {
        _running.value = true
        _streamDraft.value = ""
        try {
            return respondToInternal(conversationId, characterId, userText, onAssistantSaved, burst = burst, mode = mode)
        } finally {
            _running.value = false
            _streamDraft.value = ""
        }
    }

    /**
     * 主动消息回合(F4 范围 5):无用户输入,由 [sceneLine] 场景指令驱动角色
     * 先开口(05 号文档「角色先连发 1~3 条主动消息」)。落库/回调语义与
     * [respondTo] 完全一致;[sceneLine] 不进消息库、不进上下文(与用户消息
     * 同为"触发器",仅出现在当轮 USER 槽)。
     */
    suspend fun respondProactively(
        conversationId: Long,
        characterId: Long,
        sceneLine: String,
        onAssistantSaved: suspend (MessageEntity) -> Unit,
        burst: BurstParams = BurstParams.resolve(ReplyLength.DEFAULT, phone = true, DialogMode.DEFAULT),
        mode: DialogMode = DialogMode.DEFAULT,
    ) {
        _running.value = true
        _streamDraft.value = ""
        try {
            respondToInternal(conversationId, characterId, sceneLine, onAssistantSaved, proactive = true, burst = burst, mode = mode)
        } finally {
            _running.value = false
            _streamDraft.value = ""
        }
    }

    private suspend fun respondToInternal(
        conversationId: Long,
        characterId: Long,
        userText: String,
        onAssistantSaved: suspend (MessageEntity) -> Unit,
        proactive: Boolean = false,
        burst: BurstParams,
        mode: DialogMode,
    ): List<Bubble> {
        val turnId = if (proactive) "proactive-${UUID.randomUUID()}" else "turn-${UUID.randomUUID()}"
        val character = daoAccess.character(characterId) ?: return emptyList()
        val system = ChatContextBuilder.systemPrompt(
            character = character,
            memories = daoAccess.memories(characterId),
            maxBubbles = burst.maxBubbles,
            maxBubbleChars = burst.maxBubbleChars,
            minBubbles = burst.minBubbles,
            userText = userText,
            mode = mode,
        )
        val history = daoAccess.history(conversationId, HISTORY_LIMIT)
        val messages = buildList {
            add(ChatMessage(ChatRole.SYSTEM, system))
            addAll(ChatContextBuilder.history(history))
            add(ChatMessage(ChatRole.USER, userText))
        }

        val raw = streamRaw(messages)
        val reply = MultiBubbleParser.parse(raw)
        val parsed = reply.bubbles
        val bubbles = when {
            parsed.isEmpty() -> listOf(Bubble(Bubble.Kind.TEXT, if (proactive) PROACTIVE_FALLBACK_TEXT else FALLBACK_TEXT))
            // 单泡长文 = 模型没按连发协议分行(实机 F2 三轮全是单泡长文)。
            // 在纯文本单泡上做确定性兜底切分,保证"连发多泡"观感稳定出现;
            // 已有多行/占位结构的结果不动,尊重模型真实的分泡意图。
            parsed.size == 1 && parsed[0].kind == Bubble.Kind.TEXT ->
                splitSingleTextBubble(parsed[0])
            else -> parsed
        }
        persistBubbles(conversationId, turnId, bubbles, onAssistantSaved)
        // 记忆收尾(F3):全部泡落库后触发总结通道(fire-and-forget 语义在
        // 协调器内部;失败/取消不影响本回合)。无文本泡时 assistant 侧以
        // 动作/贴纸描述参与,同样进窗口(用户可感知事件)。主动消息回合
        // (proactive)没有用户输入,assistant 文本前缀「(TA 主动发来)」
        // 让总结通道把它读作"角色单侧发言"而非"对空回复"。
        memoryHook?.onTurnCompleted(
            character,
            if (proactive) "" else userText,
            (if (proactive) "(TA 主动发来)" else "") + bubbles.joinToString("\n") { it.text },
        )
        return bubbles
    }

    /**
     * 泡序列 → 数据库行(turnId 统一、泡间 [bubbleGapMs] 连发节奏、会话行
     * 摘要刷到最后一泡)。落库与回调按顺序交替进行(上一条上屏后才写下一
     * 条,连发观感由数据层顺序保证)。主动消息与普通回复共用此路径。
     */
    internal suspend fun persistBubbles(
        conversationId: Long,
        turnId: String,
        bubbles: List<Bubble>,
        onAssistantSaved: suspend (MessageEntity) -> Unit,
    ) {
        bubbles.forEachIndexed { index, bubble ->
            if (index > 0 && bubbleGapMs > 0) delay(bubbleGapMs)
            val entity = MessageEntity(
                conversationId = conversationId,
                turnId = turnId,
                role = "assistant",
                kind = bubble.storageKey,
                content = bubble.text,
                actionsJson = com.neethu.gouxiang.protocol.ActionsJson.encode(bubble.actions),
                at = System.currentTimeMillis(),
            )
            daoAccess.insertMessage(entity)
            daoAccess.touchConversation(conversationId, bubble.text, entity.at)
            onAssistantSaved(entity)
        }
    }

    /**
     * 单泡长文兜底切分:按 [SPLIT_MIN_CHARS] 阈值以上,沿句读标点切成
     * 2~[DEFAULT_SPLIT_MAX] 条;短句/无标点边界保持原样。
     * () 动作只挂在第一条(动作描述的是整个回合的场景,不该每泡重复)。
     */
    internal fun splitSingleTextBubble(bubble: Bubble): List<Bubble> {
        val body = bubble.text.trim()
        if (body.length < SPLIT_MIN_CHARS) return listOf(bubble)
        val parts = splitByPunctuation(body, DEFAULT_SPLIT_MAX)
        return parts.mapIndexed { i, text ->
            Bubble(Bubble.Kind.TEXT, text, if (i == 0) bubble.actions else emptyList())
        }
    }

    /**
     * 沿句读标点把长文切成 ≤[maxParts] 段:切点取"理想均匀位置"最近的标点,
     * 且保证每段 ≥[MIN_SEGMENT_CHARS] 字(避免切出叠词碎段);无可用切点单段返回。
     */
    private fun splitByPunctuation(text: String, maxParts: Int): List<String> {
        if (maxParts <= 1) return listOf(text)
        val breakChars = "。!?~;;,,".toSet()
        val candidates = text.indices.filter { i -> text[i] in breakChars && i < text.lastIndex }
        if (candidates.isEmpty()) return listOf(text)
        val target = text.length.toDouble() / maxParts
        val cuts = mutableListOf<Int>()
        for (k in 1 until maxParts) {
            val ideal = (k * target).toInt()
            val best = candidates.minByOrNull { kotlin.math.abs(it - ideal) } ?: continue
            val segStart = (cuts.lastOrNull()?.plus(1)) ?: 0
            if (best in cuts) continue
            if (best + 1 - segStart < MIN_SEGMENT_CHARS) continue
            if (text.length - (best + 1) < MIN_SEGMENT_CHARS) continue
            cuts += best
        }
        if (cuts.isEmpty()) return listOf(text)
        val segments = mutableListOf<String>()
        var start = 0
        for (cut in cuts.sorted()) {
            segments += text.substring(start, cut + 1).trim()
            start = cut + 1
        }
        if (start < text.length) segments += text.substring(start).trim()
        return segments.filter { it.isNotEmpty() }
    }

    /** 合并流事件为最终文本;错误事件返回兜底文案(连接不上也有回复)。 */
    private suspend fun streamRaw(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        llm.streamChat(messages, llmConfig()).collect { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> {
                    sb.append(event.text)
                    _streamDraft.value = sb.toString()
                }
                is LlmStreamEvent.Error -> if (sb.isBlank()) {
                    sb.append(FALLBACK_TEXT)
                }
                else -> Unit
            }
        }
        sb.toString()
    }

    private fun llmConfig() = LlmConfig(
        baseUrl = BuildConfig.VOLCANO_ARK_BASE_URL,
        apiKey = BuildConfig.VOLCANO_ARK_API_KEY,
        model = MODEL,
        temperature = 0.9f,
        topP = 0.9f,
        maxTokens = 512,
    )

    /** 记忆通道的请求配置:同端点同模型(temperature 由抽取层覆盖为稳态)。 */
    fun llmConfigForMemory(): LlmConfig = LlmConfig(
        baseUrl = BuildConfig.VOLCANO_ARK_BASE_URL,
        apiKey = BuildConfig.VOLCANO_ARK_API_KEY,
        model = MODEL,
        temperature = 0.2f,
        topP = 0.9f,
        maxTokens = 512,
    )

    companion object {
        /** Ark 在售最小档(冒烟测试同款,2026-10-28 实查)。 */
        const val MODEL = "doubao-seed-2-0-mini-260428"

        /** 上下文窗口:最近 20 条对话(多泡协议下 ≈6~10 轮)。 */
        const val HISTORY_LIMIT = 20

        /** 兜底回复(角色语感、贴合人设)。 */
        const val FALLBACK_TEXT = "(戳了戳你)……信号有点糟,再说一遍好不好?"

        /** 主动消息回合的兜底(无用户输入可归因,文案描述"TA 先开了口")。 */
        const val PROACTIVE_FALLBACK_TEXT = "(抱着手机等了好一会儿)……你终于来啦"

        /** 单泡长文触发兜底切分的字数下限(30 字/条的协议约束下,低于它不该拆)。 */
        const val SPLIT_MIN_CHARS = 20

        /** 兜底切分后每段的最短字数(防叠词碎段)。 */
        const val MIN_SEGMENT_CHARS = 6

        /** 生产 LLM:Ark 流式适配器(移植自 AIAvatar-SDK,已实查连通)。 */
        fun defaultLlm(): Llm = Llm { messages, config ->
            OpenAiCompatibleLlmAdapter(config.baseUrl, config.apiKey).streamChat(messages, config)
        }
    }
}

/** Bubble → messages.kind 存储键(对齐 MessageEntity 注释约定)。 */
internal val Bubble.storageKey: String
    get() = when (kind) {
        Bubble.Kind.TEXT -> "text"
        Bubble.Kind.STICKER -> "sticker"
        Bubble.Kind.IMAGE -> "img"
        Bubble.Kind.VOICE -> "voice"
    }
