package com.neethu.gouxiang.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.neethu.gouxiang.chat.BurstParams
import com.neethu.gouxiang.chat.ChatBackgroundPrefs
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
import com.neethu.gouxiang.protocol.conversationSummary
import com.neethu.gouxiang.protocol.toProtocolLine
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.data.ChatDaoAccess
import com.neethu.gouxiang.data.ConversationRow
import com.neethu.gouxiang.data.GouXiangRepo
import com.neethu.gouxiang.data.HomeFeedCard
import com.neethu.gouxiang.data.MessageEntity
import com.neethu.gouxiang.derivatives.DerivativeOptions
import com.neethu.gouxiang.derivatives.DerivativeRange
import com.neethu.gouxiang.derivatives.NovelLength
import com.neethu.gouxiang.ui.character.AvatarGenState
import com.neethu.gouxiang.ui.character.CharacterDraft
import com.neethu.gouxiang.ui.character.CharacterDraftPrefs
import com.neethu.gouxiang.ui.character.CharacterEditorUi
import com.neethu.gouxiang.ui.character.CharacterField
import com.neethu.gouxiang.ui.character.CharacterFormCopy
import com.neethu.gouxiang.ui.character.CharacterFormValidator
import com.neethu.gouxiang.ui.character.CharacterSaveOutcome
import com.neethu.gouxiang.ui.character.avatarCacheKey
import com.neethu.gouxiang.ui.character.splitOpeningLine
import com.neethu.gouxiang.ui.moments.MomentPermissionField
import com.neethu.gouxiang.ui.nav.BottomTab
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 主框架状态:底栏选中 tab + 首页推荐流 + 消息列表 + 聊天主页导航 + 头像缓存路径。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    /** 全局朋友圈入口的会话哨兵(H1:无当前角色,发现页进入;会话 id 恒为正)。 */
    companion object {
        const val MOMENTS_GLOBAL_ID = -1L

        /** 用户动态正文字数上限(H3 发布校验;超限拒绝发布,UI 提示)。 */
        const val USER_POST_MAX_CHARS = 500

        /**
         * 侧移动作场景:确保转写文本被成对小括号包裹(协议口径:动作=括号文本,
         * 全/半角括号均收,判定同 MultiBubbleParser.actionRegex 口径)。已带成对
         * 括号的不重复包(防 ASR 自带括号时叠成「((..))」),其余一律补半角括号。
         */
        internal fun wrapActionBracket(text: String): String {
            val t = text.trim()
            if (t.length >= 2) {
                val first = t.first()
                val last = t.last()
                if ((first == '(' || first == '（') && (last == ')' || last == '）')) return t
            }
            return "($t)"
        }

        /**
         * 分段转写结果按「话一(动作)话二」拼合(2026-10-31 用户口径:按住中
         * 原位段=普通话原文、侧移段=动作场景括号包裹,中间漏括号即本函数缺失)。
         * 空白段(太短没听清)跳过;全空返回空串由调用方给「没听清」。
         */
        internal fun buildSegmentedText(segments: List<Pair<Boolean, String>>): String =
            segments.mapNotNull { (isAction, text) ->
                val t = text.trim()
                when {
                    t.isEmpty() -> null
                    isAction -> wrapActionBracket(t)
                    else -> t
                }
            }.joinToString("")
    }

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

    // ── 角色状态(E 工作包 E2):回合收尾生成 + 失败保持最近有效 + 规则兜底 ──
    val statusManager = com.neethu.gouxiang.chat.CharacterStatusManager(
        db = repo.db,
        llm = ChatOrchestrator.defaultLlm(),
        llmConfig = { orchestrator.llmConfigForMemory() },
        scope = viewModelScope,
        autoDetail = { com.neethu.gouxiang.ai.GenerationPrefs.statusDetailAuto(getApplication<Application>()) },
    )

    init {
        orchestrator.detailedMemoryHook = { character, conversationId, _, _, _, _ ->
            val pending = durableMemory.pending(conversationId)
            val chars = pending.sumOf { it.user.length + it.assistant.length }
            if (memoryCoordinator.autoSummarize && (pending.size >= memoryCoordinator.everyTurns || chars >= memoryCoordinator.minChars)) {
                viewModelScope.launch { durableMemory.run(character, conversationId) }
            }
        }
        // E2 状态收尾:真实生成成功的回合结束后 fire-and-forget 生成角色状态
        // (冷却/单飞/版本拦截/失败保持都在管理器内部;失败不影响聊天回合)。
        orchestrator.statusHook = { character, conversationId, turnId, userText, assistantText, userMessageId ->
            statusManager.onTurnCompleted(character, conversationId, turnId, userText, assistantText, userMessageId)
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

    /** 角色入口需要的全部会话，包括尚未开始对话的预建会话。 */
    val conversations: StateFlow<List<ConversationRow>> = repo.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 消息列表和最近聊过只展示用户发过消息的会话。 */
    val chattedConversations: StateFlow<List<ConversationRow>> = repo.chattedConversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 聊天主页 03 打开的会话 id(null=未进入)。 */
    private val _chatConversationId = MutableStateFlow<Long?>(null)
    val chatConversationId: StateFlow<Long?> = _chatConversationId.asStateFlow()

    /** Durable conversation discoveries remain available after the transient chat reminder closes. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val contentCandidates: StateFlow<List<com.neethu.gouxiang.data.ContentCandidateEntity>> =
        _chatConversationId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repo.db.contentDiscoveryDao().observeForConversation(id)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val selectedCandidateId = MutableStateFlow<Long?>(null)
    val selectedContentCandidate: StateFlow<com.neethu.gouxiang.data.ContentCandidateEntity?> =
        combine(contentCandidates, selectedCandidateId) { list, id -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _candidateBodyText = MutableStateFlow<String?>(null)
    val candidateBodyText: StateFlow<String?> = _candidateBodyText.asStateFlow()
    private val _candidateImageFile = MutableStateFlow<String?>(null)
    val candidateImageFile: StateFlow<String?> = _candidateImageFile.asStateFlow()
    private val _candidateHandbook = MutableStateFlow<com.neethu.gouxiang.data.ChatDerivativeEntity?>(null)
    val candidateHandbook: StateFlow<com.neethu.gouxiang.data.ChatDerivativeEntity?> = _candidateHandbook.asStateFlow()

    init {
        viewModelScope.launch {
            selectedContentCandidate.collectLatest { candidate ->
                _candidateBodyText.value = null
                _candidateImageFile.value = null
                if (_candidateHandbook.value?.id != candidate?.resultId || candidate?.kind != "HANDBOOK") {
                    _candidateHandbook.value = null
                }
                val resultId = candidate?.resultId ?: return@collectLatest
                when (candidate.kind) {
                    "INNER_VOICE" -> repo.db.innerVoiceDao().forCharacter(candidate.characterId)
                        .firstOrNull { it.id == resultId }?.let {
                            _candidateBodyText.value = it.content.ifBlank { it.echo }
                            _candidateImageFile.value = it.imageFile.takeIf(String::isNotBlank)
                        }
                    "MOMENT" -> repo.db.momentDao().byId(resultId)?.let {
                        _candidateBodyText.value = it.text
                        _candidateImageFile.value = it.imageFile.takeIf(String::isNotBlank)
                    }
                    "HANDBOOK", "NOVEL" -> repo.db.chatDerivativeDao().byId(resultId)?.let { record ->
                        if (candidate.kind == "HANDBOOK") _candidateHandbook.value = record
                        _candidateBodyText.value = if (candidate.kind == "HANDBOOK") {
                            com.neethu.gouxiang.derivatives.ChatDerivativeOutput.handbookFromJson(record.bodyJson)
                                ?.paragraphs?.joinToString("\n\n") { it.body }
                        } else {
                            com.neethu.gouxiang.derivatives.ChatDerivativeOutput.novelFromJson(record.bodyJson)
                                ?.let { novel -> (listOf(novel.introduction) + novel.chapters.map { "${it.title}\n${it.content}" })
                                    .filter(String::isNotBlank).joinToString("\n\n") }
                        }
                        _candidateImageFile.value = record.imageFile.takeIf(String::isNotBlank)
                    }
                }
            }
        }
    }

    fun openContentCandidate(id: Long) {
        selectedCandidateId.value = id
        viewModelScope.launch {
            repo.db.contentDiscoveryDao().setReminder(id, "SEEN")
            com.neethu.gouxiang.work.ContentCandidateBodyWorker.enqueueManual(getApplication(), id)
        }
    }

    fun retryContentCandidate(id: Long) {
        viewModelScope.launch { com.neethu.gouxiang.work.ContentCandidateBodyWorker.enqueueManual(getApplication(), id) }
    }

    fun retryContentCandidateImage(id: Long) {
        viewModelScope.launch { com.neethu.gouxiang.work.ContentCandidateImageWorker.enqueue(getApplication(), id) }
    }

    fun closeContentCandidate() { selectedCandidateId.value = null }
    fun markContentCandidateSeen(id: Long) {
        viewModelScope.launch { repo.db.contentDiscoveryDao().setReminder(id, "SEEN") }
    }
    fun dismissContentCandidate(id: Long) {
        viewModelScope.launch { repo.db.contentDiscoveryDao().setReminder(id, "DISMISSED") }
    }

    /** 当前打开的是否手机聊天形态(F4;消息列表按会话 mode=phone 入口)。 */
    private val _isPhoneChat = MutableStateFlow(false)
    val isPhoneChat: StateFlow<Boolean> = _isPhoneChat.asStateFlow()

    private val _chatSoundEnabled = MutableStateFlow(ChatPrefs.soundEnabled(app))
    val chatSoundEnabled: StateFlow<Boolean> = _chatSoundEnabled.asStateFlow()
    private val chatSpeechPlayer = com.neethu.gouxiang.ai.speech.ChatSpeechPlayer(app, viewModelScope) {
        com.neethu.gouxiang.ai.config.AiConfigStore.load(getApplication())
    }
    private val characterVoiceStore by lazy {
        com.neethu.gouxiang.ai.speech.CharacterVoiceStore(getApplication())
    }

    private suspend fun speechVoiceForConversation(conversationId: Long): com.neethu.gouxiang.ai.speech.SpeechVoice {
        val conv = repo.db.conversationDao().byId(conversationId)
            ?: return com.neethu.gouxiang.ai.speech.SpeechVoice(null)
        val character = repo.characterById(conv.characterId)
            ?: return com.neethu.gouxiang.ai.speech.SpeechVoice(null)
        val assignment = characterVoiceStore.getOrAssign(character)
        return com.neethu.gouxiang.ai.speech.SpeechVoice(assignment.voiceId, assignment.fallbackVoiceId)
    }

    private var chatSpeechForeground = false

    fun setChatSpeechForeground(foreground: Boolean) {
        chatSpeechForeground = foreground
        if (!foreground) chatSpeechPlayer.stop()
    }

    fun toggleChatSound() {
        val enabled = !_chatSoundEnabled.value
        _chatSoundEnabled.value = enabled
        ChatPrefs.setSoundEnabled(getApplication(), enabled)
        if (!enabled) chatSpeechPlayer.stop()
    }

    private fun speakChatReply(conversationId: Long, phoneMode: Boolean, result: com.neethu.gouxiang.chat.TurnResult, contextText: String = "") {
        if (chatSpeechForeground && !phoneMode && _chatSoundEnabled.value && _chatConversationId.value == conversationId &&
            !_isPhoneChat.value && _voiceCallConversationId.value == null
        ) {
            (result as? com.neethu.gouxiang.chat.TurnResult.Completed)?.let {
                chatSpeechPlayer.speak(it.bubbles, onUnavailable = {
                    android.widget.Toast.makeText(
                        getApplication(), "语音播放失败，请检查语音服务和系统文字转语音设置", android.widget.Toast.LENGTH_LONG,
                    ).show()
                }, contextText = contextText) { speechVoiceForConversation(conversationId) }
            }
        }
    }

    override fun onCleared() {
        chatSpeechPlayer.stop()
        super.onCleared()
    }

    /** characterId → 头像缓存文件绝对路径(null=未生成,UI 画占位)。 */
    private val _avatarFiles = MutableStateFlow<Map<Long, String?>>(emptyMap())
    val avatarFiles: StateFlow<Map<Long, String?>> = _avatarFiles

    // ── 角色头像生成状态(C 工作包 C3:生成中/失败/重试可见)──────────

    /** characterId → 生成状态(Idle/Generating/Failed(reason)/Ready)。 */
    private val _avatarStates = MutableStateFlow<Map<Long, AvatarGenState>>(emptyMap())
    val avatarStates: StateFlow<Map<Long, AvatarGenState>> = _avatarStates.asStateFlow()

    /** 本进程内已尝试过的版本化缓存键(失败后由 [retryAvatar] 清除以便重试)。 */
    private val avatarAttemptedKeys = mutableSetOf<String>()

    /** 头像缓存目录(与 [com.neethu.gouxiang.ai.image.AvatarImageProvider] 一致)。 */
    private val AVATAR_DIR = "avatars"

    /** 生成失败的统一原因文案(网络/密钥/服务端错误的可读收口)。 */
    private val AVATAR_FAIL_REASON = "头像生成失败,请检查网络与生图配置后重试"

    /** 头像生成 provider(进程内单例;filesDir/avatars 缓存)。 */
    private var avatarProviderLazy: com.neethu.gouxiang.ai.image.AvatarImageProvider? = null

    private fun avatarProvider(): com.neethu.gouxiang.ai.image.AvatarImageProvider =
        avatarProviderLazy ?: synchronized(this) {
            avatarProviderLazy ?: com.neethu.gouxiang.ai.image.AvatarImageProvider(
                getApplication<Application>().filesDir,
            ).also { avatarProviderLazy = it }
        }

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
                sidestoryCount = repo.countSidestoryRecords(character.id),
                handbookCount = repo.countChatDerivatives(character.id, com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_HANDBOOK),
                novelCount = repo.countChatDerivatives(character.id, com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_NOVEL),
                photoCount = repo.countChatDerivatives(character.id, com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_PHOTO),
            )
            repo.observeMessages(conversationId).collect { messages ->
                _characterHomeUi.value = _characterHomeUi.value.copy(
                    // E2 状态条数据源:记录优先,无记录走 StatusRule 规则兜底
                    // (与聊天页同一 currentLine,主页/聊天页状态一致)。
                    status = statusManager.currentLine(character, conversationId, messages),
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
    private val chatRoundMutex = Mutex()

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
        cancelVoiceInput()
        draftJob?.cancel()
        draftJob = null
        _streamDraft.value = ""
        chatSpeechPlayer.stop()
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
            // D 工作包 D4(进页消费先行):未读主动批次 → 已读 + 会话角标清零,
            // 返回的批次决定「离线分隔」锚点;随后手机形态按与后台扫描同一条
            // 链路(频控/预约/幂等门)尝试补一批。开关关闭只消费不生成(验收 #1)。
            val consumed = runCatching {
                proactiveService().onConversationOpened(conversationId, generateIfAllowed = phoneMode)
            }.getOrElse {
                android.util.Log.w("ProactiveOutbox", "entry consume failed: ${it.message}")
                emptyList()
            }
            val all = repo.allMessages(conversationId)
            _chatUi.value = _chatUi.value.copy(
                bubbleItems = toBubbleItems(all, offlineSeparators(consumed)),
                separatorTurnIds = offlineSeparators(consumed),
                status = statusManager.currentLine(character, conversationId, all),
            )
            // E2 状态条数据源:角色状态记录优先,无记录走 StatusRule 兜底。
            // 状态生成异步落库(晚于本回合消息流发射),两条流各自驱动:
            // 消息流刷气泡,状态记录流刷状态条(取当前最新消息快照)。
            var latestMessages: List<MessageEntity> = all
            launch {
                repo.observeMessages(conversationId).collect { messages ->
                    latestMessages = messages
                    _chatUi.value = _chatUi.value.copy(
                        bubbleItems = toBubbleItems(messages, _chatUi.value.separatorTurnIds),
                    )
                }
            }
            repo.db.characterStatusDao().observeForCharacter(character.id).collect {
                // 状态条刷新是挂起路径(currentLine 走库):先算 status 再读 state,
                // copy 基于挂起后的最新快照 —— 若先读后挂起,期间消息流写进来的
                // 新气泡会被这份过期快照回退掉(回复完成后气泡消失的一类成因)。
                val line = statusManager.currentLine(character, conversationId, latestMessages)
                _chatUi.value = _chatUi.value.copy(status = line)
            }
        }
    }

    fun closeChat() {
        chatSpeechPlayer.stop()
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
                // D 工作包 D1:用户活跃时间回写账本(在线输入避让的判定源头)
                runCatching { repo.noteUserActivity(conversationId, character.id, userMsg.at) }
                // 会话行摘要按泡型降级(附录 C-I3):用户卡片行 kind=card,摘要走
                // 载荷摘要,防 [卡片:…] 原始标记泄露;纯文本摘要仍=原文。
                repo.touchConversation(
                    conversationId,
                    conversationSummary(userMsg.kind, userMsg.content, userMsg.actionsJson),
                    userMsg.at,
                )
                // 回合失败显式化(02 文档 §3.5):失败记 failedTurn 出重试条,
                // 成功清 failedTurn(失败后另发新消息成功同样撤旧条)。
                // 先跑回合再读 state:若先读 `_chatUi.value` 再把整个回合当参数跑
                // (Kotlin 先求值接收者),回合期间消息流实时上屏的用户/回复泡会被
                // 这份回合前快照整体回退掉 —— 真机上表现为「回复完成后气泡消失,
                // 退出重进才出现」。
                val turnResult = runRound(conversationId, character.id, body, phoneMode, userMessageId)
                speakChatReply(conversationId, phoneMode, turnResult, body)
                if (_chatUi.value.conversationId == conversationId) {
                    _chatUi.value = _chatUi.value.afterTurnResult(turnResult, userMessageId)
                }
                // 回合收尾兜底刷一次全量(读在全部落库提交之后):Room 流的失效
                // 通知在并发写(状态生成/记忆收尾同时走库)下存在偶发丢失,
                // 收尾主动对账,回复泡不再依赖流推送才上屏。
                refreshChatBubbles(conversationId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 编排器内已显式失败,这里防数据库层等硬异常把协程吞掉;
                // 同样置失败态,发送不再静默无反馈
                android.util.Log.e("ChatOrchestrator", "round failed", e)
                if (_chatUi.value.conversationId == conversationId) {
                    _chatUi.value = _chatUi.value.copy(failedTurn = FailedTurn(conversationId, body, userMessageId))
                }
            } finally {
                if (_chatUi.value.conversationId == conversationId) {
                    _chatUi.value = _chatUi.value.copy(sending = false)
                }
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
        val phoneMode = _isPhoneChat.value
        _chatUi.value = state.copy(sending = true, failedTurn = null)
        roundJob = viewModelScope.launch {
            try {
                // 先跑回合再读 state(与 sendChatMessage 同口径):防回合前快照
                // 在收尾回写时把回合期间实时上屏的气泡回退掉。
                val turnResult = runRound(failed.conversationId, character.id, failed.userText, phoneMode = phoneMode, userMessageId = failed.userMessageId)
                speakChatReply(failed.conversationId, phoneMode, turnResult, failed.userText)
                _chatUi.value = _chatUi.value.afterTurnResult(turnResult, failed.userMessageId)
                refreshChatBubbles(failed.conversationId)
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

    /** 普通聊天页「+」面板「发送图片」的上下文提示行(落库正文 + 回合 userText)。 */
    private fun imageMessageNote(): String = "（发来一张图片）"

    /**
     * 普通聊天页「+」面板「发送图片」:相册选图 → 压缩拷贝进 app 私有目录 →
     * kind=image 消息落库(真实文件路径存 mediaPath,正文=括号提示行)→
     * 图片随请求发送给模型。回合链路/失败显式化与
     * [sendChatMessage] 完全同构(02 文档 §3.5),仅用户侧落库行构造不同。
     */
    fun sendImageMessage(uri: android.net.Uri) {
        val state = _chatUi.value
        val conversationId = state.conversationId
        val character = state.character ?: return
        if (state.sending) return
        val phoneMode = _isPhoneChat.value
        _chatUi.value = state.copy(sending = true)
        roundJob = viewModelScope.launch {
            var userMessageId: Long? = null
            try {
                // 选图 URI 是一次性读权限,先在 IO 线程压缩拷贝为持久文件再走回合。
                val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    copyPickedImage(uri)
                }
                if (path == null) {
                    android.widget.Toast.makeText(getApplication(), "图片读取失败", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val at = System.currentTimeMillis()
                val userMsg = MessageEntity(
                    conversationId = conversationId,
                    turnId = "turn-user-$at",
                    role = "user",
                    kind = "image",
                    content = imageMessageNote(),
                    mediaPath = path,
                    at = at,
                )
                userMessageId = repo.insertMessage(userMsg)
                // 同 sendChatMessage:活跃时间回写 + 会话行摘要按泡型降级(image→[图片])
                runCatching { repo.noteUserActivity(conversationId, character.id, userMsg.at) }
                repo.touchConversation(
                    conversationId,
                    conversationSummary(userMsg.kind, userMsg.content, userMsg.actionsJson),
                    userMsg.at,
                )
                // 先跑回合再读 state(快照回退防护,与 sendChatMessage 同口径)
                val turnResult = runRound(conversationId, character.id, userMsg.content, phoneMode, userMessageId)
                speakChatReply(conversationId, phoneMode, turnResult, userMsg.content)
                if (_chatUi.value.conversationId == conversationId) {
                    _chatUi.value = _chatUi.value.afterTurnResult(turnResult, userMessageId)
                }
                refreshChatBubbles(conversationId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatOrchestrator", "image round failed", e)
                if (_chatUi.value.conversationId == conversationId) {
                    _chatUi.value = _chatUi.value.copy(
                        failedTurn = FailedTurn(conversationId, imageMessageNote(), userMessageId),
                    )
                }
            } finally {
                if (_chatUi.value.conversationId == conversationId) {
                    _chatUi.value = _chatUi.value.copy(sending = false)
                }
            }
        }
    }

    /**
     * 选图落盘:contentResolver 读流,按最长边粗降采样(>1600px 按 2 的幂缩)
     * 后转 JPEG(~85)写 `filesDir/chat_images/`,返回绝对路径;读图/写盘任一
     * 失败返回 null 由调用方提示。
     */
    private fun copyPickedImage(uri: android.net.Uri): String? = runCatching {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 1600)
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val dir = java.io.File(app.filesDir, "chat_images").apply { mkdirs() }
        val out = java.io.File(dir, "img-${System.currentTimeMillis()}.jpg")
        try {
            val saved = out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
            if (!saved) {
                out.delete()
                return null
            }
        } finally {
            bitmap.recycle()
        }
        out.absolutePath
    }.getOrNull()

    /**
     * 「+」面板「回溯」一键回到上一轮:定位最后一条角色回复,复用长按回溯
     * [rewindToMessage](删该条及之后 + 联动清记忆/主动账本)。无角色回复时
     * 静默返回(UI 侧已先确认目标存在,这里兜底防并发删除)。
     */
    fun rewindLastTurn() {
        val conversationId = _chatUi.value.conversationId
        if (conversationId == 0L) return
        viewModelScope.launch {
            val lastAssistant = repo.allMessages(conversationId)
                .lastOrNull { it.role == "assistant" }?.id ?: return@launch
            rewindToMessage(lastAssistant)
        }
    }

    /**
     * 「+」面板「手机聊天」:同一会话切手机形态皮肤(F4)。不重载消息流 ——
     * 两形态共用同一份 chatUi 订阅与记忆;后续发送按 _isPhoneChat 取连发参数,
     * 与从消息列表角标进入手机聊天语义一致。
     */
    fun switchChatSkin(phoneMode: Boolean) {
        if (_chatConversationId.value == null) return
        if (_isPhoneChat.value == phoneMode) return
        _isPhoneChat.value = phoneMode
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
                voiceSource = { speechVoiceForConversation(convId) },
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
                    // 语音回合的泡同样事务落库:回聊天页前的兜底对账(仅同会话回写)
                    refreshChatBubbles(convId)
                    (result as? com.neethu.gouxiang.chat.TurnResult.Completed)?.bubbles
                        ?: emptyList()
                },
                // 主动接话(2026-11-04 通话全双工):用户持续沉默时人物先开口,复用
                // F4 范围 5 的主动消息链路 —— sceneLine 不落库不进上下文只驱动本轮,
                // 口语短连发;失败/模板降级返回空列表,引擎静默回聆听等下一轮计时
                proactiveRound = {
                    val conv = repo.db.conversationDao().byId(convId) ?: return@VoiceCallEngine emptyList()
                    val character = repo.characterById(conv.characterId) ?: return@VoiceCallEngine emptyList()
                    val outcome = orchestrator.respondProactively(
                        conversationId = convId,
                        characterId = character.id,
                        sceneLine = "(语音通话中对方一直没说话,你主动找话题接一句,像打电话那样口语、简短)",
                        onAssistantSaved = { },
                        burst = BurstParams.resolve(ReplyLength.SHORTER, phone = true, ChatPrefs.dialogMode(getApplication())),
                        mode = ChatPrefs.dialogMode(getApplication()),
                        phoneStyle = true,
                    )
                    // 主动泡同样事务落库,聊天页兜底对账(同上方语音回合口径)
                    refreshChatBubbles(convId)
                    (outcome as? com.neethu.gouxiang.chat.ProactiveTurnResult.Completed)
                        ?.takeIf { it.degraded.not() }?.bubbles
                        ?: emptyList()
                },
            )
        }
    }

    /** 打开语音通话页(聊天页电话图标)。 */
    fun openVoiceCall(conversationId: Long) {
        chatSpeechPlayer.stop()
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
    private suspend fun runRound(conversationId: Long, characterId: Long, userText: String, phoneMode: Boolean, userMessageId: Long? = null): com.neethu.gouxiang.chat.TurnResult = chatRoundMutex.withLock {
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
        val thisDraftJob = viewModelScope.launch {
            orchestrator.streamDraft.collect {
                if (_chatUi.value.conversationId == conversationId) _streamDraft.value = it
            }
        }
        draftJob = thisDraftJob
        try {
            orchestrator.respondTo(
                conversationId = conversationId,
                characterId = characterId,
                userText = userText,
                onAssistantSaved = { /* 上屏走 Room 流 */ },
                burst = burst,
                mode = style.mode,
                phoneStyle = phoneMode,
                userMessageId = userMessageId,
            )
        } finally {
            thisDraftJob.cancel()
            if (draftJob === thisDraftJob) {
                draftJob = null
                if (_chatUi.value.conversationId == conversationId) _streamDraft.value = ""
            }
        }
    }

    /**
     * 回合收尾兜底对账:全量重读当前会话消息并重建气泡列表。
     *
     * 气泡上屏的正常通路是 Room 消息流(任一落库自动推送);但回复泡走
     * `withTransaction` 落库,并发收尾写(状态生成/记忆总结同时走库)下
     * 失效通知存在偶发丢失 —— 真机实锤:回复已入库、流不推送,页面停在
     * 回合前状态,退出重进(重读全量)才恢复。这里在回合全部落库提交之后
     * 主动读一次,让收尾不依赖流推送;流后续推送同一数据时是幂等覆盖。
     * 仅当聊天页仍停留在该会话时回写,防跨会话污染。
     */
    private suspend fun refreshChatBubbles(conversationId: Long) {
        if (_chatUi.value.conversationId != conversationId) return
        val all = repo.allMessages(conversationId)
        // allMessages 挂起期间聊天页可能已关闭/切换;读后复验再写
        // (读与写之间无挂起点,同线程原子)。
        if (_chatUi.value.conversationId != conversationId) return
        _chatUi.value = _chatUi.value.copy(
            bubbleItems = toBubbleItems(all, _chatUi.value.separatorTurnIds),
        )
    }

    // ── 手机聊天 F4:主动消息/长按回溯/重生成/语音 ────────────────────

    // (D 工作包:进页触发判定升级为账本+频控全链路,见 proactiveService()/
    //  debugProactiveDecision;旧的 pickProactiveTrigger 单点判定移除,
    //  调试探针 ai_cmd=proactive 走 debugProactiveDecision 同口径。)

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
                chatRoundMutex.withLock {
                draftJob?.cancel()
                draftJob = viewModelScope.launch {
                    orchestrator.streamDraft.collect {
                        if (_chatUi.value.conversationId == conversationId) _streamDraft.value = it
                    }
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
                if (_chatUi.value.conversationId == conversationId) _streamDraft.value = ""
                }
                // 主动连发同样走事务落库,收尾兜底对账(同 sendChatMessage)
                refreshChatBubbles(conversationId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatOrchestrator", "proactive round failed", e)
            } finally {
                if (_chatUi.value.conversationId == conversationId) _chatUi.value = _chatUi.value.copy(sending = false)
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
                // D 工作包 D4:回溯清账——被删除消息涉及的主动消息批次作废,
                // 未读数与角标按存活消息重算(不重复回放不复活)。
                runCatching { repo.rewindProactiveLedger(conversationId, messageId) }
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
            // D 工作包 D4:重生成同样先清账(被删主动批次作废,未读重算)
            runCatching { repo.rewindProactiveLedger(conversationId, all[turnStartIndex].id) }
            if (lastUser == null) {
                // 无用户输入可归因(主动消息回合):重新走主动场景
                _chatUi.value = state.copy(sending = true)
                try {
                    chatRoundMutex.withLock {
                    draftJob?.cancel()
                    draftJob = viewModelScope.launch {
                        orchestrator.streamDraft.collect {
                            if (_chatUi.value.conversationId == conversationId) _streamDraft.value = it
                        }
                    }
                    val result = orchestrator.respondProactively(
                        conversationId = conversationId,
                        characterId = character.id,
                        sceneLine = "(刚刚那条没说好,重新组织一下)重新发一条主动消息,连发 1~3 条。",
                        onAssistantSaved = { },
                        burst = BurstParams.resolve(ChatPrefs.replyLength(getApplication()), phone = phoneMode, ChatPrefs.dialogMode(getApplication())),
                        mode = ChatPrefs.dialogMode(getApplication()),
                        phoneStyle = phoneMode,
                    )
                    if (result is com.neethu.gouxiang.chat.ProactiveTurnResult.Completed) {
                        speakChatReply(conversationId, phoneMode, com.neethu.gouxiang.chat.TurnResult.Completed(result.bubbles))
                    }
                    draftJob?.cancel()
                    draftJob = null
                    if (_chatUi.value.conversationId == conversationId) _streamDraft.value = ""
                    }
                    refreshChatBubbles(conversationId)
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
                // 记 failedTurn 出重试条,避免「删了旧泡又没新回复」的空窗。
                // 先跑回合再读 state(与 sendChatMessage 同口径,防快照回退)。
                val turnResult = runRound(conversationId, character.id, lastUser.content, phoneMode = phoneMode, userMessageId = lastUser.id)
                speakChatReply(conversationId, phoneMode, turnResult, lastUser.content)
                _chatUi.value = _chatUi.value.afterTurnResult(turnResult, lastUser.id)
                refreshChatBubbles(conversationId)
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
     * 松手手势 → 转写结果去向(2026-10-31 用户口径:原地/侧移松手都直接发送
     * 上屏并保持语音输入模式,上划取消;侧移段由分段拼合包成「(动作)」括号文本)。
     */
    enum class VoiceInputAction {
        /** 原地松手:转写 → 直接发送进回合。 */
        SEND,
        /** 侧移松手:与 SEND 同路直发(侧移段=括号动作);保留枚举供手势层视觉态区分。 */
        INPUT,
        /** 上划出按钮松手:停录丢弃,什么都不发。 */
        CANCEL,
    }

    /**
     * 语音输入状态机(与 [VoiceCallEngine] 的 holdStart/holdRelease 同构;
     * 松手去向见 [VoiceInputAction] 三态,失败置 notice 给 Toast 兜底,
     * 录音器永留后台线程)。
     *
     * 录音格式与 F6 完全同源:[WavRecorder](16-bit PCM 单声道 16 kHz → WAV),
     * 误触丢弃阈值同 [VoiceCallEngine.MIN_WAV_BYTES];ASR = 硅基
     * Qwen3-ASR(适配器与 Key 解析同 F6 的 [VoiceCallEngine.transcribe] 口径)。
     */
    private val recorder = com.neethu.gouxiang.ai.speech.WavRecorder().also { r ->
        // 实时音量 → 状态流(输入条按住中的 ||||| 波形;音频线程触发,StateFlow 自带合流)
        r.onLevel = { _voiceInputLevel.value = it }
    }

    /** 语音输入进行中(输入条按住态高亮);holdRelease 开始转写后即 false。 */
    private val _voiceInputActive = MutableStateFlow(false)
    val voiceInputActive: StateFlow<Boolean> = _voiceInputActive.asStateFlow()

    /** 录音实时音量(0..1;输入条按住中的波形条;未录音恒 0)。 */
    private val _voiceInputLevel = MutableStateFlow(0f)
    val voiceInputLevel: StateFlow<Float> = _voiceInputLevel.asStateFlow()

    /** 语音输入失败提示(空=无);Toast 消费后清空。 */
    private val _voiceInputNotice = MutableStateFlow("")
    val voiceInputNotice: StateFlow<String> = _voiceInputNotice.asStateFlow()

    /** 语音输入转写完成(文本;UI 消费填入草稿框后即清空)。 */
    private val _voiceInputTranscribed = MutableStateFlow("")
    val voiceInputTranscribed: StateFlow<String> = _voiceInputTranscribed.asStateFlow()

    /** 转写在途(松手后 ASR 网络请求期间输入条提示「识别中」)。 */
    private val _voiceInputRecognizing = MutableStateFlow(false)
    val voiceInputRecognizing: StateFlow<Boolean> = _voiceInputRecognizing.asStateFlow()

    /**
     * 按住中的分段缓冲(手势态区间封存的 WAV 段;first=该段是否侧移动作段)。
     * 只在主线程(UI 回调/协程内)读写,无需并发容器。
     */
    private val voiceInputSegments = ArrayDeque<Pair<Boolean, ByteArray>>()

    /** 当前录音区间所属手势态(开录=原位;侧移互切经 [voiceInputModeSwitch] 翻转)。 */
    private var voiceInputCurrentIsAction = false

    // 识别异步完成时仍需确认是发起录音的会话，包括退出后重新进入同一会话。
    private val voiceInputSession = VoiceInputSession()
    private var voiceInputToken: VoiceInputSession.Token? = null
    private var voiceInputRecognitionJob: Job? = null
    private var voiceRecorderStopping = false

    /**
     * 按住说话·按下开录(麦克风被占用/无权限给 notice,不崩)。
     * 按下即中断正在朗读的 TTS(2026-11-04 口径:回复自动朗读/语音条共路,
     * 说话前先停,别让播音盖住录音)。
     */
    fun voiceInputStart() {
        chatSpeechPlayer.stop()
        voicePlayer.stop()
        if (_voiceInputActive.value || _voiceInputRecognizing.value) return
        val conversationId = _chatConversationId.value ?: return
        _voiceInputNotice.value = ""
        _voiceInputLevel.value = 0f
        voiceInputSegments.clear()
        voiceInputCurrentIsAction = false
        try {
            recorder.start()
            voiceInputToken = voiceInputSession.begin(conversationId)
            _voiceInputActive.value = true
        } catch (e: Exception) {
            android.util.Log.w("VoiceInput", "mic start failed", e)
            _voiceInputNotice.value = "麦克风不可用,检查录音权限"
        }
    }

    /**
     * 按住中手势态切换的段边界(2026-10-31 用户口径「话一(动作)话二」):
     * 原位/侧移互切时 UI 回调,封存当前态已说的话为一段,新段按新态归属;
     * 与当前态相同(含 Cancel 不参与段切分)则无事发生。
     */
    fun voiceInputModeSwitch(isAction: Boolean) {
        if (_voiceInputActive.value.not() || isAction == voiceInputCurrentIsAction) return
        val wav = recorder.sealSegment()
        if (wav != null) voiceInputSegments += (voiceInputCurrentIsAction to wav)
        voiceInputCurrentIsAction = isAction
    }

    /**
     * 按住说话·松手。按手势去向分发(2026-10-31 口径,分段+直发):
     * - [VoiceInputAction.CANCEL] 上划取消:停录丢弃全部段,不发不转写;
     * - [VoiceInputAction.SEND] / [VoiceInputAction.INPUT] 原地或侧移松手:
     *   各段转写按「话一(动作)话二」拼合 → 直接发送进回合并保持语音输入
     *   模式(2026-10-31 用户口径:侧移松手不再切文字态填输入框;仅回合
     *   进行中发不进去时回落填输入框防丢,给 notice 说明);
     * 段=手势态区间(原位段原文/侧移段括号包裹),单段无切换时行为同旧版。
     */
    fun voiceInputRelease(action: VoiceInputAction = VoiceInputAction.SEND) {
        if (_voiceInputActive.value.not()) return
        val token = voiceInputToken
        voiceInputToken = null
        _voiceInputActive.value = false
        _voiceInputLevel.value = 0f
        if (action == VoiceInputAction.CANCEL) {
            voiceInputSession.invalidate()
            _voiceInputRecognizing.value = true
            viewModelScope.launch {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        recorder.stop()
                        recorder.takeWav() // 丢弃
                    }
                } finally {
                    _voiceInputRecognizing.value = false
                }
            }
            voiceInputSegments.clear()
            return
        }
        _voiceInputRecognizing.value = true
        voiceRecorderStopping = true
        val recognitionJob = viewModelScope.launch {
            try {
                val lastWav = try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                        recorder.stop()
                        recorder.sealSegment() // 最后一段(终态说出的话)
                    }
                } finally {
                    voiceRecorderStopping = false
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (lastWav != null) voiceInputSegments += (voiceInputCurrentIsAction to lastWav)
                val pending = voiceInputSegments.toList()
                voiceInputSegments.clear()
                // 各段并行转写(ASR 是瓶颈,3 段串行 ≈3 倍延迟),按原序拼合
                val texts = pending.map { (isActionSeg, wav) ->
                    async {
                        val t = if (wav.size <= com.neethu.gouxiang.ai.speech.VoiceCallEngine.MIN_WAV_BYTES) {
                            ""
                        } else {
                            transcribeVoiceInput(wav)
                        }
                        isActionSeg to t
                    }
                }.awaitAll()
                val text = buildSegmentedText(texts)
                if (token == null || !voiceInputSession.isCurrent(token, _chatConversationId.value)) return@launch
                if (text.isBlank()) {
                    _voiceInputNotice.value = "没听清,再试一次?"
                } else if (_chatUi.value.sending) {
                    // 回合进行中发不进去时保留识别文字，避免丢失。
                    _voiceInputTranscribed.value = text
                    _voiceInputNotice.value = "对方正在回复,已转入输入框"
                } else {
                    sendChatMessage(text, phoneMode = _isPhoneChat.value)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("VoiceInput", "asr failed", e)
                _voiceInputNotice.value = "识别失败,请重试"
            } finally {
                if (voiceInputRecognitionJob === kotlinx.coroutines.currentCoroutineContext()[Job]) {
                    voiceInputRecognitionJob = null
                    _voiceInputRecognizing.value = false
                }
            }
        }
        voiceInputRecognitionJob = recognitionJob
    }

    /** 识别:WAV → 文本(Key/模型解析与 VoiceCallEngine 同口径;失败返回空串)。 */
    private suspend fun transcribeVoiceInput(wav: ByteArray): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                com.neethu.gouxiang.ai.speech.ConfiguredAsr.transcribe(
                    wav, com.neethu.gouxiang.ai.config.AiConfigStore.load(getApplication()),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
        voiceInputSession.invalidate()
        voiceInputToken = null
        voiceInputRecognitionJob?.cancel()
        if (voiceInputRecognitionJob != null && !voiceRecorderStopping) {
            voiceInputRecognitionJob = null
            _voiceInputRecognizing.value = false
        }
        if (_voiceInputActive.value) {
            _voiceInputActive.value = false
            _voiceInputLevel.value = 0f
            try { recorder.stop() } catch (_: Exception) { }
        }
    }

    // ── 主动消息(D 工作包 D2-D4;离线阈值等策略数值走 GenerationPrefs 覆盖键,
    // 默认值收敛在 ProactivePolicy.Config,不再另存 VM 级可变字段)──────

    /** 主动消息服务(生产装配;账本挂 repo 单例,通知走通道二)。 */
    private fun proactiveService(): com.neethu.gouxiang.proactive.ProactiveOutboxService {
        val app = getApplication<Application>()
        return com.neethu.gouxiang.proactive.ProactiveOutboxService(
            context = app,
            db = repo.db,
            ledger = repo.proactiveLedger,
            generate = com.neethu.gouxiang.proactive.ProactiveGeneration.production(app),
            notify = { n ->
                com.neethu.gouxiang.work.NotificationPoster.proactive(app, n.characterName, n.conversationId, n.count)
            },
        )
    }

    /**
     * 调试命令 `proactive_backdate`(D2 验收口):把当前会话的用户活跃时间
     * 回拨到 now-[ms](只动账本,不碰消息数据),供扫描判定模拟「离线回归」。
     */
    suspend fun debugProactiveBackdate(ms: Long): String {
        val conversationId = _chatUi.value.conversationId
        if (conversationId == 0L) throw IllegalStateException("no open chat")
        val conv = repo.db.conversationDao().byId(conversationId)
            ?: throw IllegalStateException("conversation $conversationId not found")
        val to = System.currentTimeMillis() - ms
        repo.proactiveLedger.debugBackdateActivity(conversationId, to)
        return "activity backdated conv=$conversationId to ${com.neethu.gouxiang.protocol.ProactiveMessages.offlineMsDescription(System.currentTimeMillis() - to)} ago (poll proactive_state)"
    }

    /**
     * 调试命令 `proactive_scan`(D2/D3 验收口):手动跑一轮后台扫描,与周期
     * Worker 同一条链路(开关/频控/预约/收口/通知)。入队即返回,结果用
     * msgs / proactive_state / 系统通知确认。
     */
    fun debugProactiveScan(): String {
        viewModelScope.launch {
            runCatching {
                val report = proactiveService().scanOnce()
                android.util.Log.i("ProactiveOutbox", "manual scan: ${report.reports.size} conversations, ${report.generated.size} generated, anyFailed=${report.anyFailed}")
                for (r in report.reports) {
                    when (r) {
                        is com.neethu.gouxiang.proactive.ProactiveOutboxService.Report.Generated ->
                            android.util.Log.i("ProactiveOutbox", "scan conv=${r.conversationId} GENERATED kind=${r.kind} count=${r.count} degraded=${r.degraded}")
                        is com.neethu.gouxiang.proactive.ProactiveOutboxService.Report.Blocked ->
                            android.util.Log.i("ProactiveOutbox", "scan conv=${r.conversationId} BLOCKED: ${r.reason}")
                        is com.neethu.gouxiang.proactive.ProactiveOutboxService.Report.Failed ->
                            android.util.Log.w("ProactiveOutbox", "scan conv=${r.conversationId} FAILED: ${r.reason}")
                        is com.neethu.gouxiang.proactive.ProactiveOutboxService.Report.Skipped ->
                            android.util.Log.i("ProactiveOutbox", "scan conv=${r.conversationId} SKIPPED: ${r.reason}")
                    }
                }
            }.onFailure { android.util.Log.w("ProactiveOutbox", "manual scan failed: ${it.message}") }
        }
        return "scan started (results per conversation in logcat -s ProactiveOutbox; poll msgs / proactive_state / notifications)"
    }

    /**
     * 调试命令 `proactive` 升级版(只读):按与服务/扫描完全相同的入参走一遍
     * 频控判定(不预约不生成),回显放行场景或拦截原因(D2 验收口)。
     */
    suspend fun debugProactiveDecision(): String {
        val conversationId = _chatUi.value.conversationId
            ?: throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
        val conv = repo.db.conversationDao().byId(conversationId)
            ?: throw IllegalStateException("conversation $conversationId not found")
        if (repo.db.messageDao().lastUserMessageAt(conversationId) == null) {
            return "last user activity=从未发言 verdict=blocked: 用户尚未在此会话发言"
        }
        val character = repo.characterById(conv.characterId)
            ?: throw IllegalStateException("character of conversation $conversationId not found")
        val service = proactiveService()
        val config = service.policyConfig()
        val now = System.currentTimeMillis()
        val snapshot = repo.proactiveLedger.snapshot(conversationId, character.id)
        val everProactive = repo.db.messageDao().countProactiveTurnMessages(conversationId) > 0
        val decision = com.neethu.gouxiang.protocol.ProactivePolicy.evaluate(
            now = now,
            config = config,
            snapshot = com.neethu.gouxiang.protocol.ProactivePolicy.Snapshot(
                conversationId = conversationId,
                everProactive = everProactive,
                lastUserActivityAt = snapshot.lastUserActivityAt,
                lastProactiveAt = snapshot.lastProactiveAt,
                dailyCount = snapshot.dailyCount,
                dailyStamp = snapshot.dailyStamp,
                unseenCount = snapshot.unseenCount,
            ),
            localDay = com.neethu.gouxiang.proactive.ProactiveOutboxService.localDayOf(now),
            localHour = com.neethu.gouxiang.proactive.ProactiveOutboxService.hourOf(now),
        )
        val age = snapshot.lastUserActivityAt?.let {
            com.neethu.gouxiang.protocol.ProactiveMessages.offlineMsDescription(now - it)
        } ?: "从未发言"
        val verdict = when (decision) {
            is com.neethu.gouxiang.protocol.ProactivePolicy.Decision.Fire ->
                "${decision.kind.name.lowercase()} would fire (batchMax=${decision.batchMax}; ${decision.reason})"
            is com.neethu.gouxiang.protocol.ProactivePolicy.Decision.Block -> "blocked: ${decision.reason}"
        }
        return "last user activity=$age unseen=${snapshot.unseenCount} verdict=$verdict"
    }

    /** 调试命令 `proactive_state`(只读):当前会话的账本状态行 + 未读批次。 */
    suspend fun debugProactiveState(): String {
        val conversationId = _chatUi.value.conversationId
            ?: throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
        val ledger = repo.proactiveLedger
        val state = repo.db.proactiveDao().stateFor(conversationId)
        val rows = repo.db.proactiveDao().forConversation(conversationId)
        val conv = repo.db.conversationDao().byId(conversationId)
        fun fmt(at: Long) = if (at <= 0) "-" else
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(at))
        val stateLine = if (state == null) {
            "(no ledger state yet)"
        } else {
            "activity=${fmt(state.lastUserActivityAt)} lastProactive=${fmt(state.lastProactiveAt)} " +
                "lastBatch(count=${state.lastBatchCount}) unseen=${state.unseenCount} " +
                "today(${state.dailyStamp})=${state.dailyCount} override=${state.activityOverride}"
        }
        val rowsLine = if (rows.isEmpty()) "(no batches)" else rows.joinToString("\n") {
            "${it.batchId.take(8)} ${it.kind.lowercase()} ${it.state.lowercase()} count=${it.count} " +
                "notified=${it.notified} seen=${it.seen} msgIds=${it.messageIds.ifBlank { "-" }}" +
                (if (it.failureReason.isNotBlank()) " 「${it.failureReason.take(24)}」" else "")
        }
        return "conv=$conversationId unread=${conv?.unread ?: 0}\n$stateLine\n$rowsLine"
    }

    /** 任务历史页一次载入的行数上限(本地全量有限,非真分页)。 */
    private val TASK_HISTORY_LIMIT = 100

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

    /** 角色头像生成(范围说明 + C 工作包 C3 状态机):
     *  - 版本化缓存键 [avatarCacheKey]:改写形象保存后版本 +1 → 新键 → 新文件,
     *    旧头像文件保留在图库可「设为当前」切回;
     *  - 生成中/失败/就绪对用户可见([avatarStates]);失败不静默吞,同一版本键
     *    本进程内只自动尝试一次,重试走 [retryAvatar] 显式发起;
     *  - 已落库头像文件(avatarFile 列)存在时直接复用,重启不重复请求;
     *  - 形象描述为空时置可解释失败态,不发请求;
     *  - 生图 prompt 在这里拼好再交给 provider(ImageGenAdapter 契约:
     *    prompt = appearance + 风格词缀,由调用方拼好),与心声/朋友圈配图
     *    同一拼法 —— 修复前头像只喂 appearance、风格词缀缺失;
     *  - 网络请求在数据库事务外(只读列 + 成功后单列回写)。
     */
    fun ensureAvatars(characters: List<CharacterEntity>) {
        viewModelScope.launch {
            val provider = avatarProvider()
            val filesDir = getApplication<Application>().filesDir
            val updated = _avatarFiles.value.toMutableMap()
            val states = _avatarStates.value.toMutableMap()
            for (character in characters) {
                val key = avatarCacheKey(character.id, character.avatarVersion)
                val state = states[character.id]
                if (state is AvatarGenState.Ready && updated.containsKey(character.id)) continue
                if (state is AvatarGenState.Generating) continue
                // 1) 已落库头像文件(重启后/图库指定后直接复用,不发请求)
                val persisted = character.avatarFile.takeIf { it.isNotBlank() }?.let {
                    java.io.File(java.io.File(filesDir, AVATAR_DIR), it)
                }
                if (persisted != null && persisted.isFile && persisted.length() > 0) {
                    updated[character.id] = persisted.absolutePath
                    states[character.id] = AvatarGenState.Ready
                    _avatarFiles.value = updated.toMap()
                    _avatarStates.value = states.toMap()
                    continue
                }
                // 2) 未填形象描述:置可解释状态,不发请求(手工卡照常可用)
                if (character.appearance.isBlank()) {
                    states[character.id] = AvatarGenState.Failed(CharacterFormCopy.AVATAR_NO_APPEARANCE)
                    _avatarStates.value = states.toMap()
                    continue
                }
                // 3) 同版本键已尝试过(含失败):不再自动发请求,等待显式重试
                if (!avatarAttemptedKeys.add(key)) {
                    if (states[character.id] !is AvatarGenState.Failed) {
                        states[character.id] = AvatarGenState.Failed(AVATAR_FAIL_REASON)
                        _avatarStates.value = states.toMap()
                    }
                    continue
                }
                states[character.id] = AvatarGenState.Generating
                _avatarStates.value = states.toMap()
                val file = provider.getOrGenerate(
                    characterKey = key,
                    prompt = buildAvatarPrompt(character.appearance, character.appearanceStyle),
                )
                if (file != null) {
                    updated[character.id] = file.absolutePath
                    states[character.id] = AvatarGenState.Ready
                    repo.db.characterDao().setAvatarFile(character.id, file.name)
                    // 编辑页开着且正是该角色:刷新图库(新落盘文件立即可见)
                    if (_editorOpen.value && _editorUi.value.editingCharacterId == character.id) {
                        refreshEditorGallery(character.id)
                    }
                } else {
                    states[character.id] = AvatarGenState.Failed(AVATAR_FAIL_REASON)
                }
                _avatarFiles.value = updated.toMap()
                _avatarStates.value = states.toMap()
            }
        }
    }

    /** 头像显式重试(创作页/编辑页「重试」;清除尝试记录后重走生成链)。 */
    fun retryAvatar(characterId: Long) {
        viewModelScope.launch {
            val character = repo.characterById(characterId) ?: return@launch
            avatarAttemptedKeys.remove(avatarCacheKey(character.id, character.avatarVersion))
            _avatarStates.value = _avatarStates.value - characterId
            ensureAvatars(listOf(character))
        }
    }

    // ── 角色创作与管理(C 工作包 C1~C3)──────────────────────────────

    /** 创作页角色列表(全部角色;种子与自建同列,行内以 source 区分)。 */
    val allCharacters: StateFlow<List<CharacterEntity>> =
        repo.db.characterDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 新建角色草稿槽(仅新建路径;编辑直接改行不落草稿)。 */
    private val draftPrefs by lazy { CharacterDraftPrefs(getApplication()) }

    // ── 编辑页(表单)状态 ───────────────────────────────────────────

    private val _editorOpen = MutableStateFlow(false)
    val editorOpen: StateFlow<Boolean> = _editorOpen.asStateFlow()

    private val _editorUi = MutableStateFlow(CharacterEditorUi())
    val editorUi: StateFlow<CharacterEditorUi> = _editorUi.asStateFlow()

    /** 编辑页一次性提示(设为当前/删除图等;Toast 消费后清空)。 */
    private val _editorNotice = MutableStateFlow("")
    val editorNotice: StateFlow<String> = _editorNotice.asStateFlow()

    fun consumeEditorNotice() {
        _editorNotice.value = ""
    }

    /** 编辑页本地图片列表(filesDir/avatars/ 下该角色的版本化文件,新→旧)。 */
    private val _editorGallery = MutableStateFlow<List<java.io.File>>(emptyList())
    val editorGallery: StateFlow<List<java.io.File>> = _editorGallery.asStateFlow()

    /**
     * 打开编辑页:[characterId] = null 新建(恢复草稿),>0 编辑既有角色。
     * 关闭页面不丢草稿(自动保存),保存成功才清草稿槽。
     */
    fun openCharacterEditor(characterId: Long?) {
        if (_editorOpen.value) return
        _editorNotice.value = ""
        _editorGallery.value = emptyList()
        viewModelScope.launch {
            val existing = characterId?.takeIf { it > 0 }?.let { repo.characterById(it) }
            _editorUi.value = if (existing != null) {
                CharacterEditorUi(
                    editingCharacterId = existing.id,
                    originalCharacter = existing,
                    name = existing.name,
                    profile = existing.profile,
                    intro = existing.intro,
                    exampleLines = existing.exampleLines,
                    openingLine = existing.openingLine,
                    appearance = existing.appearance,
                    appearanceStyle = existing.appearanceStyle,
                    originalAppearance = existing.appearance,
                    originalAppearanceStyle = existing.appearanceStyle,
                )
            } else {
                val draft = draftPrefs.load()
                if (draft != null) {
                    CharacterEditorUi(
                        editingCharacterId = draft.id,
                        name = draft.name,
                        profile = draft.profile,
                        intro = draft.intro,
                        exampleLines = draft.exampleLines,
                        openingLine = draft.openingLine,
                        appearance = draft.appearance,
                        appearanceStyle = draft.appearanceStyle,
                        draftRestored = true,
                    )
                } else {
                    CharacterEditorUi()
                }
            }
            refreshEditorGallery(characterId)
            // 编辑模式:确保当前头像立即可见(已落库文件直接复用;缺图且已填
            // 形象描述时走同一生成链,失败可在形象区看到并可重试)
            existing?.let { ensureAvatars(listOf(it)) }
            _editorOpen.value = true
        }
    }

    /** 角色主页「编辑」入口:按会话解析角色行后进入编辑页(先收起主页)。 */
    fun editCharacterForConversation(conversationId: Long) {
        viewModelScope.launch {
            val conv = repo.db.conversationDao().byId(conversationId) ?: return@launch
            closeCharacterHome()
            openCharacterEditor(conv.characterId)
        }
    }

    /** 角色主页「聊天」入口(C2):收起主页,直接进入该角色的 IM 会话续聊。 */
    fun openChatFromCharacterHome(conversationId: Long) {
        closeCharacterHome()
        openChat(conversationId)
    }

    /** 关闭编辑页(新建草稿已自动保存,重进可恢复)。 */
    fun closeCharacterEditor() {
        _editorOpen.value = false
        _editorUi.value = CharacterEditorUi()
        _editorGallery.value = emptyList()
        _editorNotice.value = ""
    }

    /** 表单字段更新(逐项;新建路径写通草稿槽,该字段既有错误即时清除)。 */
    fun updateCharacterField(field: CharacterField, value: String) {
        val ui = _editorUi.value
        if (ui.saving) return
        val next = when (field) {
            CharacterField.NAME -> ui.copy(name = value, errors = ui.errors.copy(name = null))
            CharacterField.PROFILE -> ui.copy(profile = value, errors = ui.errors.copy(profile = null))
            CharacterField.INTRO -> ui.copy(intro = value, errors = ui.errors.copy(intro = null))
            CharacterField.LINES -> ui.copy(exampleLines = value, errors = ui.errors.copy(exampleLines = null))
            CharacterField.OPENING -> ui.copy(openingLine = value, errors = ui.errors.copy(openingLine = null))
            CharacterField.APPEARANCE -> ui.copy(appearance = value, errors = ui.errors.copy(appearance = null))
            CharacterField.STYLE -> ui.copy(appearanceStyle = value, errors = ui.errors.copy(appearanceStyle = null))
        }
        _editorUi.value = next
        if (!next.isEditing) draftPrefs.save(next.toDraft())
    }

    /** 切预览/编辑视图。 */
    fun toggleEditorPreview(show: Boolean) {
        _editorUi.value = _editorUi.value.copy(showPreview = show)
    }

    /**
     * 保存表单(编辑页按钮;ai_cmd 走 [saveCharacter] 同一数据路径):
     * 校验 → 原子落库 → 成功清草稿/关页/直接进入该角色的 IM 续聊。
     */
    fun saveCharacterFromEditor(openChatAfterSave: Boolean = true) {
        val ui = _editorUi.value
        if (ui.saving || !_editorOpen.value) return
        _editorUi.value = ui.copy(saving = true, errorMessage = null)
        viewModelScope.launch {
            val draft = ui.toDraft()
            when (val outcome = saveCharacter(draft, openChatAfter = openChatAfterSave)) {
                is CharacterSaveOutcome.Invalid -> _editorUi.value =
                    _editorUi.value.copy(saving = false, errors = outcome.errors)
                is CharacterSaveOutcome.Conflict -> _editorUi.value =
                    _editorUi.value.copy(saving = false, errorMessage = CharacterFormCopy.SAVE_CONFLICT)
                is CharacterSaveOutcome.Failure -> _editorUi.value =
                    _editorUi.value.copy(saving = false, errorMessage = outcome.reason)
                is CharacterSaveOutcome.Ok -> {
                    if (outcome.created) draftPrefs.clear()
                    _editorOpen.value = false
                    _editorUi.value = CharacterEditorUi()
                    _editorGallery.value = emptyList()
                    if (openChatAfterSave) openChat(outcome.conversationId)
                }
            }
        }
    }

    /**
     * 保存数据路径(UI 按钮与 ai_cmd 共用):schema 校验(必填/长度/重名)→
     * 单事务原子落库([com.neethu.gouxiang.data.SeedDao.saveCharacterAtomic])→
     * 形象改写时失效旧头像并按新版本键排队重生成(网络在事务外)。
     * 不做任何网络/LLM 调用即完成保存 —— 无模型/断网也能存下手工卡。
     */
    suspend fun saveCharacter(
        draft: CharacterDraft,
        openChatAfter: Boolean = false,
    ): CharacterSaveOutcome {
        val existing = if (draft.id > 0) repo.characterById(draft.id) else null
        if (draft.id > 0 && existing == null) {
            return CharacterSaveOutcome.Failure("角色不存在或已被删除")
        }
        // 目录来源可能存在同名人物；保留当前名称的编辑不应被其他同名行阻止。
        val nameOwnerId = if (existing?.name == draft.name) null else repo.characterByName(draft.name)?.id
        val errors = CharacterFormValidator.validate(draft, nameOwnerId = nameOwnerId)
        if (errors.hasErrors) return CharacterSaveOutcome.Invalid(errors)
        val appearanceChanged = existing != null &&
            (existing.appearance != draft.appearance || existing.appearanceStyle != draft.appearanceStyle)
        val (openingContent, openingActions) = splitOpeningLine(draft.openingLine)
        return try {
            val saved = repo.db.seedDao().saveCharacterAtomic(
                character = draft.toEntity(existing?.createdAt ?: System.currentTimeMillis()),
                openingContent = openingContent.ifBlank { draft.openingLine.trim() },
                openingActions = openingActions,
                openingAt = System.currentTimeMillis(),
                appearanceChanged = appearanceChanged,
            )
            if (appearanceChanged) {
                // 失效旧头像:清内存路径与尝试记录,按新版本键重走生成链
                _avatarFiles.value = _avatarFiles.value - saved.characterId
                _avatarStates.value = _avatarStates.value + (saved.characterId to AvatarGenState.Idle)
            }
            repo.characterById(saved.characterId)?.let { ensureAvatars(listOf(it)) }
            CharacterSaveOutcome.Ok(saved.characterId, saved.conversationId, saved.created)
        } catch (e: com.neethu.gouxiang.data.CharacterNameConflictException) {
            CharacterSaveOutcome.Conflict
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("CharacterStudio", "save character failed", e)
            CharacterSaveOutcome.Failure(CharacterFormCopy.SAVE_FAILED)
        }
    }

    /** 刷新编辑页本地图片列表(打开页/图库操作/头像落盘后调用)。 */
    fun refreshEditorGallery(characterId: Long?) {
        val id = characterId ?: _editorUi.value.editingCharacterId
        if (id <= 0) {
            _editorGallery.value = emptyList()
            return
        }
        val dir = java.io.File(getApplication<Application>().filesDir, AVATAR_DIR)
        _editorGallery.value = dir.listFiles { f ->
            // 版本化文件(character-<id>-v<N>.jpg)与既有基础命名(character-<id>.jpg)
            f.isFile && f.name.endsWith(".jpg") &&
                (f.name == "character-$id.jpg" || f.name.startsWith("character-$id-"))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** 图库「设为当前」:写 avatarFile 列 + 立即更新内存路径(上屏即生效)。 */
    fun setCharacterAvatar(characterId: Long, fileName: String) {
        viewModelScope.launch {
            repo.db.characterDao().setAvatarFile(characterId, fileName)
            val file = java.io.File(java.io.File(getApplication<Application>().filesDir, AVATAR_DIR), fileName)
            if (file.isFile) {
                _avatarFiles.value = _avatarFiles.value + (characterId to file.absolutePath)
                _avatarStates.value = _avatarStates.value + (characterId to AvatarGenState.Ready)
            }
            _editorNotice.value = CharacterFormCopy.GALLERY_SET_DONE
        }
    }

    /** 图库删除:落盘删除;删除的是当前形象则清引用回落占位。 */
    fun deleteCharacterAvatar(characterId: Long, fileName: String) {
        viewModelScope.launch {
            val file = java.io.File(java.io.File(getApplication<Application>().filesDir, AVATAR_DIR), fileName)
            val deleted = runCatching { file.delete() }.getOrDefault(false)
            if (!deleted) {
                _editorNotice.value = "图片删除失败,请重试"
                return@launch
            }
            if (repo.characterById(characterId)?.avatarFile == fileName) {
                repo.db.characterDao().setAvatarFile(characterId, "")
                _avatarFiles.value = _avatarFiles.value - characterId
                _avatarStates.value = _avatarStates.value + (characterId to AvatarGenState.Idle)
            }
            refreshEditorGallery(characterId)
            _editorNotice.value = CharacterFormCopy.GALLERY_DELETE_DONE
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
            launch {
                // G4 角色开关回显(人物行改动即时反映到页内 Switch)。
                repo.observeCharacter(character.id).collect { c ->
                    _innerVoiceUi.value = _innerVoiceUi.value.copy(
                        characterId = character.id,
                        characterAutoEnabled = c?.innerVoiceAuto ?: true,
                    )
                }
            }
            com.neethu.gouxiang.work.reconcileDerivativeTasks(getApplication())
            launch {
                repo.db.derivativeResultDao().observeImageTasks(character.id, "INNER_VOICE").collect {
                    repo.db.derivativeResultDao().recoverInterruptedImages()
                }
            }
            launch {
                repo.db.derivativeTaskDao().observeLatest(character.id, com.neethu.gouxiang.chat.DerivativeKind.INNER_VOICE.name).collect { task ->
                    _innerVoiceUi.value = _innerVoiceUi.value.copy(automaticError = if (task?.source == "AUTOMATIC" && task.state == "failed")
                        "自动生成失败：${task.failureReason ?: "请检查网络与模型配置"}。可在任务历史中查看，或在聊天提醒中重试对应内容。" else null)
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

    // ── 心声详情(F8 升级:拍立得/信纸页的朗读与动作)────────────────

    /** 详情页朗读中(TTS 播放可见态;与聊天朗读共用同一播放器,天然互斥)。 */
    val innerVoiceSpeaking: StateFlow<Boolean> get() = chatSpeechPlayer.speaking

    /** 朗读心声全文(翻到信纸自动触发/喇叭重播;剥括号旁白后按角色音色合成)。 */
    fun speakInnerVoice(text: String) {
        val conversationId = _innerVoiceConversationId.value ?: return
        val clean = com.neethu.gouxiang.ai.speech.CallSpeech.stripActions(text)
        if (clean.isBlank()) return
        chatSpeechPlayer.speakText(clean, onUnavailable = {
            android.widget.Toast.makeText(
                getApplication(), "语音播放失败，请检查语音服务和系统文字转语音设置", android.widget.Toast.LENGTH_LONG,
            ).show()
        }) { speechVoiceForConversation(conversationId) }
    }

    /** 停止朗读(翻回图片/离开详情/关闭心声页)。 */
    fun stopInnerVoiceSpeech() {
        chatSpeechPlayer.stop()
    }

    /** 心声配图设为该会话聊天背景(与合照同通道;无图/会话缺失为空操作)。 */
    fun setInnerVoiceAsChatBackground(voice: com.neethu.gouxiang.data.InnerVoiceEntity) {
        val conversationId = _innerVoiceConversationId.value ?: return
        if (voice.imageFile.isBlank()) return
        ChatBackgroundPrefs.setBackground(getApplication(), conversationId, voice.imageFile)
    }

    /** 重新生成该条心声(删旧条→入队新一批;页内 generating 态复用)。 */
    fun regenerateInnerVoice(voice: com.neethu.gouxiang.data.InnerVoiceEntity) {
        if (_innerVoiceUi.value.generating) return
        deleteInnerVoice(voice.id)
        generateInnerVoices()
    }

    /** 配图下载到系统相册 Pictures/GouXiang(minSdk 29 免权限);结果经回调供 UI toast。 */
    fun downloadInnerVoiceImage(voice: com.neethu.gouxiang.data.InnerVoiceEntity, onDone: (Boolean) -> Unit) {
        if (voice.imageFile.isBlank()) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                saveImageToGallery(java.io.File(voice.imageFile))
            }
            onDone(ok)
        }
    }

    /** MediaStore 插入 Pictures/GouXiang 并拷贝字节;任何失败返回 false。 */
    private fun saveImageToGallery(source: java.io.File): Boolean = try {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "gouxiang-inner-voice-${System.currentTimeMillis()}.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GouXiang")
        }
        val resolver = getApplication<Application>().contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } != null
    } catch (_: Exception) {
        false
    }

    // ── 朋友圈页 06(H1 多角色时间线;入口=角色主页「Ta 的朋友圈」/发现页)──

    /** 朋友圈页打开的会话 id(null=未进入;[MOMENTS_GLOBAL_ID]=全局朋友圈)。 */
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

    /** 互评引擎(与 Worker 侧共用同一认领账本;发布动态后由 VM 触发一轮)。 */
    private val momentInteractionEngine by lazy {
        com.neethu.gouxiang.chat.MomentInteractionEngine(repo.db, momentsChannel)
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
     * 打开朋友圈页:订阅多角色合并时间线(用户帖 + 参与角色的动态,查询层
     * 权限过滤)+ 全量评论流(生成/评论落库即上屏)。带会话进入时保留该角色
     * 上下文(「生成动态」按钮);全局入口(characterId=0)只读+发帖。
     * 空时间线仅在全局日更与角色级日更/参与同时开启时入队自动任务(角色页)。
     */
    fun openMoments(conversationId: Long) {
        if (_momentsConversationId.value == conversationId) return
        _momentsConversationId.value = conversationId
        _momentsUi.value = com.neethu.gouxiang.ui.moments.MomentsUiState(loading = true)
        momentsJob?.cancel()
        momentsJob = viewModelScope.launch {
            val isGlobal = conversationId == MOMENTS_GLOBAL_ID
            var currentCharacter: com.neethu.gouxiang.data.CharacterEntity? = null
            if (isGlobal) {
                com.neethu.gouxiang.work.reconcileDerivativeTasks(getApplication())
            } else {
                val conv = repo.db.conversationDao().byId(conversationId)
                val character = conv?.let { repo.characterById(it.characterId) }
                if (conv == null || character == null) {
                    _momentsUi.value = _momentsUi.value.copy(loading = false, loadFailed = true)
                    return@launch
                }
                currentCharacter = character
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
                            "自动生成失败：${task.failureReason ?: "请检查网络与模型配置"}。可在任务历史中查看，或在聊天提醒中重试对应内容。" else null)
                    }
                }
                launch {
                    observeManualDerivative(com.neethu.gouxiang.chat.DerivativeKind.MOMENT, conversationId) { generating, error ->
                        _momentsUi.value = _momentsUi.value.copy(generating = generating, error = error)
                    }
                }
                ensureAvatars(listOf(character))
            }
            launch {
                // 配图状态不分角色(合并时间线跨角色;全局入口同样要看到补图入口)
                repo.db.derivativeResultDao().observeForKind("MOMENT").collect { results ->
                    _momentsUi.value = _momentsUi.value.copy(imageResults = imageStates(results))
                }
            }
            launch {
                // 评论者/作者展示名(角色改名即时刷新;user 恒「我」)
                repo.db.characterDao().observeAll().collect { characters ->
                    _momentsUi.value = _momentsUi.value.copy(
                        characterNames = characters.associate { it.id to it.name },
                    )
                }
            }
            // 进入列表只展示已有内容；标题发现由对话协调器负责。
            repo.momentsFeed.collect { feed ->
                _momentsUi.value = _momentsUi.value.copy(items = feed, loading = false)
                feed.forEach { item ->
                    if (item.post.id !in momentCommentJobs) {
                        momentCommentJobs[item.post.id] = launch {
                            repo.observeMomentComments(item.post.id).collect { comments ->
                                _momentsUi.value = _momentsUi.value.copy(
                                    comments = _momentsUi.value.comments + (item.post.id to comments),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** 全局朋友圈入口(H4 发现页;无当前角色,只浏览/发帖/评论)。 */
    fun openMomentsGlobal() = openMoments(MOMENTS_GLOBAL_ID)

    fun closeMoments() {
        momentsJob?.cancel()
        momentsJob = null
        momentCommentJobs.values.forEach { it.cancel() }
        momentCommentJobs.clear()
        _momentsConversationId.value = null
        _momentsUi.value = com.neethu.gouxiang.ui.moments.MomentsUiState()
    }

    /**
     * 「生成动态」为手动请求，不受自动开关限制（角色级日更/参与闸只门控自动
     * 路径）。落库后由合并时间线上屏，随后触发一轮角色互评（Worker 内）。
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

    /** 用户动态正文字数上限(H3 发布校验;超限拒绝发布,UI 提示;常量在 companion)。 */

    /**
     * 用户发布动态(H1/H3):落库 → 触发一轮角色互评(授权角色各评一条;
     * 权限/去重在引擎的候选层执行)。失败不吞:上屏错误提示,帖子已落的
     * 保持可见(文字已真实落库,不假成功)。
     */
    fun publishUserMoment(text: String) {
        val body = text.trim()
        if (body.isEmpty() || body.length > USER_POST_MAX_CHARS) {
            _momentsUi.value = _momentsUi.value.copy(
                error = if (body.isEmpty()) "想说什么写下来再发布吧" else "最多 $USER_POST_MAX_CHARS 字",
            )
            return
        }
        if (_momentsUi.value.publishing) return
        _momentsUi.value = _momentsUi.value.copy(publishing = true, error = null)
        viewModelScope.launch {
            try {
                val postId = repo.insertMoment(
                    com.neethu.gouxiang.data.MomentEntity(
                        characterId = 0,
                        authorKind = com.neethu.gouxiang.data.MomentAuthorKind.USER,
                        text = body,
                        at = System.currentTimeMillis(),
                    ),
                )
                // 发布即一轮互评(用户帖对满足「参与+互动+可看用户动态」的角色
                // 开放;评论不触发新轮次 → 结构上不循环)
                repo.db.momentDao().byId(postId)?.let { post ->
                    runCatching { momentInteractionEngine.runRound(post) }
                        .onFailure { android.util.Log.w("Moments", "interaction round failed", it) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _momentsUi.value = _momentsUi.value.copy(error = "发布失败，请重试")
            } finally {
                _momentsUi.value = _momentsUi.value.copy(publishing = false)
            }
        }
    }

    /** 删除用户自己的动态(H3;角色动态不开放删除,返回后由流上屏)。 */
    fun deleteUserMoment(postId: Long) {
        viewModelScope.launch {
            runCatching { repo.deleteUserMoment(postId) }
                .onFailure { android.util.Log.w("Moments", "delete post failed", it) }
            _momentsUi.value = _momentsUi.value.copy(
                comments = _momentsUi.value.comments - postId,
            )
        }
    }

    /** 删除用户自己的评论(H3;角色评论不开放删除;评论区走流自动刷新)。 */
    fun deleteUserComment(commentId: Long) {
        viewModelScope.launch {
            runCatching { repo.deleteUserMomentComment(commentId) }
                .onFailure { android.util.Log.w("Moments", "delete comment failed", it) }
        }
    }

    // ── 朋友圈权限管理面板(H2)──────────────────────────────────────

    /** 面板行刷新(打开面板时拉一次;切换开关后即时回写快照)。 */
    fun refreshMomentPermissions() {
        viewModelScope.launch {
            _momentsUi.value = _momentsUi.value.copy(permissionsLoading = true)
            val rows = repo.db.characterDao().observeAll().firstOrNull().orEmpty()
            _momentsUi.value = _momentsUi.value.copy(
                permissions = rows.map { com.neethu.gouxiang.ui.moments.MomentPermissionRow.of(it) },
                permissionsLoading = false,
            )
        }
    }

    /**
     * 切换某角色的一项朋友圈权限(H2):写回 `characters` 对应列;生成层
     * (Scheduler 入队/Worker 执行)与查询层(合并时间线/互评候选)各自生效。
     * 手动「生成动态」不受限(与心声开关同语义,只门控自动与互动)。
     */
    fun setMomentPermission(characterId: Long, field: MomentPermissionField, enabled: Boolean) {
        viewModelScope.launch {
            val character = repo.characterById(characterId) ?: return@launch
            val updated = when (field) {
                MomentPermissionField.JOINED -> character.copy(momentsJoined = enabled)
                MomentPermissionField.SEE_USER_POSTS -> character.copy(momentsSeeUserPosts = enabled)
                MomentPermissionField.INTERACT -> character.copy(momentsInteract = enabled)
                MomentPermissionField.DAILY_AUTO -> character.copy(momentsDailyAuto = enabled)
            }
            repo.updateCharacter(updated)
            _momentsUi.value = _momentsUi.value.copy(
                permissions = _momentsUi.value.permissions.map {
                    if (it.characterId == characterId) com.neethu.gouxiang.ui.moments.MomentPermissionRow.of(updated) else it
                },
            )
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
     * 用户评论(F9 互动 4 → H 工作包权限化):用户评论先落库 → 动态作者角色
     * 回复(H2 权限在生成层执行:作者不参与朋友圈/关评论互动 → 不回复,
     * 也不插占位行;用户帖没有角色作者,不触发回复)。回复失败仍插兜底
     * 占位行(与聊天页「永远有回复」的既有约定同源;权限关闭 ≠ 失败)。
     */
    fun sendMomentComment(post: com.neethu.gouxiang.data.MomentEntity, text: String) {
        val body = text.trim()
        if (body.isEmpty() || _momentsUi.value.replyingToId != null) return
        _momentsUi.value = _momentsUi.value.copy(replyingToId = post.id)
        viewModelScope.launch {
            try {
                val userCommentId = repo.insertMomentComment(
                    com.neethu.gouxiang.data.MomentCommentEntity(
                        postId = post.id,
                        authorKind = "user",
                        authorId = 0,
                        text = body,
                        at = System.currentTimeMillis(),
                    ),
                )
                // 作者回复闸(H2):只对参与且允许互动的角色帖作者生成回复。
                val author = post.characterId.takeIf { post.authorKind != "user" }
                    ?.let { repo.characterById(it) }
                if (author == null || !author.momentsJoined || !author.momentsInteract) return@launch
                // 角色回复:LLM 生成;失败插兜底占位行(楼中楼挂用户评论)
                val memories = repo.memoriesFor(author.id)
                val thread = repo.momentComments(post.id)
                val system = com.neethu.gouxiang.chat.MomentsGenerator.replySystemPrompt(author, memories)
                val user = com.neethu.gouxiang.chat.MomentsGenerator.replyUserPrompt(
                    author, post, thread.dropLast(1), thread.last(),
                )
                val raw = momentsChannel.requestReply(system, user)
                val reply = raw?.let { com.neethu.gouxiang.chat.MomentsGenerator.parseReply(it) }
                repo.insertMomentComment(
                    com.neethu.gouxiang.data.MomentCommentEntity(
                        postId = post.id,
                        authorKind = "avatar",
                        authorId = author.id,
                        replyToId = userCommentId,
                        text = reply ?: "……(刚刚没想好说什么)",
                        at = System.currentTimeMillis(),
                    ),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("Moments", "comment round failed", e)
                // 数据层失败也要给用户评论一个角色侧落点(防评论区悬空);
                // 作者未知时不落(权限语义:不能替未授权的角色说话)
                val author = post.characterId.takeIf { post.authorKind != "user" }
                    ?.let { repo.characterById(it) }
                if (author != null && author.momentsJoined && author.momentsInteract) {
                    runCatching {
                        repo.insertMomentComment(
                            com.neethu.gouxiang.data.MomentCommentEntity(
                                postId = post.id,
                                authorKind = "avatar",
                                authorId = author.id,
                                text = "……(刚刚没想好说什么)",
                                at = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            } finally {
                _momentsUi.value = _momentsUi.value.copy(replyingToId = null)
            }
        }
    }

    // ── 番外剧场与心动事件(F 工作包 F1-F4)──────────────────────────

    /** 番外页打开的会话 id(null=未进入)。 */
    private val _sidestoryConversationId = MutableStateFlow<Long?>(null)
    val sidestoryConversationId: StateFlow<Long?> = _sidestoryConversationId.asStateFlow()

    /** 番外页 UI 状态。 */
    private val _sidestoryUi = MutableStateFlow(com.neethu.gouxiang.ui.sidestory.SidestoryUiState())
    val sidestoryUi: StateFlow<com.neethu.gouxiang.ui.sidestory.SidestoryUiState> =
        _sidestoryUi.asStateFlow()

    private var sidestoryJob: Job? = null

    /** 打开番外页:按会话取角色 + 订阅记录流;进页先对账进程中断遗留。 */
    fun openSidestory(conversationId: Long) {
        if (_sidestoryConversationId.value == conversationId) return
        _sidestoryConversationId.value = conversationId
        _sidestoryUi.value = com.neethu.gouxiang.ui.sidestory.SidestoryUiState(loading = true)
        sidestoryJob?.cancel()
        sidestoryJob = viewModelScope.launch {
            val conv = repo.db.conversationDao().byId(conversationId)
            val character = conv?.let { repo.characterById(it.characterId) }
            if (conv == null || character == null) {
                _sidestoryUi.value = _sidestoryUi.value.copy(loading = false, loadFailed = true)
                return@launch
            }
            _sidestoryUi.value = _sidestoryUi.value.copy(characterName = character.name)
            // 进页对账:记录行 generating 而任务已终态(进程中断)→ 拨失败可重试。
            runCatching {
                com.neethu.gouxiang.sidestory.SidestoryGenerator.production(getApplication())
                    .reconcileInterrupted()
            }
            repo.observeSidestoryRecords(conversationId).collect { records ->
                _sidestoryUi.value = _sidestoryUi.value.copy(
                    rows = records.map { com.neethu.gouxiang.ui.sidestory.SidestoryRecordRow.of(it) },
                    loading = false,
                )
            }
        }
    }

    fun closeSidestory() {
        sidestoryJob?.cancel()
        sidestoryJob = null
        _sidestoryConversationId.value = null
        _sidestoryUi.value = com.neethu.gouxiang.ui.sidestory.SidestoryUiState()
    }

    /**
     * 生成一条番外/心动记录(F3/F4 入口按钮与 ai_cmd 共用数据路径):同模板
     * 已有生成中的记录即在事务内拒绝(原子去重,防快速双击重复生成);短事务
     * 捕获来源范围与会话版本后落 generating 行,再入队 Worker(网络在事务外;
     * 任务与记录以同 requestId 关联)。
     *
     * @return 新记录 id;null = 同模板已有生成中记录(幂等跳过)。
     */
    suspend fun generateSidestory(templateKey: String): Long? {
        val conversationId = _sidestoryConversationId.value
            ?: throw IllegalStateException("sidestory screen is not open for a conversation")
        val entry = com.neethu.gouxiang.sidestory.SidestoryCatalog.byKey(templateKey)
            ?: throw IllegalArgumentException("No sidestory template or heartbeat event matches '$templateKey'")
        val conv = repo.db.conversationDao().byId(conversationId)
            ?: throw IllegalStateException("conversation $conversationId not found")
        val requestId = java.util.UUID.randomUUID().toString()
        val recordId = repo.db.withTransaction {
            // 原子去重:同模板已有生成中记录则拒绝(双击/命令重发幂等)。
            if (repo.db.sidestoryRecordDao().forConversation(conversationId)
                    .any { it.templateKey == entry.key && it.isGenerating }
            ) {
                return@withTransaction -1L
            }
            val source = com.neethu.gouxiang.work.captureDerivativeSource(
                repo.db, conversationId, conv.characterId,
            )
            repo.db.sidestoryRecordDao().insert(
                com.neethu.gouxiang.data.SidestoryRecordEntity(
                    kind = if (entry.isHeartbeat) com.neethu.gouxiang.data.SidestoryRecordEntity.KIND_HEARTBEAT
                    else com.neethu.gouxiang.data.SidestoryRecordEntity.KIND_SIDESTORY,
                    templateKey = entry.key,
                    templateVersion = com.neethu.gouxiang.sidestory.SidestoryCatalog.VERSION,
                    characterId = conv.characterId,
                    conversationId = conversationId,
                    sourceStartMessageId = source.firstMessageId,
                    sourceEndMessageId = source.lastMessageId,
                    sourceVersion = source.version,
                    requestId = requestId,
                    state = com.neethu.gouxiang.data.SidestoryRecordEntity.STATE_GENERATING,
                    imageStatus = com.neethu.gouxiang.data.SidestoryRecordEntity.IMAGE_PENDING,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        if (recordId == -1L) return null
        com.neethu.gouxiang.work.DerivativeWorker.enqueue(
            getApplication(),
            com.neethu.gouxiang.chat.DerivativeTask(
                com.neethu.gouxiang.chat.DerivativeKind.SIDESTORY,
                conversationId,
                characterId = conv.characterId,
                recordId = recordId,
                requiresNetwork = false,
            ),
            requestIdOverride = requestId,
        )
        return recordId
    }

    /** UI 入口(番外页按钮;失败置一次性错误提示)。 */
    fun generateSidestoryTemplate(templateKey: String) {
        if (_sidestoryConversationId.value == null) return
        viewModelScope.launch {
            try {
                val recordId = generateSidestory(templateKey)
                if (recordId == null) {
                    _sidestoryUi.value = _sidestoryUi.value.copy(error = "该模板正在生成中,请等它完成")
                } else {
                    _sidestoryUi.value = _sidestoryUi.value.copy(error = null)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.e("Sidestory", "enqueue failed", e)
                _sidestoryUi.value = _sidestoryUi.value.copy(error = "任务入队失败，请重试")
            }
        }
    }

    /** 失败重试(同一记录、同一 requestId;拨回 generating 后重新入队)。 */
    fun retrySidestory(recordId: Long) {
        val conversationId = _sidestoryConversationId.value ?: return
        viewModelScope.launch {
            try {
                val record = repo.sidestoryRecordById(recordId) ?: return@launch
                if (!record.isFailed) return@launch
                val ok = repo.db.sidestoryRecordDao().markGeneratingForRetry(recordId, System.currentTimeMillis()) > 0
                if (!ok) return@launch
                com.neethu.gouxiang.work.DerivativeWorker.enqueue(
                    getApplication(),
                    com.neethu.gouxiang.chat.DerivativeTask(
                        com.neethu.gouxiang.chat.DerivativeKind.SIDESTORY,
                        conversationId,
                        characterId = record.characterId,
                        recordId = recordId,
                        requiresNetwork = false,
                    ),
                    requestIdOverride = record.requestId,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.e("Sidestory", "retry enqueue failed", e)
                _sidestoryUi.value = _sidestoryUi.value.copy(error = "重试入队失败，请稍后再试")
            }
        }
    }

    /** 删除一条记录(列表与库一致;配图文件一并清理)。 */
    fun deleteSidestoryRecord(recordId: Long) {
        viewModelScope.launch {
            try {
                val record = repo.sidestoryRecordById(recordId) ?: return@launch
                repo.deleteSidestoryRecord(recordId)
                if (record.imageFile.isNotBlank()) {
                    runCatching { java.io.File(record.imageFile).delete() }
                }
                if (_sidestoryUi.value.detailId == recordId) closeSidestoryDetail()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.e("Sidestory", "delete failed", e)
                _sidestoryUi.value = _sidestoryUi.value.copy(error = "删除失败，请重试")
            }
        }
    }

    /** 详情开合(记录卡「查看」)。 */
    fun openSidestoryDetail(recordId: Long) {
        _sidestoryUi.value = _sidestoryUi.value.copy(detailId = recordId)
    }

    fun closeSidestoryDetail() {
        _sidestoryUi.value = _sidestoryUi.value.copy(detailId = null)
    }

    /** 通知直跳入口(番外/心动完成通知)。 */
    fun openSidestoryFromNotification(conversationId: Long) {
        openSidestory(conversationId)
    }

    // ── 手帐/小说/合照(G 工作包 G1-G3;与番外页同构的记录-任务模型)──

    /**
     * 三个衍生页的页内状态(各自独立的打开态/UI 态/订阅任务;记录同表
     * `chat_derivative_records`,按 kind 分区)。
     */
    private class DerivativePage(val kind: String) {
        val opened = MutableStateFlow<Long?>(null)
        val ui = MutableStateFlow(com.neethu.gouxiang.ui.derivatives.ChatDerivativeUiState())
        var job: Job? = null
    }

    private val handbookPage = DerivativePage(com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_HANDBOOK)
    private val novelPage = DerivativePage(com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_NOVEL)
    private val photoPage = DerivativePage(com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_PHOTO)

    val handbookConversationId: StateFlow<Long?> get() = handbookPage.opened.asStateFlow()
    val handbookUi: StateFlow<com.neethu.gouxiang.ui.derivatives.ChatDerivativeUiState> get() = handbookPage.ui.asStateFlow()
    val novelConversationId: StateFlow<Long?> get() = novelPage.opened.asStateFlow()
    val novelUi: StateFlow<com.neethu.gouxiang.ui.derivatives.ChatDerivativeUiState> get() = novelPage.ui.asStateFlow()
    val photoConversationId: StateFlow<Long?> get() = photoPage.opened.asStateFlow()
    val photoUi: StateFlow<com.neethu.gouxiang.ui.derivatives.ChatDerivativeUiState> get() = photoPage.ui.asStateFlow()

    /** 合照页的拍照描述(输入框状态;生成时随记录快照入库)。 */
    private val _photoDescription = MutableStateFlow("")
    val photoDescription: StateFlow<String> = _photoDescription.asStateFlow()

    fun onPhotoDescriptionChange(value: String) {
        _photoDescription.value = value.take(DerivativeOptions.PHOTO_DESCRIPTION_MAX_CHARS)
    }

    /** 聊天背景映射(合照页设置后聊天页即时生效;ChatBackgroundPrefs 进程内同步)。 */
    val chatBackgrounds: StateFlow<Map<Long, String>> get() = ChatBackgroundPrefs.state

    fun openHandbook(conversationId: Long) = openDerivativePage(handbookPage, conversationId)
    fun closeHandbook() = closeDerivativePage(handbookPage)

    fun openNovel(conversationId: Long) = openDerivativePage(novelPage, conversationId)
    fun closeNovel() = closeDerivativePage(novelPage)

    fun openPhoto(conversationId: Long) = openDerivativePage(photoPage, conversationId)
    fun closePhoto() = closeDerivativePage(photoPage)

    /** 打开一页衍生记录:取角色 + 订阅记录流(按 kind 过滤);进页先对账中断遗留。 */
    private fun openDerivativePage(page: DerivativePage, conversationId: Long) {
        if (page.opened.value == conversationId) return
        page.opened.value = conversationId
        page.ui.value = com.neethu.gouxiang.ui.derivatives.ChatDerivativeUiState(loading = true)
        page.job?.cancel()
        page.job = viewModelScope.launch {
            val conv = repo.db.conversationDao().byId(conversationId)
            val character = conv?.let { repo.characterById(it.characterId) }
            if (conv == null || character == null) {
                page.ui.value = page.ui.value.copy(loading = false, loadFailed = true)
                return@launch
            }
            page.ui.value = page.ui.value.copy(characterName = character.name)
            // 进页对账:记录行 generating 而任务已终态(进程中断)→ 拨失败可重试。
            runCatching {
                com.neethu.gouxiang.derivatives.ChatDerivativeGenerator.production(getApplication())
                    .reconcileInterrupted()
            }
            repo.observeChatDerivatives(conversationId).collect { records ->
                page.ui.value = page.ui.value.copy(
                    rows = records.filter { it.kind == page.kind },
                    loading = false,
                )
            }
        }
    }

    private fun closeDerivativePage(page: DerivativePage) {
        page.job?.cancel()
        page.job = null
        page.opened.value = null
        page.ui.value = com.neethu.gouxiang.ui.derivatives.ChatDerivativeUiState()
        if (page === photoPage) _photoDescription.value = ""
    }

    /**
     * 生成一条手帐/小说/合照记录(入口按钮与 ai_cmd 共用数据路径):同 kind
     * 已有生成中的记录即在事务内拒绝(原子去重,防快速双击重复生成);短事务
     * 捕获来源范围与会话版本后落 generating 行,再入队 Worker(网络在事务外;
     * 任务与记录以同 requestId 关联)。
     *
     * @return 新记录 id;null = 同 kind 已有生成中记录(幂等跳过)。
     */
    private suspend fun generateChatDerivative(page: DerivativePage, option: DerivativeOptions): Long? {
        val conversationId = page.opened.value
            ?: throw IllegalStateException("derivative screen is not open for a conversation")
        val conv = repo.db.conversationDao().byId(conversationId)
            ?: throw IllegalStateException("conversation $conversationId not found")
        val requestId = java.util.UUID.randomUUID().toString()
        val stamp = System.currentTimeMillis()
        val recordId = repo.db.withTransaction {
            // 原子去重:同 kind 已有生成中记录则拒绝(双击/命令重发幂等)。
            if (repo.db.chatDerivativeDao().forConversation(conversationId)
                    .any { it.kind == page.kind && it.isGenerating }
            ) {
                return@withTransaction -1L
            }
            when (page.kind) {
                // G1 手帐:按预设回合算选中区间(与版本快照同一事务读,来源一致)。
                com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_HANDBOOK -> {
                    val range = DerivativeRange.resolve(repo.db.messageDao().allForConversation(conversationId), (option as DerivativeOptions.HandbookOption).preset)
                        ?: return@withTransaction -2L
                    repo.db.chatDerivativeDao().insert(
                        com.neethu.gouxiang.data.ChatDerivativeEntity(
                            kind = page.kind,
                            characterId = conv.characterId,
                            conversationId = conversationId,
                            optionJson = option.toJson(),
                            sourceStartMessageId = range.startMessageId,
                            sourceEndMessageId = range.endMessageId,
                            sourceVersion = repo.db.memoryWindowDao().version(conversationId) ?: 0L,
                            requestId = requestId,
                            imageStatus = com.neethu.gouxiang.data.ChatDerivativeEntity.IMAGE_PENDING,
                            createdAt = stamp,
                            updatedAt = stamp,
                        ),
                    )
                }
                // G2/G3:来源 = 最近对话窗口(与番外 captureDerivativeSource 同口径)。
                else -> {
                    val source = com.neethu.gouxiang.work.captureDerivativeSource(
                        repo.db, conversationId, conv.characterId,
                    )
                    repo.db.chatDerivativeDao().insert(
                        com.neethu.gouxiang.data.ChatDerivativeEntity(
                            kind = page.kind,
                            characterId = conv.characterId,
                            conversationId = conversationId,
                            optionJson = option.toJson(),
                            sourceStartMessageId = source.firstMessageId,
                            sourceEndMessageId = source.lastMessageId,
                            sourceVersion = source.version,
                            requestId = requestId,
                            imageStatus = if (page.kind == com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_PHOTO) {
                                com.neethu.gouxiang.data.ChatDerivativeEntity.IMAGE_PENDING
                            } else {
                                com.neethu.gouxiang.data.ChatDerivativeEntity.IMAGE_SKIPPED
                            },
                            createdAt = stamp,
                            updatedAt = stamp,
                        ),
                    )
                }
            }
        }
        if (recordId == -1L) return null
        if (recordId == -2L) throw IllegalStateException("还没有聊天记录,先和 TA 聊几句再生成手帐")
        val derivativeKind = when (page.kind) {
            com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_HANDBOOK -> com.neethu.gouxiang.chat.DerivativeKind.HANDBOOK
            com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_NOVEL -> com.neethu.gouxiang.chat.DerivativeKind.NOVEL
            else -> com.neethu.gouxiang.chat.DerivativeKind.PHOTO
        }
        com.neethu.gouxiang.work.DerivativeWorker.enqueue(
            getApplication(),
            com.neethu.gouxiang.chat.DerivativeTask(
                derivativeKind,
                conversationId,
                characterId = conv.characterId,
                recordId = recordId,
                requiresNetwork = false,
            ),
            requestIdOverride = requestId,
        )
        return recordId
    }

    /** 手帐生成入口(范围 chips;失败置一次性错误提示)。 */
    fun generateHandbook(presetKey: String) {
        launchDerivativeGeneration(handbookPage) {
            generateChatDerivative(handbookPage, DerivativeOptions.HandbookOption(presetKey))
        }
    }

    /** 小说生成入口(长度档 chips)。 */
    fun generateNovel(lengthKey: String) {
        launchDerivativeGeneration(novelPage) {
            generateChatDerivative(novelPage, DerivativeOptions.NovelOption(lengthKey))
        }
    }

    /** 合照生成入口(描述输入;空白描述直接提示,不发请求)。 */
    fun generatePhoto() {
        val description = _photoDescription.value.trim()
        if (description.isBlank()) {
            photoPage.ui.value = photoPage.ui.value.copy(error = "先写下想和 TA 合影的场景吧")
            return
        }
        launchDerivativeGeneration(photoPage) {
            generateChatDerivative(photoPage, DerivativeOptions.PhotoOption(description))
        }
    }

    /** 生成入口包装:成功清错误,失败转一次性错误(入队失败等)。 */
    private fun launchDerivativeGeneration(page: DerivativePage, block: suspend () -> Long?) {
        if (page.opened.value == null) return
        viewModelScope.launch {
            try {
                val recordId = block()
                if (recordId == null) {
                    page.ui.value = page.ui.value.copy(error = "同类内容正在生成中,请等它完成")
                } else {
                    page.ui.value = page.ui.value.copy(error = null)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.e("ChatDerivative", "enqueue failed", e)
                page.ui.value = page.ui.value.copy(error = e.message ?: "任务入队失败，请重试")
            }
        }
    }

    /** 失败重试(同一记录、同一 requestId;拨回 generating 后重新入队)。 */
    fun retryChatDerivative(recordId: Long) {
        viewModelScope.launch {
            try {
                val record = repo.chatDerivativeById(recordId) ?: return@launch
                if (!record.isFailed) return@launch
                val ok = repo.db.chatDerivativeDao().markGeneratingForRetry(recordId, System.currentTimeMillis()) > 0
                if (!ok) return@launch
                val kind = record.kind.toDerivativeKind()
                com.neethu.gouxiang.work.DerivativeWorker.enqueue(
                    getApplication(),
                    com.neethu.gouxiang.chat.DerivativeTask(
                        kind,
                        record.conversationId,
                        characterId = record.characterId,
                        recordId = recordId,
                        requiresNetwork = false,
                    ),
                    requestIdOverride = record.requestId,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.e("ChatDerivative", "retry enqueue failed", e)
                setDerivativePageError(recordId, "重试入队失败，请稍后再试")
            }
        }
    }

    /** 删除一条记录(列表与库一致;配图文件一并清理)。 */
    fun deleteChatDerivative(recordId: Long) {
        viewModelScope.launch {
            try {
                val record = repo.chatDerivativeById(recordId) ?: return@launch
                repo.deleteChatDerivative(recordId)
                if (record.imageFile.isNotBlank()) {
                    runCatching { java.io.File(record.imageFile).delete() }
                }
                for (page in listOf(handbookPage, novelPage, photoPage)) {
                    if (page.ui.value.detailId == recordId) page.ui.value = page.ui.value.copy(detailId = null)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.e("ChatDerivative", "delete failed", e)
                setDerivativePageError(recordId, "删除失败，请重试")
            }
        }
    }

    private fun openChatDerivativeDetail(page: DerivativePage, recordId: Long) {
        page.ui.value = page.ui.value.copy(detailId = recordId)
    }

    private fun closeChatDerivativeDetail(page: DerivativePage) {
        page.ui.value = page.ui.value.copy(detailId = null)
    }

    /** 详情开合(手帐/小说/合照三页;MainActivity 按页转发)。 */
    fun openHandbookDetail(recordId: Long) = openChatDerivativeDetail(handbookPage, recordId)
    fun closeHandbookDetail() = closeChatDerivativeDetail(handbookPage)
    fun openNovelDetail(recordId: Long) = openChatDerivativeDetail(novelPage, recordId)
    fun closeNovelDetail() = closeChatDerivativeDetail(novelPage)
    fun openPhotoDetail(recordId: Long) = openChatDerivativeDetail(photoPage, recordId)
    fun closePhotoDetail() = closeChatDerivativeDetail(photoPage)

    /** 一次性错误落到记录所在页(重试/删除从详情层触发时定位用)。 */
    private fun setDerivativePageError(recordId: Long, message: String) {
        for (page in listOf(handbookPage, novelPage, photoPage)) {
            if (page.ui.value.rows.any { it.id == recordId }) {
                page.ui.value = page.ui.value.copy(error = message)
                return
            }
        }
    }

    // ── 聊天背景设置(G3/G4「可用的背景设置」;合照页动作)────────────

    /** 把一张合照设为该会话的聊天背景(07 §1.3「设为聊天背景」本地化)。 */
    fun setChatBackground(record: com.neethu.gouxiang.data.ChatDerivativeEntity) {
        if (record.imageFile.isBlank()) return
        ChatBackgroundPrefs.setBackground(getApplication(), record.conversationId, record.imageFile)
    }

    /** 还原该会话的默认背景(合照页动作;幂等)。 */
    fun clearChatBackground(conversationId: Long?) {
        val id = conversationId ?: photoPage.opened.value ?: return
        ChatBackgroundPrefs.clearBackground(getApplication(), id)
    }

    /** 冷启动懒加载:把该会话已设背景从盘读进进程内同步态(设置动作本就即时入态)。 */
    fun ensureChatBackground(conversationId: Long?) {
        val id = conversationId ?: return
        ChatBackgroundPrefs.background(getApplication(), id)
    }

    // ── 手帐/小说/合照调试命令(ai_cmd:G 工作包验收口)───────────────

    /** `derivative_list`:当前会话的手帐/小说/合照记录(最新在前)。 */
    suspend fun debugChatDerivativeList(limit: Int): List<com.neethu.gouxiang.data.ChatDerivativeEntity> {
        val (_, conversationId) = requireOpenChatCharacterAndConversation()
        return repo.chatDerivatives(conversationId).take(limit)
    }

    /** `handbook_generate`/`novel_generate`/`photo_generate`:按当前会话生成一条(入队即返回)。 */
    suspend fun debugGenerateChatDerivative(kind: String, arg: String?): String {
        val chatId = _chatUi.value.conversationId
        if (chatId == 0L) throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
        val page = when (kind) {
            com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_HANDBOOK -> handbookPage
            com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_NOVEL -> novelPage
            com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_PHOTO -> photoPage
            else -> throw IllegalArgumentException("unknown derivative kind '$kind'")
        }
        if (page.opened.value != chatId) openDerivativePage(page, chatId)
        val option = when (kind) {
            com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_HANDBOOK ->
                DerivativeOptions.HandbookOption(DerivativeRange.Preset.fromKey(arg).key)
            com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_NOVEL ->
                DerivativeOptions.NovelOption(NovelLength.fromKey(arg).key)
            else -> {
                val description = arg?.trim().orEmpty()
                if (description.isBlank()) {
                    throw IllegalArgumentException("missing ai_arg — photo description text, e.g. \"天台看晚霞\"")
                }
                DerivativeOptions.PhotoOption(description.take(DerivativeOptions.PHOTO_DESCRIPTION_MAX_CHARS))
            }
        }
        val recordId = generateChatDerivative(page, option)
            ?: throw IllegalStateException("$kind is already generating — poll derivative_list")
        return "queued record $recordId ($kind) for conversation $chatId (poll derivative_list to confirm)"
    }

    /** `derivative_retry`:按记录 id 重试一次失败记录(与 UI「重试」同一数据路径)。 */
    suspend fun debugChatDerivativeRetry(recordId: Long): String {
        val record = repo.chatDerivativeById(recordId)
            ?: throw IllegalArgumentException("No chat derivative record matches id $recordId — send ai_cmd=derivative_list")
        if (!record.isFailed) throw IllegalStateException("record $recordId is ${record.state}; only failed records can be retried")
        val reset = repo.db.chatDerivativeDao().markGeneratingForRetry(recordId, System.currentTimeMillis()) > 0
        if (!reset) throw IllegalStateException("record $recordId state changed concurrently — poll derivative_list")
        com.neethu.gouxiang.work.DerivativeWorker.enqueue(
            getApplication(),
            com.neethu.gouxiang.chat.DerivativeTask(
                record.kind.toDerivativeKind(),
                record.conversationId,
                characterId = record.characterId,
                recordId = recordId,
                requiresNetwork = false,
            ),
            requestIdOverride = record.requestId,
        )
        return "retry queued for record $recordId (poll derivative_list to confirm)"
    }

    /** `derivative_delete`:按记录 id 删除(与 UI「删除」同一数据路径)。 */
    suspend fun debugChatDerivativeDelete(recordId: Long): String {
        val record = repo.chatDerivativeById(recordId)
            ?: throw IllegalArgumentException("No chat derivative record matches id $recordId — send ai_cmd=derivative_list")
        repo.deleteChatDerivative(recordId)
        if (record.imageFile.isNotBlank()) runCatching { java.io.File(record.imageFile).delete() }
        for (page in listOf(handbookPage, novelPage, photoPage)) {
            if (page.ui.value.detailId == recordId) page.ui.value = page.ui.value.copy(detailId = null)
        }
        return "deleted record $recordId (${record.kind}/${record.state})"
    }

    /** 记录 kind 字符串 → DerivativeKind(未知直接抛错由调用方处理)。 */
    private fun String.toDerivativeKind(): com.neethu.gouxiang.chat.DerivativeKind =
        when (this) {
            com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_HANDBOOK -> com.neethu.gouxiang.chat.DerivativeKind.HANDBOOK
            com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_NOVEL -> com.neethu.gouxiang.chat.DerivativeKind.NOVEL
            com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_PHOTO -> com.neethu.gouxiang.chat.DerivativeKind.PHOTO
            else -> throw IllegalArgumentException("unknown chat derivative kind '$this'")
        }

    // ── 心声 G4:角色开关 / 内容管理 ─────────────────────────────────

    /** `innervoice_switch` 的目标角色(心声页当前角色;未开页报错)。 */
    fun debugInnerVoiceCharacterId(): Long? =
        _innerVoiceUi.value.characterId.takeIf { it > 0 }

    /** 切换该角色「允许自动生成心声」(心声页开关行;只门控自动路径,点击不受限)。 */
    fun toggleInnerVoiceAuto(characterId: Long, enabled: Boolean) {
        viewModelScope.launch {
            val character = repo.characterById(characterId) ?: return@launch
            repo.updateCharacter(character.copy(innerVoiceAuto = enabled))
        }
    }

    /** 删除一张心声卡(G4 内容管理;配图文件一并清理)。 */
    fun deleteInnerVoice(id: Long) {
        viewModelScope.launch {
            try {
                val convId = _innerVoiceConversationId.value ?: return@launch
                val conv = repo.db.conversationDao().byId(convId) ?: return@launch
                val target = repo.innerVoices(conv.characterId).firstOrNull { it.id == id } ?: return@launch
                repo.db.innerVoiceDao().deleteById(id)
                if (target.imageFile.isNotBlank()) {
                    runCatching { java.io.File(target.imageFile).delete() }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.e("InnerVoice", "delete failed", e)
                _innerVoiceUi.value = _innerVoiceUi.value.copy(error = "删除失败，请重试")
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

    /** 番外/心动任务收口(MainActivity 消费事件后回调):成功同步主页尾注计数。 */
    fun onSidestoryTaskSettled(e: com.neethu.gouxiang.work.DerivativeEvents.Event) {
        if (e.success) refreshCharacterHomeCounts()
    }

    /** 手帐/小说/合照任务收口(MainActivity 消费事件后回调):成功同步主页尾注计数。 */
    fun onChatDerivativeTaskSettled(e: com.neethu.gouxiang.work.DerivativeEvents.Event) {
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

    /** 角色主页占位卡尾注同步(若主页仍打开;F8/F9/番外/G 衍生共用)。 */
    private fun refreshCharacterHomeCounts() {
        val homeConvId = _characterHomeConversationId.value ?: return
        viewModelScope.launch {
            val conv = repo.db.conversationDao().byId(homeConvId) ?: return@launch
            _characterHomeUi.value = _characterHomeUi.value.copy(
                innerVoiceCount = repo.countInnerVoices(conv.characterId),
                momentCount = repo.countMoments(conv.characterId),
                sidestoryCount = repo.countSidestoryRecords(conv.characterId),
                handbookCount = repo.countChatDerivatives(conv.characterId, com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_HANDBOOK),
                novelCount = repo.countChatDerivatives(conv.characterId, com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_NOVEL),
                photoCount = repo.countChatDerivatives(conv.characterId, com.neethu.gouxiang.data.ChatDerivativeEntity.KIND_PHOTO),
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
                statusDetailAuto = com.neethu.gouxiang.ai.GenerationPrefs.statusDetailAuto(app),
                momentsDaily = com.neethu.gouxiang.ai.GenerationPrefs.momentsDaily(app),
                memoryOvernight = com.neethu.gouxiang.ai.GenerationPrefs.memoryOvernight(app),
                proactiveOutbox = com.neethu.gouxiang.ai.GenerationPrefs.proactiveOutbox(app),
                handbookAuto = com.neethu.gouxiang.ai.GenerationPrefs.handbookAuto(app),
                novelAuto = com.neethu.gouxiang.ai.GenerationPrefs.novelAuto(app),
                notifyEnabled = com.neethu.gouxiang.ai.GenerationPrefs.notifyEnabled(app),
                innerVoiceImages = com.neethu.gouxiang.ai.GenerationPrefs.innerVoiceImages(app),
                momentsImages = com.neethu.gouxiang.ai.GenerationPrefs.momentsImages(app),
                sidestoryImages = com.neethu.gouxiang.ai.GenerationPrefs.sidestoryImages(app),
                handbookImages = com.neethu.gouxiang.ai.GenerationPrefs.handbookImages(app),
            )
    }

    /**
     * 切换立即写入偏好；记忆和主动消息的周期任务同时更新。
     */
    fun toggleGenerationSetting(key: String, enabled: Boolean) {
        val app = getApplication<Application>()
        val sched = com.neethu.gouxiang.work.DerivativeScheduler
        when (key) {
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_STATUS_DETAIL_AUTO ->
                com.neethu.gouxiang.ai.GenerationPrefs.setStatusDetailAuto(app, enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_INNER_VOICE_AUTO -> {
                com.neethu.gouxiang.ai.GenerationPrefs.setInnerVoiceAuto(app, enabled)
            }
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_MOMENTS_DAILY -> {
                com.neethu.gouxiang.ai.GenerationPrefs.setMomentsDaily(app, enabled)
            }
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_MEMORY_OVERNIGHT -> {
                com.neethu.gouxiang.ai.GenerationPrefs.setMemoryOvernight(app, enabled)
                sched.applyMemoryOvernight(app, enabled)
            }
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_PROACTIVE_OUTBOX -> {
                com.neethu.gouxiang.ai.GenerationPrefs.setProactiveOutbox(app, enabled)
                sched.applyProactiveOutbox(app, enabled)
            }
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_HANDBOOK_AUTO ->
                com.neethu.gouxiang.ai.GenerationPrefs.setHandbookAuto(app, enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_NOVEL_AUTO ->
                com.neethu.gouxiang.ai.GenerationPrefs.setNovelAuto(app, enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_NOTIFY_ENABLED -> {
                com.neethu.gouxiang.ai.GenerationPrefs.setNotifyEnabled(app, enabled)
            }
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_INNER_VOICE_IMAGES ->
                com.neethu.gouxiang.ai.GenerationPrefs.setInnerVoiceImages(app, enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_MOMENTS_IMAGES ->
                com.neethu.gouxiang.ai.GenerationPrefs.setMomentsImages(app, enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_SIDESTORY_IMAGES ->
                com.neethu.gouxiang.ai.GenerationPrefs.setSidestoryImages(app, enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_HANDBOOK_IMAGES ->
                com.neethu.gouxiang.ai.GenerationPrefs.setHandbookImages(app, enabled)
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
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_HANDBOOK_AUTO ->
                _generationSettings.value.copy(handbookAuto = enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_NOVEL_AUTO ->
                _generationSettings.value.copy(novelAuto = enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_INNER_VOICE_IMAGES ->
                _generationSettings.value.copy(innerVoiceImages = enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_MOMENTS_IMAGES ->
                _generationSettings.value.copy(momentsImages = enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_SIDESTORY_IMAGES ->
                _generationSettings.value.copy(sidestoryImages = enabled)
            com.neethu.gouxiang.ai.GenerationPrefs.KEY_HANDBOOK_IMAGES ->
                _generationSettings.value.copy(handbookImages = enabled)
            else -> _generationSettings.value.copy(notifyEnabled = enabled)
        }
    }

    // ── 任务历史页(M0 收尾工作包 1)────────────────────────────────

    /** 任务历史页打开态(MainActivity 全屏覆盖层导航惯例)。 */
    private val _taskHistoryOpen = MutableStateFlow(false)
    val taskHistoryOpen: StateFlow<Boolean> = _taskHistoryOpen.asStateFlow()

    /** 任务历史行(打开期间订阅 Room 流;失败可重试、进行中可取消)。 */
    private val _taskHistoryItems = MutableStateFlow<List<com.neethu.gouxiang.work.TaskHistoryItem>>(emptyList())
    val taskHistoryItems: StateFlow<List<com.neethu.gouxiang.work.TaskHistoryItem>> =
        _taskHistoryItems.asStateFlow()

    private val _taskHistoryLoading = MutableStateFlow(false)
    val taskHistoryLoading: StateFlow<Boolean> = _taskHistoryLoading.asStateFlow()

    /** 任务历史操作提示(重试/取消已提交;一次性 Toast 事件)。 */
    private val _taskHistoryNotice = MutableStateFlow("")
    val taskHistoryNotice: StateFlow<String> = _taskHistoryNotice.asStateFlow()

    fun consumeTaskHistoryNotice() {
        _taskHistoryNotice.value = ""
    }

    private var taskHistoryJob: Job? = null

    /** 打开任务历史页:先对账进程中断遗留,再订阅持久化历史(最新在前)。 */
    fun openTaskHistory() {
        if (_taskHistoryOpen.value) return
        _taskHistoryOpen.value = true
        _taskHistoryLoading.value = true
        taskHistoryJob?.cancel()
        taskHistoryJob = viewModelScope.launch {
            runCatching { com.neethu.gouxiang.work.reconcileDerivativeTasks(getApplication()) }
            repo.db.derivativeTaskDao().observeHistory(TASK_HISTORY_LIMIT).collect { rows ->
                val names = mutableMapOf<Long, String?>()
                _taskHistoryItems.value = rows.map { row ->
                    val name = names.getOrPut(row.characterId) {
                        if (row.characterId > 0) repo.characterById(row.characterId)?.name else null
                    }
                    com.neethu.gouxiang.work.TaskHistoryItem(record = row, characterName = name)
                }
                _taskHistoryLoading.value = false
            }
        }
    }

    fun closeTaskHistory() {
        taskHistoryJob?.cancel()
        taskHistoryJob = null
        _taskHistoryOpen.value = false
        _taskHistoryItems.value = emptyList()
        _taskHistoryLoading.value = false
    }

    /** 重试一条失败任务(同 requestId 幂等;结果提示走 Toast)。 */
    fun retryTask(workId: String) {
        viewModelScope.launch {
            val ok = runCatching {
                com.neethu.gouxiang.work.TaskHistoryController.retry(getApplication(), workId)
            }.getOrDefault(false)
            _taskHistoryNotice.value = if (ok) "已重新排队，稍后刷新查看结果" else "重试失败，请稍后再试"
        }
    }

    /** 取消一条排队/进行中任务(WorkManager + 持久化行条件更新)。 */
    fun cancelTask(workId: String) {
        viewModelScope.launch {
            val ok = runCatching {
                com.neethu.gouxiang.work.TaskHistoryController.cancel(getApplication(), workId)
            }.getOrDefault(false)
            _taskHistoryNotice.value = if (ok) "任务已取消" else "任务已结束，无需取消"
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

    /** 通知直跳入口:手帐页(G 工作包)。 */
    fun openHandbookFromNotification(conversationId: Long) {
        openHandbook(conversationId)
    }

    /** 通知直跳入口:小说页(G 工作包)。 */
    fun openNovelFromNotification(conversationId: Long) {
        openNovel(conversationId)
    }

    /** 通知直跳入口:合照页(G 工作包)。 */
    fun openPhotoFromNotification(conversationId: Long) {
        openPhoto(conversationId)
    }

    /** 通知直跳入口:聊天页(手机形态,与消息列表角标入口同参)。 */
    fun openChatFromNotification(conversationId: Long) {
        if (_chatConversationId.value == conversationId) {
            // 同会话通知点入:openChat 幂等早退不会重放进页消费,这里补一次
            // 未读消费(角标清零;新批次消息经 Room 流照常上屏)。
            viewModelScope.launch {
                runCatching { repo.proactiveLedger.consumeConversation(conversationId, System.currentTimeMillis()) }
            }
            return
        }
        openChat(conversationId, phoneMode = true)
    }

    /** 通知直跳入口:消息列表(切 tab)。 */
    fun openMessagesFromNotification() {
        selectedTab.value = BottomTab.MESSAGES
    }

    // ── 状态面板(E 工作包 E3):聊天状态条点击进入的全屏覆盖层 ───────

    /** 状态面板打开的会话 id(null=未进入)。 */
    private val _statusPanelConversationId = MutableStateFlow<Long?>(null)
    val statusPanelConversationId: StateFlow<Long?> = _statusPanelConversationId.asStateFlow()

    /** 状态面板 UI 状态(打开期间订阅该角色的状态记录流)。 */
    private val _statusPanelUi = MutableStateFlow(com.neethu.gouxiang.ui.status.StatusPanelUiState())
    val statusPanelUi: StateFlow<com.neethu.gouxiang.ui.status.StatusPanelUiState> = _statusPanelUi.asStateFlow()

    private var statusPanelJob: Job? = null
    private var statusDetailJob: Job? = null

    /** 打开状态面板:解析角色并订阅其状态记录(最新在前;含失效区)。 */
    fun openStatusPanel(conversationId: Long) {
        if (_statusPanelConversationId.value == conversationId) return
        statusDetailJob?.cancel()
        statusDetailJob = null
        _statusPanelConversationId.value = conversationId
        _statusPanelUi.value = com.neethu.gouxiang.ui.status.StatusPanelUiState(loading = true)
        statusPanelJob?.cancel()
        statusPanelJob = viewModelScope.launch {
            val conv = repo.db.conversationDao().byId(conversationId)
            val character = conv?.let { repo.characterById(it.characterId) }
            if (conv == null || character == null) {
                _statusPanelUi.value = com.neethu.gouxiang.ui.status.StatusPanelUiState(loadFailed = true)
                return@launch
            }
            repo.db.characterStatusDao().observeForCharacter(character.id, limit = Int.MAX_VALUE).collect { records ->
                _statusPanelUi.value = _statusPanelUi.value.copy(
                    characterName = character.name,
                    loading = false,
                    rows = records.map { com.neethu.gouxiang.ui.status.toStatusRow(it) },
                )
            }
        }
    }

    /** 关闭状态面板。 */
    fun closeStatusPanel() {
        statusDetailJob?.cancel()
        statusDetailJob = null
        statusPanelJob?.cancel()
        statusPanelJob = null
        _statusPanelConversationId.value = null
        _statusPanelUi.value = com.neethu.gouxiang.ui.status.StatusPanelUiState()
    }

    /** 展开记录时才请求详情；已生成的详情由管理器从本地读取。 */
    fun openStatusDetail(id: Long) {
        if (_statusPanelConversationId.value == null) return
        if (_statusPanelUi.value.rows.none { it.id == id }) return
        statusDetailJob?.cancel()
        _statusPanelUi.value = _statusPanelUi.value.copy(
            selectedId = id, detail = null, detailLoading = true, detailFailed = false,
        )
        statusDetailJob = viewModelScope.launch {
            val record = repo.db.characterStatusDao().byId(id)
            val character = record?.let { repo.characterById(it.characterId) }
            val detail = if (record != null && character != null) {
                try {
                    statusManager.generateDetail(character, record)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } else null
            if (_statusPanelUi.value.selectedId == id) {
                _statusPanelUi.value = _statusPanelUi.value.copy(
                    detail = detail, detailLoading = false, detailFailed = detail == null,
                )
            }
        }
    }

    fun closeStatusDetail() {
        statusDetailJob?.cancel()
        statusDetailJob = null
        _statusPanelUi.value = _statusPanelUi.value.copy(
            selectedId = null, detail = null, detailLoading = false, detailFailed = false,
        )
    }

    fun retryStatusDetail() {
        _statusPanelUi.value.selectedId?.let { openStatusDetail(it) }
    }

    /** 打开生成该状态的会话；没有来源时回到当前聊天。 */
    fun viewStatusConversation() {
        val id = _statusPanelUi.value.selectedId ?: return
        viewModelScope.launch {
            val record = repo.db.characterStatusDao().byId(id)
            val sourceId = record?.sourceConversationId
            val source = sourceId?.let { repo.db.conversationDao().byId(it) }
            if (_statusPanelUi.value.selectedId != id) return@launch
            closeStatusPanel()
            if (record != null && source != null && source.characterId == record.characterId) {
                openChat(source.id, phoneMode = source.mode == "phone")
            }
        }
    }

    /** 清理当前角色的失效记录(面板「清理失效记录」;一次删光 stale 行)。 */
    fun clearStaleStatuses() {
        val conversationId = _statusPanelConversationId.value ?: return
        viewModelScope.launch {
            val conv = repo.db.conversationDao().byId(conversationId) ?: return@launch
            repo.db.characterStatusDao().clearStale(conv.characterId)
        }
    }

    // ── 番外/心动调试命令(ai_cmd:F 工作包验收口)────────────────────

    /** `sidestory_list`:当前会话的番外/心动记录(最新在前)。 */
    suspend fun debugSidestoryList(limit: Int): List<com.neethu.gouxiang.data.SidestoryRecordEntity> {
        val (_, conversationId) = requireOpenChatCharacterAndConversation()
        return repo.sidestoryRecords(conversationId).take(limit)
    }

    /** `sidestory_generate`:按模板/事件 key 在当前会话生成一条(入队即返回)。 */
    suspend fun debugSidestoryGenerate(key: String): String {
        val chatId = _chatUi.value.conversationId
        if (chatId == 0L) throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
        if (_sidestoryConversationId.value != chatId) openSidestory(chatId)
        val entry = com.neethu.gouxiang.sidestory.SidestoryCatalog.byKey(key)
            ?: throw IllegalArgumentException(
                "No sidestory template or heartbeat event matches '$key'. Send ai_cmd=sidestory_keys to see options.",
            )
        val recordId = generateSidestory(entry.key)
            ?: throw IllegalStateException("'${entry.key}' is already generating — poll sidestory_list")
        return "queued record $recordId (${entry.name}) for conversation $chatId (poll sidestory_list to confirm)"
    }

    /** `sidestory_delete`:按记录 id 删除(与 UI「删除」同一数据路径)。 */
    suspend fun debugSidestoryDelete(recordId: Long): String {
        val record = repo.sidestoryRecordById(recordId)
            ?: throw IllegalArgumentException("No sidestory record matches id $recordId — send ai_cmd=sidestory_list")
        repo.deleteSidestoryRecord(recordId)
        if (record.imageFile.isNotBlank()) runCatching { java.io.File(record.imageFile).delete() }
        if (_sidestoryUi.value.detailId == recordId) closeSidestoryDetail()
        return "deleted record $recordId (${record.templateKey}/${record.state})"
    }

    /** `sidestory_retry`:按记录 id 重试一次失败记录(与 UI「重试」同一数据路径)。 */
    suspend fun debugSidestoryRetry(recordId: Long): String {
        val record = repo.sidestoryRecordById(recordId)
            ?: throw IllegalArgumentException("No sidestory record matches id $recordId — send ai_cmd=sidestory_list")
        if (!record.isFailed) throw IllegalStateException("record $recordId is ${record.state}; only failed records can be retried")
        val reset = repo.db.sidestoryRecordDao().markGeneratingForRetry(recordId, System.currentTimeMillis()) > 0
        if (!reset) throw IllegalStateException("record $recordId state changed concurrently — poll sidestory_list")
        com.neethu.gouxiang.work.DerivativeWorker.enqueue(
            getApplication(),
            com.neethu.gouxiang.chat.DerivativeTask(
                com.neethu.gouxiang.chat.DerivativeKind.SIDESTORY,
                record.conversationId,
                characterId = record.characterId,
                recordId = recordId,
                requiresNetwork = false,
            ),
            requestIdOverride = record.requestId,
        )
        return "retry queued for record $recordId (poll sidestory_list to confirm)"
    }

    /** `sidestory_keys`:番外模板与心动事件 key 清单(生成命令的参数来源)。 */
    fun debugSidestoryKeys(): String =
        com.neethu.gouxiang.sidestory.SidestoryCatalog.entries.joinToString(" ") { it.key } +
            " | " + com.neethu.gouxiang.sidestory.SidestoryCatalog.heartbeatKeys().joinToString(" ")

    // ── 状态调试命令(ai_cmd:E 工作包验收口)────────────────────────

    /** `status_list`:当前角色近期状态记录(面板同一数据源)。 */
    suspend fun debugStatusList(limit: Int): List<com.neethu.gouxiang.data.CharacterStatusEntity> {
        val character = requireOpenChatCharacter()
        return repo.db.characterStatusDao().forCharacter(character.id, limit)
    }

    /** `status_now`:当前状态条取值(记录优先/规则兜底)+ 是否来自记录。 */
    suspend fun debugStatusNow(): Pair<com.neethu.gouxiang.chat.StatusLine, Boolean> {
        val (character, conversationId) = requireOpenChatCharacterAndConversation()
        val record = repo.db.characterStatusDao().latestActive(character.id)
        val line = statusManager.currentLine(
            character,
            conversationId,
            repo.recentMessages(conversationId, 20),
        )
        return line to (record != null)
    }

    /**
     * `status_inject`:手动注入一条状态(离线验收不依赖 LLM);
     * arg 格式 = `心情|正在做的事|内心想法[|emoji]`。
     */
    suspend fun debugStatusInject(arg: String): com.neethu.gouxiang.data.CharacterStatusEntity {
        val (character, conversationId) = requireOpenChatCharacterAndConversation()
        val parts = arg.split('|').map { it.trim() }
        if (parts.size < 2) {
            throw IllegalArgumentException("usage: status_inject \"心情|正在做的事|内心想法[|emoji]\"")
        }
        return statusManager.injectManual(
            character = character,
            conversationId = conversationId,
            mood = parts[0],
            doing = parts[1],
            thought = parts.getOrElse(2) { "" },
            emoji = parts.getOrNull(3)?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * `status_refresh`:立即生成一次状态(不等回合收尾冷却;与回合通道同一
     * LLM/schema/版本拦截链路)。入队即返回,结果用 status_list 轮询确认。
     */
    fun debugStatusRefresh(): String {
        val conversationId = _chatUi.value.conversationId
        if (conversationId == 0L) throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
        val character = _chatUi.value.character
            ?: throw IllegalStateException("chat not ready (still loading); resend ai_cmd=status_refresh")
        viewModelScope.launch {
            runCatching {
                val recent = repo.recentMessages(conversationId, 6)
                val lastUser = recent.lastOrNull { it.role == "user" }
                val lastAssistant = recent.lastOrNull { it.role == "assistant" } ?: return@runCatching
                statusManager.generate(
                    character = character,
                    conversationId = conversationId,
                    turnId = lastAssistant.turnId,
                    userText = lastUser?.content.orEmpty(),
                    assistantText = lastAssistant.content,
                    userMessageId = lastUser?.id,
                    sourceKind = com.neethu.gouxiang.data.CharacterStatusEntity.SOURCE_TURN,
                )
            }.onFailure { android.util.Log.w("CharacterStatus", "refresh failed: ${it.message}") }
        }
        return "status refresh queued for character=${character.name} (poll status_list to confirm)"
    }

    /** 聊天页打开且就绪的角色(状态调试命令前置校验)。 */
    private fun requireOpenChatCharacter(): com.neethu.gouxiang.data.CharacterEntity {
        val chatId = _chatUi.value.conversationId
        if (chatId == 0L) throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
        return _chatUi.value.character
            ?: throw IllegalStateException("chat not ready (still loading); resend the command")
    }

    private fun requireOpenChatCharacterAndConversation(): Pair<com.neethu.gouxiang.data.CharacterEntity, Long> {
        val chatId = _chatUi.value.conversationId
        if (chatId == 0L) throw IllegalStateException("no open chat — send ai_cmd=open_chat <id|name> first")
        val character = _chatUi.value.character
            ?: throw IllegalStateException("chat not ready (still loading); resend the command")
        return character to chatId
    }
}

/**
 * 角色主页 04 的 UI 状态:角色卡 + 状态条(与聊天页同一 currentLine 数据源,
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
    /** 「番外剧场」占位卡尾注(F 工作包:全部 N;N=0 时不带尾注)。 */
    val sidestoryCount: Int = 0,
    /** 「手帐」「小说」「合照」入口卡尾注(G 工作包:全部 N;N=0 时不带尾注)。 */
    val handbookCount: Int = 0,
    val novelCount: Int = 0,
    val photoCount: Int = 0,
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
     * 离线回归分隔(D 工作包 D4):turnId → 分隔文案。进页消费的未读主动
     * 批次按其 turnId 定位,对应气泡组上方渲染「离线期间发来了 N 条消息」;
     * 空表 = 无离线回归展示。openChat 重建整态时清空。
     */
    val separatorTurnIds: Map<String, String> = emptyMap(),
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
    /**
     * 离线回归分隔(D 工作包 D4):非空 = 本组上方渲染该文案的居中灰字
     * (「离线期间发来了 N 条消息」)。仅进页消费的主动批次组携带。
     */
    val separator: String? = null,
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
    /** 真实图片文件路径(kind=image 用户选图泡;空串=无,测试构造默认)。 */
    val mediaPath: String = "",
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
 *
 * [separators](turnId → 分隔文案,D 工作包 D4):命中的组在上渲染离线分隔。
 */
internal fun toBubbleItems(
    messages: List<MessageEntity>,
    separators: Map<String, String> = emptyMap(),
): List<BubbleItem> {
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
                        mediaPath = m.mediaPath,
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
                    mediaPath = row.mediaPath,
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
                separator = separators[m.turnId],
            )
        }
    }
    return items
}

/**
 * 进页消费的主动批次 → 离线分隔映射(D 工作包 D4,纯函数单测锁文案):
 * turnId → 「离线期间发来了 N 条消息」;空 turnId(生成中/作废)不产生分隔。
 */
internal fun offlineSeparators(
    consumed: List<com.neethu.gouxiang.proactive.ProactiveOutboxService.ConsumedBatch>,
): Map<String, String> =
    consumed.filter { it.turnId.isNotBlank() }
        .associate { it.turnId to "离线期间发来了 ${it.count} 条消息" }

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
