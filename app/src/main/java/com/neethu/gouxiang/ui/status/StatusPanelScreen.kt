package com.neethu.gouxiang.ui.status

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 状态面板(E 工作包 E3,「TA 的状态」本地版;对齐 08-status-system.md 的
 * 当前态卡 + 历史时间轴结构,去商业化字段):
 *
 * - 顶栏「{角色名}的状态」+ 返回;
 * - 当前状态卡:emoji + 正在做的事 + 心情/内心想法 + 生成时间与来源
 *   (E3:可查看来源);无记录时提示「暂无状态记录,聊天中会自动生成」;
 * - 状态记录时间轴(生成时间倒序,不含当前卡那条的重复展示——当前卡即最新
 *   active,时间轴从第二条开始);
 * - 失效记录区:已随回溯失效的行置灰 + 「已随回溯失效」标签 + 「清理失效记录」
 *   (对齐猫箱冲突记录可清理语义,本地版直接删除 stale 行)。
 */
@Composable
fun StatusPanelScreen(
    state: StatusPanelUiState,
    onBack: () -> Unit,
    onClearStale: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF14141A)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF5A6A8A).copy(alpha = 0.5f), Color(0xFF14141A)),
                    ),
                ),
        )
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding(),
        ) {
            // ── 顶栏:返回 + 标题「{角色名}的状态」─────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
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
                Spacer(Modifier.width(10.dp))
                Text(
                    if (state.characterName.isBlank()) "TA 的状态" else "${state.characterName}的状态",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (state.loadFailed) {
                Text(
                    "会话不存在或已关闭",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(20.dp),
                )
                return@Column
            }
            if (state.loading && state.empty) {
                Text(
                    "加载中…",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(20.dp),
                )
                return@Column
            }
            if (state.empty) {
                Text(
                    "暂无状态记录,和 TA 聊几轮后会自动生成(断网时保持最近有效状态,无记录走规则状态条)",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(20.dp),
                )
                return@Column
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { CurrentCard(state.current!!) }
                val history = state.rows.filter { !it.stale }.drop(1)
                if (history.isNotEmpty()) {
                    item {
                        SectionTitle("状态记录")
                    }
                }
                items(history, key = { "h-${it.id}" }) { row -> RecordRow(row) }
                if (state.staleRows.isNotEmpty()) {
                    item { SectionTitle("失效记录(因对话回溯)") }
                    items(state.staleRows, key = { "s-${it.id}" }) { row -> RecordRow(row, stale = true) }
                    item {
                        TextButton(onClick = onClearStale) {
                            Text("清理失效记录", color = Color(0xFFF2C94C), fontSize = 14.sp)
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.6f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** 当前置顶卡(emoji 大号 + doing 主行 + mood/thought 副行 + 时间与来源)。 */
@Composable
private fun CurrentCard(row: StatusRow) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.emoji, fontSize = 26.sp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(row.doing, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (row.mood.isNotBlank()) {
                    Text("心情:${row.mood}", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                }
            }
        }
        if (row.thought.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("内心:${row.thought}", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${row.timeLabel} · ${row.sourceLabel}",
            color = Color(0xFFF2C94C).copy(alpha = 0.85f),
            fontSize = 12.sp,
        )
    }
}

/** 时间轴行(当前卡之外的历史记录;stale 行置灰)。 */
@Composable
private fun RecordRow(row: StatusRow, stale: Boolean = false) {
    val alpha = if (stale) 0.45f else 1f
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = if (stale) 0.04f else 0.06f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.emoji, fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(row.doing, color = Color.White.copy(alpha = alpha), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (row.mood.isNotBlank() || row.thought.isNotBlank()) {
                val detail = listOf(
                    row.mood.takeIf { it.isNotBlank() }?.let { "心情:$it" },
                    row.thought.takeIf { it.isNotBlank() }?.let { "内心:$it" },
                ).filterNotNull().joinToString(" · ")
                Text(detail, color = Color.White.copy(alpha = alpha * 0.7f), fontSize = 12.sp)
            }
            Text(
                "${row.timeLabel} · ${row.sourceLabel}",
                color = Color.White.copy(alpha = alpha * 0.55f),
                fontSize = 11.sp,
            )
        }
    }
}
