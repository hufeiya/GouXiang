package com.neethu.gouxiang.ui.character

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.neethu.gouxiang.chat.DialogMode
import com.neethu.gouxiang.chat.ReplyLength
import com.neethu.gouxiang.chat.StatusLine
import com.neethu.gouxiang.data.CharacterEntity
import com.neethu.gouxiang.ai.config.volcanoVoiceName
import com.neethu.gouxiang.ai.speech.CharacterVoiceStore
import com.neethu.gouxiang.ui.aiconfig.VolcanoVoicePickerDialog

/**
 * 角色主页 04(基准 04-character-home.png / 04c-scroll2 / dump 同名 xml):
 *
 * - 全屏角色照片(接近满高,照片延伸到状态栏下的沉浸式)+ 左上 X 关闭;
 * - 照片下部叠:居中名字 → 状态条胶囊(emoji + 在做什么 ›,与聊天页同一
 *   [StatusLine] 数据源,dump:tv_state_emoji/tv_state_desc);
 * - 来源行:圆形占位头像 + 来源名「脑洞2.0」(dump:tv_author_name)+
 *   右侧「+ 关注」胶囊按钮(dump:button_follow/tv_text,本地版静态);
 * - 简介(dump:tv_desc);
 * - 功能宫格三卡(dump:rl_functions,顺序固定):
 *   对话模式(副标题=当前模式名,09 弹层)/ 对话风格(10 弹层)/
 *   Ta 的记忆(直达 F3 记忆页);
 * - 功能卡(后续功能):Ta 的装扮(带假图条)/ Ta 的心声(F8:直达心声页,
 *   尾注「全部 N」)/ Ta 的朋友圈(F9:直达朋友圈时间线页,尾注「全部 N」)。
 */
@Composable
fun CharacterHomeScreen(
    character: CharacterEntity?,
    avatarFile: String?,
    status: StatusLine,
    currentMode: DialogMode,
    currentLength: ReplyLength,
    onSelectMode: (DialogMode) -> Unit,
    onSelectLength: (ReplyLength) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenInnerVoice: () -> Unit,
    onOpenMoments: () -> Unit,
    onClose: () -> Unit,
    /** C 工作包:进编辑表单页(主页可触达的编辑入口)。 */
    onEditCharacter: () -> Unit = {},
    /** C 工作包 C2:从主页直接进入该角色的 IM 会话续聊。 */
    onOpenChat: () -> Unit = {},
    /** 「Ta 的心声」占位卡尾注计数(F8;>0 时展示「全部 N」)。 */
    innerVoiceCount: Int = 0,
    /** 「Ta 的朋友圈」占位卡尾注计数(F9;>0 时展示「全部 N」)。 */
    momentCount: Int = 0,
    /** 「番外剧场」占位卡尾注计数(F 工作包;>0 时展示「全部 N」)。 */
    sidestoryCount: Int = 0,
    /** 番外剧场页入口(F3/F4;番外模板 + 心动事件 + 记录管理)。 */
    onOpenSidestory: () -> Unit = {},
    /** 手帐/小说/合照入口卡尾注计数(G 工作包;>0 时展示「全部 N」)。 */
    handbookCount: Int = 0,
    novelCount: Int = 0,
    photoCount: Int = 0,
    /** 手帐/小说/合照页入口(G 工作包 G1-G3)。 */
    onOpenHandbook: () -> Unit = {},
    onOpenNovel: () -> Unit = {},
    onOpenPhoto: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var modeSheet by remember { mutableStateOf(false) }
    var styleSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val voiceStore = remember(context) { CharacterVoiceStore(context.applicationContext) }
    var voiceAssignment by remember(character) { mutableStateOf(character?.let(voiceStore::getOrAssign)) }
    var voicePicker by remember(character?.id) { mutableStateOf(false) }
    DisposableEffect(character, voiceStore) {
        val stopObserving = character?.let { current ->
            voiceStore.observe(current) { voiceAssignment = voiceStore.getOrAssign(current) }
        }
        onDispose { stopObserving?.invoke() }
    }
    BackHandler { onClose() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF3D4A5D)),
    ) {
        // ── 全屏角色照片(dump:img_bot_portrait [0,0][1080,2166],底部留白)──
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = 96.dp),
        ) {
            if (avatarFile != null) {
                AsyncImage(
                    model = java.io.File(avatarFile),
                    contentDescription = character?.name,
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
        }
        // 顶部/中段压暗渐变(可读性,对齐参考截图的暗角)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.35f to Color.Transparent,
                        0.72f to Color.Black.copy(alpha = 0.35f),
                        1f to Color.Black.copy(alpha = 0.55f),
                    ),
                ),
        )
        // 左侧竖排「由 AI 生成」(dump:tv_ai_risk_tips「由\nAI\n生\n成」)
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp),
        ) {
            "由 AI 生成".forEach { ch ->
                Text(ch.toString(), color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
            }
        }

        // ── 主体内容列(关闭钮/名字/状态条/来源/宫格)──
        // X 放在滚动内容列内(验收实锤:独立声明的 Icon 被声明在后的全屏滚动
        // 列压住,a11y 树里没有「关闭」节点、点击也落不到它;宫格卡与关闭钮
        // 同容器后与宫格同样可点/可 dump 索引)。
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── 关闭按钮(左上;参考图为 X;44dp 触控区)──────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 10.dp, top = 6.dp),
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onClose() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // 居中名字(dump:tv_story_title)
            Text(
                character?.name.orEmpty(),
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(14.dp))
            // 状态条胶囊(dump:tv_state_emoji + tv_state_desc;数据源与聊天页一致)
            if (status.emoji.isNotEmpty() || status.text.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
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
                Spacer(Modifier.height(22.dp))
            }
            // 来源行(dump:base_author_area:sdv_author_avatar + tv_author_name + button_follow)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 来源头像占位(圆底 emoji)
                Box(
                    Modifier
                        .size(34.dp)
                        .background(Color.White.copy(alpha = 0.92f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🧠", fontSize = 17.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    character?.source.orEmpty(),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                // 关注按钮(dump:button_follow/tv_text)在本地版无关注生态,属
                // 静态死按钮;C 工作包 C2 将该槽位替换为可用的「聊天」续聊入口
                //(主页可打开续聊)+「编辑」入口,视觉形态沿用原胶囊样式。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onOpenChat() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Outlined.Chat,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("聊天", color = Color.White, fontSize = 15.sp)
                }
                Spacer(Modifier.width(8.dp))
                // C 工作包:编辑资料入口(进编辑表单页,改字段/换形象)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onEditCharacter() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("编辑", color = Color.White, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            // 简介(dump:tv_desc)
            Text(
                character?.intro.orEmpty(),
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 17.sp,
                lineHeight = 27.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(26.dp))
            // 功能宫格(dump:rl_functions 三卡,顺序:对话模式/对话风格/Ta 的记忆;
            // 图标轮廓线风格,对齐参考图三枚浅色图标)。行高 136dp(F5 复验 low 修复:
            // 106dp 时「对话模式」副标题只露出 5px 不可见;参考 dump rl_functions
            // 高 283px(1080px 屏)≈ 106dp 密度过高,本地版放宽到两行可见)。
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(136.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                GridCell(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "对话模式",
                    subtitle = currentMode.title,
                    modifier = Modifier.weight(1f),
                ) { modeSheet = true }
                GridCell(
                    icon = Icons.Outlined.Chat,
                    title = "对话风格",
                    modifier = Modifier.weight(1f),
                ) { styleSheet = true }
                GridCell(
                    icon = Icons.Outlined.Extension,
                    title = "Ta 的记忆",
                    modifier = Modifier.weight(1f),
                ) { onOpenMemory() }
            }
            Spacer(Modifier.height(16.dp))
            if (character != null) {
                PlaceholderCard(
                    "当前音色",
                    tail = voiceAssignment?.voiceId?.let(::volcanoVoiceName) ?: "未设置（使用全局设置）",
                    onClick = { voicePicker = true },
                )
                Spacer(Modifier.height(12.dp))
            }
            // ── 占位卡(后续功能,范围 1 明示)─────────────────────────
            // Ta 的装扮(参考图带三张假图条 + 「全部 N 套 ›」)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                    .padding(16.dp),
            ) {
                PlaceholderCardHeader("Ta 的装扮", "全部 11 套")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(110.dp)
                                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("👗", fontSize = 26.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // Ta 的心声(F8):占位卡升级为心声页入口,尾注「全部 N」(有心声时)
            PlaceholderCard(
                "Ta 的心声",
                tail = if (innerVoiceCount > 0) "全部 $innerVoiceCount" else null,
                onClick = onOpenInnerVoice,
            )
            Spacer(Modifier.height(12.dp))
            // Ta 的朋友圈(F9):占位卡升级为朋友圈时间线页入口,尾注「全部 N」
            PlaceholderCard(
                "Ta 的朋友圈",
                tail = if (momentCount > 0) "全部 $momentCount" else null,
                onClick = onOpenMoments,
            )
            Spacer(Modifier.height(12.dp))
            // 番外剧场(F 工作包 F3/F4):番外模板 + 心动事件入口与记录管理
            PlaceholderCard(
                "番外剧场",
                tail = if (sidestoryCount > 0) "全部 $sidestoryCount" else null,
                onClick = onOpenSidestory,
            )
            Spacer(Modifier.height(12.dp))
            // 手帐(G 工作包 G1):选聊天范围生成图文手帐 + 历史管理
            PlaceholderCard(
                "手帐",
                tail = if (handbookCount > 0) "全部 $handbookCount" else null,
                onClick = onOpenHandbook,
            )
            Spacer(Modifier.height(12.dp))
            // 小说(G 工作包 G2):聊天转小说,篇章结构 + 长度档
            PlaceholderCard(
                "小说",
                tail = if (novelCount > 0) "全部 $novelCount" else null,
                onClick = onOpenNovel,
            )
            Spacer(Modifier.height(12.dp))
            // 合照(G 工作包 G3):二维文生图合照 + 设为聊天背景
            PlaceholderCard(
                "合照",
                tail = if (photoCount > 0) "全部 $photoCount" else null,
                onClick = onOpenPhoto,
            )
            Spacer(Modifier.height(18.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (voicePicker && character != null) {
        VolcanoVoicePickerDialog(
            selectedValue = voiceAssignment?.voiceId.orEmpty(),
            onSelect = { voiceStore.setManual(character, it) },
            onDismiss = { voicePicker = false },
            showUnselectedOption = false,
            onResetAutomatic = { voiceAssignment = voiceStore.reset(character) },
        )
    }

    // ── 弹层 09:对话模式(五选一,选择即时生效并回写宫格副标题)────────
    if (modeSheet) {
        DialogModeSheet(
            selected = currentMode,
            onSelect = {
                onSelectMode(it)
                modeSheet = false
            },
            onDismiss = { modeSheet = false },
        )
    }
    // ── 弹层 10:对话风格(回复长度三段;完成按钮收口)────────────────
    if (styleSheet) {
        DialogStyleSheet(
            selected = currentLength,
            onSelect = onSelectLength,
            onDismiss = { styleSheet = false },
        )
    }
}

/** 功能宫格单卡(深蓝磨砂底,图标+标题+可选副标题;dump 每卡 icon/tv_title/tv_sub_title)。 */
@Composable
private fun GridCell(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF46536A).copy(alpha = 0.92f), RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(top = 22.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = title, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        }
    }
}

/** 占位卡头(标题 + 可选「全部 N ›」行,后续功能入口视觉对齐 04c)。 */
@Composable
private fun PlaceholderCardHeader(title: String, tail: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        if (tail != null) {
            Text(tail, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
            Spacer(Modifier.width(4.dp))
            Text("›", color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp)
        }
    }
}

/** 纯占位行卡(心声/朋友圈:仅标题+箭头;心声卡带 onClick 直达心声页)。 */
@Composable
private fun PlaceholderCard(title: String, tail: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onClick() }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 22.dp),
    ) {
        PlaceholderCardHeader(title, tail)
    }
}
