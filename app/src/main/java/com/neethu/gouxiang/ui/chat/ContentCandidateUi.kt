package com.neethu.gouxiang.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.neethu.gouxiang.data.ContentCandidateEntity
import kotlinx.coroutines.delay
import java.io.File

private const val REMINDER_INTERVAL_MS = 10 * 60 * 1000L

private fun candidateKind(kind: String): String = when (kind) {
    "INNER_VOICE" -> "心声"
    "MOMENT" -> "朋友圈"
    "HANDBOOK" -> "手账"
    "NOVEL" -> "小说"
    else -> "想法"
}

/** 会话内的提醒与持久候选入口；展示过的候选仍可从列表找到。 */
@Composable
internal fun ContentCandidateReminder(
    conversationId: Long?,
    candidates: List<ContentCandidateEntity>,
    canShow: Boolean,
    onSeen: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("content_candidate_reminders", 0) }
    val available = candidates.filter { it.conversationId == conversationId }
    val unseen = available.filter { it.reminderStatus == "UNSEEN" && it.bodyStatus != "STALE" }
    var presented by remember(conversationId) { mutableStateOf<List<ContentCandidateEntity>>(emptyList()) }
    var listOpen by remember(conversationId) { mutableStateOf(false) }
    // 将已展示状态写入数据库，并持久化冷却时间；再次进入不会重复弹出同一条。
    LaunchedEffect(conversationId, unseen.map { it.id }, canShow, presented.isEmpty()) {
        if (conversationId == null || !canShow || unseen.isEmpty() || presented.isNotEmpty()) return@LaunchedEffect
        val key = "last_$conversationId"
        val remaining = REMINDER_INTERVAL_MS - (System.currentTimeMillis() - prefs.getLong(key, 0L))
        if (remaining > 0) delay(remaining.coerceAtMost(REMINDER_INTERVAL_MS))
        presented = unseen
        prefs.edit().putLong(key, System.currentTimeMillis()).apply()
        unseen.forEach { onSeen(it.id) }
    }
    if (available.isEmpty() && presented.isEmpty()) return

    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        if (presented.isNotEmpty() && canShow) {
            val first = available.firstOrNull { it.id == presented.first().id } ?: presented.first()
            val label = when {
                presented.size > 1 -> "TA 有 ${presented.size} 个新想法"
                first.bodyStatus == "BODY_QUEUED" || first.bodyStatus == "BODY_GENERATING" -> "正在写 · ${first.title}"
                first.bodyStatus == "READY" -> "TA 写下了 · ${first.title}"
                else -> "有一篇${candidateKind(first.kind)} · ${first.title}"
            }
            Row(
                Modifier.weight(1f, fill = false)
                    .background(Color(0xE6323038), RoundedCornerShape(24.dp))
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$label  ›", color = Color.White, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).clickable {
                        if (presented.size == 1) onOpen(first.id) else listOpen = true
                        presented = emptyList()
                    }.padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
                )
                Text("×", color = Color.White.copy(alpha = .8f), fontSize = 19.sp,
                    modifier = Modifier.semantics { contentDescription = "收起提醒" }.clickable {
                        presented.forEach { onDismiss(it.id) }
                        presented = emptyList()
                    }.padding(horizontal = 12.dp, vertical = 10.dp))
            }
            Spacer(Modifier.width(8.dp))
        }
        Box(
            Modifier.size(48.dp).background(Color(0xD93A3A3A), CircleShape)
                .semantics { contentDescription = "查看所有想法" }
                .clickable { listOpen = true }, contentAlignment = Alignment.Center,
        ) { Text("✦", color = Color.White, fontSize = 19.sp) }
    }
    if (listOpen) {
        AlertDialog(
            onDismissRequest = { listOpen = false },
            title = { Text("TA 的新想法") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(available, key = { it.id }) { candidate ->
                        Column(Modifier.fillMaxWidth().clickable {
                            listOpen = false
                            onOpen(candidate.id)
                        }.padding(vertical = 12.dp)) {
                            Text(candidate.title, fontWeight = FontWeight.Medium)
                            Text(candidateKind(candidate.kind), color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { listOpen = false }) { Text("关闭") } },
        )
    }
}

/** 与具体候选绑定的详情，正文和图片分别反映实际生成结果。 */
@Composable
internal fun ContentCandidateDetail(
    candidate: ContentCandidateEntity,
    body: String?,
    imageFile: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRetryImage: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Color(0xFFFFFAF8)).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 32.sp, color = Color(0xFF30282E),
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 20.dp))
            Text(candidateKind(candidate.kind), fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(24.dp))
            Text(candidate.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF30282E))
            Spacer(Modifier.height(24.dp))
            when (candidate.bodyStatus) {
                "STALE" -> Text("对话已变化，请继续聊天，等待新的内容", color = Color(0xFF8B727A))
                "BODY_FAILED" -> {
                    Text("生成失败，请重试", color = Color(0xFF8B727A))
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                "READY" -> {
                    if (candidate.sourceStale) {
                        Text("对话已变化，此内容可能过时", color = Color(0xFF8B727A), fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))
                    }
                    Text(body.orEmpty().ifBlank { "内容暂时不可用" }, fontSize = 16.sp,
                        lineHeight = 26.sp, color = Color(0xFF30282E))
                    val image = imageFile?.let(::File)?.takeIf { it.isFile }
                    if (image != null) {
                        Spacer(Modifier.height(20.dp))
                        AsyncImage(model = image, contentDescription = "内容配图", contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth())
                    } else if (candidate.imageStatus == "FAILED") {
                        Spacer(Modifier.height(16.dp))
                        Text("配图生成失败，正文已保存", color = Color(0xFF8B727A), fontSize = 13.sp)
                        if (!candidate.sourceStale) {
                            TextButton(onClick = onRetryImage) { Text("重试配图") }
                        }
                    }
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在写…", color = Color(0xFF8B727A))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
