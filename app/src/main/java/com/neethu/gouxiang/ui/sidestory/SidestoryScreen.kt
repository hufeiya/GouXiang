package com.neethu.gouxiang.ui.sidestory

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.neethu.gouxiang.data.SidestoryRecordEntity
import com.neethu.gouxiang.sidestory.SidestoryCatalog
import com.neethu.gouxiang.sidestory.SidestoryOutput

/**
 * 番外剧场页(F 工作包 F3 入口 + F4 心动事件;单屏三区):
 *
 * - 「心动事件」区:3 个事件卡(查Ta手机/查Ta状态/让Ta吃醋;独立按钮与
 *   结果展示,与聊天风格「心动模式」命名区分——本区文案一律「心动事件」);
 * - 「番外模板」区:15 个模板两列卡(标题+副标题+按钮文案);生成中/同模板
 *   未完成时按钮置灰防重复;
 * - 「记录」区:历史记录卡(生成中/失败可重试/成功可查看/删除;配图失败
 *   保留文字并标注),点卡片开详情。
 *
 * 文本先出、图片可选:成功记录缺图时按纯文字卡渲染并提示,不阻塞查看。
 * 详情层为全屏覆盖(后声明者在上),「由 AI 生成」角标必带。
 */
@Composable
fun SidestoryScreen(
    state: SidestoryUiState,
    onBack: () -> Unit,
    onGenerateTemplate: (String) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onCloseDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { if (state.detailId != null) onCloseDetail() else onBack() }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF14141A)),
        ) {
            // ── 顶栏(返回 + 标题;记忆页/任务历史页同惯例)────────────
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
                    Text("番外剧场", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "和 ${state.characterName} 的图文小剧场 · 由 AI 生成",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
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
                // ── 心动事件(F4)───────────────────────────────────────
                SectionTitle("心动事件", "点一下就开一次 · 结果在本页记录里查看")
                SidestoryCatalog.heartbeatEvents.forEach { event ->
                    EntryCard(
                        title = event.name,
                        subtitle = event.subtitle,
                        button = event.button,
                        accent = Color(0xFFE84D8A),
                        generating = state.rows.any {
                            it.record.templateKey == event.key && it.record.isGenerating
                        },
                        onClick = { onGenerateTemplate(event.key) },
                    )
                }
                Spacer(Modifier.height(16.dp))
                // ── 番外模板(F3)───────────────────────────────────────
                SectionTitle("番外模板", "选一个模板,让 TA 演给你看")
                SidestoryCatalog.entries.chunked(2).forEach { pair ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        pair.forEach { entry ->
                            TemplateCell(
                                title = entry.name,
                                subtitle = entry.subtitle,
                                generating = state.rows.any {
                                    it.record.templateKey == entry.key && it.record.isGenerating
                                },
                                modifier = Modifier.weight(1f),
                                onClick = { onGenerateTemplate(entry.key) },
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(16.dp))
                // ── 记录(列表/详情/删除/重试)──────────────────────────
                SectionTitle("记录", "重启后仍在;失败可以重试")
                if (state.rows.isEmpty()) {
                    Text(
                        "还没有记录,选上面的模板生成一条吧",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                state.rows.forEach { row ->
                    RecordCard(
                        row = row,
                        onOpen = { onOpenDetail(row.record.id) },
                        onRetry = { onRetry(row.record.id) },
                        onDelete = { onDelete(row.record.id) },
                    )
                }
                Spacer(Modifier.height(24.dp))
                Spacer(Modifier.navigationBarsPadding())
            }
        }

        // ── 详情层(全屏覆盖;后声明者在上)────────────────────────────
        state.detail?.let { row ->
            DetailOverlay(row = row, onClose = onCloseDetail)
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
    }
    Spacer(Modifier.height(6.dp))
}

/** 心动事件卡(横幅:标题+副标题+右端按钮)。 */
@Composable
private fun EntryCard(
    title: String,
    subtitle: String,
    button: String,
    accent: Color,
    generating: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .clickable(enabled = !generating) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .background(accent.copy(alpha = if (generating) 0.2f else 0.85f), RoundedCornerShape(999.dp))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                if (generating) "生成中…" else button,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 模板两列卡(标题+副标题;整卡点击即生成)。 */
@Composable
private fun TemplateCell(
    title: String,
    subtitle: String,
    generating: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .height(96.dp)
            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .clickable(enabled = !generating) { onClick() }
            .padding(12.dp),
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (generating) "生成中…" else subtitle,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 记录卡(状态机四态:生成中/失败可重试/成功可查看/删除)。 */
@Composable
private fun RecordCard(
    row: SidestoryRecordRow,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val record = row.record
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        // 左侧配图占位(可选配图;失败/纯文字画底色)
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
                Text("🎬", fontSize = 20.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                record.title.ifBlank { row.templateName },
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            when {
                record.isGenerating -> Text(
                    "生成中…${SidestoryCatalog.byKey(record.templateKey)?.loading ?: ""}",
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
        // 操作列:失败→重试;成功→查看;删除恒可用
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
    SidestoryRecordEntity.IMAGE_COMPLETE -> "含配图"
    SidestoryRecordEntity.IMAGE_FAILED -> "配图生成失败,文字已保存"
    SidestoryRecordEntity.IMAGE_PENDING -> "配图生成中…"
    else -> "纯文字"
}

@Composable
private fun ActionChip(label: String, color: Color, onClick: () -> Unit) {
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

/** 详情覆盖层(分区/多格竖排 + 可选配图 + 「由 AI 生成」角标)。 */
@Composable
private fun DetailOverlay(row: SidestoryRecordRow, onClose: () -> Unit) {
    val record = row.record
    val pages = remember(record.bodyJson) { SidestoryOutput.fromJson(record.bodyJson) }
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
                    record.title.ifBlank { row.templateName },
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
                if (record.imageFile.isNotBlank()) {
                    AsyncImage(
                        model = java.io.File(record.imageFile),
                        contentDescription = "封面配图",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                pages.forEach { page ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                            .padding(14.dp),
                    ) {
                        if (page.title.isNotBlank()) {
                            Text(page.title, color = Color(0xFFF2C94C), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(
                            page.body,
                            color = Color.White.copy(alpha = 0.88f),
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                        )
                    }
                }
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
