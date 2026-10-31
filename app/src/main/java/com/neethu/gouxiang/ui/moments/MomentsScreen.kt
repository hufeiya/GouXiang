package com.neethu.gouxiang.ui.moments

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.neethu.gouxiang.data.MomentAuthorKind
import com.neethu.gouxiang.data.MomentCommentEntity
import com.neethu.gouxiang.data.MomentEntity
import com.neethu.gouxiang.data.MomentFeed
import com.neethu.gouxiang.ui.messages.formatRelativeTime

/**
 * 朋友圈页 06(F9 → H 工作包 H1/H2/H3,基准 06-moments.png):
 *
 * - 多角色合并时间线(H1):用户帖 + 参与角色的动态混排,每卡显示真实作者
 *  (头像/名;user 帖 = 「我」),查询层权限过滤在 [MomentFeed.assemble];
 * - 右上「发动态」供用户发帖，「角色管理」控制角色参与和互动;
 *   角色动态随对话发现，可从聊天提醒打开。
 * - 用户可删除自己的动态(H3:删帖连带清空其评论区)与自己的评论;
 * - 评论区:用户评论(「我」)与角色评论/回复(按 authorId 归名)分色;
 * - 底部评论输入条:发送 = 用户评论落库 → 作者角色回复(权限闸内)。
 */
@Composable
fun MomentsScreen(
    state: MomentsUiState,
    avatarFiles: Map<Long, String?>,
    onBack: () -> Unit,
    onPublish: (String) -> Unit,
    onDeletePost: (MomentEntity) -> Unit,
    onDeleteComment: (MomentEntity, MomentCommentEntity) -> Unit,
    onLike: (MomentEntity) -> Unit,
    onSendComment: (MomentEntity, String) -> Unit,
    onOpenPermissions: () -> Unit,
    onTogglePermission: (Long, MomentPermissionField, Boolean) -> Unit,
    onRetryImage: (com.neethu.gouxiang.ui.ImageGenerationState) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }
    var draft by remember { mutableStateOf("") }
    // 评论目标:点击评论图标切当前输入条挂靠的动态(空 = 最新一条)
    var commentTargetId by remember { mutableStateOf<Long?>(null) }
    // H2 权限面板 / H3 发布弹层(局部导航态)
    var showPermissions by remember { mutableStateOf(false) }
    var showPublish by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        // ── 沉浸式头部(封面渐变延伸到状态栏下)────────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .height(232.dp),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFFF6EFB9),
                                Color(0xFFEFE7F6),
                                Color(0xFFE4E9FA),
                            ),
                        ),
                    ),
            ) {
                // AI 星球装饰(参考封面右上淡金星环,静态装饰)
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 96.dp, top = 34.dp)
                        .size(64.dp)
                        .background(Color(0xFFF7E88A).copy(alpha = 0.85f), CircleShape),
                )
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 76.dp, top = 118.dp)
                        .width(104.dp)
                        .height(10.dp)
                        .background(Color.White.copy(alpha = 0.55f), RoundedCornerShape(999.dp)),
                )
                Text(
                    "✦",
                    color = Color(0xFFB9A7E8),
                    fontSize = 20.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 60.dp, top = 96.dp),
                )
            }
            // 左上返回(参考图 ‹ 黑箭头)
            Box(
                Modifier
                    .statusBarsPadding()
                    .padding(start = 8.dp, top = 4.dp),
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ArrowBackIosNew,
                        contentDescription = "返回",
                        tint = Color(0xFF1A1A1A),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // 右上操作列:发动态 / 角色管理
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 14.dp, top = 10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeaderPill(
                        text = if (state.publishing) "发布中…" else "发动态",
                        enabled = !state.publishing,
                        icon = { Icon(Icons.Outlined.Edit, contentDescription = null, tint = Color(0xFF1A1A1A), modifier = Modifier.size(15.dp)) },
                        onClick = { showPublish = true },
                    )
                }
                Spacer(Modifier.height(8.dp))
                HeaderPill(
                    text = "角色管理",
                    enabled = true,
                    icon = { Icon(Icons.Outlined.ManageAccounts, contentDescription = null, tint = Color(0xFF1A1A1A), modifier = Modifier.size(15.dp)) },
                    onClick = {
                        onOpenPermissions()
                        showPermissions = true
                    },
                )
            }
            // 封面右下:页面名(角色页=角色名;全局=朋友圈)+ 头像/首字
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp),
            ) {
                Text(
                    if (state.characterId > 0) state.characterName else "朋友圈",
                    color = Color(0xFF6C6C6C),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(10.dp))
                val avatarFile = if (state.characterId > 0) avatarFiles[state.characterId] else null
                Box(
                    Modifier
                        .size(76.dp)
                        .background(Color.White, CircleShape)
                        .padding(2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatarFile != null) {
                        AsyncImage(
                            model = java.io.File(avatarFile),
                            contentDescription = state.characterName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFFEDEDED), CircleShape),
                        )
                    } else {
                        Text(
                            (state.characterName.ifBlank { "圈" }).take(1),
                            color = Color(0xFF9A9A9A),
                            fontSize = 26.sp,
                        )
                    }
                }
            }
        }
        // ── 圆角内容区(信息流;顶部大圆角叠在封面下沿)────────────────
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    Color(0xFFFAFAF7),
                    RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.generating) {
                item(key = "generating") {
                    Text("动态正在生成…", color = Color(0xFF9A9A9A), fontSize = 13.sp)
                }
            }
            if (state.error != null) {
                item(key = "error") {
                    Text(
                        state.error ?: "",
                        color = Color(0xFFE05A5A),
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            state.automaticError?.let { error ->
                item(key = "automatic-error") { Text(error, color = Color(0xFFE05A5A), fontSize = 13.sp) }
            }
            items(state.items, key = { it.post.id }) { item ->
                MomentCard(
                    item = item,
                    comments = state.comments[item.post.id].orEmpty(),
                    avatarFiles = avatarFiles,
                    characterNames = state.characterNames,
                    replying = state.replyingToId == item.post.id,
                    image = state.imageResults[item.post.id],
                    onLike = { onLike(item.post) },
                    onCommentTarget = { commentTargetId = item.post.id },
                    onDeletePost = onDeletePost,
                    onDeleteComment = onDeleteComment,
                    onRetryImage = onRetryImage,
                )
            }
            if (state.items.isEmpty() && !state.generating && !state.loading) {
                item(key = "empty") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 72.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("🌏", fontSize = 34.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "还没有动态",
                            color = Color(0xFF6C6C6C),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "你可以分享此刻；角色动态随对话发现，并在聊天中提醒你",
                            color = Color(0xFF9A9A9A),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
        // ── 底部评论输入条(发送 = 用户评论 → 作者角色回复[权限闸内])─────
        CommentInputBar(
            draft = draft,
            enabled = state.replyingToId == null,
            onDraftChange = { draft = it },
            onSend = {
                val post = state.items.firstOrNull { it.post.id == commentTargetId }?.post
                    ?: state.items.firstOrNull()?.post
                val body = draft.trim()
                if (post != null && body.isNotEmpty()) {
                    commentTargetId = post.id
                    onSendComment(post, body)
                    draft = ""
                }
            },
        )
    }

    // H3 发布弹层(用户发帖)
    if (showPublish) {
        PublishDialog(
            publishing = state.publishing,
            onPublish = { text ->
                onPublish(text)
                showPublish = false
            },
            onDismiss = { if (!state.publishing) showPublish = false },
        )
    }
    // H2 权限面板(逐角色四开关)
    if (showPermissions) {
        PermissionSheet(
            rows = state.permissions,
            loading = state.permissionsLoading,
            onToggle = onTogglePermission,
            onClose = { showPermissions = false },
        )
    }
}

/** 头部黄胶囊(发动态/角色管理共用)。 */
@Composable
private fun HeaderPill(
    text: String,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color(0xFFF6E749), RoundedCornerShape(999.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        icon()
        Spacer(Modifier.width(5.dp))
        Text(text, color = Color(0xFF1A1A1A), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** 发布弹层(H3 用户发帖;字数上限/发布中禁用由 VM 校验兜底)。 */
@Composable
private fun PublishDialog(
    publishing: Boolean,
    onPublish: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发布新动态", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                BasicTextField(
                    value = text,
                    onValueChange = { if (it.length <= 500) text = it },
                    textStyle = TextStyle(color = Color(0xFF1A1A1A), fontSize = 15.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth()) {
                            if (text.isEmpty()) {
                                Text("这一刻的想法…", color = Color(0xFFB5B5B5), fontSize = 15.sp)
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${text.length}/500 · 授权的角色可以看到并评论",
                    color = Color(0xFF9A9A9A),
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && !publishing,
                onClick = { onPublish(text.trim()) },
            ) { Text(if (publishing) "发布中…" else "发布", color = Color(0xFF7A6BD6)) }
        },
        dismissButton = {
            TextButton(enabled = !publishing, onClick = onDismiss) { Text("取消", color = Color(0xFF6C6C6C)) }
        },
    )
}

/** H2 权限面板(底部弹层;逐角色四开关,切换即写库,生成/查询层双执行)。 */
@Composable
private fun PermissionSheet(
    rows: List<MomentPermissionRow>,
    loading: Boolean,
    onToggle: (Long, MomentPermissionField, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClose() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFFAFAF7), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { /* 拦截点击,不透传关闭 */ }
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("角色管理", color = Color(0xFF1A1A1A), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = Color(0xFF6C6C6C),
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { onClose() },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "开关对角色动态发现与互动即时生效；发现新动态后可在聊天提醒中打开",
                color = Color(0xFF9A9A9A),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            if (loading) {
                Text("载入中…", color = Color(0xFF9A9A9A), fontSize = 13.sp)
            } else if (rows.isEmpty()) {
                Text("还没有角色", color = Color(0xFF9A9A9A), fontSize = 13.sp)
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { row ->
                    PermissionRow(row, onToggle)
                }
            }
        }
    }
}

/** 单个角色的权限行(名字 + 四开关)。 */
@Composable
private fun PermissionRow(
    row: MomentPermissionRow,
    onToggle: (Long, MomentPermissionField, Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(row.name, color = Color(0xFF1A1A1A), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PermissionToggle("参与", row.joined) { onToggle(row.characterId, MomentPermissionField.JOINED, it) }
            Spacer(Modifier.width(10.dp))
            PermissionToggle("看我的动态", row.seeUserPosts) { onToggle(row.characterId, MomentPermissionField.SEE_USER_POSTS, it) }
            Spacer(Modifier.width(10.dp))
            PermissionToggle("评论互动", row.interact) { onToggle(row.characterId, MomentPermissionField.INTERACT, it) }
            Spacer(Modifier.width(10.dp))
            PermissionToggle("发现动态", row.dailyAuto) { onToggle(row.characterId, MomentPermissionField.DAILY_AUTO, it) }
        }
    }
}

@Composable
private fun PermissionToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFF6C6C6C), fontSize = 12.sp)
        Spacer(Modifier.width(2.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF7A6BD6)),
            modifier = Modifier.size(38.dp),
        )
    }
}

/**
 * 单条动态卡(H1 多角色):作者行(头像+名) → 正文 → 配图(可选)→
 * 时间 + 点赞/评论 + 用户帖删除 → 评论区(用户=「我」/角色=按 id 归名)。
 */
@Composable
private fun MomentCard(
    item: MomentFeed.MomentFeedItem,
    comments: List<MomentCommentEntity>,
    avatarFiles: Map<Long, String?>,
    characterNames: Map<Long, String>,
    replying: Boolean,
    image: com.neethu.gouxiang.ui.ImageGenerationState?,
    onLike: () -> Unit,
    onCommentTarget: () -> Unit,
    onDeletePost: (MomentEntity) -> Unit,
    onDeleteComment: (MomentEntity, MomentCommentEntity) -> Unit,
    onRetryImage: (com.neethu.gouxiang.ui.ImageGenerationState) -> Unit,
) {
    val post = item.post
    val isUserPost = item.isUserPost
    val authorName = item.authorName
    val authorAvatar = if (!isUserPost) avatarFiles[item.authorCharacterId] else null
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        // 作者行:圆形头像 + 名字(avatar 帖 = 角色头像;user 帖 = 「我」首字)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(Color.White, CircleShape)
                    .padding(1.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (authorAvatar != null) {
                    AsyncImage(
                        model = java.io.File(authorAvatar),
                        contentDescription = authorName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFEDEDED), CircleShape),
                    )
                } else {
                    Text(authorName.take(1), color = Color(0xFF9A9A9A), fontSize = 16.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                authorName,
                color = if (isUserPost) Color(0xFF3A3A3A) else Color(0xFF7A6BD6),
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(10.dp))
        // 正文
        Text(
            post.text,
            color = Color(0xFF1A1A1A),
            fontSize = 16.sp,
            lineHeight = 26.sp,
        )
        if (post.isStale) {
            Text("对话已回溯，此内容可能已过时", color = Color(0xFFAD6B26), fontSize = 12.sp)
        }
        com.neethu.gouxiang.ui.ImageRetryAction(image, post.imageFile.isBlank() && post.imagePrompt.isNotBlank() && !post.isStale, Color(0xFFAD6B26), onRetryImage)
        // 配图(可选增强:纯文字卡不占位;生图失败保持纯文字,与 F8 同约定)
        if (post.imageFile.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            AsyncImage(
                model = java.io.File(post.imageFile),
                contentDescription = "动态配图",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color(0xFFEDEDED), RoundedCornerShape(10.dp)),
            )
        }
        Spacer(Modifier.height(10.dp))
        // 时间 + 互动行(点赞/评论/用户帖删除)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatRelativeTime(post.at),
                color = Color(0xFFB5B5B5),
                fontSize = 14.sp,
            )
            Spacer(Modifier.weight(1f))
            if (isUserPost) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除动态",
                    tint = Color(0xFFB5B5B5),
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onDeletePost(post) },
                )
                Spacer(Modifier.width(14.dp))
            }
            Icon(
                if (post.likedByMe) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (post.likedByMe) "取消点赞" else "点赞",
                tint = if (post.likedByMe) Color(0xFFE84D8A) else Color(0xFFB5B5B5),
                modifier = Modifier
                    .size(22.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onLike() },
            )
            Spacer(Modifier.width(14.dp))
            Icon(
                Icons.Outlined.ChatBubbleOutline,
                contentDescription = "评论",
                tint = Color(0xFFB5B5B5),
                modifier = Modifier
                    .size(22.dp)
                    .clickable { onCommentTarget() },
            )
        }
        // 评论区(用户评论与角色评论/回复;角色行首带归名,用户可删自己的)
        if (comments.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF1F1EC), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                comments.forEach { comment ->
                    val isUser = comment.authorKind == MomentAuthorKind.USER
                    val name = if (isUser) MomentFeed.USER_DISPLAY_NAME
                    else characterNames[comment.authorId] ?: authorName
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "$name:",
                            color = if (isUser) Color(0xFF3A3A3A) else Color(0xFF7A6BD6),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            comment.text,
                            color = Color(0xFF3A3A3A),
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (isUser) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "删除评论",
                                tint = Color(0xFFB5B5B5),
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { onDeleteComment(post, comment) },
                            )
                        }
                    }
                }
            }
        }
        if (replying) {
            Spacer(Modifier.height(6.dp))
            Text(
                "正在处理评论…",
                color = Color(0xFF9A9A9A),
                fontSize = 13.sp,
            )
        }
    }
}

/** 底部评论输入条(白底圆角输入框 + 黑色圆形发送钮,参考图输入区惯例)。 */
@Composable
private fun CommentInputBar(
    draft: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFAFAF7))
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .background(Color.White, RoundedCornerShape(999.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .height(24.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                singleLine = true,
                enabled = enabled,
                textStyle = TextStyle(color = Color(0xFF1A1A1A), fontSize = 15.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFFE84D8A)),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) {
                            Text("发表评论…", color = Color(0xFFB5B5B5), fontSize = 15.sp)
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(38.dp)
                .background(Color(0xFF1A1A1A), CircleShape)
                .clickable(enabled = enabled && draft.isNotBlank()) { onSend() },
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
