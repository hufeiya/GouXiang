package com.neethu.gouxiang.ui.derivatives

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.neethu.gouxiang.data.ChatDerivativeEntity

/**
 * 手帐/小说/合照三页(G 工作包 G1-G3;与番外页 [com.neethu.gouxiang.ui.sidestory.SidestoryScreen]
 * 同构:顶栏 + 参数区 + 记录列表 + 详情覆盖层):
 *
 * - 手帐页(G1):查看对话中自动产生的手帐,保留历史、失败重试和删除;
 * - 小说页(G2):按章阅读对话中自动产生的小说,历史变化标「可能过时」;
 * - 合照页(G3):描述输入 + 二维生图(如实标注:不支持参考图/身份保持,
 *   不承诺人物一致性);失败保留可重试记录;成功可「设为聊天背景」。
 */
@Composable
fun HandbookScreen(
    state: ChatDerivativeUiState,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onCloseDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { if (state.detailId != null) onCloseDetail() else onBack() }
    DerivativeScreenScaffold(
        state = state,
        title = "手帐",
        subtitle = "把和 ${state.characterName} 的聊天记成一页手帐 · 由 AI 生成",
        onBack = onBack,
        emptyHint = "还没有手帐。继续聊天，新的回忆会出现在聊天提醒中，点开即可查看。",
        onOpenDetail = onOpenDetail,
        onRetry = onRetry,
        onDelete = onDelete,
        detailContent = {},
        customDetail = { record ->
            HandbookReader(record = record, characterName = state.characterName, onClose = onCloseDetail)
        },
        onCloseDetail = onCloseDetail,
        modifier = modifier,
    )
}

@Composable
fun NovelScreen(
    state: ChatDerivativeUiState,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onCloseDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { if (state.detailId != null) onCloseDetail() else onBack() }
    DerivativeScreenScaffold(
        state = state,
        title = "小说",
        subtitle = "把和 ${state.characterName} 的最近对话写成长篇 · 由 AI 生成",
        onBack = onBack,
        emptyHint = "还没有小说。继续聊天，新的故事会出现在聊天提醒中，点开即可查看。",
        onOpenDetail = onOpenDetail,
        onRetry = onRetry,
        onDelete = onDelete,
        detailContent = { record ->
            val novel = remember(record.bodyJson) { novelOf(record) }
            if (novel?.introduction?.isNotBlank() == true) {
                ParagraphCard(body = novel.introduction, title = "简介")
            }
            novel?.chapters?.forEachIndexed { index, chapter ->
                ParagraphCard(
                    body = chapter.content,
                    title = "第${index + 1}章 " + chapter.title.ifBlank { "" } +
                        (chapter.overview.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                )
            }
            if (novel == null && record.isSucceeded) {
                ParagraphCard(body = "(这部小说内容为空)")
            }
        },
        onCloseDetail = onCloseDetail,
        modifier = modifier,
    )
}

@Composable
fun PhotoScreen(
    state: ChatDerivativeUiState,
    description: String,
    onDescriptionChange: (String) -> Unit,
    onBack: () -> Unit,
    onGenerate: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onSetBackground: (ChatDerivativeEntity) -> Unit,
    onClearBackground: () -> Unit,
    onCloseDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { if (state.detailId != null) onCloseDetail() else onBack() }
    DerivativeScreenScaffold(
        state = state,
        title = "合照",
        subtitle = "和 ${state.characterName} 拍一张二维合照 · 由 AI 生成",
        onBack = onBack,
        parameterSection = {
            SectionTitle("拍照描述", "写下你想和 TA 合影的场景与姿势")
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = description,
                    onValueChange = { onDescriptionChange(it.take(60)) },
                    placeholder = {
                        Text("例:在傍晚的天台,肩并肩看晚霞", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontSize = 14.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 3,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF7FB2FF),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        cursorColor = Color(0xFF7FB2FF),
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "当前为二维文生图:没有参考照片与身份保持能力," +
                            "不能保证人物长相/样貌一致,仅供氛围参考",
                        color = Color(0xFFFFCF88),
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${description.length}/60", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            val generating = state.rows.any { it.kind == ChatDerivativeEntity.KIND_PHOTO && it.isGenerating }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(Color(0xFF7FB2FF).copy(alpha = if (generating) 0.2f else 0.85f), RoundedCornerShape(12.dp))
                    .clickable(enabled = !generating && description.isNotBlank()) { onGenerate() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (generating) "照片生成中…" else "生成合照",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        emptyHint = "还没有合照,写下描述拍第一张吧",
        onOpenDetail = onOpenDetail,
        onRetry = onRetry,
        onDelete = onDelete,
        detailContent = { record ->
            if (record.imageFile.isNotBlank()) {
                RecordImage(record.imageFile)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .background(Color(0xFF7FB2FF).copy(alpha = 0.2f), RoundedCornerShape(999.dp))
                            .clickable { onSetBackground(record) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text("设为聊天背景", color = Color(0xFF7FB2FF), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Box(
                        Modifier
                            .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                            .clickable { onClearBackground() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text("还原默认背景", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "设为聊天背景后,打开和 TA 的聊天即可看到;也可随时在这里还原默认",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(8.dp))
            } else if (record.isSucceeded) {
                ParagraphCard(body = "(这张合照的图片缺失,可删除后重新生成)")
            }
            val desc = remember(record.bodyJson) { photoDescriptionOf(record) }
            if (desc.isNotBlank()) ParagraphCard(body = "拍照描述:$desc")
        },
        onCloseDetail = onCloseDetail,
        modifier = modifier,
    )
}

// ── 共享骨架(顶栏 + 参数区 + 记录列表 + 详情覆盖层)────────────────

@Composable
private fun DerivativeScreenScaffold(
    state: ChatDerivativeUiState,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    parameterSection: (@Composable () -> Unit)? = null,
    emptyHint: String,
    onOpenDetail: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    detailContent: @Composable (ChatDerivativeEntity) -> Unit,
    customDetail: (@Composable (ChatDerivativeEntity) -> Unit)? = null,
    onCloseDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF14141A)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ArrowBackIosNew,
                        contentDescription = "返回",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        subtitle,
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                state.error?.let { error ->
                    Text(
                        error,
                        color = Color(0xFFFFB4CD),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                parameterSection?.let { section ->
                    section()
                    Spacer(Modifier.height(16.dp))
                }
                SectionTitle("记录", "重启后仍在;失败可以重试")
                if (state.rows.isEmpty()) {
                    Text(
                        emptyHint,
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                state.rows.forEach { record ->
                    RecordCard(
                        record = record,
                        onOpen = { onOpenDetail(record.id) },
                        onRetry = { onRetry(record.id) },
                        onDelete = { onDelete(record.id) },
                    )
                }
                Spacer(Modifier.height(24.dp))
                Spacer(Modifier.navigationBarsPadding())
            }
        }

        state.detail?.let { record ->
            if (customDetail != null) customDetail(record)
            else DetailOverlay(record = record, title = title, content = detailContent, onClose = onCloseDetail)
        }
    }
}

/** 记录卡(状态机四态:生成中/失败可重试/成功可查看/删除;与番外页同款)。 */
@Composable
private fun RecordCard(
    record: ChatDerivativeEntity,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Box(
            Modifier
                .size(width = 56.dp, height = 74.dp)
                .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (record.imageFile.isNotBlank()) {
                AsyncImage(
                    model = java.io.File(record.imageFile),
                    contentDescription = "配图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    when (record.kind) {
                        ChatDerivativeEntity.KIND_HANDBOOK -> "📔"
                        ChatDerivativeEntity.KIND_NOVEL -> "📖"
                        else -> "📷"
                    },
                    fontSize = 20.sp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                record.title.ifBlank { kindLabel(record.kind) },
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            when {
                record.isGenerating -> Text(
                    "生成中…(退出不影响生成,完成后通知你)",
                    color = Color(0xFFF2C94C),
                    fontSize = 12.sp,
                )
                record.isFailed -> Text(
                    "生成失败:${record.failureReason.ifBlank { "请检查网络与模型配置" }}",
                    color = Color(0xFFFFB4CD),
                    fontSize = 12.sp,
                )
                else -> Text(
                    buildString {
                        append("点击查看 · ")
                        append(recordImageNote(record.imageStatus))
                        if (record.isStale) append(" · 对话已回溯,内容可能过时")
                    },
                    color = if (record.isStale) Color(0xFFFFCF88) else Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                com.neethu.gouxiang.ui.innervoice.formatVoiceDate(record.createdAt),
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            record.isGenerating -> Unit
            record.isFailed -> ActionChip("重试", Color(0xFFF2C94C), onRetry)
            else -> ActionChip("查看", Color(0xFF7FB2FF), onOpen)
        }
        ActionChip("删除", Color.White.copy(alpha = 0.5f), onDelete)
    }
}

/** 配图状态短注(文本先出、图片可选:失败明确说,不假成功)。 */
internal fun recordImageNote(imageStatus: String): String = when (imageStatus) {
    ChatDerivativeEntity.IMAGE_COMPLETE -> "含配图"
    ChatDerivativeEntity.IMAGE_FAILED -> "配图生成失败,文字已保存"
    ChatDerivativeEntity.IMAGE_PENDING -> "配图生成中…"
    else -> "纯文字"
}

@Composable
internal fun ActionChip(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
internal fun RecordImage(file: String) {
    AsyncImage(
        model = java.io.File(file),
        contentDescription = "配图",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
    )
}

@Composable
internal fun ParagraphCard(body: String, title: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        if (!title.isNullOrBlank()) {
            Text(title, color = Color(0xFFF2C94C), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
        }
        Text(
            body,
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 14.sp,
            lineHeight = 22.sp,
        )
    }
}

/** 详情覆盖层(全屏;「由 AI 生成」角标必带,回溯标「可能过时」)。 */
@Composable
private fun DetailOverlay(
    record: ChatDerivativeEntity,
    title: String,
    content: @Composable (ChatDerivativeEntity) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC0B0B10))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { /* 拦截点击,不透传 */ },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(40.dp))
                Text(
                    record.title.ifBlank { title },
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .background(Color.White.copy(alpha = 0.16f), CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onClose() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                content(record)
                if (record.isStale) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "对话已回溯,这条内容可能已过时",
                        color = Color(0xFFFFCF88),
                        fontSize = 12.sp,
                    )
                }
                Text(
                    "由 AI 生成 · 虚构叙事内容",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                Spacer(Modifier.height(16.dp))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}
