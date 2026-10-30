package com.neethu.gouxiang.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.neethu.gouxiang.protocol.CardPayload
import com.neethu.gouxiang.protocol.PhoneCardKind
import com.neethu.gouxiang.protocol.voiceDurationLabel
import com.neethu.gouxiang.ui.BubbleItem
import com.neethu.gouxiang.ui.ChatBubble

/**
 * 手机聊天页 14(基准 14-phone-chat.png / 14-phone-chat.xml,iMessage 风):
 *
 * - 顶栏:✕ + 名字 +「由 AI 生成」+ 记忆图标(记忆页 05)+ 右侧「…」(进 13c 说明页;长按 = 调试
 *   「模拟离线回归」主动消息,范围 5 的验收入口);
 * - 消息流:左侧白泡(每组首泡带头像)+ 右侧黄泡;语音条「•)N″」点击 TTS
 *   播放(TTS 不可用保持占位);卡片按 13b 六型渲染;长按即时弹复制/回溯/重新生成
 *   (复制=04 §3.2.F 本地 v1 最小项;回溯=删除该条及之后消息回到该点,成功后
 *   Toast 提示记录已变化);新消息到达自动滚到底部;
 * - 输入条:自由输入 + 😊 + 🎤 + 「+」(扩展面板 13b 六宫格)。
 * - dump 对照:new_tv_name(名字)/new_iv_ai_generated(「由 AI 生成」)/
 *   edit_view(hint=自由输入...)/entrance_sticker/voice_mode/ext_panel。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhoneChatScreen(
    state: com.neethu.gouxiang.ui.ChatUiState,
    avatarFile: String?,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onSendCard: (CardPayload) -> Unit,
    onOpenGuide: () -> Unit,
    onSimulateOfflineReturn: () -> Unit,
    onOpenMemory: () -> Unit = {},
    onRewind: (messageId: Long) -> Unit,
    onRegenerate: (turnId: String) -> Unit,
    onPlayVoice: (messageId: Long, text: String) -> Unit,
    playingVoiceId: Long? = null,
    streamDraft: String = "",
    /** 失败回合重试条点击(02 文档 §3.5:按原用户文本重跑回复侧)。 */
    onRetryFailedTurn: () -> Unit = {},
    /** F4 语音输入:🎤 按下开录 / 松手转写(转写结果填草稿,不直接发送)。 */
    onHoldStart: () -> Unit = {},
    onHoldRelease: () -> Unit = {},
    /** F4 语音输入状态:按住录音中。 */
    holdActive: Boolean = false,
    /** F4 语音输入状态:松手识别中。 */
    recognizing: Boolean = false,
    /** F4 转写结果(非空=待填入草稿框;消费后由调用方清空)。 */
    voiceTranscribed: String = "",
    /** F4 转写填入草稿后回调(VM 清事件)。 */
    onVoiceTranscribedConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }
    var panelOpen by remember { mutableStateOf(false) }
    var longPressTarget by remember { mutableStateOf<Long?>(null) }
    var rewindTarget by remember { mutableStateOf<Long?>(null) }
    // 回溯确认弹窗点名的目标消息(长按落点在回合在途/草稿顶动列表时可能
    // 偏到相邻泡,点名内容让误触在确认前肉眼可辨)。
    var rewindTargetText by remember { mutableStateOf("") }
    // 长按「复制」与回溯成功提示共用:剪贴板写文本 + Toast(纯 UI 一次性反馈,
    // 不走 ViewModel 事件流)。
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // 自动滚动(F4 验收 low 修复):reverseLayout 下 index 0 = 最新内容,
    // 新消息落库时若用户本来就停在底部(或刚开始回合计数变化)则滚到 index 0,
    // 保证新泡/「正在输入」条到达即贴底可见;用户上滑翻历史时不打扰。
    val listState = rememberLazyListState()
    val messageCount = state.bubbleItems.sumOf { it.bubbles.size }
    LaunchedEffect(messageCount, state.sending) {
        val atBottom = listState.firstVisibleItemIndex <= 1 &&
            listState.firstVisibleItemScrollOffset <= 200
        if (atBottom && listState.firstVisibleItemIndex > 0) {
            listState.animateScrollToItem(0)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFFF6F6F6))) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                name = state.character?.name.orEmpty(),
                onBack = onBack,
                onOpenGuide = onOpenGuide,
                onSimulateOfflineReturn = onSimulateOfflineReturn,
                onOpenMemory = onOpenMemory,
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                reverseLayout = true,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp,
                ),
                // 组间距(04 号文档「组间距>组内距」):回合组之间留 10dp,
                // 大于组内 2dp,连发组在视觉上抱团、组与组之间分明
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.sending) {
                    item(key = "typing") { TypingHint(streamDraft) }
                }
                // 回合失败显式化(02 文档 §3.5):失败条贴在消息区最底部
                // (reverseLayout 下先发射=视觉最底),点击按原用户文本重试;
                // 重试开始即撤条转「正在输入」态,失败回合不落任何假回复泡。
                state.failedTurn?.let {
                    item(key = "failed-turn") { FailedTurnBar(onRetry = onRetryFailedTurn) }
                }
                items(state.bubbleItems.asReversed()) { group ->
                    PhoneBubbleGroup(
                        group = group,
                        avatarFile = avatarFile,
                        playingVoiceId = playingVoiceId,
                        onPlayVoice = onPlayVoice,
                        onLongPress = { longPressTarget = it },
                    )
                }
                if (state.loadFailed) {
                    item(key = "failed") {
                        Text(
                            "会话不存在",
                            color = Color(0xFF9A9A9A),
                            fontSize = 14.sp,
                        )
                    }
                }
                item(key = "bottom-pad") { Spacer(Modifier.height(8.dp)) }
            }
            InputBar(
                sending = state.sending,
                onSend = onSend,
                onTogglePanel = { panelOpen = !panelOpen },
                onHoldStart = onHoldStart,
                onHoldRelease = onHoldRelease,
                holdActive = holdActive,
                recognizing = recognizing,
                voiceTranscribed = voiceTranscribed,
                onVoiceTranscribedConsumed = onVoiceTranscribedConsumed,
            )
        }

        // 「+」扩展面板(13b 六宫格,展开在输入条上方)
        if (panelOpen) {
            ExtPanel(
                onSendCard = {
                    onSendCard(it)
                    panelOpen = false
                },
                onDismiss = { panelOpen = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp),
            )
        }

        // 长按操作弹窗(复制/回溯/重新生成,13c 说明的功能更新条目):长按即弹
        // (即时反馈,F4 验收 low);复制=04 §3.2.F 长按操作条本地 v1 最小项;
        // 回溯=删除该条及其后消息回到该点(收尾轮语义)。
        longPressTarget?.let { msgId ->
            val found = state.bubbleItems
                .flatMap { group -> group.bubbles.map { group.fromUser to it } }
                .firstOrNull { it.second.id == msgId }
            if (found == null) {
                longPressTarget = null
            } else {
                val (fromUser, item) = found
                com.neethu.gouxiang.ui.chat.MessageActionsDialog(
                    fromUser = fromUser,
                    targetText = rewindSnippet(item.content),
                    onDismiss = { longPressTarget = null },
                    onCopy = {
                        // 复制原泡全文到剪贴板,Toast 即刻确认后收起菜单
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
                        rewindTargetText = rewindSnippet(item.content)
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
            com.neethu.gouxiang.ui.chat.RewindConfirmDialog(
                targetText = rewindTargetText,
                onDismiss = { rewindTarget = null },
                onConfirm = {
                    onRewind(msgId)
                    rewindTarget = null
                    // 回溯成功提示(附录 C-G2:此前静默删除无感知;文档 §2.1
                    // parallel_phone_restart_toast 口径的本地落地)
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

/** 顶栏:✕ + 名字 +「由 AI 生成」+ 记忆图标 + 右侧「…」。 */
@Composable
private fun TopBar(
    name: String,
    onBack: () -> Unit,
    onOpenGuide: () -> Unit,
    onSimulateOfflineReturn: () -> Unit,
    onOpenMemory: () -> Unit = {},
) {
    // 长按即时反馈(F4 验收 low 修复):主动消息生成要 20~90s,
    // 没有即时的可视确认时用户会反复长按;Toast 即刻确认触发成功。
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Close,
            contentDescription = "关闭",
            tint = Color(0xFF1A1A1A),
            modifier = Modifier
                .size(24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onBack() },
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = Color(0xFF1A1A1A),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text("由 AI 生成", color = Color(0xFFB9B9B9), fontSize = 12.sp)
        }
        // 记忆入口(F3):与普通聊天页顶栏同款图标;手机聊天页(mode=phone 会话)
        // 此前无任何记忆入口,真机验收无法从回溯流程直达记忆页。
        Icon(
            Icons.Outlined.Psychology,
            contentDescription = "记忆",
            tint = Color(0xFF1A1A1A),
            modifier = Modifier
                .size(24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onOpenMemory() },
        )
        Spacer(Modifier.width(14.dp))
        Text(
            "…",
            color = Color(0xFF1A1A1A),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onOpenGuide() }
                // 调试入口(范围 5):长按「…」模拟离线回归,触发主动消息连发
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onOpenGuide() },
                    onLongClick = {
                        android.widget.Toast.makeText(
                            context,
                            "正在让 Ta 主动发消息,请稍等…",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        onSimulateOfflineReturn()
                    },
                ),
        )
    }
}

/** 一组连发(左白泡带头像 / 右黄泡无头像)。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneBubbleGroup(
    group: BubbleItem,
    avatarFile: String?,
    playingVoiceId: Long?,
    onPlayVoice: (Long, String) -> Unit,
    onLongPress: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        // 离线回归分隔(D 工作包 D4):进页消费的主动批次组上方居中灰字
        group.separator?.let { com.neethu.gouxiang.ui.chat.OfflineSeparator(it) }
        Row(Modifier.fillMaxWidth()) {
            if (!group.fromUser) {
                Spacer(Modifier.width(4.dp))
                Avatar(avatarFile)
                Spacer(Modifier.width(10.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Column(
                horizontalAlignment = if (group.fromUser) Alignment.End else Alignment.Start,
                modifier = if (group.fromUser) Modifier else Modifier.weight(1f, fill = false),
            ) {
                group.bubbles.forEachIndexed { index, bubble ->
                    // 组内距(04 号文档「组间距>组内距」):首泡 0dp(组间距已由列表
                    // spacedBy 10dp 提供)、后续 2dp,保证组间 10dp > 组内 2dp
                    Spacer(Modifier.height(if (index == 0) 0.dp else 2.dp))
                    PhoneBubble(
                        bubble = bubble,
                        fromUser = group.fromUser,
                        playing = bubble.id != null && bubble.id == playingVoiceId,
                        onPlayVoice = onPlayVoice,
                        onLongPress = onLongPress,
                    )
                }
            }
            if (group.fromUser) Spacer(Modifier.width(4.dp)) else Spacer(Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Avatar(avatarFile: String?) {
    if (avatarFile != null) {
        AsyncImage(
            model = java.io.File(avatarFile),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(34.dp)
                .background(Color(0xFFEDEDED), CircleShape),
        )
    } else {
        Box(
            Modifier
                .size(34.dp)
                .background(Color(0xFFE3E6EE), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("🙂", fontSize = 16.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneBubble(
    bubble: ChatBubble,
    fromUser: Boolean,
    playing: Boolean,
    onPlayVoice: (Long, String) -> Unit,
    onLongPress: (Long) -> Unit,
) {
    // 用户黄泡 = iMessage 绿泡的本地化;角色白泡带描边感阴影
    val shape = RoundedCornerShape(18.dp)
    val bg = if (fromUser) Color(0xFFFAE14C) else Color.White
    val longPress: (() -> Unit)? = bubble.id?.let { id -> ({ onLongPress(id) }) }
    Box(
        Modifier
            .widthIn(max = 264.dp)
            .background(bg, shape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    // 语音条点击播放(点击即播,长按操作)
                    if (bubble.kind == "voice") {
                        val text = bubble.content
                        val id = bubble.id
                        if (id != null && text.isNotBlank()) onPlayVoice(id, text)
                    }
                },
                onLongClick = { longPress?.invoke() },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        when (bubble.kind) {
            "voice" -> VoiceBody(bubble, playing)
            "card" -> CardBody(bubble)
            "sticker" -> StickerBody(bubble.content)
            "img" -> ImageBody(bubble.content)
            else -> TextBody(bubble, fromUser)
        }
    }
}

/** 语音条「•)N″」(对齐 14 dump:audio_icon + time 同处泡内;转写不外显,点击 TTS 播放)。 */
@Composable
private fun VoiceBody(bubble: ChatBubble, playing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "•)",
            color = Color(0xFF1A1A1A),
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            voiceDurationLabel(bubble.durationSec),
            color = Color(0xFF1A1A1A),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        if (playing) {
            Spacer(Modifier.width(6.dp))
            Text("♪", color = Color(0xFFE8A93D), fontSize = 14.sp)
        }
    }
}

/** 卡片体:13b 六型(转账/代付/位置/音乐/帖子/图片)。 */
@Composable
private fun CardBody(bubble: ChatBubble) {
    val payload = bubble.cardPayload
    if (payload == null) {
        Text(bubble.content, color = Color(0xFF1A1A1A), fontSize = 16.sp)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val icon = when (payload.kind) {
            PhoneCardKind.IMAGE -> Icons.Outlined.Image
            PhoneCardKind.MUSIC -> Icons.Filled.MusicNote
            PhoneCardKind.POST -> Icons.Outlined.Link
            PhoneCardKind.LOCATION -> Icons.Outlined.LocationOn
            PhoneCardKind.TRANSFER -> Icons.Outlined.AccountBalanceWallet
            PhoneCardKind.PAY_FOR -> Icons.Filled.Paid
        }
        Icon(icon, contentDescription = payload.kind.label, tint = Color(0xFF8A6D1F), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                payload.kind.label,
                color = Color(0xFF1A1A1A),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            val line = when (payload.kind) {
                PhoneCardKind.TRANSFER, PhoneCardKind.PAY_FOR ->
                    buildString {
                        append(if (payload.title.isBlank()) "0" else payload.title)
                        if (payload.note.isNotBlank()) append(" · ").append(payload.note)
                    }
                else -> payload.title.ifBlank { payload.note }
            }
            if (line.isNotBlank()) {
                Text(line, color = Color(0xFF6B6B6B), fontSize = 13.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun TextBody(bubble: ChatBubble, fromUser: Boolean) {
    val actionStyle = SpanStyle(
        fontStyle = FontStyle.Italic,
        color = if (fromUser) Color(0xFF1A1A1A).copy(alpha = 0.6f) else Color(0xFF9A9A9A),
    )
    val annotated = buildAnnotatedString {
        bubble.actions.forEach { action ->
            withStyle(actionStyle) { append("($action)") }
        }
        append(bubble.content)
    }
    Text(annotated, color = Color(0xFF1A1A1A), fontSize = 17.sp, lineHeight = 24.sp)
}

@Composable
private fun StickerBody(desc: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("😄", fontSize = 38.sp)
        Text(desc, color = Color(0xFF9A9A9A), fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun ImageBody(desc: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🖼️", fontSize = 32.sp)
        Text(desc, color = Color(0xFF9A9A9A), fontSize = 11.sp, maxLines = 1)
    }
}

/** 回合进行中:流式草稿白泡(首字前「对方正在输入中...」,04 §1.3 文案)。 */
@Composable
private fun TypingHint(streamDraft: String) {
    val draft = streamDraft.takeLastCodePoints(120)
    Row(
        modifier = Modifier.padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .background(Color.White, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (draft.isEmpty()) {
                Text("对方正在输入中...", color = Color(0xFF9A9A9A), fontSize = 14.sp)
            } else {
                Text(
                    buildAnnotatedString {
                        append(draft)
                        withStyle(SpanStyle(color = Color(0xFFE8A93D))) { append("▌") }
                    },
                    color = Color(0xFF1A1A1A),
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
            }
        }
    }
}

/** 回合失败条(02 文档 §3.5):「消息发送失败,请点击重试」,点击整行触发重试。 */
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
        Text("消息发送失败，请点击重试", color = Color(0xFFE5484D), fontSize = 13.sp)
    }
}

/** 按 Unicode 码点取尾部(不劈代理对;F2 实锤惯例)。 */
private fun String.takeLastCodePoints(n: Int): String {
    if (length <= n) return this
    val pts = codePoints().toArray()
    return String(pts, (pts.size - n).coerceAtLeast(0), n)
}

// ── 输入条 + 扩展面板 ────────────────────────────────────────────────

/** 输入条:自由输入 + 😊 + 🎤 + 「+」(对齐 dump edit_view/entrance_* 三件)。
 * [sending] = 回合进行中:发送钮禁用(不丢弃草稿;发送被接受才清空 draft)。
 * F4:🎤 从装饰改为真「按住说话」——按下开录(F6 同款 WAV 管线)、松手 ASR
 * 转写填草稿(不直接发送,对齐猫箱「转写可改」);识别中禁重入。
 */
@Composable
private fun InputBar(
    sending: Boolean,
    onSend: (String) -> Unit,
    onTogglePanel: () -> Unit,
    onHoldStart: () -> Unit = {},
    onHoldRelease: () -> Unit = {},
    holdActive: Boolean = false,
    recognizing: Boolean = false,
    voiceTranscribed: String = "",
    onVoiceTranscribedConsumed: () -> Unit = {},
) {
    var draft by remember { mutableStateOf("") }
    // F4 转写落草稿:松手识别完成 → 填入草稿框(用户可见可改),填完即消费
    // 事件,再次按住说话不重复填。
    LaunchedEffect(voiceTranscribed) {
        if (voiceTranscribed.isNotBlank()) {
            draft = voiceTranscribed
            onVoiceTranscribedConsumed()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFF6F6F6))
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .background(Color.White, RoundedCornerShape(24.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .height(38.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = TextStyle(color = Color(0xFF1A1A1A), fontSize = 16.sp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) {
                            Text("自由输入...", color = Color(0xFFB9B9B9), fontSize = 16.sp)
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text("😊", fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            // F4:🎤 = 按住说话(pointerInput 按压手势;按下开录,松手转写,
            // 滑出取消停录丢弃;识别中禁重入)。文案态视觉反馈对齐 im 页。
            Text(
                when {
                    holdActive -> "🔴"
                    recognizing -> "⏳"
                    else -> "🎤"
                },
                fontSize = 20.sp,
                modifier = Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        onHoldStart()
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            up.consume()
                            onHoldRelease()
                        } else {
                            onHoldRelease()
                        }
                    }
                },
            )
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Filled.Add,
                contentDescription = "更多",
                tint = Color(0xFF1A1A1A),
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onTogglePanel() },
            )
            if (draft.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                // 发送中不丢草稿:发送中禁用而非丢弃草稿;仅在点击被接受时清空
                // (ViewModel 侧 sending 拦截保留为最后防线,UI 不再无条件清空)。
                Box(
                    Modifier
                        .size(30.dp)
                        .background(
                            if (draft.isNotBlank() && !sending) Color(0xFFFAE14C) else Color(0xFFE3E3E3),
                            CircleShape,
                        )
                        .clickable(enabled = !sending) {
                            onSend(draft)
                            draft = ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("↑", color = if (!sending) Color(0xFF1A1A1A) else Color(0xFFB9B9B9), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 13b 六宫格(发送图片/音乐/帖子/位置/转账/代付)。 */
@Composable
private fun ExtPanel(
    onSendCard: (CardPayload) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var composing by remember { mutableStateOf<PhoneCardKind?>(null) }
    Box(modifier.fillMaxWidth()) {
        // 点击空白收起
        Box(
            Modifier
                .matchParentSize()
                .clickable(onClick = onDismiss),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFF1F1F3), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(16.dp),
        ) {
            composing?.let { kind ->
                CardComposer(
                    kind = kind,
                    onSend = {
                        onSendCard(it)
                        composing = null
                    },
                    onCancel = { composing = null },
                )
                Spacer(Modifier.height(12.dp))
            }
            Row {
                PanelItem(
                    Icons.Outlined.Image, "发送图片",
                    modifier = Modifier.weight(1f),
                ) { composing = PhoneCardKind.IMAGE }
                Spacer(Modifier.width(10.dp))
                PanelItem(
                    Icons.Filled.MusicNote, "音乐",
                    modifier = Modifier.weight(1f),
                ) { composing = PhoneCardKind.MUSIC }
                Spacer(Modifier.width(10.dp))
                PanelItem(
                    Icons.Outlined.Link, "帖子",
                    modifier = Modifier.weight(1f),
                ) { composing = PhoneCardKind.POST }
                Spacer(Modifier.width(10.dp))
                PanelItem(
                    Icons.Outlined.LocationOn, "位置",
                    modifier = Modifier.weight(1f),
                ) { composing = PhoneCardKind.LOCATION }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                PanelItem(
                    Icons.Outlined.AccountBalanceWallet, "转账",
                    modifier = Modifier.weight(1f),
                ) { composing = PhoneCardKind.TRANSFER }
                Spacer(Modifier.width(10.dp))
                PanelItem(
                    Icons.Filled.Paid, "代付",
                    modifier = Modifier.weight(1f),
                ) { composing = PhoneCardKind.PAY_FOR }
                Spacer(Modifier.weight(2f))
            }
        }
    }
}

@Composable
private fun PanelItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable { onClick() }
            .padding(vertical = 6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(Color.White, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Color(0xFF3A3A3A), modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color(0xFF6B6B6B), fontSize = 13.sp)
    }
}

/** 轻量卡片表单(13b 六型:图片/音乐/帖子/位置/转账/代付)。 */
@Composable
private fun CardComposer(
    kind: PhoneCardKind,
    onSend: (CardPayload) -> Unit,
    onCancel: () -> Unit,
) {
    var field1 by remember(kind) { mutableStateOf("") }
    var field2 by remember(kind) { mutableStateOf("") }
    val (f1Hint, f2Hint) = when (kind) {
        PhoneCardKind.TRANSFER -> "转账金额(例如:520)" to "转账备注(选填)"
        PhoneCardKind.PAY_FOR -> "代付金额(例如:88)" to "备注(选填)"
        PhoneCardKind.IMAGE -> "图片描述" to ""
        PhoneCardKind.MUSIC -> "歌名或链接" to ""
        PhoneCardKind.POST -> "帖子标题或链接" to ""
        PhoneCardKind.LOCATION -> "位置名称" to ""
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kind.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
            Spacer(Modifier.weight(1f))
            Text(
                "✕",
                fontSize = 15.sp,
                color = Color(0xFF9A9A9A),
                modifier = Modifier.clickable { onCancel() },
            )
        }
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = field1,
            onValueChange = { field1 = it },
            singleLine = true,
            textStyle = TextStyle(color = Color(0xFF1A1A1A), fontSize = 15.sp),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    if (field1.isEmpty()) Text(f1Hint, color = Color(0xFFB9B9B9), fontSize = 15.sp)
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF3F4F6), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        if (f2Hint.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            BasicTextField(
                value = field2,
                onValueChange = { field2 = it },
                singleLine = true,
                textStyle = TextStyle(color = Color(0xFF1A1A1A), fontSize = 15.sp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (field2.isEmpty()) Text(f2Hint, color = Color(0xFFB9B9B9), fontSize = 15.sp)
                        inner()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF3F4F6), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        if (kind == PhoneCardKind.TRANSFER || kind == PhoneCardKind.PAY_FOR) {
            Spacer(Modifier.height(4.dp))
            Text(
                "该转账不会用到你的真实财产",
                color = Color(0xFF9A9A9A),
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        // F2 金额校验:转账/代付主字段=金额,空值不可发送(红提示明示原因;
        // 其余类型字段可选,行为不变)
        val amountRequired = kind == PhoneCardKind.TRANSFER || kind == PhoneCardKind.PAY_FOR
        val amountMissing = amountRequired && field1.isBlank()
        if (amountMissing) {
            Text(
                "请输入${if (kind == PhoneCardKind.TRANSFER) "转账" else "代付"}金额",
                color = Color(0xFFE5484D),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(if (field1.isNotBlank()) Color(0xFFFAE14C) else Color(0xFFE3E3E3), RoundedCornerShape(999.dp))
                .clickable {
                    if (field1.isNotBlank()) {
                        onSend(CardPayload(kind = kind, title = field1.trim(), note = field2.trim()))
                    }
                }
                .padding(vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (amountMissing) "请输入金额后发送" else "发送",
                color = if (amountMissing) Color(0xFF9A9A9A) else Color(0xFF1A1A1A),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 长按操作弹窗:复制/回溯 +(角色侧)重新生成(13c 功能说明的本地实现)。
 * F1 抽公共组件后两页共用(im 聊天页同款);函数体在 ui.chat.MessageActionsDialog。
 */

/** 回溯弹窗点名目标消息:压平空白后截前 12 字(长按误触相邻泡时肉眼可辨)。
 * F1 上提为聊天域公共工具(见 ui.chat.rewindSnippetShared),此处保留同名
 * 委托以稳住既有单测 import。
 */
internal fun rewindSnippet(text: String): String =
    com.neethu.gouxiang.ui.chat.rewindSnippetShared(text)
