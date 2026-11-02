package com.neethu.gouxiang.ui.status

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.neethu.gouxiang.data.CharacterStatusDetailEntity
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 状态标题时间线与按需加载的状态详情。 */
@Composable
fun StatusPanelScreen(
    state: StatusPanelUiState,
    onBack: () -> Unit,
    onClearStale: () -> Unit = {},
    backgroundFile: String? = null,
    onOpenDetail: (Long) -> Unit = {},
    onCloseDetail: () -> Unit = {},
    onRetryDetail: () -> Unit = {},
    onViewConversation: () -> Unit = onBack,
    modifier: Modifier = Modifier,
) {
    val selected = state.rows.firstOrNull { it.id == state.selectedId }
    BackHandler { if (state.selectedId != null) onCloseDetail() else onBack() }
    if (state.selectedId != null && selected != null) {
        StatusDetailScreen(
            row = selected,
            detail = state.detail,
            loading = state.detailLoading,
            failed = state.detailFailed,
            backgroundFile = backgroundFile,
            onBack = onCloseDetail,
            onRetry = onRetryDetail,
            onViewConversation = onViewConversation,
            modifier = modifier,
        )
    } else {
        StatusTimelineScreen(state, backgroundFile, onBack, onOpenDetail, onClearStale, modifier)
    }
}

@Composable
private fun StatusTimelineScreen(
    state: StatusPanelUiState,
    backgroundFile: String?,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onClearStale: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize().background(Color(0xFF263E4A))) {
        CharacterImage(backgroundFile, Modifier.fillMaxSize().blur(24.dp))
        Box(Modifier.fillMaxSize().background(Color(0xD9294350)))
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PanelHeader("状态记录", Color.White, onBack)
            Text(
                "继续聊天触发状态更新 | 由 AI 生成",
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            when {
                state.loadFailed -> TimelineMessage("会话不存在或已关闭")
                state.loading && state.empty -> TimelineMessage("加载中…")
                state.empty -> TimelineMessage("暂无状态记录，继续聊天后会自动生成")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(state.rows, key = { it.id }) { row ->
                        TimelineRow(row, onClick = { onOpenDetail(row.id) })
                    }
                    if (state.staleRows.isNotEmpty()) {
                        item {
                            TextButton(onClick = onClearStale, modifier = Modifier.padding(start = 42.dp)) {
                                Text("清理失效记录", color = Color.White.copy(alpha = 0.75f))
                            }
                        }
                    }
                    item { Spacer(Modifier.navigationBarsPadding().height(20.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TimelineMessage(message: String) {
    Text(message, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(24.dp))
}

@Composable
private fun TimelineRow(row: StatusRow, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(16.dp)) {
            Box(Modifier.padding(top = 5.dp).size(10.dp).background(Color.White.copy(alpha = 0.38f), CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(4.dp).background(Color(0xFFC2D3D9), CircleShape))
            }
            Box(Modifier.width(1.dp).height(82.dp).background(Color.White.copy(alpha = 0.12f)))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, bottom = 16.dp)) {
            Text(row.timeLabel, color = Color.White.copy(alpha = 0.56f), fontSize = 14.sp)
            Spacer(Modifier.height(9.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = if (row.stale) 0.07f else 0.12f))
                    .then(if (row.stale) Modifier else Modifier.clickable(onClick = onClick))
                    .padding(horizontal = 18.dp, vertical = 17.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.emoji, fontSize = 23.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.doing, color = Color(0xFFFFEF82).copy(alpha = if (row.stale) 0.55f else 1f), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    if (row.stale) Text("已随对话回溯失效，无法查看", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(title: String, color: Color, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
        Text(title, color = color, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
        Box(Modifier.align(Alignment.CenterStart).size(52.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "返回", tint = color, modifier = Modifier.size(21.dp))
        }
        if (action != null) Box(Modifier.align(Alignment.CenterEnd).padding(end = 18.dp)) { action() }
    }
}

@Composable
private fun StatusDetailScreen(
    row: StatusRow,
    detail: CharacterStatusDetailEntity?,
    loading: Boolean,
    failed: Boolean,
    backgroundFile: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onViewConversation: () -> Unit,
    modifier: Modifier,
) {
    val ink = Color(0xFF20242B)
    Box(modifier.fillMaxSize().background(Color(0xFFF3F6FC))) {
        CharacterImage(backgroundFile, Modifier.fillMaxSize().blur(40.dp))
        Box(Modifier.fillMaxSize().background(Color(0xEAF4F7FF)))
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PanelHeader("TA 的状态", ink, onBack) {
                Text("查看对话", modifier = Modifier.clickable(onClick = onViewConversation), color = Color(0xFF6E7D92), fontSize = 15.sp)
            }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.width(122.dp).height(166.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFFD9E0EB))) {
                            CharacterImage(backgroundFile, Modifier.fillMaxSize())
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (!detail?.location.isNullOrBlank()) DetailField("◎ 位置", detail!!.location)
                            DetailField("♡ 心情", detail?.mood?.takeIf { it.isNotBlank() } ?: row.mood.ifBlank { "暂未记录" })
                            DetailField("☁ 状态", detail?.action?.takeIf { it.isNotBlank() } ?: row.doing)
                        }
                    }
                }
                if (loading) item { DetailMessage("正在生成状态详情…") }
                if (failed) item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(20.dp)).padding(18.dp)) {
                        Text("详情生成失败，请稍后重试", color = Color(0xFF656C77), fontSize = 14.sp)
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                }
                if (detail != null) {
                    val metrics = parseMetrics(detail.metricsJson)
                    if (metrics.isNotEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            metrics.forEachIndexed { index, metric -> MetricItem(metric, index) }
                        }
                    }
                    if (detail.monologue.isNotBlank()) item { MonologueCard(detail.monologue) }
                } else if (!loading && !failed && row.thought.isNotBlank()) {
                    item { MonologueCard(row.thought) }
                }
                item { Spacer(Modifier.navigationBarsPadding().height(20.dp)) }
            }
        }
    }
}

@Composable
private fun CharacterImage(path: String?, modifier: Modifier) {
    val file = path?.let(::File)?.takeIf { it.isFile }
    if (file != null) AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

@Composable
private fun DetailField(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Color(0xFF88909C), fontSize = 13.sp)
        Text(text, color = Color(0xFF3566B4), fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DetailMessage(text: String) {
    Text(text, color = Color(0xFF747E8C), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 24.dp))
}

private data class StatusMetric(val emoji: String, val label: String, val value: Int, val description: String)

private fun parseMetrics(raw: String): List<StatusMetric> = runCatching {
    Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
        runCatching metric@{
            val obj = element.jsonObject
            val label = obj["label"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val value = obj["value"]?.jsonPrimitive?.intOrNull ?: return@metric null
            if (label.isBlank()) return@metric null
            StatusMetric(obj["emoji"]?.jsonPrimitive?.contentOrNull.orEmpty(), label, value.coerceIn(0, 100), obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }.getOrNull()
    }
}.getOrDefault(emptyList())

@Composable
private fun MetricItem(metric: StatusMetric, index: Int) {
    val colors = listOf(Color(0xFFF44750), Color(0xFFFF9874), Color(0xFFF3AFC1), Color(0xFF91C897), Color(0xFFF5C85E))
    val color = colors[index % colors.size]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${metric.emoji} ${metric.label}", color = Color(0xFF20242B), fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(metric.value.toString(), color = color, fontSize = 16.sp)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color(0xFFE9EAEB))) {
            Box(Modifier.fillMaxWidth(metric.value / 100f).height(6.dp).background(color, CircleShape))
        }
        if (metric.description.isNotBlank()) Text(metric.description, color = Color(0xFF656A72), fontSize = 15.sp, lineHeight = 23.sp)
    }
}

@Composable
private fun MonologueCard(thought: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("☁ 内心独白", color = Color(0xFF20242B), fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(thought, color = Color(0xFF656A72), fontSize = 15.sp, lineHeight = 23.sp)
    }
}
