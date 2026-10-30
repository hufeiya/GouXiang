package com.neethu.gouxiang

import android.os.Bundle
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
import com.neethu.gouxiang.ui.GouXiangBottomBar
import com.neethu.gouxiang.ui.MainViewModel
import com.neethu.gouxiang.ui.PlaceholderIcons
import com.neethu.gouxiang.ui.PlaceholderPage
import com.neethu.gouxiang.ui.character.CharacterHomeScreen
import com.neethu.gouxiang.ui.home.HomeFeedScreen
import com.neethu.gouxiang.ui.chat.ChatMainScreen
import com.neethu.gouxiang.ui.messages.MessagesScreen
import com.neethu.gouxiang.ui.memory.MemoryScreen
import com.neethu.gouxiang.ui.nav.BottomTab
import com.neethu.gouxiang.ui.phone.PhoneChatGuideScreen
import com.neethu.gouxiang.ui.phone.PhoneChatScreen
import com.neethu.gouxiang.ui.theme.GouXiangTheme

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
    }

    @Composable
    private fun MainFrame(vm: MainViewModel = viewModel()) {
        val tab by vm.selectedTab.collectAsState()
        val feed by vm.homeFeed.collectAsState()
        val conversations by vm.conversations.collectAsState()
        val avatarFiles by vm.avatarFiles.collectAsState()
        val chatId by vm.chatConversationId.collectAsState()
        val chatUi by vm.chatUi.collectAsState()
        val streamDraft by vm.streamDraft.collectAsState()
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
        // 对话风格(F5:弹层 09/10 的当前选择)
        val chatStyle by vm.chatStyle.collectAsState()
        // 语音通话 12(F6)
        val voiceCallId by vm.voiceCallConversationId.collectAsState()
        // 聊天历史页 11(F7)
        val historyId by vm.historyConversationId.collectAsState()
        val historyUi by vm.historyUi.collectAsState()

        // 角色头像:一次生成缓存本地(火山→硅基→占位兜底在 provider 内)
        LaunchedEffect(feed) {
            val need = feed.map { it.character }
            if (need.isNotEmpty()) vm.ensureAvatars(need)
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
                    )
                    BottomTab.DISCOVER -> PlaceholderPage("发现", PlaceholderIcons.Discover)
                    BottomTab.CREATE -> PlaceholderPage("创作AI+", PlaceholderIcons.Create)
                    BottomTab.MESSAGES -> MessagesScreen(
                        rows = conversations,
                        avatarFiles = avatarFiles,
                        onOpenChat = { vm.openChat(it) },
                        onOpenPhoneChat = { vm.openChat(it, phoneMode = true) },
                    )
                    BottomTab.PROFILE -> PlaceholderPage("我的", PlaceholderIcons.Profile)
                }
            }
        }

        // 聊天主页 03:全屏覆盖层(自管边缘到边缘,盖住底栏;返回回消息列表)
        if (chatId != null && !isPhoneChat) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF101014)),
            ) {
                ChatMainScreen(
                    state = chatUi,
                    avatarFile = chatUi.character?.let { avatarFiles[it.id] },
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
                    innerVoiceCount = characterHomeUi.innerVoiceCount,
                    momentCount = characterHomeUi.momentCount,
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
                )
            }
        }

        // 朋友圈页 06(F9):角色主页「Ta 的朋友圈」进入的全屏覆盖层;
        // 渲染序在角色主页/心声弹层之后(同层后声明者在上,保证从主页
        // 进入时盖回主页)。返回/‹ = closeMoments()。
        if (momentsId != null) {
            Box(Modifier.fillMaxSize()) {
                com.neethu.gouxiang.ui.moments.MomentsScreen(
                    state = momentsUi,
                    avatarFile = avatarFiles[momentsUi.characterId],
                    onBack = { vm.closeMoments() },
                    onGenerate = { momentsId?.let { vm.generateMoment(it) } },
                    onLike = { post -> vm.toggleMomentLike(post) },
                    onSendComment = { post, text -> vm.sendMomentComment(post, text) },
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
    }
}
