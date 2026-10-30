package com.neethu.gouxiang.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.gouxiang.chat.MemoryCapacity
import com.neethu.gouxiang.chat.MemoryOpsParser

/**
 * 记忆页 05(基准 05-memory.xml dump;截图因 FLAG_SECURE 全黑,以 dump 为准):
 *
 * dump 结构(dump:tv_toolbar_title → tv_profile_title → profile_title_bar
 * → tv_event_title → tv_event_subtitle → fl_action_wrapper「管理」→ rv_events):
 * - 顶栏「{角色名}的记忆」+ 返回;渐变卡片底(角色头像色系),
 *   卡内大标题「{角色名}的记忆」;
 * - 画像区:标题「Ta 心中的我」+ 右侧「编辑」入口 + 逐行「我的称呼/我的身份」,
 *   空态「暂无」(dump:tv_user_name 内 tv_show_content=「暂无」);
 * - 事件区:标题「经历过的事」+ 副标题「记忆定时总结,会员记忆容量翻倍」
 *   (dump 原文;本地版附容量 X/Y)+ 条目列表(容量外条目低透明度=「逐渐模糊」);
 * - 「管理」入口切换管理模式(逐条「遗忘」,确认弹窗三连 03 §1.9);
 * - 本地化补充:「立即总结」按钮(03 §范围 1:LLM 抽取从最近对话)。
 */
@Composable
fun MemoryScreen(
    state: MemoryUiState,
    onBack: () -> Unit,
    onSummarizeNow: () -> Unit,
    onDiscardLegacy: () -> Unit = {},
    onEditProfile: (subtype: String, value: String) -> Unit,
    onForget: (id: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<String?>(null) } // null | "name" | "identity"
    var managing by remember { mutableStateOf(false) }
    var forgetTarget by remember { mutableStateOf<Long?>(null) }
    var showLegacy by remember { mutableStateOf(false) }
    var discardLegacy by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF14141A)),
    ) {
        // 顶部渐变底(角色卡氛围,浅色渐变对齐 dump 的沉浸标题)
        Box(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF8A5A7A).copy(alpha = 0.55f), Color(0xFF14141A)),
                    ),
                ),
        )
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // ── 顶栏:返回 + 标题「{角色名}的记忆」─────────────────────
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
                Spacer(Modifier.weight(1f))
                Text(
                    "${state.characterName}的记忆",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                // 说明位(占位 dump:button_dynamic_right 图标位)
                Box(Modifier.size(22.dp))
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ── 大标题「{角色名}的记忆」(dump:tv_profile_title)──────
                item {
                    Text(
                        "${state.characterName}的记忆",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (state.legacyWindow != null) {
                    item {
                        TextButton(onClick = { showLegacy = true }) {
                            Text("有来源不明的旧待处理记忆，点击查看", color = Color.White)
                        }
                    }
                }
                // ── Ta 心中的我(dump:profile_title_bar)─────────────────
                item {
                    ProfileCard(
                        state = state,
                        managing = managing,
                        onEdit = { editing = it },
                        onToggleManage = { managing = !managing },
                    )
                }
                // ── 经历过的事(dump:rl_event_title_wrapper)──────────────
                item {
                    EventsHeader(
                        state = state,
                        managing = managing,
                        onSummarizeNow = onSummarizeNow,
                        onToggleManage = { managing = !managing },
                    )
                }
                // 事件条目(faded 条目低透明度,dump 的「逐渐模糊」弱化样式)
                items(state.bandedEvents, key = { it.memory.id }) { banded ->
                    EventRow(
                        banded = banded,
                        managing = managing,
                        onForget = { forgetTarget = it },
                    )
                }
                if (state.bandedEvents.isEmpty() && !state.generating) {
                    item {
                        EmptyEventHint()
                    }
                }
                if (state.generating) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .background(Color(0xFFE84D8A), CircleShape),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("记忆生成中", color = Color(0xFFB8B8C0), fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        if (showLegacy) {
            AlertDialog(
                onDismissRequest = { showLegacy = false },
                title = { Text("来源不明的旧待处理记忆") },
                text = { Text("这些内容无法确定所属角色，不会自动处理。\n\n" +
                    state.legacyWindow.orEmpty().replace("\u0001", " | ").replace("\u0002", "\n"),
                    modifier = Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { showLegacy = false; discardLegacy = true }) { Text("丢弃") } },
                dismissButton = { TextButton(onClick = { showLegacy = false }) { Text("关闭") } },
            )
        }
        if (discardLegacy) {
            AlertDialog(
                onDismissRequest = { discardLegacy = false },
                title = { Text("丢弃旧待处理记忆？") },
                text = { Text("丢弃后无法恢复这些旧素材。已保存的聊天和记忆不受影响。") },
                confirmButton = { TextButton(onClick = { onDiscardLegacy(); discardLegacy = false }) { Text("确认丢弃") } },
                dismissButton = { TextButton(onClick = { discardLegacy = false }) { Text("取消") } },
            )
        }
        // ── 编辑画像弹层(称呼/身份)────────────────────────────────
        editing?.let { subtype ->
            ProfileEditDialog(
                subtype = subtype,
                initial = if (subtype == "name") state.profileName else state.profileIdentity,
                onDismiss = { editing = null },
                onSave = { value ->
                    onEditProfile(subtype, value)
                    editing = null
                },
            )
        }
        // ── 遗忘确认弹窗三连(03 §1.9)───────────────────────────────
        forgetTarget?.let { id ->
            AlertDialog(
                onDismissRequest = { forgetTarget = null },
                title = { Text("确认要遗忘吗?") },
                text = { Text("遗忘的记忆不可恢复") },
                confirmButton = {
                    TextButton(onClick = {
                        onForget(id)
                        forgetTarget = null
                        if (state.bandedEvents.size <= 1) managing = false
                    }) { Text("遗忘", color = Color(0xFFE84D8A)) }
                },
                dismissButton = {
                    TextButton(onClick = { forgetTarget = null }) { Text("取消") }
                },
            )
        }
    }
}

// ── 画像卡片(Ta 心中的我)────────────────────────────────────────────

@Composable
private fun ProfileCard(
    state: MemoryUiState,
    managing: Boolean,
    onEdit: (String) -> Unit,
    onToggleManage: () -> Unit,
) {    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Ta 心中的我",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            // 编辑入口(dump:「编辑」tv_profile_edit):弹层内改称呼/身份两行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onEdit("name") },
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "编辑",
                    tint = Color(0xFFE84D8A),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("编辑", color = Color(0xFFE84D8A), fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        // 我的称呼(dump:tv_user_name_label + tv_user_name)
        Text("我的称呼", color = Color(0xFFB8B8C0), fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            state.profileName?.takeIf { it.isNotBlank() } ?: "暂无",
            color = if (state.profileName.isNullOrBlank()) Color(0xFF77777F) else Color.White,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Spacer(Modifier.height(10.dp))
        // 我的身份(dump:tv_user_profile_label + tv_user_profile)
        Text("我的身份", color = Color(0xFFB8B8C0), fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            state.profileIdentity?.takeIf { it.isNotBlank() } ?: "暂无",
            color = if (state.profileIdentity.isNullOrBlank()) Color(0xFF77777F) else Color.White,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(8.dp))
        if (state.profileEmpty) {
            Text(
                "Ta 还不够了解你,快去聊天吧~",
                color = Color(0xFF9A9AA4),
                fontSize = 12.sp,
            )
        }
    }
}

// ── 事件区标题行 ─────────────────────────────────────────────────────

@Composable
private fun EventsHeader(
    state: MemoryUiState,
    managing: Boolean,
    onSummarizeNow: () -> Unit,
    onToggleManage: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "经历过的事",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            // 管理入口(dump:fl_action_wrapper 内「管理」)→ 管理态(逐条遗忘)
            Text(
                if (managing) "完成" else "管理",
                color = if (managing) Color(0xFFE84D8A) else Color(0xFFB8B8C0),
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onToggleManage() },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "记忆定时总结 · 容量 ${state.capacity.usedCapped}/${state.capacity.capacity}",
            color = Color(0xFF9A9AA4),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(10.dp))
        // 立即总结(F3 范围 1:LLM 抽取从最近对话)
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFE84D8A).copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                .clickable(enabled = !state.generating) { onSummarizeNow() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (state.generating) "总结中…" else "立即总结",
                color = Color(0xFFFF8FB8),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun EventRow(banded: MemoryCapacity.Banded, managing: Boolean, onForget: (Long) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .weight(1f)
                .background(
                    Color.White.copy(alpha = if (banded.faded) 0.03f else 0.06f),
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                text = if (banded.faded) "${banded.memory.content} · 逐渐模糊" else banded.memory.content,
                color = if (banded.faded) Color.White.copy(alpha = 0.38f) else Color.White,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        }
        if (managing) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.Delete,
                contentDescription = "遗忘",
                tint = Color(0xFFE84D8A),
                modifier = Modifier
                    .size(22.dp)
                    .clickable { onForget(banded.memory.id) },
            )
        }
    }
}

@Composable
private fun EmptyEventHint() {
    Text(
        "暂无事件记忆,快去聊天,生成你们的专属回忆吧~",
        color = Color(0xFF77777F),
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ── 画像编辑弹层 ─────────────────────────────────────────────────────

@Composable
private fun ProfileEditDialog(
    subtype: String,
    initial: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var draft by remember(subtype) { mutableStateOf(initial.orEmpty()) }
    val invalid = draft.trim().matches(Regex(".*[\\\\/:*?\"<>|「」『』].*"))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (subtype == "name") "我的称呼" else "我的身份") },
        text = {
            Column(Modifier.imePadding()) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = TextStyle(color = Color(0xFF1A1A1A), fontSize = 15.sp),
                    cursorBrush = SolidColor(Color(0xFFE84D8A)),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (draft.isEmpty()) {
                                Text(
                                    if (subtype == "name") "Ta 对我的称呼" else "Ta 对我的身份认知",
                                    color = Color(0xFF9A9A9A),
                                    fontSize = 15.sp,
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF2F2F5), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .height(if (subtype == "name") 40.dp else 96.dp),
                )
                if (invalid) {
                    Spacer(Modifier.height(6.dp))
                    Text("昵称暂不支持输入特殊字符", color = Color(0xFFE84D8A), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = draft.isNotBlank() && !invalid,
                onClick = { onSave(draft.trim()) },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
