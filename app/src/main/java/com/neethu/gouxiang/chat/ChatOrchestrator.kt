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
import com.neethu.gouxiang.protocol.conversationSummary
import com.neethu.gouxiang.protocol.parseCardLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

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
 * 失败语义(02 文档 §3.5「回合失败显式化」):空输出/主动回合失败仍出一条
 * 兜底 assistant 消息(会话不空窗);但用户回合的流失败不再伪装成角色回复
 * ——见 [TurnResult.Failed],由 UI 出「消息发送失败,请点击重试」重试条。
 */
class ChatOrchestrator(
    private val daoAccess: DaoAccess,
    /** 可注入假 LLM 测试;生产默认 Ark(doubao-seed-2-0-mini,冒烟已实测连通)。 */
    private val llm: Llm,
    /**
     * 大模型端点源(「我的 → AI 服务配置」可改,每回合现取现组——配置页改完
     * 即生效无需重启):缺省回落 BuildConfig 烘入的火山方舟默认链路,未配置时
     * 行为与「密钥烘进 BuildConfig」时代完全一致。
     */
    private val llmEndpointSource: () -> com.neethu.gouxiang.ai.config.LlmEndpoint =
        { DEFAULT_ENDPOINT },
    /**
     * 泡间连发节奏开关(毫秒):>0 时每泡间按 600~1200ms 随机间隔连发
     * (04 号文档「条间隔 600~1200ms 随机」,区间见 [BUBBLE_GAP_MIN_MS]/
     * [BUBBLE_GAP_MAX_MS]);0 = 完全跳过延迟(测试通路,既有测试注入 0)。
     */
    private val bubbleGapMs: Long = 900L,
    /** 首 delta 看门狗阈值(毫秒):首个文本增量前等待超过它判回合失败,测试注入小值。 */
    private val firstDeltaTimeoutMs: Long = FIRST_DELTA_TIMEOUT_MS,
    /** 间隔随机源:生产默认随机;测试注入定种 Random 断言区间与随机性。 */
    private val random: Random = Random.Default,
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
     * 返回 [TurnResult]:正常完成携带本轮全部回复泡(F6 语音通话:TTS 念稿的
     * 来源;IM 聊天调用方不消费);流失败显式返回 [TurnResult.Failed],不落
     * 任何 assistant 泡(02 文档 §3.5,由 UI 出重试条)。
     */
    suspend fun respondTo(
        conversationId: Long,
        characterId: Long,
        userText: String,
        onAssistantSaved: suspend (MessageEntity) -> Unit,
        burst: BurstParams = BurstParams.resolve(ReplyLength.DEFAULT, phone = false, DialogMode.DEFAULT),
        mode: DialogMode = DialogMode.DEFAULT,
    ): TurnResult {
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
    ): TurnResult {
        val turnId = if (proactive) "proactive-${UUID.randomUUID()}" else "turn-${UUID.randomUUID()}"
        val character = daoAccess.character(characterId) ?: return TurnResult.Completed(emptyList())
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

        val stream = streamRaw(messages)
        if (stream.failed) {
            // 回合失败(02 文档 §3.5):用户回合不落任何 assistant 泡、不触发
            // 记忆收尾,失败显式上抛交由 UI 出「消息发送失败,请点击重试」;
            // 主动回合无用户输入可归因,维持「兜底一条」的既有行为(文案用
            // 主动语感的 PROACTIVE_FALLBACK_TEXT,不再借用用户回合兜底)。
            if (proactive) {
                val fallback = listOf(Bubble(Bubble.Kind.TEXT, PROACTIVE_FALLBACK_TEXT))
                persistBubbles(conversationId, turnId, fallback, onAssistantSaved)
                memoryHook?.onTurnCompleted(character, "", "(TA 主动发来)$PROACTIVE_FALLBACK_TEXT")
                return TurnResult.Completed(fallback)
            }
            return TurnResult.Failed(userText)
        }
        val reply = MultiBubbleParser.parse(stream.text)
        val parsed = reply.bubbles
        val bubbles = when {
            parsed.isEmpty() -> listOf(Bubble(Bubble.Kind.TEXT, if (proactive) PROACTIVE_FALLBACK_TEXT else FALLBACK_TEXT))
            // 单泡长文 = 模型没按连发协议分行(实机 F2 三轮全是单泡长文)。
            // 文档口径(02 号文档):flush 时若全回复无分隔但超长,即 >2×
            // maxBubbleChars(默认 30 字上限即 >60 字),才按标点强制切分;
            // 阈值内(20~60 字)的单泡尊重模型真实的单泡意图,不再按旧 20 字
            // 阈值强切。已有多行/占位结构的结果同样不动。
            parsed.size == 1 && parsed[0].kind == Bubble.Kind.TEXT ->
                splitSingleTextBubble(parsed[0], burst.maxBubbleChars)
            else -> parsed
        }
        // 日常分享泡(02 文档 §3.3:每次回复可附 0~1 条,独立一泡渲染):
        // 作为同 turnId 的独立一泡排在主泡之后落库。兜底切分只作用于上面的
        // 主泡序列,分享泡不参与;MAX_BUBBLES 截断已在解析层对 bubbles 完成,
        // 分享泡按协议 0~1 条不占额。proactivity 为 null 时 listOfNotNull
        // 原样返回主泡序列,行为与旧通路逐字节一致。
        val withShare = bubbles + listOfNotNull(reply.proactivity)
        persistBubbles(conversationId, turnId, withShare, onAssistantSaved)
        // 记忆收尾(F3):全部泡落库后触发总结通道(fire-and-forget 语义在
        // 协调器内部;失败/取消不影响本回合)。无文本泡时 assistant 侧以
        // 动作/贴纸描述参与,同样进窗口(用户可感知事件)。主动消息回合
        // (proactive)没有用户输入,assistant 文本前缀「(TA 主动发来)」
        // 让总结通道把它读作"角色单侧发言"而非"对空回复"。
        memoryHook?.onTurnCompleted(
            character,
            if (proactive) "" else userText,
            (if (proactive) "(TA 主动发来)" else "") + withShare.joinToString("\n") { it.text },
        )
        return TurnResult.Completed(withShare)
    }

    /**
     * 泡序列 → 数据库行(turnId 统一、泡间按 [nextBubbleGapMs] 连发节奏、
     * 会话行摘要刷到最后一泡)。落库与回调按顺序交替进行(上一条上屏后才写下一
     * 条,连发观感由数据层顺序保证)。主动消息与普通回复共用此路径。
     */
    internal suspend fun persistBubbles(
        conversationId: Long,
        turnId: String,
        bubbles: List<Bubble>,
        onAssistantSaved: suspend (MessageEntity) -> Unit,
    ) {
        bubbles.forEachIndexed { index, bubble ->
            // 泡间节奏:开关开启([bubbleGapMs] > 0)时从 600~1200ms 随机取间隔
            // (04 号文档);0 继续完全跳过,不进 delay 分支(测试零等待)。
            if (index > 0 && bubbleGapMs > 0) delay(nextBubbleGapMs())
            val entity = MessageEntity(
                conversationId = conversationId,
                turnId = turnId,
                role = "assistant",
                kind = bubble.storageKey,
                content = bubble.text,
                actionsJson = when (bubble.kind) {
                    // 卡片泡(端到端打通):content 保留整行原样,载荷由行内标记
                    // 解出存 actionsJson;解析失败降级为动作 JSON(渲染层
                    // CardPayload.decode 为 null → 文本兜底),不崩。
                    Bubble.Kind.CARD ->
                        parseCardLine(bubble.text)?.encode()
                            ?: com.neethu.gouxiang.protocol.ActionsJson.encode(bubble.actions)
                    else -> com.neethu.gouxiang.protocol.ActionsJson.encode(bubble.actions)
                },
                at = System.currentTimeMillis(),
            )
            daoAccess.insertMessage(entity)
            // 会话行摘要按泡型降级(附录 C-I3):语音/表情/图片/卡片泡不再把
            // 裸文本(转写全文/占位描述/行内标记)刷进消息列表;TEXT 泡摘要仍=文本。
            daoAccess.touchConversation(
                conversationId,
                conversationSummary(entity.kind, bubble.text, entity.actionsJson),
                entity.at,
            )
            onAssistantSaved(entity)
        }
    }

    /**
     * 泡间连发间隔抽样(可测纯函数,04 号文档「条间隔 600~1200ms 随机」):
     * 从 [BUBBLE_GAP_MIN_MS]~[BUBBLE_GAP_MAX_MS](含)均匀取值;[random] 可注入,
     * 单测以定种 Random 锁定区间边界与随机性。
     */
    internal fun nextBubbleGapMs(): Long =
        random.nextLong(BUBBLE_GAP_MIN_MS, BUBBLE_GAP_MAX_MS + 1)

    /**
     * 单泡长文兜底切分(02 号文档口径:flush 时若全回复无分隔但超长,即
     * >2×[maxBubbleChars] 字,按标点强制切分):沿句读标点切成
     * 2~[DEFAULT_SPLIT_MAX] 条;阈值内(≤2×上限)或无标点边界保持原样。
     * () 动作只挂在第一条(动作描述的是整个回合的场景,不该每泡重复)。
     */
    internal fun splitSingleTextBubble(bubble: Bubble, maxBubbleChars: Int): List<Bubble> {
        val body = bubble.text.trim()
        if (body.length <= 2 * maxBubbleChars) return listOf(bubble)
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

    /**
     * 流事件合并结果:[failed] = 回合失败(02 文档 §3.5「回合失败显式化」)——
     * 收到 Error 且尚无任何文本增量(无内容可保留),或首个文本增量前等待超过
     * [firstDeltaTimeoutMs]。已有内容后收到 Error 保留半截文本维持现状;
     * 全程无 Error 的空输出不判失败(空输出兜底为不改的边界)。
     */
    private data class Streamed(val text: String, val failed: Boolean)

    /**
     * 合并流事件为最终文本,同时判定回合是否失败:
     * - 首 delta 看门狗(02 文档 §3.5:30s 无首个文本增量 → 回合失败):只约束
     *   「等首个文本增量」阶段,首 delta 到达即撤表(模型已在产出,不再计时);
     * - Error 事件在尚无内容时判失败(此前伪装 FALLBACK_TEXT 兜底,已移除)。
     * 适配器硬异常照旧向上抛(与旧行为一致,由调用侧日志兜底)。
     */
    private suspend fun streamRaw(messages: List<ChatMessage>): Streamed = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        var failed = false
        val firstDeltaSeen = AtomicBoolean(false)
        coroutineScope {
            var collectJob: Job? = null
            val watchdog = launch {
                delay(firstDeltaTimeoutMs)
                // 首个文本增量仍未到达:取消收集,本回合按失败处理
                // (显式取消子任务不向父作用域传播,collect 侧 finally 照常收尾)。
                if (!firstDeltaSeen.get()) {
                    failed = true
                    collectJob?.cancel()
                }
            }
            collectJob = launch {
                try {
                    llm.streamChat(messages, llmConfig()).collect { event ->
                        when (event) {
                            is LlmStreamEvent.TextDelta -> {
                                firstDeltaSeen.set(true)
                                sb.append(event.text)
                                _streamDraft.value = sb.toString()
                            }
                            is LlmStreamEvent.Error -> if (sb.isBlank()) failed = true
                            else -> Unit
                        }
                    }
                } finally {
                    watchdog.cancel()
                }
            }
            collectJob!!.join()
            watchdog.join()
        }
        Streamed(text = sb.toString(), failed = failed)
    }

    private fun llmConfig() = llmEndpointSource().let { e ->
        LlmConfig(
            baseUrl = e.baseUrl,
            apiKey = e.apiKey,
            model = e.model,
            temperature = 0.9f,
            topP = 0.9f,
            maxTokens = 512,
            extraBody = e.extraBody,
        )
    }

    /** 记忆通道的请求配置:同端点同模型(temperature 由抽取层覆盖为稳态)。 */
    fun llmConfigForMemory(): LlmConfig = llmEndpointSource().let { e ->
        LlmConfig(
            baseUrl = e.baseUrl,
            apiKey = e.apiKey,
            model = e.model,
            temperature = 0.2f,
            topP = 0.9f,
            maxTokens = 512,
            extraBody = e.extraBody,
        )
    }

    companion object {
        /** Ark 在售最小档(冒烟测试同款,2026-10-28 实查)。 */
        const val MODEL = "doubao-seed-2-0-mini-260428"

        /**
         * 缺省端点:BuildConfig 烘入的火山方舟默认链路(MODEL 与火山目录默认
         * 首位同款)。extraBody 带上 doubao-seed 系的关思考方言(思考 token 走
         * reasoning_content 而适配器只读 delta.content,不关就是首句前的纯等待)。
         */
        val DEFAULT_ENDPOINT = com.neethu.gouxiang.ai.config.LlmEndpoint(
            baseUrl = BuildConfig.VOLCANO_ARK_BASE_URL,
            apiKey = BuildConfig.VOLCANO_ARK_API_KEY,
            model = MODEL,
            extraBody = com.neethu.gouxiang.ai.config.llmExtraBody(
                com.neethu.gouxiang.ai.config.AiProvider.VOLCANO, MODEL,
            ),
        )

        /** 上下文窗口:最近 20 条对话(多泡协议下 ≈6~10 轮)。 */
        const val HISTORY_LIMIT = 20

        /** 首 delta 看门狗阈值(02 文档 §3.5:30s 无首个文本增量 → 回合失败)。 */
        const val FIRST_DELTA_TIMEOUT_MS = 30_000L

        /** 兜底回复(角色语感、贴合人设;仅空输出回合使用,流失败不再注入)。 */
        const val FALLBACK_TEXT = "(戳了戳你)……信号有点糟,再说一遍好不好?"

        /** 主动消息回合的兜底(无用户输入可归因,文案描述"TA 先开了口")。 */
        const val PROACTIVE_FALLBACK_TEXT = "(抱着手机等了好一会儿)……你终于来啦"

        /**
         * 泡间连发间隔随机区间(04 号文档「条间隔 600~1200ms 随机」):
         * [bubbleGapMs] > 0 时每泡间从该区间抽样;= 0 仍完全跳过(测试通路)。
         */
        const val BUBBLE_GAP_MIN_MS = 600L

        /** 泡间连发间隔随机区间上限(含)。 */
        const val BUBBLE_GAP_MAX_MS = 1200L

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
        Bubble.Kind.CARD -> "card"
    }

/**
 * 回合结果(02 文档 §3.5「回合失败显式化」):不再把流失败伪装成角色回复。
 * 用户回合失败 → [Failed],不落任何 assistant 泡,由 UI 出「消息发送失败,
 * 请点击重试」失败条并按原用户文本重试;主动回合失败在编排器内部兜底落库。
 */
sealed interface TurnResult {
    /** 正常完成:回复泡(含分享泡)已按序落库,[bubbles] 即本回合全部泡。 */
    data class Completed(val bubbles: List<Bubble>) : TurnResult

    /** 回合失败(流 Error 无内容 / 首个文本增量超时);[userText] 为本回合用户原文。 */
    data class Failed(val userText: String) : TurnResult
}
