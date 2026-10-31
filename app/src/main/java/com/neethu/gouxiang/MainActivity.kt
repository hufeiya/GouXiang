package com.neethu.gouxiang

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neethu.gouxiang.debug.AI_EXTRA_KEYS
import com.neethu.gouxiang.debug.AI_LOG_TAG
import com.neethu.gouxiang.debug.AiDebugBus
import com.neethu.gouxiang.debug.EXTRA_AI_CMD
import com.neethu.gouxiang.debug.executeAiCommand
import com.neethu.gouxiang.debug.parseAiDebugCommand
import com.neethu.gouxiang.ui.GouXiangBottomBar
import com.neethu.gouxiang.ui.MainViewModel
import com.neethu.gouxiang.ui.character.CharacterHomeScreen
import com.neethu.gouxiang.ui.home.HomeFeedScreen
import com.neethu.gouxiang.ui.chat.ChatMainScreen
import com.neethu.gouxiang.ui.messages.MessagesScreen
import com.neethu.gouxiang.ui.memory.MemoryScreen
import com.neethu.gouxiang.ui.nav.BottomTab
import com.neethu.gouxiang.ui.phone.PhoneChatGuideScreen
import com.neethu.gouxiang.ui.phone.PhoneChatScreen
import com.neethu.gouxiang.ui.profile.ProfileScreen
import com.neethu.gouxiang.ui.aiconfig.AiConfigActivity
import com.neethu.gouxiang.ui.theme.GouXiangTheme
import kotlinx.coroutines.channels.Channel

/**
 * 主入口。除触摸 UI 外暴露 AI 原生调试接口(docs/ai-debug-intents.md):任何
 * 带 `ai_cmd` extras 的启动 Intent 均入队,UI 就绪后逐条执行——冷启动经
 * [onCreate],运行中经 [onNewIntent](manifest 已设 `launchMode="singleTask"`,
 * 重复 am start 复用既有实例)。结果打在 `AIDebug` 标签下,代理用
 * `adb logcat -d -s AIDebug` 读回。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GouXiangTheme {
                MainFrame()
            }
        }
        enqueueAiCommands(intent, via = "onCreate")
        handleNavigationExtras(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.setChatSpeechForeground(true)
    }

    override fun onStop() {
        viewModel.setChatSpeechForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 热启动入口(singleTask 复用既有实例):命令进进程级总线,组合内消费
        // 循环(无论经历过多少次重建)都会取到——M0 收尾工作包 4。
        enqueueAiCommands(intent, via = "onNewIntent")
        handleNavigationExtras(intent)
    }

    /**
     * 通知直跳导航 extras(后台生成基建 §2.3;与 ai_cmd 调试 extras 完全分开):
     * open_inner_voice_conversation_id 直跳心声页、open_moments_conversation_id
     * 直跳朋友圈、open_chat_conversation_id 直跳手机聊天、open_messages 切消息
     * 列表。导航态经 VM 落地(通知点击时 VM 可能未就绪,由 MainFrame 组合后
     * 在 LaunchedEffect 消费 pendingExtras)。
     */
    private val pendingNavExtras = Channel<android.os.Bundle>(Channel.UNLIMITED)

    private fun handleNavigationExtras(intent: Intent?) {
        val extras = intent?.extras ?: return
        val hasNavExtra =
            extras.containsKey(com.neethu.gouxiang.work.NotificationPoster.EXTRA_OPEN_INNER_VOICE) ||
                extras.containsKey(com.neethu.gouxiang.work.NotificationPoster.EXTRA_OPEN_MOMENTS) ||
                extras.containsKey(com.neethu.gouxiang.work.NotificationPoster.EXTRA_OPEN_SIDESTORY) ||
                extras.containsKey(com.neethu.gouxiang.work.NotificationPoster.EXTRA_OPEN_CHAT) ||
                extras.containsKey(com.neethu.gouxiang.work.NotificationPoster.EXTRA_OPEN_MESSAGES)
        if (hasNavExtra) pendingNavExtras.trySend(extras.clone() as android.os.Bundle)
    }

    /**
     * 解析 ai extras 并入队;带 ai_* extras 但缺 ai_cmd 时 warn(防 LLM 排查无从下手)。
     *
     * M0 收尾(热启动修复):入队走进程级 [AiDebugBus](Activity 实例重建不丢命令);
     * 另打 `AIDebug.Queue` 一行溯源(经 onCreate 还是 onNewIntent 入队)——
     * `AIDebug` 主标签仍保持「每条命令恰好一行 OK/FAIL」契约;排查用
     * `adb logcat -d -s AIDebug.Queue` 区分「Intent 没进 App」与「入队未消费」。
     */
    private fun enqueueAiCommands(intent: Intent?, via: String) {
        val extras = intent?.extras ?: return
        val command = parseAiDebugCommand(extras.toAiDebugMap())
        if (command == null) {
            if (extras.keySet().any { it in AI_EXTRA_KEYS }) {
                Log.w(AI_LOG_TAG, "Intent ignored: missing or empty '$EXTRA_AI_CMD' string extra")
            }
            return
        }
        AiDebugBus.enqueue(command)
        Log.d(QUEUE_LOG_TAG, "queued ai_cmd=${command.name} via $via")
    }

    /** 溯源标签(独立于 [AI_LOG_TAG],不破坏逐命令一行契约)。 */
    private val QUEUE_LOG_TAG = "AIDebug.Queue"

    private fun Bundle.toAiDebugMap(): Map<String, Any?> =
        keySet().associateWith { get(it) }

    @Composable
    private fun MainFrame(vm: MainViewModel = viewModel()) {
        val tab by vm.selectedTab.collectAsState()
        val feed by vm.homeFeed.collectAsState()
        val conversations by vm.conversations.collectAsState()
        val avatarFiles by vm.avatarFiles.collectAsState()
        // 角色创作与管理(C 工作包):创作页列表/头像状态/编辑页
        val allCharacters by vm.allCharacters.collectAsState()
        val avatarStates by vm.avatarStates.collectAsState()
        val chatId by vm.chatConversationId.collectAsState()
        val chatUi by vm.chatUi.collectAsState()
        val streamDraft by vm.streamDraft.collectAsState()
        val chatSoundEnabled by vm.chatSoundEnabled.collectAsState()
        // 手机聊天 F4:形态开关 + 说明页 + 语音播放状态
        val isPhoneChat by vm.isPhoneChat.collectAsState()
        val phoneGuideOpen by vm.phoneGuideOpen.collectAsState()
        val playingVoiceId by vm.playingVoiceId.collectAsState()
        // 记忆页 05(F3)
        val memoryId by vm.memoryConversationId.collectAsState()
        val memoryUi by vm.memoryUi.collectAsState()
        // 角色主页 04(F5)
        val characterHomeId by vm.characterHomeConversationId.collectAsState()
        val characterHomeUi by vm.characterHomeUi.collectAsState()
        // 心声页 07(F8)
        val innerVoiceId by vm.innerVoiceConversationId.collectAsState()
        val innerVoiceUi by vm.innerVoiceUi.collectAsState()
        // 朋友圈页 06(F9)
        val momentsId by vm.momentsConversationId.collectAsState()
        val momentsUi by vm.momentsUi.collectAsState()
        // 番外剧场与心动事件(F 工作包 F3/F4)
        val sidestoryId by vm.sidestoryConversationId.collectAsState()
        val sidestoryUi by vm.sidestoryUi.collectAsState()

        // 手帐/小说/合照(G 工作包):三页打开态/记录流 + 合照描述与聊天背景
        val handbookId by vm.handbookConversationId.collectAsState()
        val handbookUi by vm.handbookUi.collectAsState()
        val novelId by vm.novelConversationId.collectAsState()
        val novelUi by vm.novelUi.collectAsState()
        val photoId by vm.photoConversationId.collectAsState()
        val photoUi by vm.photoUi.collectAsState()
        val photoDescription by vm.photoDescription.collectAsState()
        val chatBackgrounds by vm.chatBackgrounds.collectAsState()
        // 对话风格(F5:弹层 09/10 的当前选择)
        val chatStyle by vm.chatStyle.collectAsState()
        // 语音通话 12(F6)
        val voiceCallId by vm.voiceCallConversationId.collectAsState()
        // 聊天历史页 11(F7)
        val historyId by vm.historyConversationId.collectAsState()
        val historyUi by vm.historyUi.collectAsState()
        // 状态面板(E 工作包 E3):聊天页状态条点击进入
        val statusPanelId by vm.statusPanelConversationId.collectAsState()
        val statusPanelUi by vm.statusPanelUi.collectAsState()
        // 按住说话·语音输入(F4-P1:录音→ASR 转写→填草稿,不直接发送)
        val voiceInputActive by vm.voiceInputActive.collectAsState()
        val voiceInputRecognizing by vm.voiceInputRecognizing.collectAsState()
        val voiceInputTranscribed by vm.voiceInputTranscribed.collectAsState()
        val voiceInputNotice by vm.voiceInputNotice.collectAsState()
        // F4 语音输入失败/没听清:Toast 兜底(事件流消费,报告 §5.2 方案口径)
        val toastContext = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(voiceInputNotice) {
            if (voiceInputNotice.isNotBlank()) {
                android.widget.Toast.makeText(
                    toastContext,
                    voiceInputNotice,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                vm.consumeVoiceInputNotice()
            }
        }
        // F4 麦克风权限懒申请:首按「按住说话/🎤」未授权时弹系统弹窗,授权后
        // 需再按一次开录(与 VoiceCallScreen 进页即申请不同,聊天页不主动打扰)。
        val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { }
        val holdStartWithPermission: () -> Unit = {
            if (toastContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                vm.voiceInputStart()
            } else {
                micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }

        // 角色头像:一次生成缓存本地(火山→硅基→占位兜底在 provider 内)
        LaunchedEffect(feed) {
            val need = feed.map { it.character }
            if (need.isNotEmpty()) vm.ensureAvatars(need)
        }

        // AI 原生调试通道(docs/ai-debug-intents.md):消费进程级总线里的命令,
        // UI 就绪即执行(冷启动积压与热启动新到命令同一条通道);每条命令恰好
        // 一行 OK/FAIL 打在 AIDebug 标签下。总线在进程级,Activity 实例重建/
        // 组合重启后新循环继续取队尾,命令不丢(热启动修复,M0 收尾工作包 4)。
        val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
        LaunchedEffect(vm) {
            for (cmd in AiDebugBus.commands) {
                executeAiCommand(appContext, vm, cmd)
            }
        }

        // 通知直跳通道(§2.3):消费 Intent 入队的导航 extras(冷启动积压的
        // 也在此排空)。数值 extras 经 Bundle 读出可能是 Long 或 String(通知
        // PendingIntent 放 Long;am 命令行 --el 也是 Long),用类型兼容读取。
        fun android.os.Bundle.navLong(key: String): Long? = when (val v = get(key)) {
            is Long -> v
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
        LaunchedEffect(vm) {
            for (extras in pendingNavExtras) {
                extras.navLong(
                    com.neethu.gouxiang.work.NotificationPoster.EXTRA_OPEN_INNER_VOICE,
                )?.let { vm.openInnerVoicesFromNotification(it) }
                extras.navLong(
                    com.neethu.gouxiang.work.NotificationPoster.EXTRA_OPEN_MOMENTS,
                )?.let { vm.openMomentsFromNotification(it) }
                extras.navLong(
                    com.neethu.gouxiang.work.NotificationPoster.EXTRA_OPEN_SIDESTORY,
                )?.let { vm.openSidestoryFromNotification(it) }
                extras.navLong(
                    com.neethu.gouxiang.work.NotificationPoster.EXTRA_OPEN_HANDBOOK,
                )?.let { vm.openHandbookFromNotification(it) }
                extras.navLong(
                    com.neethu.gouxiang.work.NotificationPoster.EXTRA_OPEN_NOVEL,
                )?.let { vm.openNovelFromNotification(it) }
                extras.navLong(
                    com.neethu.gouxiang.work.NotificationPoster.EXTRA_OPEN_PHOTO,
                )?.let { vm.openPhotoFromNotification(it) }
                extras.navLong(
                    com.neethu.gouxiang.work.NotificationPoster.EXTRA_OPEN_CHAT,
                )?.let { vm.openChatFromNotification(it) }
                if (extras.containsKey(
                        com.neethu.gouxiang.work.NotificationPoster.EXTRA_OPEN_MESSAGES,
                    )
                ) {
                    vm.openMessagesFromNotification()
                }
            }
        }

        // 派生任务事件收口(§2.4):心声/朋友圈点击路径的乐观 generating 态
        // 按事件结果收口(成功清态,失败清态 + error 提示)。
        val derivativeEvent by vm.derivativeEvent.collectAsState()
        LaunchedEffect(derivativeEvent) {
            val e = derivativeEvent ?: return@LaunchedEffect
            vm.consumeDerivativeEvent()
            when (e.kind) {
                com.neethu.gouxiang.chat.DerivativeKind.INNER_VOICE -> vm.onInnerVoiceTaskSettled(e)
                com.neethu.gouxiang.chat.DerivativeKind.MOMENT -> vm.onMomentTaskSettled(e)
                com.neethu.gouxiang.chat.DerivativeKind.SIDESTORY -> vm.onSidestoryTaskSettled(e)
                else -> vm.onChatDerivativeTaskSettled(e)
            }
        }

        // 设置页(§3.3)打开时刷快照(进页即读 prefs)
        val generationSettingsOpen by vm.generationSettingsOpen.collectAsState()
        val generationSettings by vm.generationSettings.collectAsState()
        LaunchedEffect(generationSettingsOpen) {
            if (generationSettingsOpen) vm.loadGenerationSettings()
        }

        // 任务历史页(M0 收尾):打开时订阅持久化历史;操作提示走一次性 Toast
        val taskHistoryOpen by vm.taskHistoryOpen.collectAsState()
        val taskHistoryItems by vm.taskHistoryItems.collectAsState()
        val taskHistoryLoading by vm.taskHistoryLoading.collectAsState()
        val taskHistoryNotice by vm.taskHistoryNotice.collectAsState()
        LaunchedEffect(taskHistoryNotice) {
            if (taskHistoryNotice.isNotBlank()) {
                android.widget.Toast.makeText(toastContext, taskHistoryNotice, android.widget.Toast.LENGTH_SHORT).show()
                vm.consumeTaskHistoryNotice()
            }
        }

        // 「完成通知」打开时(≥33)现场请求 POST_NOTIFICATIONS 运行时权限
        val notifPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { }
        val settingsToggleWithPermission: (String, Boolean) -> Unit = { key, enabled ->
            if (key == com.neethu.gouxiang.ai.GenerationPrefs.KEY_NOTIFY_ENABLED && enabled) {
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            vm.toggleGenerationSetting(key, enabled)
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            // 沉浸式(F1 验收唯一偏差修复):不消费状态栏 inset,首页剧卡全屏图
            // 直通到状态栏下(参考图效果),消除黑色状态栏底;顶栏/tab 行自管
            // statusBarsPadding,底栏自管 navigationBarsPadding。
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = Color(0xFF0B0B0B),
            bottomBar = {
                GouXiangBottomBar(
                    selected = tab,
                    onSelect = { vm.selectedTab.value = it },
                    messageBadge = conversations.count { it.conversation.unread > 0 },
                )
            },
        ) { innerPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0B0B0B))
                    .padding(innerPadding),
            ) {
                when (tab) {
                    BottomTab.HOME -> HomeFeedScreen(
                        cards = feed,
                        avatarFiles = avatarFiles,
                        introBarText = "本地版 · 角色形象由 AI 生成",
                        // F3(P1):「名字 >」/「历史」与 im 聊天页同款接线;
                        // feed 卡片对应 im 会话,历史页同理
                        onOpenCharacterHome = { vm.openCharacterHome(it) },
                        onOpenHistory = { vm.openHistory(it) },
                    )
                    BottomTab.DISCOVER -> com.neethu.gouxiang.ui.discover.DiscoverScreen(
                        characters = allCharacters,
                        conversations = conversations,
                        avatarFiles = avatarFiles,
                        onOpenMoments = { vm.openMomentsGlobal() },
                        onOpenCharacterHome = { vm.openCharacterHome(it) },
                    )
                    BottomTab.CREATE -> com.neethu.gouxiang.ui.character.CharacterStudioScreen(
                        characters = allCharacters,
                        conversations = conversations,
                        avatarFiles = avatarFiles,
                        avatarStates = avatarStates,
                        onCreateNew = { vm.openCharacterEditor(null) },
                        onEdit = { vm.openCharacterEditor(it) },
                        onOpenChat = { vm.openChat(it) },
                        onOpenPhoneChat = { vm.openChat(it, phoneMode = true) },
                        onRetryAvatar = { vm.retryAvatar(it) },
                    )
                    BottomTab.MESSAGES -> MessagesScreen(
                        rows = conversations,
                        avatarFiles = avatarFiles,
                        onOpenChat = { vm.openChat(it) },
                        onOpenPhoneChat = { vm.openChat(it, phoneMode = true) },
                    )
                    BottomTab.PROFILE -> ProfileScreen(
                        // AI 服务配置(独立 Activity):大模型/语音通话 TTS/ASR
                        onOpenAiConfig = {
                            startActivity(
                                android.content.Intent(this@MainActivity, AiConfigActivity::class.java),
                            )
                        },
                        // 内容生成设置页(后台生成基建 §3.3)
                        onOpenGenerationSettings = { vm.openGenerationSettings() },
                        // 任务历史页(M0 收尾工作包 1:查看/重试/取消)
                        onOpenTaskHistory = { vm.openTaskHistory() },
                    )
                }
            }
        }

        // 聊天主页 03:全屏覆盖层(自管边缘到边缘,盖住底栏;返回回消息列表)
        if (chatId != null && !isPhoneChat) {
            // 合照背景冷启动懒加载(G3/G4):进聊天页把已设背景读进同步态
            LaunchedEffect(chatId) { vm.ensureChatBackground(chatId) }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF101014)),
            ) {
                ChatMainScreen(
                    state = chatUi,
                    avatarFile = chatUi.character?.let { avatarFiles[it.id] },
                    // 合照设为聊天背景(G3/G4):设置了合照背景的会话优先展示合照
                    backgroundFile = chatBackgrounds[chatId],
                    onBack = { vm.closeChat() },
                    onSend = { vm.sendChatMessage(it) },
                    streamDraft = streamDraft,
                    onOpenMemory = {
                        chatId?.let { vm.openMemory(it) }
                    },
                    onOpenCharacterHome = {
                        chatId?.let { vm.openCharacterHome(it) }
                    },
                    onOpenVoiceCall = {
                        chatId?.let { vm.openVoiceCall(it) }
                    },
                    onOpenHistory = {
                        chatId?.let { vm.openHistory(it) }
                    },
                    onRetryFailedTurn = { vm.retryFailedTurn() },
                    onRewind = { vm.rewindToMessage(it) },
                    onRegenerate = { vm.regenerateTurn(it) },
                    onHoldStart = holdStartWithPermission,
                    onHoldRelease = { vm.voiceInputRelease() },
                    holdActive = voiceInputActive,
                    recognizing = voiceInputRecognizing,
                    voiceTranscribed = voiceInputTranscribed,
                    onVoiceTranscribedConsumed = { vm.consumeVoiceInputTranscribed() },
                    soundEnabled = chatSoundEnabled,
                    onToggleSound = { vm.toggleChatSound() },
                )
            }
        }

        // 手机聊天页 14(F4):mode=phone 会话的全屏覆盖层(iMessage 风皮肤)
        if (chatId != null && isPhoneChat) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF6F6F6)),
            ) {
                PhoneChatScreen(
                    state = chatUi,
                    avatarFile = chatUi.character?.let { avatarFiles[it.id] },
                    onBack = { vm.closeChat() },
                    onSend = { vm.sendChatMessage(it, phoneMode = true) },
                    onSendCard = { vm.sendCard(it) },
                    onOpenGuide = { vm.openPhoneGuide() },
                    onSimulateOfflineReturn = { vm.simulateOfflineReturn() },
                    onOpenMemory = {
                        chatId?.let { vm.openMemory(it) }
                    },
                    onRewind = { vm.rewindToMessage(it) },
                    onRegenerate = { vm.regenerateTurn(it) },
                    onPlayVoice = { id, text -> vm.playVoice(id, text) },
                    playingVoiceId = playingVoiceId,
                    streamDraft = streamDraft,
                    onRetryFailedTurn = { vm.retryFailedTurn() },
                    onHoldStart = holdStartWithPermission,
                    onHoldRelease = { vm.voiceInputRelease() },
                    holdActive = voiceInputActive,
                    recognizing = voiceInputRecognizing,
                    voiceTranscribed = voiceInputTranscribed,
                    onVoiceTranscribedConsumed = { vm.consumeVoiceInputTranscribed() },
                )
            }
        }

        // 13c 说明页(F4 范围 2):手机聊天顶栏「…」进入的全屏覆盖层。
        // 触摸消费(F4 验收 low 修复):全屏容器声明点击即拦截,
        // 长按空白不再穿透到底层聊天页顶栏「…」触发主动消息。
        if (phoneGuideOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* 拦截触摸,不透传 */ },
            ) {
                PhoneChatGuideScreen(onBack = { vm.closePhoneGuide() })
            }
        }

        // 角色主页 04(F5):聊天页左下角「名字 >」进入的全屏覆盖层;
        // 状态条与聊天页同源(MainViewModel openCharacterHome 用同一 StatusRule +
        // observeMessages 流),弹层 09/10 选择即时写回 chatStyle。
        if (characterHomeId != null) {
            Box(Modifier.fillMaxSize()) {
                CharacterHomeScreen(
                    character = characterHomeUi.character,
                    avatarFile = characterHomeUi.character?.let { avatarFiles[it.id] },
                    status = characterHomeUi.status,
                    currentMode = chatStyle.mode,
                    currentLength = chatStyle.length,
                    onSelectMode = { vm.selectDialogMode(it) },
                    onSelectLength = { vm.selectReplyLength(it) },
                    onOpenMemory = {
                        characterHomeId?.let { vm.openMemory(it) }
                    },
                    onOpenInnerVoice = {
                        characterHomeId?.let { vm.openInnerVoices(it) }
                    },
                    onOpenMoments = {
                        characterHomeId?.let { vm.openMoments(it) }
                    },
                    onClose = { vm.closeCharacterHome() },
                    onEditCharacter = {
                        // C 工作包:按会话解析角色行进入编辑表单页(主页可触达)
                        characterHomeId?.let { convId ->
                            vm.editCharacterForConversation(convId)
                        }
                    },
                    onOpenChat = {
                        // C 工作包 C2:主页「聊天」直接进入该角色 IM 会话续聊
                        characterHomeId?.let { convId ->
                            vm.openChatFromCharacterHome(convId)
                        }
                    },
                    innerVoiceCount = characterHomeUi.innerVoiceCount,
                    momentCount = characterHomeUi.momentCount,
                    sidestoryCount = characterHomeUi.sidestoryCount,
                    onOpenSidestory = {
                        characterHomeId?.let { vm.openSidestory(it) }
                    },
                    handbookCount = characterHomeUi.handbookCount,
                    novelCount = characterHomeUi.novelCount,
                    photoCount = characterHomeUi.photoCount,
                    onOpenHandbook = {
                        characterHomeId?.let { vm.openHandbook(it) }
                    },
                    onOpenNovel = {
                        characterHomeId?.let { vm.openNovel(it) }
                    },
                    onOpenPhoto = {
                        characterHomeId?.let { vm.openPhoto(it) }
                    },
                )
            }
        }

        // 心声页 07(F8):角色主页「Ta 的心声」进入的半屏底部弹层覆盖层;
        // 渲染序在角色主页之后 —— 同层后声明者在上(与记忆页验收实锤同款,
        // 保证心声弹层盖在角色主页上面、上半段透出主页)。X/点外/返回 = 关闭。
        if (innerVoiceId != null) {
            Box(Modifier.fillMaxSize()) {
                com.neethu.gouxiang.ui.innervoice.InnerVoiceScreen(
                    state = innerVoiceUi,
                    onGenerate = { vm.generateInnerVoices() },
                    onDismiss = { vm.closeInnerVoices() },
                    onRetryImage = { vm.retryDerivativeImage(com.neethu.gouxiang.chat.DerivativeKind.INNER_VOICE, it) },
                    // G4:角色开关(只门控自动路径)+ 内容管理(删卡)。
                    onToggleAuto = { enabled ->
                        innerVoiceUi.characterId.takeIf { it > 0 }?.let { vm.toggleInnerVoiceAuto(it, enabled) }
                    },
                    onDelete = { vm.deleteInnerVoice(it) },
                )
            }
        }

        // 朋友圈页 06(H1 多角色时间线):角色主页「Ta 的朋友圈」/发现页入口
        // 进入的全屏覆盖层;渲染序在角色主页/心声弹层之后(同层后声明者在上,
        // 保证从主页进入时盖回主页)。返回/‹ = closeMoments()。
        if (momentsId != null) {
            Box(Modifier.fillMaxSize()) {
                com.neethu.gouxiang.ui.moments.MomentsScreen(
                    state = momentsUi,
                    avatarFiles = avatarFiles,
                    onBack = { vm.closeMoments() },
                    onGenerate = {
                        // 全局入口(characterId=0)没有当前角色,按钮也不展示;双保险
                        if (momentsId != null && momentsId!! > 0) vm.generateMoment(momentsId!!)
                    },
                    onPublish = { vm.publishUserMoment(it) },
                    onDeletePost = { vm.deleteUserMoment(it.id) },
                    onDeleteComment = { _, comment -> vm.deleteUserComment(comment.id) },
                    onLike = { post -> vm.toggleMomentLike(post) },
                    onSendComment = { post, text -> vm.sendMomentComment(post, text) },
                    onOpenPermissions = { vm.refreshMomentPermissions() },
                    onTogglePermission = { characterId, field, enabled ->
                        vm.setMomentPermission(characterId, field, enabled)
                    },
                    onRetryImage = { vm.retryDerivativeImage(com.neethu.gouxiang.chat.DerivativeKind.MOMENT, it) },
                )
            }
        }

        // 番外剧场与心动事件页(F 工作包):角色主页「番外剧场」卡 / 完成通知
        // 进入的全屏覆盖层;渲染序在朋友圈页之后(同层后声明者在上,保证盖回
        // 角色主页)。返回 = closeSidestory();详情层为屏内覆盖。
        if (sidestoryId != null) {
            Box(Modifier.fillMaxSize()) {
                com.neethu.gouxiang.ui.sidestory.SidestoryScreen(
                    state = sidestoryUi,
                    onBack = { vm.closeSidestory() },
                    onGenerateTemplate = { vm.generateSidestoryTemplate(it) },
                    onOpenDetail = { vm.openSidestoryDetail(it) },
                    onRetry = { vm.retrySidestory(it) },
                    onDelete = { vm.deleteSidestoryRecord(it) },
                    onCloseDetail = { vm.closeSidestoryDetail() },
                )
            }
        }

        // 手帐页(G 工作包 G1):角色主页「手帐」卡 / 完成通知进入的全屏覆盖层
        // (渲染序在番外页之后,同层后声明者在上)。返回 = closeHandbook()。
        if (handbookId != null) {
            Box(Modifier.fillMaxSize()) {
                com.neethu.gouxiang.ui.derivatives.HandbookScreen(
                    state = handbookUi,
                    onBack = { vm.closeHandbook() },
                    onGenerate = { vm.generateHandbook(it) },
                    onOpenDetail = { vm.openHandbookDetail(it) },
                    onRetry = { vm.retryChatDerivative(it) },
                    onDelete = { vm.deleteChatDerivative(it) },
                    onCloseDetail = { vm.closeHandbookDetail() },
                )
            }
        }

        // 小说页(G 工作包 G2):同层后声明者在上,盖回角色主页。
        if (novelId != null) {
            Box(Modifier.fillMaxSize()) {
                com.neethu.gouxiang.ui.derivatives.NovelScreen(
                    state = novelUi,
                    onBack = { vm.closeNovel() },
                    onGenerate = { vm.generateNovel(it) },
                    onOpenDetail = { vm.openNovelDetail(it) },
                    onRetry = { vm.retryChatDerivative(it) },
                    onDelete = { vm.deleteChatDerivative(it) },
                    onCloseDetail = { vm.closeNovelDetail() },
                )
            }
        }

        // 合照页(G 工作包 G3):描述输入 + 二维生图 + 设为聊天背景。
        if (photoId != null) {
            Box(Modifier.fillMaxSize()) {
                com.neethu.gouxiang.ui.derivatives.PhotoScreen(
                    state = photoUi,
                    description = photoDescription,
                    onDescriptionChange = { vm.onPhotoDescriptionChange(it) },
                    onBack = { vm.closePhoto() },
                    onGenerate = { vm.generatePhoto() },
                    onOpenDetail = { vm.openPhotoDetail(it) },
                    onRetry = { vm.retryChatDerivative(it) },
                    onDelete = { vm.deleteChatDerivative(it) },
                    onSetBackground = { vm.setChatBackground(it) },
                    onClearBackground = { vm.clearChatBackground(photoId) },
                    onCloseDetail = { vm.closePhotoDetail() },
                )
            }
        }

        // 记忆页 05(F3):聊天顶栏「记忆」图标 / 角色主页宫格「Ta 的记忆」进入的
        // 全屏覆盖层。渲染序必须在角色主页 overlay 之后 —— Compose Box 同层
        // 后声明者在上;验收实锤:先声明记忆页时它被角色主页压住,点击宫格
        // "无反应"(实为记忆页已在背后打开)。
        if (memoryId != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF14141A)),
            ) {
                MemoryScreen(
                    state = memoryUi,
                    onBack = { vm.closeMemory() },
                    onSummarizeNow = { vm.summarizeMemoryNow() },
                    onDiscardLegacy = { vm.discardLegacyMemoryWindow() },
                    onEditProfile = { subtype, value -> vm.editMemoryProfile(subtype, value) },
                    onForget = { id -> vm.forgetMemory(id) },
                )
            }
        }

        // 聊天历史页 11(F7):聊天页互动列「历史」(tv_im)进入的全屏覆盖层;
        // 渲染序在语音通话页之前 —— 与记忆页同层级,保证盖回聊天页、被语音
        // 通话页盖住(同层后声明者在上)。返回/X/右下「返回」= closeHistory()。
        if (historyId != null) {
            Box(Modifier.fillMaxSize()) {
                com.neethu.gouxiang.ui.history.ChatHistoryScreen(
                    state = historyUi,
                    onBack = { vm.closeHistory() },
                    onLoadOlder = { vm.loadOlderHistory() },
                )
            }
        }

        // 状态面板(E 工作包 E3):聊天页状态条点击进入的全屏覆盖层;当前状态
        // 卡 + 近期记录时间轴 + 失效记录区(回溯标记)。渲染序与历史页同层级。
        if (statusPanelId != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF14141A)),
            ) {
                com.neethu.gouxiang.ui.status.StatusPanelScreen(
                    state = statusPanelUi,
                    onBack = { vm.closeStatusPanel() },
                    onClearStale = { vm.clearStaleStatuses() },
                )
            }
        }

        // 语音通话页 12(F6):聊天页电话图标进入的全屏覆盖层;渲染序必须在
        // 记忆页之后(同层后声明者在上,保证从聊天页/记忆页退出后盖回聊天页)。
        // 挂断/X = closeVoiceCall() 回聊天页,回合中落库的消息照常可见。
        if (voiceCallId != null) {
            Box(Modifier.fillMaxSize()) {
                com.neethu.gouxiang.ai.speech.VoiceCallScreen(
                    engine = vm.voiceCallEngine(voiceCallId ?: 0L),
                    name = chatUi.character?.name.orEmpty(),
                    avatarFile = chatUi.character?.let { avatarFiles[it.id] },
                    onHangUp = { vm.closeVoiceCall() },
                )
            }
        }

        // 内容生成设置页(§3.3):ProfileScreen 入口进入的全屏覆盖层;渲染序
        // 置底(最后声明者在上,保证盖住其他 overlay)。返回 = closeGenerationSettings()。
        if (generationSettingsOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF14141A)),
            ) {
                com.neethu.gouxiang.work.GenerationSettingsScreen(
                    snapshot = generationSettings,
                    onToggle = settingsToggleWithPermission,
                    onBack = { vm.closeGenerationSettings() },
                )
            }
        }

        // 任务历史页(M0 收尾):ProfileScreen「任务历史」进入的全屏覆盖层;
        // 渲染序与设置页同级置底(最后声明者在上)。返回 = closeTaskHistory()。
        if (taskHistoryOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF14141A)),
            ) {
                com.neethu.gouxiang.work.TaskHistoryScreen(
                    items = taskHistoryItems,
                    loading = taskHistoryLoading,
                    onBack = { vm.closeTaskHistory() },
                    onRetry = { vm.retryTask(it) },
                    onCancel = { vm.cancelTask(it) },
                )
            }
        }

        // 角色编辑页(C 工作包 C1~C3):创作页「新建/编辑」进入的全屏覆盖层;
        // 渲染序置底最后(最后声明者在上,盖住聊天页等所有 overlay —— 保存成功
        // 即关页并直接进入该角色 IM 续聊)。操作提示走一次性 Toast。
        val editorOpen by vm.editorOpen.collectAsState()
        val editorUi by vm.editorUi.collectAsState()
        val editorGallery by vm.editorGallery.collectAsState()
        val editorNotice by vm.editorNotice.collectAsState()
        LaunchedEffect(editorNotice) {
            if (editorNotice.isNotBlank()) {
                android.widget.Toast.makeText(toastContext, editorNotice, android.widget.Toast.LENGTH_SHORT).show()
                vm.consumeEditorNotice()
            }
        }
        LaunchedEffect(editorUi.draftRestored) {
            if (editorUi.draftRestored) {
                android.widget.Toast.makeText(
                    toastContext,
                    com.neethu.gouxiang.ui.character.CharacterFormCopy.DRAFT_RESTORED,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        if (editorOpen) {
            Box(Modifier.fillMaxSize()) {
                com.neethu.gouxiang.ui.character.CharacterEditScreen(
                    ui = editorUi,
                    gallery = editorGallery,
                    currentAvatarFile = avatarFiles[editorUi.editingCharacterId],
                    avatarState = avatarStates[editorUi.editingCharacterId],
                    onField = { field, value -> vm.updateCharacterField(field, value) },
                    onTogglePreview = { vm.toggleEditorPreview(it) },
                    onSave = { vm.saveCharacterFromEditor() },
                    onClose = { vm.closeCharacterEditor() },
                    onRetryAvatar = { vm.retryAvatar(it) },
                    onSetAvatarCurrent = { id, name -> vm.setCharacterAvatar(id, name) },
                    onDeleteAvatar = { id, name -> vm.deleteCharacterAvatar(id, name) },
                    onRefreshGallery = { vm.refreshEditorGallery(it) },
                )
            }
        }
    }
}
