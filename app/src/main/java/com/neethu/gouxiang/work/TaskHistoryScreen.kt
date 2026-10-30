package com.neethu.gouxiang.work

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.gouxiang.chat.DerivativeFailureKind
import com.neethu.gouxiang.chat.DerivativeKind
import com.neethu.gouxiang.data.DerivativeTaskHistoryRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 任务历史页(M0 收尾工作包 1):全部生成任务(手动/自动)的持久化历史——
 * 来源/类型/角色会话/requestId/状态/失败原因分类/时间/结果 ID 一行一条,
 * 失败行可重试、进行中行可取消。入口:我的页「任务历史」;验收亦可走
 * ai_cmd=tasks(task_retry/task_cancel)。
 */
@Composable
fun TaskHistoryScreen(
    items: List<TaskHistoryItem>,
    loading: Boolean,
    onBack: () -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "任务历史",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        Text(
            text = "生成任务的执行记录：失败任务可重试，排队/进行中的任务可取消。自动任务重试仍受内容生成开关约束。",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (loading && items.isEmpty()) {
            Text(
                text = "加载中…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else if (items.isEmpty()) {
            Text(
                text = "还没有生成任务。去聊天页或角色主页点「生成」试试。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.record.workId }) { item ->
                    TaskHistoryRow(item, onRetry, onCancel)
                }
            }
        }
    }
}

@Composable
private fun TaskHistoryRow(
    item: TaskHistoryItem,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    val record = item.record
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${taskKindLabel(record.kind)} · ${if (record.source == "AUTOMATIC") "自动" else "手动"}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TaskStateBadge(record.state)
        }
        Spacer(Modifier.height(4.dp))
        val parts = buildList {
            item.characterName?.let { add("角色 $it") }
            add(if (record.conversationId > 0) "会话 ${record.conversationId}" else "批扫任务")
            add(formatTime(record.createdAt))
        }
        Text(
            text = parts.joinToString(" · "),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val failureLine = record.failureReason?.let { reason ->
            val kind = DerivativeFailureKind.fromName(record.failureKind)
            if (record.state == "failed") "失败（${kind.label}）：$reason" else reason
        }
        if (failureLine != null) {
            Text(
                text = failureLine,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = Color(0xFFFFB4A0),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        val results = record.resultIds?.takeIf { it.isNotBlank() }
        if (results != null) {
            Text(
                text = "结果 ID：${results.replace(',', '、')}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (record.state == "failed" || record.state in setOf("queued", "running")) {
            Row(modifier = Modifier.padding(top = 6.dp)) {
                if (record.state == "failed") {
                    Text(
                        text = "重试任务",
                        fontSize = 13.sp,
                        color = Color(0xFF9FCBFF),
                        modifier = Modifier.clickable { onRetry(record.workId) },
                    )
                    Spacer(Modifier.width(16.dp))
                }
                if (record.state in setOf("queued", "running")) {
                    Text(
                        text = "取消任务",
                        fontSize = 13.sp,
                        color = Color(0xFF9FCBFF),
                        modifier = Modifier.clickable { onCancel(record.workId) },
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "请求 ${shortId(record.requestId)}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun TaskStateBadge(state: String) {
    val (text, color) = when (state) {
        "queued" -> "排队中" to Color(0xFF9FCBFF)
        "running" -> "进行中" to Color(0xFFFFE08A)
        "succeeded" -> "成功" to Color(0xFFB7E3A6)
        "failed" -> "失败" to Color(0xFFFFB4A0)
        "cancelled" -> "已取消" to MaterialTheme.colorScheme.onSurfaceVariant
        "skipped" -> "已跳过" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> state to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        fontSize = 12.sp,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 任务历史页行模型(角色名解析后回填,避免逐行查库)。 */
data class TaskHistoryItem(
    val record: DerivativeTaskHistoryRow,
    val characterName: String? = null,
)

internal fun taskKindLabel(kind: String): String = when (runCatching { DerivativeKind.valueOf(kind) }.getOrNull()) {
    DerivativeKind.INNER_VOICE -> "心声"
    DerivativeKind.MOMENT -> "朋友圈动态"
    DerivativeKind.MEMORY_SUMMARY -> "记忆总结"
    DerivativeKind.PROACTIVE_OUTBOX -> "主动消息"
    DerivativeKind.HANDBOOK -> "手帐"
    DerivativeKind.NOVEL -> "小说"
    DerivativeKind.PHOTO -> "合照"
    null -> kind
}

private fun formatTime(at: Long): String =
    if (at <= 0L) "" else SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(at))

private fun shortId(id: String): String = if (id.length <= 10) id else id.take(10) + "…"
