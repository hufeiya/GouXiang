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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.neethu.gouxiang.data.MomentCommentEntity
import com.neethu.gouxiang.data.MomentEntity
import com.neethu.gouxiang.ui.messages.formatRelativeTime

/**
 * 朋友圈页 06(基准 06-moments.png;dump 06-moments.xml 较薄,以截图还原):
 *
 * - 沉浸式头部:淡紫黄渐变封面(AI 星球风格)+ 左上返回 + 右上「邀请角色」
 *   黄胶囊(本地版范围 5:不实现邀约,按钮改为「生成动态」——原版
 *   锁卡/邀约入口隐藏,不出现待解锁字样);
 * - 封面右下:角色名 + 圆形头像,叠在圆角内容区上方(参考截图构图);
 * - 动态卡(dump 每卡):圆形头像 + 名字(tv_author_name)+ 正文 + 配图 +
 *   时间「刚刚」(相对时间,对齐 02 消息列表同款规则)+ 点赞/评论两枚图标
 *   (dump [836,1581]/[958,1581] 两 ImageView);
 * - 评论区:用户评论与角色回复列在卡下方(角色回复行首带名字);
 * - 底部评论输入条:「发表评论:」占位(10 §2.1 reply_to_default 语义,
 *   本地化「说点什么…」),发送 = 用户评论落库 → 角色 LLM 回复(楼中楼)。
 */
@Composable
fun MomentsScreen(
    state: MomentsUiState,
    avatarFile: String?,
    onBack: () -> Unit,
    onGenerate: () -> Unit,
    onLike: (MomentEntity) -> Unit,
    onSendComment: (MomentEntity, String) -> Unit,
    onRetryImage: (com.neethu.gouxiang.ui.ImageGenerationState) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }
    var draft by remember { mutableStateOf("") }
    // 评论目标:点击评论图标切当前输入条挂靠的动态(空 = 最新一条)
    var commentTargetId by remember { mutableStateOf<Long?>(null) }

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
            // 右上黄胶囊:本地版 =「生成动态」(原版「邀请角色」按范围 5 隐藏)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 14.dp, top = 12.dp)
                    .background(Color(0xFFF6E749), RoundedCornerShape(999.dp))
                    .clickable(enabled = !state.generating) { onGenerate() }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                if (state.generating) {
                    Text("生成中…", color = Color(0xFF1A1A1A), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                } else {
                    Icon(
                        Icons.Outlined.AddCircleOutline,
                        contentDescription = null,
                        tint = Color(0xFF1A1A1A),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("生成动态", color = Color(0xFF1A1A1A), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
            // 封面右下:角色名 + 圆形头像(参考截图构图)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp),
            ) {
                Text(
                    state.characterName,
                    color = Color(0xFF6C6C6C),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(10.dp))
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
                            state.characterName.take(1),
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
            items(state.items, key = { it.id }) { post ->
                MomentCard(
                    post = post,
                    comments = state.comments[post.id].orEmpty(),
                    characterName = state.characterName,
                    avatarFile = avatarFile,
                    replying = state.replyingToId == post.id,
                    onLike = { onLike(post) },
                    image = state.imageResults[post.id],
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
                            "点右上「生成动态」,看看${state.characterName}的日常",
                            color = Color(0xFF9A9A9A),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
        // ── 底部评论输入条(发送 = 用户评论 → 角色回复)──────────────────
        CommentInputBar(
            draft = draft,
            enabled = state.replyingToId == null,
            onDraftChange = { draft = it },
            onSend = {
                val post = state.items.firstOrNull { it.id == commentTargetId }
                    ?: state.items.firstOrNull()
                val body = draft.trim()
                if (post != null && body.isNotEmpty()) {
                    commentTargetId = post.id
                    onSendComment(post, body)
                    draft = ""
                }
            },
        )
    }
}

/**
 * 单条动态卡:头像+名字行 → 正文 → 配图(可选)→ 时间 + 点赞/评论图标
 * → 评论区(用户评论 / 角色回复)。
 */
@Composable
private fun MomentCard(
    post: MomentEntity,
    comments: List<MomentCommentEntity>,
    characterName: String,
    avatarFile: String?,
    replying: Boolean,
    onLike: () -> Unit,
    image: com.neethu.gouxiang.ui.ImageGenerationState?,
    onRetryImage: (com.neethu.gouxiang.ui.ImageGenerationState) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        // 作者行:圆形头像 + 名字(dump 每卡头像 [55,*][188,*] + 名字)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(Color.White, CircleShape)
                    .padding(1.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarFile != null) {
                    AsyncImage(
                        model = java.io.File(avatarFile),
                        contentDescription = characterName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFEDEDED), CircleShape),
                    )
                } else {
                    Text(characterName.take(1), color = Color(0xFF9A9A9A), fontSize = 16.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                characterName,
                color = Color(0xFF7A6BD6),
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
        // 时间 + 互动行(参考图「刚刚」左置;点赞/评论两枚图标右置)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatRelativeTime(post.at),
                color = Color(0xFFB5B5B5),
                fontSize = 14.sp,
            )
            Spacer(Modifier.weight(1f))
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
                modifier = Modifier.size(22.dp),
            )
        }
        // 评论区(用户评论与角色回复;角色回复行首带名字)
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
                    val isUser = comment.authorKind == "user"
                    Row {
                        if (!isUser) {
                            Text(
                                "$characterName:",
                                color = Color(0xFF7A6BD6),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            comment.text,
                            color = if (isUser) Color(0xFF3A3A3A) else Color(0xFF3A3A3A),
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                        )
                    }
                }
            }
        }
        if (replying) {
            Spacer(Modifier.height(6.dp))
            Text(
                "$characterName 正在回复…",
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
