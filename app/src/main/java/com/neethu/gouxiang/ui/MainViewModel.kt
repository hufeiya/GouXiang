package com.neethu.gouxiang.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neethu.gouxiang.chat.BurstParams
import com.neethu.gouxiang.chat.ChatMemoryCoordinator
import com.neethu.gouxiang.chat.ChatOrchestrator
import com.neethu.gouxiang.chat.ChatPrefs
import com.neethu.gouxiang.chat.ChatStyleParams
import com.neethu.gouxiang.chat.DialogMode
import com.neethu.gouxiang.chat.InnerVoiceChannel
import com.neethu.gouxiang.chat.InnerVoiceGenerator
import com.neethu.gouxiang.chat.MemoryBook
import com.neethu.gouxiang.chat.MemoryCategories
import com.neethu.gouxiang.chat.MemoryExtractor
import com.neethu.gouxiang.chat.ReplyLength
import com.neethu.gouxiang.chat.StatusRule
import com.neethu.gouxiang.protocol.conversationSummary
import com.neethu.gouxiang.protocol.toProtocolLine
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.ChatDaoAccess
import com.neethu.gouxiang.data.ConversationRow
import com.neethu.gouxiang.data.GouXiangRepo
import com.neethu.gouxiang.data.HomeFeedCard
import com.neethu.gouxiang.data.MessageEntity
import com.neethu.gouxiang.ui.nav.BottomTab
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 主框架状态:底栏选中 tab + 首页推荐流 + 消息列表 + 聊天主页导航 + 头像缓存路径。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = GouXiangRepo.get(app)
    private val orchestrator =
        ChatOrchestrator(
            ChatDaoAccess(repo),
            llm = ChatOrchestrator.defaultLlm(),
            // 大模型端点跟随「我的 → AI 服务配置」(每回合现读,改完即生效;
            // 留空回落 BuildConfig 内置默认)
            llmEndpointSource = {
                com.neethu.gouxiang.ai.config.AiConfigStore.load(app).llmEndpoint()
            },
        )

    // ── 记忆系统(F3):书 + 抽取通道 + 协调器,回合收尾挂进编排器 ──────
    val memoryBook = MemoryBook(com.neethu.gouxiang.data.RoomMemorySource(repo)).also {
        it.characterNameResolver = { characterId -> repo.characterById(characterId)?.name.orEmpty() }
    }
    private val memoryExtractor = MemoryExtractor(
        book = memoryBook,
        llm = ChatOrchestrator.defaultLlm(),
        llmConfig = { orchestrator.llmConfigForMemory() },
    )
    private val memoryCoordinator = ChatMemoryCoordinator(
        source = com.neethu.gouxiang.data.RoomMemorySource(repo),
        book = memoryBook,
        extractor = memoryExtractor,
        scope = viewModelScope,
        windowStore = com.neethu.gouxiang.data.RoomMemoryWindowStore(repo),
    )

    private val durableMemory = com.neethu.gouxiang.chat.DurableMemoryProcessor(repo.db, memoryExtractor)

    init {
        orchestrator.detailedMemoryHook = { character, conversationId, _, _, _, _ ->
            val pending = durableMemory.pending(conversationId)
            val chars = pending.sumOf { it.user.length + it.assistant.length }
            if (memoryCoordinator.autoSummarize && (pending.size >= memoryCoordinator.everyTurns || chars >= memoryCoordinator.minChars)) {
                viewModelScope.launch { durableMemory.run(character, conversationId) }
            }
        }
    }

    val selectedTab = MutableStateFlow(BottomTab.HOME)

    // ── AI 原生调试出口(docs/ai-debug-intents.md §7.3;均只读,不改状态)──

    /** 当前会话 id 的只读暴露(`msgs` 等命令的默认目标;null=未打开聊天)。 */
    val debugConversationId: Long? get() = _chatConversationId.value

    /** 是否手机聊天形态(发送侧 phoneMode 参数与 `state` 回显用)。 */
    val debugPhoneMode: Boolean get() = _isPhoneChat.value

    /** 当前会话最近 N 条消息(`msgs` 命令;穿透 repo 到 MessageDao.recentForConversation)。 */
    suspend fun debugMessages(conversationId: Long, limit: Int): List<MessageEntity> =
        repo.recentMessages(conversationId, limit)

    /** 首页 01:推荐流剧卡。 */
    val homeFeed: StateFlow<List<HomeFeedCard>> = repo.homeFeed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 消息 02:聊过会话行。 */
    val conversations: StateFlow<List<ConversationRow>> = repo.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 聊天主页 03 打开的会话 id(null=未进入)。 */
    private val _chatConversationId = MutableStateFlow<Long?>(null)
    val chatConversationId: StateFlow<Long?> = _chatConversationId.asStateFlow()

    /** 当前打开的是否手机聊天形态(F4;消息列表按会话 mode=phone 入口)。 */
    private val _isPhoneChat = MutableStateFlow(false)
    val isPhoneChat: StateFlow<Boolean> = _isPhoneChat.asStateFlow()

    /** characterId → 头像缓存文件绝对路径(null=未生成,UI 画占位)。 */
    private val _avatarFiles = MutableStateFlow<Map<Long, String?>>(emptyMap())
    val avatarFiles: StateFlow<Map<Long, String?>> = _avatarFiles

    // ── 对话风格(F5:角色主页 04 宫格弹层 09/10)──────────────────────

    /** 当前对话风格组合(弹层选择即时写回;回合发起时按它映射连发参数)。 */
    private val _chatStyle = MutableStateFlow(ChatStyleParams())
    val chatStyle: StateFlow<ChatStyleParams> = _chatStyle.asStateFlow()

    init {
        // 启动即回填持久化的对话风格(主页宫格副标题/弹层选中态直接可用)
        val app = getApplication<Application>()
        _chatStyle.value = ChatStyleParams(
            mode = ChatPrefs.dialogMode(app),
            length = ChatPrefs.replyLength(app),
        )
    }

    /** 弹层 09:切换对话模式(即时持久化;下一回合的 system prompt 带上行为段)。 */
    fun selectDialogMode(mode: DialogMode) {
        ChatPrefs.setDialogMode(getApplication(), mode)
        _chatStyle.value = _chatStyle.value.copy(mode = mode)
    }

    /** 弹层 10:切换回复长度(即时持久化;下一回合按 [BurstParams] 重算连发参数)。 */
    fun selectReplyLength(length: ReplyLength) {
        ChatPrefs.setReplyLength(getApplication(), length)
        _chatStyle.value = _chatStyle.value.copy(length = length)
    }

    // ── 角色主页 04(聊天页左下角「名字 >」进入)──────────────────────

    /** 角色主页打开的会话 id(null=未进入;主页状态条与聊天页共用同一数据)。 */
    private val _characterHomeConversationId = MutableStateFlow<Long?>(null)
    val characterHomeConversationId: StateFlow<Long?> = _characterHomeConversationId.asStateFlow()

    /** 角色主页 UI 状态(角色卡 + 与聊天页同源的状态条)。 */
    private val _characterHomeUi = MutableStateFlow(CharacterHomeUiState())
    val characterHomeUi: StateFlow<CharacterHomeUiState> = _characterHomeUi.asStateFlow()

    private var characterHomeJob: Job? = null

    /** 打开角色主页:按会话取角色 + 订阅消息流刷状态条(与聊天页数据一致)。 */
    fun openCharacterHome(conversationId: Long) {
        if (_characterHomeConversationId.value == conversationId) return
        _characterHomeConversationId.value = conversationId
        _characterHomeUi.value = CharacterHomeUiState(loading = true)
        characterHomeJob?.cancel()
        characterHomeJob = viewModelScope.launch {
            val conv = repo.db.conversationDao().byId(conversationId)
            val character = conv?.let { repo.characterById(it.characterId) }
            if (conv == null || character == null) {
                _characterHomeUi.value = _characterHomeUi.value.copy(loading = false, loadFailed = true)
                return@launch
            }
            _characterHomeUi.value = _characterHomeUi.value.copy(character = character, loading = false)
            ensureAvatars(listOf(character))
            // 心声/朋友圈占位卡尾注(F8「Ta 的心声 · 全部 N」/F9「Ta 的朋友圈 · 全部 N」;
            // 页面打开后再走流刷新)
            _characterHomeUi.value = _characterHomeUi.value.copy(
                innerVoiceCount = repo.countInnerVoices(character.id),
                momentCount = repo.countMoments(character.id),
            )
            repo.observeMessages(conversationId).collect { messages ->
                _characterHomeUi.value = _characterHomeUi.value.copy(
                    status = StatusRule.generate(character, messages),
                )
            }
        }
    }

    fun closeCharacterHome() {
        characterHomeJob?.cancel()
        characterHomeJob = null
        _characterHomeConversationId.value = null
        _characterHomeUi.value = CharacterHomeUiState()
    }

    // ── 聊天主页 03 ─────────────────────────────────────────────────

    /** 聊天页 UI 状态(消息气泡组/状态条/发送中)。 */
    private val _chatUi = MutableStateFlow(ChatUiState())
    val chatUi: StateFlow<ChatUiState> = _chatUi.asStateFlow()

    /** 回合进行中的流式草稿(逐字累积),驱动"正在打字"的动态泡。 */
    private val _streamDraft = MutableStateFlow("")
    val streamDraft: StateFlow<String> = _streamDraft.asStateFlow()

    private var messagesJob: Job? = null
    private var draftJob: Job? = null

    /** 在联回合协程(发消息/主动连发/重新生成共用一个槽):回溯时打断它,
     * 防止已回溯回合的后续泡继续落库(新 id 恒大于删除点,成幽灵残尾)。 */
    private var roundJob: Job? = null

    /**
     * 从消息列表进入聊天主页:载入全量历史并订阅 Room 流(新增消息自动上屏)。
     *
     * [phoneMode] = 走手机聊天形态(会话 mode=phone 的角标入口,F4 范围 1):
     * 顶栏/气泡/输入面板换肤,连发协议收紧为 2~4 条、每条更短;同一份
     * 角色记忆([ChatOrchestrator] 共用 daoAccess.memories)。
     */
    fun openChat(conversationId: Long, phoneMode: Boolean = false) {
        if (_chatConversationId.value == conversationId) return
        _chatConversationId.value = conversationId
        _isPhoneChat.value = phoneMode
        // 重建整态:上一会话遗留的失败回合条(failedTurn)一并清空
        _chatUi.value = ChatUiState(conversationId = conversationId, loading = true)
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            val conv = repo.db.conversationDao().byId(conversationId)
            val character = conv?.let { repo.characterById(it.characterId) }
            if (conv == null || character == null) {
                _chatUi.value = _chatUi.value.copy(loading = false, loadFailed = true)
                return@launch
            }
            _chatUi.value = _chatUi.value.copy(character = character, loading = false)
            // 形象缓存兜底(与首页/消息页同一 provider,命中即返回)
            ensureAvatars(listOf(character))
            val all = repo.allMessages(conversationId)
            _chatUi.value = _chatUi.value.copy(
                bubbleItems = toBubbleItems(all),
                status = StatusRule.generate(character, all),
            )
            // F4 主动消息(F4 范围 5 + §4 M1.3 outbox 优先):进入手机聊天页时
            // 先查离线 outbox(离线期 Worker 已生成的 proactive 轮次——判据:
            // 存在晚于最后一条用户消息的 proactive 轮,即离线期间角色先发了话);
            // 无 outbox 才按阈值判定现场生成(既有行为降级为兜底路径)。
            if (phoneMode) {
                val all = repo.allMessages(conversationId)
                val lastUserAt = all.lastOrNull { it.role == "user" }?.at ?: 0L
                val hasOutbox = all.any {
                    it.turnId.startsWith("proactive-") && it.at > lastUserAt
                }
                if (!hasOutbox) {
                    pickProactiveTrigger(conversationId)?.let { trigger ->
                        sendProactiveMessages(
                            trigger,
                            offlineMs = System.currentTimeMillis() - conv.lastAt,
                        )
                    }
                }
                // outbox 消费:气泡照常经 Room 流上屏,无需额外动作(§2.3
                // 聊天页红点属于 M1 后续,当前通知直跳即达)。
            }
            repo.observeMessages(conversationId).collect { messages ->
                _chatUi.value = _chatUi.value.copy(
                    bubbleItems = toBubbleItems(messages),
                    status = StatusRule.generate(character, messages),
                )
            }
        }
    }

    fun closeChat() {
        messagesJob?.cancel()
        messagesJob = null
        draftJob?.cancel()
        draftJob = null
        _streamDraft.value = ""
        stopVoicePlayback()
        cancelVoiceInput()
        _chatConversationId.value = null
        _isPhoneChat.value = false
        // 重建整态:离开聊天页即清空失败回合条(failedTurn)
        _chatUi.value = ChatUiState()
    }

    /**
     * 发送一条用户消息 → 编排器整回合(用户消息落库 + 流式回复分泡连发)。
     * 上屏靠 Room 流:任何一条入库都会推 [chatUi] 更新。
     *
     * [phoneMode] = 手机聊天(F4):连发区间放宽 2~4 条、每条更短(≤24 字);
     * 用户消息里的卡片行([sendCard] 编码的 [卡片:…] 标记)按 kind=card +
     * 载荷落库(见 [buildUserMessageEntity]),其余按纯文本上屏。
     */
    fun sendChatMessage(text: String, phoneMode: Boolean = false) {
        val state = _chatUi.value
        val conversationId = state.conversationId
        val character = state.character ?: return
        val body = text.trim()
        if (body.isEmpty() || state.sending) return
        _chatUi.value = state.copy(sending = true)
        roundJob = viewModelScope.launch {
            var userMessageId: Long? = null
            try {
                // 用户消息先落库(单写者约定:编排器只管回复侧)
                val userMsg = buildUserMessageEntity(conversationId, body, System.currentTimeMillis())
                userMessageId = repo.insertMessage(userMsg)
                // 会话行摘要按泡型降级(附录 C-I3):用户卡片行 kind=card,摘要走
                // 载荷摘要,防 [卡片:…] 原始标记泄露;纯文本摘要仍=原文。
                repo.touchConversation(
                    conversationId,
                    conversationSummary(userMsg.kind, userMsg.content, userMsg.actionsJson),
                    userMsg.at,
                )
                // 回合失败显式化(02 文档 §3.5):失败记 failedTurn 出重试条,
                // 成功清 failedTurn(失败后另发新消息成功同样撤旧条)
                _chatUi.value = _chatUi.value.afterTurnResult(runRound(conversationId, character.id, body, phoneMode, userMessageId), userMessageId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 编排器内已显式失败,这里防数据库层等硬异常把协程吞掉;
                // 同样置失败态,发送不再静默无反馈
                android.util.Log.e("ChatOrchestrator", "round failed", e)
                _chatUi.value = _chatUi.value.copy(failedTurn = FailedTurn(conversationId, body, userMessageId))
            } finally {
                _chatUi.value = _chatUi.value.copy(sending = false)
            }
        }
    }

    /**
     * 失败回合重试(02 文档 §3.5:失败条「消息发送失败,请点击重试」):
     * 点击即清 failedTurn 转发送中态,按原用户文本重跑回复侧 —— 用户消息
     * 已在库,不重复落库;重试成功撤条,再失败重新记失败态等待再次重试。
     */
    fun retryFailedTurn() {
        val state = _chatUi.value
        val failed = state.failedTurn ?: return
        val character = state.character ?: return
        if (state.sending) return
        _chatUi.value = state.copy(sending = true, failedTurn = null)
        roundJob = viewModelScope.launch {
            try {
                _chatUi.value = _chatUi.value.afterTurnResult(
                    runRound(failed.conversationId, character.id, failed.userText, phoneMode = _isPhoneChat.value, userMessageId = failed.userMessageId),
                    failed.userMessageId,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatOrchestrator", "retry failed", e)
                _chatUi.value = _chatUi.value.copy(failedTurn = failed)
            } finally {
                _chatUi.value = _chatUi.value.copy(sending = false)
            }
        }
    }

    /** 手机聊天「+」面板提交的卡片:编码为行内协议标记发送,入库侧自动落 kind=card。
     * F2 金额校验兜底(UI 校验的最后防线):转账/代付空金额静默拦截。
     */
    fun sendCard(payload: com.neethu.gouxiang.protocol.CardPayload) {
        if (isPhoneChat.value.not()) return
        if (!payload.sendable) return
        sendChatMessage(payload.toProtocolLine(), phoneMode = true)
    }

    // ── 语音通话 12(F6)──────────────────────────────────────────────

    /** 语音通话打开的会话 id(null=未进入;入口:聊天页输入条电话图标)。 */
    private val _voiceCallConversationId = MutableStateFlow<Long?>(null)
    val voiceCallConversationId: StateFlow<Long?> = _voiceCallConversationId.asStateFlow()

    /**
     * 语音通话引擎(按会话缓存:来回挂断不丢字幕态;[VoiceCallEngine.runChatRound]
     * 接到 [runRound] —— 用户消息落库/会话行/记忆收尾与 IM 完全同路)。
     */
    private val voiceCallEngines = mutableMapOf<Long, com.neethu.gouxiang.ai.speech.VoiceCallEngine>()

    fun voiceCallEngine(conversationId: Long): com.neethu.gouxiang.ai.speech.VoiceCallEngine {
        val convId = conversationId
        return voiceCallEngines.getOrPut(convId) {
            com.neethu.gouxiang.ai.speech.VoiceCallEngine(
                app = getApplication(),
                scope = viewModelScope,
                // 通话合成/识别的 Key、音色与 ASR 模型跟随「我的 → AI 服务配置」
                aiConfigSource = {
                    com.neethu.gouxiang.ai.config.AiConfigStore.load(getApplication())
                },
                runChatRound = { userText ->
                    val conv = repo.db.conversationDao().byId(convId) ?: return@VoiceCallEngine emptyList()
                    val character = repo.characterById(conv.characterId) ?: return@VoiceCallEngine emptyList()
                    // 用户消息先落库(单写者约定:编排器只管回复侧;与 sendChatMessage
                    // 同一结构,但 turnId 带时间戳独立成回合)——语音回合的 ASR 文本
                    // 也必须是聊天记录里的一等公民(范围 4:挂断后消息已保存)。
                    val userMsg = MessageEntity(
                        conversationId = convId,
                        turnId = "turn-user-${System.currentTimeMillis()}",
                        role = "user",
                        kind = "text",
                        content = userText,
                        at = System.currentTimeMillis(),
                    )
                    val userMessageId = repo.insertMessage(userMsg)
                    // 同 sendChatMessage:摘要走降级纯函数(语音回合 ASR 文本
                    // kind=text,原样返回,行为不变)。
                    repo.touchConversation(
                        convId,
                        conversationSummary(userMsg.kind, userMsg.content, userMsg.actionsJson),
                        userMsg.at,
                    )
                    // 回合失败显式化(02 文档 §3.5):语音回合失败同样记
                    // failedTurn(同会话守卫,挂断回聊天页即见「消息发送失败,
                    // 请点击重试」,按原 ASR 文本重试、用户消息已在库不重复落);
                    // 本轮无泡可念,返回空列表不播 TTS。
                    val result = runRound(convId, character.id, userText, phoneMode = false, userMessageId = userMessageId)
                    _chatUi.value = _chatUi.value.applyVoiceRoundResult(convId, result, userMessageId)
                    (result as? com.neethu.gouxiang.chat.TurnResult.Completed)?.bubbles
                        ?: emptyList()
                },
            )
        }
    }

    /** 打开语音通话页(聊天页电话图标)。 */
    fun openVoiceCall(conversationId: Long) {
        _voiceCallConversationId.value = conversationId
    }

    /** 挂断/关闭语音通话页:回聊天页(消息回合中落库的行照常可见)。 */
    fun closeVoiceCall() {
        _voiceCallConversationId.value = null
    }

    // ── 聊天历史页 11(F7:入口=聊天页互动列「历史」,tv_im)────────────

    /** 每段加载的行数(范围 3:本地分段,非真分页协议)。 */
    private val historyPageSize = 60

    /** 历史页打开的会话 id(null=未进入)。 */
    private val _historyConversationId = MutableStateFlow<Long?>(null)
    val historyConversationId: StateFlow<Long?> = _historyConversationId.asStateFlow()

    /** 历史页 UI 状态。 */
    private val _historyUi = MutableStateFlow(ChatHistoryUiState())
    val historyUi: StateFlow<ChatHistoryUiState> = _historyUi.asStateFlow()

    /** 分段加载防重入(顶部触发可能高频连发,串行化)。 */
    private var historyLoading = false

    /**
     * 打开历史页(聊天页互动列「历史」):先载最近一段,顶滚再补更旧段。
     * 只读浏览,不订阅消息流(快照;回聊天页后新消息照常上屏)。
     */
    fun openHistory(conversationId: Long) {
        if (_historyConversationId.value == conversationId) return
        _historyConversationId.value = conversationId
        _historyUi.value = ChatHistoryUiState(loadingOlder = true)
        viewModelScope.launch {
            val total = repo.countMessages(conversationId)
            val segment = repo.recentMessagesBefore(
                conversationId, beforeAt = Long.MAX_VALUE, beforeId = Long.MAX_VALUE,
                limit = historyPageSize,
            )
            _historyUi.value = ChatHistoryUiState(
                bubbleItems = toHistoryItems(segment, previousLastAt = null),
                endReached = segment.size >= total,
                loadingOlder = false,
            )
        }
    }

    /** 顶滚到最旧一段即将露出时:再往前取一段拼到头部(串行防抖)。 */
    fun loadOlderHistory() {
        val conversationId = _historyConversationId.value ?: return
        val ui = _historyUi.value
        val oldest = ui.bubbleItems.firstOrNull()?.group?.bubbles?.firstOrNull()
        if (oldest == null || historyLoading || ui.loadingOlder || ui.endReached) return
        historyLoading = true
        _historyUi.value = ui.copy(loadingOlder = true)
        viewModelScope.launch {
            try {
                val total = repo.countMessages(conversationId)
                val segment = repo.recentMessagesBefore(
                    conversationId, beforeAt = oldest.at, beforeId = oldest.id ?: 0L,
                    limit = historyPageSize,
                )
                val current = _historyUi.value
                _historyUi.value = current.copy(
                    bubbleItems = toHistoryItems(segment, previousLastAt = oldest.at) +
                        current.bubbleItems,
                    endReached = segment.size < historyPageSize,
                    loadingOlder = false,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatHistory", "load older failed", e)
                _historyUi.value = _historyUi.value.copy(loadingOlder = false)
            } finally {
                historyLoading = false
            }
        }
    }

    /** 关闭历史页:回聊天页。 */
    fun closeHistory() {
        _historyConversationId.value = null
        _historyUi.value = ChatHistoryUiState()
    }

    /** 历史页分段:消息行 → 组(时间/日期标签);[previousLastAt] 是更旧已载段的最新时间。 */
    private fun toHistoryItems(
        messages: List<MessageEntity>,
        previousLastAt: Long?,
    ): List<HistoryBubbleItem> {
        if (messages.isEmpty()) return emptyList()
        val calendar = java.util.Calendar.getInstance()
        fun dayKey(at: Long): Triple<Int, Int, Int> {
            calendar.timeInMillis = at
            return Triple(calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH), calendar.get(java.util.Calendar.DAY_OF_MONTH))
        }
        // 组序沿用聊天页聚合规则(user 一组一条 / assistant 同 turnId 聚组),
        // 再按组尾时间标日期切换与 HH:mm。
        val groups = toBubbleItems(messages).map { group ->
            // 组尾时间:组内最后一条非空泡的 at(聚合时逐泡已带行时间)
            val lastAt = group.bubbles.maxOf { it.at }
            group to lastAt
        }
        return groups.mapIndexed { index, (group, lastAt) ->
            val prev = if (index == 0) previousLastAt else groups[index - 1].second
            val newDay = prev == null || dayKey(prev) != dayKey(lastAt)
            HistoryBubbleItem(
                group = group,
                at = lastAt,
                showDayDivider = newDay,
                dayLabel = if (newDay) formatHistoryDay(lastAt) else "",
                timeLabel = formatHistoryTime(lastAt),
            )
        }
    }

    /** 历史日期标签:今天/昨天/当年月日/跨年完整日期。 */
    private fun formatHistoryDay(at: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val now = java.util.Calendar.getInstance()
        fun y(c: java.util.Calendar) = c.get(java.util.Calendar.YEAR)
        fun d(c: java.util.Calendar) = c.get(java.util.Calendar.DAY_OF_YEAR)
        return when {
            y(cal) == y(now) && d(cal) == d(now) -> "今天"
            y(cal) == y(now) && d(cal) == d(now) - 1 -> "昨天"
            y(cal) == y(now) -> "${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日"
            else -> "${y(cal)}年${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日"
        }
    }

    /** 历史组尾时间:HH:mm。 */
    private fun formatHistoryTime(at: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val m = cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
        return "$h:$m"
    }

    /** 回合执行公共体(陪伴/手机共用,仅形态参数不同)。
     * 返回回合结果(02 文档 §3.5):完成携带全部回复泡(语音通话 F6 的 TTS
     * 念稿来源);失败(流错误/首 delta 超时)显式上抛,由调用侧置 failedTurn。
     */
    private suspend fun runRound(conversationId: Long, characterId: Long, userText: String, phoneMode: Boolean, userMessageId: Long? = null): com.neethu.gouxiang.chat.TurnResult {
        // 对话风格(F5):每回合现读持久化选择(弹层改完立即生效,无需重建编排器),
        // 经 BurstParams 映射为连发参数;默认组合与 F2/F4 既定 prompt 逐字节一致。
        val app = getApplication<Application>()
        val style = ChatStyleParams(
            mode = ChatPrefs.dialogMode(app),
            length = ChatPrefs.replyLength(app),
        )
        val burst = BurstParams.resolve(style.length, phoneMode, style.mode)
        _chatStyle.value = style
        // 流式草稿:回复逐字累积期间挂到"正在输入"态(跑在独立协程,
        // 编排器回合结束后清空)
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            orchestrator.streamDraft.collect { _streamDraft.value = it }
        }
        val result = orchestrator.respondTo(
            conversationId = conversationId,
            characterId = characterId,
            userText = userText,
            onAssistantSaved = { /* 上屏走 Room 流;回调留作后续动效钩子 */ },
            burst = burst,
            mode = style.mode,
            phoneStyle = phoneMode,
            userMessageId = userMessageId,
        )
        draftJob?.cancel()
        draftJob = null
        _streamDraft.value = ""
        return result
    }

    // ── 手机聊天 F4:主动消息/长按回溯/重生成/语音 ────────────────────

    /**
     * 主动消息触发判定(05 号文档收敛版):首次进入(该会话无 proactive
     * 回合史)或距上次对话超过阈值(可配,默认 2h)。判定结果由调用方决定
     * 是否真的发起(仅手机聊天页进入时发起)。
     */
    suspend fun pickProactiveTrigger(conversationId: Long): com.neethu.gouxiang.protocol.ProactiveMessages.Kind? {
        val conv = repo.db.conversationDao().byId(conversationId) ?: return null
        val msgs = repo.allMessages(conversationId)
        val everProactive = msgs.any { it.turnId.startsWith("proactive-") }
        val lastAt = conv.lastAt
        return com.neethu.gouxiang.protocol.ProactiveMessages.pickTrigger(
            everProactive = everProactive,
            lastActivityAt = lastAt,
            now = System.currentTimeMillis(),
            offlineThresholdMs = thresholdMs,
        )
    }

    /**
     * 主动消息连发(范围 5):角色先发 1~3 条;[simulateOffline] = 调试入口
     * (长按顶栏「…」),强制按「模拟离线回归」场景生成,不看阈值。
     */
    fun sendProactiveMessages(kind: com.neethu.gouxiang.protocol.ProactiveMessages.Kind, offlineMs: Long?) {
        val state = _chatUi.value
        val conversationId = state.conversationId
        val character = state.character ?: return
        if (state.sending) return
        _chatUi.value = state.copy(sending = true)
        roundJob = viewModelScope.launch {
            try {
                draftJob?.cancel()
                draftJob = viewModelScope.launch {
                    orchestrator.streamDraft.collect { _streamDraft.value = it }
                }
                orchestrator.respondProactively(
                    conversationId = conversationId,
                    characterId = character.id,
                    sceneLine = com.neethu.gouxiang.protocol.ProactiveMessages.sceneLine(kind, offlineMs),
                    onAssistantSaved = { },
                    burst = BurstParams.resolve(ChatPrefs.replyLength(getApplication()), phone = true, ChatPrefs.dialogMode(getApplication())),
                    mode = ChatPrefs.dialogMode(getApplication()),
                    phoneStyle = true,
                )
                draftJob?.cancel()
                draftJob = null
                _streamDraft.value = ""
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatOrchestrator", "proactive round failed", e)
            } finally {
                _chatUi.value = _chatUi.value.copy(sending = false)
            }
        }
    }

    /**
     * 长按泡「回溯」:删除该条消息及其之后的所有消息,回到该点(F4 收尾轮
     * 明确语义:回溯=删除该消息及之后消息并回到该点;此前实现只删整个回合,
     * 长按较早的泡会留下后续回合残尾)。删后刷新会话行摘要/时间。
     *
     * 在联回合一并打断:回复连发/流式草稿若继续跑,会往已回溯点之后继续
     * 落库(新 id 恒大于删除点)并顶动列表,形成回溯不掉的幽灵残尾。
     *
     * 联动删记忆(03 任务 7):锚点时刻起的事件记忆一并删除(画像/关系类
     * 不动),避免回溯后已总结的事件成为「幽灵记忆」继续注入。
     */
    fun rewindToMessage(messageId: Long) {
        val conversationId = _chatUi.value.conversationId
        if (conversationId == 0L || messageId == 0L) return
        viewModelScope.launch {
            // 先打断在联回合与草稿(取消是协作式的:连发泡间的 delay 即断点,
            // 后续泡不再落库),再走删除。
            roundJob?.cancel()
            draftJob?.cancel()
            _streamDraft.value = ""
            // 删除前先取锚点(查不到=并发已删,只走消息删除既有语义);
            // characterId 按 simulateOfflineReturn 同款经会话行 byId 解析。
            val anchor = repo.messageById(messageId)
            if (anchor != null && anchor.conversationId == conversationId) {
                durableMemory.rewind(conversationId, messageId)
                repo.refreshConversationTail(conversationId)
            }
        }
    }

    /**
     * 长按 assistant 泡「重新生成」:删该回合及其后所有消息 → 用该回合前的
     * 最后一条用户输入重新请求该条回复。中途回合同样成立:删到尾后新回复
     * 顺序追加,时序与原位一致(不必留下更晚回合的残尾)。
     */
    fun regenerateTurn(turnId: String) {
        val state = _chatUi.value
        val conversationId = state.conversationId
        val character = state.character ?: return
        if (state.sending || conversationId == 0L) return
        // 与当前页面的发送和重试使用相同形态，避免协程执行期间页面切换影响请求。
        val phoneMode = _isPhoneChat.value
        roundJob = viewModelScope.launch {
            // 找该回合起点,取其之前(不含该回合)的最后一条用户输入作为重生成语境
            val all = repo.allMessages(conversationId)
            val turnStartIndex = all.indexOfFirst { it.turnId == turnId }
            if (turnStartIndex < 0) return@launch
            val lastUser = all.subList(0, turnStartIndex).lastOrNull { it.role == "user" }
            durableMemory.rewind(conversationId, all[turnStartIndex].id)
            repo.refreshConversationTail(conversationId)
            if (lastUser == null) {
                // 无用户输入可归因(主动消息回合):重新走主动场景
                _chatUi.value = state.copy(sending = true)
                try {
                    draftJob?.cancel()
                    draftJob = viewModelScope.launch {
                        orchestrator.streamDraft.collect { _streamDraft.value = it }
                    }
                    orchestrator.respondProactively(
                        conversationId = conversationId,
                        characterId = character.id,
                        sceneLine = "(刚刚那条没说好,重新组织一下)重新发一条主动消息,连发 1~3 条。",
                        onAssistantSaved = { },
                        burst = BurstParams.resolve(ChatPrefs.replyLength(getApplication()), phone = phoneMode, ChatPrefs.dialogMode(getApplication())),
                        mode = ChatPrefs.dialogMode(getApplication()),
                        phoneStyle = phoneMode,
                    )
                    draftJob?.cancel()
                    draftJob = null
                    _streamDraft.value = ""
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } finally {
                    _chatUi.value = _chatUi.value.copy(sending = false)
                }
                return@launch
            }
            _chatUi.value = _chatUi.value.copy(sending = true)
            try {
                // 重新生成失败(02 文档 §3.5)同样显式化:原用户输入仍在库,
                // 记 failedTurn 出重试条,避免「删了旧泡又没新回复」的空窗
                _chatUi.value = _chatUi.value.afterTurnResult(
                    runRound(conversationId, character.id, lastUser.content, phoneMode = phoneMode, userMessageId = lastUser.id),
                    lastUser.id,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatOrchestrator", "regenerate failed", e)
                _chatUi.value = _chatUi.value.copy(failedTurn = FailedTurn(conversationId, lastUser.content, lastUser.id))
            } finally {
                _chatUi.value = _chatUi.value.copy(sending = false)
            }
        }
    }

    /** 语音条播放(范围 1:TTS 或占位;TTS 不可用时静默不发声)。 */
    fun playVoice(messageId: Long, text: String) {
        voicePlayer.play(messageId, text)
    }

    // ── 按住说话·语音输入(F4-P1:聊天输入条复用 F6 录音+ASR 管线)──────

    /**
     * 语音输入状态机(与 [VoiceCallEngine] 的 holdStart/holdRelease 同构,
     * 但松手后不进 LLM 回合 —— ASR 转写结果填回输入条草稿,用户可改再发,
     * 对齐猫箱「转写可改」;失败置 notice 给 Toast 兜底,录音器永留后台线程)。
     *
     * 录音格式与 F6 完全同源:[WavRecorder](16-bit PCM 单声道 16 kHz → WAV),
     * 误触丢弃阈值同 [VoiceCallEngine.MIN_WAV_BYTES];ASR = 硅基
     * Qwen3-ASR(适配器与 Key 解析同 F6 的 [VoiceCallEngine.transcribe] 口径)。
     */
    private val recorder = com.neethu.gouxiang.ai.speech.WavRecorder()

    /** 语音输入进行中(输入条按住态高亮);holdRelease 开始转写后即 false。 */
    private val _voiceInputActive = MutableStateFlow(false)
    val voiceInputActive: StateFlow<Boolean> = _voiceInputActive.asStateFlow()

    /** 语音输入失败提示(空=无);Toast 消费后清空。 */
    private val _voiceInputNotice = MutableStateFlow("")
    val voiceInputNotice: StateFlow<String> = _voiceInputNotice.asStateFlow()

    /** 语音输入转写完成(文本;UI 消费填入草稿框后即清空)。 */
    private val _voiceInputTranscribed = MutableStateFlow("")
    val voiceInputTranscribed: StateFlow<String> = _voiceInputTranscribed.asStateFlow()

    /** 转写在途(松手后 ASR 网络请求期间输入条提示「识别中」)。 */
    private val _voiceInputRecognizing = MutableStateFlow(false)
    val voiceInputRecognizing: StateFlow<Boolean> = _voiceInputRecognizing.asStateFlow()

    /** 按住说话·按下开录(麦克风被占用/无权限给 notice,不崩)。 */
    fun voiceInputStart() {
        if (_voiceInputActive.value || _voiceInputRecognizing.value) return
        _voiceInputNotice.value = ""
        try {
            recorder.start()
            _voiceInputActive.value = true
        } catch (e: Exception) {
            android.util.Log.w("VoiceInput", "mic start failed", e)
            _voiceInputNotice.value = "麦克风不可用,检查录音权限"
        }
    }

    /** 按住说话·松手停录 → ASR 转写 → [voiceInputTranscribed] 出结果。 */
    fun voiceInputRelease() {
        if (_voiceInputActive.value.not()) return
        _voiceInputActive.value = false
        viewModelScope.launch {
            _voiceInputRecognizing.value = true
            try {
                val wav = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    recorder.stop()
                    recorder.takeWav()
                }
                val text = if (wav.size <= com.neethu.gouxiang.ai.speech.VoiceCallEngine.MIN_WAV_BYTES) {
                    ""
                } else {
                    transcribeVoiceInput(wav)
                }
                if (text.isBlank()) {
                    _voiceInputNotice.value = "没听清,再试一次?"
                } else {
                    _voiceInputTranscribed.value = text
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("VoiceInput", "asr failed", e)
                _voiceInputNotice.value = "识别失败,请重试"
            } finally {
                _voiceInputRecognizing.value = false
            }
        }
    }

    /** 识别:WAV → 文本(Key/模型解析与 VoiceCallEngine 同口径;失败返回空串)。 */
    private suspend fun transcribeVoiceInput(wav: ByteArray): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val cfg = com.neethu.gouxiang.ai.config.AiConfigStore.load(getApplication())
                val key = cfg.effectiveAsrKey()
                val model = com.neethu.gouxiang.ai.config.resolveAsrModel(
                    com.neethu.gouxiang.BuildConfig.SILICONFLOW_BASE_URL, cfg.asrModel,
                )
                com.neethu.gouxiang.ai.speech.OpenAiCompatibleAsrAdapter(
                    com.neethu.gouxiang.BuildConfig.SILICONFLOW_BASE_URL,
                    key,
                ).transcribe(wav, "audio/wav", com.neethu.gouxiang.ai.speech.AsrConfig(model = model, language = "zh"))
            } catch (e: Exception) {
                android.util.Log.w("VoiceInput", "ASR failed", e)
                ""
            }
        }

    /** Toast/草稿消费完提示后清空(一次性事件语义)。 */
    fun consumeVoiceInputNotice() {
        _voiceInputNotice.value = ""
    }

    fun consumeVoiceInputTranscribed() {
        _voiceInputTranscribed.value = ""
    }

    /** 离开聊天页/切后台:若按住态未松手,同步停录丢弃(防录音器悬挂)。 */
    fun cancelVoiceInput() {
        if (_voiceInputActive.value) {
            _voiceInputActive.value = false
            try { recorder.stop() } catch (_: Exception) { }
        }
    }

    /** 离线回归阈值(PhoneChatPrefs;调试/后续设置页可调)。 */
    private var thresholdMs: Long = com.neethu.gouxiang.protocol.ProactiveMessages.DEFAULT_OFFLINE_THRESHOLD_MS

    /** 语音条播放器(页面退出 stop;进程内单例挂在 VM 生命周期)。 */
    private val voicePlayer by lazy {
        com.neethu.gouxiang.phone.VoicePlayer(getApplication()).also {
            android.util.Log.i("PhoneChat", "VoicePlayer created")
        }
    }

    /** 手机聊天页正在播放的语音条消息 id。 */
    val playingVoiceId: StateFlow<Long?> get() = voicePlayer.playingId

    /** 退出手机聊天页:停 TTS(防后台继续发声)。 */
    fun stopVoicePlayback() = voicePlayer.stop()

    // ── 13c 说明页(F4 范围 2)──────────────────────────────────────

    /** 13c「小手机使用说明」页开关(手机聊天顶栏「…」进入)。 */
    private val _phoneGuideOpen = MutableStateFlow(false)
    val phoneGuideOpen: StateFlow<Boolean> = _phoneGuideOpen.asStateFlow()

    fun openPhoneGuide() {
        _phoneGuideOpen.value = true
    }

    fun closePhoneGuide() {
        _phoneGuideOpen.value = false
    }

    /**
     * 调试入口(范围 5):长按手机聊天顶栏「…」触发「模拟离线回归」,
     * 按已离线时长(至少 1h,保证走 CHECK_IN/DAILY_SHARE 场景)发起主动连发。
     */
    fun simulateOfflineReturn() {
        val conversationId = _chatUi.value.conversationId
        if (conversationId == 0L) return
        viewModelScope.launch {
            val conv = repo.db.conversationDao().byId(conversationId) ?: return@launch
            val offline = (System.currentTimeMillis() - conv.lastAt)
                .coerceAtLeast(60L * 60 * 1000) // 模拟至少离线 1 小时
            sendProactiveMessages(
                com.neethu.gouxiang.protocol.ProactiveMessages.Kind.CHECK_IN,
                offlineMs = offline,
            )
        }
    }

    /** 角色头像一次生成落盘(范围说明);失败留在 map 外=UI 占位。
     *  生图 prompt 在这里拼好再交给 provider(ImageGenAdapter 契约:
     * prompt = appearance + 风格词缀,由调用方拼好),与心声/朋友圈配图
     * 同一拼法 —— 修复前头像只喂 appearance、风格词缀缺失。 */
    fun ensureAvatars(characters: List<CharacterEntity>) {
        viewModelScope.launch {
            val provider = com.neethu.gouxiang.ai.image.AvatarImageProvider(getApplication<Application>().filesDir)
            val updated = _avatarFiles.value.toMutableMap()
            for (character in characters) {
                if (updated.containsKey(character.id)) continue
                val file = provider.getOrGenerate(
                    characterKey = "character-${character.id}",
                    prompt = buildAvatarPrompt(character.appearance, character.appearanceStyle),
                )
                updated[character.id] = file?.absolutePath
                _avatarFiles.value = updated.toMap()
            }
        }
    }

    // ── 心声页 07(F8:入口=角色主页「Ta 的心声」占位卡)───────────────

    /** 心声页打开的会话 id(null=未进入)。 */
    private val _innerVoiceConversationId = MutableStateFlow<Long?>(null)
    val innerVoiceConversationId: StateFlow<Long?> = _innerVoiceConversationId.asStateFlow()

    /** 心声页 UI 状态。 */
    private val _innerVoiceUi = MutableStateFlow(com.neethu.gouxiang.ui.innervoice.InnerVoiceUiState())
    val innerVoiceUi: StateFlow<com.neethu.gouxiang.ui.innervoice.InnerVoiceUiState> =
        _innerVoiceUi.asStateFlow()

    private var innerVoiceJob: Job? = null

    /** 打开心声页:按会话取角色 + 订阅心声流(生成落库即上屏)。 */
    fun openInnerVoices(conversationId: Long) {
        if (_innerVoiceConversationId.value == conversationId) return
        _innerVoiceConversationId.value = conversationId
        _innerVoiceUi.value = com.neethu.gouxiang.ui.innervoice.InnerVoiceUiState(loading = true)
        innerVoiceJob?.cancel()
        innerVoiceJob = viewModelScope.launch {
            val conv = repo.db.conversationDao().byId(conversationId)
            val character = conv?.let { repo.characterById(it.characterId) }
            if (conv == null || character == null) {
                _innerVoiceUi.value = _innerVoiceUi.value.copy(loading = false, loadFailed = true)
                return@launch
            }
            _innerVoiceUi.value = _innerVoiceUi.value.copy(characterName = character.name)
            com.neethu.gouxiang.work.reconcileDerivativeTasks(getApplication())
            launch {
                repo.db.derivativeResultDao().observeImageTasks(character.id, "INNER_VOICE").collect {
                    repo.db.derivativeResultDao().recoverInterruptedImages()
                }
            }
            launch {
                repo.db.derivativeTaskDao().observeLatest(character.id, com.neethu.gouxiang.chat.DerivativeKind.INNER_VOICE.name).collect { task ->
                    _innerVoiceUi.value = _innerVoiceUi.value.copy(automaticError = if (task?.source == "AUTOMATIC" && task.state == "failed")
                        "自动生成失败：${task.failureReason ?: "请检查网络与模型配置"}。可点击生成心声重试。" else null)
                }
            }
            launch {
                repo.db.derivativeResultDao().observeForCharacter(character.id, "INNER_VOICE").collect { results ->
                    _innerVoiceUi.value = _innerVoiceUi.value.copy(imageResults = imageStates(results))
                }
            }
            launch {
                observeManualDerivative(com.neethu.gouxiang.chat.DerivativeKind.INNER_VOICE, conversationId) { generating, error ->
                    _innerVoiceUi.value = _innerVoiceUi.value.copy(generating = generating, error = error)
                }
            }
            repo.observeInnerVoices(character.id).collect { voices ->
                _innerVoiceUi.value = _innerVoiceUi.value.copy(
                    items = voices,
                    loading = false,
                )
            }
        }
    }

    fun closeInnerVoices() {
        innerVoiceJob?.cancel()
        innerVoiceJob = null
        _innerVoiceConversationId.value = null
        _innerVoiceUi.value = com.neethu.gouxiang.ui.innervoice.InnerVoiceUiState()
    }

    /**
     * 「生成心声」(§2.4 双路径收敛):点击路径直接入队后台 Worker(明确意图,
     * 不经 GenerationPrefs 开关),页内保留乐观 UI(立即置 generating);后台
     * 落库经 observeInnerVoices Room 流自动上屏,生成结束由事件流回调清态。
     */
    fun generateInnerVoices() {
        val conversationId = _innerVoiceConversationId.value ?: return
        if (_innerVoiceUi.value.generating) return
        _innerVoiceUi.value = _innerVoiceUi.value.copy(generating = true, error = null)
        viewModelScope.launch {
            try {
                com.neethu.gouxiang.work.DerivativeWorker.enqueue(
                    getApplication(),
                    com.neethu.gouxiang.chat.DerivativeTask(
                        com.neethu.gouxiang.chat.DerivativeKind.INNER_VOICE, conversationId,
                    ),
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { _innerVoiceUi.value = _innerVoiceUi.value.copy(generating = false, error = "任务入队失败，请重试") }
        }
    }

    /** 心声生图 provider(进程内单例;filesDir/inner_voices 缓存)。 */
    private var innerVoiceImageProviderLazy: com.neethu.gouxiang.ai.image.AvatarImageProvider? = null

    private fun innerVoiceImageProvider(): com.neethu.gouxiang.ai.image.AvatarImageProvider {
        return innerVoiceImageProviderLazy ?: synchronized(this) {
            innerVoiceImageProviderLazy ?: com.neethu.gouxiang.ai.image.AvatarImageProvider(
                getApplication<Application>().filesDir,
            ).also { innerVoiceImageProviderLazy = it }
        }
    }

    /** 心声生成通道(与记忆抽取同款 LLM 缝;失败安全由通道保证)。 */
    private val innerVoiceChannel by lazy {
        InnerVoiceChannel(
            llm = ChatOrchestrator.defaultLlm(),
            llmConfig = { orchestrator.llmConfigForMemory() },
        )
    }

    // ── 朋友圈页 06(F9:入口=角色主页「Ta 的朋友圈」占位卡)───────────

    /** 朋友圈页打开的会话 id(null=未进入)。 */
    private val _momentsConversationId = MutableStateFlow<Long?>(null)
    val momentsConversationId: StateFlow<Long?> = _momentsConversationId.asStateFlow()

    /** 朋友圈页 UI 状态。 */
    private val _momentsUi = MutableStateFlow(com.neethu.gouxiang.ui.moments.MomentsUiState())
    val momentsUi: StateFlow<com.neethu.gouxiang.ui.moments.MomentsUiState> =
        _momentsUi.asStateFlow()

    private var momentsJob: Job? = null
    private val momentCommentJobs = mutableMapOf<Long, Job>()

    /** 朋友圈生成通道(与心声同款 LLM 缝;失败安全由通道保证)。 */
    private val momentsChannel by lazy {
        com.neethu.gouxiang.chat.MomentsChannel(
            llm = ChatOrchestrator.defaultLlm(),
            llmConfig = { orchestrator.llmConfigForMemory() },
        )
    }

    /** 朋友圈配图 provider(进程内单例;filesDir/moments 缓存)。 */
    private fun momentsImageProvider(): com.neethu.gouxiang.ai.image.AvatarImageProvider =
        momentsImageProviderLazy ?: synchronized(this) {
            momentsImageProviderLazy ?: com.neethu.gouxiang.ai.image.AvatarImageProvider(
                getApplication<Application>().filesDir,
            ).also { momentsImageProviderLazy = it }
        }

    private var momentsImageProviderLazy: com.neethu.gouxiang.ai.image.AvatarImageProvider? = null

    /**
     * 打开朋友圈页:按会话取角色 + 订阅动态流与评论流(生成/评论落库即上屏)。
     * 空时间线仅在日更开启时入队自动任务；后台按角色和本地日期去重。
     */
    fun openMoments(conversationId: Long) {
        if (_momentsConversationId.value == conversationId) return
        _momentsConversationId.value = conversationId
        _momentsUi.value = com.neethu.gouxiang.ui.moments.MomentsUiState(loading = true)
        momentsJob?.cancel()
        momentsJob = viewModelScope.launch {
            val conv = repo.db.conversationDao().byId(conversationId)
            val character = conv?.let { repo.characterById(it.characterId) }
            if (conv == null || character == null) {
                _momentsUi.value = _momentsUi.value.copy(loading = false, loadFailed = true)
                return@launch
            }
            _momentsUi.value = _momentsUi.value.copy(characterId = character.id, characterName = character.name)
            com.neethu.gouxiang.work.reconcileDerivativeTasks(getApplication())
            launch {
                repo.db.derivativeResultDao().observeImageTasks(character.id, "MOMENT").collect {
                    repo.db.derivativeResultDao().recoverInterruptedImages()
                }
            }
            launch {
                repo.db.derivativeTaskDao().observeLatest(character.id, com.neethu.gouxiang.chat.DerivativeKind.MOMENT.name).collect { task ->
                    _momentsUi.value = _momentsUi.value.copy(automaticError = if (task?.source == "AUTOMATIC" && task.state == "failed")
                        "自动生成失败：${task.failureReason ?: "请检查网络与模型配置"}。可点击生成动态重试。" else null)
                }
            }
            launch {
                repo.db.derivativeResultDao().observeForCharacter(character.id, "MOMENT").collect { results ->
                    _momentsUi.value = _momentsUi.value.copy(imageResults = imageStates(results))
                }
            }
            launch {
                observeManualDerivative(com.neethu.gouxiang.chat.DerivativeKind.MOMENT, conversationId) { generating, error ->
                    _momentsUi.value = _momentsUi.value.copy(generating = generating, error = error)
                }
            }
            ensureAvatars(listOf(character))
            var autoGenerated = false
            // 每条动态单独订阅评论流，新动态出现即挂。
            repo.observeMoments(character.id).collect { posts ->
                if (posts.isEmpty() && !autoGenerated && !_momentsUi.value.generating) {
                    autoGenerated = true
                    val epoch = com.neethu.gouxiang.work.automaticEpoch(getApplication(), com.neethu.gouxiang.chat.DerivativeKind.MOMENT)
                    if (com.neethu.gouxiang.ai.GenerationPrefs.momentsDaily(getApplication())) {
                        com.neethu.gouxiang.work.DerivativeWorker.enqueue(
                            getApplication(),
                            com.neethu.gouxiang.chat.DerivativeTask(
                                com.neethu.gouxiang.chat.DerivativeKind.MOMENT,
                                conversationId,
                                source = com.neethu.gouxiang.chat.DerivativeSource.AUTOMATIC,
                                characterId = character.id,
                                automaticEpoch = epoch,
                            ),
                        )
                    }
                }
                _momentsUi.value = _momentsUi.value.copy(items = posts, loading = false)
                posts.forEach { post ->
                    if (post.id !in momentCommentJobs) {
                        momentCommentJobs[post.id] = launch {
                            repo.observeMomentComments(post.id).collect { comments ->
                                _momentsUi.value = _momentsUi.value.copy(
                                    comments = _momentsUi.value.comments + (post.id to comments),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun closeMoments() {
        momentsJob?.cancel()
        momentsJob = null
        momentCommentJobs.values.forEach { it.cancel() }
        momentCommentJobs.clear()
        _momentsConversationId.value = null
        _momentsUi.value = com.neethu.gouxiang.ui.moments.MomentsUiState()
    }

    /**
     * 「生成动态」为手动请求，不受自动开关限制。落库后由动态流上屏。
     */
    fun generateMoment(conversationId: Long) {
        if (_momentsUi.value.generating) return
        _momentsUi.value = _momentsUi.value.copy(generating = true, error = null)
        viewModelScope.launch {
            try {
                com.neethu.gouxiang.work.DerivativeWorker.enqueue(
                    getApplication(),
                    com.neethu.gouxiang.chat.DerivativeTask(
                        com.neethu.gouxiang.chat.DerivativeKind.MOMENT, conversationId,
                    ),
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { _momentsUi.value = _momentsUi.value.copy(generating = false, error = "任务入队失败，请重试") }
        }
    }

    /** 单独补图：复用已有文字结果，不再次请求文本模型。 */
    fun retryDerivativeImage(kind: com.neethu.gouxiang.chat.DerivativeKind, image: ImageGenerationState) {
        if (image.conversationId <= 0 || kind !in setOf(
                com.neethu.gouxiang.chat.DerivativeKind.INNER_VOICE,
                com.neethu.gouxiang.chat.DerivativeKind.MOMENT,
            )) return
        viewModelScope.launch {
            com.neethu.gouxiang.work.DerivativeWorker.enqueue(
                getApplication(),
                com.neethu.gouxiang.chat.DerivativeTask(kind, image.conversationId, imageRetryRequestId = image.requestId),
            )
        }
    }

    /** 点赞/取消(F9 互动:本地状态,写库即上屏)。 */
    fun toggleMomentLike(post: com.neethu.gouxiang.data.MomentEntity) {
        viewModelScope.launch {
            repo.setMomentLiked(post.id, !post.likedByMe)
        }
    }

    /**
     * 用户评论(F9 互动 4):用户评论先落库 → 角色经 LLM 回复同一动态
     * (回复落库即上屏;失败只插一条角色侧兜底占位行,与聊天页「永远有
     * 回复」的兜底约定同源,界面永远不崩)。
     */
    fun sendMomentComment(post: com.neethu.gouxiang.data.MomentEntity, text: String) {
        val conversationId = _momentsConversationId.value ?: return
        val body = text.trim()
        if (body.isEmpty() || _momentsUi.value.replyingToId != null) return
        _momentsUi.value = _momentsUi.value.copy(replyingToId = post.id)
        viewModelScope.launch {
            try {
                val character = repo.characterById(post.characterId) ?: return@launch
                val userCommentId = repo.insertMomentComment(
                    com.neethu.gouxiang.data.MomentCommentEntity(
                        postId = post.id,
                        authorKind = "user",
                        text = body,
                        at = System.currentTimeMillis(),
                    ),
                )
                // 角色回复:LLM 生成;失败插兜底占位行(楼中楼挂用户评论)
                val memories = repo.memoriesFor(character.id)
                val thread = repo.momentComments(post.id)
                val system = com.neethu.gouxiang.chat.MomentsGenerator.replySystemPrompt(character, memories)
                val user = com.neethu.gouxiang.chat.MomentsGenerator.replyUserPrompt(
                    character, post, thread.dropLast(1), thread.last(),
                )
                val raw = momentsChannel.requestReply(system, user)
                val reply = raw?.let { com.neethu.gouxiang.chat.MomentsGenerator.parseReply(it) }
                repo.insertMomentComment(
                    com.neethu.gouxiang.data.MomentCommentEntity(
                        postId = post.id,
                        authorKind = "avatar",
                        replyToId = userCommentId,
                        text = reply ?: "……(刚刚没想好说什么)",
                        at = System.currentTimeMillis(),
                    ),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("Moments", "comment round failed", e)
                // 数据层失败也要给用户评论一个角色侧落点(防评论区悬空)
                runCatching {
                    repo.insertMomentComment(
                        com.neethu.gouxiang.data.MomentCommentEntity(
                            postId = post.id,
                            authorKind = "avatar",
                            text = "……(刚刚没想好说什么)",
                            at = System.currentTimeMillis(),
                        ),
                    )
                }
            } finally {
                _momentsUi.value = _momentsUi.value.copy(replyingToId = null)
            }
        }
    }

    // ── 记忆页 05(F3)───────────────────────────────────────────────

    /** 记忆页打开的会话 id(null=未进入;入口:聊天页顶栏「记忆」图标)。 */
    private val _memoryConversationId = MutableStateFlow<Long?>(null)
    val memoryConversationId: StateFlow<Long?> = _memoryConversationId.asStateFlow()

    /** 记忆页 UI 状态。 */
    private val _memoryUi = MutableStateFlow(com.neethu.gouxiang.ui.memory.MemoryUiState())
    val memoryUi: StateFlow<com.neethu.gouxiang.ui.memory.MemoryUiState> =
        _memoryUi.asStateFlow()

    private var memoryJob: Job? = null

    /** 打开记忆页:按会话取角色,读画像/事件/容量(事件带容量分带)。 */
    fun openMemory(conversationId: Long) {
        if (_memoryConversationId.value == conversationId) return
        _memoryConversationId.value = conversationId
        _memoryUi.value = com.neethu.gouxiang.ui.memory.MemoryUiState(loading = true)
        memoryJob?.cancel()
        memoryJob = viewModelScope.launch {
            val conv = repo.db.conversationDao().byId(conversationId)
            val character = conv?.let { repo.characterById(it.characterId) }
            if (conv == null || character == null) {
                _memoryUi.value = _memoryUi.value.copy(loading = false, loadFailed = true)
                return@launch
            }
            _memoryUi.value = _memoryUi.value.copy(characterName = character.name)
            reloadMemory()
        }
    }

    /** 重读记忆数据(进页/编辑/遗忘/总结后共用)。 */
    private suspend fun reloadMemory() {
        val conversationId = _memoryConversationId.value ?: return
        val conv = repo.db.conversationDao().byId(conversationId) ?: return
        val characterId = conv.characterId
        val profile = memoryBook.profile(characterId)
        val all = memoryBook.all(characterId)
        val (banded, capacity) = com.neethu.gouxiang.chat.MemoryCapacity.band(all)
        _memoryUi.value = _memoryUi.value.copy(
            loading = false,
            legacyWindow = repo.db.memoryWindowDao().loadPayload()?.takeIf { it.isNotEmpty() },
            profileName = profile.name,
            profileIdentity = profile.identity,
            bandedEvents = banded.filter { it.memory.category == com.neethu.gouxiang.chat.MemoryCategories.EVENT },
            capacity = capacity,
        )
    }

    fun closeMemory() {
        memoryJob?.cancel()
        memoryJob = null
        _memoryConversationId.value = null
        _memoryUi.value = com.neethu.gouxiang.ui.memory.MemoryUiState()
    }

    /** 「立即总结」:把最近对话直接交给事件总结通道(不经窗口阈值)。 */
    fun summarizeMemoryNow() {
        val conversationId = _memoryConversationId.value ?: return
        viewModelScope.launch {
            val conv = repo.db.conversationDao().byId(conversationId) ?: return@launch
            val character = repo.characterById(conv.characterId) ?: return@launch
            _memoryUi.value = _memoryUi.value.copy(generating = true)
            try { durableMemory.run(character, conversationId) } finally {
                _memoryUi.value = _memoryUi.value.copy(generating = false)
                reloadMemory()
            }
        }
    }

    fun discardLegacyMemoryWindow() {
        viewModelScope.launch {
            repo.db.memoryWindowDao().discardLegacy()
            reloadMemory()
        }
    }

    /** 画像编辑(空值 = 遗忘该条)。 */
    fun editMemoryProfile(subtype: String, value: String) {
        val conversationId = _memoryConversationId.value ?: return
        viewModelScope.launch {
            val conv = repo.db.conversationDao().byId(conversationId) ?: return@launch
            memoryBook.writeProfile(conv.characterId, subtype, value)
            reloadMemory()
        }
    }

    /** 遗忘一条记忆(确认弹窗后)。 */
    fun forgetMemory(id: Long) {
        viewModelScope.launch {
            memoryBook.forget(id)
            reloadMemory()
        }
    }

    /** 消息行 → 提炼窗口回合(user 原文 / assistant 各泡合并,泡间 "\n")。 */
    private fun toMemoryTurns(messages: List<MessageEntity>): List<com.neethu.gouxiang.data.MemoryTurn> =
        com.neethu.gouxiang.work.DerivativeExecutor.toMemoryTurns(messages)

    // ── 后台生成事件(§2.4:UI 双路径收敛的回调面)────────────────────

    /**
     * 衍生任务完成/失败事件(进程级单槽 bus 的 VM 轮询读法):点击路径的乐观
     * generating 态由它收口;内容落库本身走 Room Flow(UI 不感知后台)。
     * 心声/朋友圈页打开期间每秒查一次(轻读内存槽,无 IPC)。
     */
    private val _derivativeEvent = MutableStateFlow<com.neethu.gouxiang.work.DerivativeEvents.Event?>(
        null,
    )
    val derivativeEvent: StateFlow<com.neethu.gouxiang.work.DerivativeEvents.Event?> =
        _derivativeEvent.asStateFlow()

    /** 事件消费(UI 侧清 generating/错误态后调用)。 */
    fun consumeDerivativeEvent() {
        _derivativeEvent.value = null
    }

    /**
     * 心声任务收口(MainActivity 消费事件后回调):成功清 generating 态
     * (内容已/将经 Room 流上屏);失败清态 + 置 error 提示。仅当心声页
     * 仍打开同一会话才改状态(后台完成时页面可能已关)。
     */
    fun onInnerVoiceTaskSettled(e: com.neethu.gouxiang.work.DerivativeEvents.Event) {
        if (e.success) refreshCharacterHomeCounts()
    }

    fun onMomentTaskSettled(e: com.neethu.gouxiang.work.DerivativeEvents.Event) {
        if (e.success) refreshCharacterHomeCounts()
    }

    /** WorkManager persists this state, including across process death. */
    private suspend fun observeManualDerivative(
        kind: com.neethu.gouxiang.chat.DerivativeKind,
        conversationId: Long,
        update: (Boolean, String?) -> Unit,
    ) {
        val task = com.neethu.gouxiang.chat.DerivativeTask(kind, conversationId)
        val name = com.neethu.gouxiang.chat.DerivativeTask.uniqueName(task)
        androidx.work.WorkManager.getInstance(getApplication<Application>())
            .getWorkInfosForUniqueWorkFlow(name).collect { infos ->
                val info = infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull()
                if (info == null) return@collect
                val active = info.state == androidx.work.WorkInfo.State.ENQUEUED ||
                    info.state == androidx.work.WorkInfo.State.RUNNING ||
                    info.state == androidx.work.WorkInfo.State.BLOCKED
                val error = when (info.state) {
                    androidx.work.WorkInfo.State.FAILED -> info.outputData.getString("reason")
                        ?: "生成失败，请检查网络与模型配置后重试"
                    androidx.work.WorkInfo.State.CANCELLED -> "生成已取消，可以重新尝试"
                    androidx.work.WorkInfo.State.SUCCEEDED -> info.outputData.getString("warning")?.takeIf { it.isNotBlank() }
                    else -> null
                }
                update(active, error)
            }
    }

    /** 角色主页占位卡尾注同步(若主页仍打开;F8/F9 共用)。 */
    private fun refreshCharacterHomeCounts() {
        val homeConvId = _characterHomeConversationId.value ?: return
        viewModelScope.launch {
            val conv = repo.db.conversationDao().byId(homeConvId) ?: return@launch
            _characterHomeUi.value = _characterHomeUi.value.copy(
                innerVoiceCount = repo.countInnerVoices(conv.characterId),
                momentCount = repo.countMoments(conv.characterId),
            )
        }
    }

    private fun startDerivativeEventPolling() {
        viewModelScope.launch {
            while (true) {
                val e = com.neethu.gouxiang.work.DerivativeEvents.take()
                if (e != null) _derivativeEvent.value = e
                kotlinx.coroutines.delay(1_000)
            }
        }
    }

    init {
        startDerivativeEventPolling()
    }

    // ── 内容生成设置页(§3.3)────────────────────────────────────────

    /** 设置页打开态(MainActivity 全屏覆盖层导航惯例)。 */
    private val _generationSettingsOpen = MutableStateFlow(false)
    val generationSettingsOpen: StateFlow<Boolean> = _generationSettingsOpen.asStateFlow()

    fun openGenerationSettings() {
        _generationSettingsOpen.value = true
    }

    fun closeGenerationSettings() {
        _generationSettingsOpen.value = false
    }

    /** 设置页快照(进页即读一次;切换即写 + 即时 enqueue/cancel)。 */
    private val _generationSettings = MutableStateFlow(
        com.neethu.gouxiang.work.GenerationSettingsSnapshot(),
    )
    val generationSettings: StateFlow<com.neethu.gouxiang.work.GenerationSettingsSnapshot> =
        _generationSettings.asStateFlow()

    /** 进设置页时刷新快照(读盘;进页一次,页内切换走 toggle 即时写)。 */
    fun loadGenerationSettings() {
        val app = getApplication<Application>()
        _generationSettings.value =
            com.neethu.gouxiang.work.GenerationSettingsSnapshot(
                innerVoiceAuto = com.neethu.gouxiang.ai.GenerationPrefs.innerVoiceAuto(app),
                momentsDaily = com.neethu.gouxiang.ai.GenerationPrefs.momentsDaily(app),
                memoryOvernight = com.neethu.gouxiang.ai.GenerationPrefs.memoryOvernight(app),
                proactiveOutbox = com.neethu.gouxiang.ai.GenerationPrefs.proactiveOutbox(app),
                derivativeAuto = com.neethu.gouxiang.ai.GenerationPrefs.derivativeAuto(app),
                notifyEnabled = com.neethu.gouxiang.ai.GenerationPrefs.notifyEnabled(app),
                innerVoiceImages = com.neethu.gouxiang.ai.GenerationPrefs.innerVoiceImages(app),
                momentsImages = com.neethu.gouxiang.ai.GenerationPrefs.momentsImages(app),
            )
    }

    /**
     * 切一枚开关(即时生效):写盘 + Scheduler applyXxx(开=UPDATE 注册,关=
     * cancel);返回现场请求运行时通知权限的信号(仅「完成通知」开且 ≥33)。
     */
    fun toggleGenerationSetting(key: String, enabled: Boolean) {
        val app = getApplication<Application>()
        val sched = com.neethu.gouxiang.work.DerivativeScheduler
        when (key) {
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_INNER_VOICE_AUTO -> {
                com.neethu.gouxiang.ai.GenerationPrefs.setInnerVoiceAuto(app, enabled)
                sched.applyInnerVoiceAuto(app, enabled)
            }
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_MOMENTS_DAILY -> {
                com.neethu.gouxiang.ai.GenerationPrefs.setMomentsDaily(app, enabled)
                sched.applyMomentsDaily(app, enabled)
            }
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_MEMORY_OVERNIGHT -> {
                com.neethu.gouxiang.ai.GenerationPrefs.setMemoryOvernight(app, enabled)
                sched.applyMemoryOvernight(app, enabled)
            }
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_PROACTIVE_OUTBOX -> {
                com.neethu.gouxiang.ai.GenerationPrefs.setProactiveOutbox(app, enabled)
                sched.applyProactiveOutbox(app, enabled)
            }
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_DERIVATIVE_AUTO -> {
                com.neethu.gouxiang.ai.GenerationPrefs.setDerivativeAuto(app, enabled)
            }
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_NOTIFY_ENABLED -> {
                com.neethu.gouxiang.ai.GenerationPrefs.setNotifyEnabled(app, enabled)
            }
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_INNER_VOICE_IMAGES ->
                com.neethu.gouxiang.ai.GenerationPrefs.setInnerVoiceImages(app, enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_MOMENTS_IMAGES ->
                com.neethu.gouxiang.ai.GenerationPrefs.setMomentsImages(app, enabled)
        }
        _generationSettings.value = when (key) {
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_INNER_VOICE_AUTO ->
                _generationSettings.value.copy(innerVoiceAuto = enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_MOMENTS_DAILY ->
                _generationSettings.value.copy(momentsDaily = enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_MEMORY_OVERNIGHT ->
                _generationSettings.value.copy(memoryOvernight = enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_PROACTIVE_OUTBOX ->
                _generationSettings.value.copy(proactiveOutbox = enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_DERIVATIVE_AUTO ->
                _generationSettings.value.copy(derivativeAuto = enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_INNER_VOICE_IMAGES ->
                _generationSettings.value.copy(innerVoiceImages = enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_MOMENTS_IMAGES ->
                _generationSettings.value.copy(momentsImages = enabled)
            else -> _generationSettings.value.copy(notifyEnabled = enabled)
        }
    }

    /** 通知直跳入口(通知点入 onNewIntent extras):心声页。 */
    fun openInnerVoicesFromNotification(conversationId: Long) {
        openInnerVoices(conversationId)
    }

    /** 通知直跳入口:朋友圈页。 */
    fun openMomentsFromNotification(conversationId: Long) {
        openMoments(conversationId)
    }

    /** 通知直跳入口:聊天页(手机形态,与消息列表角标入口同参)。 */
    fun openChatFromNotification(conversationId: Long) {
        openChat(conversationId, phoneMode = true)
    }

    /** 通知直跳入口:消息列表(切 tab)。 */
    fun openMessagesFromNotification() {
        selectedTab.value = BottomTab.MESSAGES
    }
}

/**
 * 角色主页 04 的 UI 状态:角色卡 + 状态条(与聊天页同一 [StatusRule] 数据源,
 * 保证「主页状态条与聊天页状态条数据一致」)。
 */
data class CharacterHomeUiState(
    val character: com.neethu.gouxiang.data.CharacterEntity? = null,
    val status: com.neethu.gouxiang.chat.StatusLine = com.neethu.gouxiang.chat.StatusLine("", ""),
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    /** 「Ta 的心声」占位卡尾注(F8:全部 N;N=0 时不带尾注)。 */
    val innerVoiceCount: Int = 0,
    /** 「Ta 的朋友圈」占位卡尾注(F9:全部 N;N=0 时不带尾注)。 */
    val momentCount: Int = 0,
)

/**
 * 失败回合记录(02 文档 §3.5「回合失败显式化」):重试条展示文案与重试
 * 入参的依据;用户消息已在库,重试只重跑回复侧、不重复落库。
 */
data class FailedTurn(
    val conversationId: Long,
    val userText: String,
    val userMessageId: Long? = null,
)

/**
 * 聊天页渲染状态:数据库消息 → 气泡组(一轮回复 = 一组,组内 1~4 泡)。
 */
data class ChatUiState(
    val conversationId: Long = 0,
    val character: com.neethu.gouxiang.data.CharacterEntity? = null,
    val bubbleItems: List<BubbleItem> = emptyList(),
    /**
     * 状态条「emoji+在做什么」(emoji/text 分字段,严禁对字符串按位切 emoji)。
     */
    val status: com.neethu.gouxiang.chat.StatusLine = com.neethu.gouxiang.chat.StatusLine("", ""),
    /** 回合进行中(输入区防重入 + 角色正在输入观感)。 */
    val sending: Boolean = false,
    /** 最近一次用户回合失败(null=无):列表尾渲染失败条;openChat/closeChat 重建本态即清空。 */
    val failedTurn: FailedTurn? = null,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
)

/**
 * 回合结果 → 聊天页状态迁移(send/retry 共用;纯函数,JVM 单测锁住失败显式化):
 * 失败 → 记 failedTurn 等重试条;完成 → 清 failedTurn(重试成功撤条;失败后
 * 另发新消息成功同样撤陈旧条,避免重试到过期回合)。
 */
internal fun ChatUiState.afterTurnResult(result: com.neethu.gouxiang.chat.TurnResult, userMessageId: Long? = null): ChatUiState =
    when (result) {
        is com.neethu.gouxiang.chat.TurnResult.Completed -> copy(failedTurn = null)
        is com.neethu.gouxiang.chat.TurnResult.Failed ->
            copy(failedTurn = FailedTurn(conversationId = conversationId, userText = result.userText, userMessageId = userMessageId))
    }

/**
 * 语音通话回合结果应用(02 文档 §3.5):语音引擎在聊天页之上后台跑回合,
 * 聊天页可能已被关闭/切走;仅当聊天页仍停留在同一会话才按 [afterTurnResult]
 * 迁移(失败→记 failedTurn,挂断回页即见重试条;成功→清旧条),不同会话
 * 不污染当前页状态(纯函数,JVM 单测锁守卫语义)。
 */
internal fun ChatUiState.applyVoiceRoundResult(conversationId: Long, result: com.neethu.gouxiang.chat.TurnResult, userMessageId: Long? = null): ChatUiState =
    if (conversationId == this.conversationId) afterTurnResult(result, userMessageId) else this

/**
 * 一组连发气泡(同一 turnId 的 assistant 消息合并;渲染层按"首泡带尖角、
 * 后续泡缩进对齐"展示,对齐 03 截图的多泡堆叠)。
 */
data class BubbleItem(
    /** 同组消息的 turnId。 */
    val turnId: String,
    val fromUser: Boolean,
    /** 组内气泡(顺序即连发顺序)。 */
    val bubbles: List<ChatBubble>,
)/**
 * 单个气泡。
 *
 * F4 手机聊天扩展:id/turnId(长按回溯与重新生成的定位)、durationSec
 * (语音条时长)、cardPayload(卡片型载荷);IM 聊天页只消费 kind/content/
 * actions,lastInGroup 决定组底圆角。
 */
data class ChatBubble(
    val kind: String,
    val content: String,
    val actions: List<String>,
    /** 组内最后一条(决定组底部圆角与时间归属)。 */
    val lastInGroup: Boolean,
    /** 消息行 id(手机聊天长按操作用;IM 页不消费,测试构造默认 null)。 */
    val id: Long? = null,
    /** 回合 id(手机聊天长按「回溯整个回合」用)。 */
    val turnId: String = "",
    /** 语音条展示时长(秒;由转写文本按 ~4字/秒 推导)。 */
    val durationSec: Int = 0,
    /** 卡片载荷(kind=card 时非空;解码失败为 null 走文本降级)。 */
    val cardPayload: com.neethu.gouxiang.protocol.CardPayload? = null,
    /** 行时间(epoch millis;历史页 11 组尾时间/日期分组用;测试构造默认 0)。 */
    val at: Long = 0,
) {
    companion object {
        /** 语音条时长推导(协议层同款规则,渲染侧复用)。 */
        fun durationFor(text: String): Int =
            com.neethu.gouxiang.protocol.voiceDurationSec(text)
    }
}

/**
 * 用户消息行构造(卡片消息端到端打通的用户侧):「+」面板经 [MainViewModel.sendCard]
 * 编码出的 `[卡片:…]` 行内标记识别为卡片——kind="card"、content 保留原行、
 * actionsJson 存解码载荷,渲染层([toBubbleItems] → CardBody)直接出卡片;
 * 普通文本 / 解析失败仍按纯文本落库,不崩。
 */
internal fun buildUserMessageEntity(conversationId: Long, body: String, at: Long): MessageEntity {
    val card = com.neethu.gouxiang.protocol.parseCardLine(body)
    return MessageEntity(
        conversationId = conversationId,
        turnId = "turn-user-$at",
        role = "user",
        kind = if (card != null) "card" else "text",
        content = body,
        actionsJson = card?.encode() ?: "[]",
        at = at,
    )
}

/**
 * 消息行 → 气泡组。user 消息一组一条;assistant 消息按 turnId 聚组。
 * kind 未知/内容为空的行跳过(整组无内容 → 降级为一条占位文本,不产生空泡)。
 */
internal fun toBubbleItems(messages: List<MessageEntity>): List<BubbleItem> {
    val items = mutableListOf<BubbleItem>()
    var i = 0
    while (i < messages.size) {
        val m = messages[i]
        if (m.role == "user") {
            if (m.content.isNotBlank()) {
                items += BubbleItem(m.turnId, fromUser = true, bubbles = listOf(
                    ChatBubble(
                        m.kind, m.content, emptyList(), lastInGroup = true,
                        id = m.id, turnId = m.turnId,
                        durationSec = if (m.kind == "voice") ChatBubble.durationFor(m.content) else 0,
                        cardPayload = if (m.kind == "card") com.neethu.gouxiang.protocol.CardPayload.decode(m.actionsJson) else null,
                        at = m.at,
                    ),
                ))
            }
            i++
            continue
        }
        // assistant:连续同 turnId 聚组
        val bubbles = mutableListOf<ChatBubble>()
        while (i < messages.size && messages[i].role == "assistant" && messages[i].turnId == m.turnId) {
            val row = messages[i]
            if (row.content.isNotBlank()) {
                bubbles += ChatBubble(
                    kind = row.kind,
                    content = row.content,
                    actions = com.neethu.gouxiang.protocol.ActionsJson.decode(row.actionsJson),
                    lastInGroup = false,
                    id = row.id,
                    turnId = row.turnId,
                    durationSec = if (row.kind == "voice") ChatBubble.durationFor(row.content) else 0,
                    cardPayload = if (row.kind == "card") com.neethu.gouxiang.protocol.CardPayload.decode(row.actionsJson) else null,
                    at = row.at,
                )
            }
            i++
        }
        if (bubbles.isNotEmpty()) {
            items += BubbleItem(
                turnId = m.turnId,
                fromUser = false,
                bubbles = bubbles.dropLast(1) + bubbles.last().copy(lastInGroup = true),
            )
        }
    }
    return items
}

/**
 * 头像生图 prompt 组装(纯函数,单测锁行为):appearance 后拼生图风格词缀,
 * 与心声/朋友圈配图的拼接方式一致(ImageGenAdapter 契约:prompt = appearance +
 * 风格词缀,由调用方拼好)。风格词缀为空/纯空白时不加分隔符,避免 prompt 尾部
 * 挂一个孤立的「。」。
 */
internal fun buildAvatarPrompt(appearance: String, style: String): String =
    if (style.isNotBlank()) "$appearance。$style" else appearance

/**
 * 聊天历史页 11 的渲染状态:按日期分组的消息历史流(范围 3:本地分段,
 * 非真分页协议),只读浏览。
 */
data class ChatHistoryUiState(
    /** 时间正序的历史气泡组(头部=最旧一段,可被 loadOlderHistory 继续前拼)。 */
    val bubbleItems: List<HistoryBubbleItem> = emptyList(),
    /** 已载到会话最旧一条(顶滚不再触发加载)。 */
    val endReached: Boolean = false,
    /** 正在取更旧一段(顶滚提示 + 防重入)。 */
    val loadingOlder: Boolean = false,
)

/**
 * 历史页单个组:复用聊天页 [BubbleItem] 骨架 + 组尾时间/日期分组头标签。
 * [key] 供 LazyColumn 稳定复用(组内首泡 id,聚合器保证非空)。
 */
data class HistoryBubbleItem(
    val group: BubbleItem,
    /** 组尾时间(组内最后一条非空泡的 at)。 */
    val at: Long,
    /** 与更早一段不同日时,组上方出日期分组头。 */
    val showDayDivider: Boolean,
    /** 日期标签(今天/昨天/M月d日/yyyy年M月d日);非切换点为空串。 */
    val dayLabel: String,
    /** 组尾时间 HH:mm。 */
    val timeLabel: String,
) {
    val key: String get() = "${group.turnId}-${group.bubbles.firstOrNull()?.id ?: 0}"
}
