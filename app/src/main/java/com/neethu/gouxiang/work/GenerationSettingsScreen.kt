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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 内容生成设置：正文、配图及其他后台功能分别保存，即时生效。 */
@Composable
fun GenerationSettingsScreen(
    snapshot: GenerationSettingsSnapshot,
    onToggle: (key: String, enabled: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // 顶栏:返回 + 标题(与记忆页/历史页导航惯例一致)
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
                text = "内容生成",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }

        // 标题发现与正文、配图是独立的设置。
        Text(
            text = "关闭正文自动生成时，仍会根据对话生成标题；点开标题后生成正文。开启时正文也会自动生成。标题与正文均会消耗模型调用，配图受独立开关控制。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        SwitchRow(
            title = "状态详情自动生成",
            subtitle = "开启后自动生成详情；关闭后点开标题生成详情",
            checked = snapshot.statusDetailAuto,
            onCheckedChange = { onToggle(com.neethu.gouxiang.ai.GenerationPrefs.KEY_STATUS_DETAIL_AUTO, it) },
        )
        SwitchRow(
            title = "心声正文自动生成",
            subtitle = "开启后自动生成正文；关闭后点开标题生成正文",
            checked = snapshot.innerVoiceAuto,
            onCheckedChange = { onToggle(KEY_INNER_VOICE_AUTO, it) },
        )
        SwitchRow(
            title = "朋友圈正文自动生成",
            subtitle = "开启后自动生成正文；关闭后点开标题生成正文",
            checked = snapshot.momentsDaily,
            onCheckedChange = { onToggle(KEY_MOMENTS_DAILY, it) },
        )
        SwitchRow(
            title = "心声配图",
            subtitle = "关闭后新心声仅生成文字；手动补图仍可使用",
            checked = snapshot.innerVoiceImages,
            onCheckedChange = { onToggle(com.neethu.gouxiang.ai.GenerationPrefs.KEY_INNER_VOICE_IMAGES, it) },
        )
        SwitchRow(
            title = "朋友圈配图",
            subtitle = "关闭后新动态仅生成文字；手动补图仍可使用",
            checked = snapshot.momentsImages,
            onCheckedChange = { onToggle(com.neethu.gouxiang.ai.GenerationPrefs.KEY_MOMENTS_IMAGES, it) },
        )
        SwitchRow(
            title = "番外配图",
            subtitle = "关闭后新番外仅生成文字；失败保留文字，可在记录里查看",
            checked = snapshot.sidestoryImages,
            onCheckedChange = { onToggle(com.neethu.gouxiang.ai.GenerationPrefs.KEY_SIDESTORY_IMAGES, it) },
        )
        SwitchRow(
            title = "手帐配图",
            subtitle = "关闭后新手帐仅生成文字；失败保留文字，可在记录里查看",
            checked = snapshot.handbookImages,
            onCheckedChange = { onToggle(com.neethu.gouxiang.ai.GenerationPrefs.KEY_HANDBOOK_IMAGES, it) },
        )
        SwitchRow(
            title = "隔夜记忆总结",
            subtitle = "开启后闲置 8 小时以上自动提炼记忆窗口;关闭后记忆窗口等下次聊天时提炼",
            checked = snapshot.memoryOvernight,
            onCheckedChange = { onToggle(KEY_MEMORY_OVERNIGHT, it) },
        )
        SwitchRow(
            title = "离线主动消息",
            subtitle = "开启后 TA 会在你离线时先发消息;关闭后 TA 不会在离线时先开口",
            checked = snapshot.proactiveOutbox,
            onCheckedChange = { onToggle(KEY_PROACTIVE_OUTBOX, it) },
        )
        SwitchRow(
            title = "手账正文自动生成",
            subtitle = "开启后自动生成正文；关闭后点开标题生成正文",
            checked = snapshot.handbookAuto,
            onCheckedChange = { onToggle(KEY_HANDBOOK_AUTO, it) },
        )
        SwitchRow(
            title = "小说正文自动生成",
            subtitle = "开启后自动生成正文；关闭后点开标题生成正文",
            checked = snapshot.novelAuto,
            onCheckedChange = { onToggle(KEY_NOVEL_AUTO, it) },
        )
        SwitchRow(
            title = "完成通知",
            subtitle = "关闭后生成完成仅 App 内可见,不弹系统通知;不消耗任何调用",
            checked = snapshot.notifyEnabled,
            onCheckedChange = { onToggle(KEY_NOTIFY_ENABLED, it) },
        )

        Spacer(Modifier.height(24.dp))
    }
}

/** 一行开关:标题 + 副标题(写清「开启后自动…,消耗大模型调用」)+ 右侧 Switch。 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

private val KEY_INNER_VOICE_AUTO = com.neethu.gouxiang.ai.GenerationPrefs.KEY_INNER_VOICE_AUTO
private val KEY_MOMENTS_DAILY = com.neethu.gouxiang.ai.GenerationPrefs.KEY_MOMENTS_DAILY
private val KEY_MEMORY_OVERNIGHT = com.neethu.gouxiang.ai.GenerationPrefs.KEY_MEMORY_OVERNIGHT
private val KEY_PROACTIVE_OUTBOX = com.neethu.gouxiang.ai.GenerationPrefs.KEY_PROACTIVE_OUTBOX
private val KEY_HANDBOOK_AUTO = com.neethu.gouxiang.ai.GenerationPrefs.KEY_HANDBOOK_AUTO
private val KEY_NOVEL_AUTO = com.neethu.gouxiang.ai.GenerationPrefs.KEY_NOVEL_AUTO
private val KEY_NOTIFY_ENABLED = com.neethu.gouxiang.ai.GenerationPrefs.KEY_NOTIFY_ENABLED
