package com.neethu.gouxiang.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.neethu.gouxiang.ui.BubbleItem
import com.neethu.gouxiang.ui.ChatBubble
import com.neethu.gouxiang.ui.ChatUiState
import com.neethu.gouxiang.ui.MainViewModel
import com.neethu.gouxiang.data.ContentCandidateEntity

/** 首页从朗读到会话加载完成期间保持不变的卡片信息。 */
data class HomeChatPresentation(
    val name: String,
    val source: String,
    val intro: String,
    val openingLine: String,
    val imageModel: Any?,
    val openingFinished: Boolean,
)

/**
 * 陪伴聊天主页 03(基准 03-chat-main.png / 03-chat-main.xml):
 *
 * 全屏角色照片底 + 最新消息白气泡(最近一轮贴底)+ 状态条胶囊
 * (emoji + 在做什么,点击占位)+ 左下角「名字 >」+ @来源 + 右侧互动列
 * + 输入条(电话 / 按住说话 / 键盘 / + / 发送)。
 * 消息列表 LazyColumn reverseLayout:新消息自然贴底,历史向上滚。
 *
 * F1 长按操作:气泡 combinedClickable 长按弹「复制/回溯/重新生成」菜单,
 * 与手机聊天页同源(共用 ui.chat.MessageActionsDialog;复制=04 §3.2.F
 * 本地 v1 最小项,回溯=删除该条及之后回到该点,成功 Toast;重新生成仅
 * 角色侧泡提供)。
 */
@Composable
fun ChatMainScreen(
    state: ChatUiState,
    avatarFile: String?,
    /** 合照设为聊天背景(G3/G4):非空时优先于角色立绘展示。 */
    backgroundFile: String? = null,
    onBack: () -> Unit,
    onSend: (String) -> Boolean,
    /** 回合进行中的流式草稿(逐字累积;空串=无草稿)。 */
    streamDraft: String = "",
    /** 顶栏「记忆」入口(F3,临时入口;角色主页宫格后续功能再接)。 */
    onOpenMemory: () -> Unit = {},
    /** 左下角「名字 >」入口(F5:角色主页 04)。 */
    onOpenCharacterHome: () -> Unit = {},
    /** 输入条电话图标(F6:进入语音通话页 12)。 */
    onOpenVoiceCall: () -> Unit = {},
    /** 右侧互动列末位「历史」(F7:聊天历史页 11,对照 11-history.xml tv_im)。 */
    onOpenHistory: () -> Unit = {},
    /** 状态条点击(E 工作包 E3:进入「TA 的状态」面板,查看近期状态与来源)。 */
    onOpenStatusPanel: () -> Unit = {},
    /** 失败回合重试条点击(02 文档 §3.5:IM 用户回合失败也显式出条,按原文本重试)。 */
    onRetryFailedTurn: () -> Unit = {},
    /** 长按「回溯」确认后回调(删除该条及之后消息;F1 与手机聊天页同款 VM 出口)。 */
    onRewind: (messageId: Long) -> Unit = {},
    /** 长按「重新生成」回调(仅角色侧泡;F1 与手机聊天页同款 VM 出口)。 */
    onRegenerate: (turnId: String) -> Unit = {},
    // ── 「+」扩展面板(对照猫箱聊天加号面板,复用现有页面/VM 出口)──
    /** 面板「回溯」:一键回溯上一轮(定位最后一条角色回复删到该点,VM 兜底)。 */
    onRewindLastTurn: () -> Unit = {},
    /** 面板「发送图片」:相册选图回调(VM 压缩落盘 + kind=image 回合)。 */
    onSendImage: (android.net.Uri) -> Unit = {},
    /** 面板「心动指令」:番外剧场·心动事件页(复用现有心动事件管线)。 */
    onOpenHeartCommands: () -> Unit = {},
    /** 面板「聊天转小说」:跳小说列表页(不直接生成,入口语义)。 */
    onOpenNovel: () -> Unit = {},
    /** 面板「手机聊天」:同一会话切手机形态皮肤。 */
    onOpenPhoneChat: () -> Unit = {},
    /** 面板「拍合照」:跳合照页(AI 合照 + 设为聊天背景)。 */
    onOpenPhoto: () -> Unit = {},
    /** 面板「聊天转手账」:跳手账列表页(不直接生成,入口语义)。 */
    onOpenHandbook: () -> Unit = {},
    /** 按住说话:按下开录 / 松手按手势去向分发(原地=直接发送,上划=取消,侧移=动作场景输入)。 */
    onHoldStart: () -> Unit = {},
    onHoldRelease: (MainViewModel.VoiceInputAction) -> Unit = {},
    /** 按住中原位/侧移互切的段边界(2026-10-31 口径「话一(动作)话二」分段录音)。 */
    onHoldModeSwitch: (Boolean) -> Unit = {},
    /** 录音实时音量(0..1;按住中的波形条)。 */
    voiceLevel: Float = 0f,
    recognizing: Boolean = false,
    /** 转写结果(非空=待填入草稿框;消费后由调用方清空)。 */
    voiceTranscribed: String = "",
    /** F4 转写填入草稿后回调(VM 清事件)。 */
    onVoiceTranscribedConsumed: () -> Unit = {},
    /** 普通聊天回复的自动朗读开关。 */
    soundEnabled: Boolean = true,
    onToggleSound: () -> Unit = {},
    contentCandidates: List<ContentCandidateEntity> = emptyList(),
    onCandidateSeen: (Long) -> Unit = {},
    onCandidateDismiss: (Long) -> Unit = {},
    onCandidateOpen: (Long) -> Unit = {},
    /** 首页外层底栏已处理系统导航栏的安全间距。 */
    embeddedInHome: Boolean = false,
    homePresentation: HomeChatPresentation? = null,
    onNextCharacter: (() -> Unit)? = null,
    nextCharacterEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = !embeddedInHome) { onBack() }
    // 长按操作菜单(F1):目标消息 id → 弹窗;回溯二次确认目标点名防误触。
    var longPressTarget by remember { mutableStateOf<Long?>(null) }
    var rewindTarget by remember { mutableStateOf<Long?>(null) }
    var rewindTargetText by remember { mutableStateOf("") }
    // 「+」扩展面板开关 + 一键回溯的二次确认目标(面板收起后弹确认,点名
    // 最后一条角色回复的开头,与长按回溯同一 RewindConfirmDialog)。
    var panelOpen by remember { mutableStateOf(false) }
    var rewindLastTargetText by remember { mutableStateOf<String?>(null) }
    // 相册选图(系统 Photo Picker,免存储权限);选中即收面板交 VM 落盘。
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            panelOpen = false
            onSendImage(uri)
        }
    }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Box(modifier = modifier.fillMaxSize()) {
        // ── 全屏角色照片底(生成缓存命中显示真图,未生成显示渐变占位)──
        // G3/G4:合照页「设为聊天背景」的会话优先展示合照(backgroundFile 非
        // 空且文件仍存在;记录删除后文件失效,由 ChatBackgroundPrefs 校验回落)。
        val effectiveBackground: Any? = if (homePresentation != null) homePresentation.imageModel
        else (backgroundFile?.takeIf { java.io.File(it).isFile } ?: avatarFile)?.let { java.io.File(it) }
        if (effectiveBackground != null) {
            AsyncImage(
                model = effectiveBackground,
                contentDescription = homePresentation?.name ?: state.character?.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF565866), Color(0xFF2C2C34), Color(0xFF1A1A20)),
                        ),
                    ),
            )
        }
        // 底部渐变遮罩(气泡/信息区可读性,对齐参考图的压暗)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.45f),
                    ),
                ),
        )
        // 左侧竖排「由 AI 生成」(与首页同款)
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp),
        ) {
            "由 AI 生成".forEach { ch ->
                Text(ch.toString(), color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
            }
        }

        Column(Modifier.fillMaxSize()) {
            if (embeddedInHome) {
                // 首页顶栏由外层持续绘制，避免开场白结束时切换成另一套顶栏。
                Spacer(Modifier.statusBarsPadding().height(56.dp))
            } else TopBar(
                onBack = onBack,
                onOpenMemory = onOpenMemory,
                soundEnabled = soundEnabled,
                onToggleSound = onToggleSound,
                onNextCharacter = onNextCharacter,
                nextCharacterEnabled = nextCharacterEnabled,
            )
            val opening = homePresentation
            val showOpening = opening != null &&
                (!opening.openingFinished || state.loading || state.character == null)
            // 首次进入时将消息限制在画面下部；较长的开场白仍可滚动查看。
            val compactHomeMessages = opening != null &&
                (showOpening || (!state.loading && state.bubbleItems.none { it.fromUser }))
            val intro = opening?.intro ?: state.character?.intro.orEmpty()
            val showIntro = opening?.openingFinished != false &&
                (showOpening || (!state.loading && !state.loadFailed && state.bubbleItems.none { it.fromUser })) &&
                intro.isNotBlank()
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                Column(
                    Modifier.fillMaxWidth().then(
                        if (compactHomeMessages) Modifier.heightIn(max = 220.dp) else Modifier.fillMaxSize(),
                    ),
                ) {
                    if (compactHomeMessages && showIntro) {
                        CharacterIntro(intro, Modifier.padding(horizontal = 12.dp))
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        // 开场白从首字开始阅读；普通会话仍按最新消息贴底。
                        reverseLayout = !compactHomeMessages,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp,
                        ),
                        // 不足一屏的消息贴近人物信息行。
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                    ) {
                        if (showOpening) {
                            item(key = "home-opening") {
                                Box(
                                    Modifier.widthIn(max = 280.dp)
                                        .then(if (opening.openingFinished) Modifier.background(
                                            Color.White,
                                            RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
                                        ) else Modifier)
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                ) {
                                    Text(
                                        opening.openingLine,
                                        color = if (opening.openingFinished) Color(0xFF1A1A1A) else Color.White,
                                        fontSize = 17.sp,
                                        lineHeight = 26.sp,
                                    )
                                }
                            }
                        } else {
                            if (!compactHomeMessages && state.sending) item(key = "typing") { TypingHint(streamDraft) }
                            if (!compactHomeMessages && state.failedTurn != null) {
                                item(key = "failed-turn") { FailedTurnBar(onRetry = onRetryFailedTurn) }
                            }
                            items(if (compactHomeMessages) state.bubbleItems else state.bubbleItems.asReversed()) { group ->
                                BubbleGroup(group = group, onLongPress = { longPressTarget = it })
                            }
                            if (compactHomeMessages && state.failedTurn != null) {
                                item(key = "failed-turn") { FailedTurnBar(onRetry = onRetryFailedTurn) }
                            }
                            if (compactHomeMessages && state.sending) item(key = "typing") { TypingHint(streamDraft) }
                        }
                        if (!compactHomeMessages && showIntro) {
                            item(key = "character-intro") {
                                CharacterIntro(intro)
                            }
                        }
                        if (!showOpening && state.loadFailed) {
                            item(key = "failed") {
                                Text("会话不存在", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
            // ⚡ 灵感圆钮(气泡右侧,对齐参考图右缘)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ContentCandidateReminder(
                    conversationId = state.conversationId,
                    candidates = contentCandidates,
                    canShow = !state.sending && !showOpening && !state.loading,
                    onSeen = onCandidateSeen,
                    onDismiss = onCandidateDismiss,
                    onOpen = onCandidateOpen,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(34.dp)
                        .background(Color(0xFF3A3A3A).copy(alpha = 0.85f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⚡", fontSize = 15.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            // 首页需要保持人物行固定；状态条只在独立聊天页展示。
            if (!embeddedInHome) {
                StatusPill(state.status, onClick = onOpenStatusPanel)
            }
            Spacer(Modifier.height(10.dp))
            NameAndActionsRow(
                name = homePresentation?.name ?: state.character?.name.orEmpty(),
                source = homePresentation?.source ?: state.character?.source.orEmpty(),
                onNameClick = onOpenCharacterHome,
                onOpenHistory = onOpenHistory,
                showDecorativeActions = !embeddedInHome,
            )
            Spacer(Modifier.height(12.dp))
            InputBar(
                sending = state.sending,
                onSend = onSend,
                onOpenVoiceCall = onOpenVoiceCall,
                onTogglePanel = { panelOpen = !panelOpen },
                onHoldStart = onHoldStart,
                onHoldRelease = onHoldRelease,
                onHoldModeSwitch = onHoldModeSwitch,
                voiceLevel = voiceLevel,
                recognizing = recognizing,
                voiceTranscribed = voiceTranscribed,
                onVoiceTranscribedConsumed = onVoiceTranscribedConsumed,
                embeddedInHome = embeddedInHome,
                enabled = homePresentation == null || (homePresentation.openingFinished && !state.loading && state.character != null),
            )
        }

        // ── F1 长按操作弹窗(与手机聊天页共用 ui.chat 组件,行为同源)──
        longPressTarget?.let { msgId ->
            val found = state.bubbleItems
                .flatMap { group -> group.bubbles.map { group.fromUser to it } }
                .firstOrNull { it.second.id == msgId }
            if (found == null) {
                longPressTarget = null
            } else {
                val (fromUser, item) = found
                MessageActionsDialog(
                    fromUser = fromUser,
                    targetText = rewindSnippetShared(item.content),
                    onDismiss = { longPressTarget = null },
                    onCopy = {
                        clipboard.setText(AnnotatedString(item.content))
                        android.widget.Toast.makeText(
                            context,
                            "已复制",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        longPressTarget = null
                    },
                    onRewind = {
                        rewindTarget = item.id
                        rewindTargetText = rewindSnippetShared(item.content)
                        longPressTarget = null
                    },
                    onRegenerate = {
                        if (!fromUser) onRegenerate(item.turnId)
                        longPressTarget = null
                    },
                )
            }
        }
        rewindTarget?.let { msgId ->
            RewindConfirmDialog(
                targetText = rewindTargetText,
                onDismiss = { rewindTarget = null },
                onConfirm = {
                    onRewind(msgId)
                    rewindTarget = null
                    android.widget.Toast.makeText(
                        context,
                        "因为回溯，聊天记录已发生变化",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }

        // ── 「+」扩展面板(对照猫箱聊天加号面板)──
        if (panelOpen) {
            ChatPlusPanel(
                enabled = state.bubbleItems.any { !it.fromUser },
                onRewind = {
                    panelOpen = false
                    // 点名最后一条角色回复的开头(与长按回溯同一确认弹窗);
                    // 无角色回复时提示,不弹空确认。
                    val lastAssistantText = state.bubbleItems
                        .lastOrNull { !it.fromUser }?.bubbles?.lastOrNull()?.content
                    if (lastAssistantText.isNullOrBlank()) {
                        android.widget.Toast.makeText(
                            context,
                            "还没有可回溯的回复",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        rewindLastTargetText = rewindSnippetShared(lastAssistantText)
                    }
                },
                onPickImage = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onOpenHeartCommands = {
                    panelOpen = false
                    onOpenHeartCommands()
                },
                onOpenNovel = {
                    panelOpen = false
                    onOpenNovel()
                },
                onOpenPhoneChat = {
                    panelOpen = false
                    onOpenPhoneChat()
                },
                onOpenPhoto = {
                    panelOpen = false
                    onOpenPhoto()
                },
                onOpenHandbook = {
                    panelOpen = false
                    onOpenHandbook()
                },
                onDismiss = { panelOpen = false },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        // 面板「回溯」二次确认(与长按回溯同款弹窗,点名目标防误触)
        rewindLastTargetText?.let { targetText ->
            RewindConfirmDialog(
                targetText = targetText,
                onDismiss = { rewindLastTargetText = null },
                onConfirm = {
                    onRewindLastTurn()
                    rewindLastTargetText = null
                    android.widget.Toast.makeText(
                        context,
                        "因为回溯，聊天记录已发生变化",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }
    }
}

/** 顶部:返回 + V 盾 + 右侧搜索/喇叭(沉浸式叠在图上)。 */
@Composable
private fun TopBar(
    onBack: () -> Unit,
    onOpenMemory: () -> Unit = {},
    soundEnabled: Boolean,
    onToggleSound: () -> Unit,
    onNextCharacter: (() -> Unit)? = null,
    nextCharacterEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.ArrowBackIosNew,
            contentDescription = "返回",
            tint = Color.White,
            modifier = Modifier
                .size(22.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onBack() },
        )
        Spacer(Modifier.width(18.dp))
        Icon(
            Icons.Filled.Favorite,
            contentDescription = "装饰",
            tint = Color(0xFFFFC94D),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.weight(1f))
        if (onNextCharacter != null) {
            Text(
                "换人物",
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .clickable(enabled = nextCharacterEnabled, onClick = onNextCharacter)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                color = Color.White.copy(alpha = if (nextCharacterEnabled) 1f else 0.45f),
                fontSize = 14.sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(12.dp))
        }
        // 记忆入口(F3,临时入口:角色主页宫格后续功能再接)
        Icon(
            Icons.Outlined.Psychology,
            contentDescription = "记忆",
            tint = Color.White,
            modifier = Modifier
                .size(24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onOpenMemory() },
        )
        Spacer(Modifier.width(16.dp))
        Icon(
            Icons.Outlined.Search,
            contentDescription = "搜索",
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        IconToggleButton(checked = soundEnabled, onCheckedChange = { onToggleSound() }) {
            Icon(
                if (soundEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                contentDescription = if (soundEnabled) "关闭朗读" else "开启朗读",
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * 状态条胶囊:「emoji + 在做什么 ›」(点击进入状态面板,E3)。
 * emoji/text 分字段渲染,绝不按 UTF-16 位切串 —— 实机 F2 验收证据:
 * take(1) 把 🍔(U+1F354) 切成未配对代理 U+D83C,uiautomator dump 的
 * KXmlSerializer 抛 IllegalArgumentException(U+d83c) 且渲染成「?◆」。
 */
@Composable
private fun StatusPill(status: com.neethu.gouxiang.chat.StatusLine, onClick: () -> Unit = {}) {
    if (status.emoji.isEmpty() && status.text.isEmpty()) return
    Row(
        modifier = Modifier
            .padding(start = 14.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status.emoji.isNotEmpty()) {
            Text(status.emoji, fontSize = 15.sp)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            status.text,
            color = Color(0xFFF2C94C),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(4.dp))
        Text("›", color = Color(0xFFF2C94C), fontSize = 14.sp)
    }
}

/**
 * 左下角「名字 >」+ @来源 + 右侧互动列(点赞/分享/评论/历史)。
 * dump 11-history:互动列最后一项 message_im_view 内 tv_im=「历史」,
 * 点击进聊天历史页(参考页里该项文案为「返回」,本地版按 03 的「历史」进页)。
 */
@Composable
internal fun NameAndActionsRow(
    name: String,
    source: String,
    onNameClick: () -> Unit,
    onOpenHistory: (() -> Unit)? = null,
    showDecorativeActions: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onNameClick() },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    color = Color.White,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(4.dp))
                Text("›", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "@$source",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 13.sp,
            )
        }
        if (showDecorativeActions) {
            ActionItem(Icons.Filled.Favorite, "4.7w")
            ActionItem(Icons.Filled.Share, "727")
            ActionItem(Icons.Filled.ChatBubble, "2791")
        }
        if (onOpenHistory != null) ActionItem(Icons.Filled.History, "历史", onClick = onOpenHistory)
    }
}

@Composable
private fun ActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(start = 16.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

// ── 消息气泡 ────────────────────────────────────────────────────────

@Composable
private fun CharacterIntro(intro: String, modifier: Modifier = Modifier) {
    Text(
        "介绍: $intro",
        modifier = modifier.fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        color = Color.White,
        fontSize = 14.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 一组连发气泡:assistant 白泡靠左,用户粉泡靠右。 */
@Composable
private fun BubbleGroup(group: BubbleItem, onLongPress: (Long) -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (group.fromUser) Alignment.End else Alignment.Start,
    ) {
        // 离线回归分隔(D 工作包 D4):进页消费的主动批次组上方居中灰字
        group.separator?.let { OfflineSeparator(it) }
        group.bubbles.forEachIndexed { index, bubble ->
            Spacer(Modifier.height(if (index == 0) 0.dp else 6.dp))
            SingleBubble(bubble, fromUser = group.fromUser, onLongPress = onLongPress)
        }
    }
}

/** 离线分隔条(居中灰字,句式同通知「离线期间发来了 N 条消息」)。 */
@Composable
internal fun OfflineSeparator(text: String) {
    Text(
        text = text,
        color = Color(0xFF9A9A9A),
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .wrapContentWidth(Alignment.CenterHorizontally),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SingleBubble(bubble: ChatBubble, fromUser: Boolean, onLongPress: (Long) -> Unit) {
    val shape = RoundedCornerShape(
        topStart = 4.dp,
        topEnd = 18.dp,
        bottomStart = 18.dp,
        bottomEnd = 18.dp,
    )
    val bg = if (fromUser) Color(0xFFE84D8A) else Color.White
    val longPress: (() -> Unit)? = bubble.id?.let { id -> ({ onLongPress(id) }) }
    Box(
        Modifier
            .widthIn(max = 280.dp)
            .background(bg, shape)
            // F1 长按操作:与手机聊天页同款 combinedClickable(单击无动作,
            // 长按弹复制/回溯/重新生成菜单);id 为空(测试构造)不可长按。
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = { longPress?.invoke() },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        when {
            // 存量表情行(2026-10-31 前落库)降级为括号灰字动作,与括号口径一致,
            // 普通聊天页不再出现表情占位卡
            bubble.kind == "sticker" ->
                TextContent(bubble.copy(actions = listOf(bubble.content), content = ""), fromUser)
            bubble.kind == "img" -> ImageContent(bubble.content)
            // 用户真实选图泡(「+」面板发送):mediaPath 为本地持久文件;
            // 路径缺失(文件被清/存量异常)回落括号文本不崩
            bubble.kind == "image" && bubble.mediaPath.isNotBlank() ->
                ImageBubbleContent(bubble.mediaPath)
            bubble.kind == "voice" -> VoiceContent(bubble.content, fromUser)
            else -> TextContent(bubble, fromUser)
        }
    }
}

/** 文本泡:() 动作以淡色斜体内联(对齐参考图「(靠在你肩头…)」的观感)。 */
@Composable
private fun TextContent(bubble: ChatBubble, fromUser: Boolean) {
    val actionStyle = SpanStyle(
        fontStyle = FontStyle.Italic,
        color = if (fromUser) Color.White.copy(alpha = 0.75f) else Color(0xFF9A9A9A),
    )
    val bodyColor = if (fromUser) Color.White else Color(0xFF1A1A1A)
    val annotated = buildAnnotatedString {
        bubble.actions.forEach { action ->
            withStyle(actionStyle) { append("($action)") }
        }
        append(bubble.content)
    }
    Text(
        annotated,
        color = bodyColor,
        fontSize = 17.sp,
        lineHeight = 26.sp,
    )
}

/** 图片占位卡:🖼 + 描述(生图接入为后续功能)。 */
@Composable
private fun ImageContent(desc: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🖼️", fontSize = 34.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            desc,
            color = Color(0xFF9A9A9A),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 用户真实选图泡:「+」面板发送,本地文件经压缩落盘后 Coil 直读。 */
@Composable
private fun ImageBubbleContent(path: String) {
    AsyncImage(
        model = java.io.File(path),
        contentDescription = "图片",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(180.dp)
            .clip(RoundedCornerShape(10.dp)),
    )
}

/** 语音条占位:麦克风 + 转写文本(语音合成/播放为后续功能)。 */
@Composable
private fun VoiceContent(text: String, fromUser: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("🎤", fontSize = 20.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            color = if (fromUser) Color.White else Color(0xFF1A1A1A),
            fontSize = 16.sp,
            fontStyle = FontStyle.Italic,
        )
    }
}

// ── 输入条 ──────────────────────────────────────────────────────────

/**
 * 输入条:电话图标 / 按住说话(语音态,真录音) / 键盘 / + / 发送。
 * 键盘图标切换 语音 ↔ 文字 两种形态;文字态尾部出粉色发送圆钮。
 * 「按住说话」胶囊(2026-11-04)抽为共享组件 [HoldToTalkPill](手机聊天页
 * 无侧移版同款复用),手势口径见其注释:
 *
 * - 原地松手 = ASR 转写直接发送(不再先落草稿再手动发);
 * - 按住中按钮变黑色胶囊 + 实时波形,上方浮小字提示;上划出按钮 = 取消;
 * - 侧移 = 输入态,松手把语音转成「(动作)」括号文本填输入框可改再发;
 * - 轻点/识别失败落「没听清」提示;识别中防重入。
 * [sending] = 回合进行中:发送钮禁用(不丢弃草稿;发送被接受才清空 draft,
 * 与手机聊天页输入条同语义,避免 ViewModel sending 拦截静默吞掉草稿)。
 */
@Composable
internal fun InputBar(
    sending: Boolean,
    onSend: (String) -> Boolean,
    onOpenVoiceCall: () -> Unit = {},
    /** 「+」扩展面板开关(猫箱加号面板:功能宫格悬于输入条上方)。 */
    onTogglePanel: () -> Unit = {},
    onHoldStart: () -> Unit = {},
    onHoldRelease: (MainViewModel.VoiceInputAction) -> Unit = {},
    onHoldModeSwitch: (Boolean) -> Unit = {},
    voiceLevel: Float = 0f,
    recognizing: Boolean = false,
    voiceTranscribed: String = "",
    onVoiceTranscribedConsumed: () -> Unit = {},
    embeddedInHome: Boolean = false,
    enabled: Boolean = true,
) {
    var textMode by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    // 转写落草稿仅剩回合占用回落路径(2026-10-31 口径:原地/侧移松手都直发
    // 并保持语音模式,只有发不进去才回落填输入框):切到文字态可见可改;
    // 填完即消费事件,再次按住说话不重复填。
    LaunchedEffect(voiceTranscribed) {
        if (voiceTranscribed.isNotBlank()) {
            textMode = true
            draft = voiceTranscribed
            onVoiceTranscribedConsumed()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (embeddedInHome) Modifier else Modifier.navigationBarsPadding())
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(Color(0xFFF3F4F6).copy(alpha = 0.94f), RoundedCornerShape(26.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Call,
            contentDescription = "语音通话",
            tint = Color(0xFF3A3A3A),
            modifier = Modifier
                .size(24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                ) { onOpenVoiceCall() },
        )
        if (textMode) {
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = Color(0xFF1A1A1A),
                    fontSize = 16.sp,
                ),
                cursorBrush = SolidColor(Color(0xFFE84D8A)),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) {
                            Text("说点什么…", color = Color(0xFF9A9A9A), fontSize = 16.sp)
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.width(10.dp))
            // 「按住说话」共享胶囊(2026-11-04 抽出,手机聊天页无侧移版同款复用):
            // 按下开录,拖动按位置实时判定手势态,松手按最终态分发(onHoldRelease)。
            HoldToTalkPill(
                modifier = Modifier.weight(1f),
                enabled = enabled,
                onHoldStart = onHoldStart,
                onHoldRelease = onHoldRelease,
                onHoldModeSwitch = onHoldModeSwitch,
                voiceLevel = voiceLevel,
                recognizing = recognizing,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Outlined.Keyboard,
            contentDescription = if (textMode) "语音输入" else "文字输入",
            tint = Color(0xFF3A3A3A),
            modifier = Modifier
                .size(26.dp)
                .clickable(enabled = enabled) { textMode = !textMode },
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) { onTogglePanel() },
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .background(Color(0xFF2A2A2A), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "更多",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            // 红点角标(参考图)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(8.dp)
                    .background(Color(0xFFFF3B30), CircleShape),
            )
        }
        if (textMode) {
            Spacer(Modifier.width(10.dp))
            // 发送中不丢草稿:发送中禁用而非丢弃草稿;仅在点击被接受时清空。
            Box(
                Modifier
                    .size(36.dp)
                    .background(
                        if (draft.isNotBlank() && !sending) Color(0xFFE84D8A) else Color(0xFFD9D9D9),
                        CircleShape,
                    )
                    .clickable(enabled = !sending) {
                        if (draft.isNotBlank()) {
                            if (onSend(draft)) draft = ""
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * 「+」扩展面板(对照猫箱聊天加号面板):全屏暗 scrim + 输入条上方功能宫格。
 * 七格两行(4+3):回溯 / 发送图片 / 心动指令 / 聊天转小说 + 手机聊天 / 拍合照 /
 * 聊天转手账 —— 全部复用既有页面与 VM 出口(转手账/小说=跳列表页不直接生成);
 * 手机页 ExtPanel 同款交互范式(条件渲染,点空白收起)。
 */
@Composable
private fun ChatPlusPanel(
    /** 无角色回复时「回溯」点击侧提示不弹确认(列表有无 assistant 组)。 */
    enabled: Boolean,
    onRewind: () -> Unit,
    onPickImage: () -> Unit,
    onOpenHeartCommands: () -> Unit,
    onOpenNovel: () -> Unit,
    onOpenPhoneChat: () -> Unit,
    onOpenPhoto: () -> Unit,
    onOpenHandbook: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        // 点击空白收起(全屏 scrim,盖住输入条与消息区)
        Box(
            Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 84.dp)
                .background(
                    Color(0xFF3A4254).copy(alpha = 0.97f),
                    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                )
                .padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            Row {
                PlusTile(Icons.Filled.History, "回溯", Modifier.weight(1f)) { onRewind() }
                Spacer(Modifier.width(10.dp))
                PlusTile(Icons.Outlined.Image, "发送图片", Modifier.weight(1f)) { onPickImage() }
                Spacer(Modifier.width(10.dp))
                PlusTile(Icons.Filled.Favorite, "心动指令", Modifier.weight(1f)) { onOpenHeartCommands() }
                Spacer(Modifier.width(10.dp))
                PlusTile(Icons.AutoMirrored.Filled.MenuBook, "聊天转小说", Modifier.weight(1f)) { onOpenNovel() }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                PlusTile(Icons.Outlined.Smartphone, "手机聊天", Modifier.weight(1f)) { onOpenPhoneChat() }
                Spacer(Modifier.width(10.dp))
                PlusTile(Icons.Outlined.PhotoCamera, "拍合照", Modifier.weight(1f)) { onOpenPhoto() }
                Spacer(Modifier.width(10.dp))
                PlusTile(Icons.Filled.Bookmarks, "聊天转手账", Modifier.weight(1f)) { onOpenHandbook() }
                Spacer(Modifier.weight(1f))
            }
            // 回溯可用性小注(无角色回复时点了只会 Toast,提前透明告知)
            if (!enabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "还没有可回溯的回复",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** 面板宫格单卡:半透明蓝灰圆角块 + 白图标 + 白标签(对齐猫箱加号面板观感)。 */
@Composable
private fun PlusTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(vertical = 6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.88f), fontSize = 12.sp)
    }
}

/**
 * 回合进行中:流式打字泡。草稿非空时展示实时累积的回复内容(可见逐字流式),
 * 末尾带闪烁光标;首字到达前退化为「正在输入…」。渲染在列表底部=reverseLayout 首项。
 *
 * 样式与正式回复泡(SingleBubble 文本态)完全同源:同圆角/同底色/同内边距/
 * 同字号行高 —— 流式结束后草稿泡被正式泡原位替换时无字号跳变(猫箱口径,
 * 用户验收 2026-10-31)。尾部预览按码点截,防把代理对劈成未配对高位代理。
 */@Composable
private fun TypingHint(streamDraft: String) {
    val draft = streamDraft.takeLastCodePoints(200)
    Row(
        modifier = Modifier.padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .background(Color.White, RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (draft.isEmpty()) {
                Text("正在输入…", color = Color(0xFF9A9A9A), fontSize = 14.sp)
            } else {
                Text(
                    buildAnnotatedString {
                        append(draft)
                        withStyle(SpanStyle(color = Color(0xFFE84D8A))) { append("▌") }
                    },
                    color = Color(0xFF1A1A1A),
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                )
            }
        }
    }
}

/** 回合失败条(02 文档 §3.5):「消息发送失败,请点击重试」,点击整行触发重试(暗底配色)。 */
@Composable
private fun FailedTurnBar(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRetry)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("消息发送失败，请点击重试", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
    }
}

/** 按 Unicode 码点取尾部(不劈代理对)。 */
private fun String.takeLastCodePoints(n: Int): String {
    if (length <= n) return this
    val pts = codePoints().toArray()
    return String(pts, (pts.size - n).coerceAtLeast(0), n)
}
